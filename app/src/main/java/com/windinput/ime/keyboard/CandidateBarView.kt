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
 */
class CandidateBarView(context: Context) : View(context) {

    interface Listener {
        fun onCandidateSelected(index: Int)
    }

    var listener: Listener? = null

    private val density = resources.displayMetrics.density
    private val barHeightPx = (44 * density).toInt()
    private val itemPaddingH = 14 * density

    private val bgColor = 0xFFFAFAFC.toInt()
    private val pressedColor = 0xFFE6F0FF.toInt()
    private val separatorColor = 0xFFE8EAF0.toInt()
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1E1E1E.toInt()
        textSize = 19 * density
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

    fun setCandidates(items: List<String>) {
        candidates = items
        pressedIndex = -1
        // 新一轮候选从头看起：保留上一轮的滚动位置会让用户以为候选没更新
        scrollX = 0f
        scroller.forceFinished(true)
        rebuildBounds()
        invalidate()
    }

    private fun rebuildBounds() {
        itemBounds.clear()
        var x = 0f
        for (item in candidates) {
            val w = textPaint.measureText(item) + itemPaddingH * 2
            itemBounds += RectF(x, 0f, x + w, barHeightPx.toFloat())
            x += w
        }
        contentWidth = x
    }

    private fun maxScroll(): Float = (contentWidth - width).coerceAtLeast(0f)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), barHeightPx)
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollX = scroller.currX.toFloat().coerceIn(0f, maxScroll())
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
                if (pressedIndex >= 0) {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
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
                }
                if (dragging) {
                    scrollX = (scrollX + dx).coerceIn(0f, maxScroll())
                    downX = event.x
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                val index = pressedIndex
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
