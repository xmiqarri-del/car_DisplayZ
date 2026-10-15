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
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.view.Display
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.VirtualDisplay
import androidx.core.app.NotificationCompat
import com.xmiqarri.cardisplayz.R

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
        val window = Presentation(this, target)
        val view = SurfaceView(window.context)
        view.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                val mode = target.mode
                virtualDisplay = projection?.createVirtualDisplay(
                    "CarDisplayZMirror",
                    mode.physicalWidth,
                    mode.physicalHeight,
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
        window.setContentView(view)
        window.show()
        presentation = window
    }

    override fun onDestroy() {
        running = false
        virtualDisplay?.release()
        virtualDisplay = null
        presentation?.dismiss()
        presentation = null
        projection?.stop()
        projection = null
        super.onDestroy()
    }
}
