package com.windinput.ime.keyboard

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
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
 * 收不到触摸，菜单必须落在窗口里才点得到。
 *
 * 点菜单外任何地方即收起，**不执行任何动作**：浮层是一层从条区顶边铺到输入视图底部的透明底，
 * 卡片放在上面。只用 outsideTouchable 做不到——不可聚焦的 PopupWindow 不拦外面的触摸，那一下照样
 * 落到底下的键上，「点空白处关菜单」就会顺手打出一个字母。
 */
class CandidateMenuPopup(private val anchor: View) {

    private val density = anchor.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private var popup: PopupWindow? = null

    /**
     * @param dismissOnOutside 点 IME 窗口以外（应用那边）是否收起。首次使用的方案卡片传 false：
     *   第一次打开输入框，用户顺手再点一下文本框，引导卡片还没看清就没了
     */
    fun show(
        title: String,
        items: List<CandidateMenuEntry>,
        dismissOnOutside: Boolean = true,
        onPick: (CandidateMenuEntry) -> Unit,
    ) {
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

        val root = anchor.rootView
        // 条区还没量好（刚重建）时宽高为 0，算出的尺寸是负数，PopupWindow 不认
        if (anchor.width <= 0 || root.height <= 0) return
        val loc = IntArray(2).also(anchor::getLocationInWindow)
        // 透明底从条区顶边起：以条区为原点定位（以根视图为原点会差出一个状态栏高度）。
        // 高度铺到输入视图底，盖住候选条与整个键盘
        val shadeHeight = root.height - loc[1]
        if (shadeHeight <= anchor.height) return
        content.isClickable = true // 点卡片空白处不算「点外面」
        val shade = FrameLayout(anchor.context).apply {
            setOnClickListener { dismiss() }
            addView(
                content,
                FrameLayout.LayoutParams(anchor.width - dp(16), FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                    leftMargin = dp(8)
                    topMargin = anchor.height + dp(4)
                },
            )
        }
        popup = PopupWindow(shade, anchor.width, shadeHeight, false).apply {
            // 输入视图比可见区多出底部导航那一截：开着裁剪时 PopupWindow 会把超出屏幕底的部分
            // 往上推，整张卡片随之上移一个状态栏高度、盖住候选条
            isClippingEnabled = false
            isTouchable = true
            // IME 窗口以外（应用那边）被点时也收起（首次使用的卡片除外，见 show 的参数说明）
            isOutsideTouchable = dismissOnOutside
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            showAtLocation(anchor, Gravity.NO_GRAVITY, loc[0], loc[1])
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
