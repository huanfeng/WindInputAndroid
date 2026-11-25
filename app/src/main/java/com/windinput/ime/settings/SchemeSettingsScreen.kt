package com.windinput.ime.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import uniffi.wind_android.ConfigException
import uniffi.wind_android.InstalledSchemaInfo
import uniffi.wind_android.SchemaSettingItem
import uniffi.wind_android.SchemaSettingKind
import uniffi.wind_android.SchemaSettingsInfo
import com.windinput.ime.keyboard.T9Availability

/**
 * 方案设置页：方案页每行的齿轮进来。第一屏是方案信息与分组入口，每组一个子页（[SchemeGroupScreen]），
 * 组内项直接列出、不折叠。设计见 docs/superpowers/specs/2026-11-25-scheme-settings-mobile-design.md。
 *
 * 两条数据口：
 * - 表单项（清单 scheme_form 页，键 `schema.<类型>.*`）经 [SchemeFormValues]，与电脑版方案对话框同口径；
 * - 方案属性（双拼布局、辅助码来源、标点、命令短语、整句输入）经 `schema_settings`（[SchemaWrite]）。
 */

/** 方案设置页里的一组 */
data class SchemeGroup(val name: String, val kind: Kind, val items: List<SettingItem> = emptyList()) {
    enum class Kind {
        /** 清单表单项 */
        FORM,

        /** 九宫格键盘布局（全拼 / 纯字母双拼） */
        T9,

        /** 辅助码：表单项 + 来源下拉 */
        AUX,

        /** 扩展词库开关 */
        DICTS,

        /** 自定义行为：标点、命令短语 */
        BEHAVIOR,

        /** 码表整句输入（实验） */
        SENTENCE,

        /** 混输的成员方案入口 */
        MEMBERS,
    }
}

internal const val GROUP_T9 = "九宫格键盘"
private const val GROUP_AUX = "辅助码"

/** 双拼布局画在这一组的顶上 */
private const val GROUP_BASIC = "基本"
private const val GROUP_DICTS = "扩展词库"
private const val GROUP_BEHAVIOR = "自定义行为"
private const val GROUP_SENTENCE = "整句输入"
private const val GROUP_MEMBERS = "成员方案"

/**
 * 某方案的分组，按设计 §4.1 的顺序：清单分组（拼音的「九宫格键盘」插在「基本」之后），再是代码画的
 * 辅助码 / 整句 / 扩展词库 / 自定义行为 / 成员方案。辅助码只在核心判定可用（[props] 里有开关）时出现。
 */
internal fun schemeGroups(
    engineType: String,
    manifest: SettingsManifestData,
    props: List<SchemaSettingItem>,
    t9Capable: Boolean,
    hasDicts: Boolean,
    hasMembers: Boolean,
): List<SchemeGroup> {
    val auxUsable = props.any { it.key == KEY_AUX_ENABLED }
    val out = mutableListOf<SchemeGroup>()
    for ((name, items) in manifest.schemeGroups(engineType)) {
        when (name) {
            GROUP_AUX -> if (auxUsable) out += SchemeGroup(name, SchemeGroup.Kind.AUX, items)
            // 清单里九宫格组只有随开关显示的项（全拼的读法栏）；组本身由代码定位在「基本」之后
            GROUP_T9 -> Unit
            else -> out += SchemeGroup(name, SchemeGroup.Kind.FORM, items)
        }
        if (out.size == 1 && t9Capable && out.none { it.kind == SchemeGroup.Kind.T9 }) {
            out += SchemeGroup(GROUP_T9, SchemeGroup.Kind.T9, manifest.schemeGroups(engineType).toMap()[GROUP_T9].orEmpty())
        }
    }
    if (props.any { it.key == KEY_SENTENCE }) out += SchemeGroup(GROUP_SENTENCE, SchemeGroup.Kind.SENTENCE)
    if (hasDicts) out += SchemeGroup(GROUP_DICTS, SchemeGroup.Kind.DICTS)
    if (props.any { it.key in BEHAVIOR_ROWS }) out += SchemeGroup(GROUP_BEHAVIOR, SchemeGroup.Kind.BEHAVIOR)
    if (hasMembers) out += SchemeGroup(GROUP_MEMBERS, SchemeGroup.Kind.MEMBERS)
    return out
}

/** 方案设置页读到的全部东西（后台线程装好） */
private class SchemeData(
    val form: SchemeFormValues,
    val global: SettingsValues,
    val props: SchemaSettingsInfo,
    val installed: InstalledSchemaInfo?,
    val layoutNames: Map<String, String>,
    val memberNames: List<Pair<String, String>>,
    val hasDicts: Boolean,
    val t9Capable: Boolean,
    /** 「启用九宫格键盘布局」此刻开着 */
    var t9Enabled: Boolean,
)

