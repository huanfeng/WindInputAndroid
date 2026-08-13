package com.windinput.ime.service

import android.inputmethodservice.InputMethodService
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import com.windinput.ime.WindInputApp
import com.windinput.ime.core.FakeSession
import com.windinput.ime.core.ImeSession
import com.windinput.ime.core.UiEvent
import com.windinput.ime.core.UserConfig
import com.windinput.ime.keyboard.CandidateBarView
import com.windinput.ime.keyboard.KeyAction
import com.windinput.ime.keyboard.KeyboardLayer
import com.windinput.ime.keyboard.KeyboardView
import com.windinput.ime.keyboard.LayoutRepository
import java.io.File

/**
 * 输入法服务：Android IME 框架对接层。
 *
 * 生命周期与主仓协议语义的对应（见 docs/architecture.md §3.1）：
 * - onStartInput  ↔ CMD_FOCUS_GAINED
 * - onFinishInput ↔ CMD_FOCUS_LOST
 *
 * 输入链路：键盘 UI → ImeSession（占位 FakeSession，后由 UniFFI 真实现替换）
 * → UiEvent → 预编辑（setComposingText）/候选条/上屏（commitText）。
 * session.keyPressed 返回 false 时执行默认行为（吃键契约，与桌面同构）。
 */
class WindInputImeService : InputMethodService(), KeyboardView.Listener, CandidateBarView.Listener {

    private var candidateBar: CandidateBarView? = null
    private var keyboard: KeyboardView? = null
    private val session: ImeSession = FakeSession()

    /** 密码等敏感场景：绕过会话直输（对应桌面 compat 场景语义的最小版） */
    private var directInput = false

    private lateinit var layoutRepository: LayoutRepository
    private lateinit var userConfig: UserConfig

    override fun onCreate() {
        super.onCreate()
        session.listener = ::onUiEvent
        val dirs = (application as WindInputApp).dataDirs
        layoutRepository = LayoutRepository(dirs)
        userConfig = UserConfig(File(dirs.userDir, "config.toml"))
    }

    override fun onCreateInputView(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        candidateBar = CandidateBarView(this).apply { listener = this@WindInputImeService }
        keyboard = KeyboardView(this).apply {
            listener = this@WindInputImeService
            layoutProvider = layoutRepository::rows
        }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        root.addView(candidateBar, lp)
        root.addView(keyboard, lp)
        return root
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        session.focusGained()
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        // 设置项热生效：小文件，每次展开键盘时重读
        keyboard?.hapticEnabled = userConfig.getBoolean("ui.android.haptic", true)
        applyEditorScene(info)
    }

    override fun onFinishInput() {
        session.focusLost()
        super.onFinishInput()
    }

    /** EditorInfo 场景感知：密码框直输、数字/电话框初始切符号层 */
    private fun applyEditorScene(info: EditorInfo?) {
        val inputType = info?.inputType ?: 0
        val clazz = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION

        directInput = clazz == InputType.TYPE_CLASS_TEXT && variation in setOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
        )
        if (directInput) {
            session.reset()
        }

        val numericScene = clazz == InputType.TYPE_CLASS_NUMBER ||
            clazz == InputType.TYPE_CLASS_PHONE ||
            clazz == InputType.TYPE_CLASS_DATETIME
        keyboard?.setLayer(if (numericScene) KeyboardLayer.SYMBOLS else KeyboardLayer.LETTERS)
    }

    // ── 会话事件 → 编辑器/候选条 ───────────────────────────────────

    private fun onUiEvent(event: UiEvent) {
        val ic = currentInputConnection
        when (event) {
            is UiEvent.Composition -> {
                if (event.preedit.isEmpty()) {
                    ic?.finishComposingText()
                } else {
                    ic?.setComposingText(event.preedit, 1)
                }
            }
            is UiEvent.Candidates -> candidateBar?.setCandidates(event.items)
            is UiEvent.CommitText -> ic?.commitText(event.text, 1)
        }
    }

    // ── 键盘/候选条回调 ───────────────────────────────────────────

    override fun onKeyAction(action: KeyAction, shifted: Boolean) {
        if (!directInput && session.keyPressed(action, shifted)) return
        // 会话未吃键：默认行为
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
            // Shift/SwitchSymbols 由 KeyboardView 内部消化，不会到达这里
            else -> Unit
        }
    }

    override fun onCandidateSelected(index: Int) {
        session.candidateSelected(index)
    }
}
