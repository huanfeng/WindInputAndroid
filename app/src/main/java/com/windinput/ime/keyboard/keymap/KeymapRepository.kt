package com.windinput.ime.keyboard.keymap

import android.util.Log
import com.windinput.ime.keyboard.KeyDef
import com.windinput.ime.keyboard.KeyboardLayer
import java.io.File

/**
 * `<userRoot>/mobile/keymap.toml` 的读取缓存 + 合并入口。
 *
 * 按（路径存在、修改时间、长度）判断是否重读；文件不存在时是零开销的空结果。
 * 解析告警只在每次重读时记一次日志，不重复刷。
 */
class KeymapRepository(private val file: File) {

    private companion object {
        const val TAG = "KeymapRepository"
    }

    private class Stamp(val exists: Boolean, val modified: Long, val length: Long)

    private var stamp: Stamp? = null
    private var parsed: KeymapParseResult = KeymapParseResult.EMPTY

    /** 文件版本高于本版本支持（整份被忽略）；设置页据此提示 */
    val tooNew: Boolean get() = current().tooNew

    @Synchronized
    private fun current(): KeymapParseResult {
        val now = Stamp(file.isFile, file.lastModified(), file.length())
        val old = stamp
        if (old != null && old.exists == now.exists && old.modified == now.modified && old.length == now.length) return parsed
        parsed = KeymapParser.load(file).also { r -> r.warnings.forEach { Log.w(TAG, it) } }
        stamp = now
        return parsed
    }

    fun apply(rows: List<List<KeyDef>>, layer: KeyboardLayer): List<List<KeyDef>> =
        KeymapMerge.apply(rows, layer, current()) { Log.w(TAG, it) }
}
