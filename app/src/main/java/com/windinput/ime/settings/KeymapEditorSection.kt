package com.windinput.ime.settings

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.windinput.ime.WindInputApp
import com.windinput.ime.core.UserConfig
import com.windinput.ime.keyboard.KeyAction
import com.windinput.ime.keyboard.KeyDef
import com.windinput.ime.keyboard.KeyboardLayer
import com.windinput.ime.keyboard.LayoutRepository
import com.windinput.ime.keyboard.LayoutTweaks
import com.windinput.ime.keyboard.keymap.Gesture
import com.windinput.ime.keyboard.keymap.GestureOverride
import com.windinput.ime.keyboard.keymap.KeyOutput
import com.windinput.ime.keyboard.keymap.KeymapMerge
import com.windinput.ime.keyboard.keymap.KeymapParser
import com.windinput.ime.keyboard.keymap.KeymapWriter
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File

internal const val SUB_KEYMAP_EDITOR = "自定义键位"

/** 页面编辑的层：本阶段只做字母层（数字 / 符号页的键位另议） */
private const val EDIT_LAYER = "letters"

/** 键盘页底部的入口：摘要说明编辑了几个键 */
@Composable
internal fun KeymapEditorEntry(onOpen: () -> Unit) {
    val context = LocalContext.current
    val dirs = (context.applicationContext as WindInputApp).dataDirs
    val count = KeymapParser.load(dirs.keymapEditorFile).layers[EDIT_LAYER]?.size ?: 0
    SectionTitle("自定义键位")
    Card(modifier = Modifier.fillMaxWidth()) {
        NavRow(
            SUB_KEYMAP_EDITOR,
            if (count == 0) "点选键盘上的键，改它的上滑 / 下滑 / 长按" else "已自定义 $count 个键",
            null,
            onClick = onOpen,
        )
    }
}

/**
 * 「自定义键位」：键盘缩略图里点一个键，改它的上滑 / 下滑 / 长按。
 *
 * - 改动**只存内存，离开本页才落盘**（避免半成品被键盘读到）；落到 `mobile/keymap_editor.toml`，
 *   与用户手写的 `keymap.toml` 分开：本页整份重写前者，不会动后者里的手写内容 / 注释；叠加时手写的优先。
 * - 默认键位一个字都不改：没编辑过的键、点了「恢复默认」的手势都不在文件里。
 */
