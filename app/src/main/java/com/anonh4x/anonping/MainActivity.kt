package com.anonh4x.anonping

import android.animation.ObjectAnimator
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
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
            val intent = VpnService.prepare(this)
            if (intent != null) {
                startActivityForResult(intent, REQUEST_VPN)
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                startActivityForResult(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
                    REQUEST_OVERLAY
                )
            } else {
                toggleBubble()
            }
        }
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

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_VPN -> if (resultCode == RESULT_OK) startVpn()
            REQUEST_OVERLAY -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
                    toggleBubble()
                } else {
                    Toast.makeText(this, "Overlay izni gerekli", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun startVpn() {
        val intent = Intent(this, AnonVpnService::class.java).apply {
            putExtra(AnonVpnService.EXTRA_LATENCY_MS, currentLatencyMs)
            putExtra(AnonVpnService.EXTRA_PACKET_LOSS, currentLossPercent)
            putExtra(AnonVpnService.EXTRA_BANDWIDTH_KBS, currentBandwidthKbs)
        }
        startForegroundService(intent)
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
        startService(Intent(this, FloatingBubbleService::class.java))
        Toast.makeText(this, "Floating bubble açıldı", Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val REQUEST_VPN = 100
        private const val REQUEST_OVERLAY = 101
    }
}
