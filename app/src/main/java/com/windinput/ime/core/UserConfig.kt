package com.windinput.ime.core

import android.util.Log
import org.tomlj.Toml
import java.io.File

/**
 * 用户层配置（filesDir/user/config.toml）的最小读写。
 *
 * ⚠️ 过渡实现：正式配置链路由 Rust wind-config 负责（三层合并 + REGISTRY 校验），
 * 本类只服务于 Rust 核心接入前的 Kotlin 侧设置项（点分键 → TOML 节）。
 * 文件格式与桌面用户层 config.toml 一致，接入后可被 wind-config 直接读取。
 */
class UserConfig(private val file: File) {

    private companion object {
        const val TAG = "UserConfig"
    }

    /** 点分键 → 值（String/Boolean/Long/Double），读取失败返回空表 */
    fun readAll(): Map<String, Any> {
        if (!file.isFile) return emptyMap()
        return try {
            val result = Toml.parse(file.readText())
            if (result.hasErrors()) {
                Log.w(TAG, "config.toml has errors: ${result.errors()}")
            }
            result.dottedKeySet().associateWith { key -> result.get(key)!! }
        } catch (e: Exception) {
            Log.w(TAG, "read config.toml failed", e)
            emptyMap()
        }
    }

    fun getBoolean(key: String, default: Boolean): Boolean =
        readAll()[key] as? Boolean ?: default

    /**
     * 写回一个键值（读全量 → 改内存 → 重新序列化整文件）。
     * 值仅支持标量（Boolean/Long/Double/String）。
     */
    fun put(key: String, value: Any) {
        val all = readAll().toMutableMap()
        all[key] = value
        write(all)
    }

    private fun write(values: Map<String, Any>) {
        // 按节分组：a.b.c=v → [a.b] 下的 c = v；无点键归根节
        val sections = sortedMapOf<String, MutableList<Pair<String, Any>>>()
        for ((key, value) in values) {
            val lastDot = key.lastIndexOf('.')
            val section = if (lastDot < 0) "" else key.substring(0, lastDot)
            val name = if (lastDot < 0) key else key.substring(lastDot + 1)
            sections.getOrPut(section) { mutableListOf() }.add(name to value)
        }
        val sb = StringBuilder("# WindInput Android 用户层配置（设置页写入）\n")
        for ((section, entries) in sections) {
            if (section.isNotEmpty()) {
                sb.append("\n[").append(section).append("]\n")
            }
            for ((name, value) in entries.sortedBy { it.first }) {
                sb.append(name).append(" = ").append(serialize(value)).append('\n')
            }
        }
        file.parentFile?.mkdirs()
        file.writeText(sb.toString())
    }

    private fun serialize(value: Any): String = when (value) {
        is Boolean, is Long, is Int, is Double -> value.toString()
        else -> "\"" + value.toString().replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }
}
