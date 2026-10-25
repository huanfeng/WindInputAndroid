package com.windinput.ime.panels

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.windinput.ime.core.RecentStore
import com.windinput.ime.keyboard.GlyphDrawable
import com.windinput.ime.keyboard.InputWindow
import com.windinput.ime.keyboard.KeyHaptics
import com.windinput.ime.keyboard.KeyIcons
import com.windinput.ime.keyboard.KeyboardDimens
import com.windinput.ime.keyboard.Palette
import com.windinput.ime.keyboard.PanelIcon

/**
 * 分类符号页（docs/android-ime-features.md §14.3）：上面 3 行是和键盘同样样式的符号键，
 * 底部一行是 `←` · 横滑分类 tab · `⌫`。
 *
 * 与两家的出入（§14.7 有记录）：
 * - 两家第 3 行两端是 `123` 和 `⌫`。这里符号区要能上下滚（日文、序号一类一页放不下），
 *   固定在滚动区里的两个键不好处理，于是 `⌫` 挪到底行，`←` 直接回进来前的那一层（123 层或数字键盘），
 *   就不再要单独的 `123`。
 * - 点一个符号后回到进来前的那一层（主流做法）；底行的锁定键锁上后改为**停在本页**，
 *   连着打几个符号时不用每次重进三层。锁定态由宿主持久化（[Listener.isSymbolLocked]）。
 *
 * 符号原样上屏，不经引擎（中文模式下点「,」就是半角逗号，这一页本来就是按形态挑的）。
 * 窗口保留条区（[keepsBar]），与键盘同高，进出时整个输入法不跳。
 */
