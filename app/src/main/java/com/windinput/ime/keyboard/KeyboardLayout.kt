package com.windinput.ime.keyboard

/**
 * 键位模型与内置 qwerty 布局。
 *
 * 占位实现：布局暂以代码内置；后续迁移为 TOML 布局定义文件
 * （与配置文件家族格式统一，见 docs/architecture.md §4.1）。
 */
sealed interface KeyAction {
    /** 字符键：小写形态，Shift 态由视图层换算 */
    data class Text(val char: Char) : KeyAction
    data object Backspace : KeyAction
    data object Enter : KeyAction
    data object Space : KeyAction
    data object Shift : KeyAction
    data object SwitchSymbols : KeyAction

    /**
     * 中英切换（底栏，回车左侧）。
     *
     * 点击 = 在中文方案与**英文方案**之间切换。用英文方案而非「系统英文模式」是有意的：
     * 后者只是把字母直通给宿主、没有候选，而移动端打英文很依赖词库补全。
     * 长按 = 打开快捷面板做其它切换（方案 / 标点 / 全半角 / 简繁）。
     */
    data object SwitchLang : KeyAction

    /** 打开表情面板（符号层底栏） */
    data object Emoji : KeyAction

    /**
     * 占位空白：不绘制、不响应。用于第 2 行两侧各缩进半个键（大众布局）。
     * 落在空白里的触点归最近的键（见 KeyboardView.findBounds），边缘不会点空。
     */
    data object Spacer : KeyAction
}

data class KeyDef(
    val label: String,
    val action: KeyAction,
    /** 相对宽度权重，同一行内按权重分配 */
    val widthWeight: Float = 1f,
    /** 特殊键（功能键）用不同底色 */
    val isSpecial: Boolean = false,
    /** 上滑输出的副字符（右上角小字提示） */
    val swipeUp: Char? = null,
    /** 长按气泡字符集（每字符一项，横移选择）；空 = 无气泡 */
    val longPress: String = "",
    /** 英文模式下改输出的字符（`，。`合一键：中文点按是逗号，英文是句点）；null = 同 [action] */
    val enText: Char? = null,
    /** 英文模式下的上滑字符；null = 同 [swipeUp] */
    val enSwipeUp: Char? = null,
) {
    /** 按当前模式取点按输出的字符；非字符键为 null */
    fun textFor(english: Boolean): Char? =
        if (english && enText != null) enText else (action as? KeyAction.Text)?.char

    /** 按当前模式取上滑字符 */
    fun swipeFor(english: Boolean): Char? = if (english && enSwipeUp != null) enSwipeUp else swipeUp
}

enum class KeyboardLayer { LETTERS, SYMBOLS, NUMBER }

/**
 * 键盘尺寸（dp）：横竖屏由 Service 按配置注入（twinInt 模式，
 * 见 docs/android-ime-features.md §10 建议 3）。面板高度随之联动。
 */
object KeyboardDimens {
    var rowHeightDp: Int = 56
}

object KeyboardLayouts {

    /**
     * 字符键批量展开。字母键的长按气泡由视图按「小写 / 大写」自动生成（主流做法），
     * 这里只给非字母键挂上滑字符兼长按。
     */
    private fun charKeys(chars: String, swipes: String = ""): List<KeyDef> =
        chars.mapIndexed { i, c ->
            val swipe = swipes.getOrNull(i)
            KeyDef(
                c.toString(),
                KeyAction.Text(c),
                swipeUp = swipe,
                longPress = if (c.isLetter()) "" else swipe?.toString() ?: "",
            )
        }

    private val halfSpacer = KeyDef("", KeyAction.Spacer, widthWeight = 0.5f)

