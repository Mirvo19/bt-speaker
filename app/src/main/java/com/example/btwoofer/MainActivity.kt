package com.example.btwoofer

import android.app.Activity
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Bundle
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
    private lateinit var receiver: AudioReceiver
    private var listening = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        receiver = AudioReceiver(this) { status ->
            runOnUiThread {
                statusLabel.text = "Status: $status"
                listening = status != "Stopped" && !status.startsWith("Error:")
                actionButton.text = when {
                    status == "Stopping" -> "Stopping..."
                    listening -> "Stop listening"
                    else -> "Start listening"
                }
                actionButton.isEnabled = status != "Stopping"
            }
        }
        buildInterface()
    }

    override fun onResume() {
        super.onResume()
        if (::addressLabel.isInitialized) updateAddress()
    }

    override fun onDestroy() {
        receiver.stop()
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
            text = "Status: Waiting"
            textSize = 18f
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(20))
        }
        content.addView(statusLabel, matchWidth())
        actionButton = Button(this).apply {
            text = "Start listening"
            setOnClickListener {
                if (listening) {
                    receiver.stop()
                } else {
                    updateAddress()
                    receiver.start(addressLabel.text.toString())
                }
            }
        }
        content.addView(actionButton, matchWidth())
        updateAddress()
    }

    private fun updateAddress() {
        addressLabel.text = localIpv4Address()
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
}