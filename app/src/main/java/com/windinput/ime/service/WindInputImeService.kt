package com.windinput.ime.service

import android.content.res.Configuration
import android.inputmethodservice.InputMethodService
import android.os.SystemClock
import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.ExtractedTextRequest
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.windinput.ime.WindInputApp
import com.windinput.ime.core.AssetsInstaller
import com.windinput.ime.core.ClipboardStore
import com.windinput.ime.core.FakeSession
import com.windinput.ime.core.ImeSession
import com.windinput.ime.core.RecentEmojiStore
import com.windinput.ime.core.RustCoreLoader
import com.windinput.ime.core.RustSession
import com.windinput.ime.core.UiEvent
import com.windinput.ime.core.UserConfig
import com.windinput.ime.keyboard.BarView
import com.windinput.ime.keyboard.CandidateBarView
import com.windinput.ime.keyboard.InputRootView
import com.windinput.ime.keyboard.InputWindowManager
import com.windinput.ime.keyboard.KeyAction
import com.windinput.ime.keyboard.KeyboardDimens
import com.windinput.ime.keyboard.KeyboardLayer
import com.windinput.ime.keyboard.KeyboardView
import com.windinput.ime.keyboard.KeyboardWindow
import com.windinput.ime.keyboard.LayoutRepository
import com.windinput.ime.panels.ClipboardWindow
import com.windinput.ime.panels.EmojiWindow
import com.windinput.ime.panels.ExpandedCandidatesWindow
import com.windinput.ime.panels.TextEditingWindow
import java.io.File

/**
 * 输入法服务：Android IME 框架对接层。
 *
 * 结构（docs/android-ime-features.md §10 建议 1 的 InputWindow 模式）：
 *   root = BarView(工具栏↔候选双态) + 面板区(InputWindowManager)
 *   面板区窗口：键盘 / 文本编辑 / 剪贴板 / 表情，可互换
 *
 * 输入链路：键盘 UI → ImeSession（占位 FakeSession，后由 UniFFI 真实现替换）
 * → UiEvent → 预编辑/候选/上屏。keyPressed 返回 false 时执行默认行为。
 */
