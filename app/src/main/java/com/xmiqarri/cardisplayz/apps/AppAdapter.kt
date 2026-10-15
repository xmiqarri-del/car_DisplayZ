package com.xmiqarri.cardisplayz.apps

import android.content.res.ColorStateList
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.xmiqarri.cardisplayz.MATCH
import com.xmiqarri.cardisplayz.Theme
import com.xmiqarri.cardisplayz.WRAP
import com.xmiqarri.cardisplayz.dp
import com.xmiqarri.cardisplayz.roundedBackground

/**
 * Grid tiles for the app list. [compact] tiles have a fixed width for the horizontal "most used" row.
 * A tap calls [onClick]; a long press calls [onLongClick] (used to delete an app).
 */
class AppAdapter(
    private val compact: Boolean,
    private val onClick: (AppItem) -> Unit,
    private val onLongClick: (AppItem) -> Unit,
) : RecyclerView.Adapter<AppAdapter.Holder>() {

    private var items: List<AppItem> = emptyList()

    fun submit(list: List<AppItem>) {
        items = list
        notifyDataSetChanged()
    }

    class Holder(
        val root: LinearLayout,
        val icon: ImageView,
        val label: TextView,
    ) : RecyclerView.ViewHolder(root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val ctx = parent.context

        val icon = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(ctx.dp(60), ctx.dp(60))
        }
        val label = TextView(ctx).apply {
            setTextColor(Theme.TEXT_PRIMARY)
            textSize = 15f
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = ctx.dp(10) }
        }

        val card = roundedBackground(Theme.SURFACE, ctx.dp(20).toFloat(), Theme.SURFACE_BORDER, ctx.dp(1))
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(ctx.dp(8), ctx.dp(16), ctx.dp(8), ctx.dp(14))
            background = RippleDrawable(ColorStateList.valueOf(Theme.SURFACE_PRESSED), card, null)
            val width = if (compact) ctx.dp(116) else MATCH
            layoutParams = RecyclerView.LayoutParams(width, WRAP).apply {
                setMargins(ctx.dp(6), ctx.dp(6), ctx.dp(6), ctx.dp(6))
            }
            addView(icon)
            addView(label)
        }
        return Holder(root, icon, label)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        holder.icon.setImageDrawable(item.icon)
        holder.label.text = item.label
        holder.root.setOnClickListener { onClick(item) }
        holder.root.setOnLongClickListener {
            onLongClick(item)
            true
        }
    }

    override fun getItemCount(): Int = items.size
}
