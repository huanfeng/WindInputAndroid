package com.windinput.ime.settings

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import uniffi.wind_android.ConfigException
import uniffi.wind_android.PhraseInfo
import uniffi.wind_android.WindCore

/**
 * 快捷短语：打编码出一段固定文字（地址、常用回复）或模板（日期、时间）。
 *
 * 与电脑版短语管理同一份数据（系统短语 + 用户短语的叠加视图）。系统短语只能开关——出厂关着的
 * （时间戳 tmts）在这里打开；自己加的可删。此前手机设置里没有这一页，出厂关闭的系统短语
 * 根本打不开。
 */
@Composable
internal fun PhrasesSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var phrases by remember { mutableStateOf<List<PhraseInfo>?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var newCode by remember { mutableStateOf("") }
    var newText by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<PhraseInfo?>(null) }

    LaunchedEffect(reload) {
        phrases = withContext(Dispatchers.IO) {
            runCatching { CoreAccess.obtainCore(context)?.phrases() }.getOrNull()
        }
    }

    /** 写操作放后台；失败报核心给的原因（只读模式等），成功后重读列表 */
    fun write(done: String?, onSuccess: () -> Unit = {}, op: (WindCore) -> Unit) {
        scope.launch {
            val error = withContext(Dispatchers.IO) {
                val core = CoreAccess.obtainCore(context) ?: return@withContext "输入法核心未就绪"
                try {
                    op(core)
                    null
                } catch (e: ConfigException) {
                    (e as? ConfigException.Rejected)?.reason ?: e.toString()
                } catch (e: Exception) {
                    "操作失败：${e.message ?: e}"
                }
            }
            (error ?: done)?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
            if (error == null) onSuccess()
            selected = null
            reload++
        }
    }

    val all = phrases
    if (all == null) {
        SectionTitle("正在读取…")
        return
    }

    SectionTitle("添加短语")
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TextField(
                value = newText,
                onValueChange = { newText = it },
                label = "内容（如地址、常用回复）",
                modifier = Modifier.fillMaxWidth(),
            )
            TextField(
                value = newCode,
                onValueChange = { newCode = it },
                label = "编码（打这个编码时出现在候选里）",
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            TextButton(
                text = "添加",
                enabled = newCode.isNotBlank() && newText.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    val (code, text) = newCode.trim() to newText.trim()
                    write("已添加「$text」", onSuccess = { newCode = ""; newText = "" }) { it.phraseAdd(code, text) }
                },
            )
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            TextField(
                value = query,
                onValueChange = { query = it },
                label = "按编码或内容搜索",
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        }
    }
    val q = query.trim()
    val shown = if (q.isEmpty()) all else all.filter { q in it.code || q in it.text }
    val (system, mine) = shown.partition { it.isSystem }

    SectionTitle("我的短语 · ${mine.size} 条")
    Card(modifier = Modifier.fillMaxWidth()) {
        if (mine.isEmpty()) NavRow(if (q.isEmpty()) "还没有自己加的短语" else "没有找到", null, null)
        for (p in mine) {
            PhraseRow(
                phrase = p,
                selected = selected == p,
                onClick = { selected = if (selected == p) null else p },
                onToggle = { on -> write(null) { it.phraseSetEnabled(p.code, p.text, on) } },
                onDelete = { write("已删除「${p.text}」") { it.phraseRemove(p.code, p.text) } },
            )
        }
    }

    SectionTitle("系统短语 · ${system.size} 条")
    Card(modifier = Modifier.fillMaxWidth()) {
        if (system.isEmpty()) NavRow("没有找到", null, null)
        for (p in system) {
            PhraseRow(
                phrase = p,
                selected = false,
                onClick = null,
                onToggle = { on -> write(null) { it.phraseSetEnabled(p.code, p.text, on) } },
                onDelete = null,
            )
        }
        NavRow("系统短语恢复默认", "开关回到出厂设定（出厂关闭的仍关闭），自己加的不动") {
            write("已恢复默认") { it.phrasesRestoreDefaults() }
        }
    }
}

/**
 * 一条短语：编码 + 内容 + 开关。用户短语点一下行尾出「删除」（同「我的词条」）；
 * 系统短语删不掉，只能关。
 */
@Composable
private fun PhraseRow(
    phrase: PhraseInfo,
    selected: Boolean,
    onClick: (() -> Unit)?,
    onToggle: (Boolean) -> Unit,
    onDelete: (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = phrase.code)
            Text(
                text = phrase.text,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 2,
            )
        }
        if (selected && onDelete != null) {
            Text(
                text = "删除",
                modifier = Modifier
                    .clickable(onClick = onDelete)
                    .padding(horizontal = 12.dp),
                color = MiuixTheme.colorScheme.primary,
            )
        }
        Switch(checked = phrase.enabled, onCheckedChange = onToggle)
    }
}
