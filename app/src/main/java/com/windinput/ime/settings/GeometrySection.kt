package com.windinput.ime.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.windinput.ime.WindInputApp
import com.windinput.ime.core.UserConfig
import com.windinput.ime.skin.GeometryAdjust
import com.windinput.ime.skin.SkinGeometry
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * 外观 →「键盘几何」：键间距、行间距、圆角、字号四个滑块，上面一块按**真实尺寸**画的预览随拖动实时变。
 *
 * 值落在 `mobile/skin_prefs.toml` 的 `[geometry]`（全是 dp / 倍率，可随皮肤偏好同步）；松手才写盘，
 * 输入法下次弹键盘时发现几何变了就重建输入视图。**行高不在这里**（设备 / 手型相关，在「键盘 → 键盘高度」）：
 * 调行间距不改整体高度，键帽高随之变。每项带「恢复默认」，页尾还有一个全部恢复。
 */
@Composable
internal fun GeometrySection() {
    val context = LocalContext.current
    val dirs = remember { (context.applicationContext as WindInputApp).dataDirs }
    val store = remember { dirs.skinStore() }
    val rowHeightDp = remember { UserConfig(dirs.keyboardConfigFile).getInt("ui.android.row_height_portrait", 60) }
    var adjust by remember { mutableStateOf(store.geometryAdjust()) }
    var confirmAll by remember { mutableStateOf(false) }
    val base = remember { SkinGeometry() }

    fun save(a: GeometryAdjust) {
        adjust = a
        store.setGeometryAdjust(a)
    }

    SectionTitle("键盘几何")
    Card(modifier = Modifier.fillMaxWidth()) {
        GeometryPreview(adjust, base, rowHeightDp)
        GeometrySlider(
            title = "键间距",
            summary = "左右两键之间的空隙",
            value = adjust.keyGapHDp ?: base.keyGapHDp,
            range = GeometryAdjust.KEY_GAP_H,
            step = 0.2f,
            format = { String.format(Locale.ROOT, "%.1f dp", it) },
            changed = adjust.keyGapHDp != null,
            onDrag = { adjust = adjust.copy(keyGapHDp = it) },
            onFinish = { save(adjust) },
            onReset = { save(adjust.copy(keyGapHDp = null)) },
        )
        GeometrySlider(
            title = "行间距",
            summary = "上下两行之间的空隙；键盘总高度不变，键帽随之变矮或变高",
            value = adjust.rowGapDp ?: defaultRowGap(rowHeightDp, base),
            range = GeometryAdjust.ROW_GAP,
            step = 1f,
            format = { String.format(Locale.ROOT, "%.0f dp", it) },
            changed = adjust.rowGapDp != null,
            onDrag = { adjust = adjust.copy(rowGapDp = it) },
            onFinish = { save(adjust) },
            onReset = { save(adjust.copy(rowGapDp = null)) },
        )
        GeometrySlider(
            title = "键帽圆角",
            summary = "0 是直角，越大越圆",
            value = adjust.keyRadiusDp ?: base.keyRadiusDp,
            range = GeometryAdjust.KEY_RADIUS,
            step = 1f,
            format = { String.format(Locale.ROOT, "%.0f dp", it) },
            changed = adjust.keyRadiusDp != null,
            onDrag = { adjust = adjust.copy(keyRadiusDp = it) },
            onFinish = { save(adjust) },
            onReset = { save(adjust.copy(keyRadiusDp = null)) },
        )
        GeometrySlider(
            title = "键面字号",
            summary = "按键上的字与角标一起放大缩小",
            value = adjust.labelScale ?: 1f,
            range = GeometryAdjust.LABEL_SCALE,
            step = 0.05f,
            format = { "${Math.round(it * 100)}%" },
            changed = adjust.labelScale != null,
            onDrag = { adjust = adjust.copy(labelScale = it) },
            onFinish = { save(adjust) },
            onReset = { save(adjust.copy(labelScale = null)) },
        )
        NavRow("全部恢复默认", "四项都回到出厂值") { confirmAll = true }
    }
    if (confirmAll) {
        ConfirmDialog(
            title = "全部恢复默认",
            text = "键间距、行间距、键帽圆角、键面字号四项都回到出厂值。",
            confirmText = "恢复",
            onConfirm = {
                confirmAll = false
                save(GeometryAdjust())
            },
            onDismiss = { confirmAll = false },
        )
    }
}

