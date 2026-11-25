package com.windinput.ime.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class T9AvailabilityTest {

    @Test
    fun defaultsByType() {
        assertTrue("没设过：全拼开", T9Availability.enabled(null, "pinyin", "pinyin"))
        assertFalse("没设过：双拼关", T9Availability.enabled(null, "shuangpin", "shuangpin"))
    }

    @Test
    fun importedPinyinDefaultsOn() {
        assertTrue(T9Availability.enabled(null, "my_pinyin", "pinyin"))
        assertFalse("类型未知（核心没给）按关", T9Availability.enabled(null, "x", null))
    }

    @Test
    fun explicitListWins() {
        assertFalse(T9Availability.enabled("shuangpin", "pinyin", "pinyin"))
        assertTrue(T9Availability.enabled("shuangpin", "shuangpin", "shuangpin"))
        assertFalse("写过空串 = 都关", T9Availability.enabled("", "pinyin", "pinyin"))
    }

    @Test
    fun firstToggleMaterializesDefaults() {
        val capable = linkedMapOf("pinyin" to "pinyin", "shuangpin" to "shuangpin", "my_pinyin" to "pinyin")
        assertEquals("pinyin,my_pinyin,shuangpin", T9Availability.toggled(null, capable, "shuangpin", true))
        assertEquals("my_pinyin", T9Availability.toggled(null, capable, "pinyin", false))
        assertEquals("pinyin", T9Availability.toggled("pinyin,pinyin", capable, "pinyin", true))
    }
}
