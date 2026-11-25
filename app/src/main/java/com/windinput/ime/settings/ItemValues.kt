package com.windinput.ime.settings

import uniffi.wind_android.ConfigValue

/**
 * 一行设置的值从哪读、往哪写。全局设置页用 [SettingsValues]（核心用户层 + 键盘配置），
 * 方案设置页用 [SchemeFormValues]（某个方案的覆盖层）。行控件（[ItemRow] 等）只认这个接口。
 *
 * 所有方法都可能做 IO，读写放后台线程；[version] 每次写入后自增，供 Compose 重组。
 */
interface ItemValues {
    val version: Int
    fun available(item: SettingItem): Boolean
    fun visible(item: SettingItem, all: List<SettingItem>): Boolean
    fun bool(item: SettingItem): Boolean
    fun int(item: SettingItem): Int
    fun text(item: SettingItem): String
    fun choice(item: SettingItem): String

    /** 行副标题：清单 summary；按方案设置的值改过时追加「已自定义 · 默认：x」 */
    fun summary(item: SettingItem): String?

    /** 写入。返回错误说明（成功为 null） */
    fun set(item: SettingItem, value: Any): String?
}

/** 列表型配置在文本框 / 下拉值里的分隔：空白或逗号（配对字符、键名里都不会出现这两种） */
private val LIST_SEPARATOR = Regex("[,\\s]+")

/** 按核心注册表的类型（bool / int / float / str_list / 其它按字符串）把界面上的值转成写入值 */
internal fun configValueOf(type: String?, value: Any): ConfigValue = when (type) {
    "bool" -> ConfigValue.Bool(value == true || value == "true")
    "int" -> ConfigValue.Int(value.toString().toLong())
    "float" -> ConfigValue.Float(value.toString().toDouble())
    // 触发键类（trigger_keys）：下拉里一项 = 逗号分隔的一组键名，空串 = 一个都不绑
    "str_list" -> ConfigValue.StrList(value.toString().split(LIST_SEPARATOR).filter { it.isNotBlank() })
    else -> ConfigValue.Str(value.toString())
}

/** 摊平后的配置值（Boolean / Long / Double / String / List）按设置项形态读出来，两种值来源共用 */
internal object RawValue {
    fun bool(v: Any?): Boolean = when (v) {
        is Boolean -> v
        is String -> v == "true"
        else -> false
    }

    fun int(v: Any?): Int = when (v) {
        is Number -> v.toInt()
        is String -> v.toIntOrNull() ?: 0
        else -> 0
    }

    fun text(v: Any?): String = when (v) {
        is List<*> -> v.joinToString(" ")
        else -> v?.toString() ?: ""
    }

    fun choice(v: Any?): String = when (v) {
        is List<*> -> v.joinToString(",") // 键名列表（trigger_keys）按逗号拼回下拉项的值
        else -> v?.toString() ?: ""
    }
}
