package com.windinput.ime.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.wind_android.ConfigValue
import uniffi.wind_android.SchemeFormInfo
import uniffi.wind_android.SchemeFormSet

class SchemeFormValuesTest {

    /** 假的核心：记下每次写入，写后按 sets / resets 改 effective */
    private class FakeIo(effective: Map<String, String>, private val defaults: Map<String, String>) : SchemeFormIo {
        val eff = effective.toMutableMap()
        val calls = mutableListOf<Pair<List<SchemeFormSet>, List<String>>>()
        override fun load(id: String) = SchemeFormInfo(toml(eff), toml(defaults), "\"schema.pinyin.english_merge.exact_position\" = 1\n", emptyList())
        override fun apply(id: String, sets: List<SchemeFormSet>, resets: List<String>) {
            calls += sets to resets
            for (s in sets) eff[s.key] = when (val v = s.value) {
                is ConfigValue.Bool -> v.v.toString()
                is ConfigValue.Int -> v.v.toString()
                is ConfigValue.Str -> "\"${v.v}\""
                is ConfigValue.Float -> v.v.toString()
                else -> error("unexpected")
            }
            for (k in resets) eff[k] = defaults.getValue(k)
        }
        private fun toml(m: Map<String, String>) = m.entries.joinToString("\n") { "\"${it.key}\" = ${it.value}" }
    }

    private val types = mapOf(
        "schema.pinyin.frequency.enabled" to "bool",
        "schema.pinyin.sentence_count" to "int",
        "schema.pinyin.separator" to "enum",
        "schema.pinyin.frequency.half_life" to "float",
    )

    private fun item(key: String, type: String, options: List<Pair<String, String>> = emptyList(), source: SettingSource = SettingSource.ENGINE) =
        SettingItem("scheme_form", "调频", key, source, type, key.substringAfterLast('.'), "说明", null, options = options, schemes = listOf("pinyin"))

    private val freq = item("schema.pinyin.frequency.enabled", "toggle")
    private val count = item("schema.pinyin.sentence_count", "int")
    private val sep = item("schema.pinyin.separator", "select", listOf("auto" to "自动", "'" to "单引号"))

    private fun values(eff: Map<String, String>, def: Map<String, String> = eff): Pair<SchemeFormValues, FakeIo> {
        val io = FakeIo(eff, def)
        return SchemeFormValues("pinyin", io, types::get, global = null).also { it.load() } to io
    }

    @Test
    fun setToDefaultSendsReset() {
        val (v, io) = values(mapOf(freq.key to "false"), mapOf(freq.key to "true"))
        assertNull(v.set(freq, true))
        assertEquals(listOf(emptyList<SchemeFormSet>() to listOf(freq.key)), io.calls)
        assertTrue(v.bool(freq))
    }

    /** 清单按整数滑块写（Int 30），方案默认是浮点（30.0）：按值相等，仍是恢复 */
    @Test
    fun floatDefaultMatchesIntValue() {
        val half = item("schema.pinyin.frequency.half_life", "int")
        val (v, io) = values(mapOf(half.key to "45.0"), mapOf(half.key to "30.0"))
        assertNull(v.set(half, 30))
        assertEquals(listOf(emptyList<SchemeFormSet>() to listOf(half.key)), io.calls)
    }

    @Test
    fun setOtherSendsSet() {
        val (v, io) = values(mapOf(count.key to "3"))
        assertNull(v.set(count, 5))
        assertEquals(listOf(SchemeFormSet(count.key, ConfigValue.Int(5))), io.calls.single().first)
        assertEquals(5, v.int(count))
    }

    @Test
    fun summaryNamesDefaultWhenChanged() {
        val (v, _) = values(
            mapOf(sep.key to "\"'\"", freq.key to "true", count.key to "3"),
            mapOf(sep.key to "\"auto\"", freq.key to "true", count.key to "5"),
        )
        assertEquals("说明\n已自定义 · 默认：自动", v.summary(sep))
        assertEquals("说明", v.summary(freq))
        assertEquals("说明\n已自定义 · 默认：5", v.summary(count))
        assertEquals("'", v.choice(sep))
    }

    @Test
    fun resetAllResetsOnlyChangedKeys() {
        val (v, io) = values(
            mapOf(freq.key to "false", count.key to "3"),
            mapOf(freq.key to "true", count.key to "3"),
        )
        assertEquals(1, v.changedCount(listOf(freq, count)))
        assertNull(v.resetAll(listOf(freq, count)))
        assertEquals(listOf(freq.key), io.calls.single().second)
        assertEquals(0, v.changedCount(listOf(freq, count)))
    }

    @Test
    fun presetsComeFromBuiltin() {
        val (v, _) = values(mapOf(count.key to "3"))
        assertEquals(mapOf("schema.pinyin.english_merge.exact_position" to 1L), v.presets())
    }

    @Test
    fun androidItemsDelegateToGlobal() {
        val autoCap = item("ui.android.auto_cap", "toggle", source = SettingSource.ANDROID)
        val written = mutableListOf<Any>()
        val global = object : ItemValues {
            override val version = 0
            override fun available(item: SettingItem) = true
            override fun visible(item: SettingItem, all: List<SettingItem>) = true
            override fun bool(item: SettingItem) = true
            override fun int(item: SettingItem) = 0
            override fun text(item: SettingItem) = ""
            override fun choice(item: SettingItem) = ""
            override fun summary(item: SettingItem) = "全局"
            override fun set(item: SettingItem, value: Any): String? { written += value; return null }
        }
        val io = FakeIo(emptyMap(), emptyMap())
        val v = SchemeFormValues("english", io, types::get, global).also { it.load() }
        assertTrue(v.available(autoCap))
        assertTrue(v.bool(autoCap))
        assertEquals("全局", v.summary(autoCap))
        assertNull(v.set(autoCap, false))
        assertEquals(listOf<Any>(false), written)
        assertTrue("键盘项不经方案写口", io.calls.isEmpty())
    }

    @Test
    fun missingKeyIsNotAvailable() {
        val (v, _) = values(mapOf(freq.key to "true"))
        assertTrue(v.available(freq))
        assertTrue(!v.available(count))
    }
}
