package com.xmiqarri.cardisplayz.overlay

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ImageView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.xmiqarri.cardisplayz.R
import com.xmiqarri.cardisplayz.apps.AppGridActivity
import com.xmiqarri.cardisplayz.dp
import com.xmiqarri.cardisplayz.update.UpdateWorker
import kotlin.math.abs

/**
 * Keeps a small draggable button on top of every other app. Tapping it opens the app grid.
 * It runs as a foreground service so Android does not stop it; the notification is silent.
 */
class OverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var bubble: View? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_overlay),
            NotificationManager.IMPORTANCE_MIN
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Must be called on every start, including when the service is already running,
        // otherwise Android stops it for not showing its foreground notification.
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_grid)
            .setContentTitle(getString(R.string.overlay_notification_title))
            .setContentText(getString(R.string.overlay_notification_text))
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)

        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        addBubble()
        // Background update checks run from here too, so they start after the car boots.
        UpdateWorker.schedule(this)
        return START_STICKY
    }

    override fun onDestroy() {
        bubble?.let { view -> runCatching { windowManager?.removeView(view) } }
        bubble = null
        super.onDestroy()
    }

    private fun addBubble() {
        if (bubble != null) return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val size = dp(60)

        val view = ImageView(this).apply {
            setImageResource(R.drawable.ic_grid)
            setPadding(dp(15), dp(15), dp(15), dp(15))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xE62BB3C0.toInt())
            }
            contentDescription = getString(R.string.overlay_button_desc)
        }

        val params = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(16)
            y = dp(160)
        }

        view.setOnTouchListener(
            DragTapListener(wm, params, ViewConfiguration.get(this).scaledTouchSlop) { openApps() }
        )

        try {
            wm.addView(view, params)
            windowManager = wm
            bubble = view
        } catch (e: RuntimeException) {
            // Permission was revoked while the service was starting.
            stopSelf()
        }
    }

    private fun openApps() {
        startActivity(
            Intent(this, AppGridActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
    }

    /** Moves the bubble when dragged; calls [onTap] only when the finger did not move. */
    private class DragTapListener(
        private val wm: WindowManager,
        private val params: WindowManager.LayoutParams,
        private val slop: Int,
        private val onTap: () -> Unit,
    ) : View.OnTouchListener {

        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var moved = false

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = params.x
                    startY = params.y
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (abs(dx) > slop || abs(dy) > slop) moved = true
                    if (moved) {
                        params.x = startX + dx.toInt()
                        params.y = startY + dy.toInt()
                        wm.updateViewLayout(v, params)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) onTap()
                }
            }
            return true
        }
    }

    companion object {
        private const val CHANNEL_ID = "overlay"
        private const val NOTIFICATION_ID = 1001

        /** Starts the floating button if the user has allowed it. Safe to call repeatedly. */
        fun start(context: Context) {
            if (!Settings.canDrawOverlays(context)) return
            ContextCompat.startForegroundService(context, Intent(context, OverlayService::class.java))
        }
    }
}
