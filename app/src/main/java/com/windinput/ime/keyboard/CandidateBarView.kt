package com.windinput.ime.keyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View

/**
 * 候选条占位视图：展示预编辑串与候选词。
 *
 * 正式版数据来自 Rust 核心的 UiCommand 推送（候选列表/预编辑/高亮页）；
 * 当前占位阶段仅显示预编辑文本，无候选。
 */
class CandidateBarView(context: Context) : View(context) {

    private val density = resources.displayMetrics.density
    private val barHeightPx = (42 * density).toInt()

    private val bgColor = 0xFFFAFAFC.toInt()
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1E1E1E.toInt()
        textSize = 18 * density
    }

    private var preedit: String = ""

    fun setPreedit(text: String) {
        preedit = text
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), barHeightPx)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(bgColor)
        if (preedit.isNotEmpty()) {
            val y = height / 2f - (textPaint.ascent() + textPaint.descent()) / 2
            canvas.drawText(preedit, 12 * density, y, textPaint)
        }
    }
}