@Composable
internal fun KeymapEditorSection() {
    val context = LocalContext.current
    val dirs = (context.applicationContext as WindInputApp).dataDirs
    val rows = remember {
        val cfg = UserConfig(dirs.keyboardConfigFile)
        LayoutTweaks.apply(
            LayoutRepository(dirs).rows(KeyboardLayer.LETTERS), KeyboardLayer.LETTERS,
            bottomRow = cfg.getString("ui.android.bottom_row", LayoutTweaks.BOTTOM_SPLIT),
            numberPage = cfg.getString("ui.android.number_page", LayoutTweaks.NUMBER_PAD9),
        )
    }
    var edits by remember {
        mutableStateOf(KeymapParser.load(dirs.keymapEditorFile).layers[EDIT_LAYER].orEmpty())
    }
    // 手写文件里已有的覆盖：页面改不了它们，只提示
    val handWritten = remember { KeymapParser.load(dirs.keymapFile).layers[EDIT_LAYER].orEmpty() }
    var selectedId by remember { mutableStateOf<String?>(null) }
    // 常用符号快捷条追加到哪个输入：手势 + 当前输入由子组件登记
    // 不用 state：登记发生在事件里（输入框获得焦点），读取发生在点快捷符号时，都不该触发重组
    val quickTarget = remember { arrayOfNulls<(String) -> Unit>(1) }
    var confirmAll by remember { mutableStateOf(false) }
    var confirmAt by remember { mutableLongStateOf(0L) }

    // 离开本页才落盘（最新的 edits 通过 rememberUpdatedState 之外的引用取：用 holder）
    val holder = remember { arrayOf(edits) }
    holder[0] = edits
    DisposableEffect(Unit) {
        onDispose { persist(dirs.keymapEditorFile, holder[0]) }
    }

    SectionTitle("点选要改的键")
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (row in rows) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    for (key in row) {
                        if (key.action == KeyAction.Spacer) {
                            Box(modifier = Modifier.weight(key.widthWeight))
                        } else {
                            KeyCell(
                                key = key,
                                selected = key.id == selectedId,
                                edited = key.id in edits,
                                modifier = Modifier.weight(key.widthWeight),
                                onClick = { if (KeymapEdit.editable(key)) selectedId = key.id },
                            )
                        }
                    }
                }
            }
        }
    }

    val key = rows.flatten().firstOrNull { it.id == selectedId }
    if (key == null) {
        Text(
            text = "点上面键盘里的一个键开始。圆点表示这个键已自定义；退格键有自己的手势，不能改。",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            fontSize = 13.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    } else {
        androidx.compose.runtime.key(key.id) {
            SectionTitle("「${key.label.ifBlank { key.id }}」键")
            val mine = edits[key.id].orEmpty()
            val hand = handWritten[key.id].orEmpty()
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    for (g in listOf(Gesture.SWIPE_UP, Gesture.SWIPE_DOWN)) {
                        GestureRow(
                            title = if (g == Gesture.SWIPE_UP) "上滑" else "下滑",
                            current = mine[g]?.output,
                            handWritten = hand[g] != null,
                            onChange = { edits = KeymapEdit.withGesture(edits, key.id, g, it?.let { o -> GestureOverride(output = o) }) },
                            registerQuick = { quickTarget[0] = it },
                        )
                    }
                    BubbleRow(
                        current = mine[Gesture.LONG_PRESS],
                        handWritten = hand[Gesture.LONG_PRESS] != null,
                        hasBubble = key.longPress.isNotEmpty() || (key.action as? KeyAction.Text)?.char?.isLetter() == true,
                        onChange = { edits = KeymapEdit.withGesture(edits, key.id, Gesture.LONG_PRESS, it) },
                        registerQuick = { quickTarget[0] = it },
                    )
                }
            }
            SectionTitle("常用符号（点一下加到当前输入框）")
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()).padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    for (c in KeymapEdit.QUICK_SYMBOLS) {
                        Chip(text = c.toString(), selected = false) { quickTarget[0]?.invoke(c.toString()) }
                    }
                }
            }
        }
    }

    if (confirmAll) {
        LaunchedEffect(confirmAt) {
            delay(CONFIRM_RESET_MS)
            confirmAll = false
        }
    }
    if (edits.isNotEmpty()) {
        Card(modifier = Modifier.fillMaxWidth()) {
            NavRow(
                if (confirmAll) "再点一次确认全部恢复" else "全部恢复默认",
                if (confirmAll) "本页自定义的 ${edits.size} 个键都回到出厂 / 预设（手写的 keymap.toml 不受影响）" else "清掉本页做过的所有自定义",
                icon = SettingsIcons.clear,
                highlight = confirmAll,
            ) {
                val now = SystemClock.uptimeMillis()
                if (!confirmAll) {
                    confirmAll = true
                    confirmAt = now
                } else if (now - confirmAt >= CONFIRM_MIN_GAP_MS) {
                    confirmAll = false
                    edits = emptyMap()
                }
            }
        }
    }
}

/** 整份重写 keymap_editor.toml；没有任何覆盖就删文件（回到零开销）。tmp + rename，写到一半不会被键盘读到 */
internal fun persist(file: File, edits: Map<String, Map<Gesture, GestureOverride>>) {
    runCatching {
        if (edits.isEmpty()) {
            file.delete()
        } else {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(KeymapWriter.write(mapOf(EDIT_LAYER to edits)))
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText())
                tmp.delete()
            }
        }
    }
}

@Composable
private fun KeyCell(key: KeyDef, selected: Boolean, edited: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = MiuixTheme.colorScheme
    val editable = KeymapEdit.editable(key)
    Box(
        modifier = modifier
            .height(40.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(
                when {
                    selected -> colors.primary.copy(alpha = 0.25f)
                    editable -> colors.onSurface.copy(alpha = 0.08f)
                    else -> colors.onSurface.copy(alpha = 0.03f)
                },
            )
            .clickable(enabled = editable, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = key.label.ifBlank { key.id }, fontSize = 13.sp, color = colors.onSurface.copy(alpha = if (editable) 1f else 0.4f))
        if (edited) {
            Box(
                modifier = Modifier.align(Alignment.TopEnd).padding(3.dp).size(6.dp)
                    .clip(RoundedCornerShape(3.dp)).background(colors.primary),
            )
        }
    }
}

@Composable
private fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    val colors = MiuixTheme.colorScheme
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) colors.primary.copy(alpha = 0.25f) else colors.onSurface.copy(alpha = 0.08f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text = text, fontSize = 14.sp) }
}

