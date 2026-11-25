package com.windinput.ime.settings

import androidx.compose.runtime.Composable
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference
import uniffi.wind_android.InstalledSchemaInfo
import uniffi.wind_android.SchemaSettingItem
import uniffi.wind_android.SchemaSettingKind
import uniffi.wind_android.SchemaSettingsInfo

/**
 * 方案属性行（方案设置页里经 `schema_settings` 读写的项：双拼布局、辅助码来源、标点、命令短语、
 * 整句输入）与它们的纯逻辑。设计见 docs/schema-settings-plan.md。
 *
 * 「跟随」的表达：选回**默认值**就是恢复（删掉这一项的覆盖），不另设勾选——手机上一行一个控件。
 */
/** 开关项：拨回默认值 = 恢复 */
@Composable
internal fun BoolSettingRow(title: String, summary: String?, item: SchemaSettingItem, onWrite: (SchemaWrite) -> Unit) {
    ToggleLine(title, rowSummary(summary, item, emptyMap()), item.value == "true") { on ->
        onWrite(writeFor(item, on.toString()))
    }
}

/** 下拉项：值域来自核心，文字取 [labels]（没有的显示原值）；选回默认值 = 恢复 */
@Composable
internal fun ChoiceSettingRow(
    title: String,
    summary: String?,
    item: SchemaSettingItem,
    labels: Map<String, String>,
    onWrite: (SchemaWrite) -> Unit,
) {
    // 整数项（码表「自动上屏最短码长」「出简让全」）核心不给值域：用清单选项
    val (values, names) = choiceEntries(item, labels)
    val selected = values.indexOf(item.value)
    WindowDropdownPreference(
        items = names,
        selectedIndex = selected,
        title = title,
        summary = rowSummary(summary, item, labels),
        // 点回当前项不写：否则 PC 钉住、恰等于默认值的覆盖会被当成「选回默认」删掉
        onSelectedIndexChange = { i ->
            if (i != selected) values.getOrNull(i)?.let { onWrite(writeFor(item, it)) }
        },
    )
}

/**
 * 下拉的值与文字。当前值不在值域里（PC / 手改写了清单外的整数、双拼布局已删）时追加一项
 * 「原值（自定义）」，不然下拉会静默显示成第一项，与实际值对不上。
 */
internal fun choiceEntries(item: SchemaSettingItem, labels: Map<String, String>): Pair<List<String>, List<String>> {
    val base = item.options.ifEmpty { labels.keys.toList() }
    val values = if (item.value in base) base else base + item.value
    val names = values.map { v -> labels[v] ?: if (v in base) v else "$v（自定义）" }
    return values to names
}

// ── 纯逻辑（JVM 单测覆盖）──────────────────────────────────────

internal const val KEY_SHUANGPIN_LAYOUT = "engine.pinyin.shuangpin.layout"
internal const val KEY_AUX_ENABLED = "engine.aux_code.enabled"
internal const val KEY_AUX_FILES = "engine.aux_code.files"

/** 辅助码来源的显示名：键是核心给的值（码表文件相对路径，或 `schema:<方案 id>`）；没列的显示原值 */
internal val AUX_SOURCE_LABELS = mapOf(
    "aux_code/flypy_full.txt" to "小鹤形码",
    "aux_code/ZRM-wanxiang.txt" to "自然码形码",
    "aux_code/shouyou.txt" to "首右辅助码 2.0",
    "aux_code/shouyou_plus.txt" to "首右 Plus 辅助码",
    "aux_code/stroke.txt" to "笔画码表",
    "schema:stroke" to "笔画（码表方案）",
    "schema:wubi86" to "五笔 86（码表方案）",
)
internal const val KEY_SENTENCE = "engine.codetable.sentence_input"

/**
 * 自定义行为一节列的项：标题与说明。
 *
 * 不列 `candidate.layout`（候选横排 / 竖排）：手机候选条没有这个区分，核心的布局意图在移动端
 * 没有消费者，开了没反应（核心照样给这一项，数据与 PC 对等）。
 */
internal val BEHAVIOR_ROWS = linkedMapOf(
    "punct.mode" to ("标点" to "切到本方案时用中文还是英文标点；正在使用的方案要切走再切回才生效"),
    "phrases.enabled" to ("加载命令短语" to "关掉后本方案不出短语候选（如 date、tel 这类码）"),
)

internal val PUNCT_LABELS = mapOf(
    "follow" to "跟随全局",
    "chinese" to "中文标点",
    "english" to "英文标点",
)

internal sealed interface SchemaWrite {
    data class Set(val key: String, val value: String) : SchemaWrite
    data class Reset(val key: String) : SchemaWrite
    data object ResetAll : SchemaWrite
}

/** 选回默认值就是恢复（删覆盖、回到跟随），否则写新值 */
internal fun writeFor(item: SchemaSettingItem, value: String): SchemaWrite =
    if (value == item.defaultValue) SchemaWrite.Reset(item.key) else SchemaWrite.Set(item.key, value)

/** 行摘要：说明 + 改过时注明默认值 */
internal fun rowSummary(description: String?, item: SchemaSettingItem, labels: Map<String, String>): String? {
    val changed = if (item.overridden) "已自定义 · 默认：${valueLabel(item, item.defaultValue, labels)}" else null
    return listOfNotNull(description, changed).joinToString("\n").ifEmpty { null }
}

internal fun valueLabel(item: SchemaSettingItem, value: String, labels: Map<String, String>): String =
    when (item.kind) {
        SchemaSettingKind.BOOL -> if (value == "true") "开" else "关"
        else -> labels[value] ?: value
    }

internal fun infoSummary(s: SchemaSettingsInfo, installed: InstalledSchemaInfo?): String {
    val facts = listOfNotNull(
        engineTypeLabel(s.engineType),
        installed?.let { sourceLabel(it.user, it.builtin) },
        s.version.takeIf { it.isNotEmpty() }?.let { "版本 $it" },
        s.author.takeIf { it.isNotEmpty() }?.let { "作者 $it" },
    ).joinToString(" · ")
    return listOf(facts, s.description).filter { it.isNotEmpty() }.joinToString("\n")
}

internal fun engineTypeLabel(t: String): String = when (t) {
    "pinyin" -> "全拼"
    "shuangpin" -> "双拼"
    "codetable" -> "码表"
    "mixed" -> "混输"
    "english" -> "英文"
    else -> t
}

internal fun sourceLabel(user: Boolean, builtin: Boolean): String = when {
    user && builtin -> "用户导入（覆盖了自带版本）"
    user -> "用户导入"
    else -> "应用自带"
}
