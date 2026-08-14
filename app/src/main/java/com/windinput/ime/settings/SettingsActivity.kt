package com.windinput.ime.settings

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.windinput.ime.WindInputApp
import com.windinput.ime.core.UserConfig
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File

/**
 * 设置页：由设置清单（settings_manifest.toml）驱动生成。
 * 值写入用户层 config.toml（与桌面配置域格式一致，Rust 核心接入后直接可读）。
 */
class SettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dirs = (application as WindInputApp).dataDirs
        val userConfig = UserConfig(File(dirs.userDir, "config.toml"))
        val groups = SettingsManifest.load(dirs)
            .map { g -> g.copy(items = g.items.filter { it.platform == null || it.platform == "android" }) }
            .filter { it.items.isNotEmpty() }

        setContent {
            MiuixTheme {
                SettingsScreen(
                    groups = groups,
                    config = userConfig,
                    onEnableIme = {
                        startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                    },
                    onOpenTestPad = {
                        startActivity(Intent(this, TestPadActivity::class.java))
                    },
                )
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    groups: List<SettingGroup>,
    config: UserConfig,
    onEnableIme: () -> Unit,
    onOpenTestPad: () -> Unit,
) {
    // 状态缓存：key → 当前值（初值 = 用户配置 ?: 清单默认）
    val toggles = remember {
        mutableStateMapOf<String, Boolean>().apply {
            for (group in groups) {
                for (item in group.items) {
                    if (item.type == "toggle") {
                        put(item.key, config.getBoolean(item.key, item.defaultValue as? Boolean ?: false))
                    }
                }
            }
        }
    }
    val ints = remember {
        mutableStateMapOf<String, Int>().apply {
            for (group in groups) {
                for (item in group.items) {
                    if (item.type == "int") {
                        put(item.key, config.getInt(item.key, (item.defaultValue as? Long)?.toInt() ?: 0))
                    }
                }
            }
        }
    }

    Scaffold(
        topBar = { SmallTopAppBar(title = "清风输入法") },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
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
                    // 手动试打入口。与自动化测试**共用同一个页面**：改完布局/主题/方案
                    // 能立刻在这里验，不必切到别的 App（借用别家输入框还会被它自己的
                    // 自动补全、场景切换干扰）。
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onOpenTestPad)
                            .padding(16.dp),
                    ) {
                        Text(text = "输入测试台")
                        Text(
                            text = "普通/搜索/数字/密码四种场景的输入框，用于试打",
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }

            for (group in groups) {
                Text(
                    text = group.title,
                    modifier = Modifier.padding(start = 4.dp),
                )
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column {
                        for (item in group.items) {
                            when (item.type) {
                                "toggle" -> ToggleRow(
                                    item = item,
                                    checked = toggles[item.key] == true,
                                    onCheckedChange = { checked ->
                                        toggles[item.key] = checked
                                        config.put(item.key, checked)
                                    },
                                )
                                "int" -> IntRow(
                                    item = item,
                                    value = ints[item.key] ?: 0,
                                    onValueChange = { v ->
                                        ints[item.key] = v
                                        config.put(item.key, v.toLong())
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun IntRow(
    item: SettingItem,
    value: Int,
    onValueChange: (Int) -> Unit,
) {
    val min = item.min ?: Int.MIN_VALUE
    val max = item.max ?: Int.MAX_VALUE
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = item.title)
            item.summary?.let {
                Text(text = it, modifier = Modifier.padding(top = 2.dp))
            }
        }
        Text(
            text = "－",
            modifier = Modifier
                .clickable { onValueChange((value - item.step).coerceAtLeast(min)) }
                .padding(horizontal = 12.dp),
        )
        Text(text = value.toString())
        Text(
            text = "＋",
            modifier = Modifier
                .clickable { onValueChange((value + item.step).coerceAtMost(max)) }
                .padding(horizontal = 12.dp),
        )
    }
}

@Composable
private fun ToggleRow(
    item: SettingItem,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = item.title)
            item.summary?.let {
                Text(text = it, modifier = Modifier.padding(top = 2.dp))
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
