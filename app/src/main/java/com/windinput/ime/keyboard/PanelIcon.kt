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

        /** 无痕（礼帽 + 眼镜） */
        INCOGNITO,

        /** 语音（话筒） */
        MIC,

        /** 切换输入法（地球） */
        GLOBE,

        /** 单手（手机 + 侧向箭头） */
        ONE_HAND,

        /** 置顶（实心图钉） */
        PIN,

        /** 未置顶（空心图钉） */
        PIN_OUTLINE,

        /** 最近（钟） */
        CLOCK,

        /** 方向键 */
        ARROW_UP,
        ARROW_DOWN,
        ARROW_LEFT,
        ARROW_RIGHT,

        /** 锁定（挂锁，锁梁合上） */
        LOCK,

        /** 未锁定（挂锁，锁梁打开） */
        UNLOCK,
    }

    /**
     * 只作装饰（放在可点的格子里，点击归外层）：不拦触摸、不单独报读屏节点——
     * 外层格子自己有文字标签，图标再念一遍是重复。
     */
    var decorative: Boolean = false
        set(value) {
            field = value
            isClickable = !value
            importantForAccessibility =
                if (value) IMPORTANT_FOR_ACCESSIBILITY_NO else IMPORTANT_FOR_ACCESSIBILITY_AUTO
        }

    var glyph: Glyph = Glyph.GRID
        set(value) {
            field = value
            // 画出来的图标对读屏是空白：按图标给一个说法（调用方另设过 contentDescription 的不覆盖）
            if (contentDescription == null || contentDescription in A11Y_LABELS.values) {
                contentDescription = A11Y_LABELS[value]
            }
            invalidate()
        }

    init {
        // 默认图标走的是属性初值、不经 setter，这里补一次
        contentDescription = A11Y_LABELS[glyph]
    }

    var tint: Int = Palette.current.icon
        set(value) {
            field = value
            invalidate()
        }

    /** 图标边长占控件短边的比例；留白让触摸区大于视觉尺寸 */
    var scale: Float = 0.46f

    private val density = resources.displayMetrics.density


    private var pressed = false

    init {
        isClickable = true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (decorative) return false
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

    private val painter = GlyphPainter()

    override fun onDraw(canvas: Canvas) {
        val size = minOf(width, height) * scale
        painter.draw(canvas, glyph, width / 2f, height / 2f, size, tint, if (pressed) 140 else 255)
    }

    companion object {
        fun labelOf(glyph: Glyph): String? = A11Y_LABELS[glyph]

        /** 读屏念的说法（也让 uiautomator 能按描述找到它们） */
        private val A11Y_LABELS = mapOf(
            Glyph.BACK to "返回",
            Glyph.GRID to "快捷功能",
            Glyph.EMOJI to "表情",
            Glyph.CLIPBOARD to "剪贴板",
            Glyph.TEXT_EDIT to "文本编辑",
            Glyph.SETTINGS to "设置",
            Glyph.COLLAPSE to "收起键盘",
            Glyph.EXPAND to "展开候选",
            Glyph.CLOSE to "关闭",
            Glyph.INCOGNITO to "无痕",
            Glyph.MIC to "语音输入",
            Glyph.GLOBE to "切换输入法",
            Glyph.ONE_HAND to "单手",
            Glyph.PIN to "取消置顶",
            Glyph.PIN_OUTLINE to "置顶",
            Glyph.CLOCK to "最近",
            Glyph.ARROW_UP to "上",
            Glyph.ARROW_DOWN to "下",
            Glyph.ARROW_LEFT to "左",
            Glyph.ARROW_RIGHT to "右",
        )
    }
}

/**
 * [PanelIcon] 各图标的画法，单独拿出来：面板里的文字按钮（方向键、表情分类的「最近」）
 * 也要画同一套图标（[GlyphDrawable]），不能各画各的。
 */
class GlyphPainter {
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val path = Path()
    private val oval = RectF()

