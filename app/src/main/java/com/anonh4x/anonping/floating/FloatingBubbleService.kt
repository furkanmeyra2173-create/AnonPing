package com.anonh4x.anonping.floating

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.pm.ServiceInfo
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.provider.Settings
import android.widget.Button
import android.widget.CheckBox
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.anonh4x.anonping.MainActivity
import com.anonh4x.anonping.R
import com.anonh4x.anonping.vpn.AnonVpnService
import kotlin.math.sqrt

/**
 * ClumsyOxPing / PlayPing style bubble:
 * short tap = toggle, long press = menu,
 * shake / volume = toggle, lock pos, mini size, block download
 */
class FloatingBubbleService : Service(), SensorEventListener {

    companion object {
        const val ACTION_REQUEST_VPN_PERMISSION =
            "com.anonh4x.anonping.action.REQUEST_VPN_PERMISSION"
        private const val CHANNEL_ID = "playping_bubble"
        private const val NOTIFICATION_ID = 2
    }

    private lateinit var windowManager: WindowManager
    private var bubbleView: View? = null
    private var params: WindowManager.LayoutParams? = null

    private var isExpanded = false
    private var isVpnRunning = false
    private var isLocked = false          // tap lock
    private var lockPosition = false      // drag lock

    private var currentLatency = 100
    private var currentLoss = 0
    private var currentBandwidthKbs = 0
    private var currentDurationSec = 0
    private var blockDownload = false
    private var bubbleSizeDp = 28

    private var shakeEnabled = false
    private var volumeEnabled = false

    private val handler = Handler(Looper.getMainLooper())
    private var autoStopRunnable: Runnable? = null

    private val longPressMs = 450L
    private var longPressTriggered = false
    private var downTime = 0L

    private var sensorManager: SensorManager? = null
    private var lastShakeTs = 0L
    private var lastAccel = 0f

