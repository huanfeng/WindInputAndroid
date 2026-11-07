package com.windinput.ime.core

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.tomlj.Toml
import org.tomlj.TomlArray
import org.tomlj.TomlParseResult
import org.tomlj.TomlTable
import java.io.File

/**
 * 安卓出厂 config.toml 与主仓 data/config.toml 的对照守门。
 *
 * 安卓这份是手工副本，此前没有守门、滞后了近百行（缺新键、留着已废弃的键），出厂值悄悄跟着
 * Rust 默认值走。这里要求：键集合完全一致，取值除 [ANDROID_OVERRIDES] 外也一致。
 * 主仓改了出厂配置，这里就失败——按 config.toml 文件头写的同步方法拷一遍即可。
 *
 * 主仓路径：本地与 CI 都是与本仓并排的 `WindInput/`（CI 见 .github/workflows/ci.yml 的检出位置）。
 * 找不到主仓文件（单独 clone 本仓）时跳过，不算失败。
 */
class FactoryConfigSyncTest {

    private companion object {
        /** 安卓有意不同的键（文件里都注了「安卓有意不同」） */
        val ANDROID_OVERRIDES = setOf(
            "schema.active",
            "debug.log_level",
            "input.auto_pair.chinese",
            "input.auto_pair.english",
            "input.auto_pair.chinese_pairs",
            "input.auto_pair.english_pairs",
        )

        val ANDROID = File("src/main/assets/data/config.toml")

        /** 单测工作目录是 app/，主仓在本仓的同级目录 */
        val MAIN = File("../../WindInput/data/config.toml")
    }

    private fun parse(f: File): TomlParseResult = Toml.parse(f.readText()).also {
        assertEquals("${f.path} 解析出错", emptyList<Any>(), it.errors())
    }

    /** TomlArray / TomlTable 没有按内容比较的 equals，转成普通列表 / 映射再比 */
    private fun plain(v: Any?): Any? = when (v) {
        is TomlArray -> v.toList().map(::plain)
        is TomlTable -> v.toMap().mapValues { plain(it.value) }
        else -> v
    }

    private fun mainOrSkip(): TomlParseResult {
        assumeTrue("主仓 ${MAIN.path} 不存在，跳过对照", MAIN.isFile)
        return parse(MAIN)
    }

    @Test
    fun keySetMatchesMainRepo() {
        val main = mainOrSkip().dottedKeySet()
        val android = parse(ANDROID).dottedKeySet()
        assertEquals("安卓副本多出来的键（主仓已删或改名）", emptySet<String>(), android - main)
        assertEquals("安卓副本缺的键（主仓新增）", emptySet<String>(), main - android)
    }

    @Test
    fun valuesMatchMainRepoExceptAndroidOverrides() {
        val main = mainOrSkip()
        val android = parse(ANDROID)
        val drift = (main.dottedKeySet() intersect android.dottedKeySet())
            .filter { it !in ANDROID_OVERRIDES }
            .filter { plain(main.get(it)) != plain(android.get(it)) }
            .sorted()
            .map { "$it：主仓 ${plain(main.get(it))}，安卓 ${plain(android.get(it))}" }
        assertEquals("与主仓取值不同、又没登记为安卓有意不同的键", emptyList<String>(), drift)
    }

    /** 登记了的差异键得真在文件里：主仓改名后这张表不该悄悄失效 */
    @Test
    fun overridesStillExist() {
        val keys = parse(ANDROID).dottedKeySet()
        assertEquals(emptySet<String>(), ANDROID_OVERRIDES - keys)
    }
}
