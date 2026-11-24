package com.windinput.ime.core

import android.content.Context
import java.io.File

/**
 * 数据目录布局：与桌面端目录语义对齐（docs/architecture.md §3.3）。
 *
 * | Android                                   | 桌面对应                          | 内容 |
 * |-------------------------------------------|-----------------------------------|------|
 * | filesDir/data                             | 安装目录 data/（出厂层，L2）       | 出厂配置/布局/主题/方案，App 升级整体重放 |
 * | getExternalFilesDir(null)（[userRoot]）    | %APPDATA%/WindInput（用户层，L3）  | config.toml、schemas/、schema_overrides/、用户词库与统计——**用户可经文件管理器直接编辑与同步** |
 * | [userRoot]/mobile（[mobileDir]）           | —（移动端独有）                    | keyboard.toml（键盘配置）、自定义布局与图标，之后还有 keymap.toml、skins/；**不使用 PC 已占用的文件名**，便于与 PC 同步时按目录区分归属 |
 * | filesDir/rust/data/WindInput（[localRoot]）| %LOCALAPPDATA%/WindInput           | 核心的词库编译缓存与日志（可再生，不进用户视野） |
 * | cacheDir/dict                             | —                                 | 词库 wdat 编译缓存（可再生） |
 *
 * 用户层放 `Android/data/<包名>/files` 而不是私有目录：用户要能直接改配置、放扩展词库，
 * 也为之后的方案同步留出文件入口。外部目录取不到（存储未挂载、个别 ROM 限制）时**直接报错**，
 * 不回退私有目录——悄悄换位置会让用户改了文件却不生效。
 *
 * 覆盖解析与桌面 resolve_data_file 同语义：用户层同名文件整体替代 data 下那份，
 * 失败静默回退。
 */
class DataDirs(context: Context) {

    /** 出厂数据层（assets 拷出，App 升级时整体重放） */
    val dataDir: File = File(context.filesDir, "data")

    /**
     * 用户根 = 核心的用户配置目录（升级保留）。
     * @throws StorageUnavailableException 外部存储不可用
     */
    val userRoot: File = context.getExternalFilesDir(null)
        ?: throw StorageUnavailableException("无法访问 Android/data/${context.packageName}/files（外部存储不可用）")

    /** 移动端独有的用户层目录：keyboard.toml、layouts/、icons/（引擎不读） */
    val mobileDir: File = File(userRoot, "mobile")

    /** 用户键位覆盖（`keymap.toml`，只存差异，见 docs/key-mapping-skin-plan.md）；移动端独有文件名 */
    val keymapFile: File = File(mobileDir, "keymap.toml")

    /** 用户皮肤目录：`skins/<id>/skin.toml`（内置皮肤在 [dataDir]/skins，随 App 升级重放） */
    val userSkinsDir: File = File(mobileDir, "skins")

    /** 皮肤选择（`active = "<id>"`）；移动端独有文件名 */
    val skinPrefsFile: File = File(mobileDir, "skin_prefs.toml")

    /** 皮肤的发现 / 选择 / 载入；Service 与设置页各自建一个，状态都在文件里 */
    fun skinStore(): com.windinput.ime.skin.SkinStore =
        com.windinput.ime.skin.SkinStore(File(dataDir, "skins"), userSkinsDir, skinPrefsFile)

    /** 键盘自己的配置（`ui.android.*`）；叫 keyboard.toml 而不是 config.toml，是为了不与 PC / 引擎的 config.toml 同名 */
    val keyboardConfigFile: File = File(mobileDir, "keyboard.toml")

    /** 核心的本地目录：词库编译缓存与日志（私有、可再生） */
    val localRoot: File = File(context.filesDir, "rust/data/WindInput")

    /** 核心用户层的 config.toml（设置页的引擎项只能经核心读写，这里仅供重置与日志级别读盘） */
    val engineConfigFile: File = File(userRoot, "config.toml")

    /** 词库编译缓存（可再生，允许系统清理） */
    val dictCacheDir: File = File(context.cacheDir, "dict")

    fun ensureCreated() {
        dataDir.mkdirs()
        mobileDir.mkdirs()
        localRoot.mkdirs()
        dictCacheDir.mkdirs()
    }

    /**
     * 覆盖解析：user 优先、data 兜底；都不存在返回 null（调用方自行回退内置）。
     * @param relPath 相对路径，如 "layouts/qwerty.toml"
     */
    fun resolveDataFile(relPath: String): File? {
        val user = File(mobileDir, relPath)
        if (user.isFile) return user
        val factory = File(dataDir, relPath)
        if (factory.isFile) return factory
        return null
    }
}

/** 外部存储（用户层所在）不可用。 */
class StorageUnavailableException(message: String) : IllegalStateException(message)
