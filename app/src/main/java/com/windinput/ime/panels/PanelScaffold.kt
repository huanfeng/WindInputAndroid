package com.windinput.ime.panels

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.windinput.ime.keyboard.BarView
import com.windinput.ime.keyboard.KeyboardDimens
import com.windinput.ime.keyboard.PanelIcon

/**
 * 面板脚手架：**顶栏（左上角返回 + 标题）+ 内容区**。
 *
 * # 为什么面板要自带顶栏，而不是沿用上方的工具栏
 *
 * 此前面板打开时，上方的条区（工具栏/候选栏）原样留着，面板再叠一层自己的标题栏
 * ——两条横栏上下并排，既占掉两行高度，又让「返回」和「收起键盘」两个语义相近的
 * 按钮同时出现。现在改成：**面板打开则条区整个让位**，面板自己接管那块高度，
 * 左上角就是唯一的返回入口。
 *
 * 面板总高 = 条区高 + 键盘高，正好等于让位前的整体高度，所以切换面板时
 * IME 窗口高度不变、键盘不跳。
 */
object PanelScaffold {

    const val KEYBOARD_ROWS = 4

    /** 顶栏高度：与条区的编码栏 + 主行等高，面板占位才与键盘态严丝合缝 */
    val headerHeightDp: Float get() = BarView.TOTAL_DP

    /** 面板整体高度（px）= 条区 + 键盘四行 */
    fun panelHeightPx(context: Context): Int {
        val density = context.resources.displayMetrics.density
        return ((BarView.TOTAL_DP + KEYBOARD_ROWS * KeyboardDimens.rowHeightDp) * density).toInt()
    }

    /**
     * 构建面板骨架。
     *
     * @param onBack 返回键盘（左上角）
     * @param rightActions 顶栏右侧动作按钮（label → onClick）
     */
    fun build(
        context: Context,
        title: String,
        onBack: () -> Unit,
        content: View,
        rightActions: List<Pair<String, () -> Unit>> = emptyList(),
    ): View {
        val density = context.resources.displayMetrics.density
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFF0F2F5.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                panelHeightPx(context),
            )
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFFFAFAFC.toInt())
        }
        header.addView(
            backButton(context, onBack),
            LinearLayout.LayoutParams(
                (52 * density).toInt(),
                LinearLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        header.addView(
            TextView(context).apply {
                text = title
                textSize = 15f
                gravity = Gravity.CENTER_VERTICAL
                setTextColor(0xFF1E1E1E.toInt())
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f),
        )
        for ((label, onClick) in rightActions) {
            header.addView(
                headerButton(context, label, onClick),
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.MATCH_PARENT,
                ),
            )
        }
        root.addView(
            header,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (headerHeightDp * density).toInt(),
            ),
        )
        root.addView(
            content,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        return root
    }

    /** 左上角返回：矢量箭头 + 触摸反馈，比文字「⌨ 返回」更符合移动端习惯 */
    private fun backButton(context: Context, onBack: () -> Unit): View =
        PanelIcon(context).apply {
            glyph = PanelIcon.Glyph.BACK
            tint = 0xFF1E1E1E.toInt()
            setOnClickListener { onBack() }
        }

    private fun headerButton(context: Context, label: String, onClick: () -> Unit): TextView =
        TextView(context).apply {
            val density = context.resources.displayMetrics.density
            text = label
            gravity = Gravity.CENTER
            textSize = 14f
            setTextColor(0xFF4A78C8.toInt())
            setPadding((14 * density).toInt(), 0, (14 * density).toInt(), 0)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { onClick() }
        }
}
