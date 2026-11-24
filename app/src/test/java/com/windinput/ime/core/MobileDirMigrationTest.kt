package com.windinput.ime.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MobileDirMigrationTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun File.put(rel: String, text: String = "x") {
        File(this, rel).apply { parentFile?.mkdirs() }.writeText(text)
    }

    private fun noLog(@Suppress("UNUSED_PARAMETER") s: String) = Unit

    @Test
    fun movesKeyboardDirIntoMobileAndRenamesConfig() {
        val root = tmp.newFolder("ext")
        root.put("keyboard/config.toml", "kb")
        root.put("keyboard/layouts/q.toml", "layout")
        root.put("keyboard/icons/a.png", "icon")

        assertTrue(MobileDirMigration.run(root, ::noLog))

        assertEquals("kb", File(root, "mobile/keyboard.toml").readText())
        assertEquals("layout", File(root, "mobile/layouts/q.toml").readText())
        assertEquals("icon", File(root, "mobile/icons/a.png").readText())
        assertFalse("旧目录搬空后删掉", File(root, "keyboard").exists())
    }

    @Test
    fun doesNotTouchPcSharedFilesAtRoot() {
        val root = tmp.newFolder("ext")
        root.put("config.toml", "engine")
        root.put("keyboard/config.toml", "kb")

        MobileDirMigration.run(root, ::noLog)

        assertEquals("引擎配置（与 PC 同名）原地不动", "engine", File(root, "config.toml").readText())
    }

    @Test
    fun neverOverwritesExistingTargetAndIsIdempotent() {
        val root = tmp.newFolder("ext")
        root.put("mobile/keyboard.toml", "new")
        root.put("keyboard/config.toml", "old")

        assertTrue(MobileDirMigration.run(root, ::noLog))
        assertEquals("目标已有的不覆盖", "new", File(root, "mobile/keyboard.toml").readText())

        assertFalse("第二次没有旧目录可搬", MobileDirMigration.run(root, ::noLog))
        assertEquals("new", File(root, "mobile/keyboard.toml").readText())
    }

    @Test
    fun freshInstallDoesNothing() {
        val root = tmp.newFolder("ext")
        assertFalse(MobileDirMigration.run(root, ::noLog))
        assertFalse(File(root, "mobile").exists())
    }
}
