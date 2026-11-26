package com.windinput.ime.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.CheckboxPreference

/**
 * 「复制到其它方案」对话框：先勾目标方案（默认都不勾，必选，放在上面免得被组列表挤出屏），
 * 再勾要复制的组（默认全选），一次写完。
 * [copy] 在后台线程跑，返回结果；完成后弹一行结果提示。
 */
@Composable
internal fun SchemeCopyDialog(
    sourceName: String,
    blocks: List<Pair<String, List<SettingItem>>>,
    targets: List<SchemeCopy.Target>,
    copy: (items: List<SettingItem>, targets: List<SchemeCopy.Target>) -> SchemeCopy.Result,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pickedBlocks by remember { mutableStateOf(blocks.map { it.first }.toSet()) }
    var pickedTargets by remember { mutableStateOf(emptySet<String>()) }
    var busy by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = { if (!busy) onDismiss() }) {
        Card(Modifier.padding(horizontal = 20.dp).fillMaxWidth()) {
            Column(Modifier.padding(vertical = 20.dp)) {
                Text(
                    "复制「$sourceName」的设置",
                    Modifier.padding(horizontal = 20.dp),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "一次性写给所选方案，之后各改各的。辅助码、整句开关、标点与命令短语不复制。",
                    Modifier.padding(horizontal = 20.dp),
                )
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    SectionTitle("复制到")
                    for (t in targets) {
                        CheckboxPreference(
                            title = t.name,
                            checked = t.id in pickedTargets,
                            onCheckedChange = { on -> pickedTargets = if (on) pickedTargets + t.id else pickedTargets - t.id },
                        )
                    }
                    SectionTitle("复制哪些设置")
                    for ((name, _) in blocks) {
                        CheckboxPreference(
                            title = name,
                            checked = name in pickedBlocks,
                            onCheckedChange = { on -> pickedBlocks = if (on) pickedBlocks + name else pickedBlocks - name },
                        )
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(text = "取消", onClick = onDismiss, enabled = !busy)
                    TextButton(
                        text = "复制",
                        enabled = !busy && pickedBlocks.isNotEmpty() && pickedTargets.isNotEmpty(),
                        onClick = {
                            busy = true
                            val items = blocks.filter { it.first in pickedBlocks }.flatMap { it.second }
                            val to = targets.filter { it.id in pickedTargets }
                            scope.launch {
                                val r = withContext(Dispatchers.IO) { copy(items, to) }
                                Toast.makeText(context, copyResultText(r), Toast.LENGTH_LONG).show()
                                onDismiss()
                            }
                        },
                    )
                }
            }
        }
    }
}

/** 结果提示：成功几个、哪些失败及原因 */
internal fun copyResultText(r: SchemeCopy.Result): String {
    val ok = if (r.copied.isEmpty()) "" else "已复制到 ${r.copied.joinToString("、")}"
    val bad = r.failed.joinToString("；") { (name, why) -> "$name 失败：$why" }
    return listOf(ok, bad).filter { it.isNotEmpty() }.joinToString("\n")
}
