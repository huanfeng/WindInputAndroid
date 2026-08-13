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
 * 手势体系（对齐 fcitx5 KeyDef.Behavior 声明式模型）：
 * 点按 / 长按气泡（横移选择）/ 长按重复（退格）/ 上滑副字符 / 按键预览。
 * 占位阶段：内置配色常量；正式版配色来自 wind-theme 求值后的调色板（键盘域节点）。
 */
class KeyboardView(context: Context) : View(context) {

    interface Listener {
        fun onKeyAction(action: KeyAction, shifted: Boolean)
    }

    var listener: Listener? = null

    /** 布局来源，缺省内置；Service 注入 LayoutRepository 后走 TOML + 覆盖解析 */
    var layoutProvider: (KeyboardLayer) -> List<List<KeyDef>> = KeyboardLayouts::rows
        set(value) {
            field = value
            reloadLayout()
        }

    /** 按键触觉反馈开关（设置项 ui.android.haptic） */
    var hapticEnabled = true

    /** 按键预览气泡开关（设置项 ui.android.key_preview） */
    var previewEnabled = false

    /** 回车键动作标签（imeOptions 驱动：发送/搜索/下一项…），null = 缺省 label */
    var enterLabel: String? = null
        set(value) {
            field = value
            invalidate()
        }

    private var layer = KeyboardLayer.LETTERS
    private var rows = layoutProvider(layer)
    private var shifted = false

    // ── 占位配色（后续由主题系统提供） ──────────────────────────────
    private val bgColor = 0xFFF0F2F5.toInt()
    private val keyColor = 0xFFFFFFFF.toInt()
    private val keySpecialColor = 0xFFD9DDE3.toInt()
    private val keyPressedColor = 0xFFBBD0F8.toInt()
    private val textColor = 0xFF1E1E1E.toInt()
    private val hintColor = 0xFF969696.toInt()