    /**
     * 字母层底栏，按豆包 / 微信的大众布局（docs/android-ime-features.md §14.1）：
     * `123`(2) · `，。`合一(1) · 空格(4) · 中英(1) · 回车(2)，总权重 10 与字母行对齐。
     * **标点一律写 ASCII**（中英形态由 [Punct] 按模式派生，见该文件说明）。
     */
    private val lettersBottomRow: List<KeyDef> = listOf(
        KeyDef("123", KeyAction.SwitchSymbols, widthWeight = 2f, isSpecial = true),
        KeyDef(
            ",", KeyAction.Text(','),
            swipeUp = '.', longPress = ".,?!:;", enText = '.', enSwipeUp = '\'',
        ),
        KeyDef("空格", KeyAction.Space, widthWeight = 4f),
        KeyDef("中英", KeyAction.SwitchLang, isSpecial = true),
        KeyDef("换行", KeyAction.Enter, widthWeight = 2f, isSpecial = true),
    )

    /** 符号层底栏（Stage 2 重做 123 层前沿用旧形态；表情入口在这里） */
    private fun bottomRow(switchLabel: String): List<KeyDef> = listOf(
        KeyDef(switchLabel, KeyAction.SwitchSymbols, widthWeight = 1.3f, isSpecial = true),
        KeyDef("☺", KeyAction.Emoji, isSpecial = true),
        KeyDef(",", KeyAction.Text(','), longPress = "\\!?:;"),
        KeyDef("空格", KeyAction.Space, widthWeight = 3.4f),
        KeyDef(".", KeyAction.Text('.'), longPress = "^~`_%"),
        // 中英切换固定在回车左侧——主流输入法的位置约定，肌肉记忆在这
        KeyDef("中/英", KeyAction.SwitchLang, isSpecial = true),
        KeyDef("⏎", KeyAction.Enter, widthWeight = 1.3f, isSpecial = true),
    )

    /** 内置 qwerty 主布局（26 键，上滑/长按副字符） */
    val qwerty: List<List<KeyDef>> = listOf(
        charKeys("qwertyuiop", "1234567890"),
        listOf(halfSpacer) + charKeys("asdfghjkl", "@#$%&-+()") + listOf(halfSpacer),
        buildList {
            add(KeyDef("⇧", KeyAction.Shift, widthWeight = 1.5f, isSpecial = true))
            addAll(charKeys("zxcvbnm", "*\"':;!?"))
            add(KeyDef("⌫", KeyAction.Backspace, widthWeight = 1.5f, isSpecial = true))
        },
        lettersBottomRow,
    )

    /** 符号/数字层（占位集合，正式键位随布局 TOML 化调整） */
    val symbols: List<List<KeyDef>> = listOf(
        charKeys("1234567890"),
        charKeys("@#%&*-+=()"),
        buildList {
            add(KeyDef("~", KeyAction.Text('~'), widthWeight = 1.5f))
            addAll(charKeys("!?:;/'\""))
            add(KeyDef("⌫", KeyAction.Backspace, widthWeight = 1.5f, isSpecial = true))
        },
        bottomRow("ABC"),
    )

    /** 数字/电话/日期框的计算器式键盘 */
    val number: List<List<KeyDef>> = listOf(
        listOf(
            KeyDef("1", KeyAction.Text('1')),
            KeyDef("2", KeyAction.Text('2')),
            KeyDef("3", KeyAction.Text('3')),
            KeyDef("⌫", KeyAction.Backspace, isSpecial = true),
        ),
        listOf(
            KeyDef("4", KeyAction.Text('4')),
            KeyDef("5", KeyAction.Text('5')),
            KeyDef("6", KeyAction.Text('6')),
            KeyDef("-", KeyAction.Text('-')),
        ),
        listOf(
            KeyDef("7", KeyAction.Text('7')),
            KeyDef("8", KeyAction.Text('8')),
            KeyDef("9", KeyAction.Text('9')),
            KeyDef(".", KeyAction.Text('.')),
        ),
        listOf(
            KeyDef("+", KeyAction.Text('+')),
            KeyDef("0", KeyAction.Text('0')),
            KeyDef(",", KeyAction.Text(',')),
            KeyDef("⏎", KeyAction.Enter, isSpecial = true),
        ),
    )

    fun rows(layer: KeyboardLayer): List<List<KeyDef>> = when (layer) {
        KeyboardLayer.LETTERS -> qwerty
        KeyboardLayer.SYMBOLS -> symbols
        KeyboardLayer.NUMBER -> number
    }
}
