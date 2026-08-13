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
)

object KeyboardLayouts {

    private fun charKeys(chars: String): List<KeyDef> =
        chars.map { KeyDef(it.toString(), KeyAction.Text(it)) }

    /** 内置 qwerty 主布局（26 键） */
    val qwerty: List<List<KeyDef>> = listOf(
        charKeys("qwertyuiop"),
        charKeys("asdfghjkl"),
        buildList {
            add(KeyDef("⇧", KeyAction.Shift, widthWeight = 1.5f, isSpecial = true))
            addAll(charKeys("zxcvbnm"))
            add(KeyDef("⌫", KeyAction.Backspace, widthWeight = 1.5f, isSpecial = true))
        },
        listOf(
            KeyDef("?123", KeyAction.SwitchSymbols, widthWeight = 1.5f, isSpecial = true),
            KeyDef("，", KeyAction.Text(','), widthWeight = 1f),
            KeyDef("空格", KeyAction.Space, widthWeight = 4f),
            KeyDef("。", KeyAction.Text('.'), widthWeight = 1f),
            KeyDef("⏎", KeyAction.Enter, widthWeight = 1.5f, isSpecial = true),
        ),
    )
}
