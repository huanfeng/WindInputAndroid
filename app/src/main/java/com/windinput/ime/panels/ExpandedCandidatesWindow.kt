package com.windinput.ime.panels

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.windinput.ime.core.TextSegment
import com.windinput.ime.keyboard.CandidateText
import com.windinput.ime.keyboard.InputWindow
import com.windinput.ime.keyboard.Palette

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

        /** 长按某个候选：弹候选菜单（与候选栏长按同一个菜单） */
        fun onExpandedCandidateLongPressed(index: Int) = Unit

        /** 滚到接近底部、且核心那边还有更多：请求续取一段 */
        fun onNeedMoreCandidates()
    }

    private companion object {
        const val COLUMNS = 4

        /** 距底部还剩几行就预约续取：留出余量，滚到底之前就到货 */
        const val PREFETCH_ROWS = 3
    }

    private var grid: RecyclerView? = null
    private val adapter = CandidateAdapter(
        onClick = { listener.onExpandedCandidateSelected(it) },
        onLongClick = { listener.onExpandedCandidateLongPressed(it) },
    )

    /** 已请求续取、尚未到货：挡住重复请求 */
    private var loadingMore = false

    /** 会话候选更新入口（附着与否都先存着）：新一轮候选，回到顶部 */
    fun setCandidates(
        items: List<String>,
        comments: List<String> = emptyList(),
        segments: List<List<TextSegment>> = emptyList(),
    ) {
        loadingMore = false
        adapter.submit(items, comments, segments)
        grid?.scrollToPosition(0)
    }

    /** 核心扩容重建后整份替换，**不回到顶部**（用户正往下滑） */
    fun replaceCandidates(
        items: List<String>,
        comments: List<String>,
        segments: List<List<TextSegment>> = emptyList(),
    ) {
        loadingMore = false
        adapter.submit(items, comments, segments)
    }

    /**
     * 续取到货：**只通知新增区间**。
     *
     * 不用 `notifyDataSetChanged`——那会让 RecyclerView 丢弃全部 ViewHolder 重新绑定，
     * 用户正在滑动时表现为一顿，而且滚动位置也保不住。
     */
    fun appendCandidates(
        more: List<String>,
        moreComments: List<String> = emptyList(),
        moreSegments: List<List<TextSegment>> = emptyList(),
    ) {
        loadingMore = false
        if (more.isEmpty()) return
        adapter.append(more, moreComments, moreSegments)
    }

    override fun onCreateView(context: Context): View {
        val lm = GridLayoutManager(context, COLUMNS)
        grid = RecyclerView(context).apply {
            layoutManager = lm
            adapter = this@ExpandedCandidatesWindow.adapter
            setBackgroundColor(Palette.current.surface)
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
        private val onLongClick: (Int) -> Unit,
    ) : RecyclerView.Adapter<CandidateAdapter.Holder>() {

        private var items: List<String> = emptyList()

        /** 与 [items] 按下标对应的编码提示灰字；可以比它短（没开提示时为空） */
        private var comments: List<String> = emptyList()

        /** 与 [items] 按下标对应的分段正文（模糊音提示）；可以比它短，缺的那条按原文显示 */
        private var segments: List<List<TextSegment>> = emptyList()

        @Suppress("NotifyDataSetChanged")
        fun submit(newItems: List<String>, newComments: List<String>, newSegments: List<List<TextSegment>>) {
            items = newItems
            comments = newComments
            segments = newSegments
            notifyDataSetChanged()
        }

        /** 追加：只通知新增区间，不动已绑定的 ViewHolder */
        fun append(more: List<String>, moreComments: List<String>, moreSegments: List<List<TextSegment>>) {
            val from = items.size
            if (moreComments.isNotEmpty()) {
                comments = com.windinput.ime.keyboard.CandidateBarView.alignComments(comments, from) + moreComments
            }
            if (moreSegments.isNotEmpty()) {
                segments = CandidateText.align(segments, from, emptyList()) + moreSegments
            }
            items = items + more
            notifyItemRangeInserted(from, more.size)
        }

        class Holder(val view: TextView) : RecyclerView.ViewHolder(view)

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): Holder {
            val density = parent.resources.displayMetrics.density
            val tv = TextView(parent.context).apply {
                textSize = 18f
                gravity = Gravity.CENTER
                setTextColor(Palette.current.text)
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    (46 * density).toInt(),
                )
            }
            return Holder(tv)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val comment = comments.getOrNull(position).orEmpty()
            val segs = segments.getOrNull(position)
            holder.view.text = if (comment.isEmpty() && segs.isNullOrEmpty()) {
                items[position]
            } else {
                android.text.SpannableStringBuilder().apply {
                    if (segs.isNullOrEmpty()) {
                        append(items[position])
                    } else {
                        // 提示段（模糊音的正确拼音）与正文同字号，只换颜色
                        for (s in segs) {
                            val start = length
                            append(s.text)
                            if (CandidateText.isHint(s)) {
                                setSpan(android.text.style.ForegroundColorSpan(Palette.current.hint), start, length, 0)
                            }
                        }
                    }
                    if (comment.isNotEmpty()) {
                        // 与候选栏同一种排法：字后跟小一号灰字（码表剩余编码 / 拼音反查码）
                        append(' ')
                        val start = length
                        append(comment)
                        setSpan(android.text.style.RelativeSizeSpan(0.6f), start, length, 0)
                        setSpan(android.text.style.ForegroundColorSpan(Palette.current.hint), start, length, 0)
                    }
                }
            }
            holder.view.setOnClickListener { onClick(position) }
            holder.view.setOnLongClickListener {
                onLongClick(position)
                true
            }
        }

        override fun getItemCount() = items.size
    }
}
