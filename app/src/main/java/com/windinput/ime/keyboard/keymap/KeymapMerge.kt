package com.windinput.ime.keyboard.keymap

import com.windinput.ime.keyboard.KeyAction
import com.windinput.ime.keyboard.KeyDef
import com.windinput.ime.keyboard.KeyboardLayer

/**
 * 把用户键位覆盖（[KeymapParseResult]）合并进布局。纯函数、不依赖 Context。
 *
 * 合并后仍是 `List<List<KeyDef>>`，视图与 [com.windinput.ime.keyboard.LayoutTweaks] 不用改接口；
 * **没有覆盖时原样返回同一个对象**——不写 keymap.toml 的用户看到的键盘与此前逐项一致。
 * 认不出的键位 id 跳过并告警（沿用「认不出就放过」）。
 *
 * 阶段 1 的取舍：
 * - 单字符的上滑（`char` / 一个字的 `text`）直接写回 [KeyDef.swipeUp]，走原有的气泡、角标与引擎路径；
 *   多字符文本与功能动作落在 [KeyDef.upOut]；下滑只有 [KeyDef.downOut]。
 * - 长按气泡是「每字符一项」的字符串，所以气泡项只收单字符；多字符项跳过并告警。
 * - 未实现的 `func`（目前只有 history）跳过并告警；`template` / `phrase` 在阶段 2 已支持。
 * - 退格键有自己的手势机，覆盖一律不收。
 */
object KeymapMerge {

    /** 阶段 1 已实现的功能动作；白名单里其余名字（punct_toggle / one_hand / history）等阶段 2 */
    val IMPLEMENTED_FUNCS = setOf(
        "select_all", "cut", "copy", "paste", "undo", "redo", "para_start", "para_end", "clipboard", "symbol_page",
        "punct_toggle", "one_hand", "candidate_2",
    )
    // history（输入历史）没有现成的键盘侧入口（核心的「快捷输入历史」是候选来源，不是面板），仍留在白名单里但跳过并告警

    /** 功能动作在键帽角标上显示的短名 */
    fun funcLabel(name: String): String = when (name) {
        "select_all" -> "全选"
        "cut" -> "剪切"
        "copy" -> "复制"
        "paste" -> "粘贴"
        "undo" -> "撤销"
        "redo" -> "重做"
        "para_start" -> "行首"
        "para_end" -> "行末"
        "clipboard" -> "剪贴板"
        "symbol_page" -> "符号"
        "punct_toggle" -> "标点"
        "one_hand" -> "单手"
        "candidate_2" -> "次选"
        "history" -> "历史"
        else -> name
    }

    fun layerId(layer: KeyboardLayer): String = when (layer) {
        KeyboardLayer.LETTERS -> "letters"
        KeyboardLayer.SYMBOLS -> "symbols"
        KeyboardLayer.NUMBER -> "number"
    }

    fun apply(
        rows: List<List<KeyDef>>,
        layer: KeyboardLayer,
        result: KeymapParseResult,
        warn: (String) -> Unit = {},
    ): List<List<KeyDef>> {
        val id = layerId(layer)
        val overrides = result.layers[id] ?: return rows
        val used = HashSet<String>()
        val merged = rows.map { row ->
            row.map { key ->
                val g = overrides[key.id]
                if (g == null || key.id.isEmpty() || key.action == KeyAction.Spacer) {
                    key
                } else {
                    used += key.id
                    mergeKey(id, key, g, warn)
                }
            }
        }
        for (k in overrides.keys - used) warn("layer.$id 里的键位 $k 在当前布局找不到，已跳过")
        return merged
    }

    private fun mergeKey(layer: String, key: KeyDef, g: Map<Gesture, GestureOverride>, warn: (String) -> Unit): KeyDef {
        if (key.action == KeyAction.Backspace) {
            warn("layer.$layer.${key.id}：退格键有自己的手势，覆盖已跳过")
            return key
        }
        var out = key
        val where = "layer.$layer.${key.id}"
        g[Gesture.SWIPE_UP]?.output?.let { o ->
            val c = singleChar(o)
            out = when {
                c != null -> out.copy(
                    swipeUp = c, enSwipeUp = null, upOut = null,
                    // 非字母键的上滑字符兼作长按气泡（charKey 的约定）；覆盖后一并换掉，免得角标与气泡不一致
                    longPress = if (g[Gesture.LONG_PRESS] == null && key.longPress == key.swipeUp?.toString()) c.toString() else out.longPress,
                )
                supported(where, "swipe_up", o, warn) -> out.copy(swipeUp = null, enSwipeUp = null, upOut = o)
                else -> out
            }
        }
        g[Gesture.SWIPE_DOWN]?.output?.let { o ->
            if (singleChar(o) != null || supported(where, "swipe_down", o, warn)) out = out.copy(downOut = o)
        }
        g[Gesture.LONG_PRESS]?.let { ov ->
            val chars = StringBuilder()
            for (item in ov.bubble) {
                val c = singleChar(item)
                if (c != null) chars.append(c) else warn("$where.long_press：气泡项只支持单个字符，已跳过 $item")
            }
            if (chars.isNotEmpty() || ov.bubble.isEmpty()) out = out.copy(longPress = chars.toString())
        }
        if (g.containsKey(Gesture.TAP)) warn("$where：阶段 1 不支持改点按，已跳过")
        return out
    }

    /** Char / 单个字符的 Text → 字符，其余 null */
    private fun singleChar(o: KeyOutput): kotlin.Char? = when (o) {
        is KeyOutput.Char -> o.c
        is KeyOutput.Text -> if (o.text.length == 1) o.text[0] else null
        else -> null
    }

    /** 角标上显示的短名：文本原样、功能 / 模板 / 短语取各自的名字 */
    fun outputLabel(o: KeyOutput): String? = when (o) {
        is KeyOutput.Char -> o.c.toString()
        is KeyOutput.Text -> o.text
        is KeyOutput.Func -> funcLabel(o.name)
        is KeyOutput.Template -> KeyTemplate.label(o.template)
        is KeyOutput.Phrase -> o.id
    }

    /**
     * R7（开关打开时由 Service 调用）：空格 / 中英切换键的上滑本来是空槽，在预编辑时拿它选第二候选。
     * **只填空槽**：用户在 keymap.toml 里绑过的、或本来就有上滑字符的键不碰；没有编码时 Service 侧什么也不做。
     */
    fun fillSecondCandidate(rows: List<List<KeyDef>>): List<List<KeyDef>> = rows.map { row ->
        row.map { k ->
            if ((k.action == KeyAction.Space || k.action == KeyAction.SwitchLang) && k.upOut == null && k.swipeUp == null) {
                k.copy(upOut = KeyOutput.Func("candidate_2"))
            } else {
                k
            }
        }
    }

    /** 多字符 Text、Template、Phrase 与已实现的 Func 可用；未实现的 Func 告警跳过 */
    private fun supported(where: String, gesture: String, o: KeyOutput, warn: (String) -> Unit): Boolean = when (o) {
        is KeyOutput.Text, is KeyOutput.Template, is KeyOutput.Phrase -> true
        is KeyOutput.Func ->
            if (o.name in IMPLEMENTED_FUNCS) true else false.also { warn("$where.$gesture：功能 ${o.name} 暂未实现，已跳过") }
        else -> false.also { warn("$where.$gesture：该输出类型不支持，已跳过") }
    }
}
