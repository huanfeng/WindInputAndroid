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
 * 键盘上方的条区：**一行**（候选 / 工具栏 / 剪贴板建议 三态），高度恒定 [TOTAL_DP]。
 *
 * ```
 *   ╭ 五 aa 1/3 ╮                  编码栏：浮层（PreeditPopup），伸出窗口顶部，不占高度
 * ┌──────────────────────────────┐
 * │ 式  戒  工  ...            ∨ │  主行：候选 / 工具栏 / 剪贴板建议
 * └──────────────────────────────┘
 * ```
 *
 * **高度为什么必须恒定**：条区高度直接决定 IME 窗口高度。若编码出现时整体变高，
 * 每次起手打字键盘都会向上跳一下、宿主界面跟着 reflow。此前编码栏是条区里的一行，
 * 为了恒定空闲时也得把那 26dp 占着——工具栏 70dp 高，真机上明显太高。现在编码栏
 * 是浮层，条区只剩主行。
 *
 * 主行三态优先级：候选 > 剪贴板建议 > 工具栏；建议条 30s 超时自动消失。
 *
 * **候选展开态**（下方铺开全部候选）主行强制切工具栏：候选已在网格里，条上再画一遍
 * 纯属重复；同时尾格由「收起键盘」变「返回」——展开面板自己没有返回栏，
 * 这一格就是它唯一的退路。
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

        /** 候选栏滚到接近尾部、且核心那边还有更多：请求续取一段 */
        fun onNeedMoreCandidates()

        fun onPasteSuggestion(text: String)
    }

    enum class ToolbarAction { QUICK_PANEL, HIDE_IME }

    companion object {
        /** 主行高度（dp）：候选/工具栏 */
        const val MAIN_ROW_DP = 44f

        /** 条区总高 = 主行；编码栏是浮层不计入。恒定不随组合态变化 */
        const val TOTAL_DP = MAIN_ROW_DP

        private const val SUGGESTION_TIMEOUT_MS = 30_000L

        private const val PREPARING_TEXT = "词库准备中…"

        /** 工具栏两端图标格的宽度（dp）：入口在最左、尾格在最右，中间留空 */
        const val TOOLBAR_ICON_DP = 52

        /** ViewGroup.LayoutParams.MATCH_PARENT 的短名（类内无法裸用继承来的常量） */
        private const val MATCH = android.view.ViewGroup.LayoutParams.MATCH_PARENT
    }

    private enum class State { TOOLBAR, CANDIDATES, CLIP_SUGGESTION }

    val candidateBar = CandidateBarView(context).apply {
        listener = candidateListener
        onNeedMore = { this@BarView.listener.onNeedMoreCandidates() }
    }

    private val density = resources.displayMetrics.density

    // ── 编码栏（浮层）──────────────────────────────────────────────
    private val preeditPopup = PreeditPopup(this)

    /** 核心构造期的提示是否正在显示（与真编码共用同一个浮层） */
    private var preparing = false

    // ── 主行 ─────────────────────────────────────────────────────
    private val mainRow: FrameLayout
    private val candidateRow: View
    private val toolbar: View
    private val suggestionRow: LinearLayout
    private val suggestionText: TextView

    private var state = State.TOOLBAR
    private var hasCandidates = false

    /** 候选是否已在下方面板铺开（见 [setExpanded]） */
    private var expanded = false

    /** 候选态尾格：展开全部候选 */
    private val expandButton = PanelIcon(context).apply {
        glyph = PanelIcon.Glyph.EXPAND
        setOnClickListener { listener.onToggleExpanded() }
    }

    /**
     * 工具栏尾格：与 [expandButton] 同一个屏幕位置（条区最右）。
     *
     * 常态是「收起键盘」；候选展开时变「返回」——用户看到的就是「原下拉钮的位置
     * 变成了返回」。两个动作共用一格而不是并排两格：这一格的语义恒为
     * 「离开当前这层」，并排反而要用户分辨两个下箭头。
     */
    /**
     * 无痕标记：放在空闲工具栏中间那块空地上。无痕是「看不见的状态」，忘了关就一直学不会，
     * 必须常驻可见；只在工具栏态显示（打字时条区归候选）。
     */
    private val incognitoLabel = TextView(context).apply {
        text = "🕶 无痕"
        gravity = Gravity.CENTER
        textSize = 13f
        setTextColor(Palette.current.hint)
        // INVISIBLE 不是 GONE：它兼做中间的弹性占位，GONE 掉尾格就挤到左边去了
        visibility = INVISIBLE
    }

    /** 学习是否暂停（手动无痕或输入框要求），见 [incognitoLabel] */
    fun setIncognito(on: Boolean) {
        incognitoLabel.visibility = if (on) VISIBLE else INVISIBLE
    }

    private val trailingIcon = PanelIcon(context).apply {
        glyph = PanelIcon.Glyph.COLLAPSE
        setOnClickListener {
            if (expanded) listener.onToggleExpanded() else listener.onToolbarAction(ToolbarAction.HIDE_IME)
        }
    }

    private val dismissSuggestion = Runnable { hideClipSuggestion() }

    init {
        orientation = VERTICAL

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
            setTextColor(Palette.current.text)
        }
        suggestionRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            setBackgroundColor(Palette.current.surface)
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
        toolbar = buildToolbar()

        mainRow = FrameLayout(context).apply {
            addView(toolbar, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(candidateRow, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(suggestionRow, FrameLayout.LayoutParams(MATCH, MATCH))
        }

        addView(mainRow, LayoutParams(MATCH, MATCH))
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
            if (!preparing) preeditPopup.dismiss()
        } else {
            preparing = false
            preeditPopup.show(preedit, modeLabel, if (totalPages > 1) "$page/$totalPages" else "")
        }

        // 展开态下主行归工具栏管：候选正在下方网格里铺开，条上不再重复一份
        if (expanded) {
            // 但 hasCandidates 已更新——收起时要据此决定回候选态还是工具栏态
            return
        }
        if (hasCandidates) {
            applyState(State.CANDIDATES)
        } else if (state == State.CANDIDATES) {
            applyState(State.TOOLBAR)
        }
    }

    /** 续取到货：只把新的一段交给候选栏（不重量已有项，见 CandidateBarView 的说明） */
    fun appendCandidates(more: List<String>) {
        if (more.isEmpty()) return
        candidateBar.appendCandidates(more)
        hasCandidates = true
    }

    /**
     * 候选全览面板的开合：条区随之在「候选行」与「工具栏（快捷模式）」之间切换，
     * 尾格同步在「收起键盘」与「返回」之间切换。
     */
    fun setExpanded(expanded: Boolean) {
        this.expanded = expanded
        trailingIcon.glyph =
            if (expanded) PanelIcon.Glyph.BACK else PanelIcon.Glyph.COLLAPSE
        applyState(
            when {
                expanded -> State.TOOLBAR
                hasCandidates -> State.CANDIDATES
                else -> State.TOOLBAR
            },
        )
    }

    /**
     * 核心尚在后台构造：编码栏位置显示提示条。
     *
     * 不弹 Toast、不禁用键盘——用户此时按键会直接上屏英文（宿主默认行为），
     * 键盘照常可用，只是没有中文候选。给一行静态提示即可，别打断输入。
     */
    fun setPreparing(preparing: Boolean) {
        this.preparing = preparing
        if (preparing) preeditPopup.show(PREPARING_TEXT, "", "") else preeditPopup.dismiss()
    }

    /** 收掉编码浮层。键盘收起 / 面板切换时必须调：浮层是独立窗口，不随条区隐藏 */
    fun hidePreedit() {
        preparing = false
        preeditPopup.dismiss()
    }

    override fun onDetachedFromWindow() {
        hidePreedit()
        super.onDetachedFromWindow()
    }

    override fun setVisibility(visibility: Int) {
        super.setVisibility(visibility)
        // 面板打开时条区让位（GONE），编码浮层跟着收；它锚在条区上，条区不在了它就没有落点
        if (visibility != VISIBLE) hidePreedit()
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

    /**
     * 空闲工具栏，按大众布局（豆包 / 微信，docs/android-ime-features.md §14.1）只放两格：
     * 左端是快捷面板入口（表情、剪贴板、文本编辑、设置都在面板里），右端是尾格
     * （见 [trailingIcon]），中间留空。此前常驻 6 个等宽图标，空闲时满屏是按钮。
     */
    private fun buildToolbar(): View = LinearLayout(context).apply {
        orientation = HORIZONTAL
        setBackgroundColor(Palette.current.surface)
        addView(
            icon(PanelIcon.Glyph.GRID) { listener.onToolbarAction(ToolbarAction.QUICK_PANEL) },
            LayoutParams(dp(TOOLBAR_ICON_DP), LayoutParams.MATCH_PARENT),
        )
        addView(incognitoLabel, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        addView(trailingIcon, LayoutParams(dp(TOOLBAR_ICON_DP), LayoutParams.MATCH_PARENT))
    }

    private fun icon(glyph: PanelIcon.Glyph, onClick: () -> Unit): PanelIcon =
        PanelIcon(context).apply {
            this.glyph = glyph
            setOnClickListener { onClick() }
        }
}
