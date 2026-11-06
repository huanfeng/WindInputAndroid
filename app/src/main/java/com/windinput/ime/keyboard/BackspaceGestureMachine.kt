package com.windinput.ime.keyboard

/**
 * 退格手势状态机（纯 Kotlin，不依赖 Android，可直接 JVM 单测）。
 *
 * 一次按压里每次移动都按**当前位置**重新判定模式，不抬手可在各模式间来回切换：
 * - 空闲：未滑动。抬手 = 单击（没触发连删、也没进过任何滑动时才补）。
 * - 扩选：左滑量 > [Config.activatePx]。每 [Config.stepPx] 多选一个字符（[Listener.onSelect]）。
 *   左滑量回到 [Config.exitPx] 以内即退出，选区回到光标（onSelect(0)），之后可直接上滑。抬手 = 删选区。
 * - 气泡：上滑量 > activate 且大于横向位移（且不在扩选里）时弹出；上滑量回到 exit 以内收起。
 *   收起后须先回到水平原点附近（左移量 <= activate）才允许进入扩选，
 *   否则「上滑 -> 左移 -> 下滑收起」会把残留的左移量误当成扩选。
 *   上滑量 > [Config.thresholdPx] 才算「选中」：手指偏左超过 [Config.leftPx] = 清空输入框，
 *   否则 = 删光标前全部；未过阈值抬手 = 反悔，什么都不删。
 *
 * 进入用 activate / threshold，退出用更小的 exit / threshold - [Config.hysteresisPx]（滞回），
 * 手指在阈值附近抖动不会反复切换。
 *
 * 长按 [down] 传入的延迟后每 [Config.repeatIntervalMs] 连删一下。延迟由宿主在按下时读设置
 * （ui.android.long_press_delay），计时经 [Timer] 提供：到点调 [onTimer]。
 */
class BackspaceGestureMachine(
    private val cfg: Config,
    private val listener: BackspaceGesture.Listener,
    private val timer: Timer,
    private val onBubble: (Bubble) -> Unit,
) {
    data class Config(
        val activatePx: Float,
        val thresholdPx: Float,
        val stepPx: Float,
        val leftPx: Float,
        val repeatIntervalMs: Long = REPEAT_INTERVAL_MS,
    ) {
        /** 扩选 / 气泡的退出距离：进入距离的一半 */
        val exitPx: Float get() = activatePx / 2

        /** 选项阈值（BEFORE / ALL）的回差 */
        val hysteresisPx: Float get() = thresholdPx / 10

        companion object {
            fun forDensity(density: Float) = Config(
                activatePx = KeyboardDimens.SWIPE_ACTIVATE_DP * density,
                thresholdPx = KeyboardDimens.SWIPE_THRESHOLD_DP * density,
                stepPx = KeyboardDimens.CURSOR_STEP_DP * density,
                leftPx = 28 * density,
            )
        }
    }

    interface Timer {
        fun schedule(delayMs: Long)
        fun cancel()
    }

    enum class Bubble { HIDDEN, IDLE, BEFORE, ALL }

    private enum class Mode { IDLE, SELECT, BUBBLE }

    private var pressed = false
    private var swipeOn = false
    private var mode = Mode.IDLE
    private var bubble = Bubble.HIDDEN
    private var consumed = false
    private var repeatFired = false
    private var needsRecenter = false
    private var lastCount = 0
    private var downX = 0f
    private var downY = 0f

    val isPressed: Boolean get() = pressed

    /** 本次按压已不是一次单纯的点按（连删过 / 进过滑动）：宿主据此屏蔽别的手势 */
    val isEngaged: Boolean get() = pressed && (consumed || repeatFired || mode != Mode.IDLE)

    fun down(x: Float, y: Float, longPressDelayMs: Long) {
        reset()
        pressed = true
        swipeOn = listener.swipeEnabled()
        downX = x
        downY = y
        timer.schedule(longPressDelayMs)
    }

    fun onTimer() {
        if (!pressed) return
        repeatFired = true
        listener.onDelete()
        timer.schedule(cfg.repeatIntervalMs)
    }

    fun move(x: Float, y: Float) {
        if (!pressed || !swipeOn) return
        val left = downX - x
        val up = downY - y
        if (needsRecenter && left <= cfg.activatePx) needsRecenter = false
        if (mode == Mode.SELECT) {
            if (left > cfg.exitPx) {
                select(left)
                return
            }
            select(0f)
            mode = Mode.IDLE
        }
        if (mode == Mode.BUBBLE) {
            if (up > cfg.exitPx) {
                chooseBubble(left, up)
                return
            }
            setBubble(Bubble.HIDDEN)
            mode = Mode.IDLE
            needsRecenter = left > cfg.activatePx
        }
        if (up > cfg.activatePx && up > kotlin.math.abs(x - downX)) {
            engage()
            mode = Mode.BUBBLE
            chooseBubble(left, up)
        } else if (left > cfg.activatePx && !needsRecenter) {
            engage()
            mode = Mode.SELECT
            select(left)
        }
    }

    fun up() {
        if (!pressed) return
        val m = mode
        val b = bubble
        val plainTap = !consumed && !repeatFired
        finish()
        when (m) {
            Mode.SELECT -> listener.onSelectionDelete()
            Mode.BUBBLE -> when (b) {
                Bubble.BEFORE -> listener.onDeleteBeforeCursor()
                Bubble.ALL -> listener.onClearAll()
                else -> Unit
            }
            Mode.IDLE -> if (plainTap) listener.onDelete()
        }
    }

    /** 被打断（系统取消、被别的键顶替）：不执行任何删除，扩选的选区收回光标 */
    fun cancel() {
        if (!pressed) return
        val hadSelection = mode == Mode.SELECT && lastCount != 0
        finish()
        if (hadSelection) listener.onSelect(0)
    }

    private fun engage() {
        consumed = true
        timer.cancel()
    }

    private fun select(left: Float) {
        val count = (left / cfg.stepPx).toInt().coerceAtLeast(0)
        if (count != lastCount) {
            lastCount = count
            listener.onSelect(count)
        }
    }

    private fun chooseBubble(left: Float, up: Float) {
        val h = cfg.hysteresisPx
        val chosen = bubble == Bubble.BEFORE || bubble == Bubble.ALL
        val armed = up > (if (chosen) cfg.thresholdPx - h else cfg.thresholdPx)
        val toAll = left > (if (bubble == Bubble.ALL) cfg.leftPx - h else cfg.leftPx)
        setBubble(
            when {
                !armed -> Bubble.IDLE
                toAll -> Bubble.ALL
                else -> Bubble.BEFORE
            },
        )
    }

    private fun setBubble(b: Bubble) {
        if (b == bubble) return
        bubble = b
        onBubble(b)
    }

    private fun finish() {
        pressed = false
        timer.cancel()
        setBubble(Bubble.HIDDEN)
    }

    private fun reset() {
        mode = Mode.IDLE
        bubble = Bubble.HIDDEN
        consumed = false
        repeatFired = false
        needsRecenter = false
        lastCount = 0
    }

    companion object {
        const val REPEAT_INTERVAL_MS = 50L
    }
}
