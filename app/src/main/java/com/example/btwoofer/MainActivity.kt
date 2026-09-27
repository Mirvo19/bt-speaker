package com.example.btwoofer

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.Manifest
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : Activity() {
    private lateinit var addressLabel: TextView
    private lateinit var statusLabel: TextView
    private lateinit var actionButton: Button
    private var listening = false
    private val handler = Handler(Looper.getMainLooper())
    private var activityResumed = false
    private var pendingReceiverHost: String? = null

    private val statusPoll = object : Runnable {
        override fun run() {
            if (!activityResumed || !::statusLabel.isInitialized) return
            val status = getSharedPreferences(ReceiverService.PREFERENCES, MODE_PRIVATE)
                .getString(ReceiverService.KEY_STATUS, ReceiverService.STATUS_STOPPED)
                ?: ReceiverService.STATUS_STOPPED
            showStatus(status)
            handler.postDelayed(this, STATUS_REFRESH_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildInterface()
    }

    override fun onResume() {
        super.onResume()
        activityResumed = true
        handler.removeCallbacks(statusPoll)
        handler.post(statusPoll)
        if (::addressLabel.isInitialized) updateAddress()
    }

    override fun onPause() {
        activityResumed = false
        handler.removeCallbacks(statusPoll)
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacks(statusPoll)
        super.onDestroy()
    }

    private fun buildInterface() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(36), dp(24), dp(24))
        }
        setContentView(ScrollView(this).apply { addView(content) })

        content.addView(TextView(this).apply {
            text = "BT Woofer Audio Link"
            textSize = 24f
        }, matchWidth())
        content.addView(TextView(this).apply {
            text = "Phone receiver"
            textSize = 16f
            setPadding(0, dp(8), 0, dp(24))
        }, matchWidth())
        addressLabel = TextView(this).apply {
            textSize = 30f
            setPadding(0, dp(8), 0, dp(8))
            setTextIsSelectable(true)
        }
        content.addView(addressLabel, matchWidth())
        content.addView(TextView(this).apply {
            text = "Receiver port: ${AudioReceiver.PORT}\nConnect the PC and phone to the same Wi-Fi network."
            textSize = 16f
            setPadding(0, dp(4), 0, dp(12))
        }, matchWidth())
        content.addView(TextView(this).apply {
            text = "One-time PC setup: use the Windows certificate helper linked on the sender page while this receiver is listening. Approve the certificate once; future connections will be automatic."
            textSize = 14f
            setPadding(0, 0, 0, dp(16))
        }, matchWidth())
        statusLabel = TextView(this).apply {
            text = "Status: Stopped"
            textSize = 18f
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(20))
        }
        content.addView(statusLabel, matchWidth())
        actionButton = Button(this).apply {
            text = "Start listening"
            setOnClickListener {
                if (listening) {
                    val stopIntent = Intent(this@MainActivity, ReceiverService::class.java).apply {
                        action = ReceiverService.ACTION_STOP
                    }
                    startService(stopIntent)
                    showStatus("Stopping")
                } else {
                    updateAddress()
                    if (addressLabel.text.toString().startsWith("Unavailable")) {
                        showStatus("Error: connect to Wi-Fi first")
                    } else {
                        requestNotificationAccessThenStart(addressLabel.text.toString())
                    }
                }
            }
        }
        content.addView(actionButton, matchWidth())
        updateAddress()
    }

    private fun updateAddress() {
        addressLabel.text = localIpv4Address()
    }

    private fun startReceiverService(host: String) {
        val intent = Intent(this, ReceiverService::class.java).apply {
            action = ReceiverService.ACTION_START
            putExtra(ReceiverService.EXTRA_HOST, host)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
        else startService(intent)
        showStatus("Starting")
    }

    private fun requestNotificationAccessThenStart(host: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingReceiverHost = host
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_PERMISSION_REQUEST)
            return
        }
        startReceiverService(host)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != NOTIFICATION_PERMISSION_REQUEST) return
        val host = pendingReceiverHost ?: return
        pendingReceiverHost = null
        startReceiverService(host)
        if (grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
            statusLabel.text = "Status: Starting (notification hidden; allow notifications for controls)"
        }
    }

    private fun showStatus(status: String) {
        statusLabel.text = "Status: $status"
        listening = status != ReceiverService.STATUS_STOPPED && !status.startsWith("Error:")
        actionButton.text = when {
            status == "Stopping" -> "Stopping..."
            listening -> "Stop listening"
            else -> "Start listening"
        }
        actionButton.isEnabled = status != "Stopping"
    }

    private fun localIpv4Address(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val network = interfaces.nextElement()
                if (!network.isUp || network.isLoopback ||
                    !(network.name.startsWith("wlan", ignoreCase = true) ||
                        network.displayName.contains("wifi", ignoreCase = true))
                ) continue
                val addresses = network.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && !address.isLinkLocalAddress && address is Inet4Address) {
                        return address.hostAddress ?: continue
                    }
                }
            }
        } catch (_: Exception) {
        }
        try {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val address = wifi.connectionInfo.ipAddress
            if (address != 0) {
                return listOf(0, 8, 16, 24).joinToString(".") { shift ->
                    ((address shr shift) and 0xff).toString()
                }
            }
        } catch (_: Exception) {
        }
        return "Unavailable - connect to Wi-Fi"
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun matchWidth() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    )

    companion object {
        private const val STATUS_REFRESH_MS = 500L
        private const val NOTIFICATION_PERMISSION_REQUEST = 31
    }
}