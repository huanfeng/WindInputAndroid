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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.windinput.ime.WindInputApp
import com.windinput.ime.core.EngineCmd
import com.windinput.ime.core.ImeSession
import com.windinput.ime.core.LayoutEntry
import com.windinput.ime.core.SchemaEntry
import com.windinput.ime.core.UserConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
                    // 国内系统把「切换输入法」藏得很深（导航栏没有键盘图标，要进好几层设置），
                    // 系统选择框本身却人人可调，这里直接弹
                    onSwitchIme = {
                        getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                            ?.showInputMethodPicker()
                    },
                    onOpenTestPad = {
                        startActivity(Intent(this, TestPadActivity::class.java))
                    },
                )
            }
        }
    }
}

/**
 * 方案与双拼布局：**数据全部来自核心**，本页不留一份自己的清单。
 *
 * 装了什么方案由文件系统说了算（核心扫 `data/schemas` 下的 `.schema.toml`），双拼有哪些布局
 * 同理（扫 `schemas/shuangpin` 下的 `.toml`）。K 侧写死清单的后果是用户往目录里放的东西看不见，
 * 以及删掉某个方案后设置页还列着一个选不动的死项。
 *
 * 核心构造在后台线程（首次约 1 秒），未就绪时整段不渲染——不占位、不显示假清单。
 */
@Composable
private fun SchemaSection() {
    val context = LocalContext.current
    var session by remember { mutableStateOf<ImeSession?>(null) }
    var schemas by remember { mutableStateOf<List<SchemaEntry>>(emptyList()) }
    var layouts by remember { mutableStateOf<List<LayoutEntry>>(emptyList()) }

    LaunchedEffect(Unit) {
        val core = withContext(Dispatchers.IO) { CoreAccess.obtain(context) }
        session = core
        schemas = core?.schemas().orEmpty()
        layouts = core?.shuangpinLayouts().orEmpty()
    }

    val core = session ?: return
    if (schemas.isEmpty()) return

    Text(text = "输入方案", modifier = Modifier.padding(start = 4.dp))
    Card(modifier = Modifier.fillMaxWidth()) {
        Column {
            for (s in schemas) {
                ChoiceRow(
                    title = s.name.ifEmpty { s.id },
                    selected = s.active,
                    onClick = {
                        core.runCommand(EngineCmd.SelectSchema(s.index))
                        // 真值一律回读，不本地翻转：切换可能被引擎拒绝（方案损坏、
                        // 词库缺失），本地先行打勾会造出一个和引擎不一致的选中态。
                        schemas = core.schemas()
                        layouts = core.shuangpinLayouts()
                    },
                )
            }
        }
    }

    // 双拼布局只在装了双拼方案时才有意义
    if (layouts.isNotEmpty() && schemas.any { it.id == SHUANGPIN_SCHEMA_ID }) {
        Text(text = "双拼布局", modifier = Modifier.padding(start = 4.dp))
        Card(modifier = Modifier.fillMaxWidth()) {
            Column {
                for (l in layouts) {
                    ChoiceRow(
                        title = l.name.ifEmpty { l.id },
                        selected = l.active,
                        onClick = {
                            core.runCommand(EngineCmd.SetShuangpinLayout(l.id))
                            layouts = core.shuangpinLayouts()
                        },
                    )
                }
            }
        }
    }
}

/** 双拼方案 id（与 assets/data/schemas/shuangpin.schema.toml 一致） */
private const val SHUANGPIN_SCHEMA_ID = "shuangpin"

@Composable
private fun ChoiceRow(title: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = title, modifier = Modifier.weight(1f))
        if (selected) Text(text = "✓")
    }
}

@Composable
private fun SettingsScreen(
    groups: List<SettingGroup>,
    config: UserConfig,
    onEnableIme: () -> Unit,
    onSwitchIme: () -> Unit,
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
    val strings = remember {
        mutableStateMapOf<String, String>().apply {
            for (group in groups) {
                for (item in group.items) {
                    if (item.type == "select") {
                        put(item.key, config.getString(item.key, item.defaultValue as? String ?: ""))
                    }
                }
            }
        }
    }
    // 标了 reload 的键归引擎读：写完通知它重读配置。UI 侧的键不用——Service 每次弹出都重读
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    fun afterWrite(item: SettingItem) {
        if (!item.reload) return
        scope.launch {
            withContext(Dispatchers.IO) { CoreAccess.obtain(context) }?.runCommand(EngineCmd.ReloadConfig)
        }
    }

    Scaffold(
        topBar = { SmallTopAppBar(title = "清风输入法") },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                // 方案与双拼布局加进来后内容必然超过一屏；没有这一句，后面的
                // 「键盘」等分组会被静默截在屏幕外——看起来像那些设置项没了。
                .verticalScroll(rememberScrollState())
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
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onSwitchIme)
                            .padding(16.dp),
                    ) {
                        Text(text = "切换输入法")
                        Text(
                            text = "弹出系统的输入法选择框，选「清风输入法」即设为当前输入法",
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
                            text = "普通 / 搜索 / 数字 / 密码 / 句子五种场景的输入框，用于试打",
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }

            // 方案排在通用设置项之前：它是这一页里最常改、也最"重"的一项
            SchemaSection()

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
                                        afterWrite(item)
                                    },
                                )
                                "int" -> IntRow(
                                    item = item,
                                    value = ints[item.key] ?: 0,
                                    onValueChange = { v ->
                                        ints[item.key] = v
                                        config.put(item.key, v.toLong())
                                        afterWrite(item)
                                    },
                                )
                                "select" -> SelectRows(
                                    item = item,
                                    value = strings[item.key] ?: "",
                                    onValueChange = { v ->
                                        strings[item.key] = v
                                        config.put(item.key, v)
                                        afterWrite(item)
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

/**
 * 单选项：标题一行 + 每个选项一行打勾（与方案区同一形态）。
 * 不做下拉菜单：选项就三五个，全部摊开比藏进菜单少一次点击，也和 miuix 的列表风格一致。
 */
@Composable
private fun SelectRows(
    item: SettingItem,
    value: String,
    onValueChange: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, end = 16.dp)) {
        Text(text = item.title)
        item.summary?.let { Text(text = it, modifier = Modifier.padding(top = 2.dp)) }
    }
    for ((optValue, label) in item.options) {
        ChoiceRow(title = label, selected = optValue == value, onClick = { onValueChange(optValue) })
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