    private val density = resources.displayMetrics.density
    private val rowHeightPx = (56 * density).toInt()
    private val keyGapPx = 3 * density
    private val keyRadiusPx = 8 * density
    private val swipeThresholdPx = 40 * density

    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor
        textAlign = Paint.Align.CENTER
        textSize = 20 * density
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = hintColor
        textAlign = Paint.Align.RIGHT
        textSize = 11 * density
    }

    /** 每次 layout 重算的键位命中区域 */
    private data class KeyBounds(val key: KeyDef, val rect: RectF)

    private val keyBounds = mutableListOf<KeyBounds>()
    private var pressedKey: KeyDef? = null
    private var pressedRect: RectF? = null

    private val popupManager by lazy { KeyPopupManager(this) }

    // 手势状态
    private var downX = 0f
    private var downY = 0f

    /** 上滑副字符已触发（本次按压后续事件忽略） */
    private var swipeConsumed = false

    private val handler = Handler(Looper.getMainLooper())

    private var repeating = false

    /** 本次按压中连删已至少触发一次（抬手时不再补发单击） */
    private var repeatFired = false

    private val repeatRunnable = object : Runnable {
        override fun run() {
            val key = pressedKey ?: return
            if (key.action == KeyAction.Backspace) {
                repeatFired = true
                listener?.onKeyAction(key.action, shifted)
                repeatHaptic()
                handler.postDelayed(this, 50)
            }
        }
    }

    private val longPressRunnable = Runnable {
        val key = pressedKey ?: return@Runnable
        val rect = pressedRect ?: return@Runnable
        if (key.longPress.isNotEmpty()) {
            popupManager.dismissPreview()
            popupManager.showPopup(rect, key.longPress.map { it.toString() })
            repeatHaptic()
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

    override fun onDetachedFromWindow() {
        popupManager.dismissAll()
        super.onDetachedFromWindow()
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
            // 右上角副字符角标
            key.swipeUp?.let { hint ->
                canvas.drawText(
                    hint.toString(),
                    rect.right - 5 * density,
                    rect.top + 13 * density,
                    hintPaint,
                )
            }
        }
    }

    private fun displayLabel(key: KeyDef): String {
        if (key.action == KeyAction.Enter && enterLabel != null) {
            return enterLabel!!
        }
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
                downX = event.x
                downY = event.y
                swipeConsumed = false
                repeatFired = false
                val bounds = findBounds(event.x, event.y)
                pressedKey = bounds?.key
                pressedRect = bounds?.rect
                val key = bounds?.key
                if (key != null) {
                    if (hapticEnabled) {
                        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    }
                    if (previewEnabled && !key.isSpecial && key.action is KeyAction.Text) {
                        popupManager.showPreview(bounds.rect, displayLabel(key))
                    }
                    if (key.action == KeyAction.Backspace) {
                        repeating = true
                        handler.postDelayed(repeatRunnable, 400)
                    }
                    if (key.longPress.isNotEmpty()) {
                        handler.postDelayed(longPressRunnable, 300)
                    }
                }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                val key = pressedKey ?: return true
                if (swipeConsumed) return true
                if (popupManager.isPopupShown) {
                    popupManager.updatePopupSelection(event.x)
                    return true
                }
                // 上滑副字符：位移超阈值且以纵向为主
                val dy = downY - event.y
                val dx = event.x - downX
                if (key.swipeUp != null && dy > swipeThresholdPx && dy > kotlin.math.abs(dx)) {
                    swipeConsumed = true
                    cancelTimers()
                    popupManager.dismissAll()
                    listener?.onKeyAction(KeyAction.Text(key.swipeUp), shifted = false)
                    clearPressed()
                    return true
                }
                // 滑出键位即取消（滑动输入为二期能力）
                if (findKey(event.x, event.y) !== key && dy < swipeThresholdPx / 2) {
                    cancelPress()
                }
            }
            MotionEvent.ACTION_UP -> {
                val key = pressedKey
                val popupChoice =
                    if (popupManager.isPopupShown) popupManager.popupSelected() else null
                val skipTap = repeatFired // 连删已触发过则抬手不再补发单击
                cancelPress()
                when {
                    swipeConsumed -> Unit
                    popupChoice != null ->
                        listener?.onKeyAction(KeyAction.Text(popupChoice.first()), shifted = false)
                    key != null && !skipTap -> handleKey(key)
                }
            }
            MotionEvent.ACTION_CANCEL -> cancelPress()
        }
        return true
    }

    private fun repeatHaptic() {
        if (hapticEnabled) {
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
    }

    private fun cancelTimers() {
        repeating = false
        handler.removeCallbacks(repeatRunnable)
        handler.removeCallbacks(longPressRunnable)
    }

    private fun clearPressed() {
        pressedKey = null
        pressedRect = null
        invalidate()
    }

    private fun cancelPress() {
        cancelTimers()
        popupManager.dismissAll()
        clearPressed()
    }

    private fun findBounds(x: Float, y: Float): KeyBounds? =
        keyBounds.firstOrNull { it.rect.contains(x, y) }

    private fun findKey(x: Float, y: Float): KeyDef? = findBounds(x, y)?.key

    /** 切换布局层（EditorInfo 场景感知/符号键共用入口） */
    fun setLayer(newLayer: KeyboardLayer) {
        if (layer == newLayer) return
        layer = newLayer
        shifted = false
        reloadLayout()
    }

    private fun reloadLayout() {
        rows = layoutProvider(layer)
        requestLayout()
        if (width > 0) {
            rebuildBounds(width)
        }
        invalidate()
    }

    private fun handleKey(key: KeyDef) {
        when (key.action) {
            KeyAction.Shift -> {
                shifted = !shifted
                invalidate()
            }
            KeyAction.SwitchSymbols -> {
                setLayer(
                    when (layer) {
                        KeyboardLayer.LETTERS -> KeyboardLayer.SYMBOLS
                        KeyboardLayer.SYMBOLS -> KeyboardLayer.LETTERS
                    },
                )
            }
            else -> {
                listener?.onKeyAction(key.action, shifted)
                // 单次 Shift：出字后自动复位（长按锁定为后续能力）
                if (shifted && key.action is KeyAction.Text) {
                    shifted = false
                    invalidate()
                }
            }
        }
    }
}
