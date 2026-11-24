package com.windinput.ime.keyboard.keymap

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime

class KeyTemplateTest {
    // 2026-11-24 是星期二
    private val t = LocalDateTime.of(2026, 11, 4, 9, 5, 7)

    @Test
    fun placeholdersExpand() {
        assertEquals("2026-11-04", KeyTemplate.render("{date}", t))
        assertEquals("2026年11月4日", KeyTemplate.render("{date_cn}", t))
        assertEquals("09:05", KeyTemplate.render("{time}", t))
        assertEquals("09:05:07", KeyTemplate.render("{time_s}", t))
        assertEquals("2026-11-04 09:05", KeyTemplate.render("{datetime}", t))
        assertEquals("11/04", KeyTemplate.render("{month}/{day_2}", t))
        assertEquals("11/04", KeyTemplate.render("{month_2}/{day_2}", t))
    }

    @Test
    fun weekdayIsChinese() {
        assertEquals("星期三", KeyTemplate.render("{weekday}", t)) // 2026-11-04 周三
        assertEquals("星期二", KeyTemplate.render("{weekday}", LocalDateTime.of(2026, 11, 24, 0, 0)))
    }

    @Test
    fun literalTextAndUnknownPlaceholdersAreKept() {
        assertEquals("今天是2026-11-04。", KeyTemplate.render("今天是{date}。", t))
        assertEquals("{nope}", KeyTemplate.render("{nope}", t))
        assertEquals("无占位符", KeyTemplate.render("无占位符", t))
    }

    @Test
    fun labelUsesChineseNameForSinglePlaceholder() {
        assertEquals("日期", KeyTemplate.label("{date}"))
        assertEquals("时间", KeyTemplate.label("{time}"))
        assertEquals("日期时间", KeyTemplate.label("{datetime}"))
        assertEquals("短文本", KeyTemplate.label("短文本"))
        assertEquals("很长的模…", KeyTemplate.label("很长的模板文字内容"))
        assertEquals("abcd…", KeyTemplate.label("abcdefg"))
    }
}
