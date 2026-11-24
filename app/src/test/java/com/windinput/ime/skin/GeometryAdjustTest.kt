package com.windinput.ime.skin

import com.windinput.ime.keyboard.KeyboardDimens
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GeometryAdjustTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val savedRow = KeyboardDimens.rowHeightDp
    private val savedGeometry = KeyboardDimens.geometry

    @After
    fun restore() {
        KeyboardDimens.rowHeightDp = savedRow
        KeyboardDimens.geometry = savedGeometry
    }

    @Test
    fun emptyAdjustReturnsSameGeometry() {
        val g = SkinGeometry()
        assertSame(g, GeometryAdjust().applyTo(g))
    }

    @Test
    fun adjustOverridesOnlyTouchedFields() {
        val g = GeometryAdjust(keyGapHDp = 7f, labelScale = 1.1f).applyTo(SkinGeometry())
        assertEquals(3.5f, g.keyGapHalfDp, 0f)
        assertEquals(1.1f, g.labelScale, 0f)
        assertEquals(8f, g.keyRadiusDp, 0f)
        assertFalse(g.rowGapFixed)
        assertTrue(GeometryAdjust(rowGapDp = 6f).applyTo(SkinGeometry()).rowGapFixed)
    }

    @Test
    fun outOfRangeValuesAreClamped() {
        val a = GeometryAdjust(keyGapHDp = 99f, rowGapDp = -3f, keyRadiusDp = 500f, labelScale = 0.1f).clamped()
        assertEquals(10f, a.keyGapHDp!!, 0f)
        assertEquals(2f, a.rowGapDp!!, 0f)
        assertEquals(20f, a.keyRadiusDp!!, 0f)
        assertEquals(0.8f, a.labelScale!!, 0f)
    }

    // ── KeyboardDimens：默认值逐项等于旧常量；调行间距不改整体高度 ──

    @Test
    fun defaultKeyGapVMatchesLegacyDerivation() {
        KeyboardDimens.geometry = SkinGeometry()
        for (row in listOf(40, 44, 50, 60, 72)) {
            KeyboardDimens.rowHeightDp = row
            for (d in listOf(1f, 2.625f, 3f)) {
                val legacy = (KeyboardDimens.rowHeightPx(d) - 51f * d).coerceAtLeast(2 * d) / 2
                assertEquals("row=$row d=$d", legacy, KeyboardDimens.keyGapVPx(d), 0f)
            }
        }
        assertEquals(2.7f, KeyboardDimens.KEY_GAP_DP, 0f)
        assertEquals(8f, KeyboardDimens.KEY_RADIUS_DP, 0f)
    }

    @Test
    fun fixedRowGapKeepsRowHeightAndShrinksKey() {
        KeyboardDimens.rowHeightDp = 60
        KeyboardDimens.geometry = GeometryAdjust(rowGapDp = 14f).applyTo(SkinGeometry())
        val d = 2f
        val gapHalf = KeyboardDimens.keyGapVPx(d)
        assertEquals(14f * d / 2, gapHalf, 0f)
        // 键帽高 = 行高 − 间隙；行高（整体高度）不受影响
        assertEquals(60 * d - 14f * d, KeyboardDimens.rowHeightPx(d) - 2 * gapHalf, 0f)
        assertEquals(60 * 4 * d, KeyboardDimens.keyboardHeightPx(d, 4).toFloat(), 1f)
    }

    @Test
    fun fixedRowGapNeverMakesKeyShorterThanMinimum() {
        KeyboardDimens.rowHeightDp = 44
        KeyboardDimens.geometry = GeometryAdjust(rowGapDp = 16f).applyTo(SkinGeometry())
        val d = 2f
        val keyH = KeyboardDimens.rowHeightPx(d) - 2 * KeyboardDimens.keyGapVPx(d)
        assertTrue(keyH >= KeyboardDimens.MIN_KEY_HEIGHT_DP * d - 0.01f)
        KeyboardDimens.geometry = GeometryAdjust(rowGapDp = 2f).applyTo(SkinGeometry())
        assertEquals(2f * d / 2, KeyboardDimens.keyGapVPx(d), 0f)
    }

    @Test
    fun keyRadiusIsClampedToHalfKeyHeight() {
        KeyboardDimens.rowHeightDp = 60
        KeyboardDimens.geometry = GeometryAdjust(keyRadiusDp = 20f).applyTo(SkinGeometry())
        val d = 2f
        val keyH = KeyboardDimens.rowHeightPx(d) - 2 * KeyboardDimens.keyGapVPx(d)
        assertTrue(KeyboardDimens.keyRadiusPx(d) <= keyH / 2)
        KeyboardDimens.geometry = SkinGeometry()
        assertEquals(8f * d, KeyboardDimens.keyRadiusPx(d), 0f)
    }

    // ── skin_prefs.toml ──

    @Test
    fun prefsRoundTripKeepsActiveAndGeometry() {
        val f = tmp.newFile("skin_prefs.toml")
        SkinPrefsFile.write(f, SkinPrefs("mint", GeometryAdjust(6f, 7f, 12f, 1.1f)))
        val p = SkinPrefsFile.read(f)
        assertEquals("mint", p.active)
        assertEquals(GeometryAdjust(6f, 7f, 12f, 1.1f), p.adjust)
    }

    @Test
    fun settingActiveDoesNotLoseGeometryAndViceVersa() {
        val f = tmp.newFile("skin_prefs.toml")
        val store = SkinStore(tmp.newFolder("b"), tmp.newFolder("u"), f)
        store.setGeometryAdjust(GeometryAdjust(keyRadiusDp = 3f))
        store.setActive("default")
        assertEquals(3f, store.geometryAdjust().keyRadiusDp!!, 0f)
        store.setGeometryAdjust(GeometryAdjust())
        assertEquals("default", store.activeId())
        assertFalse(f.readText().contains("[geometry]"))
    }

    @Test
    fun brokenPrefsFallBackToDefaults() {
        val f = tmp.newFile("skin_prefs.toml")
        f.writeText("active = [[[")
        assertEquals(SkinPrefs(), SkinPrefsFile.read(f))
        f.writeText("active = \"x\"\n[geometry]\nkey_gap_h = 999\nrow_gap = \"abc\"\n")
        val p = SkinPrefsFile.read(f)
        assertEquals(10f, p.adjust.keyGapHDp!!, 0f)
        assertEquals(null, p.adjust.rowGapDp)
    }
}