    private var lastVolume = -1
    private var volumeReceiver: BroadcastReceiver? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startServiceNotification()

        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "PlayPing balonu için ekran üstünde gösterme izni gerekli", Toast.LENGTH_LONG).show()
            stopSelf()
            return
        }

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        try {
            createBubble()
        } catch (e: Exception) {
            Toast.makeText(this, "Balon açılamadı: ${e.message ?: "izin veya pencere hatası"}", Toast.LENGTH_LONG).show()
            stopSelf()
        }
    }

    private fun startServiceNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "PlayPing yüzen kontroller", NotificationManager.IMPORTANCE_LOW)
            )
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("PlayPing")
            .setContentText("Yüzen ağ kontrolleri çalışıyor")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createBubble() {
        bubbleView = LayoutInflater.from(this).inflate(R.layout.floating_bubble, null)

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 40; y = 200
        }

        val expanded = bubbleView!!.findViewById<View>(R.id.expandedView)
        val statusDot = bubbleView!!.findViewById<View>(R.id.statusDot)
        val statusText = bubbleView!!.findViewById<TextView>(R.id.bubbleStatus)
        val compact = bubbleView!!.findViewById<View>(R.id.compactView)

        val latencySeek = bubbleView!!.findViewById<SeekBar>(R.id.bubbleLatency)
        val latencyValue = bubbleView!!.findViewById<TextView>(R.id.bubbleLatencyValue)
        val lossSeek = bubbleView!!.findViewById<SeekBar>(R.id.bubbleLoss)
        val lossValue = bubbleView!!.findViewById<TextView>(R.id.bubbleLossValue)
        val bwSeek = bubbleView!!.findViewById<SeekBar>(R.id.bubbleBandwidth)
        val bwValue = bubbleView!!.findViewById<TextView>(R.id.bubbleBandwidthValue)
        val durationSeek = bubbleView!!.findViewById<SeekBar>(R.id.bubbleDuration)
        val durationValue = bubbleView!!.findViewById<TextView>(R.id.bubbleDurationValue)
        val sizeSeek = bubbleView!!.findViewById<SeekBar>(R.id.bubbleSize)
        val sizeValue = bubbleView!!.findViewById<TextView>(R.id.bubbleSizeValue)

        val chkShake = bubbleView!!.findViewById<CheckBox>(R.id.chkShake)
        val chkVolume = bubbleView!!.findViewById<CheckBox>(R.id.chkVolume)
        val chkLockPos = bubbleView!!.findViewById<CheckBox>(R.id.chkLockPos)
        val chkBlockDl = bubbleView!!.findViewById<CheckBox>(R.id.chkBlockDl)

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
        sizeSeek.progress = bubbleSizeDp
        sizeValue.text = "$bubbleSizeDp dp"

        latencySeek.setOnSeekBarChangeListener(seek { p -> currentLatency = p; latencyValue.text = "$p ms" })
        lossSeek.setOnSeekBarChangeListener(seek { p -> currentLoss = p; lossValue.text = "%$p" })
        bwSeek.setOnSeekBarChangeListener(seek { p ->
            currentBandwidthKbs = if (p == 0) 0 else p * 50
            bwValue.text = if (currentBandwidthKbs == 0) "∞ Unlimited" else "$currentBandwidthKbs KB/s"
        })
        durationSeek.setOnSeekBarChangeListener(seek { p ->
            currentDurationSec = p
            durationValue.text = if (p == 0) "Continuous" else "${p}s"
        })
        sizeSeek.setOnSeekBarChangeListener(seek { p ->
            bubbleSizeDp = p.coerceAtLeast(16)
            sizeValue.text = "$bubbleSizeDp dp"
            val lp = statusDot.layoutParams
            val px = (bubbleSizeDp * resources.displayMetrics.density).toInt()
            lp.width = px; lp.height = px
            statusDot.layoutParams = lp
        })

        chkShake.setOnCheckedChangeListener { _, checked ->
            shakeEnabled = checked
            if (checked) registerShake() else unregisterShake()
        }
        chkVolume.setOnCheckedChangeListener { _, checked ->
            volumeEnabled = checked
            if (checked) registerVolume() else unregisterVolume()
        }
        chkLockPos.setOnCheckedChangeListener { _, checked -> lockPosition = checked }
        chkBlockDl.setOnCheckedChangeListener { _, checked -> blockDownload = checked }

        applyBtn.setOnClickListener {
            applySettings(statusDot, statusText)
            collapse(expanded)
        }
        lockBtn.setOnClickListener {
            isLocked = !isLocked
            lockBtn.text = if (isLocked) "Aç" else "Kilit"
            Toast.makeText(this, if (isLocked) "Tuş kilitlendi" else "Kilit açıldı", Toast.LENGTH_SHORT).show()
            collapse(expanded)
        }

        var initialX = 0; var initialY = 0
        var initialTouchX = 0f; var initialTouchY = 0f

        val longPressCheck = Runnable {
            longPressTriggered = true
            if (isLocked) {
                Toast.makeText(this, "Kilitli", Toast.LENGTH_SHORT).show()
            } else {
                isExpanded = !isExpanded
                expanded.visibility = if (isExpanded) View.VISIBLE else View.GONE
                windowManager.updateViewLayout(bubbleView, params)
            }
        }

        bubbleView!!.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params!!.x; initialY = params!!.y
                    initialTouchX = event.rawX; initialTouchY = event.rawY
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
                        if (!lockPosition) {
                            params!!.x = initialX + (event.rawX - initialTouchX).toInt()
                            params!!.y = initialY + (event.rawY - initialTouchY).toInt()
                            windowManager.updateViewLayout(bubbleView, params)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPressCheck)
                    val dx = Math.abs(event.rawX - initialTouchX)
                    val dy = Math.abs(event.rawY - initialTouchY)
                    val elapsed = System.currentTimeMillis() - downTime
                    if (!longPressTriggered && dx < 12 && dy < 12 && elapsed < longPressMs) {
                        if (isLocked) Toast.makeText(this, "Kilitli", Toast.LENGTH_SHORT).show()
                        else toggleVpn(statusDot, statusText)
                    }
                    true
                }
                else -> false
            }
        }

        windowManager.addView(bubbleView, params)
        updateUi(statusDot, statusText)
    }

    private fun seek(block: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) = block(p)
        override fun onStartTrackingTouch(s: SeekBar?) {}
        override fun onStopTrackingTouch(s: SeekBar?) {}
    }

    // ---- Sensors ----

    private fun registerShake() {
        sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    private fun unregisterShake() {
        sensorManager?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (!shakeEnabled || event?.sensor?.type != Sensor.TYPE_ACCELEROMETER) return
        val x = event.values[0]; val y = event.values[1]; val z = event.values[2]
        val accel = sqrt(x * x + y * y + z * z)
        val delta = Math.abs(accel - lastAccel)
        lastAccel = accel
        val now = System.currentTimeMillis()
        if (delta > 12f && now - lastShakeTs > 1200) {
            lastShakeTs = now
            bubbleView?.let {
                toggleVpn(it.findViewById(R.id.statusDot), it.findViewById(R.id.bubbleStatus))
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun registerVolume() {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        lastVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        volumeReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (!volumeEnabled) return
                val vol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                if (lastVolume >= 0 && vol != lastVolume) {
                    bubbleView?.let {
                        toggleVpn(it.findViewById(R.id.statusDot), it.findViewById(R.id.bubbleStatus))
                    }
                }
                lastVolume = vol
            }
        }
        // VOLUME_CHANGED_ACTION is hidden; use common string
        val filter = IntentFilter("android.media.VOLUME_CHANGED_ACTION")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(volumeReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(volumeReceiver, filter)
        }
    }

    private fun unregisterVolume() {
        try { volumeReceiver?.let { unregisterReceiver(it) } } catch (_: Exception) {}
        volumeReceiver = null
    }

    // ---- VPN ----

    private fun toggleVpn(statusDot: View, statusText: TextView) {
        if (isVpnRunning) {
            stopVpn()
            Toast.makeText(this, "VPN OFF", Toast.LENGTH_SHORT).show()
        } else if (VpnService.prepare(this) != null) {
            requestVpnPermissionFromActivity()
        } else {
            startVpnWithCurrent()
        }
        updateUi(statusDot, statusText)
    }

    private fun requestVpnPermissionFromActivity() {
        val request = Intent(this, MainActivity::class.java).apply {
            action = ACTION_REQUEST_VPN_PERMISSION
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(AnonVpnService.EXTRA_LATENCY_MS, currentLatency)
            putExtra(AnonVpnService.EXTRA_PACKET_LOSS, currentLoss)
            putExtra(AnonVpnService.EXTRA_BANDWIDTH_KBS, currentBandwidthKbs)
            putExtra(AnonVpnService.EXTRA_BLOCK_DOWNLOAD, blockDownload)
        }
        try {
            startActivity(request)
        } catch (e: Exception) {
            Toast.makeText(this, "VPN izni ekranı açılamadı: ${e.message ?: "bilinmeyen hata"}", Toast.LENGTH_LONG).show()
        }
    }

    private fun applySettings(statusDot: View, statusText: TextView) {
        if (isVpnRunning) stopVpn()
        if (VpnService.prepare(this) != null) {
            requestVpnPermissionFromActivity()
        } else {
            startVpnWithCurrent()
        }
        val bw = if (currentBandwidthKbs == 0) "∞" else "$currentBandwidthKbs KB/s"
        Toast.makeText(this, "Uygulandı → ${currentLatency}ms | %$currentLoss | $bw", Toast.LENGTH_SHORT).show()
        updateUi(statusDot, statusText)
    }

    private fun startVpnWithCurrent() {
        val intent = Intent(this, AnonVpnService::class.java).apply {
            putExtra(AnonVpnService.EXTRA_LATENCY_MS, currentLatency)
            putExtra(AnonVpnService.EXTRA_PACKET_LOSS, currentLoss)
            putExtra(AnonVpnService.EXTRA_BANDWIDTH_KBS, currentBandwidthKbs)
            putExtra(AnonVpnService.EXTRA_BLOCK_DOWNLOAD, blockDownload)
        }
        try {
            startForegroundService(intent)
            isVpnRunning = true
        } catch (e: Exception) {
            isVpnRunning = false
            Toast.makeText(this, "VPN başlatılamadı: ${e.message ?: "başlatma hatası"}", Toast.LENGTH_LONG).show()
            bubbleView?.let {
                updateUi(it.findViewById(R.id.statusDot), it.findViewById(R.id.bubbleStatus))
            }
            return
        }

        autoStopRunnable?.let { handler.removeCallbacks(it) }
        if (currentDurationSec > 0) {
            autoStopRunnable = Runnable {
                stopVpn()
                bubbleView?.let {
                    updateUi(it.findViewById(R.id.statusDot), it.findViewById(R.id.bubbleStatus))
                }
                Toast.makeText(this, "Süre doldu – OFF", Toast.LENGTH_SHORT).show()
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
        autoStopRunnable?.let { handler.removeCallbacks(it) }
        unregisterShake()
        unregisterVolume()
        bubbleView?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        bubbleView = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
