package com.windinput.ime.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.wind_android.InstalledSchemaInfo
import uniffi.wind_android.SchemaSettingGroup
import uniffi.wind_android.SchemaSettingItem
import uniffi.wind_android.SchemaSettingKind
import uniffi.wind_android.SchemaSettingsInfo

class SchemaDetailTest {

    private fun item(
        key: String,
        kind: SchemaSettingKind = SchemaSettingKind.BOOL,
        value: String = "true",
        default: String = "true",
        overridden: Boolean = false,
        group: SchemaSettingGroup = SchemaSettingGroup.CODE_TABLE,
        options: List<String> = emptyList(),
    ) = SchemaSettingItem(key, group, kind, value, default, overridden, options)

    @Test
    fun choosingTheDefaultValueResets() {
        val top = item("engine.codetable.top_code_commit", value = "false", default = "true", overridden = true)
        assertEquals(SchemaWrite.Reset(top.key), writeFor(top, "true"))
        assertEquals(SchemaWrite.Set(top.key, "false"), writeFor(top.copy(value = "true", overridden = false), "false"))
        val punct = item("punct.mode", SchemaSettingKind.CHOICE, "follow", "english", group = SchemaSettingGroup.BEHAVIOR)
        assertEquals("英文方案文件写了 english：选「跟随全局」是改动", SchemaWrite.Set("punct.mode", "follow"), writeFor(punct, "follow"))
    }

    @Test
    fun auxSourceChoiceShowsFriendlyNamesAndResetsToDefault() {
        val src = item(
            KEY_AUX_FILES, SchemaSettingKind.CHOICE, "aux_code/ZRM-wanxiang.txt", "aux_code/flypy_full.txt",
            group = SchemaSettingGroup.AUX_CODE, overridden = true,
        ).copy(options = listOf("aux_code/flypy_full.txt", "aux_code/ZRM-wanxiang.txt", "schema:stroke", "aux_code/x.txt"))
        val (values, names) = choiceEntries(src, AUX_SOURCE_LABELS)
        assertEquals(src.options, values)
        assertEquals(listOf("小鹤形码", "自然码形码", "笔画（码表方案）", "aux_code/x.txt"), names)
        assertEquals(SchemaWrite.Reset(KEY_AUX_FILES), writeFor(src, "aux_code/flypy_full.txt"))
        assertEquals(SchemaWrite.Set(KEY_AUX_FILES, "schema:stroke"), writeFor(src, "schema:stroke"))
    }

    @Test
    fun summaryNamesTheDefaultOnlyWhenChanged() {
        val punct = item("punct.mode", SchemaSettingKind.CHOICE, "english", "follow", group = SchemaSettingGroup.BEHAVIOR)
        assertEquals("说明", rowSummary("说明", punct, PUNCT_LABELS))
        assertNull(rowSummary(null, punct, PUNCT_LABELS))
        assertEquals("说明\n已自定义 · 默认：跟随全局", rowSummary("说明", punct.copy(overridden = true), PUNCT_LABELS))
        val bool = item("phrases.enabled", value = "false", default = "true", overridden = true)
        assertEquals("已自定义 · 默认：开", rowSummary(null, bool, emptyMap()))
    }

    @Test
    fun infoSummaryJoinsFactsThenDescription() {
        val s = SchemaSettingsInfo("wubi86", "五笔", "codetable", "1.0", "内置", "五笔86版输入方案", emptyList())
        val builtin = InstalledSchemaInfo("wubi86", "五笔", true, false, false, true)
        assertEquals("码表 · 应用自带 · 版本 1.0 · 作者 内置\n五笔86版输入方案", infoSummary(s, builtin))
        assertEquals("码表", infoSummary(s.copy(version = "", author = "", description = ""), null))
    }

    @Test
    fun labels() {
        assertEquals("双拼", engineTypeLabel("shuangpin"))
        assertEquals("码表", engineTypeLabel("codetable"))
        assertEquals("应用自带", sourceLabel(user = false, builtin = true))
        assertEquals("用户导入（覆盖了自带版本）", sourceLabel(user = true, builtin = true))
        assertEquals("用户导入", sourceLabel(user = true, builtin = false))
    }

    @Test
    fun choiceEntriesAppendsUnknownValueInsteadOfShowingFirst() {
        val labels = linkedMapOf("0" to "关闭", "2" to "二码", "3" to "三码")
        val known = item("engine.codetable.auto_commit_min_len", SchemaSettingKind.INT, value = "2")
        assertEquals(listOf("0", "2", "3") to listOf("关闭", "二码", "三码"), choiceEntries(known, labels))
        val odd = item("engine.codetable.auto_commit_min_len", SchemaSettingKind.INT, value = "6")
        val (values, names) = choiceEntries(odd, labels)
        assertEquals(3, values.indexOf("6"))
        assertEquals("6（自定义）", names[3])
        val gone = item(KEY_SHUANGPIN_LAYOUT, SchemaSettingKind.CHOICE, value = "old", options = listOf("xiaohe", "ziranma"))
        assertEquals(listOf("xiaohe", "ziranma", "old"), choiceEntries(gone, emptyMap()).first)
    }
}
