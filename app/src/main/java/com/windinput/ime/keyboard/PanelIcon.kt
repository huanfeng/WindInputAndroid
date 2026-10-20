package com.windinput.ime.keyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

/**
 * 线性矢量图标（Canvas Path 绘制）。
 *
 * 取代先前的 emoji 文本图标：emoji 由系统字体渲染，**各机型长相不一、颜色写死、
 * 与键盘配色格格不入**，也无法跟随主题变色。Path 绘制的图标粗细统一、可着色，
 * 后续接 wind-theme 调色板时只需换 [tint]。
 *
 * 图标在 24×24 的名义网格里描点，绘制时按实际尺寸等比缩放。
 */
class PanelIcon(context: Context) : View(context) {

    enum class Glyph {
        /** 返回（左箭头） */
        BACK,

        /** 快捷功能（田字格） */
        GRID,

        /** 表情（笑脸） */
        EMOJI,

        /** 剪贴板 */
        CLIPBOARD,

        /** 文本编辑（光标 I 形 + 方向） */
        TEXT_EDIT,

        /** 设置（齿轮简化：圆 + 六向短齿） */
        SETTINGS,

        /** 收起键盘（下箭头） */
        COLLAPSE,

        /** 展开候选（下三角） */
        EXPAND,

        /** 关闭（叉） */
        CLOSE,
    }

    var glyph: Glyph = Glyph.GRID
        set(value) {
            field = value
            invalidate()
        }

    var tint: Int = Palette.current.icon
        set(value) {
            field = value
            invalidate()
        }

    /** 图标边长占控件短边的比例；留白让触摸区大于视觉尺寸 */
    var scale: Float = 0.46f

    private val density = resources.displayMetrics.density

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val path = Path()
    private val oval = RectF()

    private var pressed = false

    init {
        isClickable = true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressed = true
                KeyHaptics.tap(this)
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                pressed = false
                invalidate()
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        val size = minOf(width, height) * scale
        val cx = width / 2f
        val cy = height / 2f
        // 名义网格 24×24 → 实际尺寸
        val u = size / 24f
        stroke.color = tint
        stroke.strokeWidth = 2f * u
        stroke.alpha = if (pressed) 140 else 255
        fill.color = tint
        fill.alpha = stroke.alpha

        path.reset()
        // p(x, y)：把 24×24 网格坐标（原点在中心）换算到画布
        fun px(x: Float) = cx + (x - 12f) * u
        fun py(y: Float) = cy + (y - 12f) * u

        when (glyph) {
            Glyph.BACK -> {
                path.moveTo(px(15f), py(5f))
                path.lineTo(px(8f), py(12f))
                path.lineTo(px(15f), py(19f))
                canvas.drawPath(path, stroke)
            }
            Glyph.COLLAPSE -> {
                path.moveTo(px(5f), py(9f))
                path.lineTo(px(12f), py(16f))
                path.lineTo(px(19f), py(9f))
                canvas.drawPath(path, stroke)
            }
            Glyph.EXPAND -> {
                path.moveTo(px(6f), py(10f))
                path.lineTo(px(12f), py(16f))
                path.lineTo(px(18f), py(10f))
                path.close()
                canvas.drawPath(path, fill)
            }
            Glyph.CLOSE -> {
                path.moveTo(px(6f), py(6f))
                path.lineTo(px(18f), py(18f))
                path.moveTo(px(18f), py(6f))
                path.lineTo(px(6f), py(18f))
                canvas.drawPath(path, stroke)
            }
            Glyph.GRID -> {
                // 2×2 圆点阵：比线框更轻，且小尺寸下不糊
                val r = 2.1f * u
                for (gx in floatArrayOf(8f, 16f)) {
                    for (gy in floatArrayOf(8f, 16f)) {
                        canvas.drawCircle(px(gx), py(gy), r, fill)
                    }
                }
            }
            Glyph.EMOJI -> {
                canvas.drawCircle(cx, cy, 8f * u, stroke)
                canvas.drawCircle(px(9f), py(10f), 1.2f * u, fill)
                canvas.drawCircle(px(15f), py(10f), 1.2f * u, fill)
                oval.set(px(7.5f), py(11f), px(16.5f), py(18f))
                canvas.drawArc(oval, 20f, 140f, false, stroke)
            }
            Glyph.CLIPBOARD -> {
                oval.set(px(6f), py(6f), px(18f), py(20f))
                canvas.drawRoundRect(oval, 2f * u, 2f * u, stroke)
                oval.set(px(9.5f), py(3.5f), px(14.5f), py(7.5f))
                canvas.drawRoundRect(oval, 1.2f * u, 1.2f * u, fill)
            }
            Glyph.TEXT_EDIT -> {
                // I 形光标 + 左右指示，表达「在文本里移动」
                path.moveTo(px(12f), py(6f))
                path.lineTo(px(12f), py(18f))
                path.moveTo(px(10f), py(6f))
                path.lineTo(px(14f), py(6f))
                path.moveTo(px(10f), py(18f))
                path.lineTo(px(14f), py(18f))
                canvas.drawPath(path, stroke)
                path.reset()
                path.moveTo(px(7f), py(9f))
                path.lineTo(px(4f), py(12f))
                path.lineTo(px(7f), py(15f))
                path.moveTo(px(17f), py(9f))
                path.lineTo(px(20f), py(12f))
                path.lineTo(px(17f), py(15f))
                canvas.drawPath(path, stroke)
            }
            Glyph.SETTINGS -> {
                // 内圈 + 外圈 + 短齿。齿必须**短**且贴着外圈：先前齿长到 9.2u、
                // 又没有外圈，整体看着像太阳而不是齿轮。
                canvas.drawCircle(cx, cy, 3.0f * u, stroke)
                canvas.drawCircle(cx, cy, 6.6f * u, stroke)
                for (i in 0 until 8) {
                    val a = Math.toRadians((i * 45).toDouble())
                    val sx = cx + (6.6f * u * Math.cos(a)).toFloat()
                    val sy = cy + (6.6f * u * Math.sin(a)).toFloat()
                    val ex = cx + (8.6f * u * Math.cos(a)).toFloat()
                    val ey = cy + (8.6f * u * Math.sin(a)).toFloat()
                    canvas.drawLine(sx, sy, ex, ey, stroke)
                }
            }
        }
    }
}
