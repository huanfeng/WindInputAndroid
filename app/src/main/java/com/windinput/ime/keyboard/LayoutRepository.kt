package com.windinput.ime.keyboard

import android.util.Log
import com.windinput.ime.core.DataDirs
import org.tomlj.Toml
import org.tomlj.TomlArray
import org.tomlj.TomlTable

/**
 * 布局仓库：TOML 布局文件解析 + 三级覆盖解析。
 *
 * 解析顺序（与桌面 resolve_data_file 同语义，失败静默回退）：
 *   filesDir/user/layouts/{id}.toml → filesDir/data/layouts/{id}.toml → 代码内置。
 */
class LayoutRepository(private val dirs: DataDirs) {

    private companion object {
        const val TAG = "LayoutRepository"
    }

    private val layerToId = mapOf(
        KeyboardLayer.LETTERS to "qwerty",
        KeyboardLayer.SYMBOLS to "symbols",
        KeyboardLayer.NUMBER to "number",
    )

    fun rows(layer: KeyboardLayer): List<List<KeyDef>> {
        val id = layerToId.getValue(layer)
        val file = dirs.resolveDataFile("layouts/$id.toml")
        if (file != null) {
            try {
                val parsed = LayoutParser.parse(file.readText())
                if (parsed.isNotEmpty()) return parsed
                Log.w(TAG, "layout $id empty, fallback to builtin")
            } catch (e: Exception) {
                Log.w(TAG, "layout $id parse failed, fallback to builtin", e)
            }
        }
        return KeyboardLayouts.rows(layer)
    }
}

/**
 * 布局 TOML 解析，格式见 assets/data/layouts/qwerty.toml 头部注释。
 * 独立成纯函数对象：不依赖 Context，可在 JVM 单测里直接跑。
 */
object LayoutParser {

    fun parse(toml: String): List<List<KeyDef>> {
        val result = Toml.parse(toml)
        if (result.hasErrors()) {
            throw IllegalArgumentException(
                "TOML errors: " + result.errors().joinToString { it.toString() },
            )
        }
        val rowsArray = result.getArray("rows") ?: return emptyList()
        return (0 until rowsArray.size()).map { i ->
            parseRow(rowsArray.getTable(i))
        }
    }

    /**
     * 字符键。字母键不挂长按：视图按「小写 / 大写」自动生成气泡（主流做法），
     * 上滑字符仍保留；非字母键的上滑字符兼作长按气泡。
     */
    private fun charKey(c: Char, swipe: Char?, width: Float = 1f): KeyDef = KeyDef(
        c.toString(),
        KeyAction.Text(c),
        width,
        swipeUp = swipe,
        longPress = if (c.isLetter()) "" else swipe?.toString() ?: "",
    )

    /** 宽度类数值：TOML 里 `1` 是整数、`0.5` 是浮点，两种都收 */
    private fun number(t: TomlTable, key: String): Float? =
        (t.get(key) as? Number)?.toFloat()

    private fun parseRow(row: TomlTable): List<KeyDef> {
        row.getString("keys")?.let { keys ->
            // 位置对齐的简写副字符串：swipe 第 i 位 = 第 i 键的上滑字符
            val swipes = row.getString("swipe") ?: ""
            return keys.mapIndexed { i, c -> charKey(c, swipes.getOrNull(i)) }
        }
        val keyArray = row.getArray("key")
            ?: throw IllegalArgumentException("row 缺少 keys/key 字段")
        return parseKeys(keyArray)
    }

    private fun parseKeys(keys: TomlArray): List<KeyDef> = buildList {
        for (i in 0 until keys.size()) {
            val entry = keys.getTable(i)

            number(entry, "spacer")?.let { w ->
                add(KeyDef("", KeyAction.Spacer, w))
                continue
            }

            val width = number(entry, "width") ?: 1f
            val rowSpan = number(entry, "row_span")?.toInt() ?: 1
            val accent = entry.getBoolean("accent") ?: false
            val longPress = entry.getString("long_press") ?: ""
            val swipeUp = entry.getString("swipe_up")?.firstOrNull()

            val chars = entry.getString("chars")
            if (chars != null) {
                val swipes = entry.getString("swipe") ?: ""
                chars.forEachIndexed { ci, c -> add(charKey(c, swipes.getOrNull(ci), width)) }
                continue
            }

            val commit = entry.getString("commit")
            if (commit != null) {
                add(
                    KeyDef(
                        entry.getString("label") ?: commit,
                        KeyAction.Commit(commit),
                        width,
                        enText = entry.getString("en_text")?.firstOrNull(),
                        rowSpan = rowSpan,
                    ),
                )
                continue
            }

            val text = entry.getString("text")
            if (text != null) {
                val ch = text.first()
                add(
                    KeyDef(
                        entry.getString("label") ?: text,
                        KeyAction.Text(ch),
                        width,
                        swipeUp = swipeUp,
                        longPress = longPress,
                        enText = entry.getString("en_text")?.firstOrNull(),
                        enSwipeUp = entry.getString("en_swipe_up")?.firstOrNull(),
                    ),
                )
                continue
            }

            val action = when (val name = entry.getString("action")) {
                "shift" -> KeyAction.Shift
                "backspace" -> KeyAction.Backspace
                "enter" -> KeyAction.Enter
                "space" -> KeyAction.Space
                "switch_symbols" -> KeyAction.SwitchSymbols
                "switch_lang" -> KeyAction.SwitchLang
                "emoji" -> KeyAction.Emoji
                "symbol_page" -> KeyAction.SymbolPage
                "number_pad" -> KeyAction.NumberPad
                else -> throw IllegalArgumentException("未知 action: $name")
            }
            val label = entry.getString("label")
                ?: throw IllegalArgumentException("功能键缺少 label")
            // 功能键缺省灰底；空格缺省画成字母键的白底（豆包 / 微信都是白空格）。`special` 显式覆盖：
            // 九宫格里的小空格是灰的功能键。此前这里一律 isSpecial，字母层的长空格成了灰键
            val special = entry.getBoolean("special") ?: (action != KeyAction.Space)
            add(KeyDef(label, action, width, isSpecial = special, rowSpan = rowSpan, isAccent = accent))
        }
    }
}
