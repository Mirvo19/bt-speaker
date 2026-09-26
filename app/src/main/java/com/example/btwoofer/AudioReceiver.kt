package com.example.btwoofer

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.DefaultSSLWebSocketServerFactory
import org.java_websocket.server.WebSocketServer
import java.io.IOException
import java.math.BigInteger
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

class AudioReceiver(private val onStatusChanged: (String) -> Unit) {
    @Volatile private var running = false
    @Volatile private var server: WebSocketServer? = null
    @Volatile private var serverThread: Thread? = null
    private val playbackLock = Any()
    private var activeClient: WebSocket? = null
    private var audioTrack: AudioTrack? = null
    private var carriedBytes = ByteArray(0)
    private var hasReceivedAudio = false

    fun start(ipAddress: String) {
        if (running) return
        if (server != null) stop()
        if (ipAddress == "Unavailable - connect to Wi-Fi") {
            onStatusChanged("Error: connect to Wi-Fi first")
            return
        }
        running = true
        onStatusChanged("Starting")
        serverThread = Thread({
            try {
                val websocketServer = createWebSocketServer(ipAddress)
                server = websocketServer
                websocketServer.start()
            } catch (error: Exception) {
                if (running) {
                    running = false
                    onStatusChanged("Error: ${error.message ?: "could not start receiver"}")
                }
            }
        }, "AudioReceiverServer").also { it.start() }
    }

    fun stop() {
        if (!running && server == null) return
        running = false
        try {
            synchronized(playbackLock) {
                activeClient?.close(1001, "Receiver stopped")
                releasePlayback()
                activeClient = null
            }
            server?.stop(1000)
        } catch (_: Exception) {
        } finally {
            server = null
            onStatusChanged("Stopped")
        }
    }

    private fun createWebSocketServer(ipAddress: String): WebSocketServer {
        val websocketServer = object : WebSocketServer(InetSocketAddress("0.0.0.0", PORT)) {
            override fun onOpen(connection: WebSocket, handshake: ClientHandshake) {
                synchronized(playbackLock) {
                    if (!running) {
                        connection.close(1001, "Receiver stopped")
                        return
                    }
                    if (activeClient != null) {
                        connection.close(1008, "Only one sender is supported")
                        return
                    }
                    try {
                        activeClient = connection
                        audioTrack = createAudioTrack()
                        audioTrack?.play()
                        carriedBytes = ByteArray(0)
                        hasReceivedAudio = false
                        onStatusChanged("Connected")
                    } catch (error: Exception) {
                        releasePlayback()
                        activeClient = null
                        connection.close(1011, "Audio output unavailable")
                    }
                }
            }

            override fun onMessage(connection: WebSocket, message: ByteBuffer) {
                val packet = ByteArray(message.remaining())
                message.get(packet)
                playPcm(connection, packet)
            }

            override fun onMessage(connection: WebSocket, message: String) {
                connection.close(1003, "Binary PCM frames required")
            }

            override fun onClose(connection: WebSocket, code: Int, reason: String, remote: Boolean) {
                synchronized(playbackLock) {
                    if (activeClient === connection) {
                        releasePlayback()
                        activeClient = null
                        if (running) onStatusChanged("Waiting")
                    }
                }
            }

            override fun onError(connection: WebSocket?, error: Exception) {
                if (running && connection == null) {
                    running = false
                    onStatusChanged("Error: ${error.message ?: "WebSocket server error"}")
                }
            }

            override fun onStart() {
                if (running) onStatusChanged("Waiting")
            }
        }
        websocketServer.setWebSocketFactory(DefaultSSLWebSocketServerFactory(createSslContext(ipAddress)))
        return websocketServer
    }

    private fun playPcm(connection: WebSocket, packet: ByteArray) {
        synchronized(playbackLock) {
            if (!running || activeClient !== connection) return
            val combined = ByteArray(carriedBytes.size + packet.size)
            System.arraycopy(carriedBytes, 0, combined, 0, carriedBytes.size)
            System.arraycopy(packet, 0, combined, carriedBytes.size, packet.size)
            val alignedSize = combined.size - combined.size % BYTES_PER_FRAME
            try {
                var offset = 0
                while (offset < alignedSize) {
                    val written = audioTrack?.write(combined, offset, alignedSize - offset, AudioTrack.WRITE_BLOCKING)
                        ?: throw IOException("Audio output is unavailable")
                    if (written <= 0) throw IOException("Audio output write failed: $written")
                    offset += written
                }
                carriedBytes = combined.copyOfRange(alignedSize, combined.size)
                if (!hasReceivedAudio) {
                    hasReceivedAudio = true
                    onStatusChanged("Streaming")
                }
            } catch (_: Exception) {
                connection.close(1011, "Audio output failed")
            }
        }
    }

    private fun createAudioTrack(): AudioTrack {
        val minimum = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minimum <= 0) throw IOException("Unsupported playback format")
        val track = AudioTrack(
            AudioManager.STREAM_MUSIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minimum, PLAYBACK_BUFFER_BYTES),
            AudioTrack.MODE_STREAM
        )
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            throw IOException("Unable to initialize audio output")
        }
        return track
    }

    private fun releasePlayback() {
        val track = audioTrack
        audioTrack = null
        carriedBytes = ByteArray(0)
        hasReceivedAudio = false
        if (track != null) {
            try { track.pause() } catch (_: IllegalStateException) { }
            try { track.flush() } catch (_: IllegalStateException) { }
            track.release()
        }
    }

    private fun createSslContext(ipAddress: String): SSLContext {
        val keyPairGenerator = KeyPairGenerator.getInstance("RSA")
        keyPairGenerator.initialize(2048)
        val keyPair = keyPairGenerator.generateKeyPair()
        val subject = X500Name("CN=BT Woofer Audio Link")
        val now = System.currentTimeMillis()
        val certificateBuilder = JcaX509v3CertificateBuilder(
            subject,
            BigInteger(64, SecureRandom()),
            Date(now - CERTIFICATE_CLOCK_SKEW_MS),
            Date(now + CERTIFICATE_VALIDITY_MS),
            subject,
            keyPair.public
        ).addExtension(
            Extension.subjectAlternativeName,
            false,
            GeneralNames(GeneralName(GeneralName.iPAddress, ipAddress))
        )
        val certificate = JcaX509CertificateConverter().getCertificate(
            certificateBuilder.build(JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private))
        )
        certificate.verify(keyPair.public)

        val password = ByteArray(32).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }.toCharArray()
        val keyStore = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry("receiver", keyPair.private, password, arrayOf(certificate))
        }
        val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(keyStore, password)
        }
        return SSLContext.getInstance("TLS").apply { init(keyManagers.keyManagers, null, SecureRandom()) }
    }

    companion object {
        const val PORT = 50005
        private const val SAMPLE_RATE = 44_100
        private const val BYTES_PER_FRAME = 4
        private const val PLAYBACK_BUFFER_BYTES = 8_192
        private const val CERTIFICATE_CLOCK_SKEW_MS = 60_000L
        private const val CERTIFICATE_VALIDITY_MS = 365L * 24 * 60 * 60 * 1000
    }
}