private sealed interface SchemeLoad {
    data class Ok(val data: SchemeData) : SchemeLoad
    data class Failed(val reason: String) : SchemeLoad
}

private fun loadScheme(context: android.content.Context, schemeId: String, global: SettingsValues): SchemeLoad {
    val core = CoreAccess.obtainCore(context) ?: return SchemeLoad.Failed("输入法核心未就绪")
    return try {
        val form = SchemeFormValues(schemeId, CoreSchemeFormIo(core), global::typeOf, global)
        form.load()?.let { return SchemeLoad.Failed(it) }
        val installed = core.installedSchemas()
        val name = { id: String -> installed.firstOrNull { it.id == id }?.name?.ifEmpty { id } ?: id }
        val props = core.schemaSettings(schemeId)
        val keyboard = global.keyboardValues()
        SchemeLoad.Ok(
            SchemeData(
                form = form,
                global = global,
                props = props,
                installed = installed.firstOrNull { it.id == schemeId },
                layoutNames = core.shuangpinLayouts().associate { it.id to it.name.ifEmpty { it.id } },
                memberNames = form.members.map { it to name(it) },
                hasDicts = runCatching { core.schemaDicts(schemeId).isNotEmpty() }.getOrDefault(false),
                t9Capable = runCatching { core.keypadSupported(schemeId) }.getOrDefault(false),
                t9Enabled = T9Availability.enabled(
                    keyboard[T9Availability.KEY_ON] as? String,
                    keyboard[T9Availability.KEY_OFF] as? String,
                    schemeId,
                    props.engineType,
                ),
            ),
        )
    } catch (e: ConfigException) {
        SchemeLoad.Failed((e as? ConfigException.Rejected)?.reason ?: e.toString())
    } catch (e: Exception) {
        SchemeLoad.Failed(e.message ?: e.toString())
    }
}

/**
 * 方案设置：[group] 为 null 时画第一屏，否则画那一组。
 * [onOpenGroup] 进一组；[onOpenScheme] 打开另一个方案（混输的成员）；[onLoaded] 报方案名（标题用）。
 */
@Composable
internal fun SchemeSettingsScreen(
    schemeId: String,
    group: String?,
    manifest: SettingsManifestData,
    global: SettingsValues,
    onOpenGroup: (String) -> Unit,
    onOpenScheme: (id: String, name: String) -> Unit,
    onLoaded: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val writeOrder = remember { Mutex() }
    var data by remember { mutableStateOf<SchemeData?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(reload, global.engineReady) {
        if (!global.engineReady) return@LaunchedEffect
        when (val l = withContext(Dispatchers.IO) { loadScheme(context, schemeId, global) }) {
            is SchemeLoad.Failed -> error = l.reason
            is SchemeLoad.Ok -> {
                data = l.data
                onLoaded(l.data.props.name.ifEmpty { schemeId })
            }
        }
    }
    error?.let {
        Card(modifier = Modifier.fillMaxWidth()) { BasicComponent(title = "读不到方案设置", summary = it) }
        return
    }
    val d = data ?: return

    /** 方案属性的写入：后台串行写盘（核心随即重载），完后重读 */
    fun writeProp(action: SchemaWrite) {
        scope.launch {
            val failure = writeOrder.withLock {
                withContext(Dispatchers.IO) {
                    val core = CoreAccess.obtainCore(context) ?: return@withContext "输入法核心未就绪"
                    try {
                        when (action) {
                            is SchemaWrite.Set -> core.setSchemaSetting(schemeId, action.key, action.value)
                            is SchemaWrite.Reset -> core.resetSchemaSetting(schemeId, action.key)
                            SchemaWrite.ResetAll -> core.resetSchemaSettings(schemeId)
                        }
                        null
                    } catch (e: ConfigException) {
                        (e as? ConfigException.Rejected)?.reason ?: e.toString()
                    } catch (e: Exception) {
                        "操作失败：${e.message ?: e}"
                    }
                }
            }
            if (failure != null) Toast.makeText(context, failure, Toast.LENGTH_SHORT).show()
            reload++
        }
    }

    val type = d.props.engineType
    val groups = schemeGroups(type, manifest, d.props.items, d.t9Capable, d.hasDicts, d.memberNames.isNotEmpty())
    // 组不在（直达链接指向的组，此方案没有，如关了九宫格能力的方案）：显示方案首页，不留空白页
    val g = group?.let { name -> groups.firstOrNull { it.name == name } }
    if (g == null) {
        SchemeFirstScreen(d, type, groups, manifest, onOpenGroup, ::writeProp) { reload++ }
    } else {
        SchemeGroupScreen(d, schemeId, g, onOpenScheme, ::writeProp)
    }
}

@Composable
private fun SchemeFirstScreen(
    d: SchemeData,
    type: String,
    groups: List<SchemeGroup>,
    manifest: SettingsManifestData,
    onOpenGroup: (String) -> Unit,
    writeProp: (SchemaWrite) -> Unit,
    onReloaded: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    @Suppress("UNUSED_EXPRESSION") d.form.version
    Card(modifier = Modifier.fillMaxWidth()) {
        BasicComponent(title = d.props.name.ifEmpty { d.props.id }, summary = infoSummary(d.props, d.installed))
        presetsLine(d.form.presets(), manifest.schemeGroups(type).flatMap { it.second }, d.form)?.let {
            BasicComponent(title = "本方案预设", summary = it)
        }
    }
    SectionTitle("设置")
    Card(modifier = Modifier.fillMaxWidth()) {
        for (g in groups) {
            NavRow(title = g.name, summary = groupSummary(g, d)) { onOpenGroup(g.name) }
        }
    }

    val formItems = manifest.schemeGroups(type).flatMap { it.second }
    val changed = d.form.changedCount(formItems) + d.props.items.count { it.overridden }
    var confirm by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        NavRow(
            title = "全部恢复默认",
            summary = if (changed > 0) "已自定义 $changed 项" else "没有自定义的项",
            onClick = if (changed > 0) ({ confirm = true }) else null,
        )
    }
    if (confirm) {
        Dialog(onDismissRequest = { confirm = false }) {
            Card(Modifier.padding(horizontal = 20.dp).fillMaxWidth()) {
                Column(Modifier.padding(20.dp)) {
                    Text("全部恢复默认", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(12.dp))
                    Text("本方案自定义过的 $changed 项回到方案默认。扩展词库开关不受影响。")
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(text = "取消", onClick = { confirm = false })
                        TextButton(text = "恢复", onClick = {
                            confirm = false
                            scope.launch {
                                val err = withContext(Dispatchers.IO) { d.form.resetAll(formItems) }
                                if (err != null) Toast.makeText(context, "恢复失败：$err", Toast.LENGTH_SHORT).show()
                                if (d.props.items.any { it.overridden }) writeProp(SchemaWrite.ResetAll) else onReloaded()
                            }
                        })
                    }
                }
            }
        }
    }
}

