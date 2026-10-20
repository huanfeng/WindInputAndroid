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

        fun onToggleIncognito()
    }

    enum class Target { EMOJI, CLIPBOARD, TEXT_EDIT }

    private companion object {
        /** 英文方案 id（与 assets/data/schemas/english.schema.toml 一致） */
        const val ENGLISH_SCHEMA_ID = "english"
        const val COLUMNS = 4
        const val TILE_HEIGHT_DP = 64
    }

    private var schemaRow: LinearLayout? = null
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
        return PanelScaffold.build(
            context = context,
            title = "快捷功能",
            onBack = listener::onBackToKeyboard,
            content = scroll,
        )
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
    override fun onAttached() {
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
        fun add(icon: String, label: String, selected: Boolean? = null, onClick: () -> Unit) {
            grid.addView(tile(context, icon, label, selected, onClick), cell(context))
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
        // 无痕是键盘自己的状态（引擎只管「这次学不学」），选中态取本端，不等引擎推送
        add("🕶", "无痕", selected = listener.incognito()) {
            listener.onToggleIncognito()
            rebuildTiles(context)
        }
        add("😀", "表情") { listener.onOpenPanel(Target.EMOJI) }
        add("📋", "剪贴板") { listener.onOpenPanel(Target.CLIPBOARD) }
        add("✂", "文本编辑") { listener.onOpenPanel(Target.TEXT_EDIT) }
        if (listener.voiceInputAvailable()) {
            add("🎤", "语音输入") { listener.onVoiceInput() }
        }
        add("🌐", "切换输入法") { listener.onSwitchInputMethod() }
        add("⚙", "设置") { listener.onOpenSettings() }
    }

    private fun cell(context: Context) = GridLayout.LayoutParams().apply {
        val density = context.resources.displayMetrics.density
        width = 0
        height = (TILE_HEIGHT_DP * density).toInt()
        columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
        setMargins(0, 0, (6 * density).toInt(), (6 * density).toInt())
    }

    /**
     * 一个格子：上图标下文字。[selected] 为 null = 纯入口（无选中态）；
     * true/false = 开关，开着用强调色实底。
     */
    private fun tile(
        context: Context,
        icon: String,
        label: String,
        selected: Boolean?,
        onClick: () -> Unit,
    ): View {
        val density = context.resources.displayMetrics.density
        val p = Palette.current
        val on = selected == true
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = 10 * density
                setColor(if (on) p.accent else p.key)
            }
            addView(
                TextView(context).apply {
                    text = icon
                    textSize = 20f
                    gravity = Gravity.CENTER
                    setTextColor(if (on) p.onAccent else p.text)
                },
            )
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
    }

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
