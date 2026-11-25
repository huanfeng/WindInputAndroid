package com.windinput.ime.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.wind_android.SchemaSettingGroup
import uniffi.wind_android.SchemaSettingItem
import uniffi.wind_android.SchemaSettingKind
import java.io.File

class SchemeSettingsModelTest {

    private val manifest by lazy {
        SettingsManifest.parse(File("src/main/assets/data/settings_manifest.toml").readText())
    }

    private fun prop(key: String, group: SchemaSettingGroup) =
        SchemaSettingItem(key, group, SchemaSettingKind.BOOL, "true", "true", false, emptyList())

    private val behavior = listOf(prop("punct.mode", SchemaSettingGroup.BEHAVIOR))
    private val aux = prop(KEY_AUX_ENABLED, SchemaSettingGroup.AUX_CODE)

    private fun names(type: String, props: List<SchemaSettingItem> = behavior, t9: Boolean = false, dicts: Boolean = false, members: Boolean = false) =
        schemeGroups(type, manifest, props, t9Capable = t9, hasDicts = dicts, hasMembers = members).map { it.name }

    @Test
    fun pinyinGroups() {
        assertEquals(
            listOf("基本", "九宫格键盘", "智能组词", "词组补全", "英文候选", "调频", "自动学习", "辅助码", "扩展词库", "自定义行为"),
            names("pinyin", behavior + aux, t9 = true, dicts = true),
        )
    }

    @Test
    fun auxGroupOnlyWhenUsable() {
        assertTrue("辅助码" !in names("pinyin"))
        assertTrue("九宫格键盘" !in names("pinyin"))
        assertEquals(SchemeGroup.Kind.AUX, schemeGroups("shuangpin", manifest, behavior + aux, false, false, false).first { it.name == "辅助码" }.kind)
    }

    @Test
    fun codetableGroupsIncludeSentence() {
        val sentence = prop(KEY_SENTENCE, SchemaSettingGroup.EXPERIMENTAL)
        assertEquals(
            listOf("上屏", "匹配", "通配与 z 键", "调频", "自动造词", "英文候选", "整句输入", "自定义行为"),
            names("codetable", behavior + sentence),
        )
    }

    @Test
    fun mixedHasMembers() {
        assertEquals(listOf("基本", "拼音部分", "英文部分", "自定义行为", "成员方案"), names("mixed", members = true))
        assertEquals(SchemeGroup.Kind.MEMBERS, schemeGroups("mixed", manifest, behavior, false, false, true).last().kind)
    }

    @Test
    fun formGroupsCarryTheirItems() {
        val basic = schemeGroups("shuangpin", manifest, behavior, false, false, false).first()
        assertEquals(SchemeGroup.Kind.FORM, basic.kind)
        assertEquals("schema.pinyin.shuangpin.allow_full_pinyin", basic.items.first().key)
    }
}
