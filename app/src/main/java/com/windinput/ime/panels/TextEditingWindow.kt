package com.windinput.ime.panels

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.windinput.ime.keyboard.InputWindow
import com.windinput.ime.keyboard.Palette

/**
 * 文本编辑面板：光标移动/选择/剪切复制粘贴（参考 fcitx5 TextEditingWindow）。
 * 编辑操作经 Listener 回调 Service 执行（InputConnection/KeyEvent）。
 */
class TextEditingWindow(private val listener: Listener) : InputWindow() {

    interface Listener {
        fun onEditAction(action: EditAction)

        /** 当前选择模式状态（按住方向键扩选） */
        fun isSelecting(): Boolean

        fun onBackToKeyboard()
    }

    enum class EditAction {
        UP, DOWN, LEFT, RIGHT, HOME, END,
        TOGGLE_SELECT, SELECT_ALL, CUT, COPY, PASTE, BACKSPACE,
    }

    private var selectButton: TextView? = null

    override fun onCreateView(context: Context): View {
        val grid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        // 4×3 网格：文本按钮，功能标注见各 label
        val rows = listOf(
            listOf("全选" to EditAction.SELECT_ALL, "↑" to EditAction.UP, "剪切" to EditAction.CUT, "行首" to EditAction.HOME),
            listOf("←" to EditAction.LEFT, "选择" to EditAction.TOGGLE_SELECT, "→" to EditAction.RIGHT, "行尾" to EditAction.END),
            listOf("复制" to EditAction.COPY, "↓" to EditAction.DOWN, "粘贴" to EditAction.PASTE, "⌫" to EditAction.BACKSPACE),
        )
        for (row in rows) {
            val rowView = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            for ((label, action) in row) {
                val btn = cellButton(context, label) {
                    it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    listener.onEditAction(action)
                    refreshSelectState()
                }
                if (action == EditAction.TOGGLE_SELECT) selectButton = btn
                val gap = (3 * context.resources.displayMetrics.density).toInt()
                rowView.addView(
                    btn,
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                        .apply { setMargins(gap, gap, gap, gap) },
                )
            }
            grid.addView(
                rowView,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f),
            )
        }

        return PanelScaffold.build(
            context = context,
            title = "编辑",
            onBack = listener::onBackToKeyboard,
            content = grid,
        )
    }

    override fun onAttached() = refreshSelectState()

    private fun refreshSelectState() {
        selectButton?.let {
            val selecting = listener.isSelecting()
            it.setTypeface(null, if (selecting) Typeface.BOLD else Typeface.NORMAL)
            it.setTextColor(if (selecting) Palette.current.accentText else Palette.current.text)
        }
    }

    private fun cellButton(context: Context, label: String, onClick: (View) -> Unit): TextView =
        TextView(context).apply {
            val density = context.resources.displayMetrics.density
            text = label
            gravity = Gravity.CENTER
            textSize = 16f
            setTextColor(Palette.current.text)
            setBackgroundColor(Palette.current.key)
            setOnClickListener(onClick)
        }
}
