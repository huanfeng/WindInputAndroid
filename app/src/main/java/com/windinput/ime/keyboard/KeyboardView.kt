package com.windinput.ime.keyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.Log
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

    private companion object {
        const val TAG = "WindKeyboard"

        /**
         * 「分词」键发的字符。**是反引号不是 `'`**：出厂二三候选键是 `;` `'`
         * （`select_key_groups = semicolon_quote`），`'` 被占时引擎的分隔符 `auto` 档改用
         * 反引号（主仓 `manual_separator_key_of`）。发 `'` 实测是「选第三候选上屏」。
         * 引擎把两者都显示成 `'`。
         */
        const val SPLIT_CHAR = '`'

        /** 按下高亮的最小可见时长；约 4 帧，短于此人眼捕捉不到 */
        const val MIN_HIGHLIGHT_MS = 70L
    }

    interface Listener {
        fun onKeyAction(action: KeyAction, shifted: Boolean)

        /** 空格滑动移光标：delta 为有符号步数（右正左负） */
        fun onCursorMove(delta: Int)

        /** 退格左滑扩选：选中光标前 count 个字符（实时更新） */
        fun onBackspaceSelect(count: Int)

        /** 退格滑动抬手：删除当前选区 */
        fun onBackspaceSwipeDelete()

        /** 功能键长按（中英键 → 方案卡片，空格 → 按设置切输入法 / 语音） */
        fun onKeyLongPress(action: KeyAction)

        /**
         * 功能键长按的选项条：手指不抬、横移选、抬手定（中英键：各方案 + 切换输入法）。
         * null = 没有选项条，长按走 [onKeyLongPress]。
         */
        fun longPressOptions(action: KeyAction): KeyOptions? = null

        /** 选项条抬手：[index] 是手指停住的那项；停在初始项（没滑走）为 null */
        fun onLongPressOption(action: KeyAction, index: Int?) = Unit
    }

    /** 长按选项条的内容：[selected] 为初始选中（通常是当前状态，如当前方案） */
    data class KeyOptions(val labels: List<String>, val selected: Int)

    var listener: Listener? = null

    /** 布局来源，缺省内置；Service 注入 LayoutRepository 后走 TOML + 覆盖解析 */
    var layoutProvider: (KeyboardLayer) -> List<List<KeyDef>> = KeyboardLayouts::rows
        set(value) {
            field = value
            reloadLayout()
        }


    /** 按键音开关（设置项 ui.android.sound）；音量随系统 */
    var soundEnabled = false

    /** 按键预览气泡开关（设置项 ui.android.key_preview） */
    var previewEnabled = false

    /**
     * 长按判定时长（设置项 ui.android.long_press_delay）。退格连删的起始延迟与它
     * **共用**：两者在用户感知里是同一件事——「按住多久算按住」，分开配只会互相打架。
     */
    var longPressDelayMs = 300

    /** 空格横滑移光标 / 退格左滑扩选 / 上滑副字符 三个手势的开关（设置项 ui.android.gesture_*） */
    var spaceSwipeEnabled = true

    /** 长按空格有没有动作（设置项 ui.android.space_long_press ≠ none）；做什么由 Service 决定 */
    var spaceLongPressEnabled = true
    var backspaceSwipeEnabled = true
    var swipeSymbolsEnabled = true

    /**
     * 中文标点形态开关：键面、角标、长按气泡里的标点按它显示成「，。？」还是「,.?」。
     * 真值来自引擎推送（chineseMode && chinesePunct），本地不自记——见 [Punct] 说明。
     * 初值取中文：与 [englishMode] 的初值一致；核心起来后不主动推初始状态，
     * 初值为 false 时，刚装好的键盘上逗号键显示成半角「,」。
     */
    var chinesePunct = true
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager

    /**
     * 当前方案短称（「五」/「拼」/「英」）；是「英」即英文模式（见 [englishMode]）。
     *
     * 值由引擎推送（Service 转发），**本地不自记**——方案切换、密码框强制英文都发生在
     * 引擎内部，本地记第二份必然与引擎漂移。用短称而不是 `chineseMode` 布尔：
     * 英文在本端是一个方案，切过去后 `chineseMode` 仍为 true（见 Service 的说明）。
     */
    var modeLabel: String = "中"
        set(value) {
            if (field == value) return
            field = value
            englishMode = value == "英"
        }

    /** 回车键动作标签（imeOptions 驱动：发送/搜索/下一项…），null = 缺省 label */
    var enterLabel: String? = null
        set(value) {
            field = value
            invalidate()
        }

    /**
     * 英文模式（中英键显示「英」时）。决定字母键面大小写（中文大写、英文小写，大众布局）、
     * 中英键哪个字高亮，以及逗号句号合一键输出哪一个。随 [modeLabel] 自动更新。
     */
    var englishMode = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /**
     * 引擎里有未上屏的编码。有编码时回车写「确定」（上屏编码原文），
     * 可分词的方案（见 [splitEnabled]）⇧ 写「分词」。
     */
    var composing = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** 当前方案有可用的音节分隔键（见 [SPLIT_CHAR]）。只有它为真，有编码时 ⇧ 才变「分词」 */
    var splitEnabled = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /**
     * 字母键顶上是否画上滑字符角标（设置项 ui.android.show_swipe_hints，默认开：
     * 豆包、微信的默认布局都画，首行一排 1–0 让数字一眼可得）。关掉只是不画，上滑功能
     * 本身不受影响；非字母键的角标始终画。
     */
    var showSwipeHints = true
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /**
     * 九宫格数字键盘左列的符号（数字框和电话框不同，由 Service 按输入框类型给）。
     * 画在布局左列的占位空白里，可上下滚动，点选原样上屏（见 [sideRect]）。
     */
    var sideSymbols: List<String> = emptyList()
        set(value) {
            if (field == value) return
            field = value
            sideScroll = 0f
            invalidate()
        }

    /** 空格上的灰色小字：当前方案名。空串 = 不画 */
    var spaceLabel = ""
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    private var layer = KeyboardLayer.LETTERS

    /** 数字键盘「←」回到哪一层：从 123 层进来回 123 层，数字框直接调起的回字母层 */
    private var numberReturnLayer = KeyboardLayer.LETTERS
    private var rows = layoutProvider(layer)
    private var shifted = false

    /** 大写锁定（Shift 双击） */
    private var shiftLocked = false

    /** 当前的单次 Shift 是自动大写给的（不是用户按的）：条件消失时要自己撤回 */
    private var autoShifted = false
    private var lastShiftTapTime = 0L

    // ── 配色取自键盘域调色板（Service 按系统深浅色注入，见 Palette） ──
    private val palette = Palette.current
    private val bgColor = palette.background
    private val keyColor = palette.key
    private val keySpecialColor = palette.keySpecial
    private val keyPressedColor = palette.keyPressed
    private val textColor = palette.text
    private val hintColor = palette.hint

    private val density = resources.displayMetrics.density

    /** 行高动态取自 KeyboardDimens（横竖屏配置由 Service 注入） */
    private val rowHeightPx get() = (KeyboardDimens.rowHeightDp * density).toInt()
    private val keyGapPx = 3 * density
    private val keyRadiusPx = 8 * density
    private val swipeThresholdPx = 40 * density
    private val swipeActivatePx = 16 * density

    /** 空格移光标/退格扩选的步进距离 */
    private val cursorStepPx = 12 * density

    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor
        textAlign = Paint.Align.LEFT
        textSize = 20 * density
    }

    /** 复用的字形包围盒（onDraw 每键一次，避免分配） */
    private val glyphBounds = android.graphics.Rect()
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = hintColor
        textAlign = Paint.Align.RIGHT
        textSize = 11 * density
    }

    /** 每次 layout 重算的键位命中区域；[row] 用于落在空白处时找同一行最近的键 */
    private data class KeyBounds(val key: KeyDef, val rect: RectF, val row: Int)

    private val keyBounds = mutableListOf<KeyBounds>()

    /**
     * 左列符号的区域：布局从第 1 行起、每行打头的等宽占位空白连成的一列（数字键盘的左列）。
     * 没有这样的列或没给 [sideSymbols] 时为 null，视图就是普通键盘。
     */
    private var sideRect: RectF? = null
    private var sideScroll = 0f
    private val sideItemHeightPx get() = rowHeightPx * 0.75f
    private var sideTouch = false
    private var sideDragging = false
    private var sideLastY = 0f
    private var sidePressed = -1
    private val touchSlopPx = android.view.ViewConfiguration.get(context).scaledTouchSlop
    private var pressedKey: KeyDef? = null
    private var pressedRect: RectF? = null

    /**
     * **绘制用**的按下高亮，与手势状态 [pressedKey] 分开。
     *
     * 快速点击时 DOWN 与 UP 常落在同一帧内（间隔 < 16ms）：`invalidate()` 只是标脏，
     * 真正重绘要等下一次 VSYNC，届时 [pressedKey] 已被抬手清空，于是**按下态一帧都没画过**
     * ——表现就是「点得快就没有按下效果」。故抬手时不立刻清高亮，而是保证它至少可见
     * [MIN_HIGHLIGHT_MS]（约 4 帧）。
     */
    private var highlightKey: KeyDef? = null
    private var highlightAt = 0L

    /** 高亮实际经历的帧数：自检用，验证按下态确实被绘制过（见 [onDraw]） */
    private var highlightFrames = 0

    private val clearHighlight = Runnable {
        finishHighlight()
        invalidate()
    }

    /**
     * 结束高亮并自检。
     *
     * 记录**实际绘制帧数**而不是时长：这个 bug 的本质是「标脏了但没轮到重绘」，
     * 时长达标而帧数为 0 依然是没有按下效果。帧数 ≥1 才算真的画过。
     */
    private fun finishHighlight() {
        if (highlightKey != null && highlightFrames == 0) {
            Log.w(TAG, "按下高亮一帧未绘制（${android.os.SystemClock.uptimeMillis() - highlightAt}ms）")
        }
        highlightKey = null
        highlightFrames = 0
    }

    private val popupManager by lazy { KeyPopupManager(this) }

    // 手势状态
    private var downX = 0f
    private var downY = 0f

    /** 上滑的一体式气泡已长出（本次按压改由气泡决定上屏什么） */
    private var swipeShown = false

    /** 上滑已过阈值：抬手上屏副字符 */
    private var swipeArmed = false

    /** 正弹着选项条的功能键（长按中英键）；抬手时按条上的选中项回调 */
    private var optionsAction: KeyAction? = null
    private var optionsInitial = 0

    /**
     * 选项条弹出时手指所在的 x；手指真正横移过触摸阈值之前不改选中项。按住不动时系统照样会发
     * MOVE（坐标不变），不拦的话选中项立刻跳到手指正上方那一项，抬手就误切了方案
     */
    private var optionsAnchorX = 0f
    private var optionsMoved = false

    /** 本次按压最近一次的手指 x（长按弹选项条时记作锚点） */
    private var lastX = 0f

    /** 空格滑动移光标进行中 */
    private var spaceSwipeActive = false
    private var lastStepX = 0f

    /** 退格左滑扩选进行中 */
    private var backspaceSwipeActive = false
    private var lastBackspaceCount = 0

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

    /** 长按已触发（抬手时不再补发单击） */
    private var longPressFired = false

    private val longPressRunnable = Runnable {
        val key = pressedKey ?: return@Runnable
        val rect = pressedRect ?: return@Runnable
        val popup = popupChars(key)
        if (popup.isNotEmpty()) {
            popupManager.dismissPreview()
            // 气泡里显示中英形态，抬手时按**下标**映回布局里的 ASCII 字符再喂引擎
            popupManager.showPopup(rect, popup.map { Punct.display(it, chinesePunct) })
            repeatHaptic()
        } else if (hasLongPressAction(key.action)) {
            // 功能键长按走独立回调（没有字符气泡可弹）。置位后抬手不补单击，
            // 否则「长按开面板」会连带触发一次「点击切模式」。
            longPressFired = true
            repeatHaptic()
            val options = listener?.longPressOptions(key.action)
            if (options != null) {
                optionsAction = key.action
                optionsInitial = options.selected
                optionsAnchorX = lastX
                optionsMoved = false
                popupManager.dismissPreview()
                popupManager.showMenu(rect, options.labels, options.selected)
            } else {
                listener?.onKeyLongPress(key.action)
            }
        }
    }

    private fun hasLongPressAction(action: KeyAction): Boolean =
        action == KeyAction.SwitchLang || (action == KeyAction.Space && spaceLongPressEnabled)

    /**
     * 长按气泡的字符集。字母键固定是「小写 / 大写」两个形态（豆包的做法）：上滑字符已经有
     * 上滑这条路，长按再给一遍是重复；而单个大写字母在中文模式下原本要按两次 ⇧ 才打得出。
     */
    private fun popupChars(key: KeyDef): String {
        if (key.longPress.isNotEmpty()) return key.longPress
        val c = (key.action as? KeyAction.Text)?.char ?: return ""
        return if (c in 'a'..'z') "$c${c.uppercaseChar()}" else ""
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
        handler.removeCallbacks(clearHighlight)
        highlightKey = null
        highlightFrames = 0
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
                // 占位空白只占宽度，不进命中表也不绘制
                if (key.action != KeyAction.Spacer) {
                    keyBounds += KeyBounds(
                        key,
                        RectF(
                            x + keyGapPx, top + keyGapPx,
                            x + keyWidth - keyGapPx, top + rowHeightPx * key.rowSpan - keyGapPx,
                        ),
                        rowIndex,
                    )
                }
                x += keyWidth
            }
        }
        sideRect = computeSideRect(width)
        // 键位变了（换层、改尺寸）：旧的虚拟节点作废，让无障碍服务重取
        if (a11yAttached) a11y.invalidateRoot()
    }

    /**
     * 无障碍代理已挂上。属性按声明顺序初始化，[rebuildBounds] 可能在 [a11y] 建好之前被调用
     * （构造期换层），那时它还是 null——用这个标记挡住（JVM 默认 false）。
     */
    private var a11yAttached = false

    private fun computeSideRect(width: Int): RectF? {
        val first = rows.firstOrNull()?.firstOrNull() ?: return null
        if (first.action != KeyAction.Spacer) return null
        val count = rows.takeWhile { row ->
            row.firstOrNull()?.let { it.action == KeyAction.Spacer && it.widthWeight == first.widthWeight } == true
        }.size
        val unit = width / rows[0].sumOf { it.widthWeight.toDouble() }.toFloat()
        return RectF(keyGapPx, keyGapPx, unit * first.widthWeight - keyGapPx, rowHeightPx * count - keyGapPx)
    }

    private val maxSideScroll: Float
        get() {
            val rect = sideRect ?: return 0f
            return (sideSymbols.size * sideItemHeightPx - rect.height()).coerceAtLeast(0f)
        }

    private fun sideIndexAt(y: Float): Int {
        val rect = sideRect ?: return -1
        val i = ((y - rect.top + sideScroll) / sideItemHeightPx).toInt()
        return if (i in sideSymbols.indices) i else -1
    }

    /** 左列符号：一整条功能键底色，里面一格一个符号，超出部分上下滚动 */
    private fun drawSide(canvas: Canvas) {
        val rect = sideRect ?: return
        if (sideSymbols.isEmpty()) return
        keyPaint.color = keySpecialColor
        canvas.drawRoundRect(rect, keyRadiusPx, keyRadiusPx, keyPaint)
        canvas.save()
        canvas.clipRect(rect)
        textPaint.textSize = 18 * density
        textPaint.color = textColor
        sideSymbols.forEachIndexed { i, sym ->
            val top = rect.top + i * sideItemHeightPx - sideScroll
            if (top + sideItemHeightPx < rect.top || top > rect.bottom) return@forEachIndexed
            val cell = RectF(rect.left, top, rect.right, top + sideItemHeightPx)
            if (i == sidePressed) {
                keyPaint.color = keyPressedColor
                canvas.drawRect(cell, keyPaint)
            }
            drawCentered(canvas, sym, cell, textPaint)
        }
        canvas.restore()
    }

    /** 左列的触摸：竖向拖动是滚动，没拖动就抬手是点选 */
    private fun handleSideTouch(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (!sideDragging && kotlin.math.abs(event.y - downY) > touchSlopPx) {
                    sideDragging = true
                    sidePressed = -1
                }
                if (sideDragging) {
                    sideScroll = (sideScroll - (event.y - sideLastY)).coerceIn(0f, maxSideScroll)
                    sideLastY = event.y
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                val index = sidePressed
                sideTouch = false
                sidePressed = -1
                invalidate()
                if (!sideDragging && index >= 0) {
                    listener?.onKeyAction(KeyAction.Commit(sideSymbols[index]), shifted = false)
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                sideTouch = false
                sidePressed = -1
                invalidate()
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(bgColor)
        drawSide(canvas)
        if (highlightKey != null) highlightFrames++
        for ((key, rect) in keyBounds) {
            keyPaint.color = when {
                key === highlightKey -> keyPressedColor
                key.isAccent -> palette.accent
                key.action == KeyAction.Shift && (shifted || shiftLocked) -> keyPressedColor
                key.isSpecial -> keySpecialColor
                else -> keyColor
            }
            canvas.drawRoundRect(rect, keyRadiusPx, keyRadiusPx, keyPaint)
            textPaint.color = if (key.isAccent) palette.onAccent else textColor
            if (key.action == KeyAction.SwitchLang) {
                drawLangKey(canvas, rect)
                continue
            }
            // 数字键盘的空格只是一个小功能键，写「空格」；方案名留给字母层和 123 层的长空格
            if (key.action == KeyAction.Space && spaceLabel.isNotEmpty() && layer != KeyboardLayer.NUMBER) {
                drawCentered(canvas, spaceLabel, rect, smallPaint)
                continue
            }
            // ⇧ / ⌫ 画矢量：字体里这两个符号笔画细、各家字体字形不一，和字母键面放一起很突兀
            if (key.action == KeyAction.Shift && !splitMode) {
                drawShiftIcon(canvas, rect, textPaint.color)
                continue
            }
            if (key.action == KeyAction.Backspace) {
                drawBackspaceIcon(canvas, rect, textPaint.color)
                continue
            }
            val label = displayLabel(key)
            textPaint.textSize = labelSizePx(label)
            val isLetter = (key.action as? KeyAction.Text)?.char?.isLetter() == true
            val hint = if (isLetter && !showSwipeHints) null else key.swipeFor(englishMode)
            // 有角标的字母键：主字下移一点，给顶上居中的角标让出位置（豆包的排法）
            val shiftDown = if (hint != null && isLetter) 4 * density else 0f
            val textY = rect.centerY() - (textPaint.ascent() + textPaint.descent()) / 2 + shiftDown
            // 水平按**字形包围盒**居中，而不是按字符前进宽度居中：全角标点「，」「。」的
            // 字形挤在字模左下角，按前进宽度居中会明显偏左（真机反馈的就是这个）。
            // 竖向仍按 ascent/descent 居中——按包围盒居竖向会让 q 与 a 的基线上下乱跳。
            textPaint.getTextBounds(label, 0, label.length, glyphBounds)
            canvas.drawText(label, rect.centerX() - glyphBounds.exactCenterX(), textY, textPaint)
            // 副字符角标（按当前模式显示中英形态）：字母键在顶上居中（豆包、微信都这么排，
            // 一眼扫过去数字行就是一排 1–0），其余键仍在右上角；字母键的角标受开关控制
            if (hint != null) {
                val text = Punct.display(hint, chinesePunct)
                if (isLetter) {
                    hintPaint.textAlign = Paint.Align.CENTER
                    canvas.drawText(text, rect.centerX(), rect.top + 12 * density, hintPaint)
                    hintPaint.textAlign = Paint.Align.RIGHT
                } else {
                    canvas.drawText(text, rect.right - 5 * density, rect.top + 13 * density, hintPaint)
                }
            }
        }
    }

    private val iconPaint = KeyIcons.strokePaint(density)
    private val iconPath = android.graphics.Path()

    private fun drawShiftIcon(canvas: Canvas, rect: RectF, color: Int) {
        iconPaint.color = color
        KeyIcons.drawShift(canvas, rect.centerX(), rect.centerY(), density, shifted, shiftLocked, iconPaint, iconPath)
    }

    private fun drawBackspaceIcon(canvas: Canvas, rect: RectF, color: Int) {
        iconPaint.color = color
        KeyIcons.drawBackspace(canvas, rect.centerX(), rect.centerY(), density, iconPaint, iconPath)
    }

    /** 有编码且方案支持分词：⇧ 这一格临时当「分词」用（发 [SPLIT_CHAR]） */
    private val splitMode get() = composing && splitEnabled

    private fun displayLabel(key: KeyDef): String {
        if (key.action == KeyAction.Enter) {
            // 有编码时回车上屏编码原文，键面写「确定」（两家相同）
            if (composing) return "确定"
            enterLabel?.let { return it }
        }
        if (key.action == KeyAction.Shift) {
            if (splitMode) return "分词"
            if (shiftLocked) return "⇪"
        }
        val action = key.action
        val ch = key.textFor(englishMode)
        // 标点键：键面随模式显示中英形态（布局里写的是 ASCII）
        if (ch != null && !ch.isLetterOrDigit()) {
            return Punct.display(ch, chinesePunct)
        }
        // 字母键面：中文模式大写、英文模式小写（键面本身就是模式提示）；英文下按 ⇧ 变大写
        val upper = !englishMode || shifted || shiftLocked
        return if (upper && action is KeyAction.Text && action.char.isLetter()) {
            key.label.uppercase()
        } else {
            key.label
        }
    }

    /** 两个字以上的功能键（换行 / 发送 / 分词 / 123）字号收小，免得挤出键帽 */
    private fun labelSizePx(label: String): Float =
        (if (label.length >= 2) 16 else 20) * density

    private val smallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = hintColor
        textSize = 12 * density
    }

    private fun drawCentered(canvas: Canvas, text: String, rect: RectF, paint: Paint) {
        paint.getTextBounds(text, 0, text.length, glyphBounds)
        val y = rect.centerY() - (paint.ascent() + paint.descent()) / 2
        canvas.drawText(text, rect.centerX() - glyphBounds.exactCenterX(), y, paint)
    }

    /**
     * 中英键：键面同时写「中」「英」，左上大字、右下小字，**当前模式那个字**在前面用正文色，
     * 另一个用提示色（两家的做法）。用户一眼看出现在在哪，也知道点了会去哪。
     */
    private fun drawLangKey(canvas: Canvas, rect: RectF) {
        val (major, minor) = if (englishMode) "英" to "中" else "中" to "英"
        textPaint.textSize = 17 * density
        canvas.drawText(
            major,
            rect.left + rect.width() * 0.18f,
            rect.top + rect.height() * 0.50f,
            textPaint,
        )
        smallPaint.textSize = 11 * density
        canvas.drawText(
            minor,
            rect.left + rect.width() * 0.52f,
            rect.top + rect.height() * 0.80f,
            smallPaint,
        )
        smallPaint.textSize = 12 * density
    }

    /**
     * 当前跟踪的手指。快打时常是「上一指未抬、下一指已落」（rollover）：只认第一指的话，
     * 第二指整次按键都丢了。做法同 AOSP LatinIME——新手指落下时把上一指的按压按单击收尾，
     * 改跟新手指；之后只看这根手指的事件，已收尾的那根抬起时不再处理。
     */
    private var activePointerId = MotionEvent.INVALID_POINTER_ID

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (sideTouch) {
            // 侧栏滚动只跟第一指，其余手指的落下/抬起忽略
            if (event.actionMasked != MotionEvent.ACTION_POINTER_DOWN &&
                event.actionMasked != MotionEvent.ACTION_POINTER_UP
            ) {
                handleSideTouch(event)
            }
            return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val side = sideRect
                if (side != null && sideSymbols.isNotEmpty() && side.contains(event.x, event.y)) {
                    sideTouch = true
                    sideDragging = false
                    downY = event.y
                    sideLastY = event.y
                    sidePressed = sideIndexAt(event.y)
                    KeyHaptics.tap(this)
                    invalidate()
                    return true
                }
                startPress(event.getPointerId(0), event.x, event.y)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // 上一指正处在手势里（气泡选字、空格移光标、退格扩选/连删、长按）时不打断它
                if (pressedKey != null && gestureInProgress()) return true
                val i = event.actionIndex
                if (pressedKey != null) finishPress()
                // 落在左侧符号列：那里不是键（占位空白），按键位找会命中同行最近的数字键、打错字。
                // 侧列滚动只认单指，这根手指忽略
                val side = sideRect
                if (side != null && sideSymbols.isNotEmpty() && side.contains(event.getX(i), event.getY(i))) {
                    activePointerId = MotionEvent.INVALID_POINTER_ID
                    return true
                }
                startPress(event.getPointerId(i), event.getX(i), event.getY(i))
            }
            MotionEvent.ACTION_MOVE -> {
                val i = event.findPointerIndex(activePointerId)
                if (i >= 0) movePress(event.getX(i), event.getY(i))
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(event.actionIndex) == activePointerId) finishPress()
            }
            MotionEvent.ACTION_UP -> {
                if (event.getPointerId(0) == activePointerId) finishPress()
                activePointerId = MotionEvent.INVALID_POINTER_ID
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelPress()
                activePointerId = MotionEvent.INVALID_POINTER_ID
            }
        }
        return true
    }

    private fun gestureInProgress(): Boolean =
        popupManager.isPopupShown || swipeShown || spaceSwipeActive || backspaceSwipeActive ||
            repeatFired || longPressFired

    private fun startPress(pointerId: Int, x: Float, y: Float) {
        activePointerId = pointerId
        lastX = x
        downX = x
        downY = y
        swipeShown = false
        swipeArmed = false
        optionsAction = null
        repeatFired = false
        longPressFired = false
        spaceSwipeActive = false
        backspaceSwipeActive = false
        lastStepX = x
        lastBackspaceCount = 0
        val bounds = findBounds(x, y)
        pressedKey = bounds?.key
        pressedRect = bounds?.rect
        val key = bounds?.key
        // 高亮立刻改指新键（连击时上一键的延迟清除不会误伤：clearHighlight 已撤销）
        handler.removeCallbacks(clearHighlight)
        highlightKey = key
        highlightFrames = 0
        highlightAt = android.os.SystemClock.uptimeMillis()
        if (key != null) {
            feedback(key)
            if (previewEnabled && !key.isSpecial && key.action is KeyAction.Text) {
                popupManager.showPreview(bounds.rect, displayLabel(key))
            }
            if (key.action == KeyAction.Backspace) {
                repeating = true
                handler.postDelayed(repeatRunnable, longPressDelayMs.toLong())
            }
            if (popupChars(key).isNotEmpty() || hasLongPressAction(key.action)) {
                handler.postDelayed(longPressRunnable, longPressDelayMs.toLong())
            }
        }
        invalidate()
    }

    private fun movePress(x: Float, y: Float) {
        val key = pressedKey ?: return
        lastX = x
        if (popupManager.isPopupShown) {
            if (optionsAction != null && !optionsMoved) {
                if (kotlin.math.abs(x - optionsAnchorX) < touchSlopPx) return
                optionsMoved = true
            }
            popupManager.updatePopupSelection(x)
            return
        }
        val dy = downY - y
        val dx = x - downX

        // 空格横向滑动：移动光标（步进制，进入后不再出空格）
        if (key.action == KeyAction.Space && spaceSwipeEnabled) {
            if (!spaceSwipeActive && kotlin.math.abs(dx) > swipeActivatePx) {
                spaceSwipeActive = true
                cancelTimers()
                popupManager.dismissAll()
                lastStepX = x
            }
            if (spaceSwipeActive) {
                val steps = ((x - lastStepX) / cursorStepPx).toInt()
                if (steps != 0) {
                    listener?.onCursorMove(steps)
                    lastStepX += steps * cursorStepPx
                }
                return
            }
        }

        // 退格左滑：扩选删除（实时选中光标前 N 字符）
        if (key.action == KeyAction.Backspace && backspaceSwipeEnabled) {
            val dxLeft = downX - x
            if (!backspaceSwipeActive && dxLeft > swipeActivatePx) {
                backspaceSwipeActive = true
                cancelTimers()
            }
            if (backspaceSwipeActive) {
                val count = (dxLeft / cursorStepPx).toInt().coerceAtLeast(0)
                if (count != lastBackspaceCount) {
                    listener?.onBackspaceSelect(count)
                    lastBackspaceCount = count
                }
                return
            }
        }

        // 上滑副字符：往上一动就从键位长出一体式气泡（上半副字符、下半主字符），过阈值副字符
        // 变强调色，抬手才上屏——上滑途中看得见要出什么，滑过头了退回来还能反悔
        val swipeChar = key.swipeFor(englishMode)
        if (swipeSymbolsEnabled && swipeChar != null) {
            if (!swipeShown && dy > swipeActivatePx && dy > kotlin.math.abs(dx)) {
                val rect = pressedRect ?: return
                swipeShown = true
                cancelTimers()
                popupManager.showSwipeBubble(rect, displayLabel(key), Punct.display(swipeChar, chinesePunct))
            }
            if (swipeShown) {
                val armed = dy > swipeThresholdPx
                if (armed != swipeArmed) {
                    swipeArmed = armed
                    popupManager.setSwipeArmed(armed)
                    if (armed) KeyHaptics.tap(this)
                }
                return
            }
        }
        // 滑出键位即取消（滑动输入为二期能力）
        if (findKey(x, y) !== key && dy < swipeThresholdPx / 2) {
            cancelPress()
        }
    }

    /** 当前手指抬起（或被下一指顶替）：按本次按压的状态决定发什么 */
    private fun finishPress() {
        optionsAction?.let { action ->
            val index = popupManager.popupSelectedIndex()?.takeIf { it != optionsInitial }
            optionsAction = null
            cancelPress()
            listener?.onLongPressOption(action, index)
            return
        }
        val key = pressedKey
        val swipeChar = if (swipeArmed) key?.swipeFor(englishMode) else null
        val popupChoice = popupManager.popupSelectedIndex()
            ?.let { i -> key?.let { popupChars(it).getOrNull(i) } }
        val skipTap = repeatFired || longPressFired // 连删/长按触发过则不补单击
        val wasSpaceSwipe = spaceSwipeActive
        val wasBackspaceSwipe = backspaceSwipeActive
        cancelPress()
        when {
            swipeChar != null -> listener?.onKeyAction(KeyAction.Text(swipeChar), shifted = false)
            wasSpaceSwipe -> Unit
            wasBackspaceSwipe -> listener?.onBackspaceSwipeDelete()
            // 大写字母按「Shift + 小写」发：与 ⇧ 单次态走同一条路（直接上屏大写）
            popupChoice != null && popupChoice.isUpperCase() ->
                listener?.onKeyAction(KeyAction.Text(popupChoice.lowercaseChar()), shifted = true)
            popupChoice != null ->
                listener?.onKeyAction(KeyAction.Text(popupChoice), shifted = false)
            key != null && !skipTap -> handleKey(key)
        }
    }

    /** 按下反馈：震动 + 按键音，各自受开关控制 */
    private fun feedback(key: KeyDef) {
        KeyHaptics.tap(this)
        if (soundEnabled) {
            // 系统自带四种键音按键型分发（空格/退格/回车有各自的音色），与系统键盘一致
            val fx = when (key.action) {
                KeyAction.Space -> android.media.AudioManager.FX_KEYPRESS_SPACEBAR
                KeyAction.Backspace -> android.media.AudioManager.FX_KEYPRESS_DELETE
                KeyAction.Enter -> android.media.AudioManager.FX_KEYPRESS_RETURN
                else -> android.media.AudioManager.FX_KEYPRESS_STANDARD
            }
            // -1 = 跟随系统触摸音量
            audioManager.playSoundEffect(fx, -1f)
        }
    }

    private fun repeatHaptic() {
        pressedKey?.let { feedback(it) }
    }

    private fun cancelTimers() {
        repeating = false
        handler.removeCallbacks(repeatRunnable)
        handler.removeCallbacks(longPressRunnable)
    }

    /**
     * 清手势状态；高亮另按最小可见时长延迟清除（见 [highlightKey]）。
     * 已满时长的直接清，未满的补足剩余毫秒——快点慢点都恰好亮一下。
     */
    private fun clearPressed() {
        pressedKey = null
        pressedRect = null
        if (highlightKey != null) {
            val shown = android.os.SystemClock.uptimeMillis() - highlightAt
            if (shown >= MIN_HIGHLIGHT_MS) {
                finishHighlight()
            } else {
                handler.postDelayed(clearHighlight, MIN_HIGHLIGHT_MS - shown)
            }
        }
        invalidate()
    }

    private fun cancelPress() {
        cancelTimers()
        popupManager.dismissAll()
        clearPressed()
    }

    /**
     * 命中的键。落在键缝或占位空白里时，归同一行水平距离最近的键——第 2 行缩进的
     * 半个键位、键与键之间的 3dp 缝都不该是「点空」。
     */
    private fun findBounds(x: Float, y: Float): KeyBounds? {
        keyBounds.firstOrNull { it.rect.contains(x, y) }?.let { return it }
        val row = (y / rowHeightPx).toInt()
        return keyBounds
            .filter { it.row == row }
            .minByOrNull { kotlin.math.abs(it.rect.centerX() - x) }
    }

    private fun findKey(x: Float, y: Float): KeyDef? = findBounds(x, y)?.key

    /**
     * 自动首字母大写（英文模式句首 / 输入框开头，由 Service 按 `getCursorCapsMode` 判定）。
     * 给的是**单次** Shift：键面变大写，打一个字母后复位，和用户按一次 ⇧ 一样。
     * 条件消失（删回了句中、挪了光标）时只撤回自己给的那次，用户手按的 ⇧ 不动。
     */
    fun setAutoCaps(on: Boolean) {
        if (shiftLocked || layer != KeyboardLayer.LETTERS) return
        when {
            on && !shifted -> {
                shifted = true
                autoShifted = true
            }
            !on && autoShifted -> {
                shifted = false
                autoShifted = false
            }
            else -> return
        }
        invalidate()
    }

    /**
     * 切换布局层（EditorInfo 场景感知 / 符号键共用入口）。
     * [returnTo]：进数字键盘时记下「←」回哪一层。
     */
    fun setLayer(newLayer: KeyboardLayer, returnTo: KeyboardLayer = KeyboardLayer.LETTERS) {
        if (newLayer == KeyboardLayer.NUMBER) numberReturnLayer = returnTo
        if (layer == newLayer) return
        layer = newLayer
        shifted = false
        shiftLocked = false
        autoShifted = false
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

    // ── 无障碍 ──────────────────────────────────────────────────────
    // 键是 Canvas 画的，系统看不见：每个键报成一个虚拟节点（读屏念键名、双击即按），
    // uiautomator 也因此能按文字找到键。按下走 [handleKey]，与手指点按同一条路。

    private val a11y = object : androidx.customview.widget.ExploreByTouchHelper(this) {
        override fun getVirtualViewAt(x: Float, y: Float): Int =
            keyBounds.indexOfFirst { it.rect.contains(x, y) }.takeIf { it >= 0 } ?: INVALID_ID

        override fun getVisibleVirtualViews(ids: MutableList<Int>) {
            keyBounds.indices.forEach(ids::add)
        }

        override fun onPopulateNodeForVirtualView(id: Int, node: androidx.core.view.accessibility.AccessibilityNodeInfoCompat) {
            val kb = keyBounds.getOrNull(id)
            if (kb == null) {
                // 键位刚重排、旧 id 失效：给个空壳，ExploreByTouchHelper 要求边界非空
                node.contentDescription = ""
                node.setBoundsInParent(android.graphics.Rect(0, 0, 1, 1))
                return
            }
            node.className = android.widget.Button::class.java.name
            node.contentDescription = a11yLabel(kb.key)
            node.text = a11yLabel(kb.key)
            val r = android.graphics.Rect()
            kb.rect.roundOut(r)
            node.setBoundsInParent(r)
            node.addAction(androidx.core.view.accessibility.AccessibilityNodeInfoCompat.ACTION_CLICK)
        }

        override fun onPerformActionForVirtualView(id: Int, action: Int, arguments: android.os.Bundle?): Boolean {
            if (action != androidx.core.view.accessibility.AccessibilityNodeInfoCompat.ACTION_CLICK) return false
            val key = keyBounds.getOrNull(id)?.key ?: return false
            handleKey(key)
            invalidate()
            invalidateVirtualView(id)
            return true
        }
    }

    init {
        androidx.core.view.ViewCompat.setAccessibilityDelegate(this, a11y)
        a11yAttached = true
    }

    override fun dispatchHoverEvent(event: MotionEvent): Boolean =
        a11y.dispatchHoverEvent(event) || super.dispatchHoverEvent(event)

    /** 读屏念的键名：功能键说它做什么，字符键念键面上的那个字 */
    private fun a11yLabel(key: KeyDef): String = when (key.action) {
        KeyAction.Shift -> when {
            splitMode -> "分词"
            shiftLocked -> "大写锁定"
            shifted -> "上档，已开"
            else -> "上档"
        }
        KeyAction.Backspace -> "删除"
        KeyAction.Space -> "空格"
        KeyAction.SwitchLang -> "中英切换"
        KeyAction.Emoji -> "表情"
        KeyAction.SymbolPage -> "符号"
        KeyAction.NumberPad -> "数字键盘"
        else -> displayLabel(key)
    }

    private fun handleKey(key: KeyDef) {
        when (key.action) {
            KeyAction.Shift -> {
                if (splitMode) {
                    listener?.onKeyAction(KeyAction.Text(SPLIT_CHAR), shifted = false)
                    return
                }
                val now = android.os.SystemClock.uptimeMillis()
                autoShifted = false
                when {
                    shiftLocked -> {
                        shiftLocked = false
                        shifted = false
                    }
                    // 双击（300ms 内二连击）→ 大写锁定
                    shifted && now - lastShiftTapTime < 300 -> shiftLocked = true
                    else -> shifted = !shifted
                }
                lastShiftTapTime = now
                invalidate()
            }
            KeyAction.SwitchSymbols -> {
                setLayer(
                    when (layer) {
                        KeyboardLayer.LETTERS -> KeyboardLayer.SYMBOLS
                        KeyboardLayer.SYMBOLS -> KeyboardLayer.LETTERS
                        KeyboardLayer.NUMBER -> numberReturnLayer
                    },
                )
            }
            KeyAction.NumberPad -> setLayer(KeyboardLayer.NUMBER, returnTo = layer)
            else -> {
                // 逗号句号合一键：英文模式点按改出句点
                val action = key.textFor(englishMode)
                    ?.takeIf { it != (key.action as? KeyAction.Text)?.char }
                    ?.let { KeyAction.Text(it) }
                    ?: key.action
                listener?.onKeyAction(action, shifted || shiftLocked)
                // 单次 Shift：出字后自动复位（锁定态保持）
                if (shifted && !shiftLocked && key.action is KeyAction.Text) {
                    shifted = false
                    autoShifted = false
                    invalidate()
                }
            }
        }
    }
}