    /** 以 ([cx], [cy]) 为中心、边长 [size] 画 [glyph] */
    fun draw(canvas: Canvas, glyph: PanelIcon.Glyph, cx: Float, cy: Float, size: Float, tint: Int, alpha: Int = 255) {
        // 名义网格 24×24 → 实际尺寸
        val u = size / 24f
        stroke.color = tint
        stroke.strokeWidth = 2f * u
        stroke.alpha = alpha
        fill.color = tint
        fill.alpha = alpha

        path.reset()
        // p(x, y)：把 24×24 网格坐标（原点在中心）换算到画布
        fun px(x: Float) = cx + (x - 12f) * u
        fun py(y: Float) = cy + (y - 12f) * u

        when (glyph) {
            PanelIcon.Glyph.BACK -> {
                path.moveTo(px(15f), py(5f))
                path.lineTo(px(8f), py(12f))
                path.lineTo(px(15f), py(19f))
                canvas.drawPath(path, stroke)
            }
            PanelIcon.Glyph.COLLAPSE -> {
                path.moveTo(px(5f), py(9f))
                path.lineTo(px(12f), py(16f))
                path.lineTo(px(19f), py(9f))
                canvas.drawPath(path, stroke)
            }
            PanelIcon.Glyph.EXPAND -> {
                path.moveTo(px(6f), py(10f))
                path.lineTo(px(12f), py(16f))
                path.lineTo(px(18f), py(10f))
                path.close()
                canvas.drawPath(path, fill)
            }
            PanelIcon.Glyph.CLOSE -> {
                path.moveTo(px(6f), py(6f))
                path.lineTo(px(18f), py(18f))
                path.moveTo(px(18f), py(6f))
                path.lineTo(px(6f), py(18f))
                canvas.drawPath(path, stroke)
            }
            PanelIcon.Glyph.GRID -> {
                // 2×2 圆点阵：比线框更轻，且小尺寸下不糊
                val r = 2.1f * u
                for (gx in floatArrayOf(8f, 16f)) {
                    for (gy in floatArrayOf(8f, 16f)) {
                        canvas.drawCircle(px(gx), py(gy), r, fill)
                    }
                }
            }
            PanelIcon.Glyph.EMOJI -> {
                canvas.drawCircle(cx, cy, 8f * u, stroke)
                canvas.drawCircle(px(9f), py(10f), 1.2f * u, fill)
                canvas.drawCircle(px(15f), py(10f), 1.2f * u, fill)
                oval.set(px(7.5f), py(11f), px(16.5f), py(18f))
                canvas.drawArc(oval, 20f, 140f, false, stroke)
            }
            PanelIcon.Glyph.CLIPBOARD -> {
                oval.set(px(6f), py(6f), px(18f), py(20f))
                canvas.drawRoundRect(oval, 2f * u, 2f * u, stroke)
                oval.set(px(9.5f), py(3.5f), px(14.5f), py(7.5f))
                canvas.drawRoundRect(oval, 1.2f * u, 1.2f * u, fill)
            }
            PanelIcon.Glyph.TEXT_EDIT -> {
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
            PanelIcon.Glyph.SETTINGS -> {
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
            PanelIcon.Glyph.INCOGNITO -> {
                // 帽顶 + 帽檐
                path.moveTo(px(7f), py(10f))
                path.lineTo(px(8.5f), py(4.5f))
                path.lineTo(px(15.5f), py(4.5f))
                path.lineTo(px(17f), py(10f))
                path.moveTo(px(3.5f), py(10.5f))
                path.lineTo(px(20.5f), py(10.5f))
                canvas.drawPath(path, stroke)
                // 眼镜
                canvas.drawCircle(px(8f), py(16f), 3f * u, stroke)
                canvas.drawCircle(px(16f), py(16f), 3f * u, stroke)
                canvas.drawLine(px(11f), py(16f), px(13f), py(16f), stroke)
            }
            PanelIcon.Glyph.MIC -> {
                oval.set(px(9f), py(3f), px(15f), py(14f))
                canvas.drawRoundRect(oval, 3f * u, 3f * u, stroke)
                oval.set(px(6f), py(6f), px(18f), py(17f))
                canvas.drawArc(oval, 0f, 180f, false, stroke)
                path.moveTo(px(12f), py(17f))
                path.lineTo(px(12f), py(21f))
                path.moveTo(px(9f), py(21f))
                path.lineTo(px(15f), py(21f))
                canvas.drawPath(path, stroke)
            }
            PanelIcon.Glyph.GLOBE -> {
                canvas.drawCircle(cx, cy, 8.5f * u, stroke)
                oval.set(px(8.3f), py(3.5f), px(15.7f), py(20.5f))
                canvas.drawOval(oval, stroke)
                path.moveTo(px(3.5f), py(12f))
                path.lineTo(px(20.5f), py(12f))
                canvas.drawPath(path, stroke)
            }
            PanelIcon.Glyph.ONE_HAND -> {
                // 手机靠右 + 左边一个指向它的箭头：「键盘收到一边」
                oval.set(px(11f), py(3.5f), px(19f), py(20.5f))
                canvas.drawRoundRect(oval, 2f * u, 2f * u, stroke)
                canvas.drawLine(px(14f), py(17.5f), px(16f), py(17.5f), stroke)
                path.moveTo(px(4f), py(12f))
                path.lineTo(px(8.5f), py(12f))
                path.moveTo(px(6.5f), py(9.5f))
                path.lineTo(px(9f), py(12f))
                path.lineTo(px(6.5f), py(14.5f))
                canvas.drawPath(path, stroke)
            }
            PanelIcon.Glyph.PIN, PanelIcon.Glyph.PIN_OUTLINE -> {
                path.moveTo(px(9f), py(4f))
                path.lineTo(px(15f), py(4f))
                path.lineTo(px(14f), py(10f))
                path.lineTo(px(17.5f), py(13.5f))
                path.lineTo(px(6.5f), py(13.5f))
                path.lineTo(px(10f), py(10f))
                path.close()
                canvas.drawPath(path, if (glyph == PanelIcon.Glyph.PIN) fill else stroke)
                canvas.drawLine(px(12f), py(13.5f), px(12f), py(20.5f), stroke)
            }
            PanelIcon.Glyph.CLOCK -> {
                canvas.drawCircle(cx, cy, 8.5f * u, stroke)
                path.moveTo(px(12f), py(7f))
                path.lineTo(px(12f), py(12f))
                path.lineTo(px(15.5f), py(14f))
                canvas.drawPath(path, stroke)
            }
            PanelIcon.Glyph.LOCK, PanelIcon.Glyph.UNLOCK -> {
                // 锁身：圆角矩形；锁梁：半圆 + 两条竖边，未锁定时右边竖边抬起、整个梁右移
                oval.set(px(6f), py(11f), px(18f), py(20f))
                canvas.drawRoundRect(oval, 2f * u, 2f * u, stroke)
                val open = glyph == PanelIcon.Glyph.UNLOCK
                val shift = if (open) 3f else 0f
                path.moveTo(px(8.5f + shift), py(11f))
                path.lineTo(px(8.5f + shift), py(8f))
                oval.set(px(8.5f + shift), py(4f), px(15.5f + shift), py(12f))
                path.arcTo(oval, 180f, 180f)
                if (!open) path.lineTo(px(15.5f), py(11f))
                canvas.drawPath(path, stroke)
            }
            PanelIcon.Glyph.ARROW_UP, PanelIcon.Glyph.ARROW_DOWN, PanelIcon.Glyph.ARROW_LEFT, PanelIcon.Glyph.ARROW_RIGHT -> {
                // 统一按「朝上」描点，再绕中心转到各方向
                val angle = when (glyph) {
                    PanelIcon.Glyph.ARROW_RIGHT -> 90f
                    PanelIcon.Glyph.ARROW_DOWN -> 180f
                    PanelIcon.Glyph.ARROW_LEFT -> 270f
                    else -> 0f
                }
                canvas.save()
                canvas.rotate(angle, cx, cy)
                path.moveTo(px(12f), py(19f))
                path.lineTo(px(12f), py(5f))
                path.moveTo(px(6.5f), py(10.5f))
                path.lineTo(px(12f), py(5f))
                path.lineTo(px(17.5f), py(10.5f))
                canvas.drawPath(path, stroke)
                canvas.restore()
            }
        }
    }
}

/** 把一个图标当前景画在按钮中央（文字清空后用），尺寸 [sizeDp] */
class GlyphDrawable(
    private val glyph: PanelIcon.Glyph,
    private val tint: Int,
    private val sizePx: Float,
) : android.graphics.drawable.Drawable() {
    private val painter = GlyphPainter()

    override fun draw(canvas: Canvas) {
        painter.draw(canvas, glyph, bounds.exactCenterX(), bounds.exactCenterY(), sizePx, tint)
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT

    companion object {
        /** [view] 的文字换成居中的矢量图标（尺寸 [sizeDp]） */
        fun apply(view: android.widget.TextView, glyph: PanelIcon.Glyph, tint: Int, sizeDp: Float = 22f) {
            view.text = ""
            view.contentDescription = PanelIcon.labelOf(glyph)
            view.foreground = GlyphDrawable(glyph, tint, sizeDp * view.resources.displayMetrics.density)
        }
    }
}