/** 第一屏每组一行的副标题 */
private fun groupSummary(g: SchemeGroup, d: SchemeData): String =
    when (g.kind) {
        SchemeGroup.Kind.FORM, SchemeGroup.Kind.AUX -> {
            val n = d.form.changedCount(g.items)
            val master = groupMaster(g.items)
            when {
                master != null ->
                    (if (d.form.bool(master)) "已开启" else "未开启") + if (n > 0) " · 已自定义 $n 项" else ""
                n > 0 -> "已自定义 $n 项"
                else -> g.items.take(3).joinToString(" · ") { it.title }
            }
        }
        SchemeGroup.Kind.T9 -> if (d.t9Enabled) "已开启：键盘列表里有「${d.props.name.ifEmpty { d.props.id }} · 九宫格」" else "未开启"
        SchemeGroup.Kind.DICTS -> "本方案的扩展词库开关"
        SchemeGroup.Kind.BEHAVIOR -> "标点、命令短语"
        SchemeGroup.Kind.SENTENCE ->
            if (d.props.items.firstOrNull { it.key == KEY_SENTENCE }?.value == "true") "已开启" else "未开启"
        SchemeGroup.Kind.MEMBERS -> d.memberNames.joinToString(" · ") { it.second }
    }

/** 组的总开关：打头的开关项，且组里有别的项随它显隐（调频、自动学习、英文候选……） */
internal fun groupMaster(items: List<SettingItem>): SettingItem? =
    items.firstOrNull()?.takeIf { head -> head.type == "toggle" && items.any { it.enabledWhen == head.key } }

/** 「本方案预设」：方案文件改过的项（标题 当前值，类型默认值） */
internal fun presetsLine(builtin: Map<String, Any>, items: List<SettingItem>, values: ItemValues): String? =
    items.filter { it.key in builtin }
        .map { item ->
            val now = when (item.type) {
                "toggle" -> if (values.bool(item)) "开" else "关"
                "select" -> values.choice(item).let { c -> item.options.firstOrNull { it.first == c }?.second ?: c }
                else -> values.text(item)
            }
            "${item.title} $now（类型默认 ${builtin[item.key]}）"
        }
        .takeIf { it.isNotEmpty() }
        ?.joinToString("\n")

