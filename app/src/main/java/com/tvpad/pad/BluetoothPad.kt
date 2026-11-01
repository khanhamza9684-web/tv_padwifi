package com.tvpad.pad

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.concurrent.Executors

object HidDescriptor {
    /** Gamepad: 16 buttons, hat switch, left stick (X, Y) and right stick (Z, Rz). One 7 byte report, no report id. */
    val GAMEPAD = intArrayOf(
        0x05, 0x01, 0x09, 0x05, 0xA1, 0x01,
        0x05, 0x09, 0x19, 0x01, 0x29, 0x10, 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x10, 0x81, 0x02,
        0x05, 0x01, 0x09, 0x39, 0x15, 0x00, 0x25, 0x07, 0x35, 0x00, 0x46, 0x3B, 0x01, 0x65, 0x14,
        0x75, 0x04, 0x95, 0x01, 0x81, 0x42,
        0x75, 0x04, 0x95, 0x01, 0x81, 0x03,
        0x35, 0x00, 0x45, 0x00, 0x65, 0x00,
        0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x09, 0x32, 0x09, 0x35,
        0x15, 0x81, 0x25, 0x7F, 0x75, 0x08, 0x95, 0x04, 0x81, 0x02,
        0xC0
    ).map { it.toByte() }.toByteArray()
}

/** Makes the phone look like a Bluetooth gamepad to the TV (Bluetooth HID device profile, Android 9+). */
@SuppressLint("MissingPermission")
class BluetoothPad(private val ctx: Context) : PadOutput {
    var status by mutableStateOf("Starting Bluetooth...")
        private set
    var connected by mutableStateOf(false)
        private set

    private val adapter: BluetoothAdapter? =
        (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private val exec = Executors.newSingleThreadExecutor()
    private var hid: BluetoothHidDevice? = null
    @Volatile
    private var host: BluetoothDevice? = null

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            status = if (registered) "Ready. Pair from the TV, or tap the TV below." else "Gamepad not registered"
        }

        override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    host = device
                    status = "Connected to ${runCatching { device?.name }.getOrNull() ?: "TV"}"
                    connected = true
                }
                BluetoothProfile.STATE_CONNECTING -> status = "Connecting..."
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (host == device) host = null
                    connected = false
                    status = "Disconnected"
                }
            }
        }
    }

    fun start() {
        val a = adapter
        if (a == null || !a.isEnabled) {
            status = "Turn Bluetooth on, then come back."
            return
        }
        val ok = a.getProfileProxy(ctx, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                val h = proxy as BluetoothHidDevice
                hid = h
                val sdp = BluetoothHidDeviceAppSdpSettings(
                    "TV Pad", "Phone gamepad", "TV Pad", 0x08.toByte(), HidDescriptor.GAMEPAD
                )
                val registered = runCatching { h.registerApp(sdp, null, null, exec, callback) }.getOrDefault(false)
                if (!registered) status = "Could not start the gamepad. Another app may be using Bluetooth HID."
            }

            override fun onServiceDisconnected(profile: Int) {
                hid = null
            }
        }, BluetoothProfile.HID_DEVICE)
        if (!ok) status = "This phone doesn't support Bluetooth gamepad mode."
    }

    /** Devices already paired with this phone (your TV, if you paired it before). */
    fun bonded(): List<BluetoothDevice> = runCatching { adapter?.bondedDevices?.toList() }.getOrNull() ?: emptyList()

    fun connect(d: BluetoothDevice) {
        status = "Connecting..."
        runCatching { hid?.connect(d) }
    }

    override fun update(s: PadState, prevButtons: Int, prevHat: Int) {
        val h = host ?: return
        val data = s.report()
        exec.execute { runCatching { hid?.sendReport(h, 0, data) } }
    }

    fun stop() {
        runCatching { host?.let { hid?.disconnect(it) } }
        runCatching { hid?.unregisterApp() }
        runCatching { adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, hid) }
        hid = null
        host = null
        connected = false
    }

    override fun close() = stop()
}
