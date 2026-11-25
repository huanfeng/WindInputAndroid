package com.windinput.ime.keyboard

import org.junit.Assert.assertEquals
import org.junit.Test

/** 九宫格编码键的长按气泡：左大写、中数字（初始选中）、右小写，同全键盘字母键 */
class T9LongPressTest {

    private fun key(label: String) =
        KeyboardLayouts.t9.flatten().first { it.label == label }

    @Test
    fun `three letter key puts digit in the middle`() {
        val k = key("ABC")
        assertEquals("ABC2abc", k.popupChars(english = false))
        assertEquals('2', k.popupChars(false)[k.popupInitialIndex(false)])
    }

    @Test
    fun `four letter keys also start on the digit`() {
        for ((label, digit) in listOf("PQRS" to '7', "WXYZ" to '9')) {
            val k = key(label)
            assertEquals(label + digit + label.lowercase(), k.popupChars(false))
            assertEquals(digit, k.popupChars(false)[k.popupInitialIndex(false)])
        }
    }

    @Test
    fun `separator key bubble is just the digit`() {
        val k = key("分词")
        assertEquals("1", k.popupChars(false))
        assertEquals(0, k.popupInitialIndex(false))
    }
}
