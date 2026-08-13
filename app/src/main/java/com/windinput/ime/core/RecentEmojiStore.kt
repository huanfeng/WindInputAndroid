package com.windinput.ime.core

import android.content.Context

/**
 * 最近使用的表情（LRU，容量 40），SharedPreferences 持久化。
 * 对齐 fcitx5 RecentlyUsed 语义：最近使用分类自动置首。
 */
class RecentEmojiStore(context: Context) {

    private companion object {
        const val PREFS = "recent_emoji"
        const val KEY = "items"
        const val CAPACITY = 40
        const val SEPARATOR = "\u0001"
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun list(): List<String> =
        prefs.getString(KEY, null)?.split(SEPARATOR)?.filter { it.isNotEmpty() } ?: emptyList()

    fun record(emoji: String) {
        val items = list().toMutableList()
        items.remove(emoji)
        items.add(0, emoji)
        while (items.size > CAPACITY) items.removeAt(items.size - 1)
        prefs.edit().putString(KEY, items.joinToString(SEPARATOR)).apply()
    }
}
