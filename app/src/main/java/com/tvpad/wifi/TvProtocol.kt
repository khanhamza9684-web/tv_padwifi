package com.tvpad.wifi

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.IOException
import java.math.BigInteger
import java.net.InetSocketAddress
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.util.Date
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import javax.security.auth.x500.X500Principal
import kotlin.concurrent.thread

/** Android KeyStore backed client certificate the TV remembers after pairing. */
object Identity {
    private const val ALIAS = "tvpad_wifi_client"

    private fun store(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun ensure() {
        if (store().containsAlias(ALIAS)) return
        val now = System.currentTimeMillis()
        val gen = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore")
        gen.initialize(
            KeyGenParameterSpec.Builder(
                ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            )
                .setKeySize(2048)
                .setDigests(
                    KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA1,
                    KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384,
                    KeyProperties.DIGEST_SHA512
                )
                .setSignaturePaddings(
                    KeyProperties.SIGNATURE_PADDING_RSA_PKCS1,
                    KeyProperties.SIGNATURE_PADDING_RSA_PSS
                )
                .setCertificateSubject(X500Principal("CN=TV Pad Wi-Fi"))
                .setCertificateSerialNumber(BigInteger.valueOf(now))
                .setCertificateNotBefore(Date(now - 86_400_000L))
                .setCertificateNotAfter(Date(now + 20L * 365 * 86_400_000L))
                .build()
        )
        gen.generateKeyPair()
    }

    fun cert(): X509Certificate {
        ensure()
        return store().getCertificate(ALIAS) as X509Certificate
    }

    /** TLS socket using our client cert. The TV's own cert is self-signed, so it is not validated. */
    fun open(host: String, port: Int): SSLSocket {
        ensure()
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(store(), null)
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(kmf.keyManagers, arrayOf(trustAll), null)
        val s = ctx.socketFactory.createSocket() as SSLSocket
        try {
            s.connect(InetSocketAddress(host, port), 8000)
            s.startHandshake()
        } catch (e: Exception) {
            runCatching { s.close() }
            throw e
        }
        return s
    }
}

/** Step one: the TV shows a 6 character code, step two: we prove we know it. Port 6467. */
class PairingSession(private val host: String) {
    private var sock: SSLSocket? = null

    private fun wrap(field: Int, body: PW) = PW().int(1, 2).int(2, 200).msg(field, body).frame()

    private fun readOk(): List<Field> {
        val f = parse(readFrame(sock!!.inputStream))
        val status = f.field(2)?.value ?: 0L
        if (status != 200L) throw IOException("TV refused pairing (status $status)")
        return f
    }

    fun start() {
        val s = Identity.open(host, 6467)
        sock = s
        val o = s.outputStream
        val enc = PW().int(1, 3).int(2, 6) // hexadecimal, 6 symbols
        o.write(wrap(10, PW().str(1, "atvremote").str(2, "TV Pad Wi-Fi"))); o.flush(); readOk()
        o.write(wrap(20, PW().msg(1, enc).int(3, 1))); o.flush(); readOk()
        o.write(wrap(30, PW().msg(1, enc).int(2, 1))); o.flush(); readOk()
    }

    fun finish(code: String) {
        val s = sock ?: throw IOException("Pairing not started")
        val c = code.trim().uppercase()
        require(Regex("[0-9A-F]{6}").matches(c)) { "Enter the 6 characters shown on the TV" }
        val alpha = ByteArray(3) { c.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

        val clientKey = Identity.cert().publicKey as RSAPublicKey
        val serverKey = s.session.peerCertificates[0].publicKey as RSAPublicKey
        val md = MessageDigest.getInstance("SHA-256")
        md.update(unsigned(clientKey.modulus))
        md.update(unsigned(clientKey.publicExponent))
        md.update(unsigned(serverKey.modulus))
        md.update(unsigned(serverKey.publicExponent))
        md.update(alpha, 1, 2)
        val secret = md.digest()

        s.outputStream.write(wrap(40, PW().bytes(1, secret)))
        s.outputStream.flush()
        readOk()
        close()
    }

    fun close() {
        runCatching { sock?.close() }
        sock = null
    }

    private fun unsigned(b: BigInteger): ByteArray {
        val a = b.toByteArray()
        return if (a.size > 1 && a[0] == 0.toByte()) a.copyOfRange(1, a.size) else a
    }
}

object Keys {
    const val POWER = 26
    const val HOME = 3
    const val BACK = 4
    const val MENU = 82
    const val UP = 19
    const val DOWN = 20
    const val LEFT = 21
    const val RIGHT = 22
    const val OK = 23
    const val VOL_UP = 24
    const val VOL_DOWN = 25
    const val MUTE = 164
    const val CH_UP = 166
    const val CH_DOWN = 167
    const val PLAY_PAUSE = 85
    const val INPUT = 178
    const val SETTINGS = 176
}

/** The live remote channel. Port 6466. */
class RemoteSession(private val host: String) {
    private var sock: SSLSocket? = null
    private val lock = Any()
    private val ready = CountDownLatch(1)

    @Volatile
    var open = false
        private set

    private fun send(msg: PW) = synchronized(lock) {
        val s = sock ?: throw IOException("Not connected")
        s.outputStream.write(msg.frame())
        s.outputStream.flush()
    }

    fun connect() {
        val s = Identity.open(host, 6466)
        sock = s
        open = true
        thread(isDaemon = true, name = "tv-remote-reader") { loop(s) }
        if (!ready.await(6, TimeUnit.SECONDS)) {
            close()
            throw IOException("TV didn't accept the connection. Try pairing again.")
        }
    }

    private fun loop(s: SSLSocket) {
        try {
            val input = s.inputStream
            while (true) {
                val f = parse(readFrame(input))
                when {
                    f.field(1) != null -> send(
                        PW().msg(
                            1, PW().int(1, 622).msg(
                                2, PW().str(1, "TV Pad").str(2, "TV Pad").int(3, 1).str(4, "1")
                                    .str(5, "com.tvpad.wifi").str(6, "1.0.0")
                            )
                        )
                    )
                    f.field(2) != null -> {
                        send(PW().msg(2, PW().int(1, 622)))
                        ready.countDown()
                    }
                    f.field(8) != null -> {
                        val v = parse(f.field(8)!!.data ?: ByteArray(0)).field(1)?.value ?: 0L
                        send(PW().msg(9, PW().int(1, v.toInt())))
                    }
                    f.field(3) != null -> break
                }
            }
        } catch (_: Exception) {
        } finally {
            open = false
            runCatching { s.close() }
        }
    }

    /** direction 3 = a single short press. */
    fun key(code: Int, direction: Int = 3) {
        send(PW().msg(10, PW().int(1, code).int(2, direction)))
    }

    fun close() {
        open = false
        runCatching { sock?.close() }
    }
}
