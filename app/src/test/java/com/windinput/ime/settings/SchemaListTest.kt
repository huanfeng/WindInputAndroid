package com.windinput.ime.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import uniffi.wind_android.InstalledSchemaInfo

class SchemaListTest {

    private fun s(id: String, locked: Boolean = false, enabled: Boolean = true) =
        InstalledSchemaInfo(id, id, enabled, locked, false, true)

    @Test
    fun rowSummary() {
        assertEquals("正在使用", schemaRowSummary(s("pinyin"), activeId = "pinyin"))
        assertEquals("中英键切换要用，不能关", schemaRowSummary(s("english", locked = true), activeId = "pinyin"))
        assertEquals("正在使用 · 中英键切换要用，不能关", schemaRowSummary(s("english", locked = true), activeId = "english"))
        assertNull(schemaRowSummary(s("wubi86"), activeId = "pinyin"))
    }

    @Test
    fun homeSummary() {
        assertEquals("正在使用：全拼 · 已启用 3 个", schemaHomeSummary("全拼", 3))
        assertEquals("已启用 3 个", schemaHomeSummary(null, 3))
    }

    @Test
    fun schemeRouteRoundTrip() {
        assertEquals("pinyin", schemeIdOf(schemeSub("pinyin")))
        assertNull(schemeIdOf("模糊音"))
    }
}
