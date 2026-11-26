package com.windinput.ime.settings

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.windinput.ime.core.UserConfig
import uniffi.wind_android.ConfigException
import uniffi.wind_android.ConfigValue
import uniffi.wind_android.EngineCommand
import uniffi.wind_android.WindCore

/**
 * 设置页的值：按设置项的来源分两条路读写。
 *
 * - **引擎项**：读核心的配置快照（四层合并后的生效值），写走 `config_set`，写完发 ReloadConfig
 *   让核心即时重读。⚠ 不能写 K 侧的 `<用户根>/mobile/keyboard.toml`——引擎根本不读那个文件，
 *   核心的用户层在 `<用户根>/config.toml`（`Android/data/<包名>/files/`）。
 * - **键盘项**：读写 [UserConfig]，下次弹出键盘时 Service 重读。
 *
 * 核心未就绪（[core] 为 null）时引擎项一律不显示：显示一个读不到值、写了也没用的开关，
 * 比不显示更糟。
 *
 * 所有方法都可能做 IO，调用方放后台线程；[version] 每次写入后自增，供 Compose 重组。
 */
class SettingsValues(
    private val userConfig: UserConfig,
    private val core: WindCore?,
) : ItemValues {
    companion object {
        private const val TAG = "SettingsValues"

        /** 「显示实体键盘设置」开关的键（键盘项，出厂关）：[SettingItem.hardware] 的项靠它放出来 */
        const val HARDWARE_GATE = "ui.android.show_hardware_options"
    }

    /** 值变化的版本号：Compose 读它，写入后递增触发重组 */
    override var version by mutableIntStateOf(0)
        private set

    /** 绕过 [set] 改了配置文件之后（恢复默认设置）让界面重读 */
    fun touch() {
        version++
    }

    @Volatile
    private var engine: Map<String, Any> = emptyMap()

    /** 出厂默认（摊平）：页面「恢复默认」的比较基准与写回值 */
    @Volatile
    private var defaults: Map<String, Any> = emptyMap()

    /** 核心注册表：键 → 类型（bool / int / float / str / enum …） */
    @Volatile
    private var types: Map<String, String> = emptyMap()

    val engineReady: Boolean get() = core != null && types.isNotEmpty()

    /** 键盘配置的原值（不经清单） */
    fun keyboardValues(): Map<String, Any> = userConfig.readAll()

    /** 在配置锁里读改写键盘配置（方案设置页的九宫格开关这类不在清单里的值） */
    fun editKeyboard(block: (MutableMap<String, Any>) -> Unit) {
        userConfig.edit(block)
        version++
    }

    /** 核心注册表里这个键的类型；方案表单写入时按它转换值 */
    fun typeOf(key: String): String? = types[key]

    /** 读核心的注册表与快照（后台线程） */
    fun load() {
        val c = core ?: return
        types = c.configRegistry().associate { it.key to it.ty }
        engine = runCatching { ConfigSnapshot.flatten(c.configSnapshot()) }
            .onFailure { Log.w(TAG, "配置快照解析失败", it) }
            .getOrDefault(emptyMap())
        defaults = runCatching { ConfigSnapshot.flatten(c.configDefaults()) }
            .onFailure { Log.w(TAG, "出厂默认解析失败", it) }
            .getOrDefault(emptyMap())
        version++
    }

    /**
     * 这一项现在能不能显示：键盘项总能；引擎项要核心就绪且键在注册表里。
     * 清单里写错的引擎键在这里被挡掉并记一条警告——不显示一个「点了没反应」的开关。
     */
    override fun available(item: SettingItem): Boolean {
        if (item.source == SettingSource.ANDROID) return true
        if (!engineReady) return false
        return (item.key in types).also { known ->
            if (!known) Log.w(TAG, "设置清单里的引擎键不在核心注册表里：${item.key}")
        }
    }

    /**
     * 这一项现在该不该显示：[SettingItem.enabledWhen] 指向的开关关着就藏起来。
     * 照电脑版的 enabled_when，只是电脑版置灰、手机上直接不显示——屏幕小，一列灰掉的选项只是噪声。
     * 逐级看：依赖的开关自己被收起（它的依赖关着）时本项也收起，否则总开关关着时
     * 「提示范围」还会因「显示正确拼音」开着而单独露出来。
     */
    override fun visible(item: SettingItem, all: List<SettingItem>): Boolean = visible(item, all, 0)

    private fun visible(item: SettingItem, all: List<SettingItem>, depth: Int): Boolean {
        if (item.hardware && userConfig.readAll()[HARDWARE_GATE] != true) return false
        val dep = item.enabledWhen ?: return true
        val depItem = all.firstOrNull { it.key == dep } ?: return true
        // 清单解析时不查环；写成环也只是收起，不会栈溢出
        return bool(depItem) && (depth >= 8 || visible(depItem, all, depth + 1))
    }

    private fun raw(item: SettingItem): Any? = when (item.source) {
        SettingSource.ENGINE -> engine[item.key]
        SettingSource.ANDROID -> userConfig.readAll()[item.key] ?: item.defaultValue
    }

    override fun bool(item: SettingItem): Boolean = RawValue.bool(raw(item))

    override fun int(item: SettingItem): Int = RawValue.int(raw(item))

    /** text 项的当前值：列表型配置拼成空格分隔的一行 */
    override fun text(item: SettingItem): String = RawValue.text(raw(item))

    /** select 的当前值，与选项的 value 同形（字符串） */
    override fun choice(item: SettingItem): String = RawValue.choice(raw(item))

    override fun summary(item: SettingItem): String? = item.summary

    /**
     * 写入。返回错误说明（成功为 null）。后台线程调用。
     *
     * 串行：每次点击各起一个 IO 协程，不锁的话两次写真会并行——连点「简入繁出」「繁入简出」时
     * 两边都读到对方「关着」，各写各的，二选一就同时开了；两次 ReloadConfig 也会交错。
     */
    @Synchronized
    override fun set(item: SettingItem, value: Any): String? {
        // 二选一：打开本项时先关掉另一项（两边都是引擎项，一次重载）
        val partner = item.exclusiveWith?.takeIf { value == true || value == "true" }
        val err = when (item.source) {
            SettingSource.ENGINE -> setEngine(item.key, value, partner)
            SettingSource.ANDROID -> {
                userConfig.put(item.key, androidValue(item, value))
                null
            }
        }
        version++
        return err
    }

    override fun customized(items: List<SettingItem>): List<SettingItem> {
        val kb = userConfig.readAll()
        return items.filter { item ->
            when (item.source) {
                SettingSource.ENGINE -> item.key in defaults && item.key in engine && engine[item.key] != defaults[item.key]
                SettingSource.ANDROID -> item.key in kb && kb[item.key].toString() != item.defaultValue.toString()
            }
        }
    }

    /**
     * 恢复默认：引擎项把出厂值写回（核心写入等于出厂值时直接删键，与电脑版「恢复默认」同效），
     * 一次重载；键盘项删键。
     */
    @Synchronized
    override fun reset(items: List<SettingItem>): String? {
        val changed = customized(items)
        val (engineItems, kbItems) = changed.partition { it.source == SettingSource.ENGINE }
        val kbKeys = kbItems.map { it.key }.toSet()
        if (kbKeys.isNotEmpty()) userConfig.removeIf { it in kbKeys }
        var err: String? = null
        if (engineItems.isNotEmpty()) {
            val c = core
            err = if (c == null) "输入法核心未就绪" else try {
                for (item in engineItems) c.configSet(item.key, rawConfigValue(types[item.key], defaults.getValue(item.key)))
                null
            } catch (e: ConfigException) {
                Log.w(TAG, "恢复默认失败", e)
                (e as? ConfigException.Rejected)?.reason ?: e.toString()
            } finally {
                // 成败都重载、重读：写了一半时界面也要与文件对得上
                runCatching {
                    c.runCommand(EngineCommand.ReloadConfig)
                    engine = ConfigSnapshot.flatten(c.configSnapshot())
                }
            }
        }
        version++
        return err
    }

    private fun setEngine(key: String, value: Any, turnOff: String? = null): String? {
        val c = core ?: return "输入法核心未就绪"
        val cv = configValueOf(types[key], value)
        return try {
            if (turnOff != null && engine[turnOff] == true) c.configSet(turnOff, ConfigValue.Bool(false))
            c.configSet(key, cv)
            c.runCommand(EngineCommand.ReloadConfig)
            engine = ConfigSnapshot.flatten(c.configSnapshot())
            null
        } catch (e: ConfigException) {
            Log.w(TAG, "写入 $key 失败", e)
            // 二选一的另一项可能已经写进去了：重载并刷新快照，界面才和文件对得上
            runCatching {
                c.runCommand(EngineCommand.ReloadConfig)
                engine = ConfigSnapshot.flatten(c.configSnapshot())
            }
            (e as? ConfigException.Rejected)?.reason ?: e.toString()
        }
    }

    /** 键盘项按默认值的类型存：默认是整数的 select（如长按速度）存成整数，Service 按整数读 */
    private fun androidValue(item: SettingItem, value: Any): Any = when (item.defaultValue) {
        is Boolean -> value == true || value == "true"
        is Long, is Int -> value.toString().toLong()
        else -> value.toString()
    }
}