/** 上滑 / 下滑的一行：类型 + 输入 + 恢复默认 */
@Composable
private fun GestureRow(
    title: String,
    current: KeyOutput?,
    handWritten: Boolean,
    onChange: (KeyOutput?) -> Unit,
    registerQuick: ((String) -> Unit) -> Unit,
) {
    var kind by remember { mutableStateOf(KeymapEdit.kindOf(current)) }
    var input by remember { mutableStateOf(KeymapEdit.inputOf(current)) }
    fun commit(k: EditKind, text: String) {
        kind = k
        input = text
        onChange(KeymapEdit.output(k, text))
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = title, fontSize = 15.sp, modifier = Modifier.weight(1f))
            if (kind != EditKind.NONE) {
                TextButton(text = "恢复默认", onClick = { commit(EditKind.NONE, "") })
            }
        }
        Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (k in EditKind.entries) {
                Chip(k.label, selected = kind == k) {
                    // 换类型时清掉不合法的旧输入（功能名只能从下面选）
                    commit(k, if (k == kind) input else if (k == EditKind.FUNC) "" else input.takeIf { kind != EditKind.FUNC }.orEmpty())
                }
            }
        }
        when (kind) {
            EditKind.NONE -> Unit
            EditKind.FUNC -> Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                for (f in KeymapEdit.FUNC_CHOICES) Chip(KeymapMerge.funcLabel(f), selected = input == f) { commit(EditKind.FUNC, f) }
            }
            else -> {
                if (kind == EditKind.TEMPLATE) {
                    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (t in KeymapEdit.TEMPLATE_CHOICES) Chip(t, selected = input == t) { commit(EditKind.TEMPLATE, t) }
                    }
                }
                TextField(
                    value = input,
                    onValueChange = { commit(kind, it) },
                    label = when (kind) {
                        EditKind.TEMPLATE -> "模板（{date} {time} {weekday} …）"
                        EditKind.PHRASE -> "命令短语的编码"
                        else -> "上屏的字符或文本"
                    },
                    modifier = Modifier.fillMaxWidth().onFocusChanged {
                        // 快捷符号追加到焦点所在的输入框
                        if (it.isFocused) registerQuick { s -> commit(kind, input + s) }
                    },
                    singleLine = true,
                )
            }
        }
        if (handWritten) HandWrittenNote()
    }
}

/** 长按气泡：一个输入框，每个字符一项 */
@Composable
private fun BubbleRow(
    current: GestureOverride?,
    handWritten: Boolean,
    hasBubble: Boolean,
    onChange: (GestureOverride?) -> Unit,
    registerQuick: ((String) -> Unit) -> Unit,
) {
    var input by remember { mutableStateOf(KeymapEdit.bubbleToString(current?.bubble.orEmpty())) }
    var active by remember { mutableStateOf(current != null) }
    fun commit(text: String, on: Boolean) {
        input = text
        active = on
        // 打开却一个字没写 = 不覆盖（不把「清空气泡」当成默认动作）
        onChange(if (on && text.isNotEmpty()) GestureOverride(bubble = KeymapEdit.bubbleFromString(text)) else null)
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = "长按气泡", fontSize = 15.sp, modifier = Modifier.weight(1f))
            if (active) TextButton(text = "恢复默认", onClick = { commit("", false) })
        }
        if (!active) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Chip(if (hasBubble) "自定义气泡内容" else "加一个气泡", selected = false) { active = true }
            }
        } else {
            TextField(
                value = input,
                onValueChange = { commit(it, true) },
                label = "气泡里的字符（每个字符一项，顺序即位置）",
                modifier = Modifier.fillMaxWidth().onFocusChanged {
                    if (it.isFocused) registerQuick { s -> commit(input + s, true) }
                },
                singleLine = true,
            )
        }
        if (handWritten) HandWrittenNote()
    }
}

@Composable
private fun HandWrittenNote() {
    Text(
        text = "keymap.toml 里手写了这一项，优先于本页的设置",
        fontSize = 12.sp,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
    )
}
