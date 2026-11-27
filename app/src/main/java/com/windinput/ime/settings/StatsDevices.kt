package com.windinput.ime.settings

/** 统计页设备下拉的一项：[value] 是核心认的 `all` / `self` / 设备号，[label] 是显示名 */
internal data class StatsDeviceOption(val value: String, val label: String)

internal const val STATS_DEVICE_ALL = "all"
internal const val STATS_DEVICE_SELF = "self"

/**
 * 设备下拉：全部设备、本机，其余他机按显示名排序。没有他机统计时为空（不显示下拉）。
 * 规则同电脑版统计页（`wind-setting/src/pages/stats/data.rs` 的 `device_options`）。
 *
 * [roster] 是同步设备名册：没取到（null 或空）时显示设备号；取到但没有该设备号的标为已移除。
 */
internal fun statsDeviceOptions(ids: List<String>, roster: List<SyncModel.Device>?): List<StatsDeviceOption> {
    if (ids.isEmpty()) return emptyList()
    val peers = ids.map { id -> StatsDeviceOption(id, statsDeviceLabel(id, roster)) }
        .sortedWith(compareBy<StatsDeviceOption> { it.label }.thenBy { it.value })
    return listOf(
        StatsDeviceOption(STATS_DEVICE_ALL, "全部设备"),
        StatsDeviceOption(STATS_DEVICE_SELF, "本机"),
    ) + peers
}

internal fun statsDeviceLabel(id: String, roster: List<SyncModel.Device>?): String {
    // 空名册等同没取到：否则每台设备都会被标成已移除
    if (roster.isNullOrEmpty()) return id
    val d = roster.firstOrNull { it.id == id } ?: return "已移除的设备（$id）"
    return if (d.readable && d.name.isNotBlank()) d.name else id
}

/** 选中的设备不在下拉里（被清除了）就回到「全部设备」 */
internal fun statsDeviceOrAll(current: String, options: List<StatsDeviceOption>): String =
    if (options.any { it.value == current }) current else STATS_DEVICE_ALL
