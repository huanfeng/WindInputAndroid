package com.windinput.ime.settings

import com.windinput.ime.skin.GeometryAdjust
import com.windinput.ime.skin.SkinGeometry
import org.junit.Assert.assertEquals
import org.junit.Test

class GeometrySectionTest {
    @Test
    fun snapFloatSnapsToGridAndClamps() {
        assertEquals(5.4f, snapFloat(5.43f, 2f..10f, 0.2f), 0.001f)
        assertEquals(10f, snapFloat(99f, 2f..10f, 0.2f), 0f)
        assertEquals(2f, snapFloat(-5f, 2f..10f, 0.2f), 0f)
    }

    @Test
    fun previewMatchesDefaultAndFixedRowGap() {
        val base = SkinGeometry()
        // 默认：60 行高 → 键帽 51 + 间隙 9；横屏 44 → 间隙卡在 2
        assertEquals(51f to 9f, previewKeyRect(60, GeometryAdjust(), base))
        assertEquals(42f to 2f, previewKeyRect(44, GeometryAdjust(), base))
        // 固定行间距：键帽高 = 行高 − 间隙，且不低于 36
        assertEquals(46f to 14f, previewKeyRect(60, GeometryAdjust(rowGapDp = 14f), base))
        assertEquals(36f to 8f, previewKeyRect(44, GeometryAdjust(rowGapDp = 16f), base))
    }
}
