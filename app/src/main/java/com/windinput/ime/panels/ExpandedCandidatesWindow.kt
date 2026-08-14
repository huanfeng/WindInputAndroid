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
 * 它没有自己的标题栏与返回按钮：**选中即原位返回**，或点条区尾格返回。为它专设一行
 * 返回栏是浪费——那一行的位置正被条区占着，而条区在展开态已经把尾格让给了「返回」。
 *
 * 这也是本窗口 [keepsBar] 为 true 的原因：条区是它的返回入口，不能让位。
 * 相应地，视图高度只取**键盘四行**而非整体面板高，否则条区没让位、这里又按整体高
 * 铺开，IME 窗口会被顶高一条。
 *
 * 分页加载（对接核心的按页取候选）为后续能力，当前展示会话给出的全量。
 */
class ExpandedCandidatesWindow(private val listener: Listener) : InputWindow() {

    override val keepsBar: Boolean get() = true

    interface Listener {
        fun onExpandedCandidateSelected(index: Int)

        /** 滚到接近底部、且核心那边还有更多：请求续取一段 */
        fun onNeedMoreCandidates()
    }

    private companion object {
        const val COLUMNS = 4

        /** 距底部还剩几行就预约续取：留出余量，滚到底之前就到货 */
        const val PREFETCH_ROWS = 3
    }

    private var grid: RecyclerView? = null
    private val adapter = CandidateAdapter { listener.onExpandedCandidateSelected(it) }

    /** 已请求续取、尚未到货：挡住重复请求 */
    private var loadingMore = false

    /** 会话候选更新入口（附着与否都先存着）：新一轮候选，回到顶部 */
    fun setCandidates(items: List<String>) {
        loadingMore = false
        adapter.submit(items)
        grid?.scrollToPosition(0)
    }

    /**
     * 续取到货：**只通知新增区间**。
     *
     * 不用 `notifyDataSetChanged`——那会让 RecyclerView 丢弃全部 ViewHolder 重新绑定，
     * 用户正在滑动时表现为一顿，而且滚动位置也保不住。
     */
    fun appendCandidates(more: List<String>) {
        loadingMore = false
        if (more.isEmpty()) return
        adapter.append(more)
    }

    override fun onCreateView(context: Context): View {
        val lm = GridLayoutManager(context, COLUMNS)
        grid = RecyclerView(context).apply {
            layoutManager = lm
            adapter = this@ExpandedCandidatesWindow.adapter
            setBackgroundColor(Color.WHITE)
            addOnScrollListener(
                object : RecyclerView.OnScrollListener() {
                    override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                        if (loadingMore || dy <= 0) return
                        val last = lm.findLastVisibleItemPosition()
                        val threshold =
                            this@ExpandedCandidatesWindow.adapter.itemCount -
                                PREFETCH_ROWS * COLUMNS
                        if (last >= threshold) {
                            loadingMore = true
                            listener.onNeedMoreCandidates()
                        }
                    }
                },
            )
        }
        // 条区留着（keepsBar），故只占键盘四行的高度，前后总高不变、键盘不跳
        grid!!.layoutParams = android.view.ViewGroup.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            PanelScaffold.keyboardHeightPx(context),
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

        /** 追加：只通知新增区间，不动已绑定的 ViewHolder */
        fun append(more: List<String>) {
            val from = items.size
            items = items + more
            notifyItemRangeInserted(from, more.size)
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
