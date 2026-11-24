package com.windinput.ime.core

import android.content.Context
import com.windinput.ime.WindInputApp
import com.windinput.ime.settings.CoreAccess
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import uniffi.wind_android.MobileSyncHandle
import uniffi.wind_android.WindCore

/**
 * 数据同步句柄：进程内唯一，与核心（[RustSession] 单例）同生命周期。
 *
 * 状态目录取核心的本地目录（[DataDirs.localRoot]，即构造 WindCore 的 `local_root`），
 * 同步的设置、状态、机密都在其下 `sync/`；用户目录与核心的 `user_root` 同一个。
 * 备份规则排除了 `rust/data/WindInput/sync/`（见 res/xml/backup_rules.xml）。
 */
object SyncHub {

    /** 键盘每次弹出最多触发一轮同步的最小间隔（秒） */
    const val MIN_INTERVAL_SECS = 600uL

    @Volatile
    private var handle: MobileSyncHandle? = null
    private val kicking = AtomicBoolean(false)

    @Synchronized
    fun get(core: WindCore, dirs: DataDirs): MobileSyncHandle {
        handle?.let { return it }
        return MobileSyncHandle(core, dirs.userRoot.absolutePath, dirs.localRoot.absolutePath)
            .also { handle = it }
    }

    /**
     * 键盘弹出时调用：到期则后台起一轮同步。自己切后台线程，不阻塞调用方（主线程）；
     * 上一次调用还没返回时直接略过。
     */
    fun syncIfDue(context: Context) {
        val dirs = (context.applicationContext as WindInputApp).dataDirs
        // 从没进过同步设置页的用户没有设置文件：不建句柄、不起线程，零开销
        if (handle == null && !File(dirs.localRoot, "sync/settings.toml").isFile) return
        if (!kicking.compareAndSet(false, true)) return
        Thread({
            try {
                // 核心可能还在构造：取用会等它（同一把单例锁），所以必须在后台线程
                val core = CoreAccess.obtainCore(context) ?: return@Thread
                get(core, dirs).syncIfDue(MIN_INTERVAL_SECS)
            } catch (_: Throwable) {
                // 同步失败不能影响输入
            } finally {
                kicking.set(false)
            }
        }, "wind-sync-kick").start()
    }
}
