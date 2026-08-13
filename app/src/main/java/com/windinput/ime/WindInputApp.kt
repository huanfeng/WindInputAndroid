package com.windinput.ime

import android.app.Application

class WindInputApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // 预留：初始化 Rust 核心（加载 .so、目录准备、词库缓存检查）
        // WindCore.initialize(this)
    }
}
