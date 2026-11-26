package com.windinput.ime.settings

import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import uniffi.wind_android.ConfigException

/**
 * 「联想输入」页底部的学习数据：联想里选过的词、相邻上屏学到的「下一词」，共几条，可清空。
 *
 * 联想被自己的输入带偏时（学了一对不想要的搭配），用户只想重置这一项——词频、个人词不动。
 * 清空跨全部方案（停用了的方案下学到的也算），点了先弹确认框。
 */
@Composable
internal fun AssocLearningSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var count by remember { mutableStateOf<Long?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var confirm by remember { mutableStateOf(false) }

    LaunchedEffect(reload) {
        count = withContext(Dispatchers.IO) {
            runCatching { CoreAccess.obtainCore(context)?.assocHistoryCount()?.toLong() }.getOrNull()
        }
    }
    val n = count ?: return
    if (confirm) {
        ConfirmDialog(
            title = "清空联想学习",
            text = "$n 条联想学习全部删除，不能恢复。词频、个人词不受影响。",
            confirmText = "清空",
            onConfirm = {
                confirm = false
                scope.launch {
                    val message = withContext(Dispatchers.IO) {
                        val core = CoreAccess.obtainCore(context) ?: return@withContext "输入法核心未就绪"
                        try {
                            "已清空 ${core.assocHistoryClear()} 条"
                        } catch (e: ConfigException) {
                            (e as? ConfigException.Rejected)?.reason ?: e.toString()
                        } catch (e: Exception) {
                            "操作失败：${e.message ?: e}"
                        }
                    }
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                    reload++
                }
            },
            onDismiss = { confirm = false },
        )
    }

    SectionTitle("学习数据")
    Card(modifier = Modifier.fillMaxWidth()) {
        NavRow(
            "清空联想学习",
            if (n == 0L) "还没有学到搭配" else "已学到 $n 条搭配（联想里选过的词、常连着打的词）",
            icon = SettingsIcons.clear,
        ) {
            if (n > 0L) confirm = true
        }
    }
}
