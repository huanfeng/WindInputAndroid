package com.windinput.ime.settings

import android.util.Log
import com.windinput.ime.core.DataDirs
import org.tomlj.Toml

/**
 * 设置清单：TOML → 数据模型，驱动设置页生成。
 * 三端对齐机制见 docs/architecture.md §3.3（platform 字段门控）。
 */
data class SettingItem(
    val key: String,
    val type: String,
    val title: String,
    val summary: String?,
    val defaultValue: Any?,
    val platform: String?,
    /** int 类型的取值范围与步长 */
    val min: Int? = null,
    val max: Int? = null,
    val step: Int = 1,
    /** select 类型的选项：(value, label)，value 即写入配置的字符串 */
    val options: List<Pair<String, String>> = emptyList(),
    /** 改动后要通知引擎重读配置（引擎侧的键；UI 侧的键不需要，Service 每次弹出都重读） */
    val reload: Boolean = false,
)

data class SettingGroup(
    val title: String,
    val items: List<SettingItem>,
)

object SettingsManifest {

    private const val TAG = "SettingsManifest"

    /** 从数据目录加载清单（user 覆盖 data），失败返回空列表 */
    fun load(dirs: DataDirs): List<SettingGroup> {
        val file = dirs.resolveDataFile("settings_manifest.toml") ?: return emptyList()
        return try {
            parse(file.readText())
        } catch (e: Exception) {
            Log.w(TAG, "settings manifest parse failed", e)
            emptyList()
        }
    }

    fun parse(toml: String): List<SettingGroup> {
        val result = Toml.parse(toml)
        if (result.hasErrors()) {
            throw IllegalArgumentException("TOML errors: ${result.errors()}")
        }
        val groups = result.getArray("group") ?: return emptyList()
        return (0 until groups.size()).map { g ->
            val group = groups.getTable(g)
            val items = group.getArray("item")
            SettingGroup(
                title = group.getString("title") ?: "",
                items = (0 until (items?.size() ?: 0)).map { i ->
                    val item = items!!.getTable(i)
                    SettingItem(
                        key = requireNotNull(item.getString("key")) { "item 缺少 key" },
                        type = item.getString("type") ?: "toggle",
                        title = item.getString("title") ?: "",
                        summary = item.getString("summary"),
                        defaultValue = item.get("default"),
                        platform = item.getString("platform"),
                        min = item.getLong("min")?.toInt(),
                        max = item.getLong("max")?.toInt(),
                        step = item.getLong("step")?.toInt() ?: 1,
                        options = item.getArray("options")?.let { arr ->
                            (0 until arr.size()).map { o ->
                                val t = arr.getTable(o)
                                requireNotNull(t.getString("value")) { "option 缺少 value" } to
                                    (t.getString("label") ?: t.getString("value")!!)
                            }
                        } ?: emptyList(),
                        reload = item.getBoolean("reload") ?: false,
                    )
                },
            )
        }
    }
}