@Composable
private fun SchemeGroupScreen(
    d: SchemeData,
    schemeId: String,
    g: SchemeGroup,
    onOpenScheme: (String, String) -> Unit,
    writeProp: (SchemaWrite) -> Unit,
) {
    @Suppress("UNUSED_EXPRESSION") d.form.version
    when (g.kind) {
        SchemeGroup.Kind.FORM -> {
            val layout = d.props.items.firstOrNull { it.key == KEY_SHUANGPIN_LAYOUT }
            if (layout != null && g.name == GROUP_BASIC) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    ChoiceSettingRow("双拼布局", "决定声母韵母怎么落到键位上", layout, d.layoutNames, writeProp)
                }
            }
            FormCard(g.items, d.form)
        }
        SchemeGroup.Kind.AUX -> {
            val (head, rest) = g.items.partition { it.key == AUX_FORM_ENABLED }
            FormCard(head, d.form)
            if (head.firstOrNull()?.let(d.form::bool) == true) {
                d.props.items.firstOrNull { it.key == KEY_AUX_FILES }?.let { src ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        ChoiceSettingRow("辅助码来源", "用哪张字形码表筛选；双拼多为小鹤 / 自然码 / 首右，全拼多为笔画", src, AUX_SOURCE_LABELS, writeProp)
                    }
                }
                FormCard(rest, d.form, g.items)
            }
        }
        SchemeGroup.Kind.T9 -> T9GroupScreen(d, g)
        SchemeGroup.Kind.DICTS -> SchemaDictsSection("扩展词库", listOf(schemeId))
        SchemeGroup.Kind.BEHAVIOR -> Card(modifier = Modifier.fillMaxWidth()) {
            for (item in d.props.items.filter { it.key in BEHAVIOR_ROWS }) {
                val (title, summary) = BEHAVIOR_ROWS.getValue(item.key)
                when (item.kind) {
                    SchemaSettingKind.BOOL -> BoolSettingRow(title, summary, item, writeProp)
                    else -> ChoiceSettingRow(title, summary, item, PUNCT_LABELS, writeProp)
                }
            }
        }
        SchemeGroup.Kind.SENTENCE -> d.props.items.firstOrNull { it.key == KEY_SENTENCE }?.let { sentence ->
            Card(modifier = Modifier.fillMaxWidth()) {
                BoolSettingRow("整句输入", "码长超过上限时按码表拼整句（实验性）", sentence, writeProp)
            }
        }
        SchemeGroup.Kind.MEMBERS -> Card(modifier = Modifier.fillMaxWidth()) {
            d.memberNames.forEachIndexed { i, (id, name) ->
                NavRow(
                    title = (if (i == 0) "码表部分：" else "拼音部分：") + name,
                    summary = if (i == 0) "上屏、调频、造词等码表行为取自这个方案" else "简拼、调频、自动学习等拼音行为取自这个方案",
                ) { onOpenScheme(id, name) }
            }
        }
    }
}

/** 表单项一张卡：按 enabled_when 显隐（依赖项在 [all] 里找） */
@Composable
private fun FormCard(items: List<SettingItem>, values: SchemeFormValues, all: List<SettingItem> = items) {
    val shown = items.filter { values.available(it) && values.visible(it, all) }
    if (shown.isEmpty()) return
    Card(modifier = Modifier.fillMaxWidth()) {
        for (item in shown) key(item.key) { ItemRow(item, values) }
    }
}

/** 表单里辅助码总开关的键（`engine.aux_code.enabled` 的别名） */
internal const val AUX_FORM_ENABLED = "schema.pinyin.aux_code.enabled"

/**
 * 「九宫格键盘」组：启用开关（键盘配置，按方案记）；开着时列出随开关的清单项（全拼的读法栏）与
 * 九宫格标点编辑（全拼、双拼共用一份）。关掉后键盘列表不再有这个方案的九宫格项，
 * 正在用九宫格的话下次弹出键盘退回全键盘。
 */
@Composable
private fun T9GroupScreen(d: SchemeData, g: SchemeGroup) {
    val scope = rememberCoroutineScope()
    var on by remember { mutableStateOf(d.t9Enabled) }
    val name = d.props.name.ifEmpty { d.props.id }
    Card(modifier = Modifier.fillMaxWidth()) {
        ToggleLine(
            "启用九宫格键盘布局",
            "开启后键盘选择、长按中英键菜单里多出「$name · 九宫格」",
            on,
        ) { v ->
            on = v
            d.t9Enabled = v
            scope.launch {
                // 读改写整段在配置锁里做：连点「开 → 关」不会两次都基于同一份旧值
                withContext(Dispatchers.IO) {
                    d.global.editKeyboard { m ->
                        val (on, off) = T9Availability.toggled(
                            m[T9Availability.KEY_ON] as? String,
                            m[T9Availability.KEY_OFF] as? String,
                            d.props.id,
                            v,
                        )
                        m[T9Availability.KEY_ON] = on
                        m[T9Availability.KEY_OFF] = off
                    }
                }
            }
        }
    }
    if (!on) return
    FormCard(g.items, d.form)
    SectionTitle("九宫格标点（所有方案共用）")
    T9SymbolsSection()
}
