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
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ImageView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.xmiqarri.cardisplayz.AppSettings
import com.xmiqarri.cardisplayz.DragTapListener
import com.xmiqarri.cardisplayz.R
import com.xmiqarri.cardisplayz.apps.AppGridActivity
import com.xmiqarri.cardisplayz.dp
import com.xmiqarri.cardisplayz.lock.CarLock
import com.xmiqarri.cardisplayz.lock.FobLockAssist
import com.xmiqarri.cardisplayz.update.UpdateWorker

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

        if (!AppSettings.floatingEnabled(this) || !Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_REFRESH) rebuildBubble() else addBubble()
        // Resumes the key-lock watch after a restart; does nothing when the setting is off.
        FobLockAssist.sync(this)
        CarLock.resumeRestore(this)
        // Background update checks run from here too, so they start after the car boots.
        UpdateWorker.schedule(this)
        return START_STICKY
    }

    override fun onDestroy() {
        bubble?.let { view -> runCatching { windowManager?.removeView(view) } }
        bubble = null
        super.onDestroy()
    }

    /** Removes the current button and draws it again with the latest size and opacity. */
    private fun rebuildBubble() {
        bubble?.let { view -> runCatching { windowManager?.removeView(view) } }
        bubble = null
        addBubble()
    }

    private fun addBubble() {
        if (bubble != null) return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val sizeDp = AppSettings.floatingSizeDp(this)
        val size = dp(sizeDp)
        // The icon keeps the same proportion inside the circle at every size (9 of 40 dp at the default).
        val paddingPx = dp(sizeDp * 9 / 40)
        // Opacity is set on the whole view, so the circle and the icon fade together.
        val opacity = AppSettings.floatingAlpha(this) / 100f

        val view = ImageView(this).apply {
            setImageResource(R.drawable.ic_grid)
            setPadding(paddingPx, paddingPx, paddingPx, paddingPx)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF2BB3C0.toInt())
            }
            alpha = opacity
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
            DragTapListener(
                wm = wm,
                params = params,
                slop = ViewConfiguration.get(this).scaledTouchSlop,
                moveTarget = view,
                onTap = { openApps() },
            )
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

    companion object {
        private const val CHANNEL_ID = "overlay"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_REFRESH = "com.xmiqarri.cardisplayz.overlay.REFRESH"

        /** Redraws the floating button so a new size or opacity shows at once. Does nothing when it is off. */
        fun refresh(context: Context) {
            if (!AppSettings.floatingEnabled(context) || !Settings.canDrawOverlays(context)) return
            try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, OverlayService::class.java).setAction(ACTION_REFRESH)
                )
            } catch (e: RuntimeException) {
                // Same as start(): Android may refuse a background start; the next app open fixes it.
            }
        }

        /** Starts the floating button if the user has turned it on and allowed it. Safe to call repeatedly. */
        fun start(context: Context) {
            if (!AppSettings.floatingEnabled(context) || !Settings.canDrawOverlays(context)) return
            try {
                ContextCompat.startForegroundService(context, Intent(context, OverlayService::class.java))
            } catch (e: RuntimeException) {
                // Android can refuse a start from the background (ForegroundServiceStartNotAllowedException).
                // Opening the app starts the floating button again, so nothing else is needed here.
            }
        }

        /** Removes the floating button and stops the service. */
        fun stop(context: Context) {
            context.stopService(Intent(context, OverlayService::class.java))
        }
    }
}
