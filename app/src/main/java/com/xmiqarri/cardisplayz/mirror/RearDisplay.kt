package com.xmiqarri.cardisplayz.mirror

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import com.xmiqarri.cardisplayz.AppSettings

/** The rear screen (any display other than the main one) chosen by the user. */
object RearDisplay {

    /** Every display except the main one, in the order Android lists them. */
    fun candidates(context: Context): List<Display> =
        context.getSystemService(DisplayManager::class.java).displays
            .filter { it.displayId != Display.DEFAULT_DISPLAY }

    /** The chosen rear screen, or null when none is chosen or it is not connected now. */
    fun selected(context: Context): Display? {
        val id = AppSettings.rearDisplayId(context)
        return candidates(context).firstOrNull { it.displayId == id }
    }
}
