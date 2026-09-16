package com.windinput.ime.keyboard

import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView

/**
 * 编码栏：**浮在条区上方**的小气泡，显示「五 ss 1/34」。
 *
 * 此前编码栏是条区里的一行（26dp），为了 IME 窗口高度恒定，空闲时那 26dp 也得占着
 * ——工具栏于是有 70dp 高，真机上看着太高。改成浮层后条区只剩主行 44dp，编码栏出现时
 * 伸出 IME 窗口顶部，既不顶高键盘、也不占空闲时的高度。fcitx5 的 PreeditUi、Gboard 的
 * 拼音串都是这个做法。
 *
 * PopupWindow 关闭裁剪才能画到 IME 窗口之外；不可触摸，不抢焦点。
 * ⚠ 条区离开窗口（键盘收起、面板切换）时必须 [dismiss]，否则气泡会悬在屏幕上。
 */
class PreeditPopup(private val anchor: View) {

    private val density = anchor.resources.displayMetrics.density
    private fun dp(v: Float) = (v * density).toInt()

    private val p = Palette.current

    private val modeLabel = TextView(anchor.context).apply {
        textSize = 12f
        gravity = Gravity.CENTER
        setTextColor(p.onAccent)
        background = GradientDrawable().apply {
            cornerRadius = 4 * density
            setColor(p.accent)
        }
        setPadding(dp(6f), dp(1f), dp(6f), dp(1f))
    }
    private val preedit = TextView(anchor.context).apply {
        textSize = 15f
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.MIDDLE
        gravity = Gravity.CENTER_VERTICAL
        setTextColor(p.preedit)
        setPadding(dp(8f), 0, dp(4f), 0)
    }
    private val pager = TextView(anchor.context).apply {
        textSize = 12f
        gravity = Gravity.CENTER_VERTICAL
        setTextColor(p.hint)
        setPadding(dp(4f), 0, dp(8f), 0)
    }
    private val content = LinearLayout(anchor.context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8f), 0, 0, 0)
        background = GradientDrawable().apply {
            cornerRadius = 8 * density
            setColor(p.surface)
            setStroke(dp(1f), p.border)
        }
        addView(modeLabel)
        addView(preedit)
        addView(pager)
    }

    private val heightPx = dp(HEIGHT_DP)
    private var popup: PopupWindow? = null

    fun show(text: String, mode: String, pagerText: String) {
        preedit.text = text
        modeLabel.visibility = if (mode.isEmpty()) View.GONE else View.VISIBLE
        modeLabel.text = mode
        pager.text = pagerText
        pager.visibility = if (pagerText.isEmpty()) View.GONE else View.VISIBLE

        // 宽度随内容，上限为条区宽度
        val maxW = anchor.width.takeIf { it > 0 } ?: dp(300f)
        content.measure(
            View.MeasureSpec.makeMeasureSpec(maxW, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY),
        )
        val w = content.measuredWidth
        val loc = IntArray(2)
        anchor.getLocationInWindow(loc)
        val x = loc[0] + dp(6f)
        val y = loc[1] - heightPx - dp(2f)   // 条区之上；为负即伸出 IME 窗口，允许

        val existing = popup
        if (existing != null && existing.isShowing) {
            existing.update(x, y, w, heightPx)
        } else {
            popup = PopupWindow(content, w, heightPx, false).also {
                it.isClippingEnabled = false
                it.isTouchable = false
                it.showAtLocation(anchor, Gravity.NO_GRAVITY, x, y)
            }
        }
    }

    fun dismiss() {
        popup?.dismiss()
        popup = null
    }

    companion object {
        const val HEIGHT_DP = 28f
    }
}
