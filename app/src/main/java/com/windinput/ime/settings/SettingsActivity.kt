package com.windinput.ime.settings

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 设置入口（Compose + miuix 风格）。
 *
 * 占位阶段：仅提供「启用输入法」引导；正式版由设置清单（settings manifest）
 * 驱动生成页面骨架，与桌面端设置项自动对齐（见 docs/architecture.md §3.3）。
 */
class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MiuixTheme {
                SettingsScreen(
                    onEnableIme = {
                        startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                    },
                )
            }
        }
    }
}

@Composable
private fun SettingsScreen(onEnableIme: () -> Unit) {
    Scaffold(
        topBar = {
            SmallTopAppBar(title = "清风输入法")
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp),
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onEnableIme)
                        .padding(16.dp),
                ) {
                    Text(text = "启用输入法")
                    Text(
                        text = "在系统设置中启用「清风输入法」",
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            Text(
                text = "更多设置项将由设置清单驱动生成",
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}
