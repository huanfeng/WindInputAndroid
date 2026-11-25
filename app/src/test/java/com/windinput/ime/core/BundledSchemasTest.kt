package com.windinput.ime.core

import org.junit.Assert.assertTrue
import org.junit.Test
import org.tomlj.Toml
import java.io.File

/**
 * 随包方案引用的词库都得在 APK 里。
 *
 * 缺了核心不报错，只在日志里留一行 warning，那一路候选就悄悄没了——五笔的 Emoji 库与行政区域库
 * 就这样缺了很久（输入 emoj 什么都不出）。只查**默认就加载**的库（主库 `default = true`、
 * 附加库 `default_enabled = true`）：默认关的细胞词库手机上还没有开关，不随包是有意的。
 */
class BundledSchemasTest {

    private val schemaDir = File("src/main/assets/data/schemas")

    @Test
    fun defaultDictionariesAreBundled() {
        val schemas = schemaDir.listFiles { f -> f.name.endsWith(".schema.toml") }.orEmpty()
        assertTrue("没找到随包方案", schemas.isNotEmpty())
        val missing = mutableListOf<String>()
        for (f in schemas) {
            val toml = Toml.parse(f.readText())
            val dicts = toml.getArray("dictionaries") ?: continue
            for (i in 0 until dicts.size()) {
                val d = dicts.getTable(i)
                val loads = d.getBoolean("default") == true || d.getBoolean("default_enabled") == true
                val path = d.getString("path") ?: continue
                if (loads && !File(schemaDir, path).isFile) missing += "${f.name}: $path"
            }
        }
        assertTrue("默认加载却没随包的词库：$missing", missing.isEmpty())
    }

    /**
     * 内置误读表：核心按 `schemas/pinyin_misread.yaml` 读，缺了不报错，只是误读提示与召回悄悄没了。
     * 主仓在隔壁时内容须与主仓 `data/schemas/` 那份一致（手工同步，改主仓后记得拷过来）。
     */
    @Test
    fun misreadTableIsBundledAndInSync() {
        val bundled = File(schemaDir, "pinyin_misread.yaml")
        assertTrue("缺内置误读表 ${bundled.path}", bundled.isFile)
        val upstream = File("../../WindInput/data/schemas/pinyin_misread.yaml")
        if (upstream.isFile) {
            assertTrue("误读表与主仓不一致，从 ${upstream.path} 拷过来", upstream.readBytes().contentEquals(bundled.readBytes()))
        }
    }

    /**
     * 辅助码来源：方案声明的 `[engine.aux_code] files` 都得在包里。
     * 缺了核心的设置页判据会把「辅助码」开关整个藏掉（见 docs/schema-settings-plan.md），
     * 用户看不到功能也不知道缺了什么。码表不入主仓版本库，靠构建产物拷进来（NOTICE.md）。
     */
    @Test
    fun auxCodeSourcesAreBundled() {
        val missing = mutableListOf<String>()
        for (f in schemaDir.listFiles { f -> f.name.endsWith(".schema.toml") }.orEmpty()) {
            val files = Toml.parse(f.readText()).getArray("engine.aux_code.files") ?: continue
            for (i in 0 until files.size()) {
                val src = files.getString(i)
                val ok = if (src.startsWith("schema:")) {
                    File(schemaDir, src.removePrefix("schema:") + ".schema.toml").isFile
                } else File(schemaDir, src).isFile
                if (!ok) missing += "${f.name}: $src"
            }
        }
        assertTrue("辅助码来源没随包：$missing", missing.isEmpty())
    }
}