class SymbolWindow(
    private val categoriesProvider: () -> List<SymbolCategory>,
    private val recentStore: RecentStore,
    private val listener: Listener,
) : InputWindow() {

    interface Listener {
        fun onSymbolSelected(symbol: String)
        fun onSymbolBackspace()
        fun onSymbolBack()

        /** 锁定态（点完符号是否停在本页）：由宿主持久化，缺省不锁 */
        fun isSymbolLocked(): Boolean = false
        fun onSymbolLockChanged(locked: Boolean) = Unit

        /** 符号页打开 / 收起：打开时条区的候选行用来放最近用过的符号 */
        fun onSymbolPageShown(shown: Boolean) = Unit
    }

    private companion object {
        const val COLUMNS = 10
        const val GRID_ROWS = 3
        const val COMMON_LABEL = "常用"

        /** 「常用」里最近用过的排在前面，最多这么多个；其余用 [COMMON_DEFAULTS] 补齐 */
        const val RECENT_IN_COMMON = 20

        /**
         * 「常用」的底子：此前这一格只放点过的符号，点过一个再进来就只剩那一个（真机反馈）。
         * 现在常用 = 最近用过的 + 这一组常见符号，永远是满的。
         */
        val COMMON_DEFAULTS = (
            "， 。 ？ ！ 、 ： ； “ ” ‘ ’ （ ） 《 》 【 】 … — ～ · ￥ " +
                "@ # % & * + - = / _ ~ ^ | \\ < > ( ) [ ] { } ' \" ` $"
            ).split(' ').filter { it.isNotEmpty() }

        /** 退格按住连删：起始延迟与间隔，和键盘上的退格手感一致 */
        const val REPEAT_START_MS = 400L
        const val REPEAT_INTERVAL_MS = 50L
    }

    override val keepsBar: Boolean get() = true

    private val categories by lazy(categoriesProvider)
    private var tabsRow: LinearLayout? = null
    private var grid: RecyclerView? = null
    private val adapter = SymbolAdapter { symbol ->
        // 只记不刷：正在看「最近」时不让格子在手指底下挪位（微信也是下次进来才更新）
        recentStore.record(symbol)
        listener.onSymbolSelected(symbol)
        if (!locked) listener.onSymbolBack()
    }
    private var locked = false
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreateView(context: Context): View {
        val density = context.resources.displayMetrics.density
        val palette = Palette.current
        val rowHeight = (KeyboardDimens.rowHeightDp * density).toInt()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(palette.background)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                PanelScaffold.keyboardHeightPx(context),
            )
        }

        grid = RecyclerView(context).apply {
            layoutManager = GridLayoutManager(context, COLUMNS)
            adapter = this@SymbolWindow.adapter
            // 脚本按 content-desc 找到符号区（格子本身是 TextView，文字即符号）
            contentDescription = "symbol-grid"
        }
        root.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowHeight * GRID_ROWS))

        val bottom = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val sideWidth = context.resources.displayMetrics.widthPixels * 15 / 100

        bottom.addView(
            keyCell(context, "返回", palette.accent, palette.onAccent).apply {
                // 矢量返回箭头，与面板左上角的返回同一个图标
                GlyphDrawable.apply(this, PanelIcon.Glyph.BACK, palette.onAccent)
                contentDescription = "symbol-back"
                setOnClickListener {
                    KeyHaptics.tap(it)
                    listener.onSymbolBack()
                }
            },
            LinearLayout.LayoutParams(sideWidth, rowHeight),
        )

        val tabs = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        tabsRow = tabs
        val labels = listOf(COMMON_LABEL) + categories.map { it.name }
        labels.forEachIndexed { index, label ->
            tabs.addView(
                TextView(context).apply {
                    text = label
                    textSize = 16f
                    setTextColor(palette.text)
                    gravity = Gravity.CENTER
                    setPadding((14 * density).toInt(), 0, (14 * density).toInt(), 0)
                    setOnClickListener { selectTab(index) }
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, (rowHeight * 0.7f).toInt()),
            )
        }
        bottom.addView(
            HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                addView(tabs)
            },
            LinearLayout.LayoutParams(0, rowHeight, 1f).apply { gravity = Gravity.CENTER_VERTICAL },
        )

        val lockCell = keyCell(context, "", palette.keySpecial, palette.text)
        lockCell.contentDescription = "symbol-lock"
        fun paintLock() {
            val tint = if (locked) palette.accentText else palette.text
            GlyphDrawable.apply(lockCell, if (locked) PanelIcon.Glyph.LOCK else PanelIcon.Glyph.UNLOCK, tint)
        }
        locked = listener.isSymbolLocked()
        paintLock()
        lockCell.setOnClickListener {
            KeyHaptics.tap(it)
            locked = !locked
            listener.onSymbolLockChanged(locked)
            paintLock()
        }
        bottom.addView(lockCell, LinearLayout.LayoutParams(sideWidth * 2 / 3, rowHeight))

        bottom.addView(
            keyCell(context, "⌫", palette.keySpecial, palette.text).apply {
                contentDescription = "symbol-backspace"
                KeyIcons.applyBackspace(this, palette.text)
                setOnTouchListener(backspaceRepeater())
            },
            LinearLayout.LayoutParams(sideWidth, rowHeight),
        )
        root.addView(bottom, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowHeight))
        return root
    }

    override fun onAttached() {
        selectTab(0)
        listener.onSymbolPageShown(true)
    }

    override fun onDetached() {
        handler.removeCallbacksAndMessages(null)
        listener.onSymbolPageShown(false)
    }

    private fun commonSymbols(): List<String> =
        (recentStore.list().take(RECENT_IN_COMMON) + COMMON_DEFAULTS).distinct()

    private fun selectTab(index: Int) {
        adapter.submit(if (index == 0) commonSymbols() else categories[index - 1].items)
        grid?.scrollToPosition(0)
        val palette = Palette.current
        val density = grid?.resources?.displayMetrics?.density ?: 1f
        tabsRow?.let { tabs ->
            for (i in 0 until tabs.childCount) {
                tabs.getChildAt(i).background = if (i == index) {
                    GradientDrawable().apply {
                        cornerRadius = 16 * density
                        setColor(palette.accentSoft)
                    }
                } else {
                    null
                }
            }
        }
    }

    /** 退格：点一下删一个，按住连删（与键盘上的退格一致） */
    @SuppressLint("ClickableViewAccessibility")
    private fun backspaceRepeater() = object : View.OnTouchListener {
        private val repeat = object : Runnable {
            override fun run() {
                listener.onSymbolBackspace()
                handler.postDelayed(this, REPEAT_INTERVAL_MS)
            }
        }

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    KeyHaptics.tap(v)
                    listener.onSymbolBackspace()
                    handler.postDelayed(repeat, REPEAT_START_MS)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    handler.removeCallbacks(repeat)
                }
            }
            return true
        }
    }

    private class SymbolAdapter(
        private val onClick: (String) -> Unit,
    ) : RecyclerView.Adapter<SymbolAdapter.Holder>() {

        private var items: List<String> = emptyList()

        @Suppress("NotifyDataSetChanged")
        fun submit(newItems: List<String>) {
            items = newItems
            notifyDataSetChanged()
        }

        class Holder(val view: TextView) : RecyclerView.ViewHolder(view)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val density = parent.resources.displayMetrics.density
            val palette = Palette.current
            val tv = keyCell(parent.context, "", palette.key, palette.text).apply {
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    (KeyboardDimens.rowHeightDp * density).toInt(),
                )
            }
            return Holder(tv)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val symbol = items[position]
            holder.view.text = symbol
            holder.view.setOnClickListener {
                KeyHaptics.tap(it)
                onClick(symbol)
            }
        }

        override fun getItemCount() = items.size
    }
}

/**
 * 键帽样式的格子：圆角键面 + 3dp 键缝 + 按下高亮，与 KeyboardView 画出来的键一致
 * （圆角 8dp、键缝 3dp 是同一组数）。
 */
private fun keyCell(context: Context, label: String, face: Int, textColor: Int): TextView {
    val density = context.resources.displayMetrics.density
    val gap = (3 * density).toInt()
    fun face(color: Int) = InsetDrawable(
        GradientDrawable().apply {
            cornerRadius = 8 * density
            setColor(color)
        },
        gap,
    )
    return TextView(context).apply {
        text = label
        textSize = 20f
        gravity = Gravity.CENTER
        setTextColor(textColor)
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), face(Palette.current.keyPressed))
            addState(intArrayOf(), face(face))
        }
    }
}
