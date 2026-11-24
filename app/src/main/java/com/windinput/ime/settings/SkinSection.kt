package com.windinput.ime.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.windinput.ime.WindInputApp
import com.windinput.ime.skin.SkinStore
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.RadioButtonLocation
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 外观 →「键盘皮肤」：列出内置与用户皮肤（`mobile/skins/<id>/skin.toml`），选一个即写入 `skin_prefs.toml`；
 * 输入法下次弹出键盘时按文件戳发现变化并重建，不用重启。
 *
 * 「重新载入」：手改了皮肤文件后立即重读一次，把解析与对比度警告列在下面；皮肤坏了（读不出、版本过高、
 * 文件没了）键盘退回默认外观，这里同步给出原因，不让用户对着没变化的键盘猜。
 */
@Composable
internal fun SkinSection() {
    val context = LocalContext.current
    val store = remember { (context.applicationContext as WindInputApp).dataDirs.skinStore() }
    var entries by remember { mutableStateOf(store.list()) }
    var active by remember { mutableStateOf(store.activeId()) }
    var report by remember { mutableStateOf<SkinStore.Activation?>(null) }

    SectionTitle("键盘皮肤")
    Card(modifier = Modifier.fillMaxWidth()) {
        for (e in entries) {
            RadioButtonPreference(
                title = e.name,
                summary = if (e.builtin) null else "自定义 · ${e.id}",
                selected = e.id == active,
                onClick = {
                    store.setActive(e.id)
                    active = e.id
                    report = store.activate(force = true)
                },
                radioButtonLocation = RadioButtonLocation.End,
            )
        }
        NavRow("重新载入", "手改过皮肤文件后点这里；也会列出皮肤的问题") {
            entries = store.list()
            active = store.activeId()
            report = store.activate(force = true)
        }
    }
    report?.let { r ->
        val lines = buildList {
            if (r.fellBack) add("所选皮肤没能载入，键盘仍是默认外观。")
            addAll(r.warnings)
        }
        if (lines.isNotEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    for (l in lines) Text(text = l, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
            }
        }
    }
    GeometrySection()
}
