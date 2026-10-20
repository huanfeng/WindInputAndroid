package com.windinput.ime.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SettingsManifestTest {

    private val bundled by lazy {
        SettingsManifest.parse(File("src/main/assets/data/settings_manifest.toml").readText())
    }

    @Test
    fun homeHasThreeSegmentsInDesignOrder() {
        assertEquals(
            listOf("schema", "input", "ui", "stats", "dict", "keyboard", "keys", "toolbar", "advanced", "about"),
            bundled.pages.map { it.id },
        )
        assertEquals(listOf("shared", "android", "tail"), bundled.pages.map { it.segment }.distinct())
    }

    /** 键盘项的值只在 K 侧，没有默认值就显示成空 */
    @Test
    fun androidItemsHaveDefaults() {
        val missing = bundled.items.filter { it.source == SettingSource.ANDROID && it.defaultValue == null }
        assertTrue("键盘项缺默认值：${missing.map { it.key }}", missing.isEmpty())
    }

    /** 键的命名空间决定来源：ui.android.* 只有键盘读，其余都是核心注册表里的键 */
    @Test
    fun sourceMatchesKeyNamespace() {
        val wrong = bundled.items.filter {
            (it.source == SettingSource.ANDROID) != it.key.startsWith("ui.android.")
        }
        assertTrue("来源与键名不符：${wrong.map { it.key }}", wrong.isEmpty())
    }

    @Test
    fun selectItemsHaveOptionsAndKeysAreUnique() {
        val noOptions = bundled.items.filter { it.type == "select" && it.options.isEmpty() }
        assertTrue("select 没有选项：${noOptions.map { it.key }}", noOptions.isEmpty())
        val dup = bundled.items.groupBy { it.key }.filter { it.value.size > 1 }.keys
        assertTrue("键重复：$dup", dup.isEmpty())
    }

    @Test
    fun sectionsKeepFirstAppearanceOrder() {
        assertEquals(
            listOf("码表", "拼音", "模糊音", "混输", "英文"),
            bundled.sectionsOf("schema").map { it.first },
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun itemOnUnknownPageIsRejected() {
        SettingsManifest.parse(
            """
            [[page]]
            id = "a"
            [[item]]
            page = "b"
            key = "x"
            source = "engine"
            """.trimIndent(),
        )
    }

    @Test
    fun heightAndFontSizeItemsCarryPreview() {
        val previews = bundled.items.filter { it.preview != null }.associate { it.key to it.preview }
        assertEquals(
            mapOf(
                "ui.android.candidate_text_sp" to "candidate_text",
                "ui.android.row_height_portrait" to "keyboard_rows",
                "ui.android.row_height_landscape" to "keyboard_rows",
            ),
            previews,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun unknownPreviewIsRejected() {
        SettingsManifest.parse(
            """
            [[page]]
            id = "a"
            [[item]]
            page = "a"
            key = "ui.android.x"
            source = "android"
            type = "int"
            preview = "nope"
            """.trimIndent(),
        )
    }

    /** 滑块的浮点值吸附到 min 起算的步长网格，且不出范围 */
    @Test
    fun sliderSnapsToStepGrid() {
        assertEquals(56, snapToStep(56.9f, 40, 72, 2))
        assertEquals(58, snapToStep(57.1f, 40, 72, 2))
        assertEquals(40, snapToStep(30f, 40, 72, 2))
        assertEquals(72, snapToStep(80f, 40, 72, 2))
        assertEquals(19, snapToStep(19.4f, 15, 24, 1))
    }

    /** 简繁二选一要互相指向，否则只有一个方向会自动关掉另一个 */
    @Test
    fun exclusivePairsPointAtEachOther() {
        val pairs = bundled.items.filter { it.exclusiveWith != null }.associate { it.key to it.exclusiveWith }
        assertTrue("清单里应有二选一项", pairs.isNotEmpty())
        for ((a, b) in pairs) assertEquals("$a ↔ $b 应双向", a, pairs[b])
    }

    @Test(expected = IllegalArgumentException::class)
    fun enabledWhenMustReferToAToggle() {
        SettingsManifest.parse(
            """
            [[page]]
            id = "a"
            [[item]]
            page = "a"
            key = "input.x"
            source = "engine"
            type = "select"
            options = [{ value = "1" }]
            enabled_when = "input.no_such"
            """.trimIndent(),
        )
    }
}
