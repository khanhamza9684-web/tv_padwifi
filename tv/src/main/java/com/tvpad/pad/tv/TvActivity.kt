package com.tvpad.pad.tv

import android.app.Activity
import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Shows the TV's IP address (needed for the Wi-Fi/ADB mode), explains how to pair the phone,
 * and lists every gamepad button or stick the phone sends so you can check the connection.
 */
class TvActivity : Activity() {
    private lateinit var log: TextView
    private val lines = ArrayDeque<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 48, 64, 48)
            layoutParams = ViewGroup.LayoutParams(-1, -1)
        }
        val info = TextView(this).apply {
            textSize = 22f
            text = "TV Pad Helper\n\n" +
                "TV IP address: ${ip() ?: "not connected to Wi-Fi"}\n\n" +
                "Bluetooth: TV Settings > Remotes & Accessories > Add accessory. " +
                "Then on the phone open TV Pad > Bluetooth gamepad.\n" +
                "Wi-Fi (ADB): turn on USB debugging in Developer options and type the IP above in the phone app.\n\n" +
                "Press buttons on the phone pad. They show here:"
        }
        log = TextView(this).apply {
            textSize = 26f
            text = "(waiting for input)"
        }
        root.addView(info)
        root.addView(log)
        setContentView(root)
    }

    private fun ip(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { !it.isLoopbackAddress && it is Inet4Address }
            ?.hostAddress
    }.getOrNull()

    private fun show(line: String) {
        lines.addFirst(line)
        while (lines.size > 8) lines.removeLast()
        log.text = lines.joinToString("\n")
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (event.repeatCount == 0) show("${KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_")} pressed")
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        show("${KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_")} released")
        return true
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if ((event.source and InputDevice.SOURCE_JOYSTICK) != 0 && event.action == MotionEvent.ACTION_MOVE) {
            val lx = event.getAxisValue(MotionEvent.AXIS_X)
            val ly = event.getAxisValue(MotionEvent.AXIS_Y)
            val rx = event.getAxisValue(MotionEvent.AXIS_Z)
            val ry = event.getAxisValue(MotionEvent.AXIS_RZ)
            show("Sticks  L(%.2f, %.2f)  R(%.2f, %.2f)".format(lx, ly, rx, ry))
            return true
        }
        return super.onGenericMotionEvent(event)
    }
}
