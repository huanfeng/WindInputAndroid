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
 * 展开式候选面板：条区的展开钮进入，整个面板区网格铺开全部候选（中文重码刚需）。
 *
 * # 为什么不用 PanelScaffold
 *
 * 它没有标题栏、也没有返回按钮：**选中即原位返回**，收起则再点一次条区的展开钮
 * （那个钮在展开态变成收起态）。此前套 PanelScaffold 会多出「返回 / 候选」一整条，
 * 而展开候选的唯一去向就是选一个词——为它专设一行返回栏纯属浪费高度，
 * 且与上方条区里仍在显示的候选形成重复。
 *
 * 分页加载（对接核心的按页取候选）为后续能力，当前展示会话给出的全量。
 */
class ExpandedCandidatesWindow(private val listener: Listener) : InputWindow() {

    interface Listener {
        fun onExpandedCandidateSelected(index: Int)
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
        // 高度与键盘态一致（条区 + 键盘四行），切换不跳
        grid!!.layoutParams = android.view.ViewGroup.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            PanelScaffold.panelHeightPx(context),
        )
        return grid!!
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
