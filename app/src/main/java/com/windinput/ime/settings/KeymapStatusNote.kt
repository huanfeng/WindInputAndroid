package com.windinput.ime.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.windinput.ime.WindInputApp
import com.windinput.ime.keyboard.keymap.KeymapParser
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 「键位」小节所在的二级页名（settings_manifest.toml 里的 section） */
internal const val KEYMAP_SECTION = "键位"

/** 提示文案：纯函数，JVM 单测直接测 */
internal fun keymapStatusText(tooNew: Boolean, version: Int, warnings: Int): String? = when {
    tooNew -> "mobile/keymap.toml 来自更新版本（version=$version），当前版本不认识，已整份忽略；文件原样保留，不会被改写。"
    warnings > 0 -> "mobile/keymap.toml 里有 $warnings 处写法无法识别，已跳过，其余照常生效（详情见日志）。"
    else -> null
}

/** 用户键位文件的状态提示：版本过高 / 有被跳过的项才显示，正常时什么都不占 */
@Composable
internal fun KeymapStatusNote() {
    val context = LocalContext.current
    val file = (context.applicationContext as WindInputApp).dataDirs.keymapFile
    val text = remember {
        val r = KeymapParser.load(file)
        keymapStatusText(r.tooNew, r.version, r.warnings.size)
    } ?: return
    Card(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = text,
            modifier = Modifier.padding(16.dp),
            fontSize = 14.sp,
            lineHeight = 22.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}
