package com.xmiqarri.cardisplayz.mirror

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Presentation
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.view.Display
import android.view.SurfaceHolder
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.View
import androidx.core.app.NotificationCompat
import com.xmiqarri.cardisplayz.R
import com.xmiqarri.cardisplayz.adb.AdbGranter
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Shows what the main screen (display 0) shows on the rear screen.
 *
 * Android only lets an app capture the main screen through MediaProjection, which needs the user's
 * consent (MirrorConsentActivity). The captured content is sent to a virtual display that mirrors
 * the main screen (VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR), drawn on a full-screen Presentation on the rear screen.
 */
class MirrorService : Service() {

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
        const val EXTRA_DISPLAY_ID = "display_id"

        private const val NOTIFICATION_ID = 41
        private const val CHANNEL_ID = "mirror"

        /** True while the rear screen shows the main screen. */
        @Volatile
        var running = false
            private set

        fun stop(context: Context) {
            running = false
            context.stopService(Intent(context, MirrorService::class.java))
        }
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var touchInjector: TouchInjector? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.mirror_channel), NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data: Intent? = intent?.getParcelableExtra(EXTRA_DATA)
        val displayId = intent?.getIntExtra(EXTRA_DISPLAY_ID, -1) ?: -1
        val target = getSystemService(DisplayManager::class.java).getDisplay(displayId)

        if (resultCode != Activity.RESULT_OK || data == null || target == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        // Must be in the foreground before the projection is taken.
        startForeground(
            NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_slideshow)
                .setContentTitle(getString(R.string.mirror_notification))
                .setOngoing(true)
                .build(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
        )

        val mediaProjection = getSystemService(MediaProjectionManager::class.java).getMediaProjection(resultCode, data)
        projection = mediaProjection
        mediaProjection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopSelf()
            }
        }, null)

        showOn(target)
        running = true
        return START_NOT_STICKY
    }

    /** Full-screen window on the rear display; the mirror starts once its surface exists. */
    private fun showOn(target: Display) {
        val main = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val mainMode = main.mode
        val window = Presentation(this, target)
        val view = SurfaceView(window.context)
        view.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                // Same size as the main screen, so a touch position on the rear screen maps to the same place on the main screen.
                virtualDisplay = projection?.createVirtualDisplay(
                    "CarDisplayZMirror",
                    mainMode.physicalWidth,
                    mainMode.physicalHeight,
                    resources.displayMetrics.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    holder.surface,
                    null,
                    null,
                )
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                virtualDisplay?.release()
                virtualDisplay = null
            }
        })
        val injector = TouchInjector { AdbGranter.connect(this@MirrorService) }
        touchInjector = injector
        view.setOnTouchListener(TouchForwarder(injector, mainMode.physicalWidth, mainMode.physicalHeight))
        window.setContentView(view)
        window.show()
        presentation = window
    }

    /** Turns a tap or swipe on the rear screen into a tap or swipe on the main screen. */
    private inner class TouchForwarder(
        private val injector: TouchInjector,
        private val mainWidth: Int,
        private val mainHeight: Int,
    ) : View.OnTouchListener {

        private var downX = 0f
        private var downY = 0f
        private var downTime = 0L
        private val slop = resources.displayMetrics.density * 24

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            if (view.width <= 0 || view.height <= 0) return true
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    downTime = event.eventTime
                }
                MotionEvent.ACTION_UP -> {
                    val x2 = toMainX(view, event.x)
                    val y2 = toMainY(view, event.y)
                    if (hypot((event.x - downX).toDouble(), (event.y - downY).toDouble()) < slop) {
                        injector.tap(x2, y2)
                    } else {
                        val duration = (event.eventTime - downTime).coerceIn(100L, 2000L).toInt()
                        injector.swipe(toMainX(view, downX), toMainY(view, downY), x2, y2, duration)
                    }
                }
            }
            return true
        }

        private fun toMainX(view: View, x: Float) = (x * mainWidth / view.width).roundToInt().coerceIn(0, mainWidth - 1)

        private fun toMainY(view: View, y: Float) = (y * mainHeight / view.height).roundToInt().coerceIn(0, mainHeight - 1)
    }

    override fun onDestroy() {
        running = false
        touchInjector?.close()
        touchInjector = null
        virtualDisplay?.release()
        virtualDisplay = null
        presentation?.dismiss()
        presentation = null
        projection?.stop()
        projection = null
        super.onDestroy()
    }
}
