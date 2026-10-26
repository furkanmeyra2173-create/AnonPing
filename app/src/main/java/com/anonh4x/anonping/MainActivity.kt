package com.anonh4x.anonping

import android.animation.ObjectAnimator
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.anonh4x.anonping.floating.FloatingBubbleService
import com.anonh4x.anonping.vpn.AnonVpnService

class MainActivity : AppCompatActivity() {

    private lateinit var latencySeek: SeekBar
    private lateinit var latencyValue: TextView
    private lateinit var lossSeek: SeekBar
    private lateinit var lossValue: TextView
    private lateinit var bwSeek: SeekBar
    private lateinit var bwValue: TextView
    private lateinit var startBtn: Button
    private lateinit var stopBtn: Button
    private lateinit var bubbleBtn: Button
    private lateinit var statusText: TextView
    private lateinit var titleText: TextView

    private var currentLatencyMs = 0
    private var currentLossPercent = 0
    private var currentBandwidthKbs = 0
    private var isRunning = false
    private var pendingVpnSettings: Intent? = null

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val settings = pendingVpnSettings
        pendingVpnSettings = null
        if (result.resultCode == RESULT_OK) {
            if (settings == null) startVpn() else startVpn(settings)
        } else {
            Toast.makeText(this, "VPN izni verilmedi", Toast.LENGTH_SHORT).show()
        }
    }

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Settings.canDrawOverlays(this)) {
            toggleBubble()
        } else {
            Toast.makeText(this, "Overlay izni gerekli", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        titleText = findViewById(R.id.tvTitle)
        statusText = findViewById(R.id.tvStatus)
        latencySeek = findViewById(R.id.seekLatency)
        latencyValue = findViewById(R.id.tvLatencyValue)
        lossSeek = findViewById(R.id.seekLoss)
        lossValue = findViewById(R.id.tvLossValue)
        bwSeek = findViewById(R.id.seekBandwidth)
        bwValue = findViewById(R.id.tvBandwidthValue)
        startBtn = findViewById(R.id.btnStart)
        stopBtn = findViewById(R.id.btnStop)
        bubbleBtn = findViewById(R.id.btnBubble)

        // Entrance animations
        animateIn(titleText, 0)
        animateIn(statusText, 80)
        animateIn(latencySeek, 120)
        animateIn(lossSeek, 160)
        animateIn(bwSeek, 200)
        animateIn(startBtn, 240)
        animateIn(stopBtn, 280)
        animateIn(bubbleBtn, 320)

        latencySeek.max = 500
        latencySeek.setOnSeekBarChangeListener(simpleListener { p ->
            currentLatencyMs = p
            latencyValue.text = "$p ms"
        })

        lossSeek.max = 50
        lossSeek.setOnSeekBarChangeListener(simpleListener { p ->
            currentLossPercent = p
            lossValue.text = "%$p"
        })

        bwSeek.max = 100
        bwSeek.setOnSeekBarChangeListener(simpleListener { p ->
            currentBandwidthKbs = if (p == 0) 0 else p * 50
            bwValue.text = if (currentBandwidthKbs == 0) "∞ Unlimited" else "$currentBandwidthKbs KB/s"
        })

        startBtn.setOnClickListener {
            val permissionIntent = VpnService.prepare(this)
            if (permissionIntent != null) {
                pendingVpnSettings = null
                vpnPermissionLauncher.launch(permissionIntent)
            } else {
                startVpn()
            }
        }

        stopBtn.setOnClickListener {
            stopService(Intent(this, AnonVpnService::class.java))
            isRunning = false
            updateStatus(false)
            Toast.makeText(this, "VPN stopped", Toast.LENGTH_SHORT).show()
        }

        bubbleBtn.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                overlayPermissionLauncher.launch(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                )
            } else {
                toggleBubble()
            }
        }

        handleIncomingIntent(intent)
    }

    private fun animateIn(view: View, delay: Long) {
        view.alpha = 0f
        view.translationY = 30f
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay(delay)
            .setDuration(400)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun simpleListener(onProgress: (Int) -> Unit) =
        object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                onProgress(progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(incoming: Intent?) {
        if (incoming?.action != FloatingBubbleService.ACTION_REQUEST_VPN_PERMISSION) return
        pendingVpnSettings = Intent(incoming)
        val permissionIntent = VpnService.prepare(this)
        if (permissionIntent != null) {
            vpnPermissionLauncher.launch(permissionIntent)
        } else {
            val settings = pendingVpnSettings
            pendingVpnSettings = null
            startVpn(settings)
        }
    }

    private fun startVpn(settings: Intent? = null) {
        val intent = Intent(this, AnonVpnService::class.java).apply {
            putExtra(
                AnonVpnService.EXTRA_LATENCY_MS,
                settings?.getIntExtra(AnonVpnService.EXTRA_LATENCY_MS, currentLatencyMs) ?: currentLatencyMs
            )
            putExtra(
                AnonVpnService.EXTRA_PACKET_LOSS,
                settings?.getIntExtra(AnonVpnService.EXTRA_PACKET_LOSS, currentLossPercent) ?: currentLossPercent
            )
            putExtra(
                AnonVpnService.EXTRA_BANDWIDTH_KBS,
                settings?.getIntExtra(AnonVpnService.EXTRA_BANDWIDTH_KBS, currentBandwidthKbs) ?: currentBandwidthKbs
            )
            putExtra(
                AnonVpnService.EXTRA_BLOCK_DOWNLOAD,
                settings?.getBooleanExtra(AnonVpnService.EXTRA_BLOCK_DOWNLOAD, false) ?: false
            )
        }
        try {
            startForegroundService(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "VPN başlatılamadı: ${e.message ?: "başlatma hatası"}", Toast.LENGTH_LONG).show()
            updateStatus(false)
            return
        }
        isRunning = true
        updateStatus(true)

        val bwText = if (currentBandwidthKbs == 0) "∞" else "$currentBandwidthKbs KB/s"
        Toast.makeText(this, "Started → ${currentLatencyMs}ms | %$currentLossPercent | $bwText", Toast.LENGTH_SHORT).show()
    }

    private fun updateStatus(running: Boolean) {
        if (running) {
            statusText.text = "● ACTIVE"
            statusText.setTextColor(0xFF00FF9A.toInt())
            // subtle pulse
            ObjectAnimator.ofFloat(statusText, "alpha", 1f, 0.5f, 1f).apply {
                duration = 1200
                repeatCount = ObjectAnimator.INFINITE
                start()
            }
        } else {
            statusText.animate().cancel()
            statusText.alpha = 1f
            statusText.text = "● IDLE"
            statusText.setTextColor(0xFF888888.toInt())
        }
    }

    private fun toggleBubble() {
        try {
            startForegroundService(Intent(this, FloatingBubbleService::class.java))
            Toast.makeText(this, "Floating bubble açıldı", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Balon başlatılamadı: ${e.message ?: "başlatma hatası"}", Toast.LENGTH_LONG).show()
        }
    }
}
