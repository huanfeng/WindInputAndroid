package com.windinput.ime.core

import android.util.Log
import java.io.File

/**
 * 老版本的用户数据在私有目录 `filesDir` 下；用户层搬到 `Android/data/<包名>/files`（[DataDirs.userRoot]）
 * 之后，首次启动把它们挪过去。
 *
 * | 旧位置（filesDir 下）                      | 新位置（userRoot 下）  |
 * |-------------------------------------------|------------------------|
 * | user/ 下全部（键盘配置、布局、图标）              | keyboard/ 下全部           |
 * | rust/config/WindInput/ 下全部（核心用户配置）     | 用户根本身                  |
 * | rust/meta.toml、rust/userdata.redb.state   | 同名                   |
 * | rust/data/ 下全部（除 WindInput/：缓存与日志）    | data/ 下全部              |
 *
 * 缓存与日志（`rust/data/WindInput/`）本来就是本地目录，原地不动。
 *
 * 原则：目标已有的文件**不覆盖**（用户可能已经手工放了文件）；复制成功后才删旧的；
 * 写完成标记后不再运行。必须在核心构造、键盘读配置**之前**同步完成。
 */
object LegacyUserDataMigration {
    private const val TAG = "LegacyMigration"
    const val MARKER = ".migrated_from_private"

    /** @return 本次是否真的迁移了旧数据 */
    fun run(filesDir: File, userRoot: File, log: (String) -> Unit = { Log.i(TAG, it) }): Boolean {
        val marker = File(userRoot, MARKER)
        if (marker.isFile) return false
        userRoot.mkdirs()
        val rust = File(filesDir, "rust")
        val moves = listOf(
            File(filesDir, "user") to File(userRoot, "keyboard"),
            File(rust, "config/WindInput") to userRoot,
            File(rust, "meta.toml") to File(userRoot, "meta.toml"),
            File(rust, "userdata.redb.state") to File(userRoot, "userdata.redb.state"),
        ) + (rust.resolve("data").listFiles().orEmpty())
            .filter { it.name != "WindInput" }
            .map { it to File(userRoot, "data/${it.name}") }

        var moved = false
        for ((from, to) in moves) {
            if (!from.exists()) continue
            if (mergeInto(from, to, log)) moved = true
        }
        File(rust, "config").delete() // 只剩空壳时才删（delete 对非空目录无效）
        marker.writeText("1")
        if (moved) log("老用户数据已迁到 ${userRoot.absolutePath}")
        return moved
    }

    /** 把 [from] 合并进 [to]（不覆盖已有文件）。**全部**复制成功才删除 [from]；返回是否全部成功。 */
    internal fun mergeInto(from: File, to: File, log: (String) -> Unit): Boolean = try {
        val ok = if (from.isDirectory) {
            to.mkdirs()
            from.listFiles().orEmpty().map { mergeInto(it, File(to, it.name), log) }.all { it }
        } else {
            if (!to.exists()) {
                to.parentFile?.mkdirs()
                from.copyTo(to)
            }
            true
        }
        if (ok) from.deleteRecursively()
        ok
    } catch (e: Exception) {
        log("迁移失败，保留旧文件：${from.absolutePath}（$e）")
        false
    }
}
