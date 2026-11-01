package com.tvpad.wifi

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import java.net.Inet4Address

/** Finds Android TVs on the local Wi-Fi (mDNS `_androidtvremote2._tcp`). */
class Discovery(ctx: Context, private val onFound: (name: String, host: String) -> Unit) {
    private val nsd = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val main = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var listener: NsdManager.DiscoveryListener? = null

    fun start() {
        stop()
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                main.post {
                    queue.addLast(serviceInfo)
                    pump()
                }
            }
        }
        listener = l
        runCatching { nsd.discoverServices("_androidtvremote2._tcp.", NsdManager.PROTOCOL_DNS_SD, l) }
    }

    fun stop() {
        listener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        listener = null
    }

    // NsdManager can only resolve one service at a time.
    @Suppress("DEPRECATION")
    private fun pump() {
        if (resolving) return
        val info = queue.removeFirstOrNull() ?: return
        resolving = true
        nsd.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                main.post {
                    resolving = false
                    pump()
                }
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                val addr = serviceInfo.host
                main.post {
                    resolving = false
                    if (addr is Inet4Address) onFound(serviceInfo.serviceName, addr.hostAddress ?: "")
                    pump()
                }
            }
        })
    }
}
