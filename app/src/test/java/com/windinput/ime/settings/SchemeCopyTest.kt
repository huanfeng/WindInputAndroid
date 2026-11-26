package com.windinput.ime.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.wind_android.ConfigValue
import uniffi.wind_android.SchemeFormInfo
import uniffi.wind_android.SchemeFormSet
import java.io.File

class SchemeCopyTest {

    private val manifest by lazy {
        SettingsManifest.parse(File("src/main/assets/data/settings_manifest.toml").readText())
    }

    private val types = mapOf(
        "schema.pinyin.frequency.enabled" to "bool",
        "schema.pinyin.frequency.half_life" to "float",
        "schema.pinyin.shuangpin.allow_full_pinyin" to "bool",
        "schema.pinyin.separator" to "str",
    )

    private class Io(private val effective: String) : SchemeFormIo {
        val applied = mutableMapOf<String, List<SchemeFormSet>>()
        var failOn: String? = null
        override fun load(id: String) = SchemeFormInfo(effective, "", "", emptyList())
        override fun apply(id: String, sets: List<SchemeFormSet>, resets: List<String>) {
            if (id == failOn) throw IllegalStateException("坏文件")
            applied[id] = sets
        }
    }

    @Test
    fun pinyinAndShuangpinAreOneFamily() {
        assertEquals(SchemeCopy.family("pinyin"), SchemeCopy.family("shuangpin"))
        assertTrue(SchemeCopy.family("codetable") != SchemeCopy.family("pinyin"))
    }

    @Test
    fun blocksSkipAuxCodeAndKeyboardOnlyItems() {
        val blocks = SchemeCopy.blocks("pinyin", manifest)
        val names = blocks.map { it.first }
        assertFalse("辅助码按方案各配各的码表，不复制", "辅助码" in names)
        assertFalse("九宫格读法栏是键盘项，不按方案存", "九宫格键盘" in names)
        assertTrue("调频" in names)
        assertTrue(blocks.flatMap { it.second }.all { it.source == SettingSource.ENGINE })
    }

    @Test
    fun codetableBlocksIncludeSentenceTuning() {
        val keys = SchemeCopy.blocks("codetable", manifest).toMap()["整句输入（实验性）"].orEmpty().map { it.key }
        assertTrue("schema.codetable.sentence_count" in keys)
    }

    @Test
    fun shuangpinOnlyItemsAreNotWrittenToFullPinyin() {
        val items = SchemeCopy.blocks("shuangpin", manifest).flatMap { it.second }
        val eff = mapOf<String, Any>("schema.pinyin.shuangpin.allow_full_pinyin" to true, "schema.pinyin.separator" to "'")
        val toPinyin = SchemeCopy.sets(items, eff, "pinyin", types::get).map { it.key }
        assertEquals(listOf("schema.pinyin.separator"), toPinyin)
        val toShuangpin = SchemeCopy.sets(items, eff, "shuangpin", types::get).map { it.key }
        assertTrue("schema.pinyin.shuangpin.allow_full_pinyin" in toShuangpin)
    }

    @Test
    fun valuesKeepTheirCoreType() {
        val items = SchemeCopy.blocks("pinyin", manifest).toMap().getValue("调频")
        val eff = mapOf<String, Any>("schema.pinyin.frequency.enabled" to true, "schema.pinyin.frequency.half_life" to 30.0)
        val sets = SchemeCopy.sets(items, eff, "pinyin", types::get).associate { it.key to it.value }
        assertEquals(ConfigValue.Bool(true), sets["schema.pinyin.frequency.enabled"])
        assertEquals(ConfigValue.Float(30.0), sets["schema.pinyin.frequency.half_life"])
    }

    @Test
    fun copyWritesEveryTargetAndReportsFailures() {
        val io = Io("\"schema.pinyin.frequency.enabled\" = true")
        io.failOn = "bad"
        val items = SchemeCopy.blocks("pinyin", manifest).toMap().getValue("调频")
        val r = SchemeCopy.copy(
            "pinyin",
            items,
            listOf(SchemeCopy.Target("sp", "双拼", "shuangpin"), SchemeCopy.Target("bad", "坏方案", "pinyin")),
            io,
            types::get,
        )
        assertEquals(listOf("双拼"), r.copied)
        assertEquals(listOf("坏方案"), r.failed.map { it.first })
        assertEquals(listOf("schema.pinyin.frequency.enabled"), io.applied.getValue("sp").map { it.key })
    }
}
