package com.windinput.ime.keyboard.keymap

import com.windinput.ime.keyboard.KeyAction
import com.windinput.ime.keyboard.KeyDef
import com.windinput.ime.keyboard.KeyboardLayer
import com.windinput.ime.keyboard.KeyboardLayouts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class KeymapMergeTest {
    private val rows = KeyboardLayouts.qwerty

    private fun key(rows: List<List<KeyDef>>, id: String) = rows.flatten().first { it.id == id }

    private fun merge(toml: String, warns: MutableList<String> = mutableListOf()): List<List<KeyDef>> =
        KeymapMerge.apply(rows, KeyboardLayer.LETTERS, KeymapParser.parse(toml)) { warns += it }

    @Test
    fun noOverridesReturnsSameInstance() {
        assertSame(rows, KeymapMerge.apply(rows, KeyboardLayer.LETTERS, KeymapParseResult.EMPTY))
        // 只有别的层的覆盖：本层也原样
        val other = KeymapParser.parse("[layer.symbols.key.x1]\nswipe_up = \"a\"")
        assertSame(rows, KeymapMerge.apply(rows, KeyboardLayer.LETTERS, other))
    }

    @Test
    fun singleCharSwipeUpGoesThroughLegacyPath() {
        val m = merge("[layer.letters.key.z]\nswipe_up = { text = \"《\" }")
        val z = key(m, "z")
        assertEquals('《', z.swipeUp)
        assertNull(z.upOut)
        assertEquals('《', z.swipeFor(english = true))
        // 别的键不受影响
        assertEquals(key(rows, "x"), key(m, "x"))
    }

    @Test
    fun multiCharTextAndFuncUseUpOut() {
        val m = merge(
            """
            [layer.letters.key.z]
            swipe_up = { text = "……" }
            [layer.letters.key.x]
            swipe_up = { func = "paste" }
            """.trimIndent(),
        )
        assertEquals(KeyOutput.Text("……"), key(m, "z").upOut)
        assertNull(key(m, "z").swipeUp)
        assertEquals(KeyOutput.Func("paste"), key(m, "x").upOut)
    }

    @Test
    fun swipeDownBindsFuncOrChar() {
        val m = merge(
            """
            [layer.letters.key.x]
            swipe_down = { func = "cut" }
            [layer.letters.key.c]
            swipe_down = { char = "-" }
            """.trimIndent(),
        )
        assertEquals(KeyOutput.Func("cut"), key(m, "x").downOut)
        assertEquals(KeyOutput.Char('-'), key(m, "c").downOut)
        // 出厂布局没有下滑
        assertTrue(rows.flatten().all { it.downOut == null })
    }

    @Test
    fun longPressReplacesBubbleChars() {
        val m = merge("[layer.letters.key.comma]\nlong_press = [\"，\", \"；\", \"·\"]")
        assertEquals("，；·", key(m, "comma").longPress)
        val cleared = merge("[layer.letters.key.comma]\nlong_press = []")
        assertEquals("", key(cleared, "comma").longPress)
    }

    @Test
    fun multiCharBubbleItemIsSkippedWithWarning() {
        val warns = mutableListOf<String>()
        val m = merge("[layer.letters.key.comma]\nlong_press = [\"，\", \"……\"]", warns)
        assertEquals("，", key(m, "comma").longPress)
        assertTrue(warns.any { "单个字符" in it })
    }

    @Test
    fun historyFuncIsStillSkippedWithWarning() {
        val warns = mutableListOf<String>()
        val m = merge(
            """
            [layer.letters.key.z]
            swipe_up = { func = "history" }
            """.trimIndent(),
            warns,
        )
        assertEquals(key(rows, "z"), key(m, "z"))
        assertEquals(1, warns.size)
    }

    @Test
    fun templatePhraseAndNewFuncsAreAccepted() {
        val m = merge(
            """
            [layer.letters.key.z]
            swipe_up = { template = "{date}" }
            swipe_down = { phrase = "rq" }
            [layer.letters.key.x]
            swipe_up = { func = "punct_toggle" }
            swipe_down = { func = "one_hand" }
            """.trimIndent(),
        )
        assertEquals(KeyOutput.Template("{date}"), key(m, "z").upOut)
        assertEquals(KeyOutput.Phrase("rq"), key(m, "z").downOut)
        assertEquals(KeyOutput.Func("punct_toggle"), key(m, "x").upOut)
        assertEquals(KeyOutput.Func("one_hand"), key(m, "x").downOut)
    }

    @Test
    fun outputLabels() {
        assertEquals("日期", KeymapMerge.outputLabel(KeyOutput.Template("{date}")))
        assertEquals("rq", KeymapMerge.outputLabel(KeyOutput.Phrase("rq")))
        assertEquals("标点", KeymapMerge.outputLabel(KeyOutput.Func("punct_toggle")))
        assertEquals("次选", KeymapMerge.outputLabel(KeyOutput.Func("candidate_2")))
        assertEquals("《", KeymapMerge.outputLabel(KeyOutput.Text("《")))
    }

    @Test
    fun secondCandidateFillsOnlyEmptySlotsOfSpaceAndLangKey() {
        val filled = KeymapMerge.fillSecondCandidate(rows)
        val special = filled.flatten().filter { it.action == KeyAction.Space || it.action == KeyAction.SwitchLang }
        assertTrue(special.isNotEmpty())
        assertTrue(special.all { it.upOut == KeyOutput.Func("candidate_2") })
        // 其余键一个字节都不变
        val others = { l: List<List<KeyDef>> -> l.flatten().filter { it.action != KeyAction.Space && it.action != KeyAction.SwitchLang } }
        assertEquals(others(rows), others(filled))
    }

    @Test
    fun secondCandidateKeepsUserBoundSlot() {
        val bound = merge("[layer.letters.key.space]\nswipe_up = { func = \"paste\" }")
        val space = bound.flatten().first { it.action == KeyAction.Space }
        assertEquals(KeyOutput.Func("paste"), space.upOut)
        val filled = KeymapMerge.fillSecondCandidate(bound).flatten().first { it.action == KeyAction.Space }
        assertEquals(KeyOutput.Func("paste"), filled.upOut)
    }

    @Test
    fun backspaceAndUnknownIdsAreSkipped() {
        val warns = mutableListOf<String>()
        val bs = rows.flatten().first { it.action == KeyAction.Backspace }.id
        val m = merge(
            """
            [layer.letters.key.$bs]
            swipe_down = { func = "undo" }
            [layer.letters.key.nosuchkey]
            swipe_up = "a"
            """.trimIndent(),
            warns,
        )
        assertEquals(rows, m)
        assertEquals(2, warns.size)
    }

    @Test
    fun nonLetterBubbleFollowsOverriddenSwipeUp() {
        // 非字母键的上滑字符兼作长按气泡（布局解析器的约定）；覆盖上滑后气泡一并换掉
        val k = KeyDef("1", KeyAction.Text('1'), swipeUp = '!', longPress = "!", id = "k1")
        val r = KeymapParser.parse("[layer.symbols.key.k1]\nswipe_up = \"~\"")
        val nk = KeymapMerge.apply(listOf(listOf(k)), KeyboardLayer.SYMBOLS, r).flatten().first()
        assertEquals('~', nk.swipeUp)
        assertEquals("~", nk.longPress)
    }
}
