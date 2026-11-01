package com.tvpad.wifi

import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainVM(app: Application) : AndroidViewModel(app) {
    enum class Screen { CONNECT, CODE, REMOTE }

    private val prefs = app.getSharedPreferences("tvpad", Context.MODE_PRIVATE)

    var screen by mutableStateOf(Screen.CONNECT)
        private set
    var host by mutableStateOf(prefs.getString("host", "") ?: "")
    var status by mutableStateOf("")
        private set
    var busy by mutableStateOf(false)
        private set
    val found = mutableStateListOf<Pair<String, String>>()

    private var pairing: PairingSession? = null
    private var remote: RemoteSession? = null
    private val discovery = Discovery(app) { name, ip ->
        if (found.none { it.second == ip }) found.add(name to ip)
    }

    init {
        discovery.start()
    }

    private fun isPaired(ip: String) = prefs.getStringSet("paired", emptySet())!!.contains(ip)

    private fun markPaired(ip: String) {
        val set = prefs.getStringSet("paired", emptySet())!!.toMutableSet()
        set.add(ip)
        prefs.edit().putStringSet("paired", set).apply()
    }

    fun connect(input: String, forcePair: Boolean = false) {
        val ip = input.trim()
        if (ip.isEmpty()) {
            status = "Pick your TV or type its IP address"
            return
        }
        host = ip
        prefs.edit().putString("host", ip).apply()
        val paired = isPaired(ip) && !forcePair
        viewModelScope.launch(Dispatchers.IO) {
            busy = true
            status = if (paired) "Connecting..." else "Asking the TV to pair..."
            try {
                if (paired) {
                    remote = RemoteSession(ip).also { it.connect() }
                    screen = Screen.REMOTE
                } else {
                    pairing?.close()
                    pairing = PairingSession(ip).also { it.start() }
                    screen = Screen.CODE
                }
                status = ""
            } catch (e: Exception) {
                status = "Couldn't connect: ${e.message ?: e.javaClass.simpleName}" +
                    if (paired) "\nIf you reset the TV, use \"Pair again\"." else ""
            } finally {
                busy = false
            }
        }
    }

    fun submitCode(code: String) {
        viewModelScope.launch(Dispatchers.IO) {
            busy = true
            status = "Checking code..."
            try {
                pairing!!.finish(code)
                markPaired(host)
                remote = RemoteSession(host).also { it.connect() }
                screen = Screen.REMOTE
                status = ""
            } catch (e: Exception) {
                status = "Pairing failed: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                busy = false
            }
        }
    }

    @Synchronized
    private fun session(): RemoteSession {
        val r = remote
        if (r != null && r.open) return r
        return RemoteSession(host).also {
            it.connect()
            remote = it
        }
    }

    fun key(code: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                session().key(code)
                status = ""
            } catch (e: Exception) {
                status = "Lost connection: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    fun disconnect() {
        pairing?.close()
        remote?.close()
        remote = null
        status = ""
        screen = Screen.CONNECT
    }

    override fun onCleared() {
        discovery.stop()
        pairing?.close()
        remote?.close()
    }
}

class MainActivity : ComponentActivity() {
    private val vm: MainVM by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    Box(Modifier.statusBarsPadding().navigationBarsPadding()) {
                        when (vm.screen) {
                            MainVM.Screen.CONNECT -> ConnectScreen(vm)
                            MainVM.Screen.CODE -> CodeScreen(vm)
                            MainVM.Screen.REMOTE -> RemoteScreen(vm)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectScreen(vm: MainVM) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("TV Pad Wi-Fi", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text("Phone and TV must be on the same Wi-Fi.")

        Text("TVs found", fontWeight = FontWeight.SemiBold)
        if (vm.found.isEmpty()) Text("Searching...")
        vm.found.forEach { (name, ip) ->
            OutlinedButton(onClick = { vm.connect(ip) }, modifier = Modifier.fillMaxWidth()) {
                Text("$name  ($ip)")
            }
        }

        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = vm.host,
            onValueChange = { vm.host = it },
            label = { Text("TV IP address (e.g. 192.168.1.20)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Button(onClick = { vm.connect(vm.host) }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) {
            Text("Connect")
        }
        OutlinedButton(
            onClick = { vm.connect(vm.host, forcePair = true) },
            enabled = !vm.busy,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Pair again") }
        if (vm.status.isNotEmpty()) Text(vm.status)
    }
}

@Composable
private fun CodeScreen(vm: MainVM) {
    var code by remember { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Enter the code", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text("Your TV is showing a 6 character code (0-9, A-F). Type it here.")
        OutlinedTextField(
            value = code,
            onValueChange = { code = it.uppercase().filter { c -> c.isLetterOrDigit() }.take(6) },
            label = { Text("Code") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = { vm.submitCode(code) },
            enabled = code.length == 6 && !vm.busy,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Pair") }
        OutlinedButton(onClick = { vm.disconnect() }, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
        if (vm.status.isNotEmpty()) Text(vm.status)
    }
}

@Composable
private fun RemoteScreen(vm: MainVM) {
    BackHandler { vm.disconnect() }
    val haptic = LocalHapticFeedback.current
    fun press(code: Int) {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        vm.key(code)
    }

    @Composable
    fun RowScope.Key(label: String, code: Int?, primary: Boolean = false) {
        val mod = Modifier.weight(1f).height(60.dp)
        if (code == null) {
            Spacer(mod)
        } else if (primary) {
            Button(onClick = { press(code) }, modifier = mod) { Text(label, fontSize = 18.sp) }
        } else {
            FilledTonalButton(onClick = { press(code) }, modifier = mod) { Text(label, fontSize = 16.sp) }
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Key("Power", Keys.POWER)
            Key("Input", Keys.INPUT)
            Key("Settings", Keys.SETTINGS)
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Key("", null); Key("▲", Keys.UP); Key("", null)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Key("◀", Keys.LEFT); Key("OK", Keys.OK, primary = true); Key("▶", Keys.RIGHT)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Key("", null); Key("▼", Keys.DOWN); Key("", null)
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Key("Back", Keys.BACK); Key("Home", Keys.HOME); Key("Menu", Keys.MENU)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Key("Vol +", Keys.VOL_UP); Key("Mute", Keys.MUTE); Key("Ch +", Keys.CH_UP)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Key("Vol -", Keys.VOL_DOWN); Key("Play/Pause", Keys.PLAY_PAUSE); Key("Ch -", Keys.CH_DOWN)
        }
        if (vm.status.isNotEmpty()) Text(vm.status)
        OutlinedButton(onClick = { vm.disconnect() }) { Text("Change TV") }
    }
}
