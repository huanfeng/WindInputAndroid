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
 * 按键气泡：按下预览（单字符放大）、长按多字符选择（横移换焦点，抬手上屏）、
 * 功能键长按的竖排菜单、上滑副字符的一体式气泡。
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

    /**
     * 长按功能键的竖排菜单（如中英键：各方案 + 切换输入法）。从键位上方往上排，左沿对齐键位，
     * 手指上移逐项选中、抬手定；初始高亮 [selected]（当前状态）。
     *
     * 竖排而不是横排：方案名长短不一、个数不定，横排一多就得缩字号挤成一行；中英键又在
     * 底栏靠边，横条大半伸到手指够不着的另一侧。竖排时手指只管上下，离键最近的是最后一项。
     */
    fun showMenu(keyRect: RectF, labels: List<String>, selected: Int) {
        dismissAll()
        if (labels.isEmpty()) return
        val pad = (16 * density).toInt()
        val views = labels.map { label ->
            bubbleText(label, 16f).also {
                it.background = null
                it.maxLines = 1
                it.gravity = Gravity.CENTER_VERTICAL or Gravity.START
                it.setPadding(pad, 0, pad, 0)
            }
        }
        val w = maxOf(
            (120 * density).toInt(),
            views.maxOf { it.paint.measureText(it.text.toString()).toInt() } + 2 * pad,
        ).coerceAtMost(anchor.width)
        // 方案多、上方放不下时行高往下收（不低于 32dp），条整个仍在屏内
        val loc = IntArray(2)
        anchor.getLocationOnScreen(loc)
        val room = loc[1] + keyRect.top - 40 * density
        val rowH = (room / labels.size).coerceIn(32 * density, 44 * density).toInt()
        val col = LinearLayout(anchor.context).apply {
            orientation = LinearLayout.VERTICAL
            background = bubbleBackground()
        }
        views.forEach { col.addView(it, LinearLayout.LayoutParams(w, rowH)) }
        popupItems = views
        val h = rowH * labels.size
        val win = IntArray(2)
        anchor.getLocationInWindow(win)
        val x = (win[0] + keyRect.left).toInt().coerceIn(win[0], win[0] + anchor.width - w)
        val y = (win[1] + keyRect.top - h - 8 * density).toInt()
        menuTop = y - win[1]
        menuRowH = rowH
        selectedIndex = selected.coerceIn(0, labels.size - 1)
        menuInitial = selectedIndex
        highlight()
        popup = popupWindow(col, w, h).also {
            it.showAtLocation(anchor, Gravity.NO_GRAVITY, x, y)
        }
    }

    /** [showMenu] 的竖排菜单：顶边（anchor 坐标）、行高；行高为 0 表示当前是横排字符气泡 */
    private var menuTop = 0
    private var menuRowH = 0
    private var menuInitial = 0

    /** 手指位置（anchor 坐标）更新选中项：字符气泡看横向，竖排菜单看纵向 */
    fun updatePopupSelection(x: Float, y: Float) {
        if (popupItems.isEmpty()) return
        selectedIndex = if (menuRowH > 0) {
            // 手指还在菜单下沿以下（键位上）：不算选了哪项，保持初始项——抬手即作罢。
            // 否则一按住往旁边蹭一下，就会落到离键最近的最后一项（切换输入法）上
            val bottom = menuTop + menuRowH * popupItems.size
            if (y >= bottom) menuInitial
            else ((y - menuTop) / menuRowH).toInt().coerceIn(0, popupItems.size - 1)
        } else {
            val loc = IntArray(2)
            anchor.getLocationInWindow(loc)
            ((x + loc[0] - popupLeft) / itemWidthPx).toInt().coerceIn(0, popupItems.size - 1)
        }
        highlight()
    }

    /** 当前选中项**下标**；未弹出返回 null。调用方按下标映回自己的字符表（气泡里显示的可能是换过形态的） */
    fun popupSelectedIndex(): Int? = if (popupItems.isEmpty()) null else selectedIndex

    fun dismissPopup() {
        popup?.dismiss()
        popup = null
        popupItems = emptyList()
        menuRowH = 0
    }

    // ── 上滑一体式气泡 ───────────────────────────────────────────
    private var swipeBubble: PopupWindow? = null
    private var swipeTop: TextView? = null
    private var swipeMain: TextView? = null

    /**
     * 上滑副字符的一体式气泡：从键位向上长出一截（下沿与键位齐平、比键略宽），把键整个罩住，
     * 上半是副字符、下半是主字符——看着是键自己伸长了，而不是另外飘出一个框。
     * 上滑过阈值时副字符放大变强调色（[setSwipeArmed]），抬手上屏的就是它；退回来恢复。
     */
    fun showSwipeBubble(keyRect: RectF, main: String, swipe: String) {
        dismissAll()
        val w = (keyRect.width() + 8 * density).toInt()
        val h = (keyRect.height() * 2 + 4 * density).toInt()
        val top = bubbleText(swipe, 22f).also { it.background = null }
        val bottom = bubbleText(main, 18f).also { it.background = null }
        swipeTop = top
        swipeMain = bottom
        val col = LinearLayout(anchor.context).apply {
            orientation = LinearLayout.VERTICAL
            background = bubbleBackground()
            addView(top, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.1f))
            addView(bottom, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 0.9f))
        }
        val loc = IntArray(2)
        anchor.getLocationInWindow(loc)
        val x = (loc[0] + keyRect.centerX() - w / 2f).toInt().coerceIn(loc[0], loc[0] + anchor.width - w)
        val y = (loc[1] + keyRect.bottom + 2 * density - h).toInt()
        setSwipeArmed(false)
        swipeBubble = popupWindow(col, w, h).also {
            it.showAtLocation(anchor, Gravity.NO_GRAVITY, x, y)
        }
    }

    fun setSwipeArmed(armed: Boolean) {
        val p = Palette.current
        swipeTop?.apply {
            setTextColor(if (armed) p.accentText else p.hint)
            textSize = if (armed) 28f else 22f
            typeface = if (armed) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
        }
        swipeMain?.setTextColor(if (armed) p.hint else p.text)
    }

    private fun dismissSwipeBubble() {
        swipeBubble?.dismiss()
        swipeBubble = null
        swipeTop = null
        swipeMain = null
    }

    fun dismissAll() {
        dismissPreview()
        dismissPopup()
        dismissSwipeBubble()
    }

    // ── 内部 ─────────────────────────────────────────────────────

    private fun highlight() {
        popupItems.forEachIndexed { i, tv ->
            tv.setTextColor(if (i == selectedIndex) Palette.current.accentText else Palette.current.text)
            tv.setBackgroundColor(
                if (i == selectedIndex) Palette.current.accentSoft else Color.TRANSPARENT,
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
            setTextColor(Palette.current.text)
            background = bubbleBackground()
        }

    private fun bubbleBackground(): GradientDrawable =
        GradientDrawable().apply {
            setColor(Palette.current.key)
            cornerRadius = 10 * density
            setStroke((1 * density).toInt(), Palette.current.border)
        }
}
