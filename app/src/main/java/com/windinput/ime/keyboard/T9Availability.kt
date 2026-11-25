package com.windinput.ime.keyboard

/**
 * 哪些方案提供九宫格键盘布局：全拼 / 双拼方案设置里「启用九宫格键盘布局」的开关。
 *
 * 键盘配置里记两份显式选择（逗号分隔的方案 id，配置文件只存标量）：[KEY_ON] 用户开过的、
 * [KEY_OFF] 用户关过的。两份里都没有的方案按类型给默认：全拼类开、双拼类关——全拼九宫格是主流用法，
 * 双拼九宫格少见，默认不让键盘列表多一项。只记显式选择，后来导入的全拼方案照样默认开。
 * 开着的方案在键盘选择面板 / 长按中英键菜单里多出「九宫格」一项（还要核心判定能用）。
 */
object T9Availability {
    const val KEY_ON = "ui.android.t9.enabled_schemas"
    const val KEY_OFF = "ui.android.t9.disabled_schemas"

    private fun parse(raw: String?): List<String> =
        raw?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.distinct().orEmpty()

    /** 没选过时的默认：全拼类开 */
    private fun byDefault(engineType: String?) = engineType == "pinyin"

    fun enabled(on: String?, off: String?, id: String, engineType: String?): Boolean = when (id) {
        in parse(off) -> false
        in parse(on) -> true
        else -> byDefault(engineType)
    }

    /**
     * 确定关着：用户关过，或没选过且**已知**类型默认关。类型读不到（核心忙）时不算关——
     * 键盘侧据此撤掉记着的九宫格布局，不能因为瞬时读不到就把刚选的九宫格撤了。
     */
    fun disabled(on: String?, off: String?, id: String, engineType: String?): Boolean = when (id) {
        in parse(off) -> true
        in parse(on) -> false
        else -> engineType != null && !byDefault(engineType)
    }

    /** 拨一个方案的开关，返回新的 (on, off) 配置值 */
    fun toggled(on: String?, off: String?, id: String, value: Boolean): Pair<String, String> {
        val onList = parse(on) - id
        val offList = parse(off) - id
        return if (value) {
            (onList + id).joinToString(",") to offList.joinToString(",")
        } else {
            onList.joinToString(",") to (offList + id).joinToString(",")
        }
    }
}
