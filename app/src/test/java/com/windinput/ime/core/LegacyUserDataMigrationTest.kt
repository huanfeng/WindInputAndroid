package com.windinput.ime.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LegacyUserDataMigrationTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun noLog(@Suppress("UNUSED_PARAMETER") msg: String) {}

    private fun File.put(rel: String, text: String = rel): File =
        File(this, rel).also { it.parentFile!!.mkdirs(); it.writeText(text) }

    /** 旧布局：filesDir/{user, rust/{config/WindInput, data, meta.toml, userdata.redb.state}} */
    private fun legacyFilesDir(): File = tmp.newFolder("files").also { f ->
        f.put("user/config.toml", "kb")
        f.put("user/layouts/q.toml")
        f.put("rust/config/WindInput/config.toml", "engine")
        f.put("rust/config/WindInput/schema_overrides/pinyin.toml")
        f.put("rust/meta.toml")
        f.put("rust/userdata.redb.state/pinyin/freq.meta")
        f.put("rust/data/stats/meta.toml")
        f.put("rust/data/phrases.dict.yaml")
        f.put("rust/data/WindInput/cache/x.wdat", "cache")
    }

    @Test
    fun movesEachLegacyPieceToItsNewHome() {
        val files = legacyFilesDir()
        val root = tmp.newFolder("ext")
        LegacyUserDataMigration.run(files, root, ::noLog)

        assertEquals("engine", File(root, "config.toml").readText())
        assertTrue(File(root, "schema_overrides/pinyin.toml").isFile)
        assertTrue(File(root, "meta.toml").isFile)
        assertTrue(File(root, "userdata.redb.state/pinyin/freq.meta").isFile)
        assertTrue(File(root, "data/stats/meta.toml").isFile)
        assertTrue(File(root, "data/phrases.dict.yaml").isFile)
        assertEquals("kb", File(root, "keyboard/config.toml").readText())
        assertTrue(File(root, "keyboard/layouts/q.toml").isFile)
    }

    @Test
    fun leavesCacheInPlaceAndRemovesMovedSources() {
        val files = legacyFilesDir()
        val root = tmp.newFolder("ext")
        LegacyUserDataMigration.run(files, root, ::noLog)

        assertTrue("缓存是本地目录，不搬", File(files, "rust/data/WindInput/cache/x.wdat").isFile)
        assertFalse(File(files, "user").exists())
        assertFalse(File(files, "rust/config").exists())
        assertFalse(File(files, "rust/userdata.redb.state").exists())
        assertFalse(File(files, "rust/data/stats").exists())
    }

    @Test
    fun neverOverwritesAndRunsOnlyOnce() {
        val files = legacyFilesDir()
        val root = tmp.newFolder("ext")
        root.put("config.toml", "already edited by user")
        assertTrue(LegacyUserDataMigration.run(files, root, ::noLog))
        assertEquals("外部已有的文件不被覆盖", "already edited by user", File(root, "config.toml").readText())

        files.put("user/config.toml", "stale")
        assertFalse("有完成标记后不再迁移", LegacyUserDataMigration.run(files, root, ::noLog))
        assertEquals("kb", File(root, "keyboard/config.toml").readText())
    }

    @Test
    fun freshInstallJustWritesMarker() {
        val files = tmp.newFolder("files")
        val root = tmp.newFolder("ext")
        assertFalse(LegacyUserDataMigration.run(files, root, ::noLog))
        assertTrue(File(root, LegacyUserDataMigration.MARKER).isFile)
    }
}
