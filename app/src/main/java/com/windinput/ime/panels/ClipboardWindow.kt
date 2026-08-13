package com.windinput.ime.panels

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.windinput.ime.core.ClipboardStore
import com.windinput.ime.keyboard.InputWindow

/**
 * 剪贴板历史面板：点击粘贴、置顶、删除、清空（跳过置顶）。
 * 列表条数上限 50，普通 ScrollView 足够；样式为占位形态，正式版接主题。
 */
class ClipboardWindow(
    private val store: ClipboardStore,
    private val listener: Listener,
) : InputWindow() {

    interface Listener {
        fun onPasteText(text: String)
        fun onBackToKeyboard()
    }

    private var listContainer: LinearLayout? = null

    override fun onCreateView(context: Context): View {
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        listContainer = list
        val scroll = ScrollView(context).apply { addView(list) }
        return PanelScaffold.build(
            context = context,
            title = "剪贴板",
            onBack = listener::onBackToKeyboard,
            content = scroll,
            rightActions = listOf("清空" to { store.clearUnpinned() }),
        )
    }

    override fun onAttached() {
        store.onChanged = { listContainer?.post { refresh() } }
        refresh()
    }

    override fun onDetached() {
        store.onChanged = null
    }

    private fun refresh() {
        val list = listContainer ?: return
        val context = list.context
        val density = context.resources.displayMetrics.density
        list.removeAllViews()

        val entries = store.list()
        if (entries.isEmpty()) {
            list.addView(
                TextView(context).apply {
                    text = "复制的内容会出现在这里"
                    gravity = Gravity.CENTER
                    setTextColor(0xFF969696.toInt())
                    setPadding(0, (48 * density).toInt(), 0, 0)
                },
            )
            return
        }

        for (entry in entries) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundColor(Color.WHITE)
            }
            row.addView(
                TextView(context).apply {
                    text = entry.text
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setTextColor(0xFF1E1E1E.toInt())
                    val p = (10 * density).toInt()
                    setPadding(p, p, p, p)
                    setOnClickListener { listener.onPasteText(entry.text) }
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
            row.addView(
                actionButton(context, if (entry.pinned) "📌" else "📍") {
                    store.setPinned(entry.text, !entry.pinned)
                },
            )
            row.addView(actionButton(context, "✕") { store.remove(entry.text) })

            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            val m = (3 * density).toInt()
            lp.setMargins(m, m, m, 0)
            list.addView(row, lp)
        }
    }

    private fun actionButton(context: Context, label: String, onClick: () -> Unit): TextView =
        TextView(context).apply {
            val density = context.resources.displayMetrics.density
            text = label
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding((10 * density).toInt(), 0, (10 * density).toInt(), 0)
            setOnClickListener { onClick() }
        }
}
