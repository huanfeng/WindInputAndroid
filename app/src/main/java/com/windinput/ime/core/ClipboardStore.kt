package com.windinput.ime.core

import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 剪贴板历史存储：监听系统剪贴板，JSON 持久化（设备本地，不进 user 同步树）。
 *
 * 能力对齐 fcitx5 ClipboardManager 的核心子集：置顶、删除、清空(跳过置顶)、
 * 条数上限、敏感内容跳过(Android 13 IS_SENSITIVE 标记)。
 * 过渡实现：数据量小（上限 50 条），全量读写 JSON 文件；后续量大再换 SQLite。
 */
class ClipboardStore(context: Context) : ClipboardManager.OnPrimaryClipChangedListener {

    data class Entry(val text: String, val time: Long, val pinned: Boolean)

    private companion object {
        const val TAG = "ClipboardStore"
        const val MAX_UNPINNED = 50
        const val MAX_TEXT_LEN = 100_000
    }

    private val file = File(context.filesDir, "clipboard.json")
    private val clipboardManager =
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    private val entries = mutableListOf<Entry>()

    /** 历史变化通知（面板刷新用） */
    var onChanged: (() -> Unit)? = null

    init {
        load()
    }

    fun start() = clipboardManager.addPrimaryClipChangedListener(this)

    fun stop() = clipboardManager.removePrimaryClipChangedListener(this)

    /** 最新在前，置顶优先 */
    fun list(): List<Entry> =
        entries.sortedWith(compareByDescending<Entry> { it.pinned }.thenByDescending { it.time })

    override fun onPrimaryClipChanged() {
        val clip = clipboardManager.primaryClip ?: return
        if (Build.VERSION.SDK_INT >= 33 &&
            clip.description?.extras?.getBoolean("android.content.extra.IS_SENSITIVE") == true
        ) {
            return // 敏感内容（密码管理器等）不入历史
        }
        val text = clip.getItemAt(0)?.coerceToText(null)?.toString() ?: return
        if (text.isBlank() || text.length > MAX_TEXT_LEN) return
        add(text)
    }

    fun add(text: String) {
        // 同文重复：仅刷新时间（保留置顶态）
        val existing = entries.indexOfFirst { it.text == text }
        if (existing >= 0) {
            entries[existing] = entries[existing].copy(time = System.currentTimeMillis())
        } else {
            entries += Entry(text, System.currentTimeMillis(), pinned = false)
            trim()
        }
        persistAndNotify()
    }

    fun setPinned(text: String, pinned: Boolean) {
        val i = entries.indexOfFirst { it.text == text }
        if (i < 0) return
        entries[i] = entries[i].copy(pinned = pinned)
        persistAndNotify()
    }

    fun remove(text: String) {
        if (entries.removeAll { it.text == text }) persistAndNotify()
    }

    /** 清空（保留置顶项） */
    fun clearUnpinned() {
        if (entries.removeAll { !it.pinned }) persistAndNotify()
    }

    private fun trim() {
        val unpinned = entries.filter { !it.pinned }.sortedByDescending { it.time }
        if (unpinned.size <= MAX_UNPINNED) return
        val evict = unpinned.drop(MAX_UNPINNED).toSet()
        entries.removeAll { it in evict }
    }

    private fun persistAndNotify() {
        try {
            val arr = JSONArray()
            for (e in entries) {
                arr.put(
                    JSONObject()
                        .put("text", e.text)
                        .put("time", e.time)
                        .put("pinned", e.pinned),
                )
            }
            file.writeText(arr.toString())
        } catch (e: Exception) {
            Log.w(TAG, "persist clipboard failed", e)
        }
        onChanged?.invoke()
    }

    private fun load() {
        if (!file.isFile) return
        try {
            val arr = JSONArray(file.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                entries += Entry(o.getString("text"), o.getLong("time"), o.getBoolean("pinned"))
            }
        } catch (e: Exception) {
            Log.w(TAG, "load clipboard failed", e)
        }
    }
}
