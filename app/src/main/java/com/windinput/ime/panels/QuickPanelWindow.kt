package com.windinput.ime.panels

import android.content.Context
import android.graphics.Color
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

/**
 * 快捷功能面板：方案切换 + 模式开关 + 各面板与设置入口。
 *
 * **界面完全由本端定义，不镜像桌面菜单树。** 桌面那棵功能菜单里有截图、打开日志目录、
 * 诊断 HUD、语言栏图标等一批桌面专属项，照搬过来只会制造点不通的入口；Rust 侧只提供
 * [EngineCmd]（能调到什么）与 [UiEvent.Status]（当前是什么），排布与命名归这里。
 *
 * 开关的**显示态一律来自引擎推送**（[updateStatus]），本地不记一份：方案切换会改写模式
 * 短称，密码框会强制英文，都发生在引擎内部，本地自记必然漂移。
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
    }

    enum class Target { EMOJI, CLIPBOARD, TEXT_EDIT }

    private var schemaRow: LinearLayout? = null
    private var toggleGrid: GridLayout? = null
    private var status: UiEvent.Status? = null

    override fun onCreateView(context: Context): View {
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(4), dp(12), dp(8))
        }

        column.addView(sectionLabel(context, "输入方案"))
        val schemas = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        schemaRow = schemas
        column.addView(schemas, matchWrap())

        column.addView(sectionLabel(context, "开关"))
        val grid = GridLayout(context).apply { columnCount = 4 }
        toggleGrid = grid
        column.addView(grid, matchWrap())

        column.addView(sectionLabel(context, "更多"))
        val more = GridLayout(context).apply { columnCount = 4 }
        more.addView(chip(context, "😀 表情") { listener.onOpenPanel(Target.EMOJI) }, cell(density))
        more.addView(
            chip(context, "📋 剪贴板") { listener.onOpenPanel(Target.CLIPBOARD) },
            cell(density),
        )
        more.addView(
            chip(context, "✂ 文本编辑") { listener.onOpenPanel(Target.TEXT_EDIT) },
            cell(density),
        )
        more.addView(chip(context, "⚙ 设置") { listener.onOpenSettings() }, cell(density))
        column.addView(more, matchWrap())

        rebuildSchemas(context)
        rebuildToggles(context)

        // 面板高度固定（= 键盘高），条目多了要能滚
        val scroll = ScrollView(context).apply {
            isFillViewport = true
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
        rebuildToggles(ctx)
        rebuildSchemas(ctx)
    }

    /** 每次进入面板刷新一次：方案可能在别处（键盘快捷键/设置页）被切过 */
    override fun onAttached() {
        val ctx = schemaRow?.context ?: return
        rebuildSchemas(ctx)
        rebuildToggles(ctx)
    }

    private fun rebuildSchemas(context: Context) {
        val row = schemaRow ?: return
        val density = context.resources.displayMetrics.density
        row.removeAllViews()
        val chinese = status?.chineseMode ?: true
        // "英文" 与各方案是一组单选：这与桌面主菜单的分组语义一致
        row.addView(
            chip(context, "英", selected = !chinese) {
                listener.onQuickCommand(EngineCmd.English)
            },
            LinearLayout.LayoutParams(0, (40 * density).toInt(), 1f).apply {
                marginEnd = (6 * density).toInt()
            },
        )
        for (s in listener.quickSchemas()) {
            row.addView(
                chip(context, s.name.ifEmpty { s.id }, selected = chinese && s.active) {
                    listener.onQuickCommand(EngineCmd.SelectSchema(s.index))
                },
                LinearLayout.LayoutParams(0, (40 * density).toInt(), 1f).apply {
                    marginEnd = (6 * density).toInt()
                },
            )
        }
    }

    private fun rebuildToggles(context: Context) {
        val grid = toggleGrid ?: return
        val density = context.resources.displayMetrics.density
        grid.removeAllViews()
        val st = status
        grid.addView(
            chip(context, "中文标点", selected = st?.chinesePunct ?: true) {
                listener.onQuickCommand(EngineCmd.TogglePunct)
            },
            cell(density),
        )
        grid.addView(
            chip(context, "全角", selected = st?.fullWidth ?: false) {
                listener.onQuickCommand(EngineCmd.ToggleWidth)
            },
            cell(density),
        )
        // 简繁格仅在用户启用简繁功能后出现（与桌面工具栏同一判据）
        if (st?.s2tShown != false) {
            grid.addView(
                chip(context, "简入繁出", selected = st?.s2tEnabled ?: false) {
                    listener.onQuickCommand(EngineCmd.ToggleS2t)
                },
                cell(density),
            )
        }
    }

    private fun sectionLabel(context: Context, text: String): TextView {
        val density = context.resources.displayMetrics.density
        return TextView(context).apply {
            this.text = text
            textSize = 11f
            setTextColor(0xFF969696.toInt())
            setPadding(0, (4 * density).toInt(), 0, (2 * density).toInt())
        }
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    )

    private fun cell(density: Float) = GridLayout.LayoutParams().apply {
        width = 0
        height = (36 * density).toInt()
        columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
        setMargins(0, 0, (6 * density).toInt(), (6 * density).toInt())
    }

    private fun chip(
        context: Context,
        label: String,
        selected: Boolean = false,
        onClick: () -> Unit,
    ): TextView {
        val density = context.resources.displayMetrics.density
        return TextView(context).apply {
            text = label
            textSize = 13f
            gravity = Gravity.CENTER
            maxLines = 1
            setTextColor(if (selected) Color.WHITE else 0xFF1E1E1E.toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 8 * density
                setColor(if (selected) 0xFF4A78C8.toInt() else Color.WHITE)
            }
            setOnClickListener { onClick() }
        }
    }
}
