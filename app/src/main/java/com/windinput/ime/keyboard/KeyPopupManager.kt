package com.windinput.ime.keyboard

import android.graphics.Color
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView

/**
 * 按键气泡：按下预览（单字符放大）与长按多字符选择（横移换焦点，抬手上屏）。
 * PopupWindow 关闭裁剪以便伸出 IME 窗口顶部（fcitx5 PopupComponent 同思路）。
 */
class KeyPopupManager(private val anchor: View) {

    private val density = anchor.resources.displayMetrics.density

    // ── 预览气泡 ─────────────────────────────────────────────────
    private var preview: PopupWindow? = null
    private var previewLabel: TextView? = null

    fun showPreview(keyRect: RectF, label: String) {
        dismissPreview()
        val w = (52 * density).toInt()
        val h = (56 * density).toInt()
        val tv = bubbleText(label, 26f)
        previewLabel = tv
        val pos = positionAbove(keyRect, w, h)
        preview = popupWindow(tv, w, h).also {
            it.showAtLocation(anchor, Gravity.NO_GRAVITY, pos[0], pos[1])
        }
    }

    fun dismissPreview() {
        preview?.dismiss()
        preview = null
        previewLabel = null
    }

    // ── 长按选择气泡 ─────────────────────────────────────────────
    private var popup: PopupWindow? = null
    private var popupItems: List<TextView> = emptyList()
    private var popupLeft = 0
    private var itemWidthPx = 0
    private var selectedIndex = 0

    val isPopupShown: Boolean get() = popup != null

    fun showPopup(keyRect: RectF, chars: List<String>) {
        dismissAll()
        if (chars.isEmpty()) return
        itemWidthPx = (44 * density).toInt()
        val h = (52 * density).toInt()
        val w = itemWidthPx * chars.size
        val row = LinearLayout(anchor.context).apply { orientation = LinearLayout.HORIZONTAL }
        popupItems = chars.map { ch ->
            bubbleText(ch, 22f).also {
                it.background = null
                row.addView(it, LinearLayout.LayoutParams(itemWidthPx, h))
            }
        }
        row.background = bubbleBackground()
        val pos = positionAbove(keyRect, w, h)
        popupLeft = pos[0]
        selectedIndex = 0
        highlight()
        popup = popupWindow(row, w, h).also {
            it.showAtLocation(anchor, Gravity.NO_GRAVITY, pos[0], pos[1])
        }
    }

    /** 手指横移（anchor 窗口坐标系 x）更新选中项 */
    fun updatePopupSelection(x: Float) {
        if (popupItems.isEmpty()) return
        selectedIndex = ((x - popupLeft) / itemWidthPx).toInt()
            .coerceIn(0, popupItems.size - 1)
        highlight()
    }

    /** 当前选中字符；未弹出返回 null */
    fun popupSelected(): String? =
        popupItems.getOrNull(selectedIndex)?.text?.toString()

    fun dismissPopup() {
        popup?.dismiss()
        popup = null
        popupItems = emptyList()
    }

    fun dismissAll() {
        dismissPreview()
        dismissPopup()
    }

    // ── 内部 ─────────────────────────────────────────────────────

    private fun highlight() {
        popupItems.forEachIndexed { i, tv ->
            tv.setTextColor(if (i == selectedIndex) 0xFF3B82F6.toInt() else 0xFF1E1E1E.toInt())
            tv.setBackgroundColor(
                if (i == selectedIndex) 0xFFE6F0FF.toInt() else Color.TRANSPARENT,
            )
        }
    }

    /** 气泡置于键位上方居中，水平钳制在锚点视图范围内 */
    private fun positionAbove(keyRect: RectF, w: Int, h: Int): IntArray {
        val loc = IntArray(2)
        anchor.getLocationInWindow(loc)
        val x = (loc[0] + keyRect.centerX() - w / 2f).toInt()
            .coerceIn(loc[0], loc[0] + anchor.width - w)
        val y = (loc[1] + keyRect.top - h - 8 * density).toInt()
        return intArrayOf(x, y)
    }

    private fun popupWindow(content: View, w: Int, h: Int): PopupWindow =
        PopupWindow(content, w, h, false).apply {
            isClippingEnabled = false
            isTouchable = false
        }

    private fun bubbleText(label: String, size: Float): TextView =
        TextView(anchor.context).apply {
            text = label
            textSize = size
            gravity = Gravity.CENTER
            setTextColor(0xFF1E1E1E.toInt())
            background = bubbleBackground()
        }

    private fun bubbleBackground(): GradientDrawable =
        GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = 10 * density
            setStroke((1 * density).toInt(), 0xFFC8C8C8.toInt())
        }
}
