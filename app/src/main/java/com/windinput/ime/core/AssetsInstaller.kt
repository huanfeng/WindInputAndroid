package com.windinput.ime.core

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import java.io.File

/**
 * 出厂数据安装器：把 APK assets/data 整树拷到 filesDir/data。
 *
 * 版本锚点 = versionCode（写入 data/.installed_version）：
 * - 首启或 App 升级（versionCode 变化）→ 出厂层整体重放（用户层不动）；
 * - 同版本 → 跳过，零 I/O。
 *
 * 词库 yaml（GPL 合规，原文分发）后续同走此通道进入 data/schemas/，
 * mmap 与端上编译均要求真实文件路径，故必须拷出而非直接读 assets。
 */
class AssetsInstaller(private val context: Context, private val dirs: DataDirs) {

    private companion object {
        const val TAG = "AssetsInstaller"
        const val ASSET_ROOT = "data"
        const val VERSION_MARKER = ".installed_version"
    }

    /** 同步执行（调用方放后台线程）。@return 本次是否执行了拷贝 */
    fun installIfNeeded(): Boolean {
        val current = currentVersion()
        val marker = File(dirs.dataDir, VERSION_MARKER)
        if (marker.isFile && marker.readText().trim() == current) {
            return false
        }
        dirs.ensureCreated()
        copyAssetDir(ASSET_ROOT, dirs.dataDir)
        marker.writeText(current)
        Log.i(TAG, "factory data installed, version=$current")
        return true
    }

    private fun currentVersion(): String = try {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val code = if (android.os.Build.VERSION.SDK_INT >= 28) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
        "$code"
    } catch (e: PackageManager.NameNotFoundException) {
        "unknown"
    }

    private fun copyAssetDir(assetPath: String, target: File) {
        val assets = context.assets
        val children = assets.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            // 叶子 = 文件
            target.parentFile?.mkdirs()
            assets.open(assetPath).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            return
        }
        target.mkdirs()
        for (child in children) {
            copyAssetDir("$assetPath/$child", File(target, child))
        }
    }
}
