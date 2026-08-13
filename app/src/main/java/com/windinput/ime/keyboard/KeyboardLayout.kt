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
)

enum class KeyboardLayer { LETTERS, SYMBOLS }

object KeyboardLayouts {

    private fun charKeys(chars: String, swipes: String = ""): List<KeyDef> =
        chars.mapIndexed { i, c ->
            val swipe = swipes.getOrNull(i)
            KeyDef(
                c.toString(),
                KeyAction.Text(c),
                swipeUp = swipe,
                longPress = swipe?.toString() ?: "",
            )
        }

    private fun bottomRow(switchLabel: String): List<KeyDef> = listOf(
        KeyDef(switchLabel, KeyAction.SwitchSymbols, widthWeight = 1.5f, isSpecial = true),
        KeyDef("，", KeyAction.Text('，'), longPress = "、！？：；"),
        KeyDef("空格", KeyAction.Space, widthWeight = 4f),
        KeyDef("。", KeyAction.Text('。'), longPress = "…～·—％"),
        KeyDef("⏎", KeyAction.Enter, widthWeight = 1.5f, isSpecial = true),
    )

    /** 内置 qwerty 主布局（26 键，上滑/长按副字符） */
    val qwerty: List<List<KeyDef>> = listOf(
        charKeys("qwertyuiop", "1234567890"),
        charKeys("asdfghjkl", "@#$%&-+()"),
        buildList {
            add(KeyDef("⇧", KeyAction.Shift, widthWeight = 1.5f, isSpecial = true))
            addAll(charKeys("zxcvbnm", "*\"':;!?"))
            add(KeyDef("⌫", KeyAction.Backspace, widthWeight = 1.5f, isSpecial = true))
        },
        bottomRow("?123"),
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

    fun rows(layer: KeyboardLayer): List<List<KeyDef>> = when (layer) {
        KeyboardLayer.LETTERS -> qwerty
        KeyboardLayer.SYMBOLS -> symbols
    }
}
