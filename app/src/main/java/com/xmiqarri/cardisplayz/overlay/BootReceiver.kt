package com.xmiqarri.cardisplayz.overlay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.xmiqarri.cardisplayz.AppSettings
import com.xmiqarri.cardisplayz.update.UpdateWorker

/** Brings the floating button back after the car system starts, if the user kept that setting on. */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != "android.intent.action.QUICKBOOT_POWERON") return
        if (AppSettings.startOnBoot(context)) OverlayService.start(context)
        UpdateWorker.schedule(context)
    }
}
