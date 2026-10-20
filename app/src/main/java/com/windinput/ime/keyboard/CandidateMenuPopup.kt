package com.windinput.ime.keyboard

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import com.windinput.ime.core.CandidateMenuEntry

/**
 * 候选长按菜单：盖在键盘区顶部的一张小卡片——标题是那个候选，下面一格一个动作。
 *
 * 菜单项与文案全部来自核心（与电脑版候选右键菜单同一份，见 [com.windinput.ime.core.ImeSession.candidateMenu]），
 * 这里只管画。放在**条区之下、键盘区之上**而不是条区上方：IME 窗口以外的区域在多数系统上
 * 收不到触摸（PreeditPopup 能伸出去是因为它不可点），菜单必须落在窗口里才点得到。
 *
 * 点菜单外任何地方即收起，不执行任何动作。
 */
class CandidateMenuPopup(private val anchor: View) {

    private val density = anchor.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private var popup: PopupWindow? = null

    fun show(title: String, items: List<CandidateMenuEntry>, onPick: (CandidateMenuEntry) -> Unit) {
        dismiss()
        if (items.isEmpty() || anchor.windowToken == null) return
        val p = Palette.current
        val content = LinearLayout(anchor.context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = 10 * density
                setColor(p.surface)
                setStroke(dp(1), p.border)
            }
        }
        content.addView(
            TextView(anchor.context).apply {
                text = title
                textSize = 15f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(p.text)
                setPadding(dp(4), 0, dp(4), dp(6))
            },
        )
        val grid = GridLayout(anchor.context).apply { columnCount = COLUMNS }
        for (item in items) {
            grid.addView(
                TextView(anchor.context).apply {
                    text = item.label
                    textSize = 14f
                    gravity = Gravity.CENTER
                    maxLines = 1
                    setTextColor(if (item.enabled) p.accentText else p.hint)
                    background = GradientDrawable().apply {
                        cornerRadius = 6 * density
                        setColor(p.key)
                    }
                    isEnabled = item.enabled
                    setOnClickListener {
                        dismiss()
                        onPick(item)
                    }
                },
                GridLayout.LayoutParams().apply {
                    width = 0
                    height = dp(40)
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    setMargins(dp(3), dp(3), dp(3), dp(3))
                },
            )
        }
        content.addView(grid)

        val width = anchor.width - dp(16)
        val loc = IntArray(2)
        anchor.getLocationInWindow(loc)
        popup = PopupWindow(content, width, LinearLayout.LayoutParams.WRAP_CONTENT, false).apply {
            isTouchable = true
            isOutsideTouchable = true
            // 有背景才收得到「点外面」的事件；透明，不改观感
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            showAtLocation(anchor, Gravity.NO_GRAVITY, loc[0] + dp(8), loc[1] + anchor.height + dp(4))
        }
    }

    val isShowing: Boolean get() = popup?.isShowing == true

    fun dismiss() {
        popup?.dismiss()
        popup = null
    }

    private companion object {
        const val COLUMNS = 4
    }
}
