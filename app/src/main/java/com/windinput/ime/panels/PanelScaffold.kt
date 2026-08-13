package com.windinput.ime.panels

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 面板公共脚手架：标题栏（返回键盘 + 标题 + 右侧动作）+ 内容区，
 * 总高度与键盘一致（4 行 × 56dp），保证窗口切换不跳动。
 * 占位配色内置，正式版接主题调色板。
 */
object PanelScaffold {

    const val KEYBOARD_ROWS = 4
    const val ROW_HEIGHT_DP = 56
    const val HEADER_HEIGHT_DP = 40

    fun panelHeightPx(context: Context): Int =
        (KEYBOARD_ROWS * ROW_HEIGHT_DP * context.resources.displayMetrics.density).toInt()

    /**
     * 构建面板骨架。
     * @param onBack 返回键盘
     * @param rightActions 标题栏右侧动作按钮（label → onClick）
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
            setBackgroundColor(0xFFFAFAFC.toInt())
        }
        header.addView(
            headerButton(context, "⌨ 返回", onBack),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        header.addView(
            TextView(context).apply {
                text = title
                gravity = Gravity.CENTER
                setTextColor(0xFF646464.toInt())
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
                (HEADER_HEIGHT_DP * density).toInt(),
            ),
        )
        root.addView(
            content,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        return root
    }

    private fun headerButton(context: Context, label: String, onClick: () -> Unit): TextView =
        TextView(context).apply {
            val density = context.resources.displayMetrics.density
            text = label
            gravity = Gravity.CENTER
            textSize = 14f
            setTextColor(0xFF1E1E1E.toInt())
            setPadding((14 * density).toInt(), 0, (14 * density).toInt(), 0)
            setOnClickListener { onClick() }
        }
}
