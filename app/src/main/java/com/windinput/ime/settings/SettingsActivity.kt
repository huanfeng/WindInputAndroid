package com.windinput.ime.settings

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
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
 * 设置页：由设置清单（assets/data/settings_manifest.toml）驱动生成，结构见
 * docs/mobile-ux-reference.md §6——首页三段（与电脑版相同 / 手机键盘 / 收尾），
 * 每段一行一个页面并带一行摘要；点进去是按卡片分组的设置项，工程参数收在「进阶」里。
 *
 * 方案列表、统计概览、关于是代码画的专属区块，其余全部来自清单。
 */
class SettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dirs = (application as WindInputApp).dataDirs
        val userConfig = UserConfig(File(dirs.userDir, "config.toml"))
        val manifest = SettingsManifest.load(this)
        val versionName = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull().orEmpty()

        setContent {
            MiuixTheme {
                SettingsApp(
                    manifest = manifest,
                    userConfig = userConfig,
                    versionName = versionName,
                    actions = SettingsActions(
                        enableIme = { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) },
                        // 国内系统把「切换输入法」藏得很深（导航栏没有键盘图标，要进好几层设置），
                        // 系统选择框本身却人人可调，这里直接弹
                        switchIme = {
                            getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                                ?.showInputMethodPicker()
                        },
                        openTestPad = { startActivity(Intent(this, TestPadActivity::class.java)) },
                    ),
                )
            }
        }
    }
}

/** 首页状态卡上的三个动作 */
private class SettingsActions(
    val enableIme: () -> Unit,
    val switchIme: () -> Unit,
    val openTestPad: () -> Unit,
)

@Composable
private fun SettingsApp(
    manifest: SettingsManifestData,
    userConfig: UserConfig,
    versionName: String,
    actions: SettingsActions,
) {
    val context = LocalContext.current
    var session by remember { mutableStateOf<ImeSession?>(null) }
    var values by remember { mutableStateOf(SettingsValues(userConfig, null)) }
    var pageId by rememberSaveable { mutableStateOf<String?>(null) }

    // 核心构造在后台线程（首次约 1 秒）。未就绪时键盘项照常可改，引擎项先不显示
    LaunchedEffect(Unit) {
        val (s, v) = withContext(Dispatchers.IO) {
            val core = CoreAccess.obtainCore(context)
            CoreAccess.obtain(context) to SettingsValues(userConfig, core).also { it.load() }
        }
        session = s
        values = v
    }

    BackHandler(enabled = pageId != null) { pageId = null }
    val page = manifest.pages.firstOrNull { it.id == pageId }

    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = page?.title ?: "清风输入法",
                navigationIcon = {
                    if (page != null) {
                        Text(
                            text = "‹",
                            modifier = Modifier
                                .clickable { pageId = null }
                                .padding(horizontal = 16.dp),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (page == null) {
                HomeScreen(manifest, values, session, versionName, actions) { pageId = it }
            } else {
                PageScreen(manifest, page, values, session, versionName)
            }
        }
    }
}

// ── 首页 ─────────────────────────────────────────────────────────

private val SEGMENT_TITLES = listOf(
    "shared" to "与电脑版相同",
    "android" to "手机键盘",
    "tail" to "",
)

@Composable
private fun HomeScreen(
    manifest: SettingsManifestData,
    values: SettingsValues,
    session: ImeSession?,
    versionName: String,
    actions: SettingsActions,
    onOpen: (String) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column {
            NavRow("启用输入法", "在系统设置中启用「清风输入法」", actions.enableIme)
            NavRow("切换输入法", "弹出系统的输入法选择框，选「清风输入法」即设为当前输入法", actions.switchIme)
            NavRow("输入测试台", "普通 / 搜索 / 数字 / 密码 / 句子五种场景的输入框，用于试打", actions.openTestPad)
        }
    }
    for ((segment, title) in SEGMENT_TITLES) {
        val pages = manifest.pages.filter { it.segment == segment }
        if (pages.isEmpty()) continue
        if (title.isNotEmpty()) SectionTitle(title)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column {
                for (p in pages) {
                    NavRow(p.title, pageSummary(manifest, p, values, session, versionName)) { onOpen(p.id) }
                }
            }
        }
    }
}

/**
 * 首页每一行的摘要：几个页面给出当前状态（方案名、今日字数、版本），
 * 其余列出前几张卡片的名字，让人知道点进去有什么。
 */
private fun pageSummary(
    manifest: SettingsManifestData,
    page: SettingPage,
    values: SettingsValues,
    session: ImeSession?,
    versionName: String,
): String {
    @Suppress("UNUSED_EXPRESSION") values.version // 值变了摘要跟着变
    return when (page.id) {
        "schema" -> session?.schemas()?.firstOrNull { it.active }?.name?.let { "当前：$it" }
            ?: sectionList(manifest, page.id)
        "about" -> "版本 $versionName"
        else -> sectionList(manifest, page.id)
    }
}

private fun sectionList(manifest: SettingsManifestData, pageId: String): String =
    manifest.sectionsOf(pageId).map { it.first }.filter { it.isNotEmpty() }.take(4).joinToString(" · ")

// ── 二级页面 ─────────────────────────────────────────────────────

