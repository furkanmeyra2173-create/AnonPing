package com.anonh4x.anonping.floating

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.anonh4x.anonping.R
import com.anonh4x.anonping.vpn.AnonVpnService

/**
 * Floating bubble with full controls:
 * Latency + Packet Loss + Bandwidth
 */
class FloatingBubbleService : Service() {

    private lateinit var windowManager: WindowManager
    private var bubbleView: View? = null
    private var params: WindowManager.LayoutParams? = null

    private var isExpanded = false
    private var isVpnRunning = false
    private var currentLatency = 100
    private var currentLoss = 0
    private var currentBandwidthKbs = 0 // 0 = unlimited

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createBubble()
    }

    private fun createBubble() {
        val inflater = LayoutInflater.from(this)
        bubbleView = inflater.inflate(R.layout.floating_bubble, null)

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 40
            y = 180
        }

        val expanded = bubbleView!!.findViewById<View>(R.id.expandedView)
        val icon = bubbleView!!.findViewById<ImageView>(R.id.bubbleIcon)
        val statusText = bubbleView!!.findViewById<TextView>(R.id.bubbleStatus)

        val latencySeek = bubbleView!!.findViewById<SeekBar>(R.id.bubbleLatency)
        val latencyValue = bubbleView!!.findViewById<TextView>(R.id.bubbleLatencyValue)
        val lossSeek = bubbleView!!.findViewById<SeekBar>(R.id.bubbleLoss)
        val lossValue = bubbleView!!.findViewById<TextView>(R.id.bubbleLossValue)
        val bwSeek = bubbleView!!.findViewById<SeekBar>(R.id.bubbleBandwidth)
        val bwValue = bubbleView!!.findViewById<TextView>(R.id.bubbleBandwidthValue)
        val applyBtn = bubbleView!!.findViewById<Button>(R.id.bubbleApply)

        // Initial values
        latencySeek.progress = currentLatency
        latencyValue.text = "$currentLatency ms"
        lossSeek.progress = currentLoss
        lossValue.text = "%$currentLoss"
        bwSeek.progress = 0
        bwValue.text = "∞ Unlimited"

        latencySeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                currentLatency = progress
                latencyValue.text = "$progress ms"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        lossSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                currentLoss = progress
                lossValue.text = "%$progress"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        bwSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                currentBandwidthKbs = if (progress == 0) 0 else progress * 50
                bwValue.text = if (currentBandwidthKbs == 0) "∞ Unlimited" else "$currentBandwidthKbs KB/s"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        applyBtn.setOnClickListener {
            applyAndToggle(statusText, icon)
        }

        // Drag + short click to expand/collapse
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        bubbleView!!.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params!!.x
                    initialY = params!!.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params!!.x = initialX + (event.rawX - initialTouchX).toInt()
                    params!!.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(bubbleView, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val dx = Math.abs(event.rawX - initialTouchX)
                    val dy = Math.abs(event.rawY - initialTouchY)
                    if (dx < 12 && dy < 12) {
                        isExpanded = !isExpanded
                        expanded.visibility = if (isExpanded) View.VISIBLE else View.GONE
                        windowManager.updateViewLayout(bubbleView, params)
                    }
                    true
                }
                else -> false
            }
        }

        windowManager.addView(bubbleView, params)
        updateBubbleUi(statusText, icon)
    }

    private fun applyAndToggle(statusText: TextView, icon: ImageView) {
        if (isVpnRunning) {
            stopService(Intent(this, AnonVpnService::class.java))
        }

        val intent = Intent(this, AnonVpnService::class.java).apply {
            putExtra(AnonVpnService.EXTRA_LATENCY_MS, currentLatency)
            putExtra(AnonVpnService.EXTRA_PACKET_LOSS, currentLoss)
            putExtra(AnonVpnService.EXTRA_BANDWIDTH_KBS, currentBandwidthKbs)
        }
        startForegroundService(intent)
        isVpnRunning = true

        val bwText = if (currentBandwidthKbs == 0) "∞" else "$currentBandwidthKbs KB/s"
        Toast.makeText(
            this,
            "Uygulandı → ${currentLatency}ms | %$currentLoss | $bwText",
            Toast.LENGTH_SHORT
        ).show()

        updateBubbleUi(statusText, icon)

        // Collapse panel
        isExpanded = false
        bubbleView?.findViewById<View>(R.id.expandedView)?.visibility = View.GONE
        windowManager.updateViewLayout(bubbleView, params)
    }

    private fun updateBubbleUi(statusText: TextView, icon: ImageView) {
        if (isVpnRunning) {
            statusText.text = "ON"
            icon.setImageResource(android.R.drawable.presence_online)
        } else {
            statusText.text = "OFF"
            icon.setImageResource(android.R.drawable.presence_offline)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        bubbleView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {}
        }
        bubbleView = null
    }
}
