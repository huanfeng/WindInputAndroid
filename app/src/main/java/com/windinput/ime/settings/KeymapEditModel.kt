package com.windinput.ime.settings

import com.windinput.ime.keyboard.KeyAction
import com.windinput.ime.keyboard.KeyDef
import com.windinput.ime.keyboard.keymap.Gesture
import com.windinput.ime.keyboard.keymap.GestureOverride
import com.windinput.ime.keyboard.keymap.KeyOutput
import com.windinput.ime.keyboard.keymap.KeymapMerge

/** 单键编辑页里一行的「类型」 */
internal enum class EditKind(val label: String) {
    NONE("不改"),
    TEXT("字符 / 文本"),
    TEMPLATE("日期时间"),
    PHRASE("短语"),
    FUNC("功能"),
}

/**
 * 「自定义键位」页的编辑模型：界面状态与 [GestureOverride] 之间的换算。纯函数，JVM 单测直接跑；
 * 页面本身（Compose）只管摆控件。
 */
internal object KeymapEdit {

    /** 功能下拉的可选项（= 已实现的白名单，按常用次序） */
    val FUNC_CHOICES = listOf(
        "select_all", "cut", "copy", "paste", "undo", "redo", "para_start", "para_end",
        "clipboard", "symbol_page", "punct_toggle", "one_hand", "candidate_2",
    ).also { check(KeymapMerge.IMPLEMENTED_FUNCS.containsAll(it)) }

    /** 模板的常用写法（输入框里也可以自己拼，如 `今天是{date_cn}`） */
    val TEMPLATE_CHOICES = listOf("{date}", "{date_cn}", "{time}", "{datetime}", "{weekday}")

    /** 常用符号快捷条：手机上不用手敲中文标点 */
    const val QUICK_SYMBOLS = "《》〔〕「」『』·—…（）、"

    /** 能编辑的键：字符键与空格 / 中英等功能键；退格有自己的手势机，占位空白不是键 */
    fun editable(key: KeyDef): Boolean =
        key.id.isNotEmpty() && key.action != KeyAction.Spacer && key.action != KeyAction.Backspace

    fun kindOf(o: KeyOutput?): EditKind = when (o) {
        null -> EditKind.NONE
        is KeyOutput.Char, is KeyOutput.Text -> EditKind.TEXT
        is KeyOutput.Template -> EditKind.TEMPLATE
        is KeyOutput.Phrase -> EditKind.PHRASE
        is KeyOutput.Func -> EditKind.FUNC
    }

    fun inputOf(o: KeyOutput?): String = when (o) {
        null -> ""
        is KeyOutput.Char -> o.c.toString()
        is KeyOutput.Text -> o.text
        is KeyOutput.Template -> o.template
        is KeyOutput.Phrase -> o.id
        is KeyOutput.Func -> o.name
    }

    /** 类型 + 输入 → 输出；空输入、不在白名单的功能名都得 null（= 这一行不覆盖） */
    fun output(kind: EditKind, input: String): KeyOutput? {
        if (kind == EditKind.NONE || input.isEmpty()) return null
        return when (kind) {
            EditKind.NONE -> null
            EditKind.TEXT -> KeyOutput.Text(input)
            EditKind.TEMPLATE -> KeyOutput.Template(input)
            EditKind.PHRASE -> KeyOutput.Phrase(input)
            EditKind.FUNC -> if (input in FUNC_CHOICES) KeyOutput.Func(input) else null
        }
    }

    /** 长按气泡：输入框里每个字符一项（气泡就是按字符排的）；空白去掉、重复只留一个 */
    fun bubbleFromString(s: String): List<KeyOutput> =
        s.filter { !it.isWhitespace() && !it.isSurrogate() }.toList().distinct().map { KeyOutput.Text(it.toString()) }

    fun bubbleToString(items: List<KeyOutput>): String = items.joinToString("") { inputOf(it) }

    /** 改一键一个手势；[ov] 为 null 即恢复默认（去掉这条覆盖），键下没有手势了整键去掉 */
    fun withGesture(
        edits: Map<String, Map<Gesture, GestureOverride>>,
        keyId: String,
        gesture: Gesture,
        ov: GestureOverride?,
    ): Map<String, Map<Gesture, GestureOverride>> {
        val g = (edits[keyId].orEmpty()).toMutableMap()
        if (ov == null) g.remove(gesture) else g[gesture] = ov
        val out = edits.toMutableMap()
        if (g.isEmpty()) out.remove(keyId) else out[keyId] = g
        return out
    }
}
