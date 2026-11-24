package com.windinput.ime.skin

import com.windinput.ime.keyboard.Palette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 阶段 1 新增槽位：回退链、显式覆盖、引用，以及「每个槽位都有人用」的覆盖率检查 */
class SkinSlotsTest {
    private val d = SkinRuntime.DEFAULT.light

    @Test
    fun unsetNewSlotsDeriveFromTheirFallbackSlots() {
        // 只改 text / hint / accent：派生槽位跟着变，不用逐个写
        val r = SkinParser.parse("[colors]\ntext = \"#112233\"\nhint = \"#445566\"\naccent = \"#778899\"\n")
        val p = r.skin.light
        assertEquals(0xFF112233.toInt(), p.keyText)
        assertEquals(0xFF112233.toInt(), p.candidateText)
        assertEquals(0xFF112233.toInt(), p.popupText)
        assertEquals(0xFF445566.toInt(), p.keyHint)
        assertEquals(0xFF445566.toInt(), p.candidateComment)
        assertEquals(0xFF778899.toInt(), p.keyAccent)
        assertEquals(d.surface, p.panelBackground)
    }

    @Test
    fun explicitNewSlotWinsOverFallback() {
        val r = SkinParser.parse("[colors]\ntext = \"#112233\"\nkeyText = { light = \"#AABBCC\", dark = \"#DDEEFF\" }\n")
        assertEquals(0xFFAABBCC.toInt(), r.skin.light.keyText)
        assertEquals(0xFFDDEEFF.toInt(), r.skin.dark.keyText)
        assertEquals(0xFF112233.toInt(), r.skin.light.candidateText)
    }

    @Test
    fun referenceToNewSlotAndToUnsetNewSlot() {
        val r = SkinParser.parse("[colors]\ntext = \"#112233\"\npopupText = \"\${keyText}\"\n")
        assertEquals(0xFF112233.toInt(), r.skin.light.popupText)
        assertTrue(r.warnings.isEmpty())
    }

    @Test
    fun borderShadowScrimAreOffByDefault() {
        val p = SkinParser.parse("[colors]\ntext = \"#112233\"\n").skin.light
        assertEquals(0, p.keyBorder)
        assertEquals(0, p.keyShadow)
        assertEquals(0, p.scrim)
        val q = SkinParser.parse("[colors]\nkeyBorder = \"#11223380\"\n").skin.light
        assertEquals(0x80112233.toInt(), q.keyBorder)
    }

    @Test
    fun defaultPaletteNewSlotsEqualTheirBaseSlots() {
        assertEquals(d.text, d.keyText)
        assertEquals(d.text, d.keySpecialText)
        assertEquals(d.hint, d.keyHint)
        assertEquals(d.accent, d.keyAccent)
        assertEquals(d.background, d.barBackground)
        assertEquals(d.accentText, d.candidateSelected)
        assertEquals(d.key, d.popupBackground)
        assertEquals(d.border, d.popupBorder)
        assertEquals(d.accentSoft, d.popupSelected)
        assertEquals(d.surface, d.panelBackground)
    }

    /** 防「加了槽位没人用」：Palette 每个字段都要在主源码里（除 Palette / skin 自身）被读到 */
    @Test
    fun everyPaletteSlotIsReadSomewhere() {
        val src = File("src/main/java").walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { "/skin/" !in it.path && !it.path.endsWith("keyboard/Palette.kt") }
            .joinToString("\n") { it.readText() }
        // scrim 是阶段 3「背景图遮罩」用的，现阶段只解析不绘制；到阶段 3 删掉这条豁免
        val pending = setOf("scrim")
        val unused = Palette::class.java.declaredFields.map { it.name }
            .filter { !it.contains('$') && it != "Companion" && it != "applied" && it !in pending }
            .filter { !Regex("\\.$it\\b").containsMatchIn(src) }
        assertTrue("这些槽位没有任何调用点：$unused", unused.isEmpty())
    }

    @Test
    fun contrastRatioMatchesKnownValues() {
        assertEquals(21.0, SkinContrast.ratio(0xFF000000.toInt(), 0xFFFFFFFF.toInt()), 0.01)
        assertEquals(1.0, SkinContrast.ratio(0xFF777777.toInt(), 0xFF777777.toInt()), 0.001)
        // 半透明前景先合成：50% 黑压白 ≈ 灰 #808080（4.0:1）
        assertEquals(3.95, SkinContrast.ratio(0x80000000.toInt(), 0xFFFFFFFF.toInt()), 0.1)
    }

    @Test
    fun defaultSkinHasNoContrastWarnings() {
        assertTrue(SkinContrast.warnings(SkinRuntime.DEFAULT.light, "浅").isEmpty())
        assertTrue(SkinContrast.warnings(SkinRuntime.DEFAULT.dark, "深").isEmpty())
    }

    @Test
    fun lowContrastIsWarnedNotBlocked() {
        val r = SkinParser.parse("[colors]\nkey = \"#FFFFFF\"\ntext = \"#EEEEEE\"\n")
        assertNotEquals(SkinRuntime.DEFAULT, r.skin)
        assertFalse(SkinContrast.warnings(r.skin.light, "浅").isEmpty())
    }
}
