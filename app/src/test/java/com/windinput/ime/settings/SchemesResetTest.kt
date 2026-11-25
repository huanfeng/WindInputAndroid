package com.windinput.ime.settings

import org.junit.Assert.assertEquals
import org.junit.Test
import uniffi.wind_android.SchemeFormInfo
import uniffi.wind_android.SchemeFormSet

class SchemesResetTest {

    private class Io(private val forms: Map<String, Pair<String, String>>) : SchemeFormIo {
        val resets = mutableMapOf<String, List<String>>()
        override fun load(id: String) = forms.getValue(id).let { (eff, def) -> SchemeFormInfo(eff, def, "", emptyList()) }
        override fun apply(id: String, sets: List<SchemeFormSet>, resets: List<String>) {
            this.resets[id] = resets
        }
    }

    @Test
    fun resetsOnlyChangedKeysAndBehaviorOfEveryScheme() {
        val io = Io(
            mapOf(
                "pinyin" to ("\"schema.pinyin.a\" = 1\n\"schema.pinyin.b\" = true" to "\"schema.pinyin.a\" = 2\n\"schema.pinyin.b\" = true"),
                "wubi86" to ("\"schema.codetable.c\" = 1" to "\"schema.codetable.c\" = 1"),
            ),
        )
        val behavior = mutableListOf<String>()
        val changed = SchemesReset.resetAll(listOf("pinyin", "wubi86"), io) { behavior += it }
        assertEquals(mapOf("pinyin" to listOf("schema.pinyin.a")), io.resets)
        assertEquals("自定义行为每个方案都清", listOf("pinyin", "wubi86"), behavior)
        assertEquals(1, changed)
    }

    @Test
    fun oneSchemeFailingDoesNotStopOthers() {
        val io = Io(mapOf("ok" to ("\"schema.pinyin.a\" = 1" to "\"schema.pinyin.a\" = 2")))
        val changed = SchemesReset.resetAll(listOf("missing", "ok"), io) { }
        assertEquals(listOf("schema.pinyin.a"), io.resets["ok"])
        assertEquals(1, changed)
    }
}
