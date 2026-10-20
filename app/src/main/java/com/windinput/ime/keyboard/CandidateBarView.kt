package com.windinput.ime.keyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller

/**
 * 候选条：横向展示候选词，**可拖动滚动**，点选上屏。
 *
 * # 滚动为什么必须有
 *
 * 中文重码多，一屏放不下的候选此前是**直接不画**（`if (rect.left > width) return`）——
 * 用户既看不到也够不着，只能去开展开面板。现在支持拖动 + 惯性滑行。
 *
 * # 拖动与点选的区分
 *
 * 两者共用同一片触摸区，判据是**位移是否超过 touchSlop**：超过即进入滚动、
 * 本次抬手不选词；未超过才算点选。没有这条，轻微手抖会变成误选词。
 *
 * # 为什么分「设置」与「追加」两个入口
 *
 * 候选全量可达上千条，宿主按窗口分批取（见 `ImeSession.candidateWindow`）。
 * 若续取时也走 [setCandidates]，就得把已经量过的那几百条**重新 measureText 一遍**
 * ——那是主线程上的实打实开销，而且发生在用户正在滑动的时候，最容易掉帧。
 * [appendCandidates] 只量新来的那一段。
 */
class CandidateBarView(context: Context) : View(context) {

    interface Listener {
        fun onCandidateSelected(index: Int)

        /** 长按某个候选：弹候选菜单（置顶 / 删除 …） */
        fun onCandidateLongPressed(index: Int) = Unit
    }

    /**
     * 长按计时：按下即排，拖动 / 抬手 / 取消即撤。触发后本次抬手不再算点选——
     * 否则用户长按出菜单、松手的同时就把这个词上屏了。
     */
    private var longPressFired = false
    private val longPress = Runnable {
        val index = pressedIndex
        if (index >= 0 && !dragging) {
            longPressFired = true
            pressedIndex = -1
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            invalidate()
            listener?.onCandidateLongPressed(index)
        }
    }

    var listener: Listener? = null

    private val density = resources.displayMetrics.density
    private val barHeightPx = (44 * density).toInt()
    private val itemPaddingH = 14 * density

