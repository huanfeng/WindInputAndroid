package com.windinput.ime.core

import android.util.Log

/**
 * Rust 核心（libwind_android.so）加载器。
 *
 * UniFFI 生成绑定（uniffi.wind_android 包）由 JNA 按库名自动加载；
 * 这里仅做一次显式预加载探测，失败则 [available] = false，
 * 会话层回退 FakeSession（无 .so 的构建形态仍可运行）。
 */
object RustCoreLoader {
    private const val TAG = "RustCoreLoader"

    val available: Boolean by lazy {
        try {
            System.loadLibrary("wind_android")
            true
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "libwind_android.so not bundled, using FakeSession", e)
            false
        }
    }
}
