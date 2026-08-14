package com.windinput.ime.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 键盘上方的条区：**编码栏 + 主行**两层，总高恒定。
 *
 * ```
 * ┌──────────────────────────────┐
 * │ 五 aa                    1/3 │  编码栏（CODE_BAR_DP，仅组合中显示）
 * ├──────────────────────────────┤
 * │ 式  戒  工  ...            ∨ │  主行：候选 / 工具栏 / 剪贴板建议
 * └──────────────────────────────┘
 * ```
 *
 * **总高为什么必须恒定**：条区高度直接决定 IME 窗口高度。若编码栏出现时整体变高，
 * 每次起手打字键盘都会向上跳一下、宿主界面跟着 reflow，观感与误触都很糟。
 * 故空闲时编码栏 GONE、主行（工具栏）吃满全高，组合中主行让出 [CODE_BAR_DP]。
 *
 * 主行三态优先级：候选 > 剪贴板建议 > 工具栏；建议条 30s 超时自动消失。
 */
@SuppressLint("ViewConstructor")
class BarView(
    context: Context,
    candidateListener: CandidateBarView.Listener,
    private val listener: Listener,
) : LinearLayout(context) {

    interface Listener {
        fun onToolbarAction(action: ToolbarAction)

        /** 展开/收起候选全览（同一个钮往返） */
        fun onToggleExpanded()

        fun onPasteSuggestion(text: String)
    }

    enum class ToolbarAction { QUICK_PANEL, EMOJI, CLIPBOARD, TEXT_EDIT, SETTINGS, HIDE_IME }

    companion object {
        /** 编码栏高度（dp）：明显小于主行，视觉上从属于候选 */
        const val CODE_BAR_DP = 26f

        /** 主行高度（dp）：候选/工具栏 */
        const val MAIN_ROW_DP = 44f

        /** 条区总高 = 编码栏 + 主行，恒定不随组合态变化 */
        const val TOTAL_DP = CODE_BAR_DP + MAIN_ROW_DP

        private const val SUGGESTION_TIMEOUT_MS = 30_000L

        private const val PREPARING_TEXT = "词库准备中…"

        /** 工具栏图标与动作（顺序即从左到右）；模式指示另置最左 */
        private val TOOLBAR_ITEMS = listOf(
            PanelIcon.Glyph.GRID to ToolbarAction.QUICK_PANEL,
            PanelIcon.Glyph.EMOJI to ToolbarAction.EMOJI,
            PanelIcon.Glyph.CLIPBOARD to ToolbarAction.CLIPBOARD,
            PanelIcon.Glyph.TEXT_EDIT to ToolbarAction.TEXT_EDIT,
            PanelIcon.Glyph.SETTINGS to ToolbarAction.SETTINGS,
            PanelIcon.Glyph.COLLAPSE to ToolbarAction.HIDE_IME,
        )

        /** ViewGroup.LayoutParams.MATCH_PARENT 的短名（类内无法裸用继承来的常量） */
        private const val MATCH = android.view.ViewGroup.LayoutParams.MATCH_PARENT
    }

    private enum class State { TOOLBAR, CANDIDATES, CLIP_SUGGESTION }

    val candidateBar = CandidateBarView(context).apply { listener = candidateListener }

    private val density = resources.displayMetrics.density

    // ── 编码栏 ───────────────────────────────────────────────────
    private val modeLabelView: TextView
    private val preeditView: TextView
    private val pagerView: TextView
    private val codeBar: LinearLayout

    // ── 主行 ─────────────────────────────────────────────────────
    private val mainRow: FrameLayout
    private val candidateRow: View
    private val toolbar: View
    private val suggestionRow: LinearLayout
    private val suggestionText: TextView

    private var state = State.TOOLBAR
    private var hasCandidates = false

    /** 工具栏上的模式指示（"五"/"英"），由引擎推送更新 */
    private val modeChip: TextView

    /** 候选展开/收起钮：展开态图标翻转，同一个钮往返 */
    private val expandButton = PanelIcon(context).apply {
        glyph = PanelIcon.Glyph.EXPAND
        setOnClickListener { listener.onToggleExpanded() }
    }

    private val dismissSuggestion = Runnable { hideClipSuggestion() }

    init {
        orientation = VERTICAL

        // ── 编码栏 ──
        modeLabelView = TextView(context).apply {
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF4A78C8.toInt())
            setPadding(dp(6), dp(1), dp(6), dp(1))
        }
        preeditView = TextView(context).apply {
            textSize = 15f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(0xFF32568C.toInt())
            setPadding(dp(8), 0, dp(8), 0)
        }
        pagerView = TextView(context).apply {
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(0xFF969696.toInt())
            setPadding(dp(8), 0, dp(12), 0)
        }
        codeBar = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFFFAFAFC.toInt())
            setPadding(dp(10), 0, 0, 0)
            visibility = GONE
            addView(modeLabelView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
            addView(preeditView, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
            addView(pagerView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        }

        // ── 主行：候选（含展开钮） ──
        candidateRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            addView(
                candidateBar,
                LayoutParams(0, LayoutParams.MATCH_PARENT, 1f),
            )
            addView(
                expandButton,
                LayoutParams(dp(44), LayoutParams.MATCH_PARENT),
            )
        }

        // ── 主行：剪贴板建议 ──
        suggestionText = TextView(context).apply {
            textSize = 15f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(0xFF1E1E1E.toInt())
        }
        suggestionRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            setBackgroundColor(0xFFFAFAFC.toInt())
            addView(
                TextView(context).apply {
                    text = "📋"
                    textSize = 16f
                    gravity = Gravity.CENTER
                    setPadding(dp(12), 0, dp(8), 0)
                },
                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT),
            )
            addView(suggestionText, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
            addView(
                icon(PanelIcon.Glyph.CLOSE) { hideClipSuggestion() },
                LayoutParams(dp(44), LayoutParams.MATCH_PARENT),
            )
        }

        // ── 主行：工具栏 ──
        modeChip = TextView(context).apply {
            text = "五"
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(0xFF32568C.toInt())
            setOnClickListener { listener.onToolbarAction(ToolbarAction.QUICK_PANEL) }
        }
        toolbar = buildToolbar()

        mainRow = FrameLayout(context).apply {
            addView(toolbar, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(candidateRow, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(suggestionRow, FrameLayout.LayoutParams(MATCH, MATCH))
        }

        addView(codeBar, LayoutParams(MATCH, dp(CODE_BAR_DP)))
        // 主行用 weight 吃掉剩余高度：编码栏一显一隐，主行自动伸缩，总高不变
        addView(mainRow, LayoutParams(MATCH, 0, 1f))
        applyState(State.TOOLBAR)
    }

    /** 条区自身高度写死为 [TOTAL_DP]——它是"总高恒定"这条规矩的落点 */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(
            widthMeasureSpec,
            MeasureSpec.makeMeasureSpec(dp(TOTAL_DP), MeasureSpec.EXACTLY),
        )
    }

    /**
     * 候选帧到达：更新编码栏与候选栏。
     *
     * 编码栏的显隐只看 [preedit] 是否为空，**不看候选是否为空**：有编码但零候选
     * （生僻码、打错码）时编码栏仍要显示，否则用户看不到自己打了什么。
     */
    fun setCandidateFrame(
        items: List<String>,
        preedit: String,
        modeLabel: String,
        page: Int,
        totalPages: Int,
    ) {
        candidateBar.setCandidates(items)
        hasCandidates = items.isNotEmpty()

        if (preedit.isEmpty()) {
            codeBar.visibility = GONE
        } else {
            codeBar.visibility = VISIBLE
            preeditView.text = preedit
            modeLabelView.visibility = if (modeLabel.isEmpty()) GONE else VISIBLE
            modeLabelView.text = modeLabel
            pagerView.text = if (totalPages > 1) "$page/$totalPages" else ""
        }

        if (hasCandidates) {
            applyState(State.CANDIDATES)
        } else if (state == State.CANDIDATES) {
            applyState(State.TOOLBAR)
        }
    }

    /** 候选全览的展开态：钮的图标随之翻转（展开↔收起同一个钮） */
    fun setExpanded(expanded: Boolean) {
        expandButton.glyph =
            if (expanded) PanelIcon.Glyph.COLLAPSE else PanelIcon.Glyph.EXPAND
    }

    /**
     * 核心尚在后台构造：编码栏位置显示提示条。
     *
     * 不弹 Toast、不禁用键盘——用户此时按键会直接上屏英文（宿主默认行为），
     * 键盘照常可用，只是没有中文候选。给一行静态提示即可，别打断输入。
     */
    fun setPreparing(preparing: Boolean) {
        if (!preparing) {
            if (preeditView.text == PREPARING_TEXT) codeBar.visibility = GONE
            return
        }
        codeBar.visibility = VISIBLE
        modeLabelView.visibility = GONE
        pagerView.text = ""
        preeditView.text = PREPARING_TEXT
    }

    /** 引擎模式真值 → 工具栏指示（K 侧不自记开关态） */
    fun setStatus(status: com.windinput.ime.core.UiEvent.Status) {
        modeChip.text = status.iconLabel.ifEmpty { if (status.chineseMode) "中" else "英" }
    }

    /** 复制新内容后的建议条（候选优先级更高，仅空闲时展示） */
    fun showClipSuggestion(text: String) {
        if (hasCandidates) return
        suggestionText.text = text
        suggestionText.setOnClickListener {
            hideClipSuggestion()
            listener.onPasteSuggestion(text)
        }
        applyState(State.CLIP_SUGGESTION)
        removeCallbacks(dismissSuggestion)
        postDelayed(dismissSuggestion, SUGGESTION_TIMEOUT_MS)
    }

    private fun hideClipSuggestion() {
        removeCallbacks(dismissSuggestion)
        if (state == State.CLIP_SUGGESTION) {
            applyState(if (hasCandidates) State.CANDIDATES else State.TOOLBAR)
        }
    }

    private fun applyState(newState: State) {
        state = newState
        toolbar.visibility = if (newState == State.TOOLBAR) VISIBLE else GONE
        candidateRow.visibility = if (newState == State.CANDIDATES) VISIBLE else GONE
        suggestionRow.visibility = if (newState == State.CLIP_SUGGESTION) VISIBLE else GONE
    }

    private fun dp(value: Float): Int = (value * density).toInt()

    private fun dp(value: Int): Int = (value * density).toInt()

    private fun iconButton(label: String, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(0xFF646464.toInt())
            setPadding(dp(14), 0, dp(14), 0)
            setOnClickListener { onClick() }
        }

    /**
     * 空闲工具栏。左端是**模式指示兼快捷面板入口**（点它进方案/开关/设置的全功能页），
     * 右端是收起键盘，中间放高频面板入口。
     *
     * 图标目前是文本 emoji 占位；正式版换主题化矢量图标（wind-theme 键盘域节点）。
     */
    private fun buildToolbar(): View = LinearLayout(context).apply {
        orientation = HORIZONTAL
        setBackgroundColor(0xFFFAFAFC.toInt())

        addView(modeChip, LayoutParams(0, LayoutParams.MATCH_PARENT, 1.2f))
        for ((glyph, action) in TOOLBAR_ITEMS) {
            addView(
                icon(glyph) { listener.onToolbarAction(action) },
                LayoutParams(0, LayoutParams.MATCH_PARENT, 1f),
            )
        }
    }

    private fun icon(glyph: PanelIcon.Glyph, onClick: () -> Unit): PanelIcon =
        PanelIcon(context).apply {
            this.glyph = glyph
            setOnClickListener { onClick() }
        }
}
