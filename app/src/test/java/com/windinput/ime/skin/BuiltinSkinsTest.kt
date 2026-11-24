package com.windinput.ime.skin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** 内置预设（assets 的 skins 目录下各 toml）与皮肤选择 / 载入（SkinStore） */
class BuiltinSkinsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val builtin = File("src/main/assets/data/skins")

    private fun store(user: File = File(tmp.root, "mobile/skins")) =
        SkinStore(builtin, user, File(tmp.root, "mobile/skin_prefs.toml"))

    @Test
    fun atLeastThreePresetsShip() {
        assertTrue(builtin.listFiles { f -> f.extension == "toml" }!!.size >= 3)
    }

    @Test
    fun everyPresetParsesCleanlyAndDiffersFromDefault() {
        for (f in builtin.listFiles { f -> f.extension == "toml" }!!) {
            val r = SkinParser.parse(f.readText())
            assertTrue("${f.name}: ${r.warnings}", r.warnings.isEmpty())
            assertFalse("${f.name} tooNew", r.tooNew)
            assertNotSame("${f.name} 解析后与默认相同", SkinRuntime.DEFAULT, r.skin)
            assertTrue("${f.name} 缺 meta.name", !r.name.isNullOrBlank())
            val c = SkinContrast.warnings(r.skin.light, "浅") + SkinContrast.warnings(r.skin.dark, "深")
            assertTrue("${f.name} 对比度：$c", c.isEmpty())
        }
    }

    @Test
    fun listPutsDefaultFirstThenBuiltinsThenUser() {
        val user = File(tmp.root, "mobile/skins/mine").apply { mkdirs() }
        File(user, "skin.toml").writeText("[meta]\nname = \"我的\"\n")
        val list = store().list()
        assertEquals(SkinStore.DEFAULT_ID, list.first().id)
        assertTrue(list.first().builtin)
        assertEquals("我的", list.last().name)
        assertFalse(list.last().builtin)
        assertTrue(list.any { it.id == "mint" && it.name == "薄荷" })
    }

    @Test
    fun noPrefsMeansDefaultAndDefaultActivationIsTheDefaultInstance() {
        val s = store()
        assertEquals(SkinStore.DEFAULT_ID, s.activeId())
        val a = s.activate()
        assertTrue(a.skin === SkinRuntime.DEFAULT)
        assertFalse(a.fellBack)
    }

    @Test
    fun selectingAPresetActivatesItAndSurvivesRestart() {
        val s = store()
        s.setActive("mint")
        val a = s.activate()
        assertEquals("mint", a.id)
        assertTrue(SkinRuntime.active === a.skin)
        assertNotSame(SkinRuntime.DEFAULT, a.skin)
        assertEquals("mint", store().activeId())
        // 复位，别影响其它测试
        SkinRuntime.active = SkinRuntime.DEFAULT
    }

    @Test
    fun missingSkinFallsBackToDefaultWithMessage() {
        val s = store()
        s.setActive("ghost")
        val a = s.activate()
        assertTrue(a.fellBack)
        assertTrue(a.skin === SkinRuntime.DEFAULT)
        assertTrue(a.warnings.any { "ghost" in it })
    }

    @Test
    fun brokenOrTooNewUserSkinFallsBack() {
        val user = File(tmp.root, "mobile/skins")
        File(user, "bad").apply { mkdirs() }.resolve("skin.toml").writeText("version = = =")
        File(user, "future").apply { mkdirs() }.resolve("skin.toml").writeText("version = 99\n")
        val s = store(user)
        for (id in listOf("bad", "future")) {
            s.setActive(id)
            val a = s.activate(force = true)
            assertTrue("$id", a.fellBack)
            assertTrue("$id", a.skin === SkinRuntime.DEFAULT)
        }
    }

    @Test
    fun editingTheSkinFileIsPickedUpWithoutForce() {
        val user = File(tmp.root, "mobile/skins")
        val f = File(user, "mine").apply { mkdirs() }.resolve("skin.toml")
        f.writeText("[colors]\nbackground = \"#101010\"\n")
        val s = store(user)
        s.setActive("mine")
        val first = s.activate()
        assertEquals(0xFF101010.toInt(), first.skin.light.background)
        assertTrue(s.activate() === first) // 没变：命中缓存
        f.writeText("[colors]\nbackground = \"#202020\"\n# 长度变了\n")
        assertEquals(0xFF202020.toInt(), s.activate().skin.light.background)
        SkinRuntime.active = SkinRuntime.DEFAULT
    }

    @Test
    fun userSkinCannotShadowDefaultAndBadIdsAreRejected() {
        val user = File(tmp.root, "mobile/skins")
        File(user, "default").apply { mkdirs() }.resolve("skin.toml").writeText("[colors]\ntext = \"#FF0000\"\n")
        val s = store(user)
        assertEquals(1, s.list().count { it.id == "default" })
        assertTrue(s.activate().skin === SkinRuntime.DEFAULT)
        val err = runCatching { s.setActive("../evil") }.exceptionOrNull()
        assertTrue(err is IllegalArgumentException)
    }
}
