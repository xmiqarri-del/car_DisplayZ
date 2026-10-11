package com.xmiqarri.cardisplayz

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.ViewGroup
import kotlin.math.roundToInt

const val MATCH: Int = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP: Int = ViewGroup.LayoutParams.WRAP_CONTENT

/** Converts design dp to pixels for this device. */
fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

/** Rounded rectangle with optional border, used for every card and button in the app. */
fun roundedBackground(fill: Int, radius: Float, stroke: Int = 0, strokeWidth: Int = 0): GradientDrawable =
    GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radius
        setColor(fill)
        if (stroke != 0 && strokeWidth > 0) setStroke(strokeWidth, stroke)
    }
