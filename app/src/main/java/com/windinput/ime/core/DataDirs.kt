package com.windinput.ime.core

import android.content.Context
import java.io.File

/**
 * 数据目录布局：与桌面端目录语义对齐（docs/architecture.md §3.3）。
 *
 * | Android                  | 桌面对应                          | 内容 |
 * |--------------------------|-----------------------------------|------|
 * | filesDir/data            | 安装目录 data/（出厂层，L2）       | 出厂配置/布局/主题/方案，App 升级整体重放 |
 * | filesDir/user            | %APPDATA%/WindInput（用户层，L3）  | 用户 config.toml、覆盖文件、自定义布局 |
 * | cacheDir/dict            | %LOCALAPPDATA%/cache               | 词库 wdat 编译缓存（可再生） |
 *
 * 覆盖解析与桌面 resolve_data_file 同语义：user 下同名文件整体替代 data 下那份，
 * 失败静默回退。
 */
class DataDirs(context: Context) {

    /** 出厂数据层（assets 拷出，App 升级时整体重放） */
    val dataDir: File = File(context.filesDir, "data")

    /** 用户数据层（升级保留） */
    val userDir: File = File(context.filesDir, "user")

    /** 词库编译缓存（可再生，允许系统清理） */
    val dictCacheDir: File = File(context.cacheDir, "dict")

    fun ensureCreated() {
        dataDir.mkdirs()
        userDir.mkdirs()
        dictCacheDir.mkdirs()
    }

    /**
     * 覆盖解析：user 优先、data 兜底；都不存在返回 null（调用方自行回退内置）。
     * @param relPath 相对路径，如 "layouts/qwerty.toml"
     */
    fun resolveDataFile(relPath: String): File? {
        val user = File(userDir, relPath)
        if (user.isFile) return user
        val factory = File(dataDir, relPath)
        if (factory.isFile) return factory
        return null
    }
}