@Composable
private fun PageScreen(
    manifest: SettingsManifestData,
    page: SettingPage,
    values: SettingsValues,
    session: ImeSession?,
    versionName: String,
) {
    when (page.id) {
        "schema" -> SchemaSection(session)
        "stats" -> StatsSection()
        "about" -> AboutSection(versionName)
    }
    val sections = manifest.sectionsOf(page.id)
        .map { (name, items) -> name to items.filter(values::available) }
        .filter { it.second.isNotEmpty() }
    if (sections.isEmpty() && !values.engineReady && manifest.itemsOf(page.id).isNotEmpty()) {
        Text(text = "正在启动输入法核心…", modifier = Modifier.padding(start = 4.dp))
    }
    for ((name, items) in sections) {
        SettingsCard(name, items, values)
    }
}

/** 一张卡片：常用项直接列出，`advanced` 项收进底部的「进阶」折叠区 */
@Composable
private fun SettingsCard(title: String, items: List<SettingItem>, values: SettingsValues) {
    var expanded by rememberSaveable(title) { mutableStateOf(false) }
    val (advanced, normal) = items.partition { it.advanced }
    if (title.isNotEmpty()) SectionTitle(title)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column {
            for (item in normal) ItemRow(item, values)
            if (advanced.isNotEmpty()) {
                NavRow(if (expanded) "进阶 ▴" else "进阶 ▾", null) { expanded = !expanded }
                if (expanded) for (item in advanced) ItemRow(item, values)
            }
        }
    }
}

@Composable
private fun ItemRow(item: SettingItem, values: SettingsValues) {
    val scope = rememberCoroutineScope()
    @Suppress("UNUSED_EXPRESSION") values.version // 写入后重组
    fun write(v: Any) {
        scope.launch { withContext(Dispatchers.IO) { values.set(item, v) } }
    }
    when (item.type) {
        "toggle" -> ToggleRow(item, values.bool(item)) { write(it) }
        "int" -> IntRow(item, values.int(item)) { write(it) }
        "select" -> SelectRows(item, values.choice(item)) { write(it) }
    }
}

// ── 方案页的专属区块：方案列表与双拼布局 ─────────────────────────

/**
 * 方案与双拼布局：**数据全部来自核心**，本页不留一份自己的清单。
 *
 * 装了什么方案由文件系统说了算（核心扫 `data/schemas` 下的 `.schema.toml`），双拼有哪些布局
 * 同理（扫 `schemas/shuangpin` 下的 `.toml`）。K 侧写死清单的后果是用户往目录里放的东西看不见，
 * 以及删掉某个方案后设置页还列着一个选不动的死项。
 */
@Composable
private fun SchemaSection(session: ImeSession?) {
    val core = session ?: return
    var schemas by remember { mutableStateOf<List<SchemaEntry>>(core.schemas()) }
    var layouts by remember { mutableStateOf<List<LayoutEntry>>(core.shuangpinLayouts()) }
    if (schemas.isEmpty()) return

    SectionTitle("输入方案")
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

    // 双拼布局只在**当前用的是双拼**时列出：十来个布局对其他方案的用户只是一长串噪声
    if (layouts.isNotEmpty() && schemas.any { it.id == SHUANGPIN_SCHEMA_ID && it.active }) {
        SectionTitle("双拼布局")
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

// ── 关于 ─────────────────────────────────────────────────────────

@Composable
private fun AboutSection(versionName: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column {
            NavRow("清风输入法", "版本 $versionName", null)
        }
    }
}

// ── 通用行 ───────────────────────────────────────────────────────

@Composable
internal fun SectionTitle(text: String) {
    Text(text = text, modifier = Modifier.padding(start = 4.dp, top = 4.dp))
}

/** 标题 + 一行摘要的行；[onClick] 为 null 时不可点 */
@Composable
internal fun NavRow(title: String, summary: String?, onClick: (() -> Unit)?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(16.dp),
    ) {
        Text(text = title)
        if (!summary.isNullOrEmpty()) {
            Text(
                text = summary,
                modifier = Modifier.padding(top = 4.dp),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

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

/**
 * 单选项：标题一行 + 每个选项一行打勾（与方案区同一形态）。
 * 不做下拉菜单：选项就三五个，全部摊开比藏进菜单少一次点击，也和 miuix 的列表风格一致。
 */
@Composable
private fun SelectRows(item: SettingItem, value: String, onValueChange: (String) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, end = 16.dp)) {
        Text(text = item.title)
        item.summary?.let {
            Text(
                text = it,
                modifier = Modifier.padding(top = 2.dp),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
    for ((optValue, label) in item.options) {
        ChoiceRow(title = label, selected = optValue == value, onClick = { onValueChange(optValue) })
    }
}

@Composable
private fun IntRow(item: SettingItem, value: Int, onValueChange: (Int) -> Unit) {
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
                Text(
                    text = it,
                    modifier = Modifier.padding(top = 2.dp),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
        Text(
            text = "－",
            modifier = Modifier
                .clickable { onValueChange((value - item.step).coerceAtLeast(min)) }
                .padding(horizontal = 12.dp),
        )
        Text(text = if (item.unit != null) "$value ${item.unit}" else value.toString())
        Text(
            text = "＋",
            modifier = Modifier
                .clickable { onValueChange((value + item.step).coerceAtMost(max)) }
                .padding(horizontal = 12.dp),
        )
    }
}

@Composable
private fun ToggleRow(item: SettingItem, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
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
                Text(
                    text = it,
                    modifier = Modifier.padding(top = 2.dp),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
