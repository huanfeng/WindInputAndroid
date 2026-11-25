package com.windinput.ime.keyboard

/**
 * 九宫格拼音左栏（不打字时）的标点：存在 `ui.android.t9.side_symbols`，格式与增删挪规则同
 * [NumberSideSymbols]，只是出厂列表不同。键不存在 = 出厂列表；空串 = 用户清空了（左栏只剩「自定义」格）。
 */
object T9SideSymbols {

    const val CONFIG_KEY = "ui.android.t9.side_symbols"

    /** 出厂列表：中文常用标点 */
    val DEFAULTS = listOf("，", "。", "？", "！", "、", "：", "；", "…", "～", "“", "”", "@")

    fun parse(raw: String?): List<String> = NumberSideSymbols.parse(raw, DEFAULTS)
}
