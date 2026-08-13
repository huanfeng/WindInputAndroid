package com.windinput.ime.service

import android.inputmethodservice.InputMethodService
import android.view.View
import android.widget.LinearLayout
import com.windinput.ime.keyboard.CandidateBarView
import com.windinput.ime.keyboard.KeyAction
import com.windinput.ime.keyboard.KeyboardView

/**
 * 输入法服务：Android IME 框架对接层。
 *
 * 生命周期与主仓协议语义的对应（见 docs/architecture.md §3.1）：
 * - onStartInput  ↔ CMD_FOCUS_GAINED
 * - onFinishInput ↔ CMD_FOCUS_LOST
 * - 键事件 → VK 码 → Rust 核心 handle_key_event（walking skeleton 后接通）
 *
 * 当前占位阶段：按键直接经 InputConnection 透传上屏，不经引擎。
 */
class WindInputImeService : InputMethodService(), KeyboardView.Listener {

    private var candidateBar: CandidateBarView? = null

    override fun onCreateInputView(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        candidateBar = CandidateBarView(this)
        val keyboard = KeyboardView(this).apply { listener = this@WindInputImeService }
        root.addView(
            candidateBar,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        root.addView(
            keyboard,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        return root
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onKeyAction(action: KeyAction, shifted: Boolean) {
        val ic = currentInputConnection ?: return
        when (action) {
            is KeyAction.Text -> {
                val ch = if (shifted) action.char.uppercaseChar() else action.char
                ic.commitText(ch.toString(), 1)
            }
            KeyAction.Space -> ic.commitText(" ", 1)
            KeyAction.Backspace -> ic.deleteSurroundingText(1, 0)
            KeyAction.Enter -> {
                if (!sendDefaultEditorAction(true)) {
                    ic.commitText("\n", 1)
                }
            }
            KeyAction.SwitchSymbols -> {
                // 占位：符号层布局尚未实现
            }
            KeyAction.Shift -> {
                // Shift 态由 KeyboardView 内部维护，不会传到这里
            }
        }
    }
}