/** 没固定行间距时的实际值：行高 − 键帽高，至少 2dp（与 [com.windinput.ime.keyboard.KeyboardDimens.keyGapVPx] 同一条规则） */
internal fun defaultRowGap(rowHeightDp: Int, base: SkinGeometry): Float = max(rowHeightDp - base.keyHeightDp, 2f)

/**
 * 预览用的键帽高与间隙（dp）。与键盘的取值规则一致：行间距被固定时间隙 = 它（夹在 [2, 行高 − 36]），
 * 键帽高 = 行高 − 间隙；没固定时沿用「行高 − 键帽高」。
 */
internal fun previewKeyRect(rowHeightDp: Int, adjust: GeometryAdjust, base: SkinGeometry): Pair<Float, Float> {
    val gap = adjust.rowGapDp
        ?.coerceIn(2f, max(2f, rowHeightDp - com.windinput.ime.keyboard.KeyboardDimens.MIN_KEY_HEIGHT_DP))
        ?: defaultRowGap(rowHeightDp, base)
    return (rowHeightDp - gap) to gap
}

@Composable
private fun GeometryPreview(adjust: GeometryAdjust, base: SkinGeometry, rowHeightDp: Int) {
    val a = adjust.clamped()
    val gapH = a.keyGapHDp ?: base.keyGapHDp
    val scale = a.labelScale ?: 1f
    val (keyH, rowGap) = previewKeyRect(rowHeightDp, a, base)
    val radius = min(a.keyRadiusDp ?: base.keyRadiusDp, keyH / 2)
    val keyColor = MiuixTheme.colorScheme.dividerLine
    val textColor = MiuixTheme.colorScheme.onSurface
    val measurer = rememberTextMeasurer()
    val rows = listOf("QWERTYUIOP", "ASDFGHJKL", "ZXCVBNM")
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = base.edgePadDp.dp, vertical = 8.dp)
            .height((rowHeightDp * rows.size).dp),
    ) {
        val density = this.density
        val slot = size.width / 10f
        rows.forEachIndexed { r, keys ->
            val indent = (10 - keys.length) * slot / 2
            keys.forEachIndexed { k, c ->
                val left = indent + k * slot + gapH / 2 * density
                val top = r * rowHeightDp * density + rowGap / 2 * density
                val w = slot - gapH * density
                val h = keyH * density
                drawRoundRect(keyColor, Offset(left, top), Size(w, h), CornerRadius(radius * density))
                val layout = measurer.measure(c.toString(), TextStyle(fontSize = (18 * scale).sp, color = textColor))
                drawText(layout, topLeft = Offset(left + (w - layout.size.width) / 2, top + (h - layout.size.height) / 2))
            }
        }
    }
}

@Composable
private fun GeometrySlider(
    title: String,
    summary: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    step: Float,
    format: (Float) -> String,
    changed: Boolean,
    onDrag: (Float) -> Unit,
    onFinish: () -> Unit,
    onReset: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title)
                Text(
                    text = summary,
                    modifier = Modifier.padding(top = 2.dp),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            if (changed) {
                Text(
                    text = "恢复默认",
                    color = MiuixTheme.colorScheme.primary,
                    modifier = Modifier.clickable(onClick = onReset),
                )
            }
            Text(text = format(value))
        }
        Slider(
            value = value,
            onValueChange = { onDrag(snapFloat(it, range, step)) },
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            valueRange = range,
            onValueChangeFinished = onFinish,
        )
    }
}

/** 滑块值吸附到步长网格（从下限起算）并钳进范围；纯函数，单测直接跑 */
internal fun snapFloat(raw: Float, range: ClosedFloatingPointRange<Float>, step: Float): Float {
    val snapped = range.start + Math.round((raw - range.start) / step) * step
    return snapped.coerceIn(range.start, range.endInclusive)
}
