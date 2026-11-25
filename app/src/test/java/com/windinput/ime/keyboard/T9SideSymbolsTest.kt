package com.windinput.ime.keyboard

import org.junit.Assert.assertEquals
import org.junit.Test

class T9SideSymbolsTest {

    @Test
    fun `missing key falls back to Chinese punctuation`() {
        assertEquals(T9SideSymbols.DEFAULTS, T9SideSymbols.parse(null))
        assertEquals("，", T9SideSymbols.DEFAULTS.first())
    }

    @Test
    fun `empty string means cleared`() {
        assertEquals(emptyList<String>(), T9SideSymbols.parse(""))
    }

    @Test
    fun `stored list round trips`() {
        val list = listOf("。", "，", "……")
        assertEquals(list, T9SideSymbols.parse(NumberSideSymbols.format(list)))
    }

    @Test
    fun `defaults are independent of number pad defaults`() {
        assertEquals(NumberSideSymbols.DEFAULTS, NumberSideSymbols.parse(null))
    }
}
