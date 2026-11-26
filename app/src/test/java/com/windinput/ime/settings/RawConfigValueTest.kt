package com.windinput.ime.settings

import org.junit.Assert.assertEquals
import org.junit.Test
import uniffi.wind_android.ConfigValue

/** 页面「恢复默认」把出厂快照里的值原样写回：摊平后的类型要按注册表类型转对 */
class RawConfigValueTest {

    @Test
    fun scalarsFollowRegistryType() {
        assertEquals(ConfigValue.Bool(true), rawConfigValue("bool", true))
        assertEquals(ConfigValue.Int(3), rawConfigValue("int", 3L))
        assertEquals(ConfigValue.Float(30.0), rawConfigValue("float", 30L))
        assertEquals(ConfigValue.Float(0.5), rawConfigValue("float", 0.5))
        assertEquals(ConfigValue.Str("auto"), rawConfigValue("enum", "auto"))
    }

    @Test
    fun listsStayLists() {
        assertEquals(ConfigValue.StrList(listOf("semicolon", "quote")), rawConfigValue("str_list", listOf("semicolon", "quote")))
        assertEquals(ConfigValue.StrList(emptyList()), rawConfigValue("str_list", emptyList<String>()))
    }
}
