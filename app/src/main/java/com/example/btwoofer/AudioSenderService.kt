package com.example.btwoofer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.Manifest
import android.annotation.RequiresApi
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

class AudioSenderService : Service() {
    @Volatile private var running = false
    @Volatile private var destroyed = false
    @Volatile private var socket: Socket? = null
    private var captureThread: Thread? = null
    private var projection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var receiverHost = ""

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            setStatus("Sender requires Android 10 or newer")
            stopSelf()
            return START_NOT_STICKY
        }
        val host = intent.getStringExtra(EXTRA_HOST).orEmpty()
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val projectionData = intent.getParcelableExtra<Intent>(EXTRA_PROJECTION_DATA)
        if (host.isBlank() || projectionData == null || running) {
            stopSelf()
            return START_NOT_STICKY
        }
        receiverHost = host

        createNotificationChannel()
        setStatus("Connecting")
        val notification = buildNotification("Connecting to $host")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        try {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(resultCode, projectionData)
            projection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    stopSelf()
                }
            }, Handler(Looper.getMainLooper()))
            startCapture(host)
        } catch (_: SecurityException) {
            setStatus("Capture permission failed")
            stopSelf()
        } catch (_: Exception) {
            setStatus("Capture failed")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun startCapture(host: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            throw SecurityException("Audio recording permission was revoked")
        }
        val captureProjection = projection ?: throw IllegalStateException("Missing media projection")
        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(captureProjection)
            .addMatchingUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(android.media.AudioAttributes.USAGE_GAME)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
        val minimum = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minimum <= 0) throw IllegalStateException("Unsupported audio capture format")
        val bufferBytes = maxOf(minimum, READ_BUFFER_BYTES)
        val record = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufferBytes)
            .setAudioPlaybackCaptureConfig(captureConfig)
            .build()
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException("Unable to initialize audio capture")
        }
        audioRecord = record
        record.startRecording()
        running = true
        captureThread = Thread({ streamAudio(host, record) }, "AudioCaptureStream").also { it.start() }
    }

    private fun streamAudio(host: String, record: AudioRecord) {
        val buffer = ByteArray(READ_BUFFER_BYTES)
        var output: java.io.OutputStream? = null
        var nextConnectAttempt = 0L
        var wasConnected = false
        try {
            while (running) {
                if (socket == null && System.currentTimeMillis() >= nextConnectAttempt) {
                    setStatus("Connecting")
                    nextConnectAttempt = System.currentTimeMillis() + RECONNECT_DELAY_MS
                    try {
                        val newSocket = Socket()
                        newSocket.tcpNoDelay = true
                        newSocket.sendBufferSize = SOCKET_BUFFER_BYTES
                        newSocket.connect(InetSocketAddress(host, AudioReceiver.PORT), CONNECT_TIMEOUT_MS)
                        if (!running) {
                            newSocket.close()
                            break
                        }
                        socket = newSocket
                        output = newSocket.getOutputStream()
                        wasConnected = true
                        setStatus("Streaming")
                    } catch (_: IOException) {
                        closeConnection()
                        if (wasConnected) setStatus("Disconnected; retrying")
                        else setStatus("Receiver unavailable; retrying")
                    }
                }

                val count = record.read(buffer, 0, buffer.size)
                if (count > 0 && socket != null) {
                    try {
                        output?.write(buffer, 0, count)
                    } catch (_: IOException) {
                        closeConnection()
                        setStatus("Disconnected; retrying")
                    }
                } else if (count < 0) {
                    throw IOException("Audio capture read failed: $count")
                }
            }
        } catch (_: Exception) {
            if (running) setStatus("Capture stopped")
        } finally {
            closeConnection()
            try { record.stop() } catch (_: IllegalStateException) { }
            record.release()
            if (audioRecord === record) audioRecord = null
            running = false
            setStatus("Stopped")
            stopSelf()
        }
    }

    private fun closeConnection() {
        try { socket?.close() } catch (_: IOException) { }
        socket = null
    }

    private fun setStatus(status: String) {
        getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit().putString(STATUS_KEY, status).apply()
        if (destroyed) return
        val notification = buildNotification("$status - $receiverHost")
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Audio streaming", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(message: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Streaming audio")
            .setContentText(message)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        destroyed = true
        running = false
        closeConnection()
        try { audioRecord?.stop() } catch (_: IllegalStateException) { }
        projection?.stop()
        projection = null
        getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit().putString(STATUS_KEY, "Stopped").apply()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
        else @Suppress("DEPRECATION") stopForeground(true)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val PREFERENCES = "audio_sender"
        const val STATUS_KEY = "status"
        const val EXTRA_RESULT_CODE = "projection_result_code"
        const val EXTRA_PROJECTION_DATA = "projection_data"
        const val EXTRA_HOST = "receiver_host"
        private const val CHANNEL_ID = "audio_stream"
        private const val NOTIFICATION_ID = 42
        private const val SAMPLE_RATE = 44_100
        private const val READ_BUFFER_BYTES = 4_096
        private const val SOCKET_BUFFER_BYTES = 16_384
        private const val CONNECT_TIMEOUT_MS = 1_000
        private const val RECONNECT_DELAY_MS = 1_000L
    }
}