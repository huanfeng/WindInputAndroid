package com.windinput.ime.settings

import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.windinput.ime.WindInputApp
import com.windinput.ime.core.UserConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import uniffi.wind_android.EngineCommand
import java.io.File

/** 三档恢复（键盘 / 输入两档的键见 [SettingsReset]；方案一档见 [SchemesReset]） */
private enum class ResetTier(val title: String, val summary: String) {
    KEYBOARD("恢复键盘设置", "键盘高度、振动与按键音、手势、工具栏等键盘侧设置回到出厂值"),
    ENGINE("恢复输入设置", "输入页、外观、模糊音等全局输入设置回到出厂值；各方案自己的设置不动"),
    SCHEMES("恢复所有方案设置", "每个方案的设置回到方案默认；启用的方案、扩展词库保留"),
}

/**
 * 「高级」页底部的恢复默认设置。用户把配置改乱了，此前只能清数据，词库也跟着丢。
 * 三档分开恢复，都不动词库、扩展词库开关与导入的方案。点了先弹确认框。
 */
@Composable
internal fun ResetSection(values: SettingsValues) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirming by remember { mutableStateOf<ResetTier?>(null) }

    confirming?.let { tier ->
        ConfirmDialog(
            title = tier.title,
            text = "${tier.summary}。词库不受影响。",
            confirmText = "恢复",
            onConfirm = {
                confirming = null
                scope.launch {
                    val message = withContext(Dispatchers.IO) { runReset(context, tier, values) }
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                }
            },
            onDismiss = { confirming = null },
        )
    }

    SectionTitle("恢复默认")
    Card(modifier = Modifier.fillMaxWidth()) {
        for (tier in ResetTier.entries) {
            NavRow(tier.title, tier.summary, icon = SettingsIcons.reset) { confirming = tier }
        }
    }
}

private fun runReset(context: android.content.Context, tier: ResetTier, values: SettingsValues): String = try {
    when (tier) {
        ResetTier.KEYBOARD -> {
            val n = SettingsReset.resetKeyboard(UserConfig((context.applicationContext as WindInputApp).dataDirs.keyboardConfigFile))
            values.touch()
            if (n == 0) "键盘设置已是出厂值" else "已恢复 $n 项键盘设置，下次弹出键盘时生效"
        }
        ResetTier.ENGINE -> {
            val n = SettingsReset.resetEngine((context.applicationContext as WindInputApp).dataDirs.engineConfigFile)
            // 核心没起来也无妨：文件已改，下次构造时读到的就是出厂值
            CoreAccess.obtainCore(context)?.runCommand(EngineCommand.ReloadConfig)
            values.load()
            values.touch()
            if (n == 0) "输入设置已是出厂值" else "已恢复 $n 项输入设置"
        }
        ResetTier.SCHEMES -> {
            val core = CoreAccess.obtainCore(context) ?: return "输入法核心还没启动，稍后再试"
            val ids = core.installedSchemas().map { it.id }
            // 每次写入核心都会重载配置，不用另发 ReloadConfig
            val n = SchemesReset.resetAll(ids, CoreSchemeFormIo(core)) { core.resetSchemaSettings(it) }
            values.touch()
            if (n == 0) "各方案设置已是方案默认" else "已恢复 $n 个方案的设置"
        }
    }
} catch (e: Exception) {
    Log.w("ResetSection", "恢复默认设置失败：$tier", e)
    "恢复失败：${e.message ?: e}"
}
