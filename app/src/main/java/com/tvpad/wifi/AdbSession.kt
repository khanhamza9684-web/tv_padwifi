package com.tvpad.wifi

import android.content.Context
import dadb.AdbKeyPair
import dadb.Dadb
import java.io.File
import java.io.IOException

/** Sends key presses to the TV with ADB over the network (the TV's "USB debugging" setting, port 5555). */
class AdbSession(private val ctx: Context, private val host: String) : KeySender {
    private var dadb: Dadb? = null

    override val open: Boolean get() = dadb != null

    override fun connect() {
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

    override fun key(code: Int) {
        val d = dadb ?: throw IOException("Not connected")
        try {
            d.shell("input keyevent $code")
        } catch (e: Exception) {
            dadb = null
            runCatching { d.close() }
            throw e
        }
    }

    override fun close() {
        runCatching { dadb?.close() }
        dadb = null
    }
}
