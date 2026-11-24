package com.windinput.ime.settings

import com.windinput.ime.keyboard.KeyAction
import com.windinput.ime.keyboard.KeyDef
import com.windinput.ime.keyboard.keymap.Gesture
import com.windinput.ime.keyboard.keymap.GestureOverride
import com.windinput.ime.keyboard.keymap.KeyOutput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeymapEditModelTest {
    @Test
    fun outputRoundTripPerKind() {
        for ((kind, input) in listOf(
            EditKind.TEXT to "《",
            EditKind.TEXT to "……",
            EditKind.TEMPLATE to "今天{date_cn}",
            EditKind.PHRASE to "rq",
            EditKind.FUNC to "paste",
        )) {
            val o = KeymapEdit.output(kind, input)
            assertEquals(kind, KeymapEdit.kindOf(o))
            assertEquals(input, KeymapEdit.inputOf(o))
        }
    }

    @Test
    fun emptyInputNoneAndUnknownFuncGiveNoOverride() {
        assertNull(KeymapEdit.output(EditKind.NONE, "x"))
        assertNull(KeymapEdit.output(EditKind.TEXT, ""))
        assertNull(KeymapEdit.output(EditKind.FUNC, "format_disk"))
        assertNull(KeymapEdit.output(EditKind.FUNC, "history")) // 白名单里有但未实现
    }

    @Test
    fun funcChoicesAreAllImplementedAndLabelled() {
        for (f in KeymapEdit.FUNC_CHOICES) {
            assertTrue(f, f in com.windinput.ime.keyboard.keymap.KeymapMerge.IMPLEMENTED_FUNCS)
            assertTrue(f, com.windinput.ime.keyboard.keymap.KeymapMerge.funcLabel(f) != f)
        }
    }

    @Test
    fun bubbleStringIsPerCharDedupedAndTrimmed() {
        val items = KeymapEdit.bubbleFromString(" 《 》，《 ")
        assertEquals(listOf("《", "》", "，"), items.map { KeymapEdit.inputOf(it) })
        assertEquals("《》，", KeymapEdit.bubbleToString(items))
        assertTrue(KeymapEdit.bubbleFromString("  ").isEmpty())
    }

    @Test
    fun withGestureAddsRemovesAndPrunesEmptyKeys() {
        var e = emptyMap<String, Map<Gesture, GestureOverride>>()
        e = KeymapEdit.withGesture(e, "z", Gesture.SWIPE_UP, GestureOverride(output = KeyOutput.Text("《")))
        e = KeymapEdit.withGesture(e, "z", Gesture.SWIPE_DOWN, GestureOverride(output = KeyOutput.Func("cut")))
        assertEquals(setOf(Gesture.SWIPE_UP, Gesture.SWIPE_DOWN), e.getValue("z").keys)
        e = KeymapEdit.withGesture(e, "z", Gesture.SWIPE_UP, null)
        assertEquals(setOf(Gesture.SWIPE_DOWN), e.getValue("z").keys)
        e = KeymapEdit.withGesture(e, "z", Gesture.SWIPE_DOWN, null)
        assertTrue(e.isEmpty())
    }

    @Test
    fun editableKeys() {
        assertTrue(KeymapEdit.editable(KeyDef("z", KeyAction.Text('z'), id = "z")))
        assertTrue(KeymapEdit.editable(KeyDef("空格", KeyAction.Space, id = "space")))
        assertFalse(KeymapEdit.editable(KeyDef("⌫", KeyAction.Backspace, id = "backspace")))
        assertFalse(KeymapEdit.editable(KeyDef("", KeyAction.Spacer, id = "spacer")))
        assertFalse(KeymapEdit.editable(KeyDef("z", KeyAction.Text('z'))))
    }
}
