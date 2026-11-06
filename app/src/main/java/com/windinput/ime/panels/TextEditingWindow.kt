package com.windinput.ime.panels

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.windinput.ime.keyboard.BackspaceGesture
import com.windinput.ime.keyboard.InputWindow
import com.windinput.ime.keyboard.KeyHaptics
import com.windinput.ime.keyboard.KeyIcons
import com.windinput.ime.keyboard.GlyphDrawable
import com.windinput.ime.keyboard.Palette
import com.windinput.ime.keyboard.PanelIcon

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

        /** 退格键的手势回调（单击 / 连删 / 扩选 / 上滑删光标前或清空） */
        fun editBackspaceGesture(): BackspaceGesture.Listener
    }

    enum class EditAction {
        UP, DOWN, LEFT, RIGHT, HOME, END,
        TOGGLE_SELECT, SELECT_ALL, CUT, COPY, PASTE, BACKSPACE, UNDO, REDO,
    }

    private var selectButton: TextView? = null

    private val ARROWS = mapOf(
        EditAction.UP to PanelIcon.Glyph.ARROW_UP,
        EditAction.DOWN to PanelIcon.Glyph.ARROW_DOWN,
        EditAction.LEFT to PanelIcon.Glyph.ARROW_LEFT,
        EditAction.RIGHT to PanelIcon.Glyph.ARROW_RIGHT,
    )

    override fun onCreateView(context: Context): View {
        val grid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        // 4×3 网格 + 底行撤销 / 重做（各占半行）：文本按钮，功能标注见各 label
        val rows = listOf(
            listOf("全选" to EditAction.SELECT_ALL, "↑" to EditAction.UP, "剪切" to EditAction.CUT, "行首" to EditAction.HOME),
            listOf("←" to EditAction.LEFT, "选择" to EditAction.TOGGLE_SELECT, "→" to EditAction.RIGHT, "行尾" to EditAction.END),
            listOf("复制" to EditAction.COPY, "↓" to EditAction.DOWN, "粘贴" to EditAction.PASTE, "⌫" to EditAction.BACKSPACE),
            listOf("撤销" to EditAction.UNDO, "重做" to EditAction.REDO),
        )
        for (row in rows) {
            val rowView = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            for ((label, action) in row) {
                val btn = cellButton(context, label) {
                    KeyHaptics.tap(it)
                    listener.onEditAction(action)
                    refreshSelectState()
                }
                if (action == EditAction.TOGGLE_SELECT) selectButton = btn
                if (action == EditAction.BACKSPACE) {
                    KeyIcons.applyBackspace(btn, Palette.current.text)
                    val gesture = listener.editBackspaceGesture()
                    BackspaceGesture(gesture, gesture::longPressDelayMs, feedback = { KeyHaptics.tap(btn) }).attach(btn)
                }
                // 方向键用矢量箭头，与键盘上其他功能图标同一套笔画（字体里的 ↑↓←→ 细且各机型不一）
                ARROWS[action]?.let { GlyphDrawable.apply(btn, it, Palette.current.text) }
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
