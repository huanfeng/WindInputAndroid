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
            listOf("schema", "input", "ui", "stats", "keyboard", "keys", "toolbar", "advanced", "about"),
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
}
