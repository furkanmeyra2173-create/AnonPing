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
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import com.anonh4x.anonping.R
import com.anonh4x.anonping.vpn.AnonVpnService

/**
 * Ekran üstü sürüklenen floating bubble.
 * Tıklayınca VPN'i aç/kapa yapar.
 */
class FloatingBubbleService : Service() {

    private lateinit var windowManager: WindowManager
    private var bubbleView: View? = null
    private var params: WindowManager.LayoutParams? = null

    private var isVpnRunning = false

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
            x = 50
            y = 200
        }

        val icon = bubbleView!!.findViewById<ImageView>(R.id.bubbleIcon)
        val statusText = bubbleView!!.findViewById<TextView>(R.id.bubbleStatus)

        // Sürükleme
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
                    // Kısa tıklama mı yoksa sürükleme mi?
                    val dx = Math.abs(event.rawX - initialTouchX)
                    val dy = Math.abs(event.rawY - initialTouchY)
                    if (dx < 10 && dy < 10) {
                        toggleVpn(statusText, icon)
                    }
                    true
                }
                else -> false
            }
        }

        windowManager.addView(bubbleView, params)
        updateBubbleUi(statusText, icon)
    }

    private fun toggleVpn(statusText: TextView, icon: ImageView) {
        if (isVpnRunning) {
            stopService(Intent(this, AnonVpnService::class.java))
            isVpnRunning = false
            Toast.makeText(this, "VPN stopped", Toast.LENGTH_SHORT).show()
        } else {
            // Varsayılan değerlerle başlat (latency 100ms, loss 0)
            val intent = Intent(this, AnonVpnService::class.java).apply {
                putExtra(AnonVpnService.EXTRA_LATENCY_MS, 100)
                putExtra(AnonVpnService.EXTRA_PACKET_LOSS, 0)
            }
            startForegroundService(intent)
            isVpnRunning = true
            Toast.makeText(this, "VPN started (100ms)", Toast.LENGTH_SHORT).show()
        }
        updateBubbleUi(statusText, icon)
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
