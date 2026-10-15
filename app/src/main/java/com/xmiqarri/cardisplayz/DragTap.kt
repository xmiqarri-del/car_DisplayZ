package com.xmiqarri.cardisplayz

import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlin.math.abs

/**
 * Touch handling for a button that lives in its own draggable window.
 * Dragging moves [moveTarget] (the whole window); a touch that does not move calls [onTap].
 * [onDown] runs when a finger goes down, and [onDrop] runs after a drag ends.
 */
class DragTapListener(
    private val wm: WindowManager,
    private val params: WindowManager.LayoutParams,
    private val slop: Int,
    private val moveTarget: View,
    private val onDown: () -> Unit = {},
    private val onDrop: () -> Unit = {},
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
                onDown()
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downX
                val dy = event.rawY - downY
                if (abs(dx) > slop || abs(dy) > slop) moved = true
                if (moved) {
                    params.x = startX + dx.toInt()
                    params.y = startY + dy.toInt()
                    wm.updateViewLayout(moveTarget, params)
                }
            }
            MotionEvent.ACTION_UP -> {
                if (moved) onDrop() else onTap()
            }
        }
        return true
    }
}
