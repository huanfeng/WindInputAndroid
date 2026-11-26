package com.windinput.ime.settings

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 设置页的外框零件：标题栏的「恢复默认」、对话框按钮行、返回后恢复滚动位置。
 */

/** 一屏标题栏右侧的「恢复默认」：页面内容用 [ResetInTitle] 挂上，离开时自动摘掉 */
internal class TitleActions {
    var reset by mutableStateOf<(() -> Unit)?>(null)
}

internal val LocalTitleActions = staticCompositionLocalOf { TitleActions() }

/**
 * 在本屏标题栏放「恢复默认」。安卓设置没有「应用 / 保存」，改了即生效，所以不在每行标「已自定义」
 * （行高随之变、界面抖），而是每页一个恢复入口。点了由 [onReset] 自己决定确认与提示。
 */
@Composable
internal fun ResetInTitle(onReset: () -> Unit) {
    val holder = LocalTitleActions.current
    val latest by rememberUpdatedState(onReset)
    DisposableEffect(holder) {
        holder.reset = { latest() }
        onDispose { holder.reset = null }
    }
}

/** 标题栏右侧的文字按钮：图标（回转箭头）容易被看成「返回」，直接写字 */
@Composable
internal fun TitleTextAction(text: String, onClick: () -> Unit) {
    Text(
        text,
        modifier = Modifier
            .padding(end = 12.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        color = MiuixTheme.colorScheme.primary,
        fontSize = 16.sp,
    )
}

/** 对话框底部按钮行：靠右，按钮之间留空（miuix 按钮自带底色，贴在一起像一整块） */
@Composable
internal fun DialogButtons(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
        content = content,
    )
}

/** 确认对话框：标题、一段说明、取消 / [confirmText] */
@Composable
internal fun ConfirmDialog(
    title: String,
    text: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(Modifier.padding(horizontal = 20.dp).fillMaxWidth()) {
            Column(Modifier.padding(20.dp)) {
                Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                Text(text)
                Spacer(Modifier.height(16.dp))
                DialogButtons {
                    TextButton(text = "取消", onClick = onDismiss)
                    TextButton(text = confirmText, onClick = onConfirm)
                }
            }
        }
    }
}

/** 恢复滚动最多等内容加载这么久；再久就按当时的高度能滚多少算多少 */
private const val RESTORE_WAIT_MS = 2000L

/**
 * 返回上一级后停在离开时的位置。`rememberScrollState` 自己也会存位置，但内容异步加载的页
 * （方案设置读核心）回来那一刻还是空的，存下的位置被截成 0，加载完已回不去了。
 * 这里另存一份目标位置，等内容高到够滚过去再滚，滚到之前不拿截断后的值覆盖它。
 */
@Composable
internal fun rememberRestoringScrollState(): ScrollState {
    val scroll = remember { ScrollState(0) }
    var target by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(scroll) {
        if (target > 0) {
            val want = target
            // 未测量时 maxValue 是 Int.MAX_VALUE，不算数
            withTimeoutOrNull(RESTORE_WAIT_MS) {
                snapshotFlow { scroll.maxValue }.first { it != Int.MAX_VALUE && it >= want }
            }
            scroll.scrollTo(want)
        }
        snapshotFlow { scroll.value }.collect { target = it }
    }
    return scroll
}
