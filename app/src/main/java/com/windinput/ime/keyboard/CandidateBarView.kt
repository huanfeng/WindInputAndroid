package com.windinput.ime.keyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View

/**
 * 候选条：横向展示候选词，点选上屏。
 *
 * 数据来源是会话层 UiEvent（Rust 核心接入后即 UiCommand 投影）。
 * 占位阶段配色内置；正式版来自 wind-theme 调色板。
 * 溢出候选的展开面板（下拉全览）为后续能力。
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
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1E1E1E.toInt()
        textSize = 19 * density
    }

    private var candidates: List<String> = emptyList()

    /** 每个候选的命中区域（随 setCandidates 重算） */
    private val itemBounds = mutableListOf<RectF>()
    private var pressedIndex = -1

    fun setCandidates(items: List<String>) {
        candidates = items
        pressedIndex = -1
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
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), barHeightPx)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(bgColor)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        candidates.forEachIndexed { i, item ->
            val rect = itemBounds.getOrNull(i) ?: return@forEachIndexed
            if (rect.left > width) return@forEachIndexed
            if (i == pressedIndex) {
                paint.color = pressedColor
                canvas.drawRect(rect, paint)
            }
            val y = rect.centerY() - (textPaint.ascent() + textPaint.descent()) / 2
            canvas.drawText(item, rect.left + itemPaddingH, y, textPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressedIndex = findIndex(event.x, event.y)
                if (pressedIndex >= 0) {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                }
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                val index = pressedIndex
                pressedIndex = -1
                invalidate()
                if (index >= 0 && findIndex(event.x, event.y) == index) {
                    listener?.onCandidateSelected(index)
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                pressedIndex = -1
                invalidate()
            }
        }
        return true
    }

    private fun findIndex(x: Float, y: Float): Int =
        itemBounds.indexOfFirst { it.contains(x, y) }
}
