package com.example.btwoofer

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : Activity() {
    private lateinit var senderRadio: RadioButton
    private lateinit var receiverRadio: RadioButton
    private lateinit var receiverAddress: EditText
    private lateinit var receiverIpLabel: TextView
    private lateinit var statusLabel: TextView
    private lateinit var actionButton: Button
    private lateinit var receiver: AudioReceiver
    private val handler = Handler(Looper.getMainLooper())
    private var receiverRunning = false

    private val statusPoll = object : Runnable {
        override fun run() {
            if (::statusLabel.isInitialized && senderRadio.isChecked) {
                val status = getSharedPreferences(AudioSenderService.PREFERENCES, MODE_PRIVATE)
                    .getString(AudioSenderService.STATUS_KEY, "Stopped") ?: "Stopped"
                statusLabel.text = "Status: $status"
                actionButton.text = if (status == "Stopped") "Start streaming" else "Stop streaming"
            }
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        receiver = AudioReceiver { status ->
            runOnUiThread {
                receiverRunning = status != "Stopped"
                if (receiverRadio.isChecked) {
                    statusLabel.text = "Status: $status"
                    actionButton.text = if (receiverRunning) "Stop listening" else "Start listening"
                }
            }
        }
        buildInterface()
        handler.post(statusPoll)
    }

    override fun onDestroy() {
        handler.removeCallbacks(statusPoll)
        receiver.stop()
        super.onDestroy()
    }

    private fun buildInterface() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(32), dp(24), dp(24))
        }
        val scroll = ScrollView(this).apply { addView(content) }
        setContentView(scroll)

        content.addView(TextView(this).apply {
            text = "BT Woofer Audio Link"
            textSize = 24f
        }, matchWidth())
        content.addView(TextView(this).apply {
            text = "Stream local phone audio over Wi-Fi"
            textSize = 16f
            setPadding(0, dp(8), 0, dp(24))
        }, matchWidth())

        val modes = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        senderRadio = RadioButton(this).apply { text = "Sender"; id = View.generateViewId() }
        receiverRadio = RadioButton(this).apply { text = "Receiver"; id = View.generateViewId() }
        modes.addView(senderRadio)
        modes.addView(receiverRadio)
        content.addView(modes, matchWidth())

        receiverAddress = EditText(this).apply {
            hint = "Receiver IP address"
            inputType = InputType.TYPE_CLASS_PHONE
            setSingleLine(true)
        }
        content.addView(receiverAddress, matchWidth())

        receiverIpLabel = TextView(this).apply {
            textSize = 16f
            setPadding(0, dp(8), 0, dp(8))
        }
        content.addView(receiverIpLabel, matchWidth())
        statusLabel = TextView(this).apply {
            text = "Status: Stopped"
            textSize = 18f
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(20), 0, dp(20))
        }
        content.addView(statusLabel, matchWidth())
        actionButton = Button(this)
        content.addView(actionButton, matchWidth())

        modes.setOnCheckedChangeListener { _, checkedId ->
            val isSender = checkedId == senderRadio.id
            receiverAddress.visibility = if (isSender) View.VISIBLE else View.GONE
            receiverIpLabel.visibility = if (isSender) View.GONE else View.VISIBLE
            if (isSender) {
                statusLabel.text = "Status: " + getSharedPreferences(
                    AudioSenderService.PREFERENCES, MODE_PRIVATE
                ).getString(AudioSenderService.STATUS_KEY, "Stopped")
                updateSenderButton()
            } else {
                receiverIpLabel.text = "This phone's Wi-Fi IP: ${localIpv4Address()}\nPort: ${AudioReceiver.PORT}"
                statusLabel.text = "Status: ${if (receiverRunning) "Listening" else "Stopped"}"
                actionButton.text = if (receiverRunning) "Stop listening" else "Start listening"
            }
        }
        actionButton.setOnClickListener {
            if (senderRadio.isChecked) {
                val status = getSharedPreferences(AudioSenderService.PREFERENCES, MODE_PRIVATE)
                    .getString(AudioSenderService.STATUS_KEY, "Stopped")
                if (status == "Stopped") startSender() else stopSender()
            } else if (receiverRunning) {
                receiver.stop()
            } else {
                receiver.start()
            }
        }
        senderRadio.isChecked = true
    }

    private fun startSender() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            statusLabel.text = "Status: Sender requires Android 10 or newer"
            return
        }
        if (receiverAddress.text.toString().trim().isEmpty()) {
            receiverAddress.error = "Enter the Receiver's Wi-Fi IP address"
            return
        }
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = permissions.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), REQUEST_PERMISSIONS)
            return
        }
        requestProjectionPermission()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS) {
            val audioGranted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            if (audioGranted) requestProjectionPermission()
            else statusLabel.text = "Status: Microphone permission is required for playback capture"
        }
    }

    private fun requestProjectionPermission() {
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_PROJECTION)
    }

    @Deprecated("The platform permission result is handled through this activity callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PROJECTION) return
        if (resultCode != RESULT_OK || data == null) {
            statusLabel.text = "Status: Audio capture permission not granted"
            return
        }
        val serviceIntent = Intent(this, AudioSenderService::class.java).apply {
            putExtra(AudioSenderService.EXTRA_RESULT_CODE, resultCode)
            putExtra(AudioSenderService.EXTRA_PROJECTION_DATA, data)
            putExtra(AudioSenderService.EXTRA_HOST, receiverAddress.text.toString().trim())
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(serviceIntent)
        else startService(serviceIntent)
        statusLabel.text = "Status: Connecting"
    }

    private fun stopSender() {
        stopService(Intent(this, AudioSenderService::class.java))
        statusLabel.text = "Status: Stopped"
        actionButton.text = "Start streaming"
    }

    private fun updateSenderButton() {
        val status = getSharedPreferences(AudioSenderService.PREFERENCES, MODE_PRIVATE)
            .getString(AudioSenderService.STATUS_KEY, "Stopped")
        actionButton.text = if (status == "Stopped") "Start streaming" else "Stop streaming"
    }

    private fun localIpv4Address(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val network = interfaces.nextElement()
                val addresses = network.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address is Inet4Address) return address.hostAddress ?: "Unavailable"
                }
            }
        } catch (_: Exception) {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val address = wifi.connectionInfo.ipAddress
            if (address != 0) return listOf(0, 8, 16, 24).joinToString(".") { shift ->
                ((address shr shift) and 0xff).toString()
            }
        }
        return "Unavailable"
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun matchWidth() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    )

    companion object {
        private const val REQUEST_PERMISSIONS = 10
        private const val REQUEST_PROJECTION = 11
    }
}