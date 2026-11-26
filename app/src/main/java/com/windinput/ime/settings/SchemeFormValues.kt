package com.windinput.ime.settings

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import uniffi.wind_android.ConfigException
import uniffi.wind_android.SchemeFormInfo
import uniffi.wind_android.SchemeFormSet
import uniffi.wind_android.WindCore

/** 方案表单的读写口：生产实现直连核心，单测换假的 */
interface SchemeFormIo {
    fun load(id: String): SchemeFormInfo

    /** 写入后核心随即重载配置；失败抛 [ConfigException] */
    fun apply(id: String, sets: List<SchemeFormSet>, resets: List<String>)
}

class CoreSchemeFormIo(private val core: WindCore) : SchemeFormIo {
    override fun load(id: String) = core.schemeForm(id)
    override fun apply(id: String, sets: List<SchemeFormSet>, resets: List<String>) = core.applySchemeForm(id, sets, resets)
}

/**
 * 一个方案的设置值（方案设置页）。值来自核心 `scheme_form`：生效值 / 方案默认 / 方案预设三张表，
 * 键为 `schema.<类型>.<项>`，与电脑版方案对话框同口径。
 *
 * - 选回方案默认值 = 恢复（发 reset，覆盖层删键），否则写值。
 * - 键盘自己的项（`source = android`，如英文的句首自动大写）不按方案存，委托给 [global]。
 * - [typeOf] 是核心注册表的键类型，决定写入值的形态（与全局设置页同一套转换）。
 */
class SchemeFormValues(
    val schemeId: String,
    private val io: SchemeFormIo,
    private val typeOf: (String) -> String?,
    private val global: ItemValues?,
) : ItemValues {

    override var version by mutableIntStateOf(0)
        private set

    @Volatile private var effective: Map<String, Any> = emptyMap()
    @Volatile private var defaults: Map<String, Any> = emptyMap()
    @Volatile private var builtin: Map<String, Any> = emptyMap()

    /** 混输方案的成员 `[主成员, 次成员]` */
    @Volatile var members: List<String> = emptyList()
        private set

    /** 读核心（后台线程）。失败返回原因 */
    fun load(): String? = try {
        val f = io.load(schemeId)
        effective = ConfigSnapshot.flatten(f.effective)
        defaults = ConfigSnapshot.flatten(f.defaultValues)
        builtin = ConfigSnapshot.flatten(f.builtin)
        members = f.members
        version++
        null
    } catch (e: ConfigException) {
        (e as? ConfigException.Rejected)?.reason ?: e.toString()
    } catch (e: Exception) {
        Log.w(TAG, "读方案 $schemeId 的设置失败", e)
        e.message ?: e.toString()
    }

    private fun android(item: SettingItem) = item.source == SettingSource.ANDROID

    override fun available(item: SettingItem): Boolean =
        if (android(item)) global?.available(item) ?: false else item.key in effective

    override fun visible(item: SettingItem, all: List<SettingItem>): Boolean {
        val dep = item.enabledWhen ?: return true
        val depItem = all.firstOrNull { it.key == dep } ?: return true
        return bool(depItem) && visible(depItem, all)
    }

    override fun bool(item: SettingItem) = if (android(item)) global?.bool(item) ?: false else RawValue.bool(effective[item.key])
    override fun int(item: SettingItem) = if (android(item)) global?.int(item) ?: 0 else RawValue.int(effective[item.key])
    override fun text(item: SettingItem) = if (android(item)) global?.text(item).orEmpty() else RawValue.text(effective[item.key])
    override fun choice(item: SettingItem) = if (android(item)) global?.choice(item).orEmpty() else RawValue.choice(effective[item.key])

    /** 这一项与方案默认不同 */
    fun overridden(item: SettingItem): Boolean =
        !android(item) && item.key in defaults && effective[item.key] != defaults[item.key]

    /** 不在行里标「已自定义」：行高随值变、界面抖；改没改过由标题栏的「恢复默认」统一处理 */
    override fun summary(item: SettingItem): String? =
        if (android(item)) global?.summary(item) ?: item.summary else item.summary

    @Synchronized
    override fun set(item: SettingItem, value: Any): String? {
        if (android(item)) return global.let { if (it == null) "设置未就绪" else it.set(item, value) }
        val toDefault = item.key in defaults && sameValue(typeOf(item.key), defaults[item.key], value)
        return write(
            sets = if (toDefault) emptyList() else listOf(SchemeFormSet(item.key, configValueOf(typeOf(item.key), value))),
            resets = if (toDefault) listOf(item.key) else emptyList(),
        )
    }

    /**
     * 界面值与方案默认按核心类型比：数值按数比（清单的整数滑块写 30，浮点键默认给 30.0），
     * 其余按下拉 / 文本形态比
     */
    private fun sameValue(type: String?, default: Any?, value: Any): Boolean = when (type) {
        "int", "float" -> (default as? Number)?.toDouble() == value.toString().toDoubleOrNull()
        "bool" -> RawValue.bool(default) == (value == true || value == "true")
        else -> RawValue.choice(default) == value.toString()
    }

    /** [items] 里与方案默认不同的项（键盘自己的项不按方案存，不算） */
    override fun customized(items: List<SettingItem>): List<SettingItem> = items.filter { overridden(it) }

    /** 把 [items] 里改过的项全部恢复方案默认 */
    @Synchronized
    override fun reset(items: List<SettingItem>): String? {
        val keys = items.filter { overridden(it) }.map { it.key }
        return if (keys.isEmpty()) null else write(emptyList(), keys)
    }

    /** 方案文件改过的项在类型内置默认里的值（「本方案预设」） */
    fun presets(): Map<String, Any> = builtin

    private fun write(sets: List<SchemeFormSet>, resets: List<String>): String? {
        val err = try {
            io.apply(schemeId, sets, resets)
            null
        } catch (e: ConfigException) {
            Log.w(TAG, "写方案 $schemeId 的设置失败", e)
            (e as? ConfigException.Rejected)?.reason ?: e.toString()
        }
        // 成败都重读：失败时界面也要与文件对得上
        load()
        return err
    }

    private companion object {
        const val TAG = "SchemeFormValues"
    }
}
