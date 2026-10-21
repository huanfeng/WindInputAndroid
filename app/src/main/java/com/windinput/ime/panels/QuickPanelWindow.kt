package com.windinput.ime.panels

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.windinput.ime.core.EngineCmd
import com.windinput.ime.core.SchemaEntry
import com.windinput.ime.core.UiEvent
import com.windinput.ime.keyboard.InputWindow
import com.windinput.ime.keyboard.Palette
import com.windinput.ime.keyboard.PanelIcon
import com.windinput.ime.keyboard.ToolbarItem

/**
 * 快捷功能面板：主流输入法「更多」页的形态——**方案一行 + 4 列图标格子**。
 *
 * 格子统一：上图标、下文字、等宽等高，开关类格子用强调色表示"开着"。搜狗/百度/Gboard
 * 的功能页都是这一套，用户不用学。此前是三段标签 + 大小不一的文字 chip，看着像设置页
 * 而不是键盘面板。
 *
 * **界面完全由本端定义，不镜像桌面菜单树。** Rust 侧只提供 [EngineCmd]（能调到什么）与
 * [UiEvent.Status]（当前是什么），排布与命名归这里。开关的**显示态一律来自引擎推送**
 * （[updateStatus]），本地不记一份：方案切换会改写模式短称，密码框会强制英文，
 * 都发生在引擎内部，本地自记必然漂移。
 */
