package com.windinput.ime.keyboard.keymap

import java.time.LocalDateTime
import java.time.format.TextStyle
import java.util.Locale

/**
 * 键位输出里 `template` 的展开：上屏时现算，纯函数（时间由调用方注入，JVM 单测固定时间）。
 *
 * 占位符（花括号里，其余文字原样保留；认不出的占位符原样输出，不吞掉）：
 * `{date}` 2026-11-24 · `{date_cn}` 2026年11月24日 · `{time}` 15:30 · `{time_s}` 15:30:08 ·
 * `{datetime}` 2026-11-24 15:30 · `{year}` `{month}` `{day}` `{hour}` `{minute}`（不补零，month/day 补零见 `_2`）·
 * `{weekday}` 星期二 · `{timestamp}` 秒级时间戳（本地时区）。
 */
object KeyTemplate {

    private val PLACEHOLDER = Regex("\\{([a-z_0-9]+)\\}")

    /** 角标上显示的名字：整条模板就是一个已知占位符时用中文名，否则取模板原文（截短） */
    fun label(template: String): String {
        val only = PLACEHOLDER.matchEntire(template)?.groupValues?.get(1)
        val name = when (only) {
            "date", "date_cn" -> "日期"
            "time", "time_s" -> "时间"
            "datetime" -> "日期时间"
            "weekday" -> "星期"
            "year" -> "年"
            "month" -> "月"
            "day" -> "日"
            "timestamp" -> "时间戳"
            else -> null
        }
        return name ?: if (template.length > 4) template.take(4) + "…" else template
    }

    fun render(template: String, now: LocalDateTime): String =
        PLACEHOLDER.replace(template) { m -> value(m.groupValues[1], now) ?: m.value }

    private fun two(n: Int) = n.toString().padStart(2, '0')

    private fun value(name: String, t: LocalDateTime): String? = when (name) {
        "date" -> "${t.year}-${two(t.monthValue)}-${two(t.dayOfMonth)}"
        "date_cn" -> "${t.year}年${t.monthValue}月${t.dayOfMonth}日"
        "time" -> "${two(t.hour)}:${two(t.minute)}"
        "time_s" -> "${two(t.hour)}:${two(t.minute)}:${two(t.second)}"
        "datetime" -> "${t.year}-${two(t.monthValue)}-${two(t.dayOfMonth)} ${two(t.hour)}:${two(t.minute)}"
        "year" -> t.year.toString()
        "month" -> t.monthValue.toString()
        "month_2" -> two(t.monthValue)
        "day" -> t.dayOfMonth.toString()
        "day_2" -> two(t.dayOfMonth)
        "hour" -> t.hour.toString()
        "minute" -> t.minute.toString()
        "weekday" -> t.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.CHINA)
            .let { if (it.startsWith("星期")) it else "星期$it" }
        "timestamp" -> t.atZone(java.time.ZoneId.systemDefault()).toEpochSecond().toString()
        else -> null
    }
}
