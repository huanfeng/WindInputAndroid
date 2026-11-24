package com.windinput.ime.skin

import org.tomlj.Toml
import java.io.File
import java.util.Locale

/**
 * 用户在「外观」里调的几何旋钮（键间距、行间距、圆角、字号）。每项 null = 没动过，沿用皮肤 / 出厂值。
 *
 * 全是 dp / 倍率，不依赖屏幕尺寸，所以可以随 `skin_prefs.toml` 一起同步（docs/mobile-skin-design.md §5.1）；
 * 与设备相关的**行高**不在这里（留在 `ui.android.row_height_*`）。越界值**钳制**，不报错。
 */
data class GeometryAdjust(
    /** 两键横向间隙（整数口径，= skin.toml 的 key_gap_h） */
    val keyGapHDp: Float? = null,
    /** 上下两行之间的间隙；设了它，键帽高 = 行高 − 间隙（整体高度不变） */
    val rowGapDp: Float? = null,
    val keyRadiusDp: Float? = null,
    /** 键面字号倍率（主字与角标） */
    val labelScale: Float? = null,
) {
    val isEmpty: Boolean get() = keyGapHDp == null && rowGapDp == null && keyRadiusDp == null && labelScale == null

    fun clamped(): GeometryAdjust = GeometryAdjust(
        keyGapHDp?.coerceIn(KEY_GAP_H.start, KEY_GAP_H.endInclusive),
        rowGapDp?.coerceIn(ROW_GAP.start, ROW_GAP.endInclusive),
        keyRadiusDp?.coerceIn(KEY_RADIUS.start, KEY_RADIUS.endInclusive),
        labelScale?.coerceIn(LABEL_SCALE.start, LABEL_SCALE.endInclusive),
    )

    /** 叠在皮肤几何上；没动过任何一项就原样返回同一个对象（出厂外观不变） */
    fun applyTo(base: SkinGeometry): SkinGeometry {
        if (isEmpty) return base
        val a = clamped()
        return base.copy(
            keyGapHalfDp = a.keyGapHDp?.let { it / 2 } ?: base.keyGapHalfDp,
            rowGapDp = a.rowGapDp ?: base.rowGapDp,
            rowGapFixed = a.rowGapDp != null || base.rowGapFixed,
            keyRadiusDp = a.keyRadiusDp ?: base.keyRadiusDp,
            labelScale = a.labelScale ?: base.labelScale,
        )
    }

    companion object {
        val KEY_GAP_H = 2f..10f
        val ROW_GAP = 2f..16f
        val KEY_RADIUS = 0f..20f
        val LABEL_SCALE = 0.8f..1.3f
    }
}

/** `skin_prefs.toml` 的内容：选中的皮肤 + 几何调节 */
data class SkinPrefs(val active: String = SkinStore.DEFAULT_ID, val adjust: GeometryAdjust = GeometryAdjust())

/**
 * `skin_prefs.toml` 的读写（纯 JVM）。读：任何问题都退回默认，不抛；写：整份重生成（只含已知字段，
 * 先写 [setActive] 再写 [GeometryAdjust] 互不覆盖）。
 *
 * ```
 * version = 1
 * active = "mint"
 * [geometry]
 * key_gap_h = 6.0
 * row_gap = 7.0
 * key_radius = 12.0
 * label_scale = 1.1
 * ```
 */
object SkinPrefsFile {
    private val ID_OK = Regex("^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$")

    fun read(file: File): SkinPrefs {
        if (!file.isFile) return SkinPrefs()
        return try {
            val doc = Toml.parse(file.readText())
            val id = doc.getString("active")?.takeIf { it == SkinStore.DEFAULT_ID || ID_OK.matches(it) }
            val g = doc.getTable("geometry")
            fun num(k: String): Float? = (g?.get(k) as? Number)?.toFloat()?.takeIf { it.isFinite() }
            SkinPrefs(
                id ?: SkinStore.DEFAULT_ID,
                GeometryAdjust(num("key_gap_h"), num("row_gap"), num("key_radius"), num("label_scale")).clamped(),
            )
        } catch (e: Exception) {
            SkinPrefs()
        }
    }

    fun write(file: File, prefs: SkinPrefs) {
        file.parentFile?.mkdirs()
        val a = prefs.adjust.clamped()
        fun f(v: Float) = String.format(Locale.ROOT, "%.2f", v)
        val sb = StringBuilder("version = 1\nactive = \"${prefs.active}\"\n")
        if (!a.isEmpty) {
            sb.append("[geometry]\n")
            a.keyGapHDp?.let { sb.append("key_gap_h = ${f(it)}\n") }
            a.rowGapDp?.let { sb.append("row_gap = ${f(it)}\n") }
            a.keyRadiusDp?.let { sb.append("key_radius = ${f(it)}\n") }
            a.labelScale?.let { sb.append("label_scale = ${f(it)}\n") }
        }
        file.writeText(sb.toString())
    }
}