class QuickPanelWindow(
    private val listener: Listener,
) : InputWindow() {

    interface Listener {
        fun onQuickCommand(cmd: EngineCmd)

        /** 打开某个面板（表情/剪贴板/文本编辑） */
        fun onOpenPanel(target: Target)

        fun onOpenSettings()

        fun onBackToKeyboard()

        /** 当前可选方案 */
        fun quickSchemas(): List<SchemaEntry>

        /** 弹系统输入法选择器 */
        fun onSwitchInputMethod()

        /** 系统里有没有可转发的语音输入法（没有就不摆入口，免得点不通） */
        fun voiceInputAvailable(): Boolean

        /** 切到系统语音输入法 */
        fun onVoiceInput()

        /** 用户手动开的无痕（本次键盘服务内有效，不落盘） */
        fun incognito(): Boolean

        /** 当前输入框自己要求无痕（密码框、应用置了 NO_PERSONALIZED_LEARNING），手动关不掉 */
        fun incognitoForced(): Boolean

        fun onToggleIncognito()

        /** 单手模式开着（键盘收窄靠向一侧） */
        fun oneHand(): Boolean

        /** 单手模式开关：打开到上次那一侧，再点关掉 */
        fun onToggleOneHand()

        /** 工具栏上常驻的按钮（顺序即排列） */
        fun toolbarPins(): List<ToolbarItem>

        /** 编辑工具栏后保存 */
        fun onToolbarPinsChanged(pins: List<ToolbarItem>)

        /** 当前方案能进临时拼音（码表方案） */
        fun tempPinyinAvailable(): Boolean

        /** 面板里点了一个也能常驻工具栏的入口（临时拼音 / 快捷输入），与工具栏上点它同一处理 */
        fun onToolbarItemFromPanel(item: ToolbarItem)
    }

    enum class Target { EMOJI, CLIPBOARD, TEXT_EDIT }

    private companion object {
        /** 英文方案 id（与 assets/data/schemas/english.schema.toml 一致） */
        const val ENGLISH_SCHEMA_ID = "english"
        const val COLUMNS = 4
        const val TILE_HEIGHT_DP = 64
        const val MIN_TILE_HEIGHT_DP = 40
    }

    private var schemaRow: LinearLayout? = null

    /**
     * 编辑工具栏中：能常驻的格子右上角出图钉（实心 = 已常驻），点格子改为切换常驻，
     * 不能常驻的格子（中文标点等开关）变淡、点了无效。顶栏右侧「编辑工具栏 / 完成」切换。
     */
    private var editingPins = false
    private var editButton: TextView? = null
    private var editHint: TextView? = null
    private var tileGrid: GridLayout? = null
    private var status: UiEvent.Status? = null

    override fun onCreateView(context: Context): View {
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(4), dp(8))
        }

        val schemas = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        schemaRow = schemas
        column.addView(
            schemas,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(38)).apply {
                bottomMargin = dp(10)
            },
        )

        val hint = TextView(context).apply {
            text = "点带图钉的格子，选常驻在工具栏上的按钮（最多 ${ToolbarItem.MAX_PINS} 个）"
            textSize = 12f
            setTextColor(Palette.current.hint)
            visibility = View.GONE
        }
        editHint = hint
        column.addView(
            hint,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            },
        )

        val grid = GridLayout(context).apply { columnCount = COLUMNS }
        tileGrid = grid
        column.addView(
            grid,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        rebuildSchemas(context)
        rebuildTiles(context)

        // 面板高度固定（= 条区 + 键盘），格子多了要能滚
        val scroll = ScrollView(context).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(column)
        }
        val edit = PanelScaffold.headerButton(context, "编辑工具栏") { setEditing(context, !editingPins) }
        editButton = edit
        return PanelScaffold.build(
            context = context,
            title = "快捷功能",
            onBack = listener::onBackToKeyboard,
            content = scroll,
            rightView = edit,
        )
    }

    private fun setEditing(context: Context, on: Boolean) {
        editingPins = on
        editButton?.text = if (on) "完成" else "编辑工具栏"
        editHint?.visibility = if (on) View.VISIBLE else View.GONE
        rebuildTiles(context)
    }

    /** 离开面板就退出编辑：下次进来是正常的快捷面板 */
    override fun onDetached() {
        val ctx = schemaRow?.context ?: return
        if (editingPins) setEditing(ctx, false)
    }

    private fun togglePin(context: Context, item: ToolbarItem) {
        val pins = listener.toolbarPins()
        val next = if (item in pins) pins - item else (pins + item).take(ToolbarItem.MAX_PINS)
        listener.onToolbarPinsChanged(next)
        rebuildTiles(context)
    }

    /**
     * 引擎推送的模式真值到达：刷新开关选中态。
     *
     * 视图未创建时只存值——面板是懒创建的，首次打开时 [onCreateView] 会用这份最新值构建。
     */
    fun updateStatus(newStatus: UiEvent.Status) {
        status = newStatus
        val ctx = schemaRow?.context ?: return
        rebuildTiles(ctx)
        rebuildSchemas(ctx)
    }

    /** 每次进入面板刷新一次：方案可能在别处（键盘快捷键/设置页）被切过 */
    override fun onAttached() = refresh()

    /**
     * 重画方案行与功能格子。切方案在后台做，引擎推状态时后台任务还没收尾、方案列表还是
     * 切之前的缓存——所以后台切完（忙状态解除）时 Service 再叫一次这里。
     */
    fun refresh() {
        val ctx = schemaRow?.context ?: return
        rebuildSchemas(ctx)
        rebuildTiles(ctx)
    }

    /** 方案一行：分段选择器，等宽，选中项实底 */
    private fun rebuildSchemas(context: Context) {
        val row = schemaRow ?: return
        val density = context.resources.displayMetrics.density
        row.removeAllViews()
        val chinese = status?.chineseMode ?: true
        fun segment(label: String, selected: Boolean, onClick: () -> Unit) {
            row.addView(
                chip(context, label, selected, onClick),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                    marginEnd = (6 * density).toInt()
                },
            )
        }
        // "英文" 与各方案是一组单选：这与桌面主菜单的分组语义一致。
        // 英文方案不单列一格：这个「英」就是它的入口，两格并排会让人以为是两回事。
        segment("英", selected = !chinese) { listener.onQuickCommand(EngineCmd.English) }
        for (s in listener.quickSchemas().filter { it.id != ENGLISH_SCHEMA_ID }) {
            segment(s.name.ifEmpty { s.id }, selected = chinese && s.active) {
                listener.onQuickCommand(EngineCmd.SelectSchema(s.index))
            }
        }
    }

    /** 功能格子：开关在前（带选中态），入口在后 */
    private fun rebuildTiles(context: Context) {
        val grid = tileGrid ?: return
        grid.removeAllViews()
        val st = status
        val tiles = mutableListOf<View>()
        val pins = listener.toolbarPins()

        /**
         * [icon]：字（「，」「Ａ」这类本身就是字符的）或矢量图标 [PanelIcon.Glyph]。
         * [pin]：这一格能常驻工具栏时对应的项（编辑工具栏时据此出图钉）
         */
        fun add(icon: Any, label: String, selected: Boolean? = null, pin: ToolbarItem? = null, onClick: () -> Unit) {
            tiles += when {
                !editingPins -> tile(context, icon, label, selected, null, onClick)
                pin == null -> tile(context, icon, label, selected, null) {}.apply { alpha = 0.35f }
                else -> tile(context, icon, label, selected, pin in pins) { togglePin(context, pin) }
            }
        }
        add("，", "中文标点", selected = st?.chinesePunct ?: true) {
            listener.onQuickCommand(EngineCmd.TogglePunct)
        }
        add("Ａ", "全角", selected = st?.fullWidth ?: false) {
            listener.onQuickCommand(EngineCmd.ToggleWidth)
        }
        // 简繁格仅在用户启用简繁功能后出现（与桌面工具栏同一判据）
        if (st?.s2tShown != false) {
            add("繁", "简入繁出", selected = st?.s2tEnabled ?: false) {
                listener.onQuickCommand(EngineCmd.ToggleS2t)
            }
        }
        // 无痕是键盘自己的状态（引擎只管「这次学不学」），选中态取本端，不等引擎推送。
        // 选中态显示**实际**是否无痕：密码框里条区亮着「🕶 无痕」，这格却灭着，就对不上了
        val forced = listener.incognitoForced()
        add(PanelIcon.Glyph.INCOGNITO, if (forced) "无痕·本框" else "无痕", selected = listener.incognito() || forced, pin = ToolbarItem.INCOGNITO) {
            listener.onToggleIncognito()
            rebuildTiles(context)
        }
        // 临时拼音只对码表方案（五笔）有意义：拼音方案本来就在打拼音
        if (listener.tempPinyinAvailable()) {
            add("拼", "临时拼音", pin = ToolbarItem.TEMP_PINYIN) { listener.onToolbarItemFromPanel(ToolbarItem.TEMP_PINYIN) }
        }
        add(";", "快捷输入", pin = ToolbarItem.QUICK_INPUT) { listener.onToolbarItemFromPanel(ToolbarItem.QUICK_INPUT) }
        add(PanelIcon.Glyph.EMOJI, "表情", pin = ToolbarItem.EMOJI) { listener.onOpenPanel(Target.EMOJI) }
        add(PanelIcon.Glyph.CLIPBOARD, "剪贴板", pin = ToolbarItem.CLIPBOARD) { listener.onOpenPanel(Target.CLIPBOARD) }
        add(PanelIcon.Glyph.TEXT_EDIT, "文本编辑", pin = ToolbarItem.TEXT_EDIT) { listener.onOpenPanel(Target.TEXT_EDIT) }
        if (listener.voiceInputAvailable()) {
            add(PanelIcon.Glyph.MIC, "语音输入", pin = ToolbarItem.VOICE) { listener.onVoiceInput() }
        }
        add(PanelIcon.Glyph.GLOBE, "切换输入法", pin = ToolbarItem.SWITCH_IME) { listener.onSwitchInputMethod() }
        add(PanelIcon.Glyph.SETTINGS, "设置", pin = ToolbarItem.SETTINGS) { listener.onOpenSettings() }
        // 放最后：插在中间会把后面各格整体挪一位，老用户的肌肉记忆（与按位置点的测试）全乱
        add(PanelIcon.Glyph.ONE_HAND, "单手", selected = listener.oneHand(), pin = ToolbarItem.ONE_HAND) {
            listener.onToggleOneHand() // 选中态由 Service 改完配置后经 refresh 重画
        }
        val heightPx = tileHeightPx(context, rows = (tiles.size + COLUMNS - 1) / COLUMNS)
        for (t in tiles) grid.addView(t, cell(context, heightPx))
    }

    /**
     * 格子高度：所有行在键盘区里一屏放下（此前固定 64dp，第三行被底边裁掉一大半，「切换输入法」
     * 「设置」只露出图标顶，而滚动条是隐藏的，用户不知道还能滑）。上限是原来的 64dp，下限 40dp
     * ——再矮图标与字就叠了，那时宁可滚动。
     */
    private fun tileHeightPx(context: Context, rows: Int): Int {
        val density = context.resources.displayMetrics.density
        val reserved = (8 + 8 + 38 + 10) * density // 上下内边距 + 方案行及其下间距
        val perRow = (PanelScaffold.keyboardHeightPx(context) - reserved) / rows.coerceAtLeast(1) - 6 * density
        return perRow.coerceIn(MIN_TILE_HEIGHT_DP * density, TILE_HEIGHT_DP * density).toInt()
    }

    private fun cell(context: Context, heightPx: Int) = GridLayout.LayoutParams().apply {
        val density = context.resources.displayMetrics.density
        width = 0
        height = heightPx
        columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
        setMargins(0, 0, (6 * density).toInt(), (6 * density).toInt())
    }

    /**
     * 一个格子：上图标下文字。[selected] 为 null = 纯入口（无选中态）；
     * true/false = 开关，开着用强调色实底。
     */
    private fun tile(
        context: Context,
        icon: Any,
        label: String,
        selected: Boolean?,
        pinned: Boolean?,
        onClick: () -> Unit,
    ): View {
        val density = context.resources.displayMetrics.density
        val p = Palette.current
        val on = selected == true
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = 10 * density
                setColor(if (on) p.accent else p.key)
            }
            val fg = if (on) p.onAccent else p.text
            if (icon is PanelIcon.Glyph) {
                // 矢量图标而不是 emoji：emoji 各机型长相不一、颜色写死，跟着选中态变不了色
                val size = (28 * density).toInt()
                addView(
                    PanelIcon(context).apply {
                        glyph = icon
                        tint = fg
                        scale = 0.86f
                        decorative = true
                    },
                    LinearLayout.LayoutParams(size, size),
                )
            } else {
                addView(
                    TextView(context).apply {
                        text = icon.toString()
                        textSize = 20f
                        gravity = Gravity.CENTER
                        setTextColor(fg)
                    },
                )
            }
            addView(
                TextView(context).apply {
                    text = label
                    textSize = 11f
                    maxLines = 1
                    gravity = Gravity.CENTER
                    setTextColor(if (on) p.onAccent else p.hint)
                    setPadding(0, (2 * density).toInt(), 0, 0)
                },
            )
            setOnClickListener { onClick() }
        }
        if (pinned == null) return body
        // 编辑工具栏：右上角图钉，实心 = 已常驻
        val badge = (18 * density).toInt()
        return android.widget.FrameLayout(context).apply {
            addView(body, android.widget.FrameLayout.LayoutParams(MATCH, MATCH))
            addView(
                PanelIcon(context).apply {
                    glyph = if (pinned) PanelIcon.Glyph.PIN else PanelIcon.Glyph.PIN_OUTLINE
                    tint = if (pinned) p.accentText else p.hint
                    scale = 0.9f
                    decorative = true
                },
                android.widget.FrameLayout.LayoutParams(badge, badge, Gravity.TOP or Gravity.END).apply {
                    topMargin = (3 * density).toInt()
                    marginEnd = (3 * density).toInt()
                },
            )
        }
    }

    private val MATCH = android.view.ViewGroup.LayoutParams.MATCH_PARENT

    private fun chip(
        context: Context,
        label: String,
        selected: Boolean,
        onClick: () -> Unit,
    ): TextView {
        val density = context.resources.displayMetrics.density
        val p = Palette.current
        return TextView(context).apply {
            text = label
            textSize = 13f
            gravity = Gravity.CENTER
            maxLines = 1
            setTextColor(if (selected) p.onAccent else p.text)
            background = GradientDrawable().apply {
                cornerRadius = 8 * density
                setColor(if (selected) p.accent else p.key)
            }
            setOnClickListener { onClick() }
        }
    }
}
