package com.windinput.ime.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class LayoutParserTest {

    private fun weights(row: List<KeyDef>) = row.map { it.widthWeight }

    @Test
    fun spacerAcceptsFloatAndIntegerWidth() {
        val rows = LayoutParser.parse(
            """
            [[rows]]
            key = [ { spacer = 0.5 }, { chars = "ab" }, { spacer = 1 } ]
            """.trimIndent(),
        )
        assertEquals(listOf(KeyAction.Spacer, KeyAction.Text('a'), KeyAction.Text('b'), KeyAction.Spacer), rows[0].map { it.action })
        assertEquals(listOf(0.5f, 1f, 1f, 1f), weights(rows[0]))
    }

    @Test
    fun letterKeysHaveSwipeButNoLongPress() {
        val key = LayoutParser.parse("[[rows]]\nkeys = \"q\"\nswipe = \"1\"")[0][0]
        assertEquals('1', key.swipeUp)
        assertEquals("", key.longPress)
    }

    @Test
    fun symbolKeysUseSwipeAsLongPress() {
        val key = LayoutParser.parse("[[rows]]\nkeys = \",\"\nswipe = \".\"")[0][0]
        assertEquals(".", key.longPress)
    }

    @Test
    fun combinedPunctKeySwitchesOutputByMode() {
        val key = LayoutParser.parse(
            """
            [[rows]]
            key = [ { text = ",", swipe_up = ".", en_text = ".", en_swipe_up = "'" } ]
            """.trimIndent(),
        )[0][0]
        assertEquals(',', key.textFor(english = false))
        assertEquals('.', key.swipeFor(english = false))
        assertEquals('.', key.textFor(english = true))
        assertEquals('\'', key.swipeFor(english = true))
    }

    @Test
    fun functionKeyHasNoText() {
        val key = LayoutParser.parse("[[rows]]\nkey = [ { label = \"换行\", action = \"enter\", width = 2 } ]")[0][0]
        assertEquals(KeyAction.Enter, key.action)
        assertEquals(2f, key.widthWeight)
        assertNull(key.textFor(english = true))
    }

    /** 随包布局与代码内置布局必须一致：TOML 解析失败时回退内置，两者不同步会让用户看到两套键盘 */
    @Test
    fun bundledQwertyMatchesBuiltin() {
        val bundled = LayoutParser.parse(File("src/main/assets/data/layouts/qwerty.toml").readText())
        val builtin = KeyboardLayouts.qwerty
        assertEquals(builtin.size, bundled.size)
        for (i in builtin.indices) {
            assertEquals("row $i actions", builtin[i].map { it.action }, bundled[i].map { it.action })
            assertEquals("row $i weights", weights(builtin[i]), weights(bundled[i]))
            assertEquals("row $i swipe", builtin[i].map { it.swipeUp }, bundled[i].map { it.swipeUp })
            assertEquals("row $i longPress", builtin[i].map { it.longPress }, bundled[i].map { it.longPress })
            assertEquals("row $i enText", builtin[i].map { it.enText }, bundled[i].map { it.enText })
        }
    }

    /** 大众布局：每行总权重 10，第 2 行键宽与第 1 行相同（两侧各缩进半键） */
    @Test
    fun qwertyRowsAllSumToTen() {
        for ((i, row) in KeyboardLayouts.qwerty.withIndex()) {
            assertEquals("row $i", 10f, row.sumOf { it.widthWeight.toDouble() }.toFloat(), 0.001f)
        }
    }
}
