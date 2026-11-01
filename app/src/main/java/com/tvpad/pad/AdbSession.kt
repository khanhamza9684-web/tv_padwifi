package com.tvpad.pad

import android.content.Context
import dadb.AdbKeyPair
import dadb.Dadb
import java.io.File
import java.io.IOException

/** ADB over the network (the TV's "USB debugging" setting, port 5555). */
class AdbSession(private val ctx: Context, private val host: String) {
    private var dadb: Dadb? = null

    fun connect() {
        val dir = File(ctx.filesDir, "adb").apply { mkdirs() }
        val priv = File(dir, "adbkey")
        val pub = File(dir, "adbkey.pub")
        if (!priv.exists() || !pub.exists()) AdbKeyPair.generate(priv, pub)
        val d = Dadb.create(host, 5555, AdbKeyPair.read(priv, pub))
        try {
            // Blocks until the TV's "Allow USB debugging?" prompt is accepted.
            d.shell("echo ok")
        } catch (e: Exception) {
            runCatching { d.close() }
            throw IOException("TV refused ADB. Is USB debugging on, and did you tap Allow? (${e.message})")
        }
        dadb = d
    }

    fun shell(cmd: String) {
        val d = dadb ?: throw IOException("Not connected")
        d.shell(cmd)
    }

    fun close() {
        runCatching { dadb?.close() }
        dadb = null
    }
}

/** Wi-Fi gamepad: sends button presses as gamepad key events. No analog sticks (ADB can't do them). */
class AdbPad(ctx: Context, host: String) : PadOutput {
    private val session = AdbSession(ctx, host)
    private val exec = java.util.concurrent.Executors.newSingleThreadExecutor()

    private val keys = mapOf(
        Btn.A to 96, Btn.B to 97, Btn.X to 99, Btn.Y to 100,
        Btn.L1 to 102, Btn.R1 to 103, Btn.L2 to 104, Btn.R2 to 105,
        Btn.L3 to 106, Btn.R3 to 107, Btn.START to 108, Btn.SELECT to 109, Btn.MODE to 110
    )

    fun connect() = session.connect()

    override fun update(s: PadState, prevButtons: Int, prevHat: Int) {
        val codes = mutableListOf<Int>()
        val newly = s.buttons and prevButtons.inv()
        for ((bit, code) in keys) if ((newly and (1 shl bit)) != 0) codes.add(code)
        if (s.hat != prevHat) {
            when (s.hat) {
                0 -> codes.add(19)
                1 -> { codes.add(19); codes.add(22) }
                2 -> codes.add(22)
                3 -> { codes.add(20); codes.add(22) }
                4 -> codes.add(20)
                5 -> { codes.add(20); codes.add(21) }
                6 -> codes.add(21)
                7 -> { codes.add(19); codes.add(21) }
            }
        }
        if (codes.isEmpty()) return
        val cmd = "input gamepad keyevent " + codes.joinToString(" ")
        exec.execute { runCatching { session.shell(cmd) } }
    }

    override fun close() {
        exec.execute { session.close() }
        exec.shutdown()
    }
}
