package com.windinput.ime.keyboard.keymap

import com.windinput.ime.keyboard.KeyboardLayer
import com.windinput.ime.keyboard.KeyboardLayouts
import com.windinput.ime.settings.keymapStatusText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class KeymapPresetsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun presetFile(id: String) = File("src/main/assets/data/${KeymapPresets.fileFor(id)}")

    @Test
    fun everyPresetParsesCleanlyAndHitsRealKeys() {
        for (id in KeymapPresets.IDS) {
            val f = presetFile(id)
            assertTrue("$id 文件存在", f.isFile)
            val r = KeymapParser.load(f)
            assertTrue("$id 无警告：${r.warnings}", r.warnings.isEmpty())
            assertFalse(r.tooNew)
            // 合并到真实布局时每个键位 id 都找得到、没有被告警跳过
            val warns = mutableListOf<String>()
            KeymapMerge.apply(KeyboardLayouts.qwerty, KeyboardLayer.LETTERS, r) { warns += it }
            assertTrue("$id 合并告警：$warns", warns.isEmpty())
        }
    }

    @Test
    fun unknownPresetIdMeansNoPreset() {
        assertNull(KeymapPresets.fileFor(KeymapPresets.DEFAULT))
        assertNull(KeymapPresets.fileFor("nope"))
    }

    @Test
    fun userOverridesWinPerGestureOverPreset() {
        val preset = KeymapParser.parse(
            """
            [layer.letters.key.z]
            swipe_up = "《"
            swipe_down = { func = "undo" }
            [layer.letters.key.x]
            swipe_up = "》"
            """.trimIndent(),
        )
        val user = KeymapParser.parse("[layer.letters.key.z]\nswipe_up = \"〔\"")
        val m = preset.overlaid(user)
        // 用户改了 z 的上滑，预设的 z 下滑与 x 上滑保留
        assertEquals(KeyOutput.Text("〔"), m.overrideFor("letters", "z", Gesture.SWIPE_UP)?.output)
        assertEquals(KeyOutput.Func("undo"), m.overrideFor("letters", "z", Gesture.SWIPE_DOWN)?.output)
        assertEquals(KeyOutput.Text("》"), m.overrideFor("letters", "x", Gesture.SWIPE_UP)?.output)
    }

    @Test
    fun repositoryAppliesPresetThenUserFile() {
        val user = File(tmp.root, "keymap.toml")
        val repo = KeymapRepository(user, {}) { presetFile("cn_marks") }
        val rows = KeyboardLayouts.qwerty
        fun swipe(r: List<List<com.windinput.ime.keyboard.KeyDef>>, id: String) = r.flatten().first { it.id == id }.swipeUp
        assertEquals('《', swipe(repo.apply(rows, KeyboardLayer.LETTERS), "z"))
        user.writeText("[layer.letters.key.z]\nswipe_up = \"（\"")
        // 修改时间可能同秒：靠长度变化也会重读
        assertEquals('（', swipe(repo.apply(rows, KeyboardLayer.LETTERS), "z"))
        assertEquals('》', swipe(repo.apply(rows, KeyboardLayer.LETTERS), "x"))
        user.delete()
        assertEquals('《', swipe(repo.apply(rows, KeyboardLayer.LETTERS), "z"))
    }

    @Test
    fun noPresetAndNoFileLeavesLayoutUntouched() {
        val repo = KeymapRepository(File(tmp.root, "none.toml"), {})
        assertTrue(repo.apply(KeyboardLayouts.qwerty, KeyboardLayer.LETTERS) === KeyboardLayouts.qwerty)
    }

    @Test
    fun tooNewUserFileKeepsPresetButFlagsNotice() {
        val user = File(tmp.root, "keymap.toml").apply { writeText("version = 9\n[layer.letters.key.z]\nswipe_up = \"x\"") }
        val repo = KeymapRepository(user, {}) { presetFile("cn_marks") }
        assertTrue(repo.tooNew)
        val z = repo.apply(KeyboardLayouts.qwerty, KeyboardLayer.LETTERS).flatten().first { it.id == "z" }
        assertEquals('《', z.swipeUp) // 用户文件整份忽略，预设照常
    }

    @Test
    fun statusTextOnlyWhenSomethingToSay() {
        assertNull(keymapStatusText(false, 1, 0))
        assertTrue(keymapStatusText(true, 3, 0)!!.contains("更新版本"))
        assertTrue(keymapStatusText(false, 1, 2)!!.contains("2 处"))
    }
}
