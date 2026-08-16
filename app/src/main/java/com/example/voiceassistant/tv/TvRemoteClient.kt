package com.example.voiceassistant.tv

import android.content.Context
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.security.cert.X509Certificate
import java.util.concurrent.Executors
import javax.net.ssl.SSLSocket

/**
 * Native Kotlin implementation of the Android TV Remote protocol v2 - the same
 * TLS + protobuf protocol the official Google TV app uses to control a TV over
 * the local network. No PC, no ADB, no developer mode - just this device and
 * the TV on the same Wi-Fi.
 *
 * Two channels, both plain TLS sockets to the TV's local IP:
 *  - port 6467: one-time pairing handshake (startPairing / submitPairingCode)
 *  - port 6466: persistent command channel, opened automatically once paired
 */
class TvRemoteClient(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("tv_remote", Context.MODE_PRIVATE)
    private val TAG = "TvRemoteClient"
    private val io = Executors.newCachedThreadPool()

    // Feature bits this client requests. See remotemessage.proto / the Feature enum:
    // PING(1) | KEY(2) | POWER(32) | VOLUME(64) | APP_LINK(512)
    private val desiredFeatures = 1 or 2 or 32 or 64 or 512

    var host: String?
        get() = prefs.getString("host", null)
        private set(value) { prefs.edit().putString("host", value).apply() }

    val isPaired: Boolean get() = prefs.getBoolean("paired", false)
    val isConnected: Boolean get() = ready

    @Volatile private var remoteSocket: SSLSocket? = null
    @Volatile private var remoteOut: OutputStream? = null
    @Volatile private var activeFeatures = 0
    @Volatile private var ready = false
    private val writeLock = Any()
    private var pairingSocket: SSLSocket? = null

    // ---- Pairing (port 6467) --------------------------------------------------

    /** Step 1: connect and start pairing. On success the TV displays a 6-digit hex code. */
    fun startPairing(tvHost: String, onCodeShown: () -> Unit, onError: (String) -> Unit) {
        io.execute {
            try {
                val socket = TvKeyStore.buildSslContext(appContext).socketFactory.createSocket(tvHost, 6467) as SSLSocket
                socket.startHandshake()
                pairingSocket = socket
                host = tvHost

                val out = socket.outputStream
                val din = DataInputStream(socket.inputStream)
                writeFrame(out, TvProtocol.pairingRequest("Voice Assistant"))

                while (true) {
                    val frame = readFrame(din) ?: throw IOException("Connection closed during pairing")
                    when (TvProtocol.parsePairingResponse(frame)) {
                        TvProtocol.PairingPhase.REQUEST_ACK -> writeFrame(out, TvProtocol.pairingOptions())
                        TvProtocol.PairingPhase.OPTIONS -> writeFrame(out, TvProtocol.pairingConfiguration())
                        TvProtocol.PairingPhase.CONFIGURATION_ACK -> {
                            onCodeShown()
                            return@execute
                        }
                        TvProtocol.PairingPhase.ERROR -> throw IOException("TV rejected the pairing request")
                        else -> Unit
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "startPairing failed", e)
                onError(e.message ?: "Couldn't reach the TV to start pairing")
            }
        }
    }

    /** Step 2: submit the code shown on the TV screen. */
    fun submitPairingCode(code: String, onResult: (Boolean, String) -> Unit) {
        val socket = pairingSocket
        if (socket == null) {
            onResult(false, "Call startPairing first")
            return
        }
        io.execute {
            try {
                val serverCert = socket.session.peerCertificates[0] as X509Certificate
                val clientCert = TvKeyStore.clientCertificate(appContext)
                val secret = PairingSecret.computeSecret(clientCert, serverCert, code.trim())
                if (secret == null) {
                    onResult(false, "That code doesn't match - check it and try again")
                    return@execute
                }

                val out = socket.outputStream
                val din = DataInputStream(socket.inputStream)
                writeFrame(out, TvProtocol.pairingSecret(secret))
                val frame = readFrame(din) ?: throw IOException("Connection closed while finishing pairing")

                if (TvProtocol.parsePairingResponse(frame) == TvProtocol.PairingPhase.SECRET_ACK) {
                    prefs.edit().putBoolean("paired", true).apply()
                    socket.close()
                    pairingSocket = null
                    onResult(true, "Paired! Connecting to the TV...")
                    connect {}
                } else {
                    onResult(false, "TV rejected the code")
                }
            } catch (e: Exception) {
                Log.e(TAG, "submitPairingCode failed", e)
                onResult(false, e.message ?: "Pairing failed")
            }
        }
    }

    // ---- Command channel (port 6466) -------------------------------------------

    fun connect(onReady: () -> Unit) {
        val tvHost = host ?: return
        val existing = remoteSocket
        if (existing != null && existing.isConnected && !existing.isClosed) return
        io.execute { connectBlocking(tvHost, onReady) }
    }

    private fun connectBlocking(tvHost: String, onReady: () -> Unit) {
        try {
            val socket = TvKeyStore.buildSslContext(appContext).socketFactory.createSocket(tvHost, 6466) as SSLSocket
            socket.startHandshake()
            remoteSocket = socket
            remoteOut = socket.outputStream
            ready = false
            val din = DataInputStream(socket.inputStream)

            while (true) {
                val frame = readFrame(din) ?: break
                when (val event = TvProtocol.parseRemoteMessage(frame)) {
                    is TvProtocol.RemoteEvent.Configure -> {
                        activeFeatures = event.code1 and desiredFeatures
                        writeFrame(socket.outputStream, TvProtocol.configureAck(activeFeatures))
                    }
                    is TvProtocol.RemoteEvent.SetActive ->
                        writeFrame(socket.outputStream, TvProtocol.setActiveAck(activeFeatures))
                    is TvProtocol.RemoteEvent.PingRequest ->
                        writeFrame(socket.outputStream, TvProtocol.pingResponse(event.val1))
                    is TvProtocol.RemoteEvent.Start -> if (!ready) {
                        ready = true
                        onReady()
                    }
                    else -> Unit
                }
            }
        } catch (e: Exception) {
            // Falls through to the reconnect below (TV off, out of range, network change, etc).
            Log.w(TAG, "connectBlocking dropped: ${e.message}")
        } finally {
            ready = false
            remoteSocket = null
            remoteOut = null
            if (isPaired) {
                Thread.sleep(2000)
                io.execute { connectBlocking(tvHost, onReady) }
            }
        }
    }

    fun sendKey(keyCode: Int, onResult: (Boolean, String) -> Unit) = send(TvProtocol.keyInject(keyCode), onResult)

    fun sendAppLink(appLinkOrId: String, onResult: (Boolean, String) -> Unit) =
        send(TvProtocol.appLinkLaunch(appLinkOrId), onResult)

    private fun send(frame: ByteArray, onResult: (Boolean, String) -> Unit) {
        val out = remoteOut
        if (out == null || !ready) {
            onResult(false, "The TV isn't connected. Make sure it's on and on the same WiFi.")
            return
        }
        io.execute {
            try {
                writeFrame(out, frame)
                onResult(true, "ok")
            } catch (e: Exception) {
                onResult(false, "Couldn't reach the TV: ${e.message}")
            }
        }
    }

    // ---- Framing: varint length prefix + protobuf bytes, same as the official app ----

    private fun writeFrame(out: OutputStream, message: ByteArray) {
        synchronized(writeLock) {
            val header = ByteArrayOutputStream()
            ProtoWire.writeVarint(header, message.size.toLong())
            out.write(header.toByteArray())
            out.write(message)
            out.flush()
        }
    }

    private fun readFrame(din: DataInputStream): ByteArray? {
        return try {
            var result = 0L
            var shift = 0
            while (true) {
                val b = din.readUnsignedByte()
                result = result or ((b.toLong() and 0x7F) shl shift)
                if (b and 0x80 == 0) break
                shift += 7
            }
            val buf = ByteArray(result.toInt())
            din.readFully(buf)
            buf
        } catch (e: IOException) {
            null
        }
    }
}