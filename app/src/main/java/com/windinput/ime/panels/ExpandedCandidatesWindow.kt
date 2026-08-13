package com.windinput.ime.panels

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.windinput.ime.keyboard.InputWindow

/**
 * 展开式候选面板：候选条 ∨ 按钮进入，全面板区网格展示全部候选（中文重码刚需）。
 * 分页加载（对接 Rust 核心的按页取候选）为后续能力，当前展示会话给出的全量。
 */
class ExpandedCandidatesWindow(private val listener: Listener) : InputWindow() {

    interface Listener {
        fun onExpandedCandidateSelected(index: Int)
        fun onBackToKeyboard()
    }

    private companion object {
        const val COLUMNS = 4
    }

    private var grid: RecyclerView? = null
    private val adapter = CandidateAdapter { listener.onExpandedCandidateSelected(it) }

    /** 会话候选更新入口（附着与否都先存着） */
    fun setCandidates(items: List<String>) {
        adapter.submit(items)
        grid?.scrollToPosition(0)
    }

    override fun onCreateView(context: Context): View {
        grid = RecyclerView(context).apply {
            layoutManager = GridLayoutManager(context, COLUMNS)
            adapter = this@ExpandedCandidatesWindow.adapter
            setBackgroundColor(Color.WHITE)
        }
        return PanelScaffold.build(
            context = context,
            title = "候选",
            onBack = listener::onBackToKeyboard,
            content = grid!!,
        )
    }

    private class CandidateAdapter(
        private val onClick: (Int) -> Unit,
    ) : RecyclerView.Adapter<CandidateAdapter.Holder>() {

        private var items: List<String> = emptyList()

        @Suppress("NotifyDataSetChanged")
        fun submit(newItems: List<String>) {
            items = newItems
            notifyDataSetChanged()
        }

        class Holder(val view: TextView) : RecyclerView.ViewHolder(view)

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): Holder {
            val density = parent.resources.displayMetrics.density
            val tv = TextView(parent.context).apply {
                textSize = 18f
                gravity = Gravity.CENTER
                setTextColor(0xFF1E1E1E.toInt())
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    (46 * density).toInt(),
                )
            }
            return Holder(tv)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.view.text = items[position]
            holder.view.setOnClickListener { onClick(position) }
        }

        override fun getItemCount() = items.size
    }
}
