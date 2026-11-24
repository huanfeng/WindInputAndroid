package com.windinput.ime.keyboard.keymap

/**
 * 把「自定义键位」页的编辑结果写成 keymap.toml 格式（与 [KeymapParser] 互逆）。纯函数。
 *
 * 只输出差异：没有任何覆盖的键不出现。输出稳定（层、键位、手势按固定次序），便于同步与比对。
 */
object KeymapWriter {
    private const val HEADER = "# 由「设置 → 键盘 → 自定义键位」生成，页面会整份重写它；\n" +
        "# 想手写 / 保留自己的注释，请改 keymap.toml（它的优先级更高，不会被页面改动）。\n"

    fun write(layers: Map<String, Map<String, Map<Gesture, GestureOverride>>>): String {
        val sb = StringBuilder(HEADER).append("version = 1\n")
        for (layer in KeymapParser.LAYERS.sorted()) {
            val keys = layers[layer] ?: continue
            for ((keyId, gestures) in keys.toSortedMap()) {
                val lines = Gesture.entries.mapNotNull { g ->
                    val ov = gestures[g] ?: return@mapNotNull null
                    gestureLine(g, ov)
                }
                if (lines.isEmpty()) continue
                sb.append("\n[layer.").append(layer).append(".key.").append(bareOrQuoted(keyId)).append("]\n")
                lines.forEach { sb.append(it).append('\n') }
            }
        }
        return sb.toString()
    }

    private fun gestureLine(g: Gesture, ov: GestureOverride): String? = when (g) {
        Gesture.TAP -> null
        Gesture.LONG_PRESS -> "long_press = [" + ov.bubble.joinToString(", ") { output(it) } + "]"
        else -> ov.output?.let { "${g.toml} = ${output(it)}" }
    }

    private fun output(o: KeyOutput): String = when (o) {
        is KeyOutput.Char -> "{ char = ${quote(o.c.toString())} }"
        is KeyOutput.Text -> "{ text = ${quote(o.text)} }"
        is KeyOutput.Template -> "{ template = ${quote(o.template)} }"
        is KeyOutput.Phrase -> "{ phrase = ${quote(o.id)} }"
        is KeyOutput.Func -> "{ func = ${quote(o.name)} }"
    }

    private fun bareOrQuoted(id: String): String =
        if (id.isNotEmpty() && id.all { it.isLetterOrDigit() || it == '_' || it == '-' }) id else quote(id)

    /** TOML 基本字符串转义（控制字符、引号、反斜杠） */
    fun quote(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\t' -> sb.append("\\t")
                c == '\r' -> sb.append("\\r")
                c < ' ' -> sb.append(String.format("\\u%04x", c.code))
                else -> sb.append(c)
            }
        }
        return sb.append('"').toString()
    }
}