    private val bgColor = Palette.current.surface
    private val pressedColor = Palette.current.accentSoft
    private val separatorColor = Palette.current.separator
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.current.text
        textSize = 19 * density
    }

    /** 候选字号（sp，设置项 ui.android.candidate_text_sp）；改后全部重量 */
    var textSizeSp = 19f
        set(value) {
            if (field == value) return
            field = value
            textPaint.textSize = value * density
            itemBounds.clear()
            contentWidth = 0f
            measureFrom(0)
            invalidate()
        }
    private val helperPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var candidates: List<String> = emptyList()

    /** 每个候选的命中区域（内容坐标系，未减滚动量） */
    private val itemBounds = mutableListOf<RectF>()
    private var pressedIndex = -1

    /** 内容总宽（决定可滚动范围） */
    private var contentWidth = 0f

    private var scrollX = 0f
    private val scroller = OverScroller(context)
    private var velocityTracker: VelocityTracker? = null

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val minFlingVelocity = ViewConfiguration.get(context).scaledMinimumFlingVelocity
    private val maxFlingVelocity = ViewConfiguration.get(context).scaledMaximumFlingVelocity

    private var downX = 0f
    private var dragging = false

    /**
     * 还有未加载的候选时，滚到接近尾部会回调它（宿主据此续取）。
     *
     * 由宿主置为 null 表示"取完了"，本视图不自己记总数——总数的真值在核心那边，
     * 视图另存一份必然漂移。
     */
    var onNeedMore: (() -> Unit)? = null

    /** 已请求续取、尚未到货：挡住重复请求（一次滑动会产生几十个 MOVE 事件） */
    private var loadingMore = false

    /** 新一轮候选（替换全部） */
    fun setCandidates(items: List<String>) {
        candidates = items
        pressedIndex = -1
        loadingMore = false
        // 新一轮候选从头看起：保留上一轮的滚动位置会让用户以为候选没更新
        scrollX = 0f
        scroller.forceFinished(true)
        itemBounds.clear()
        contentWidth = 0f
        measureFrom(0)
        invalidate()
    }

    /** 续取到货：**只量新来的这一段**，已有的量过就不再动 */
    fun appendCandidates(more: List<String>) {
        loadingMore = false
        if (more.isEmpty()) return
        val from = candidates.size
        candidates = candidates + more
        measureFrom(from)
        invalidate()
    }

    /** 从第 [from] 项起量文字宽度并累加命中区域（内容坐标系） */
    private fun measureFrom(from: Int) {
        var x = if (from == 0) 0f else contentWidth
        for (i in from until candidates.size) {
            val w = textPaint.measureText(candidates[i]) + itemPaddingH * 2
            itemBounds += RectF(x, 0f, x + w, barHeightPx.toFloat())
            x += w
        }
        contentWidth = x
    }

    /** 视口右缘距内容尾部不足一屏时续取：留一屏的余量，滑到尽头前就到货 */
    private fun maybeRequestMore() {
        val more = onNeedMore ?: return
        if (loadingMore) return
        if (scrollX < maxScroll() - width) return
        loadingMore = true
        more()
    }

    private fun maxScroll(): Float = (contentWidth - width).coerceAtLeast(0f)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), barHeightPx)
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollX = scroller.currX.toFloat().coerceIn(0f, maxScroll())
            // 惯性滑行同样要能触发续取：甩一下就到尾部是最常见的用法
            maybeRequestMore()
            postInvalidateOnAnimation()
        }
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(bgColor)
        canvas.save()
        canvas.translate(-scrollX, 0f)
        candidates.forEachIndexed { i, item ->
            val rect = itemBounds.getOrNull(i) ?: return@forEachIndexed
            // 视口裁剪：只画看得见的
            if (rect.right < scrollX || rect.left > scrollX + width) return@forEachIndexed
            if (i == pressedIndex) {
                helperPaint.color = pressedColor
                canvas.drawRect(rect, helperPaint)
            }
            if (i > 0) {
                helperPaint.color = separatorColor
                canvas.drawLine(
                    rect.left,
                    barHeightPx * 0.28f,
                    rect.left,
                    barHeightPx * 0.72f,
                    helperPaint,
                )
            }
            val y = rect.centerY() - (textPaint.ascent() + textPaint.descent()) / 2
            canvas.drawText(item, rect.left + itemPaddingH, y, textPaint)
        }
        canvas.restore()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val tracker = velocityTracker ?: VelocityTracker.obtain().also { velocityTracker = it }
        tracker.addMovement(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scroller.forceFinished(true)
                downX = event.x
                dragging = false
                pressedIndex = findIndex(event.x)
                longPressFired = false
                if (pressedIndex >= 0) {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                }
                // 候选条在可滚动时要拦住父容器的横向手势，否则拖动会被上层吃掉
                parent?.requestDisallowInterceptTouchEvent(maxScroll() > 0)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = downX - event.x
                if (!dragging && kotlin.math.abs(dx) > touchSlop) {
                    dragging = true
                    // 一旦判定为拖动就取消按下态：本次抬手不再算点选
                    pressedIndex = -1
                    removeCallbacks(longPress)
                }
                if (dragging) {
                    scrollX = (scrollX + dx).coerceIn(0f, maxScroll())
                    downX = event.x
                    maybeRequestMore()
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                val index = if (longPressFired) -1 else pressedIndex
                pressedIndex = -1
                if (dragging) {
                    tracker.computeCurrentVelocity(1000, maxFlingVelocity.toFloat())
                    val vx = tracker.xVelocity
                    if (kotlin.math.abs(vx) > minFlingVelocity) {
                        scroller.fling(
                            scrollX.toInt(), 0,
                            -vx.toInt(), 0,
                            0, maxScroll().toInt(),
                            0, 0,
                        )
                        postInvalidateOnAnimation()
                    }
                } else if (index >= 0 && findIndex(event.x) == index) {
                    listener?.onCandidateSelected(index)
                }
                releaseTracker()
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                pressedIndex = -1
                releaseTracker()
                invalidate()
            }
        }
        return true
    }

    private fun releaseTracker() {
        velocityTracker?.recycle()
        velocityTracker = null
        dragging = false
    }

    /** 视口 x → 候选下标（内部换算滚动量） */
    private fun findIndex(viewX: Float): Int {
        val contentX = viewX + scrollX
        return itemBounds.indexOfFirst { contentX >= it.left && contentX < it.right }
    }
}
