package com.windinput.ime.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/**
 * 文件关联入口：在文件管理器 / 微信 / 浏览器里点 `.wpkg` / `.zip`（VIEW），或用「分享」发给本应用（SEND）。
 *
 * 只是个透明壳：把 content:// 交给 [SchemeImportController] 复制进缓存目录后走预览 / 导入，
 * 界面与设置页「导入方案」是同一套 [SchemeImportDialogs]，流程结束就 finish。
 * `.zip` 不一定是方案包——核心预览不认时会如实报「不是有效的方案包」，不会装进任何东西。
 */
class SchemeImportActivity : ComponentActivity() {

    private lateinit var controller: SchemeImportController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        controller = SchemeImportController(this, lifecycleScope)
        val uri = sourceUri(intent)
        if (uri == null) {
            finish()
            return
        }
        // 转屏重建时不重复导入：此时流程状态随 Activity 丢失，直接收尾
        if (savedInstanceState == null) controller.begin(uri) else finish()

        val theme = ThemeController(ColorSchemeMode.System)
        setContent {
            MiuixTheme(controller = theme) {
                SchemeImportDialogs(controller)
                LaunchedEffect(controller.state) {
                    if (controller.state is SchemeImportState.Idle) finish()
                }
            }
        }
    }

    override fun onDestroy() {
        // 被系统回收 / 返回键退出时别留缓存文件
        if (isFinishing) controller.dismiss()
        super.onDestroy()
    }

    private fun sourceUri(intent: Intent): Uri? = when (intent.action) {
        Intent.ACTION_VIEW -> intent.data
        Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
        else -> null
    }
}
