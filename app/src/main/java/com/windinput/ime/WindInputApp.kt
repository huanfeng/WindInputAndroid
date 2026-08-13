package com.windinput.ime

import android.app.Application
import com.windinput.ime.core.AssetsInstaller
import com.windinput.ime.core.DataDirs
import kotlin.concurrent.thread

class WindInputApp : Application() {

    lateinit var dataDirs: DataDirs
        private set

    override fun onCreate() {
        super.onCreate()
        dataDirs = DataDirs(this).also { it.ensureCreated() }
        // 出厂数据拷出（首启/升级时执行；小文件阶段秒级，词库进来后改带进度）
        thread(name = "assets-install") {
            AssetsInstaller(this, dataDirs).installIfNeeded()
        }
        // 预留：初始化 Rust 核心（加载 .so、词库缓存检查）
        // WindCore.initialize(this)
    }
}
