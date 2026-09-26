package com.example.btwoofer

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

class AudioReceiver(private val onStatusChanged: (String) -> Unit) {
    @Volatile private var running = false
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var clientSocket: Socket? = null
    private var serverThread: Thread? = null

    fun start() {
        if (running) return
        running = true
        onStatusChanged("Starting")
        serverThread = Thread(::listen, "AudioReceiverServer").also { it.start() }
    }

    fun stop() {
        running = false
        try { clientSocket?.close() } catch (_: IOException) { }
        try { serverSocket?.close() } catch (_: IOException) { }
        onStatusChanged("Stopped")
    }

    private fun listen() {
        try {
            val server = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(PORT))
            }
            serverSocket = server
            if (!running) return
            onStatusChanged("Listening")
            while (running) {
                try {
                    val client = server.accept()
                    clientSocket = client
                    onStatusChanged("Connected")
                    try {
                        playClient(client)
                    } catch (_: IOException) {
                        if (running) onStatusChanged("Disconnected")
                    } finally {
                        try { client.close() } catch (_: IOException) { }
                    }
                    clientSocket = null
                    if (running) onStatusChanged("Listening")
                } catch (error: IOException) {
                    if (running) throw error
                }
            }
        } catch (_: IOException) {
            if (running) onStatusChanged("Server error")
        } finally {
            running = false
            try { clientSocket?.close() } catch (_: IOException) { }
            try { serverSocket?.close() } catch (_: IOException) { }
            clientSocket = null
            serverSocket = null
            onStatusChanged("Stopped")
        }
    }

    private fun playClient(client: Socket) {
        val minimum = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minimum <= 0) throw IOException("Unsupported playback format")
        val track = AudioTrack(
            AudioManager.STREAM_MUSIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minimum * 2, PLAYBACK_BUFFER_BYTES),
            AudioTrack.MODE_STREAM
        )
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            throw IOException("Unable to initialize audio output")
        }

        try {
            track.play()
            val input = client.getInputStream()
            val buffer = ByteArray(READ_BUFFER_BYTES + 4)
            var carry = 0
            while (running && !client.isClosed) {
                val count = input.read(buffer, carry, READ_BUFFER_BYTES)
                if (count < 0) break
                val available = carry + count
                val aligned = available - available % BYTES_PER_FRAME
                var offset = 0
                while (offset < aligned && running) {
                    val written = track.write(buffer, offset, aligned - offset)
                    if (written <= 0) throw IOException("Audio output write failed: $written")
                    offset += written
                }
                carry = available - aligned
                if (carry > 0) System.arraycopy(buffer, aligned, buffer, 0, carry)
            }
        } finally {
            try { track.stop() } catch (_: IllegalStateException) { }
            track.flush()
            track.release()
        }
    }

    companion object {
        const val PORT = 50005
        private const val SAMPLE_RATE = 44_100
        private const val BYTES_PER_FRAME = 4
        private const val READ_BUFFER_BYTES = 4_096
        private const val PLAYBACK_BUFFER_BYTES = 8_192
    }
}