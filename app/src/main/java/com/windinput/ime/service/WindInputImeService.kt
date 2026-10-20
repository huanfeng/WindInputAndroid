package com.windinput.ime.service

import android.content.res.Configuration
import android.inputmethodservice.InputMethodService
import android.os.SystemClock
import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.ExtractedTextRequest
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.InputMethodSubtype
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.windinput.ime.WindInputApp
import com.windinput.ime.core.AssetsInstaller
import com.windinput.ime.core.ClipboardStore
import com.windinput.ime.core.DeferredSession
import com.windinput.ime.core.EditOp
import com.windinput.ime.core.EngineCmd
import com.windinput.ime.core.FakeSession
import com.windinput.ime.core.ImeSession
import com.windinput.ime.core.RecentStore
import com.windinput.ime.core.RustCoreLoader
import com.windinput.ime.core.RustSession
import com.windinput.ime.core.SchemaEntry
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
import com.windinput.ime.keyboard.Palette
import com.windinput.ime.panels.ClipboardWindow
import com.windinput.ime.panels.EmojiWindow
import com.windinput.ime.panels.SymbolPages
import com.windinput.ime.panels.SymbolWindow
import com.windinput.ime.panels.ExpandedCandidatesWindow
import com.windinput.ime.panels.QuickPanelWindow
import com.windinput.ime.panels.TextEditingWindow
import com.windinput.ime.settings.SettingsActivity
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
    SymbolWindow.Listener,
    QuickPanelWindow.Listener,
    ExpandedCandidatesWindow.Listener {

    private lateinit var session: ImeSession

    /** 密码等敏感场景：绕过会话直输 */
    private var directInput = false

    /** 文本编辑面板的选择模式（方向键扩选） */
    private var selecting = false

    /** 选择模式的锚点（绝对偏移）；焦点端随方向键走，选区 = [min, max] */
    private var selAnchor = -1

    /** 引擎侧是否有未上屏的编码（来自候选帧的 preedit 非空） */
    private var composing = false

    /** 剪贴板建议条开关（设置项 ui.android.clip_suggestion） */
    private var clipSuggestionEnabled = true

    /** 英文输入三个开关（设置项 ui.android.auto_cap / double_space_period / auto_space） */
    private var autoCapEnabled = true
    private var doubleSpacePeriod = true
    private var autoSpaceEnabled = true

    /**
     * 光标前那个空格是英文选词后**自动补**的。紧接着打句末标点时要把它挪到标点后面
     * （`word ` + `.` → `word. `），否则就成了「word .」。按了别的键即作废。
     */
    private var autoSpaced = false

    /** 上一次按空格的时刻，双击空格出句号用；按了别的键清零 */
    private var lastSpaceAt = 0L

    private lateinit var layoutRepository: LayoutRepository
    private lateinit var userConfig: UserConfig
    private lateinit var clipboardStore: ClipboardStore
    private lateinit var recentEmojiStore: RecentStore
    private lateinit var recentSymbolStore: RecentStore
    private lateinit var dataDirs: com.windinput.ime.core.DataDirs

    private var barView: BarView? = null
    private var windowManager: InputWindowManager? = null
    private var keyboardWindow: KeyboardWindow? = null
    private var textEditingWindow: TextEditingWindow? = null
    private var clipboardWindow: ClipboardWindow? = null
    private var emojiWindow: EmojiWindow? = null
    private var symbolWindow: SymbolWindow? = null
    private var expandedCandidatesWindow: ExpandedCandidatesWindow? = null
    private var quickPanelWindow: QuickPanelWindow? = null

    /** 编码是否同时写进宿主组合区（ui.android.inline_preedit，默认关，见 onUiEvent） */
    private var inlinePreedit = false

    /**
     * 当前方案短称（引擎推送的 `iconLabel`：五笔为「五」、英文方案为「英」）。
     *
     * ⚠ 中英键**不能**用 `chineseMode` 判据：英文在本端是一个**方案**，而
     * `SelectSchema` 会把 `chinese_mode` 一并设回 true——于是切到英文方案后
     * `chineseMode` 仍是 true，键面显示「中」，再点一次又切去英文，**切不回来**。
     * 方案短称才是这里要的真值。
     */
    private var modeLabel = "中"

    override fun onCreate() {
        super.onCreate()
        val dirs = (application as WindInputApp).dataDirs
        dataDirs = dirs
        layoutRepository = LayoutRepository(dirs)
        userConfig = UserConfig(File(dirs.userDir, "config.toml"))
        // 核心**异步构造**：它要加载三层配置、开 redb、把词库 yaml 编译成 wdat，
        // 放在主线程会突破系统的输入分发超时（实测直接 ANR）。未就绪期间 keyPressed
        // 返回 false，字母直接上屏——头一两秒不过中文引擎，好过键盘假死。
        session = if (RustCoreLoader.available) {
            val installer = AssetsInstaller(this, dirs)
            DeferredSession(
                dataDir = dirs.dataDir,
                rustRoot = File(filesDir, "rust"),
                // 出厂数据拷出也必须在后台：数 MB 逐文件流式拷贝，留在这里会连同
                // 核心构造一起把 onCreate 拖过输入分发超时（实测 ANR）
                prepareData = { installer.installIfNeeded() },
                onReady = { ready ->
                    // 就绪时若已在输入，补一次焦点，否则引擎收不到当前会话
                    if (currentInputConnection != null) ready.focusGained()
                    barView?.setPreparing(false)
                    refreshSchemaKeys()
                },
            ).also { it.start() }
        } else {
            FakeSession()
        }
        session.listener = ::onUiEvent
        clipboardStore = ClipboardStore(this).also { it.start() }
        clipboardStore.onNewClip = { text ->
            if (!directInput && clipSuggestionEnabled) barView?.showClipSuggestion(text)
        }
        recentEmojiStore = RecentStore(this, "recent_emoji")
        recentSymbolStore = RecentStore(this, "recent_symbol")
    }

    override fun onDestroy() {
        clipboardStore.stop()
        super.onDestroy()
    }

    override fun onCreateInputView(): View {
        applyKeyboardDimens()
        // 深浅色：uiMode 变化会让系统重跑本方法，下面所有视图都按新调色板构造
        Palette.apply(this, userConfig.getString("ui.android.theme", "system"))
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
        symbolWindow = SymbolWindow({ SymbolPages.load(dataDirs) }, recentSymbolStore, listener = this)
        expandedCandidatesWindow = ExpandedCandidatesWindow(listener = this)
        quickPanelWindow = QuickPanelWindow(listener = this)

        windowManager = InputWindowManager(panelContainer, keyboardWindow!!).also {
            // 面板打开时条区让位：面板自带左上角返回，不再上下两条横栏并排。
            // 面板高度 = 条区 + 键盘，所以让位前后 IME 窗口总高不变、键盘不跳。
            //
            // 例外由窗口自己声明（InputWindow.keepsBar）：展开候选面板没有自己的返回栏，
            // 返回入口就在条区尾格上，把它一并让位掉就等于展开后无路可退。
            it.onWindowChanged = { window ->
                val keyboardMode = window === keyboardWindow
                bar.visibility =
                    if (keyboardMode || window.keepsBar) View.VISIBLE else View.GONE
                bar.setExpanded(window === expandedCandidatesWindow)
            }
            it.attachInitial()
        }
        bar.setPreparing((session as? DeferredSession)?.ready == false)
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

        /** 英文方案 id（与 assets/data/schemas/english.schema.toml 一致） */
        const val ENGLISH_SCHEMA_ID = "english"

        /** 英文切回中文时，找不到切走前的方案（首次安装就在英文里）就回出厂默认方案 */
        const val DEFAULT_CHINESE_SCHEMA_ID = "wubi86"
        const val KEY_LAST_CHINESE = "last_chinese_schema"

        /**
         * 有编码时 ⇧ 变「分词」的方案。只有全拼：分隔符 `auto` 档下全拼用反引号
         * （见 KeyboardView.SPLIT_CHAR）；双拼的反引号出厂归辅助码、`'` 归选词键，
         * `auto` 退化为不启用，发什么都不是分词。码表方案没有分词语义。
         *
         * ⚠ 按 id 判断是权宜：绑定层的 SchemaInfo 还没带引擎类型和分隔符设置。
         * 补上后改为问引擎「当前方案的分隔键是哪个」，见 IMPLEMENTATION_PLAN.md。
         */
        val SPLIT_SCHEMAS = setOf("pinyin")

        /**
         * 九宫格数字键盘左列的符号（可上下滚动）。数字框放算式和常见分隔符；
         * 电话框放拨号会用到的 `* # +` 和分隔用的 `- ( )`，`,` `;` 是拨号里的暂停 / 等待。
         */
        val NUMBER_SIDE_SYMBOLS = listOf("+", "-", "*", "/", "%", "=", ",", ":", "@", "#", "(", ")")
        val PHONE_SIDE_SYMBOLS = listOf("*", "#", "+", "-", "(", ")", ",", ";")

        /** 英文里紧跟单词、后面该带空格的标点（自动补的空格遇到它们要挪到后面） */
        val AUTO_SPACE_PUNCT = setOf('.', ',', '?', '!', ';', ':')

        /** 双击空格出句号：两下空格的最大间隔 */
        const val DOUBLE_SPACE_MS = 600L

        /**
         * 每次按键预取的候选条数。
         *
         * **这是个性能参数，别随手调大。** 候选全量可达上千条，每条都要跨 JNI
         * 序列化、再被候选栏 `measureText` 一次，全在主线程上。24 条约够横向候选栏
         * 铺三屏——用户真滑到那儿了再续取，而绝大多数按键根本不会被滑动。
         */
        const val CANDIDATE_PREFETCH = 24

        /** 续取的步长（滚到接近尾部时取下一段） */
        const val CANDIDATE_CHUNK = 60
    }

    /** 已从核心取到的候选（绝对下标即列表下标，选词直接用它） */
    private var loadedCandidates: List<String> = emptyList()

    /** 核心侧候选全量总数：`loadedCandidates.size < candidateTotal` 即还有得取 */
    private var candidateTotal = 0

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
            soundEnabled = userConfig.getBoolean("ui.android.sound", false)
            previewEnabled = userConfig.getBoolean("ui.android.key_preview", false)
            longPressDelayMs = userConfig.getInt("ui.android.long_press_delay", 300)
            spaceSwipeEnabled = userConfig.getBoolean("ui.android.gesture_space_cursor", true)
            backspaceSwipeEnabled = userConfig.getBoolean("ui.android.gesture_backspace_select", true)
            swipeSymbolsEnabled = userConfig.getBoolean("ui.android.gesture_swipe_symbols", true)
            showSwipeHints = userConfig.getBoolean("ui.android.show_swipe_hints", false)
            enterLabel = enterActionLabel(info)
        }
        barView?.candidateBar?.textSizeSp = userConfig.getInt("ui.android.candidate_text_sp", 19).toFloat()
        clipSuggestionEnabled = userConfig.getBoolean("ui.android.clip_suggestion", true)
        inlinePreedit = userConfig.getBoolean("ui.android.inline_preedit", false)
        autoCapEnabled = userConfig.getBoolean("ui.android.auto_cap", true)
        doubleSpacePeriod = userConfig.getBoolean("ui.android.double_space_period", true)
        autoSpaceEnabled = userConfig.getBoolean("ui.android.auto_space", true)
        selecting = false
        autoSpaced = false
        lastSpaceAt = 0L
        windowManager?.showKeyboard()
        applyEditorScene(info)
        refreshSchemaKeys()
    }

    /** 光标动了（上屏、删除、点击挪光标）：重新判断要不要句首大写 */
    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        updateAutoCaps()
    }

    /**
     * 句首自动大写：英文模式、没在打字、输入框声明了大写（`CAP_*` 标志，由
     * `getCursorCapsMode` 结合光标前文判定句首）时给键盘一个单次 Shift。
     * 网址、邮箱、密码框不给——这几类本就不带 `CAP_*`，这里再明确排除一次。
     */
    private fun updateAutoCaps() {
        val kv = keyboardWindow?.keyboardView ?: return
        val info = currentInputEditorInfo
        val ic = currentInputConnection
        val on = autoCapEnabled && modeLabel == "英" && !directInput && !composing &&
            info != null && ic != null && !noAutoCapField(info) &&
            ic.getCursorCapsMode(info.inputType) != 0
        kv.setAutoCaps(on)
    }

    private fun noAutoCapField(info: EditorInfo): Boolean {
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        return (info.inputType and InputType.TYPE_MASK_CLASS) != InputType.TYPE_CLASS_TEXT ||
            variation in setOf(
                InputType.TYPE_TEXT_VARIATION_URI,
                InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
                InputType.TYPE_TEXT_VARIATION_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            )
    }

    /**
     * 键盘收起（按返回键、宿主主动隐藏）：**清掉组合态**。
     *
     * 这里与 [onFinishInput] 不是一回事：收起键盘只走这一个回调，输入连接还在。
     * 不清的话，编码栏里打到一半的码会在下次弹出键盘时原样冒出来，而用户早已
     * 忘了它——接着按的第一个键就拼进了那串旧码，出来的字完全对不上。
     */
    override fun onFinishInputView(finishingInput: Boolean) {
        abandonComposition()
        // 编码浮层是独立窗口，不随 IME 窗口隐藏，必须显式收
        barView?.hidePreedit()
        selecting = false
        super.onFinishInputView(finishingInput)
    }

    /**
     * 用户**点了编辑框**（光标被挪到了别处）：组合态跟着作废。
     *
     * 五笔/拼音的编码是相对「光标当时的位置」积累的，光标一挪，再上屏就落在
     * 新位置，用户看到的是一串字凭空插进了别处。fcitx5 与 Gboard 都在这一刻清组合。
     *
     * API 29 起标记 deprecated，但编辑器至今仍会调它（API 36 亦然）；34+ 另有
     * [onUpdateEditorToolType]，两个都接，谁来都清。
     */
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onViewClicked(focusChanged: Boolean) {
        super.onViewClicked(focusChanged)
        abandonComposition()
    }

    override fun onUpdateEditorToolType(toolType: Int) {
        super.onUpdateEditorToolType(toolType)
        abandonComposition()
    }

    /**
     * 丢弃当前组合（不上屏）。核心走 ESC 语义；宿主侧若开了内联编码，把组合区收掉，
     * 否则那串灰字会留在正文里。空组合时两步都是空操作，随便调。
     */
    private fun abandonComposition() {
        session.reset()
        if (inlinePreedit) currentInputConnection?.finishComposingText()
    }

    /**
     * imeOptions → 回车键动作标签；`null` = 显示默认换行图标。
     *
     * ⚠ 光看 `IME_MASK_ACTION` 是不够的：多行文本框里系统常给出 `IME_ACTION_NEXT`
     * （因为表单后面还有输入框），但**回车在多行框里恒为换行**，照抄就会出现
     * 「键上写着『下一项』、按下去却是换行」——真机实测到的就是这个。
     *
     * 两条否决优先于 action：
     * - `TYPE_TEXT_FLAG_MULTI_LINE`：多行框回车换行，action 只能由别的方式触发
     * - `IME_FLAG_NO_ENTER_ACTION`：宿主明确声明「回车不要执行 action」
     */
    private fun enterActionLabel(info: EditorInfo?): String? {
        if (info == null) return null
        if (!enterPerformsAction(info)) return null
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

    /**
     * 回车是否应执行编辑器动作（而非换行）。
     *
     * **标签与行为共用这一个判据**——分成两处判必然漂移，那正是「显示下一项、
     * 实际换行」的成因。
     */
    private fun enterPerformsAction(info: EditorInfo?): Boolean {
        if (info == null) return false
        if (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0) return false
        if (info.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0) return false
        val action = info.imeOptions and EditorInfo.IME_MASK_ACTION
        return action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED
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
        keyboardWindow?.keyboardView?.let { kv ->
            kv.setLayer(if (numericScene) KeyboardLayer.NUMBER else KeyboardLayer.LETTERS)
            kv.sideSymbols = if (clazz == InputType.TYPE_CLASS_PHONE) PHONE_SIDE_SYMBOLS else NUMBER_SIDE_SYMBOLS
        }
    }

    /**
     * 把模式与当前方案同步到键盘：中英键、空格上的方案名、⇧ 能否当「分词」。
     *
     * 除了 Status 推送，核心就绪和每次弹出键盘时也要调：核心起来后**不会**主动推一次
     * 初始状态（工具栏推送按「有变化才发」去重），只靠推送的话，刚装好、刚重启时
     * 空格上一直是空的。
     */
    private fun refreshSchemaKeys() {
        val english = modeLabel == "英"
        val active = session.schemas().firstOrNull { it.active }
        // ⚠ 别写成 keyboardView.apply { modeLabel = modeLabel }：块内的 modeLabel
        // 解析成视图自己的属性，等于自赋值，英文模式永远进不去
        keyboardWindow?.keyboardView?.let { kv ->
            kv.modeLabel = modeLabel
            // 空格上的灰字：英文写「英文」（引擎没装英文方案时退回系统英文模式，
            // 那时 active 仍是切走前的中文方案），否则写当前方案名
            kv.spaceLabel = if (english) "英文" else active?.name.orEmpty()
            kv.splitEnabled = !english && active?.id in SPLIT_SCHEMAS
        }
        session.setLatinSchema(english)
        updateAutoCaps()
    }

    // ── 会话事件 → 编辑器/候选条 ───────────────────────────────────

    private fun onUiEvent(event: UiEvent) {
        val ic = currentInputConnection
        when (event) {
            is UiEvent.Edits -> applyEdits(ic ?: return, event.ops)
            is UiEvent.Candidates -> {
                // 推送帧只用来画编码栏（编码/短称/页码）；候选列表另行**拉取**——
                // 帧里的是当页 6~9 条，装不下一条可滚动的长列表。
                val page = if (event.items.isEmpty()) {
                    // 帧说没候选就是没候选，不必再往核心跑一趟
                    com.windinput.ime.core.CandidatePage(emptyList(), 0, 0, 0)
                } else {
                    session.candidateWindow(0, CANDIDATE_PREFETCH)
                }
                loadedCandidates = page.items
                candidateTotal = page.total
                composing = event.composing
                keyboardWindow?.keyboardView?.composing = composing
                // 打完一个词（编码清空）后再判一次句首大写：上屏回调可能早于这一帧
                updateAutoCaps()

                barView?.setCandidateFrame(
                    items = loadedCandidates,
                    preedit = event.preedit,
                    modeLabel = event.modeLabel,
                    page = event.page,
                    totalPages = event.totalPages,
                )
                expandedCandidatesWindow?.setCandidates(loadedCandidates)
                // 候选清空时若停留在展开面板，收回键盘
                if (loadedCandidates.isEmpty() &&
                    windowManager?.current === expandedCandidatesWindow
                ) {
                    windowManager?.showKeyboard()
                }
            }
            is UiEvent.Status -> {
                modeLabel = event.iconLabel.ifEmpty { if (event.chineseMode) "中" else "英" }
                refreshSchemaKeys()
                // 键面标点形态跟引擎的真值走：英文模式或关了中文标点，键面就显示 ASCII
                keyboardWindow?.keyboardView?.chinesePunct = event.chineseMode && event.chinesePunct
                quickPanelWindow?.updateStatus(event)
            }
            is UiEvent.Toast -> showToast(event.text)
        }
    }

    /**
     * 执行核心下发的编辑指令流。
     *
     * 每条指令都有 `InputConnection` 的直接对应物——这正是把按键结果从「上屏文本 +
     * 组合区」的三元组改成指令流的理由：智能符号配对、回退替换、配对跳出以前在这里
     * 无处落地，只能被丢弃（不报错、不掉键，单纯功能没有）。
     *
     * `beginBatchEdit` 包住整批：一次按键的多条指令（如「上屏 + 开新组合」）在宿主
     * 撤销栈里应该是一步，也避免中间态触发多次 reflow。
     */
    private fun applyEdits(ic: android.view.inputmethod.InputConnection, ops: List<EditOp>) {
        if (ops.isEmpty()) return
        ic.beginBatchEdit()
        try {
            for (op in ops) {
                when (op) {
                    is EditOp.Commit -> ic.commitText(op.text, 1)
                    is EditOp.SetComposition -> {
                        if (op.text.isEmpty()) {
                            ic.finishComposingText()
                        } else if (inlinePreedit) {
                            // 编码默认只显示在自绘编码栏：五笔码嵌在正文里是噪声，且与
                            // 编码栏重复。想要宿主内联的用户开 ui.android.inline_preedit。
                            ic.setComposingText(op.text, op.caret.coerceAtLeast(1))
                        }
                    }
                    // 按**码点**删除：核心按字符计数，deleteSurroundingText 的单位是
                    // UTF-16 code unit，emoji 等增补平面字符会被删半个。
                    is EditOp.DeleteBackward ->
                        ic.deleteSurroundingTextInCodePoints(op.count, 0)
                    is EditOp.ReplaceBackward -> {
                        ic.deleteSurroundingTextInCodePoints(op.count, 0)
                        ic.commitText(op.text, 1)
                    }
                    is EditOp.MoveCursor -> moveCursor(ic, op.delta)
                }
            }
        } finally {
            ic.endBatchEdit()
        }
    }

    /** 相对移动光标：取当前选区绝对位置再 setSelection，比连发方向键可靠 */
    private fun moveCursor(ic: android.view.inputmethod.InputConnection, delta: Int) {
        if (delta == 0) return
        val extracted = ic.getExtractedText(ExtractedTextRequest(), 0)
        if (extracted == null) {
            // 拿不到选区时退回方向键（部分宿主不支持 ExtractedText）
            val code = if (delta > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
            repeat(kotlin.math.abs(delta)) { sendDownUpKeyEvents(code) }
            return
        }
        val pos = (extracted.startOffset + extracted.selectionEnd + delta).coerceAtLeast(0)
        ic.setSelection(pos, pos)
    }

    /**
     * 引擎提示：用系统 Toast 而非自绘浮层。
     *
     * IME 进程可以弹 Toast（不需要窗口焦点），而自绘浮层要么占键盘空间、要么得申请
     * 悬浮窗权限——为一句「已切换到五笔」不值得。
     */
    private fun showToast(text: String) {
        if (text.isBlank()) return
        android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_SHORT).show()
    }

    // ── 键盘回调 ─────────────────────────────────────────────────

    override fun onKeyAction(action: KeyAction, shifted: Boolean) {
        // 中英切换 / 表情键是宿主侧动作，不走引擎按键路径
        if (action == KeyAction.SwitchLang) {
            toggleLanguage()
            return
        }
        if (action == KeyAction.Emoji) {
            emojiWindow?.let { windowManager?.show(it) }
            return
        }
        if (action == KeyAction.SymbolPage) {
            symbolWindow?.let { windowManager?.show(it) }
            return
        }
        if (action is KeyAction.Commit) {
            commitDirect(action.text)
            return
        }
        val english = modeLabel == "英" && !directInput
        if (action != KeyAction.Space) lastSpaceAt = 0L
        // 自动补的空格遇到句末标点：挪到标点后面（`word ` + `.` → `word. `）
        val ch = (action as? KeyAction.Text)?.char
        if (english && autoSpaced && !composing && ch != null && ch in AUTO_SPACE_PUNCT) {
            val ic = currentInputConnection ?: return
            if (ic.getTextBeforeCursor(1, 0) == " ") ic.deleteSurroundingText(1, 0)
            ic.commitText("$ch ", 1)
            return
        }
        autoSpaced = false
        val composingBefore = composing
        if (!directInput && session.keyPressed(action, shifted)) {
            // 英文方案：有编码时空格上屏原文（引擎只出词本身），这里补上词后的空格
            if (english && composingBefore && action == KeyAction.Space) {
                lastSpaceAt = android.os.SystemClock.uptimeMillis()
                commitAutoSpace()
            }
            return
        }
        val ic = currentInputConnection
        if (ic == null) {
            android.util.Log.w(TAG, "默认行为无法执行：currentInputConnection 为 null action=$action")
            return
        }
        when (action) {
            is KeyAction.Text -> {
                val ch = if (shifted) action.char.uppercaseChar() else action.char
                ic.commitText(ch.toString(), 1)
            }
            KeyAction.Space -> {
                if (directInput || !doubleSpacePeriod || !tryDoubleSpacePeriod(ic)) {
                    ic.commitText(" ", 1)
                    lastSpaceAt = android.os.SystemClock.uptimeMillis()
                }
            }
            KeyAction.Backspace -> sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
            // 与 enterActionLabel 共用 enterPerformsAction：键面写什么，按下就做什么
            KeyAction.Enter -> {
                if (enterPerformsAction(currentInputEditorInfo)) {
                    sendDefaultEditorAction(true)
                } else {
                    ic.commitText("\n", 1)
                }
            }
            // Shift/SwitchSymbols 由 KeyboardView 内部消化，不会到达这里
            else -> Unit
        }
    }

    override fun onCandidateSelected(index: Int) {
        val english = modeLabel == "英" && !directInput && composing
        session.candidateSelected(index)
        if (english) commitAutoSpace()
    }

    /** 英文选词 / 空格上屏一个词之后补的空格（设置项 ui.android.auto_space） */
    private fun commitAutoSpace() {
        if (!autoSpaceEnabled) return
        currentInputConnection?.commitText(" ", 1)
        autoSpaced = true
    }

    /**
     * 双击空格：光标前是「字 + 空格」且两次空格间隔够短，就把那个空格换成句号——
     * 英文出「. 」（后面接着打下一句），中文出「。」（中文句号后不加空格）。
     * 换完清零计时，第三下空格就是普通空格，不会连环换。
     */
    private fun tryDoubleSpacePeriod(ic: android.view.inputmethod.InputConnection): Boolean {
        if (android.os.SystemClock.uptimeMillis() - lastSpaceAt > DOUBLE_SPACE_MS) return false
        val before = ic.getTextBeforeCursor(2, 0) ?: return false
        if (before.length < 2 || before[1] != ' ' || !Character.isLetterOrDigit(before[0])) return false
        val english = modeLabel == "英"
        ic.beginBatchEdit()
        ic.deleteSurroundingText(1, 0)
        ic.commitText(if (english) ". " else "。", 1)
        ic.endBatchEdit()
        lastSpaceAt = 0L
        autoSpaced = english
        return true
    }

    /**
     * 候选栏/展开面板滚到接近尾部：向核心续取一段。
     *
     * 两个视图共用同一份 [loadedCandidates]（绝对下标即列表下标），所以续取一次
     * 两边都受益，不会各拉各的。取满即不再请求——视图那边只管发信号，
     * 「还有没有」的真值在核心，视图自己记一份必然漂移。
     */
    override fun onNeedMoreCandidates() {
        if (loadedCandidates.size >= candidateTotal) return
        val page = session.candidateWindow(loadedCandidates.size, CANDIDATE_CHUNK)
        if (page.items.isEmpty()) return
        loadedCandidates = loadedCandidates + page.items
        candidateTotal = page.total
        barView?.appendCandidates(page.items)
        expandedCandidatesWindow?.appendCandidates(page.items)
    }

    /**
     * 中英键：中文方案 ↔ **英文方案**（带词库补全：打 `beau` 出 `beautiful`）。
     *
     * 曾经只能切「系统英文模式」（字母直出、无候选）：切到英文方案后候选来自英文引擎、
     * 上屏却走中文码表。主仓已修（`rust/wind-android/tests/key_flow.rs`
     * `english_schema_commits_latin_not_hanzi` 转绿），现在走方案。引擎里没装英文方案时
     * 仍退回系统英文模式。
     */
    private fun toggleLanguage() {
        // 有编码时先把字母原文上屏再切（两家相同）：用户在中文里打了半截想改打英文，
        // 码不该被吞掉。有编码时回车就是「上屏原文」（见回车键「确定」），直接复用。
        if (composing && !directInput) session.keyPressed(KeyAction.Enter, false)
        val schemas = session.schemas()
        val active = schemas.firstOrNull { it.active }
        if (modeLabel == "英") {
            // 回到切走前的中文方案。系统英文模式下引擎报的 active 仍是那个中文方案；
            // 英文方案下 active 是英文，只能靠切走时记下的 id（持久化：输入法进程在
            // 英文里被杀掉后也能原路回去）。
            //
            // ⚠ 绝不要退化成「列表里第一个非英文方案」：方案按文件名排序，
            // 装上拼音后第一个非英文是 pinyin——五笔用户切一次英文再切回来就掉进全拼。
            val lastId = statePrefs.getString(KEY_LAST_CHINESE, null)
            val target = active?.takeIf { it.id != ENGLISH_SCHEMA_ID }
                ?: schemas.firstOrNull { it.id == lastId }
                ?: schemas.firstOrNull { it.id == DEFAULT_CHINESE_SCHEMA_ID }
                ?: return
            session.runCommand(EngineCmd.SelectSchema(target.index))
            return
        }
        active?.let { statePrefs.edit().putString(KEY_LAST_CHINESE, it.id).apply() }
        val english = schemas.firstOrNull { it.id == ENGLISH_SCHEMA_ID }
        session.runCommand(english?.let { EngineCmd.SelectSchema(it.index) } ?: EngineCmd.English)
    }

    /** 键盘自己的小状态（不属于引擎配置的那种），如中英切换前的中文方案 */
    private val statePrefs by lazy { getSharedPreferences("ime_state", MODE_PRIVATE) }

    override fun onKeyLongPress(action: KeyAction) {
        if (action == KeyAction.SwitchLang) {
            quickPanelWindow?.let { windowManager?.show(it) }
        }
    }

    /** 空格滑动移光标 */
    override fun onCursorMove(delta: Int) {
        if (delta == 0) return
        val keyCode =
            if (delta > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
        repeat(kotlin.math.abs(delta)) { sendDownUpKeyEvents(keyCode) }
    }

    /**
     * 退格左滑扩选：实时选中光标前 count 个字符。
     *
     * **有编码时不碰宿主文本**：用户正在打字，滑退格的意图是「这串码不要了」，
     * 不是删正文——此前会把编码留着、把输入框里的字选中删掉，与意图完全相反。
     * 有编码时整个手势等价于清空编码（抬手时做，见 [onBackspaceSwipeDelete]）。
     */
    override fun onBackspaceSelect(count: Int) {
        if (composing) return
        val ic = currentInputConnection ?: return
        val extracted = ic.getExtractedText(ExtractedTextRequest(), 0) ?: return
        val end = extracted.startOffset + extracted.selectionEnd
        val start = (end - count).coerceAtLeast(0)
        ic.setSelection(start, end)
    }

    /** 退格滑动抬手：有编码 → 清空编码；否则删除选区 */
    override fun onBackspaceSwipeDelete() {
        if (composing) {
            abandonComposition()
            return
        }
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
            BarView.ToolbarAction.QUICK_PANEL -> quickPanelWindow?.let { wm.show(it) }
            BarView.ToolbarAction.HIDE_IME -> requestHideSelf(0)
        }
    }

    // ── 快捷功能面板 ─────────────────────────────────────────────

    override fun onQuickCommand(cmd: EngineCmd) {
        session.runCommand(cmd)
    }

    override fun quickSchemas(): List<SchemaEntry> = session.schemas()

    override fun onOpenPanel(target: QuickPanelWindow.Target) {
        val wm = windowManager ?: return
        when (target) {
            QuickPanelWindow.Target.EMOJI -> emojiWindow?.let { wm.show(it) }
            QuickPanelWindow.Target.CLIPBOARD -> clipboardWindow?.let { wm.show(it) }
            QuickPanelWindow.Target.TEXT_EDIT -> textEditingWindow?.let { wm.show(it) }
        }
    }

    override fun onOpenSettings() = openSettings()

    /**
     * 系统输入法选择器。`switchToNextInputMethod` 是盲切，用户看不到切去了哪个；
     * 选择器列出全部已启用输入法，与 Gboard 地球键长按的行为一致。
     */
    override fun onSwitchInputMethod() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
    }

    override fun voiceInputAvailable(): Boolean = findVoiceInput() != null

    /**
     * 语音输入：**转发给系统里带 voice 子类型的输入法**（通常是 Google 语音输入），
     * 说完系统会切回本输入法。自研识别不做（docs/android-ime-features.md §9）。
     */
    override fun onVoiceInput() {
        val (id, subtype) = findVoiceInput() ?: run {
            showToast("没有可用的语音输入法")
            return
        }
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            switchInputMethod(id, subtype)
        } else {
            val token = window?.window?.attributes?.token ?: return
            @Suppress("DEPRECATION")
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                .setInputMethodAndSubtype(token, id, subtype)
        }
    }

    /**
     * 在已启用的输入法里找第一个 `voice` 模式的子类型。
     *
     * 只看**已启用**的：没启用的输入法系统不允许切过去，列出来也是点不通的入口。
     * `allowsImplicitlySelectedSubtypes = true` 是必须的——语音输入法一般不会被
     * 用户手动勾选子类型，全靠系统隐式选中。
     */
    private fun findVoiceInput(): Pair<String, InputMethodSubtype>? {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        for (imi in imm.enabledInputMethodList) {
            val subtype = imm.getEnabledInputMethodSubtypeList(imi, true)
                .firstOrNull { it.mode == "voice" } ?: continue
            return imi.id to subtype
        }
        return null
    }

    /**
     * 打开设置页。
     *
     * `NEW_TASK` 是必须的：输入法服务不是 Activity，没有任务栈可挂；
     * 同时收起键盘，否则设置页起来后键盘悬在上面挡住内容。
     */
    private fun openSettings() {
        requestHideSelf(0)
        startActivity(
            android.content.Intent(this, SettingsActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    override fun onToggleExpanded() {
        val wm = windowManager ?: return
        if (wm.current === expandedCandidatesWindow) {
            wm.showKeyboard()
        } else {
            expandedCandidatesWindow?.let { wm.show(it) }
        }
    }

    override fun onPasteSuggestion(text: String) {
        currentInputConnection?.commitText(text, 1)
    }

    override fun onExpandedCandidateSelected(index: Int) {
        val english = modeLabel == "英" && !directInput && composing
        session.candidateSelected(index)
        if (english) commitAutoSpace()
        // 选中即原位返回：展开候选的唯一去向就是选一个词
        windowManager?.showKeyboard()
    }

    override fun onBackToKeyboard() {
        selecting = false
        windowManager?.showKeyboard()
    }

    // ── 文本编辑面板 ─────────────────────────────────────────────

    override fun isSelecting(): Boolean = selecting

    /**
     * 编辑动作全部基于 `getExtractedText` + `setSelection` 实现，不发带 Shift 的方向键事件。
     *
     * 此前选择模式靠 `sendKeyEvent(DPAD_*, META_SHIFT_ON)`：**多数编辑器不理会输入法
     * 合成的按键 meta**（EditText 只在物理键盘事件里看 Shift），于是「选择」按下去光标
     * 照常移动、选区纹丝不动——真机反馈「选择无效」就是它。选区直接 set 是所有实现
     * `InputConnection` 的控件都支持的最小公分母。
     *
     * 拿不到 ExtractedText 的宿主（极少数自绘编辑器）退回方向键，能移不能选。
     */
    override fun onEditAction(action: TextEditingWindow.EditAction) {
        val ic = currentInputConnection ?: return
        when (action) {
            TextEditingWindow.EditAction.TOGGLE_SELECT -> {
                selecting = !selecting
                selAnchor = if (selecting) currentSelection(ic)?.second ?: -1 else -1
            }
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
            else -> moveCaret(ic, action)
        }
    }

    /** 当前选区的绝对偏移 (start, end)；宿主不支持 ExtractedText 时为 null */
    private fun currentSelection(ic: android.view.inputmethod.InputConnection): Pair<Int, Int>? {
        val ext = ic.getExtractedText(ExtractedTextRequest(), 0) ?: return null
        return (ext.startOffset + ext.selectionStart) to (ext.startOffset + ext.selectionEnd)
    }

    /** 方向 / 行首行尾：算出新焦点，选择模式下与锚点合成选区 */
    private fun moveCaret(
        ic: android.view.inputmethod.InputConnection,
        action: TextEditingWindow.EditAction,
    ) {
        val ext = ic.getExtractedText(ExtractedTextRequest(), 0)
        val text = ext?.text
        if (ext == null || text == null) {
            // 退回方向键：能移动，选不了
            val code = when (action) {
                TextEditingWindow.EditAction.UP -> KeyEvent.KEYCODE_DPAD_UP
                TextEditingWindow.EditAction.DOWN -> KeyEvent.KEYCODE_DPAD_DOWN
                TextEditingWindow.EditAction.LEFT -> KeyEvent.KEYCODE_DPAD_LEFT
                TextEditingWindow.EditAction.RIGHT -> KeyEvent.KEYCODE_DPAD_RIGHT
                TextEditingWindow.EditAction.HOME -> KeyEvent.KEYCODE_MOVE_HOME
                else -> KeyEvent.KEYCODE_MOVE_END
            }
            sendDownUpKeyEvents(code)
            return
        }
        val base = ext.startOffset
        val selStart = base + ext.selectionStart
        val selEnd = base + ext.selectionEnd
        // 焦点端：选择模式下是锚点的对侧；非选择模式选区已合并，取 end
        val anchor = if (selecting && selAnchor >= 0) selAnchor else selEnd
        val focus = if (selecting) (if (selStart == anchor) selEnd else selStart) else selEnd
        val len = text.length

        fun lineStart(pos: Int) = text.lastIndexOf('\n', pos - 1) + 1
        fun lineEnd(pos: Int) = text.indexOf('\n', pos).let { if (it < 0) len else it }

        val newFocus = when (action) {
            TextEditingWindow.EditAction.LEFT -> {
                var p = (focus - 1).coerceAtLeast(0)
                if (p > 0 && Character.isLowSurrogate(text[p])) p--
                p
            }
            TextEditingWindow.EditAction.RIGHT -> {
                var p = (focus + 1).coerceAtMost(len)
                if (p < len && Character.isLowSurrogate(text[p])) p++
                p
            }
            TextEditingWindow.EditAction.HOME -> lineStart(focus)
            TextEditingWindow.EditAction.END -> lineEnd(focus)
            TextEditingWindow.EditAction.UP -> {
                val ls = lineStart(focus)
                if (ls == 0) 0 else {
                    val prevEnd = ls - 1
                    val prevStart = lineStart(prevEnd)
                    (prevStart + (focus - ls)).coerceAtMost(prevEnd)
                }
            }
            TextEditingWindow.EditAction.DOWN -> {
                val le = lineEnd(focus)
                if (le >= len) len else {
                    val nextStart = le + 1
                    (nextStart + (focus - lineStart(focus))).coerceAtMost(lineEnd(nextStart))
                }
            }
            else -> focus
        }
        if (selecting) {
            if (selAnchor < 0) selAnchor = anchor
            ic.setSelection(minOf(selAnchor, newFocus), maxOf(selAnchor, newFocus))
        } else {
            ic.setSelection(newFocus, newFocus)
        }
    }

    // ── 剪贴板面板 ───────────────────────────────────────────────

    override fun onPasteText(text: String) {
        currentInputConnection?.commitText(text, 1)
        windowManager?.showKeyboard()
    }

    // ── 表情面板 ─────────────────────────────────────────────────

    override fun onEmojiSelected(emoji: String) {
        commitDirect(emoji)
    }

    // ── 分类符号页 ───────────────────────────────────────────────

    override fun onSymbolSelected(symbol: String) {
        commitDirect(symbol)
    }

    override fun onSymbolBackspace() {
        sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
    }

    /** 回到进来前的那一层：键盘窗口的层没动过，123 层或数字键盘原样还在 */
    override fun onSymbolBack() {
        windowManager?.showKeyboard()
    }

    /**
     * 不经引擎、原样上屏（数字键盘、符号页、表情）。
     *
     * 有编码时先把首选上屏，再接这个字符——与在编码后打一个标点的效果一样（引擎打标点也是
     * 先顶首选）。没有候选的编码（五笔打了空码）上屏原文。不先处理的话，编码还挂在引擎里，
     * 字符却已插到了它前面。选词是同步回调，上屏顺序有保证。
     */
    private fun commitDirect(text: String) {
        if (composing && !directInput) {
            if (loadedCandidates.isNotEmpty()) {
                session.candidateSelected(0)
            } else {
                session.keyPressed(KeyAction.Enter, false)
            }
        }
        currentInputConnection?.commitText(text, 1)
    }

    override fun onEmojiBackspace() {
        // KEYCODE_DEL 由编辑器按字素删除，代理链正确处理 emoji 代理对
        sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
    }
}
