package com.xmiqarri.cardisplayz.overlay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.xmiqarri.cardisplayz.AppSettings
import com.xmiqarri.cardisplayz.update.UpdateWorker

/**
 * Brings the floating button back when the car system starts (if "start on boot" is on),
 * and after this app is updated, because installing an update stops the running floating button.
 * Android delivers these broadcasts only to an app that has been opened since it was installed
 * or last force-stopped (see README).
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, QUICKBOOT_POWERON -> {
                if (AppSettings.startOnBoot(context)) OverlayService.start(context)
            }
            Intent.ACTION_MY_PACKAGE_REPLACED -> OverlayService.start(context)
            else -> return
        }
        UpdateWorker.schedule(context)
    }

    private companion object {
        const val QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON"
    }
}
