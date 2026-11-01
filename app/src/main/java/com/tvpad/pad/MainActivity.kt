package com.tvpad.pad

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainVM(app: Application) : AndroidViewModel(app) {
    enum class Screen { HOME, BT, ADB, PAD }

    private val prefs = app.getSharedPreferences("tvpad", Context.MODE_PRIVATE)

    var screen by mutableStateOf(Screen.HOME)
        private set
    var host by mutableStateOf(prefs.getString("host", "") ?: "")
    var status by mutableStateOf("")
        private set
    var busy by mutableStateOf(false)
        private set
    var bt by mutableStateOf<BluetoothPad?>(null)
        private set
    var pad by mutableStateOf<PadController?>(null)
        private set
    var padTitle by mutableStateOf("")
        private set

    fun openBt() {
        screen = Screen.BT
    }

    fun openAdb() {
        screen = Screen.ADB
    }

    fun startBt() {
        if (bt == null) bt = BluetoothPad(getApplication()).also { it.start() }
    }

    fun enterBtPad() {
        val b = bt ?: return
        if (pad != null) return
        pad = PadController(b)
        padTitle = "Bluetooth"
        screen = Screen.PAD
    }

    fun connectAdb(input: String) {
        val ip = input.trim()
        if (ip.isEmpty()) {
            status = "Type the TV's IP address (the TV Pad Helper app on the TV shows it)"
            return
        }
        host = ip
        prefs.edit().putString("host", ip).apply()
        viewModelScope.launch(Dispatchers.IO) {
            busy = true
            status = "Connecting... look at the TV and tap Allow (tick Always allow)"
            try {
                val a = AdbPad(getApplication(), ip)
                a.connect()
                pad = PadController(a)
                padTitle = "Wi-Fi (ADB)"
                screen = Screen.PAD
                status = ""
            } catch (e: Exception) {
                status = "Couldn't connect: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                busy = false
            }
        }
    }

    fun home() {
        pad?.close()
        pad = null
        bt?.stop()
        bt = null
        status = ""
        screen = Screen.HOME
    }

    override fun onCleared() {
        pad?.close()
        bt?.stop()
    }
}

class MainActivity : ComponentActivity() {
    private val vm: MainVM by viewModels()

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.startBt() }

    private fun ensureBtPerms() {
        val need = if (Build.VERSION.SDK_INT >= 31) {
            listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
                .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        } else {
            emptyList()
        }
        if (need.isEmpty()) vm.startBt() else permLauncher.launch(need.toTypedArray())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    when (vm.screen) {
                        MainVM.Screen.HOME -> HomeScreen(vm) {
                            vm.openBt()
                            ensureBtPerms()
                        }
                        MainVM.Screen.BT -> BtScreen(vm) { ensureBtPerms() }
                        MainVM.Screen.ADB -> AdbScreen(vm)
                        MainVM.Screen.PAD -> vm.pad?.let { p ->
                            BackHandler { vm.home() }
                            PadScreen(p, vm.padTitle) { vm.home() }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Page(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) { content() }
    }
}

@Composable
private fun HomeScreen(vm: MainVM, onBluetooth: () -> Unit) = Page {
    Text("TV Pad", fontSize = 28.sp, fontWeight = FontWeight.Bold)
    Text("Use your phone as a gamepad for your TV.")
    Button(onClick = onBluetooth, modifier = Modifier.fillMaxWidth()) { Text("Bluetooth gamepad (full gamepad, sticks)") }
    Text("Works like a real controller in games. Needs Android 9 or newer.")
    OutlinedButton(onClick = { vm.openAdb() }, modifier = Modifier.fillMaxWidth()) { Text("Wi-Fi gamepad (ADB)") }
    Text("Buttons only, no sticks, a little lag. Needs USB debugging on the TV.")
    Text("Tip: install TV Pad Helper on the TV. It shows the TV's IP address and tests your buttons.")
}

@Composable
private fun BtScreen(vm: MainVM, retry: () -> Unit) = Page {
    val ctx = LocalContext.current
    val bt = vm.bt
    LaunchedEffect(bt?.connected) {
        if (bt?.connected == true) vm.enterBtPad()
    }
    Text("Bluetooth gamepad", fontSize = 24.sp, fontWeight = FontWeight.Bold)
    Text(bt?.status ?: "Allow the Bluetooth permission to continue.")
    if (bt == null) Button(onClick = retry) { Text("Allow Bluetooth") }
    Text(
        "1. On the TV: Settings > Remotes & Accessories > Add accessory. The TV starts searching.\n" +
            "2. Tap \"Make phone visible\" below.\n" +
            "3. Pick this phone on the TV and accept the pairing request on both.\n" +
            "The pad opens by itself once connected."
    )
    Button(
        onClick = {
            ctx.startActivity(
                Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                    .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
            )
        },
        modifier = Modifier.fillMaxWidth()
    ) { Text("Make phone visible") }
    OutlinedButton(
        onClick = { ctx.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) },
        modifier = Modifier.fillMaxWidth()
    ) { Text("Open Bluetooth settings") }
    val paired = bt?.bonded().orEmpty()
    if (paired.isNotEmpty()) {
        Text("Already paired with the TV before? Tap it:")
        paired.forEach { d ->
            OutlinedButton(onClick = { bt?.connect(d) }, modifier = Modifier.fillMaxWidth()) {
                Text(runCatching { d.name }.getOrNull() ?: d.address)
            }
        }
    }
    OutlinedButton(onClick = { vm.home() }, modifier = Modifier.fillMaxWidth()) { Text("Back") }
}

@Composable
private fun AdbScreen(vm: MainVM) = Page {
    Text("Wi-Fi gamepad (ADB)", fontSize = 24.sp, fontWeight = FontWeight.Bold)
    Text(
        "On the TV: Settings > About > tap Build 7 times. Then Developer options > turn on USB debugging " +
            "(and network debugging if shown). Phone and TV must be on the same Wi-Fi."
    )
    OutlinedTextField(
        value = vm.host,
        onValueChange = { vm.host = it },
        label = { Text("TV IP address (e.g. 192.168.1.20)") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    Button(onClick = { vm.connectAdb(vm.host) }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) {
        Text("Connect")
    }
    if (vm.status.isNotEmpty()) Text(vm.status)
    OutlinedButton(onClick = { vm.home() }, modifier = Modifier.fillMaxWidth()) { Text("Back") }
}
