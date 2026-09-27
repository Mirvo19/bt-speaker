package com.example.btwoofer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

class ReceiverService : Service() {
    private var receiver: AudioReceiver? = null
    private var stopRequested = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopRequested = true
                val activeReceiver = receiver
                if (activeReceiver == null) {
                    setStatus(STATUS_STOPPED)
                    stopForegroundCompat()
                    stopSelfResult(startId)
                } else {
                    activeReceiver.stop()
                }
                return START_NOT_STICKY
            }

            ACTION_START, null -> {
                val host = intent?.getStringExtra(EXTRA_HOST)
                    ?: getSharedPreferences(PREFERENCES, MODE_PRIVATE).getString(KEY_HOST, null)
                if (host.isNullOrBlank()) {
                    setStatus("Error: receiver IP is missing")
                    stopSelfResult(startId)
                    return START_NOT_STICKY
                }

                stopRequested = false
                getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit().putString(KEY_HOST, host).apply()
                createNotificationChannel()
                startForegroundCompat(buildNotification("Starting receiver"))
                setStatus("Starting")
                if (receiver == null) {
                    receiver = AudioReceiver(applicationContext, ::onReceiverStatus)
                    receiver?.start(host)
                }
                return START_STICKY
            }

            else -> return START_NOT_STICKY
        }
    }

    private fun onReceiverStatus(status: String) {
        setStatus(status)
        if (status == STATUS_STOPPED && stopRequested) {
            stopRequested = false
            receiver = null
            stopForegroundCompat()
            stopSelf()
        } else if (status.startsWith("Error:")) {
            receiver = null
            stopForegroundCompat()
            stopSelf()
        } else if (status != "Stopping") {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIFICATION_ID, buildNotification(status))
        }
    }

    private fun setStatus(status: String) {
        getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit().putString(KEY_STATUS, status).apply()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Audio receiver",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Keeps the Wi-Fi audio receiver active" }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(status: String): Notification {
        val stopIntent = Intent(this, ReceiverService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(
            this,
            STOP_REQUEST_CODE,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("BT Woofer receiver")
            .setContentText(status)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "Stop listening", stopPendingIntent)
            .build()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
        else @Suppress("DEPRECATION") stopForeground(true)
    }

    override fun onDestroy() {
        receiver?.stop()
        receiver = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.example.btwoofer.action.START_RECEIVER"
        const val ACTION_STOP = "com.example.btwoofer.action.STOP_RECEIVER"
        const val EXTRA_HOST = "receiver_host"
        const val PREFERENCES = "receiver_service"
        const val KEY_STATUS = "status"
        const val STATUS_STOPPED = "Stopped"
        private const val KEY_HOST = "host"
        private const val CHANNEL_ID = "audio_receiver"
        private const val NOTIFICATION_ID = 51
        private const val STOP_REQUEST_CODE = 52
    }
}