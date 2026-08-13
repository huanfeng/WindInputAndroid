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
    )

    fun rows(layer: KeyboardLayer): List<List<KeyDef>> {
        val id = layerToId.getValue(layer)
        val file = dirs.resolveDataFile("layouts/$id.toml")
        if (file != null) {
            try {
                val parsed = parse(file.readText())
                if (parsed.isNotEmpty()) return parsed
                Log.w(TAG, "layout $id empty, fallback to builtin")
            } catch (e: Exception) {
                Log.w(TAG, "layout $id parse failed, fallback to builtin", e)
            }
        }
        return KeyboardLayouts.rows(layer)
    }

    /** 解析布局 TOML，格式见 assets/data/layouts/qwerty.toml 头部注释 */
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

    private fun parseRow(row: TomlTable): List<KeyDef> {
        row.getString("keys")?.let { keys ->
            // 位置对齐的简写副字符串：swipe 第 i 位 = 第 i 键的上滑字符（兼作长按气泡）
            val swipes = row.getString("swipe") ?: ""
            return keys.mapIndexed { i, c ->
                val swipe = swipes.getOrNull(i)
                KeyDef(
                    c.toString(),
                    KeyAction.Text(c),
                    swipeUp = swipe,
                    longPress = swipe?.toString() ?: "",
                )
            }
        }
        val keyArray = row.getArray("key")
            ?: throw IllegalArgumentException("row 缺少 keys/key 字段")
        return parseKeys(keyArray)
    }

    private fun parseKeys(keys: TomlArray): List<KeyDef> = buildList {
        for (i in 0 until keys.size()) {
            val entry = keys.getTable(i)
            val width = entry.getDouble("width")?.toFloat() ?: 1f
            val longPress = entry.getString("long_press") ?: ""
            val swipeUp = entry.getString("swipe_up")?.firstOrNull()

            val chars = entry.getString("chars")
            if (chars != null) {
                val swipes = entry.getString("swipe") ?: ""
                chars.forEachIndexed { ci, c ->
                    val swipe = swipes.getOrNull(ci)
                    add(
                        KeyDef(
                            c.toString(),
                            KeyAction.Text(c),
                            width,
                            swipeUp = swipe,
                            longPress = swipe?.toString() ?: "",
                        ),
                    )
                }
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
                else -> throw IllegalArgumentException("未知 action: $name")
            }
            val label = entry.getString("label")
                ?: throw IllegalArgumentException("功能键缺少 label")
            add(KeyDef(label, action, width, isSpecial = true))
        }
    }
}
