package com.windinput.ime.panels

import com.windinput.ime.core.SchemaEntry

/**
 * 「键盘选择」面板的格子列表（纯逻辑，不碰 View，JVM 单测覆盖）。
 *
 * 照微信「键盘选择」：每个启用的方案一格（方框字徽章 + 「全键盘拼音」「五笔键盘」这类标签），
 * 英文方案（清风英文，带补全候选）也是普通方案格；再加「英文直输」（引擎的英文模式：字母直出、
 * 无候选）与最后的「键盘管理」。
 *
 * 九宫格是键盘布局，不是方案：能用九宫格的方案（全拼、纯字母双拼）出两格，全键盘一格、
 * 九宫格一格（「九宫格拼音」「九宫格双拼」），选哪格就是「这个方案 + 这种布局」。
 */
object KeyboardChoices {

    /** 英文方案 id（与 assets/data/schemas/english.schema.toml 一致） */
    const val ENGLISH_SCHEMA_ID = "english"

    sealed class Action {
        /** 切到第 [index] 个方案（[SchemaEntry.index]），[t9] = 用九宫格布局 */
        data class Schema(val index: Int, val t9: Boolean = false) : Action()

        /** 英文直输：走引擎的英文命令（系统英文模式，字母直出、无候选），不按方案下标切 */
        data object English : Action()

        /** 打开设置里的「启用的方案」页 */
        data object Manage : Action()
    }

    /**
     * @param badge 方框字徽章（null = 用矢量图标，只有「键盘管理」）
     * @param selected 当前所在的方案（高亮）
     */
    data class Choice(val badge: String?, val label: String, val selected: Boolean, val action: Action)

    /** 英文直输格：徽章用「A」，与英文方案的「英」区分 */
    const val DIRECT_ENGLISH_BADGE = "A"
    const val DIRECT_ENGLISH_LABEL = "英文直输"
    const val MANAGE_LABEL = "键盘管理"

    /** 九宫格格子的方框字 */
    const val T9_BADGE = "九"

    /** 方案的九宫格格子标签：拼音「九宫格拼音」、双拼「九宫格双拼」，其余「方案名九宫格」 */
    fun t9LabelOf(id: String, name: String): String = when (typeOf(id)) {
        Type.PINYIN -> "九宫格拼音"
        Type.SHUANGPIN -> "九宫格双拼"
        else -> displayName(id, name) + "九宫格"
    }

    /** 竖排菜单（长按中英键、方案卡片）里的一项：方案名，九宫格的加「· 九宫格」 */
    fun menuLabelOf(s: SchemaEntry, t9: Boolean): String =
        displayName(s.id, s.name) + if (t9) " · 九宫格" else ""

    /**
     * 方案按布局展开：能用九宫格的方案（[t9Capable]）紧跟着多出一项九宫格，其余一项。
     * 顺序同 [schemas]。键盘选择面板、长按中英键菜单、方案卡片共用，几处才对得上。
     */
    fun expand(schemas: List<SchemaEntry>, t9Capable: Set<String>): List<Pair<SchemaEntry, Boolean>> =
        schemas.flatMap { s -> if (s.id in t9Capable) listOf(s to false, s to true) else listOf(s to false) }

    /** 方案 → 格子标签：认得出类型的按类型起名，认不出的用方案名 */
    fun labelOf(id: String, name: String): String = when (typeOf(id)) {
        Type.PINYIN -> "全键盘拼音"
        Type.SHUANGPIN -> "双拼键盘"
        // 英文方案用方案名（清风英文），与「英文直输」那格分得开
        Type.ENGLISH -> displayName(id, name)
        // 五笔类各方案名字不同（五笔、五笔拼音、五笔98…），保留名字再补「键盘」
        Type.WUBI -> displayName(id, name) + "键盘"
        Type.OTHER -> displayName(id, name)
    }

    /** 方案 → 方框字：拼音「拼」、双拼「双」、英文「英」，其余取方案名首字 */
    fun badgeOf(id: String, name: String): String = when (typeOf(id)) {
        Type.PINYIN -> "拼"
        Type.SHUANGPIN -> "双"
        Type.ENGLISH -> "英"
        Type.WUBI, Type.OTHER -> displayName(id, name).take(1)
    }

    /**
     * 格子列表：中文方案按方案列表的顺序 → 英文方案 → 英文直输 → 键盘管理。
     *
     * 选中态与引擎一致：英文模式（[chineseMode] 为 false，含密码框强制英文）高亮「英文直输」，
     * 否则高亮当前方案（英文方案也算方案：`SelectSchema` 会把 chineseMode 设回 true）。
     */
    fun build(
        schemas: List<SchemaEntry>,
        chineseMode: Boolean,
        t9Capable: Set<String> = emptySet(),
        t9Schemas: Set<String> = emptySet(),
    ): List<Choice> {
        val out = mutableListOf<Choice>()
        // 英文方案排在中文方案之后：方案按文件名排序，english 会夹在中间
        val ordered = schemas.filter { it.id != ENGLISH_SCHEMA_ID } + schemas.filter { it.id == ENGLISH_SCHEMA_ID }
        for ((s, t9) in expand(ordered, t9Capable)) {
            // 选中：方案对，且布局与这个方案记着的布局一致
            val selected = chineseMode && s.active && t9 == (s.id in t9Schemas && s.id in t9Capable)
            out += if (t9) {
                Choice(T9_BADGE, t9LabelOf(s.id, s.name), selected, Action.Schema(s.index, t9 = true))
            } else {
                Choice(badgeOf(s.id, s.name), labelOf(s.id, s.name), selected, Action.Schema(s.index))
            }
        }
        out += Choice(DIRECT_ENGLISH_BADGE, DIRECT_ENGLISH_LABEL, !chineseMode, Action.English)
        out += Choice(null, MANAGE_LABEL, false, Action.Manage)
        return out
    }

    private enum class Type { PINYIN, SHUANGPIN, ENGLISH, WUBI, OTHER }

    /** 按 id 认类型：自带方案的 id 固定（pinyin / shuangpin / english / wubi86…），导入的五笔类多以 wubi 开头 */
    private fun typeOf(id: String): Type = when {
        id == "pinyin" -> Type.PINYIN
        id == "shuangpin" -> Type.SHUANGPIN
        id == ENGLISH_SCHEMA_ID -> Type.ENGLISH
        id.startsWith("wubi") -> Type.WUBI
        else -> Type.OTHER
    }

    private fun displayName(id: String, name: String) = name.ifEmpty { id }
}
