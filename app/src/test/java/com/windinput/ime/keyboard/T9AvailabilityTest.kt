package com.windinput.ime.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class T9AvailabilityTest {

    @Test
    fun defaultsByType() {
        assertTrue("没设过：全拼开", T9Availability.enabled(null, null, "pinyin", "pinyin"))
        assertFalse("没设过：双拼关", T9Availability.enabled(null, null, "shuangpin", "shuangpin"))
        assertFalse("类型未知（核心没给）按关", T9Availability.enabled(null, null, "x", null))
    }

    @Test
    fun explicitChoiceWins() {
        assertFalse(T9Availability.enabled(null, "pinyin", "pinyin", "pinyin"))
        assertTrue(T9Availability.enabled("shuangpin", null, "shuangpin", "shuangpin"))
    }

    /** 拨过别的方案之后，新导入的全拼方案仍按类型默认开（规格 §5） */
    @Test
    fun laterImportedPinyinStillDefaultsOn() {
        val (on, off) = T9Availability.toggled(null, null, "pinyin", false)
        assertTrue(T9Availability.enabled(on, off, "my_pinyin", "pinyin"))
        assertFalse(T9Availability.enabled(on, off, "pinyin", "pinyin"))
    }

    @Test
    fun toggleMovesBetweenLists() {
        val (on1, off1) = T9Availability.toggled("a", "b", "b", true)
        assertEquals("a,b", on1)
        assertEquals("", off1)
        val (on2, off2) = T9Availability.toggled(on1, off1, "a", false)
        assertEquals("b", on2)
        assertEquals("a", off2)
    }

    /**
     * 键盘侧「撤销九宫格记录」只认用户明确的选择或已知类型的默认：核心忙时类型读不到（null），
     * 不能当成「关了」把刚选的九宫格撤掉
     */
    @Test
    fun unknownTypeIsNotDisabled() {
        assertFalse(T9Availability.disabled(null, null, "pinyin", null))
        assertTrue(T9Availability.disabled(null, "pinyin", "pinyin", null))
        assertTrue("双拼没开过 = 默认关", T9Availability.disabled(null, null, "shuangpin", "shuangpin"))
        assertFalse(T9Availability.disabled("shuangpin", null, "shuangpin", null))
    }
}
