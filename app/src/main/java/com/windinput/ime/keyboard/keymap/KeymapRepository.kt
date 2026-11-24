package com.windinput.ime.keyboard.keymap

import android.util.Log
import com.windinput.ime.keyboard.KeyDef
import com.windinput.ime.keyboard.KeyboardLayer
import java.io.File

/**
 * `<userRoot>/mobile/keymap.toml` 的读取缓存 + 合并入口。
 *
 * 底层是设置里选的出厂预设（[presetFile]），其上是「自定义键位」页写的 [editorFile]，最上是用户手写的 keymap.toml，逐手势依次覆盖。
 * 按（两个文件的存在、修改时间、长度）判断是否重读；文件不存在时是零开销的空结果。
 * 解析告警只在每次重读时记一次日志，不重复刷。
 */
class KeymapRepository(
    private val file: File,
    private val log: (String) -> Unit = { Log.w(TAG, it) },
    private val editorFile: File? = null,
    private val presetFile: () -> File? = { null },
) {

    private companion object {
        const val TAG = "KeymapRepository"
    }

    private data class Stamp(
        val exists: Boolean, val modified: Long, val length: Long,
        val presetPath: String?, val presetModified: Long, val presetLength: Long,
        val editorExists: Boolean, val editorModified: Long, val editorLength: Long,
    )

    private var stamp: Stamp? = null
    private var parsed: KeymapParseResult = KeymapParseResult.EMPTY

    /** 文件版本高于本版本支持（整份被忽略）；设置页据此提示 */
    val tooNew: Boolean get() = current().tooNew

    @Synchronized
    private fun current(): KeymapParseResult {
        val preset = presetFile()
        val now = Stamp(
            file.isFile, file.lastModified(), file.length(),
            preset?.path, preset?.lastModified() ?: 0L, preset?.length() ?: 0L,
            editorFile?.isFile == true, editorFile?.lastModified() ?: 0L, editorFile?.length() ?: 0L,
        )
        if (now == stamp) return parsed
        val user = KeymapParser.load(file)
        val base = preset?.let { KeymapParser.load(it) } ?: KeymapParseResult.EMPTY
        val edited = editorFile?.let { KeymapParser.load(it) } ?: KeymapParseResult.EMPTY
        parsed = base.overlaid(edited).overlaid(user).also { r -> r.warnings.forEach(log) }
        stamp = now
        return parsed
    }

    fun apply(rows: List<List<KeyDef>>, layer: KeyboardLayer): List<List<KeyDef>> =
        KeymapMerge.apply(rows, layer, current(), log)
}
