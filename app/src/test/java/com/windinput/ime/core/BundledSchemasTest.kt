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
}
