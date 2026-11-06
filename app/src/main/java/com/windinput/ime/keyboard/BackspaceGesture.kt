package com.windinput.ime.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.PopupWindow
import com.windinput.ime.keyboard.BackspaceGestureMachine.Bubble

/**
 * 退格手势组件：主键盘与各面板的退格键共用一套行为——
 * 单击、长按连删、左滑扩选、上滑弹「删光标前 | 清空」双选项气泡。状态机在
 * [BackspaceGestureMachine]（纯 JVM 可测），这里只管 Android 侧：计时、气泡、触摸接线。
 *
 * 坐标一律是 [anchor] 视图内的坐标。键盘（一个 View 里多个键）手动调 [down]/[move]/[up]/[cancel]，
 * 面板里的独立按钮用 [attach]。
 *
 * @param feedback 连删每一下之前的反馈（震动 / 键音），由宿主决定
 */
class BackspaceGesture(
    private val listener: Listener,
    private val style: Style? = null,
    private val feedback: (() -> Unit)? = null,
) {
    interface Listener {
        /** 单次退格（单击一下、连删的每一下） */
        fun onDelete()

        /** 左滑扩选：选中光标前 count 个字符（实时；0 = 选区收回光标） */
        fun onSelect(count: Int)

        /** 扩选后抬手：删选区 */
        fun onSelectionDelete()

        /** 上滑正上方抬手：删光标前的全部（光标后的保留） */
        fun onDeleteBeforeCursor()

        /** 上滑后左移抬手：清空整个输入框 */
        fun onClearAll()

        /** 设置里的「退格滑动手势」开关；关了只剩单击 / 连删 */
        fun swipeEnabled(): Boolean = true
    }

    /** 气泡配色；默认取当前主题，面板可传入覆盖 */
    data class Style(
        val icon: Int,
        val accent: Int,
        val accentSoft: Int,
        val bubble: Int,
        val border: Int,
    ) {
        companion object {
            fun fromPalette(): Style = Palette.current.let {
                Style(it.text, it.accentText, it.accentSoft, it.key, it.border)
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var anchor: View? = null
    private var bubbleView: BubbleView? = null
    private var popup: PopupWindow? = null
    private var keyRect = RectF()
    private var density = 1f

    private val timer = object : BackspaceGestureMachine.Timer {
        private val fire = Runnable {
            if (!machine.isPressed) return@Runnable
            feedback?.invoke()
            machine.onTimer()
        }

        override fun schedule(delayMs: Long) {
            handler.removeCallbacks(fire)
            handler.postDelayed(fire, delayMs)
        }

        override fun cancel() = handler.removeCallbacksAndMessages(null)
    }

    private lateinit var machine: BackspaceGestureMachine
    private var machineDensity = 0f

    /** 本次按压已连删过 / 进过滑动（宿主据此屏蔽别的手势） */
    val isEngaged: Boolean get() = ::machine.isInitialized && machine.isEngaged

    fun down(anchor: View, keyRect: RectF, x: Float, y: Float) {
        this.anchor = anchor
        this.keyRect = RectF(keyRect)
        density = anchor.resources.displayMetrics.density
        if (!::machine.isInitialized || machineDensity != density) {
            machineDensity = density
            machine = BackspaceGestureMachine(
                BackspaceGestureMachine.Config.forDensity(density), listener, timer, ::onBubble,
            )
        }
        machine.down(x, y)
        lastY = y
    }

    private var lastY = 0f

    fun move(x: Float, y: Float) {
        if (!::machine.isInitialized) return
        lastY = y
        machine.move(x, y)
        popup?.let { moveBubble(y) }
    }

    fun up() {
        if (::machine.isInitialized) machine.up()
    }

    fun cancel() {
        if (::machine.isInitialized) machine.cancel()
    }

    /** 面板里的独立退格按钮：按下 / 抬起 / 取消接到本组件，按压态与按下震动在此处理 */
    @SuppressLint("ClickableViewAccessibility")
    fun attach(button: View) {
        button.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    KeyHaptics.tap(v)
                    down(v, RectF(0f, 0f, v.width.toFloat(), v.height.toFloat()), e.x, e.y)
                }
                MotionEvent.ACTION_MOVE -> move(e.x, e.y)
                MotionEvent.ACTION_UP -> {
                    v.isPressed = false
                    up()
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    cancel()
                }
            }
            true
        }
    }

    // ── 气泡 ─────────────────────────────────────────────────────

    private fun onBubble(state: Bubble) {
        val view = anchor ?: return
        when (state) {
            Bubble.HIDDEN -> dismissBubble()
            else -> {
                val first = popup == null
                if (first) showBubble(view)
                else if (state != Bubble.IDLE) KeyHaptics.tap(view)
                bubbleView?.choice = state
            }
        }
    }

    private fun showBubble(view: View) {
        val st = style ?: Style.fromPalette()
        val bv = BubbleView(view.context, st, density)
        bubbleView = bv
        val w = bv.widthPx
        val h = bv.heightPx
        val root = view.rootView
        val rootLoc = IntArray(2)
        root.getLocationInWindow(rootLoc)
        val loc = IntArray(2)
        view.getLocationInWindow(loc)
        // 右格（删光标前）正对键中心，左格（清空）在它左边
        val want = (loc[0] + keyRect.centerX() - bv.rightCellCenterPx).toInt()
        bubbleX = want.coerceIn(rootLoc[0], maxOf(rootLoc[0], rootLoc[0] + root.width - w))
        bubbleW = w
        bubbleH = h
        popup = PopupWindow(bv, w, h, false).apply {
            isClippingEnabled = false
            isTouchable = false
            showAtLocation(view, Gravity.NO_GRAVITY, bubbleX, bubbleY(lastY))
        }
    }

    private var bubbleX = 0
    private var bubbleW = 0
    private var bubbleH = 0

    /** 气泡下沿高出指尖 [BUBBLE_GAP_DP]：手指高约 40dp，再近就被遮住；顶到屏幕上沿停住 */
    private fun bubbleY(fingerY: Float): Int {
        val loc = IntArray(2)
        anchor?.getLocationInWindow(loc)
        return (loc[1] + fingerY - bubbleH - BUBBLE_GAP_DP * density).toInt().coerceAtLeast(0)
    }

    private fun moveBubble(fingerY: Float) {
        popup?.update(bubbleX, bubbleY(fingerY), bubbleW, bubbleH)
    }

    private fun dismissBubble() {
        popup?.dismiss()
        popup = null
        bubbleView = null
    }

    /**
     * 双选项气泡：固定宽高、只画矢量图标，选中项放大变强调色也不会换行 / 溢出（没有文字）。
     * 左格 = 清空（垃圾桶），右格 = 删光标前（|← 退格标签）。
     */
    private class BubbleView(context: Context, private val st: Style, private val density: Float) : View(context) {
        private val inset = INSET_DP * density
        private val cell = CELL_DP * density
        val widthPx = (2 * cell + 2 * inset).toInt()
        val heightPx = (CELL_H_DP * density + 2 * inset).toInt()
        val rightCellCenterPx get() = inset + cell * 1.5f

        var choice: Bubble = Bubble.IDLE
            set(v) {
                if (field != v) {
                    field = v
                    invalidate()
                }
            }

        private val bg = GradientDrawable().apply {
            setColor(st.bubble)
            cornerRadius = RADIUS_DP * density
            setStroke((1 * density).toInt(), st.border)
        }
        private val paint = KeyIcons.strokePaint(density)
        private val path = Path()
        private val soft = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = st.accentSoft }

        override fun onDraw(canvas: Canvas) {
            bg.setBounds(0, 0, width, height)
            bg.draw(canvas)
            val cy = height / 2f
            for (all in booleanArrayOf(true, false)) {
                val selected = choice == if (all) Bubble.ALL else Bubble.BEFORE
                val left = inset + if (all) 0f else cell
                val cx = left + cell / 2
                if (selected) {
                    val r = (RADIUS_DP - INSET_DP) * density
                    canvas.drawRoundRect(left, inset, left + cell, height - inset, r, r, soft)
                }
                paint.color = if (selected) st.accent else st.icon
                val d = density * if (selected) 1.25f else 1f
                if (all) KeyIcons.drawTrash(canvas, cx, cy, d, paint, path)
                else KeyIcons.drawDeleteBefore(canvas, cx, cy, d, paint, path)
            }
        }
    }

    private companion object {
        const val BUBBLE_GAP_DP = 40f
        const val INSET_DP = 3f
        const val CELL_DP = 52f
        const val CELL_H_DP = 56f
        const val RADIUS_DP = 14f
    }
}
