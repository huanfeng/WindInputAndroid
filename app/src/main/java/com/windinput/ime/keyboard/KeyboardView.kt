package com.windinput.ime.keyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View

/**
 * 软键盘主视图：自定义 View + Canvas 绘制（渲染路线见 docs/architecture.md §3.6）。
 *
 * 占位阶段：内置配色常量；正式版配色来自 wind-theme 求值后的调色板（键盘域节点）。
 */
class KeyboardView(context: Context) : View(context) {

    interface Listener {
        fun onKeyAction(action: KeyAction, shifted: Boolean)
    }

    var listener: Listener? = null

    private var layer = KeyboardLayer.LETTERS
    private val rows get() = KeyboardLayouts.rows(layer)
    private var shifted = false

    // ── 占位配色（后续由主题系统提供） ──────────────────────────────
    private val bgColor = 0xFFF0F2F5.toInt()
    private val keyColor = 0xFFFFFFFF.toInt()
    private val keySpecialColor = 0xFFD9DDE3.toInt()
    private val keyPressedColor = 0xFFBBD0F8.toInt()
    private val textColor = 0xFF1E1E1E.toInt()

    private val density = resources.displayMetrics.density
    private val rowHeightPx = (56 * density).toInt()
    private val keyGapPx = 3 * density
    private val keyRadiusPx = 8 * density

    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor
        textAlign = Paint.Align.CENTER
        textSize = 20 * density
    }

    /** 每次 layout 重算的键位命中区域 */
    private data class KeyBounds(val key: KeyDef, val rect: RectF)

    private val keyBounds = mutableListOf<KeyBounds>()
    private var pressedKey: KeyDef? = null

    private val repeatHandler = Handler(Looper.getMainLooper())
    private var repeating = false
    private val repeatRunnable = object : Runnable {
        override fun run() {
            val key = pressedKey ?: return
            if (key.action == KeyAction.Backspace) {
                listener?.onKeyAction(key.action, shifted)
                repeatHandler.postDelayed(this, 50)
            }
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(width, rowHeightPx * rows.size + paddingTop + paddingBottom)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildBounds(w)
    }

    private fun rebuildBounds(width: Int) {
        keyBounds.clear()
        rows.forEachIndexed { rowIndex, row ->
            val totalWeight = row.sumOf { it.widthWeight.toDouble() }.toFloat()
            val unit = width / totalWeight
            var x = 0f
            val top = (rowIndex * rowHeightPx).toFloat()
            for (key in row) {
                val keyWidth = unit * key.widthWeight
                keyBounds += KeyBounds(
                    key,
                    RectF(
                        x + keyGapPx, top + keyGapPx,
                        x + keyWidth - keyGapPx, top + rowHeightPx - keyGapPx,
                    ),
                )
                x += keyWidth
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(bgColor)
        for ((key, rect) in keyBounds) {
            keyPaint.color = when {
                key === pressedKey -> keyPressedColor
                key.action == KeyAction.Shift && shifted -> keyPressedColor
                key.isSpecial -> keySpecialColor
                else -> keyColor
            }
            canvas.drawRoundRect(rect, keyRadiusPx, keyRadiusPx, keyPaint)
            val label = displayLabel(key)
            val textY = rect.centerY() - (textPaint.ascent() + textPaint.descent()) / 2
            canvas.drawText(label, rect.centerX(), textY, textPaint)
        }
    }

    private fun displayLabel(key: KeyDef): String {
        val action = key.action
        return if (shifted && action is KeyAction.Text && action.char.isLetter()) {
            key.label.uppercase()
        } else {
            key.label
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressedKey = findKey(event.x, event.y)
                if (pressedKey != null) {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                }
                if (pressedKey?.action == KeyAction.Backspace) {
                    repeating = true
                    repeatHandler.postDelayed(repeatRunnable, 400)
                }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                // 滑出键位即取消（占位行为；滑动输入为二期能力）
                if (pressedKey != null && findKey(event.x, event.y) !== pressedKey) {
                    cancelPress()
                }
            }
            MotionEvent.ACTION_UP -> {
                val key = pressedKey
                cancelPress()
                if (key != null) {
                    handleKey(key)
                }
            }
            MotionEvent.ACTION_CANCEL -> cancelPress()
        }
        return true
    }

    private fun cancelPress() {
        pressedKey = null
        repeating = false
        repeatHandler.removeCallbacks(repeatRunnable)
        invalidate()
    }

    private fun findKey(x: Float, y: Float): KeyDef? =
        keyBounds.firstOrNull { it.rect.contains(x, y) }?.key

    private fun handleKey(key: KeyDef) {
        when (key.action) {
            KeyAction.Shift -> {
                shifted = !shifted
                invalidate()
            }
            KeyAction.SwitchSymbols -> {
                layer = when (layer) {
                    KeyboardLayer.LETTERS -> KeyboardLayer.SYMBOLS
                    KeyboardLayer.SYMBOLS -> KeyboardLayer.LETTERS
                }
                shifted = false
                rebuildBounds(width)
                invalidate()
            }
            else -> {
                listener?.onKeyAction(key.action, shifted)
                // 单次 Shift：出字后自动复位（占位行为，长按锁定为后续能力）
                if (shifted && key.action is KeyAction.Text) {
                    shifted = false
                    invalidate()
                }
            }
        }
    }
}
