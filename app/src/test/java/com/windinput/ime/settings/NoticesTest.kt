package com.windinput.ime.settings

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NoticesTest {

    private val dir = File("src/main/assets/${Notices.DIR}")
    private val notices by lazy { Notices.parse(File(dir, "notices.toml").readText()) }

    /** 点开许可证要能看到全文：声明里写的 id 若有全文，文件必须在 */
    @Test
    fun everyFullTextLicenseFileExists() {
        for (id in Notices.FULL_TEXTS) {
            assertTrue("缺少许可证全文 $id.txt", File(dir, "$id.txt").isFile)
        }
        val unknown = notices.map { it.license }.filter { it !in Notices.FULL_TEXTS }
        assertTrue("没有全文的许可证：$unknown", unknown.isEmpty())
    }

    /**
     * 随包数据都要有声明：assets/data 下的词库与数据目录在这里各对上一条。
     * 往 assets 加了新数据却忘了写声明，这条会挂。
     */
    @Test
    fun bundledDataHasNotices() {
        val names = notices.joinToString(" ") { it.name + it.usage }
        val required = mapOf(
            "schemas/wubi86" to "rime-wubi86-jidian",
            "schemas/pinyin" to "rime-frost",
            "schemas/english" to "rime-frost",
            "opencc" to "OpenCC",
            "emoji" to "rime-emoji",
            "pinyin_map.txt" to "pinyin-data",
        )
        val data = File("src/main/assets/data")
        for ((path, notice) in required) {
            assertTrue("测试清单过期：$path 已不在 assets/data", File(data, path).exists())
            assertTrue("$path 缺少「$notice」的声明", notice in names)
        }
    }

    @Test
    fun rustCrateListIsGenerated() {
        val lines = File(dir, "rust-crates.txt").readLines().filterNot { it.startsWith("#") || it.isBlank() }
        assertTrue("Rust 依赖清单为空，跑 scripts/gen-rust-licenses.sh", lines.size > 20)
        assertTrue("清单里不该有 wind-* 自家 crate", lines.none { it.startsWith("wind-") })
    }
}
