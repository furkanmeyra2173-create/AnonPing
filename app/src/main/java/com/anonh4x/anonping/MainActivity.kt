package com.anonh4x.anonping

import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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
    private lateinit var startBtn: Button
    private lateinit var stopBtn: Button
    private lateinit var bubbleBtn: Button

    private var currentLatencyMs = 0
    private var currentLossPercent = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        latencySeek = findViewById(R.id.seekLatency)
        latencyValue = findViewById(R.id.tvLatencyValue)
        lossSeek = findViewById(R.id.seekLoss)
        lossValue = findViewById(R.id.tvLossValue)
        startBtn = findViewById(R.id.btnStart)
        stopBtn = findViewById(R.id.btnStop)
        bubbleBtn = findViewById(R.id.btnBubble)

        latencySeek.max = 500
        latencySeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                currentLatencyMs = progress
                latencyValue.text = "$progress ms"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        lossSeek.max = 50
        lossSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                currentLossPercent = progress
                lossValue.text = "%$progress"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
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
            Toast.makeText(this, "VPN stopped", Toast.LENGTH_SHORT).show()
        }

        bubbleBtn.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivityForResult(intent, REQUEST_OVERLAY)
            } else {
                toggleBubble()
            }
        }
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
        }
        startForegroundService(intent)
        Toast.makeText(
            this,
            "Started → Latency: ${currentLatencyMs}ms | Loss: %$currentLossPercent",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun toggleBubble() {
        val intent = Intent(this, FloatingBubbleService::class.java)
        // Basit toggle: her seferinde başlat (servis zaten varsa onCreate tekrar çağrılmaz ama
        // şimdilik startService yeterli, stop için ayrıca buton eklenebilir)
        startService(intent)
        Toast.makeText(this, "Floating bubble açıldı", Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val REQUEST_VPN = 100
        private const val REQUEST_OVERLAY = 101
    }
}
