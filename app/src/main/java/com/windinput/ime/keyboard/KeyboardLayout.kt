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

    /** 打开表情面板（123 层底栏） */
    data object Emoji : KeyAction

    /**
     * 原样上屏，**不经引擎**。用于九宫格数字键盘和 123 层的半角句点：那里要的就是
     * 键面上那个字符，中文模式下也不能被引擎转成「。」，有编码时数字也不能变成选词。
     */
    data class Commit(val text: String) : KeyAction

    /** 打开分类符号页（123 层、数字键盘的「符号」键） */
    data object SymbolPage : KeyAction

    /** 切到九宫格数字键盘（123 层的「1234」键） */
    data object NumberPad : KeyAction

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
    /** 纵向占几行（数字键盘的「换行」占两行）；下面那一行同一位置要放等宽的占位空白 */
    val rowSpan: Int = 1,
    /** 强调色键面（123 层、数字键盘左下角的返回键，两家都用强调色） */
    val isAccent: Boolean = false,
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
     * 字母层底栏，按豆包默认布局（真机截图，docs/android-ime-features.md §14.12）：
     * 符号 · 123 · 逗号 · 空格 · 句号 · 中英 · 回车，宽度按实测比例，总权重 10 与字母行对齐。
     * 逗号、句号各占一键：中文里最常用的两个标点，不该藏在合一键的上滑里。
     * **标点一律写 ASCII**（中英形态由 [Punct] 按模式派生，见该文件说明）。
     */
    private val lettersBottomRow: List<KeyDef> = listOf(
        KeyDef("符号", KeyAction.SymbolPage, widthWeight = 1.35f, isSpecial = true),
        KeyDef("123", KeyAction.SwitchSymbols, widthWeight = 1.15f, isSpecial = true),
        KeyDef(",", KeyAction.Text(','), longPress = ",;:、"),
        KeyDef("空格", KeyAction.Space, widthWeight = 2.95f),
        KeyDef(".", KeyAction.Text('.'), longPress = ".?!…"),
        KeyDef("中英", KeyAction.SwitchLang, widthWeight = 1.15f, isSpecial = true),
        KeyDef("换行", KeyAction.Enter, widthWeight = 1.4f, isSpecial = true),
    )

    /** 内置 qwerty 主布局（26 键，上滑/长按副字符） */
    val qwerty: List<List<KeyDef>> = listOf(
        charKeys("qwertyuiop", "1234567890"),
        listOf(halfSpacer) + charKeys("asdfghjkl", "-/:;()~\"'") + listOf(halfSpacer),
        buildList {
            add(KeyDef("⇧", KeyAction.Shift, widthWeight = 1.5f, isSpecial = true))
            addAll(charKeys("zxcvbnm", "@`#\\?!^"))
            add(KeyDef("⌫", KeyAction.Backspace, widthWeight = 1.5f, isSpecial = true))
        },
        lettersBottomRow,
    )

    /**
     * 123 层，按两家的形态（docs/android-ime-features.md §14.2）。标点一律写 ASCII，
     * 中文模式下显示「：（）…“」等中文形态。第 3 行最后一个是**半角句点**（原样上屏，
     * 打小数用）；英文模式下它改出分号，免得一行里两个句点。
     */
    val symbols: List<List<KeyDef>> = listOf(
        charKeys("1234567890"),
        buildList {
            addAll(charKeys("-/:~()"))
            add(KeyDef("^", KeyAction.Text('^'), enText = '_'))
            addAll(charKeys("@\"'"))
        },
        buildList {
            add(KeyDef("符号", KeyAction.SymbolPage, widthWeight = 1.5f, isSpecial = true))
            add(KeyDef(".", KeyAction.Text('.')))
            add(KeyDef(",", KeyAction.Text(',')))
            add(KeyDef("\\", KeyAction.Text('\\'), enText = '\''))
            addAll(charKeys("?!"))
            add(KeyDef(".", KeyAction.Commit("."), enText = ';'))
            add(KeyDef("⌫", KeyAction.Backspace, widthWeight = 1.5f, isSpecial = true))
        },
        listOf(
            KeyDef("←", KeyAction.SwitchSymbols, widthWeight = 1.5f, isSpecial = true, isAccent = true),
            KeyDef("☺", KeyAction.Emoji, isSpecial = true),
            KeyDef("1234", KeyAction.NumberPad, widthWeight = 1.5f, isSpecial = true),
            KeyDef("空格", KeyAction.Space, widthWeight = 4f),
            KeyDef("换行", KeyAction.Enter, widthWeight = 2f, isSpecial = true),
        ),
    )

    private fun digit(c: Char) = KeyDef(c.toString(), KeyAction.Commit(c.toString()), widthWeight = 1.4f)
    private val sideSpacer = KeyDef("", KeyAction.Spacer)

    /**
     * 九宫格数字键盘（§14.4）。左列前三行是**占位空白**：那里由视图画可滚动的符号列
     * （见 KeyboardView.sideSymbols）；「换行」占两行，第 4 行同一位置是等宽占位。
     * 数字和句点都原样上屏，不经引擎。
     */
    val number: List<List<KeyDef>> = listOf(
        listOf(sideSpacer, digit('1'), digit('2'), digit('3'),
            KeyDef("⌫", KeyAction.Backspace, isSpecial = true)),
        listOf(sideSpacer, digit('4'), digit('5'), digit('6'),
            KeyDef("空格", KeyAction.Space, isSpecial = true)),
        listOf(sideSpacer, digit('7'), digit('8'), digit('9'),
            KeyDef("换行", KeyAction.Enter, isSpecial = true, rowSpan = 2)),
        listOf(
            KeyDef("←", KeyAction.SwitchSymbols, isSpecial = true, isAccent = true),
            KeyDef("符号", KeyAction.SymbolPage, widthWeight = 1.4f, isSpecial = true),
            digit('0'),
            KeyDef(".", KeyAction.Commit("."), widthWeight = 1.4f),
            sideSpacer,
        ),
    )

    fun rows(layer: KeyboardLayer): List<List<KeyDef>> = when (layer) {
        KeyboardLayer.LETTERS -> qwerty
        KeyboardLayer.SYMBOLS -> symbols
        KeyboardLayer.NUMBER -> number
    }
}
