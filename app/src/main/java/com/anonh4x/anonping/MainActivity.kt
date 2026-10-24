package com.anonh4x.anonping

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.anonh4x.anonping.vpn.AnonVpnService

class MainActivity : AppCompatActivity() {

    private lateinit var latencySeek: SeekBar
    private lateinit var latencyValue: TextView
    private lateinit var startBtn: Button
    private lateinit var stopBtn: Button

    private var currentLatencyMs = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        latencySeek = findViewById(R.id.seekLatency)
        latencyValue = findViewById(R.id.tvLatencyValue)
        startBtn = findViewById(R.id.btnStart)
        stopBtn = findViewById(R.id.btnStop)

        latencySeek.max = 500 // 0-500 ms
        latencySeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                currentLatencyMs = progress
                latencyValue.text = "$progress ms"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        startBtn.setOnClickListener {
            val intent = VpnService.prepare(this)
            if (intent != null) {
                startActivityForResult(intent, 0)
            } else {
                startVpn()
            }
        }

        stopBtn.setOnClickListener {
            stopService(Intent(this, AnonVpnService::class.java))
            Toast.makeText(this, "VPN stopped", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == RESULT_OK) {
            startVpn()
        }
    }

    private fun startVpn() {
        val intent = Intent(this, AnonVpnService::class.java).apply {
            putExtra(AnonVpnService.EXTRA_LATENCY_MS, currentLatencyMs)
        }
        startForegroundService(intent)
        Toast.makeText(this, "VPN started with ${currentLatencyMs}ms latency", Toast.LENGTH_SHORT).show()
    }
}
