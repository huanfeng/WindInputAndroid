package com.windinput.ime.settings

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
import com.windinput.ime.core.ImeSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import uniffi.wind_android.ConfigException
import uniffi.wind_android.UserWordInfo

/** 每次多取的条数 */
private const val PAGE_SIZE = 50u

/**
 * 词库页：当前方案的个人词（自己加的、打字时学到的）。查看、搜索、删除、加词、清空。
 *
 * 实现与电脑版「词库」页同一份（核心的用户词模块）：拼音族共用一个库、拼音码的
 * ü 归一与音节切分都在核心里做，这里只管显示。
 * 只管**当前方案**：手机上换方案在「方案」页，词库跟着当前方案走，省掉一个方案选择器。
 */
@Composable
internal fun DictSection(session: ImeSession?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val schema = session?.schemas()?.firstOrNull { it.active }
    if (schema == null) {
        SectionTitle("正在启动输入法核心…")
        return
    }

    var query by remember { mutableStateOf("") }
    var words by remember { mutableStateOf<List<UserWordInfo>>(emptyList()) }
    var total by remember { mutableStateOf(0uL) }
    var limit by remember { mutableStateOf(PAGE_SIZE) }
    var reload by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<UserWordInfo?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var newCode by remember { mutableStateOf("") }
    var newText by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(schema.id, query, limit, reload) {
        val page = withContext(Dispatchers.IO) {
            CoreAccess.obtainCore(context)?.userWords(schema.id, query.trim(), 0u, limit)
        }
        words = page?.items.orEmpty()
        total = page?.total ?: 0uL
    }

    /** 写操作放后台，失败把核心给的原因显示出来（只读模式等），成功后重读列表 */
    fun write(done: String, op: (uniffi.wind_android.WindCore) -> Unit) {
        scope.launch {
            message = withContext(Dispatchers.IO) {
                val core = CoreAccess.obtainCore(context) ?: return@withContext "输入法核心未就绪"
                try {
                    op(core)
                    done
                } catch (e: ConfigException) {
                    (e as? ConfigException.Rejected)?.reason ?: e.toString()
                }
            }
            selected = null
            reload++
        }
    }

    SectionTitle("${schema.name.ifEmpty { schema.id }} · 个人词 $total 条")
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            TextField(
                value = query,
                onValueChange = {
                    query = it
                    limit = PAGE_SIZE
                },
                label = "按编码或词搜索",
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        }
        Column {
            if (words.isEmpty()) {
                NavRow(if (query.isBlank()) "还没有个人词" else "没有找到", null, null)
            }
            for (w in words) {
                WordRow(w, selected == w, onClick = { selected = if (selected == w) null else w }) {
                    write("已删除「${w.text}」") { it.userWordRemove(schema.id, w.code, w.text) }
                }
            }
            if (words.size.toULong() < total) {
                NavRow("显示更多", "已显示 ${words.size} / $total 条") { limit += PAGE_SIZE }
            }
        }
    }

    SectionTitle("加词")
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TextField(
                value = newText,
                onValueChange = { newText = it },
                label = "词",
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            TextField(
                value = newCode,
                onValueChange = { newCode = it },
                label = "编码（拼音可用空格分音节，如 ni hao）",
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            TextButton(
                text = "添加",
                onClick = {
                    val code = newCode.trim()
                    val text = newText.trim()
                    if (code.isEmpty() || text.isEmpty()) {
                        message = "词和编码都要填"
                    } else {
                        write("已添加「$text」") { it.userWordAdd(schema.id, code, text) }
                        newCode = ""
                        newText = ""
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
    message?.let {
        Text(
            text = it,
            modifier = Modifier.padding(start = 4.dp),
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }

    SectionTitle("数据")
    Card(modifier = Modifier.fillMaxWidth()) {
        NavRow(
            if (confirmClear) "再点一次确认清空" else "清空个人词",
            if (confirmClear) "「${schema.name}」的个人词全部删除，不能恢复" else "删除当前方案的全部个人词",
        ) {
            if (!confirmClear) {
                confirmClear = true
            } else {
                confirmClear = false
                write("已清空") { it.userWordsClear(schema.id) }
            }
        }
    }
}

/** 一条词：词在左、编码在右；点一下露出「删除」，再点别处收起 */
@Composable
private fun WordRow(word: UserWordInfo, selected: Boolean, onClick: () -> Unit, onDelete: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = word.text, modifier = Modifier.weight(1f))
        Text(text = word.code, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        if (selected) {
            Text(
                text = "删除",
                modifier = Modifier
                    .clickable(onClick = onDelete)
                    .padding(start = 16.dp),
                color = MiuixTheme.colorScheme.primary,
            )
        }
    }
}
