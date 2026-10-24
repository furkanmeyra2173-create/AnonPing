package com.anonh4x.anonping.floating

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.anonh4x.anonping.R
import com.anonh4x.anonping.vpn.AnonVpnService

/**
 * PlayPing-style floating bubble:
 * - Kısa bas → VPN aç/kapa
 * - Uzun bas → ayar menüsü
 * - Sürükle → taşı
 * - Kilit → yanlışlıkla basmayı engelle
 * - Duration → belirli süre sonra otomatik kapat
 */
class FloatingBubbleService : Service() {

    private lateinit var windowManager: WindowManager
    private var bubbleView: View? = null
    private var params: WindowManager.LayoutParams? = null

    private var isExpanded = false
    private var isVpnRunning = false
    private var isLocked = false

    private var currentLatency = 100
    private var currentLoss = 0
    private var currentBandwidthKbs = 0
    private var currentDurationSec = 0 // 0 = continuous

    private val handler = Handler(Looper.getMainLooper())
    private var autoStopRunnable: Runnable? = null

    private val longPressMs = 450L
    private var longPressTriggered = false
    private var downTime = 0L

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
            y = 200
        }

        val expanded = bubbleView!!.findViewById<View>(R.id.expandedView)
        val statusDot = bubbleView!!.findViewById<View>(R.id.statusDot)
        val statusText = bubbleView!!.findViewById<TextView>(R.id.bubbleStatus)

        val latencySeek = bubbleView!!.findViewById<SeekBar>(R.id.bubbleLatency)
        val latencyValue = bubbleView!!.findViewById<TextView>(R.id.bubbleLatencyValue)
        val lossSeek = bubbleView!!.findViewById<SeekBar>(R.id.bubbleLoss)
        val lossValue = bubbleView!!.findViewById<TextView>(R.id.bubbleLossValue)
        val bwSeek = bubbleView!!.findViewById<SeekBar>(R.id.bubbleBandwidth)
        val bwValue = bubbleView!!.findViewById<TextView>(R.id.bubbleBandwidthValue)
        val durationSeek = bubbleView!!.findViewById<SeekBar>(R.id.bubbleDuration)
        val durationValue = bubbleView!!.findViewById<TextView>(R.id.bubbleDurationValue)
        val applyBtn = bubbleView!!.findViewById<Button>(R.id.bubbleApply)
        val lockBtn = bubbleView!!.findViewById<Button>(R.id.bubbleLock)

        latencySeek.progress = currentLatency
        latencyValue.text = "$currentLatency ms"
        lossSeek.progress = currentLoss
        lossValue.text = "%$currentLoss"
        bwSeek.progress = 0
        bwValue.text = "∞ Unlimited"
        durationSeek.progress = 0
        durationValue.text = "Continuous"

        latencySeek.setOnSeekBarChangeListener(seek { p ->
            currentLatency = p
            latencyValue.text = "$p ms"
        })
        lossSeek.setOnSeekBarChangeListener(seek { p ->
            currentLoss = p
            lossValue.text = "%$p"
        })
        bwSeek.setOnSeekBarChangeListener(seek { p ->
            currentBandwidthKbs = if (p == 0) 0 else p * 50
            bwValue.text = if (currentBandwidthKbs == 0) "∞ Unlimited" else "$currentBandwidthKbs KB/s"
        })
        durationSeek.setOnSeekBarChangeListener(seek { p ->
            currentDurationSec = p
            durationValue.text = if (p == 0) "Continuous" else "${p}s"
        })

        applyBtn.setOnClickListener {
            applySettings(statusDot, statusText)
            collapse(expanded)
        }

        lockBtn.setOnClickListener {
            isLocked = !isLocked
            lockBtn.text = if (isLocked) "Aç" else "Kilit"
            Toast.makeText(
                this,
                if (isLocked) "Bubble kilitlendi" else "Kilit açıldı",
                Toast.LENGTH_SHORT
            ).show()
            collapse(expanded)
        }

        // Touch: drag / short = toggle / long = menu
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        val longPressCheck = Runnable {
            longPressTriggered = true
            if (!isLocked) {
                isExpanded = !isExpanded
                expanded.visibility = if (isExpanded) View.VISIBLE else View.GONE
                windowManager.updateViewLayout(bubbleView, params)
            } else {
                Toast.makeText(this, "Kilitli – önce kilidi aç", Toast.LENGTH_SHORT).show()
            }
        }

        bubbleView!!.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params!!.x
                    initialY = params!!.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    downTime = System.currentTimeMillis()
                    longPressTriggered = false
                    handler.postDelayed(longPressCheck, longPressMs)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = Math.abs(event.rawX - initialTouchX)
                    val dy = Math.abs(event.rawY - initialTouchY)
                    if (dx > 12 || dy > 12) {
                        handler.removeCallbacks(longPressCheck)
                        // allow drag even when locked (position only)
                        params!!.x = initialX + (event.rawX - initialTouchX).toInt()
                        params!!.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager.updateViewLayout(bubbleView, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPressCheck)
                    val dx = Math.abs(event.rawX - initialTouchX)
                    val dy = Math.abs(event.rawY - initialTouchY)
                    val elapsed = System.currentTimeMillis() - downTime
                    if (!longPressTriggered && dx < 12 && dy < 12 && elapsed < longPressMs) {
                        // Short tap → toggle
                        if (isLocked) {
                            Toast.makeText(this, "Kilitli", Toast.LENGTH_SHORT).show()
                        } else {
                            toggleVpn(statusDot, statusText)
                        }
                    }
                    true
                }
                else -> false
            }
        }

        windowManager.addView(bubbleView, params)
        updateUi(statusDot, statusText)
    }

    private fun seek(onProgress: (Int) -> Unit) =
        object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                onProgress(progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        }

    private fun toggleVpn(statusDot: View, statusText: TextView) {
        if (isVpnRunning) {
            stopVpn()
            Toast.makeText(this, "VPN OFF", Toast.LENGTH_SHORT).show()
        } else {
            startVpnWithCurrent()
            Toast.makeText(this, "VPN ON", Toast.LENGTH_SHORT).show()
        }
        updateUi(statusDot, statusText)
    }

    private fun applySettings(statusDot: View, statusText: TextView) {
        if (isVpnRunning) stopVpn()
        startVpnWithCurrent()
        val bw = if (currentBandwidthKbs == 0) "∞" else "$currentBandwidthKbs KB/s"
        val dur = if (currentDurationSec == 0) "sürekli" else "${currentDurationSec}s"
        Toast.makeText(
            this,
            "Uygulandı → ${currentLatency}ms | %$currentLoss | $bw | $dur",
            Toast.LENGTH_SHORT
        ).show()
        updateUi(statusDot, statusText)
    }

    private fun startVpnWithCurrent() {
        val intent = Intent(this, AnonVpnService::class.java).apply {
            putExtra(AnonVpnService.EXTRA_LATENCY_MS, currentLatency)
            putExtra(AnonVpnService.EXTRA_PACKET_LOSS, currentLoss)
            putExtra(AnonVpnService.EXTRA_BANDWIDTH_KBS, currentBandwidthKbs)
        }
        startForegroundService(intent)
        isVpnRunning = true

        // Auto-stop after duration
        autoStopRunnable?.let { handler.removeCallbacks(it) }
        if (currentDurationSec > 0) {
            autoStopRunnable = Runnable {
                stopVpn()
                bubbleView?.let {
                    val dot = it.findViewById<View>(R.id.statusDot)
                    val txt = it.findViewById<TextView>(R.id.bubbleStatus)
                    updateUi(dot, txt)
                }
                Toast.makeText(this, "Süre doldu – VPN OFF", Toast.LENGTH_SHORT).show()
            }
            handler.postDelayed(autoStopRunnable!!, currentDurationSec * 1000L)
        }
    }

    private fun stopVpn() {
        stopService(Intent(this, AnonVpnService::class.java))
        isVpnRunning = false
        autoStopRunnable?.let { handler.removeCallbacks(it) }
        autoStopRunnable = null
    }

    private fun collapse(expanded: View) {
        isExpanded = false
        expanded.visibility = View.GONE
        windowManager.updateViewLayout(bubbleView, params)
    }

    private fun updateUi(statusDot: View, statusText: TextView) {
        if (isVpnRunning) {
            statusText.text = "ON"
            statusDot.setBackgroundResource(R.drawable.bubble_dot_on)
        } else {
            statusText.text = "OFF"
            statusDot.setBackgroundResource(R.drawable.bubble_dot_off)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        autoStopRunnable?.let { handler.removeCallbacks(it) }
        bubbleView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        bubbleView = null
    }
}
