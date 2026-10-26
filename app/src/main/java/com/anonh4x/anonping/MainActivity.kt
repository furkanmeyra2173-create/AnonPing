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

        latencySeek.max = 2000
        latencySeek.setOnSeekBarChangeListener(simpleListener { p ->
            currentLatencyMs = p
            latencyValue.text = "$p ms"
        })

        lossSeek.max = 100
        lossSeek.setOnSeekBarChangeListener(simpleListener { p ->
            currentLossPercent = p
            lossValue.text = "%$p"
        })

        bwSeek.max = 200
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

        findViewById<View>(R.id.tabHome).setOnClickListener { showTab(0) }
        findViewById<View>(R.id.tabEffects).setOnClickListener { showTab(1) }
        findViewById<View>(R.id.tabSettings).setOnClickListener { showTab(2) }
        findViewById<View>(R.id.presetClean).setOnClickListener { applyPreset(0, 0, 0, "Clean connection") }
        findViewById<View>(R.id.presetDelay).setOnClickListener { applyPreset(250, 0, 0, "High latency") }
        findViewById<View>(R.id.presetUnstable).setOnClickListener { applyPreset(150, 8, 1000, "Unstable network") }
        findViewById<View>(R.id.presetSevere).setOnClickListener { applyPreset(500, 25, 250, "Severe disruption") }
        showTab(0)
        updateStatus(false)
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
        statusText.animate().cancel()
        statusText.alpha = 1f
        statusText.text = if (running) "● ACTIVE" else "● OFFLINE"
        statusText.setTextColor(if (running) 0xFF8DBBFF.toInt() else 0xFF8FA2BC.toInt())
        statusText.setBackgroundResource(if (running) R.drawable.bg_pp_status_active else R.drawable.bg_pp_status_idle)
        startBtn.isEnabled = !running
        stopBtn.isEnabled = running
        startBtn.alpha = if (running) 0.55f else 1f
        stopBtn.alpha = if (running) 1f else 0.55f
    }

    private fun showTab(index: Int) {
        findViewById<View>(R.id.pageHome).visibility = if (index == 0) View.VISIBLE else View.GONE
        findViewById<View>(R.id.pageEffects).visibility = if (index == 1) View.VISIBLE else View.GONE
        findViewById<View>(R.id.pageSettings).visibility = if (index == 2) View.VISIBLE else View.GONE
        val selected = 0xFF8DBBFF.toInt()
        val idle = 0xFF8FA2BC.toInt()
        findViewById<TextView>(R.id.labelTabHome).setTextColor(if (index == 0) selected else idle)
        findViewById<TextView>(R.id.labelTabEffects).setTextColor(if (index == 1) selected else idle)
        findViewById<TextView>(R.id.labelTabSettings).setTextColor(if (index == 2) selected else idle)
        findViewById<View>(R.id.iconTabHome).alpha = if (index == 0) 1f else 0.65f
        findViewById<View>(R.id.iconTabEffects).alpha = if (index == 1) 1f else 0.65f
        findViewById<View>(R.id.iconTabSettings).alpha = if (index == 2) 1f else 0.65f
    }

    private fun applyPreset(latency: Int, loss: Int, bandwidth: Int, name: String) {
        latencySeek.progress = latency
        lossSeek.progress = loss
        bwSeek.progress = if (bandwidth == 0) 0 else (bandwidth / 50).coerceIn(1, 200)
        if (isRunning) startVpn()
        Toast.makeText(this, "$name profile selected", Toast.LENGTH_SHORT).show()
        showTab(0)
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