class WindInputImeService :
    InputMethodService(),
    KeyboardView.Listener,
    CandidateBarView.Listener,
    BarView.Listener,
    TextEditingWindow.Listener,
    ClipboardWindow.Listener,
    EmojiWindow.Listener,
    ExpandedCandidatesWindow.Listener {

    private lateinit var session: ImeSession

    /** 密码等敏感场景：绕过会话直输 */
    private var directInput = false

    /** 文本编辑面板的选择模式（方向键扩选） */
    private var selecting = false

    private lateinit var layoutRepository: LayoutRepository
    private lateinit var userConfig: UserConfig
    private lateinit var clipboardStore: ClipboardStore
    private lateinit var recentEmojiStore: RecentEmojiStore

    private var barView: BarView? = null
    private var windowManager: InputWindowManager? = null
    private var keyboardWindow: KeyboardWindow? = null
    private var textEditingWindow: TextEditingWindow? = null
    private var clipboardWindow: ClipboardWindow? = null
    private var emojiWindow: EmojiWindow? = null
    private var expandedCandidatesWindow: ExpandedCandidatesWindow? = null

    override fun onCreate() {
        super.onCreate()
        val dirs = (application as WindInputApp).dataDirs
        layoutRepository = LayoutRepository(dirs)
        userConfig = UserConfig(File(dirs.userDir, "config.toml"))
        // Rust 核心可用则用真会话，否则回退占位。首启需保证出厂数据已拷出
        //（installIfNeeded 幂等，命中版本标记时零 I/O），否则引擎读不到方案。
        session = if (RustCoreLoader.available) {
            AssetsInstaller(this, dirs).installIfNeeded()
            RustSession.create(dirs.dataDir, File(filesDir, "rust")) ?: FakeSession()
        } else {
            FakeSession()
        }
        session.listener = ::onUiEvent
        clipboardStore = ClipboardStore(this).also { it.start() }
        clipboardStore.onNewClip = { text ->
            if (!directInput) barView?.showClipSuggestion(text)
        }
        recentEmojiStore = RecentEmojiStore(this)
    }

    override fun onDestroy() {
        clipboardStore.stop()
        super.onDestroy()
    }

    override fun onCreateInputView(): View {
        applyKeyboardDimens()
        // 根视图自理手势条/导航栏避让（IME 窗口不吃系统自动 padding）
        val root = InputRootView(this)

        val bar = BarView(this, candidateListener = this, listener = this)
        barView = bar
        root.addView(
            bar,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        val panelContainer = FrameLayout(this)
        root.addView(
            panelContainer,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        keyboardWindow = KeyboardWindow(listener = this, layoutProvider = layoutRepository::rows)
        textEditingWindow = TextEditingWindow(listener = this)
        clipboardWindow = ClipboardWindow(clipboardStore, listener = this)
        emojiWindow = EmojiWindow(recentEmojiStore, listener = this)
        expandedCandidatesWindow = ExpandedCandidatesWindow(listener = this)

        windowManager = InputWindowManager(panelContainer, keyboardWindow!!).also {
            it.attachInitial()
        }
        root.post {
            android.util.Log.i(
                TAG,
                "inputView: root=${root.width}x${root.height} " +
                    "bar=${barView?.width}x${barView?.height} " +
                    "panel=${panelContainer.width}x${panelContainer.height} " +
                    "kb=${keyboardWindow?.keyboardView?.width}x${keyboardWindow?.keyboardView?.height} " +
                    "rowDp=${KeyboardDimens.rowHeightDp}",
            )
        }
        return root
    }

    private companion object {
        const val TAG = "WindIme"
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    /** 横竖屏独立键高（twinInt 模式）：旋转会触发 onCreateInputView 重建，天然生效 */
    private fun applyKeyboardDimens() {
        val portrait =
            resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE
        KeyboardDimens.rowHeightDp = if (portrait) {
            userConfig.getInt("ui.android.row_height_portrait", 56)
        } else {
            userConfig.getInt("ui.android.row_height_landscape", 44)
        }
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        session.focusGained()
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        keyboardWindow?.keyboardView?.apply {
            hapticEnabled = userConfig.getBoolean("ui.android.haptic", true)
            previewEnabled = userConfig.getBoolean("ui.android.key_preview", false)
            enterLabel = enterActionLabel(info)
        }
        selecting = false
        windowManager?.showKeyboard()
        applyEditorScene(info)
    }

    /** imeOptions → 回车键动作标签 */
    private fun enterActionLabel(info: EditorInfo?): String? {
        if (info == null) return null
        if (info.actionLabel != null) return info.actionLabel.toString()
        return when (info.imeOptions and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_GO -> "前往"
            EditorInfo.IME_ACTION_SEARCH -> "搜索"
            EditorInfo.IME_ACTION_SEND -> "发送"
            EditorInfo.IME_ACTION_NEXT -> "下一项"
            EditorInfo.IME_ACTION_DONE -> "完成"
            EditorInfo.IME_ACTION_PREVIOUS -> "上一项"
            else -> null
        }
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
        keyboardWindow?.keyboardView
            ?.setLayer(if (numericScene) KeyboardLayer.NUMBER else KeyboardLayer.LETTERS)
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
            is UiEvent.Candidates -> {
                barView?.setCandidates(event.items)
                expandedCandidatesWindow?.setCandidates(event.items)
                // 候选清空时若停留在展开面板，收回键盘
                if (event.items.isEmpty() &&
                    windowManager?.current === expandedCandidatesWindow
                ) {
                    windowManager?.showKeyboard()
                }
            }
            is UiEvent.CommitText -> ic?.commitText(event.text, 1)
        }
    }

    // ── 键盘回调 ─────────────────────────────────────────────────

    override fun onKeyAction(action: KeyAction, shifted: Boolean) {
        if (!directInput && session.keyPressed(action, shifted)) return
        val ic = currentInputConnection ?: return
        when (action) {
            is KeyAction.Text -> {
                val ch = if (shifted) action.char.uppercaseChar() else action.char
                ic.commitText(ch.toString(), 1)
            }
            KeyAction.Space -> ic.commitText(" ", 1)
            KeyAction.Backspace -> sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
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

    /** 空格滑动移光标 */
    override fun onCursorMove(delta: Int) {
        if (delta == 0) return
        val keyCode =
            if (delta > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
        repeat(kotlin.math.abs(delta)) { sendDownUpKeyEvents(keyCode) }
    }

    /** 退格左滑扩选：实时选中光标前 count 个字符 */
    override fun onBackspaceSelect(count: Int) {
        val ic = currentInputConnection ?: return
        val extracted = ic.getExtractedText(ExtractedTextRequest(), 0) ?: return
        val end = extracted.startOffset + extracted.selectionEnd
        val start = (end - count).coerceAtLeast(0)
        ic.setSelection(start, end)
    }

    /** 退格滑动抬手：删除选区 */
    override fun onBackspaceSwipeDelete() {
        val ic = currentInputConnection ?: return
        val selected = ic.getSelectedText(0)
        if (!selected.isNullOrEmpty()) {
            ic.commitText("", 1)
        }
    }

    // ── 工具栏 ───────────────────────────────────────────────────

    override fun onToolbarAction(action: BarView.ToolbarAction) {
        val wm = windowManager ?: return
        when (action) {
            BarView.ToolbarAction.EMOJI -> emojiWindow?.let { wm.show(it) }
            BarView.ToolbarAction.CLIPBOARD -> clipboardWindow?.let { wm.show(it) }
            BarView.ToolbarAction.TEXT_EDIT -> textEditingWindow?.let { wm.show(it) }
            BarView.ToolbarAction.HIDE_IME -> requestHideSelf(0)
        }
    }

    override fun onExpandCandidates() {
        expandedCandidatesWindow?.let { windowManager?.show(it) }
    }

    override fun onPasteSuggestion(text: String) {
        currentInputConnection?.commitText(text, 1)
    }

    override fun onExpandedCandidateSelected(index: Int) {
        session.candidateSelected(index)
        windowManager?.showKeyboard()
    }

    override fun onBackToKeyboard() {
        selecting = false
        windowManager?.showKeyboard()
    }

    // ── 文本编辑面板 ─────────────────────────────────────────────

    override fun isSelecting(): Boolean = selecting

    override fun onEditAction(action: TextEditingWindow.EditAction) {
        val ic = currentInputConnection ?: return
        when (action) {
            TextEditingWindow.EditAction.UP -> sendArrow(KeyEvent.KEYCODE_DPAD_UP)
            TextEditingWindow.EditAction.DOWN -> sendArrow(KeyEvent.KEYCODE_DPAD_DOWN)
            TextEditingWindow.EditAction.LEFT -> sendArrow(KeyEvent.KEYCODE_DPAD_LEFT)
            TextEditingWindow.EditAction.RIGHT -> sendArrow(KeyEvent.KEYCODE_DPAD_RIGHT)
            TextEditingWindow.EditAction.HOME -> sendArrow(KeyEvent.KEYCODE_MOVE_HOME)
            TextEditingWindow.EditAction.END -> sendArrow(KeyEvent.KEYCODE_MOVE_END)
            TextEditingWindow.EditAction.TOGGLE_SELECT -> selecting = !selecting
            TextEditingWindow.EditAction.SELECT_ALL -> {
                ic.performContextMenuAction(android.R.id.selectAll)
                selecting = false
            }
            TextEditingWindow.EditAction.CUT -> {
                ic.performContextMenuAction(android.R.id.cut)
                selecting = false
            }
            TextEditingWindow.EditAction.COPY -> {
                ic.performContextMenuAction(android.R.id.copy)
                selecting = false
            }
            TextEditingWindow.EditAction.PASTE -> ic.performContextMenuAction(android.R.id.paste)
            TextEditingWindow.EditAction.BACKSPACE -> sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
        }
    }

    /** 方向/行首行尾键：选择模式下附加 Shift meta 扩选 */
    private fun sendArrow(keyCode: Int) {
        val ic = currentInputConnection ?: return
        val meta = if (selecting) {
            KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        } else {
            0
        }
        val now = SystemClock.uptimeMillis()
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, meta))
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, meta))
    }

    // ── 剪贴板面板 ───────────────────────────────────────────────

    override fun onPasteText(text: String) {
        currentInputConnection?.commitText(text, 1)
        windowManager?.showKeyboard()
    }

    // ── 表情面板 ─────────────────────────────────────────────────

    override fun onEmojiSelected(emoji: String) {
        currentInputConnection?.commitText(emoji, 1)
    }

    override fun onEmojiBackspace() {
        // KEYCODE_DEL 由编辑器按字素删除，代理链正确处理 emoji 代理对
        sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
    }
}
