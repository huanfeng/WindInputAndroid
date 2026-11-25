package com.windinput.ime.keyboard

/**
 * 哪些方案提供九宫格键盘布局：全拼 / 双拼方案设置里「启用九宫格键盘布局」的开关。
 *
 * 存键盘配置 `ui.android.t9.schemas`，值是逗号分隔的方案 id（配置文件只存标量）。
 * 没写过（null）时按方案类型给默认：全拼类开、双拼类关——全拼九宫格是主流用法，双拼九宫格少见，
 * 默认不让键盘列表多一项。第一次拨开关时把默认结果整份写出来，之后只认这份列表。
 * 开着的方案在键盘选择面板 / 长按中英键菜单 / 方案卡片里多出「九宫格」一项（还要核心判定能用）。
 */
object T9Availability {
    const val KEY = "ui.android.t9.schemas"

    private fun parse(raw: String?): List<String>? =
        raw?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.distinct()

    /** 没设过时的默认：全拼类开 */
    private fun byDefault(engineType: String?) = engineType == "pinyin"

    fun enabled(raw: String?, id: String, engineType: String?): Boolean =
        parse(raw)?.contains(id) ?: byDefault(engineType)

    /**
     * 拨一个方案的开关，返回新的配置值。[capable] = 能用九宫格的方案 id → 类型，
     * 没设过时据它把默认结果先落成列表，再改这一项。
     */
    fun toggled(raw: String?, capable: Map<String, String>, id: String, on: Boolean): String {
        val base = parse(raw) ?: capable.filter { byDefault(it.value) }.keys.toList()
        val next = if (on) base + id else base - id
        return next.distinct().joinToString(",")
    }
}
