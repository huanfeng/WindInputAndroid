package com.windinput.ime.core

/**
 * Rust 核心（wind-android cdylib）的 Kotlin 门面占位。
 *
 * 正式实现由主仓 `wind-android` crate 经 UniFFI 生成绑定后替换：
 * - 输入通道：键事件（VK 码，映射基准为主仓 wind-keys）、焦点/生命周期事件
 * - 输出通道：UiCommand 结构化推送（候选列表、预编辑、上屏文本、模式状态），
 *   经 UniFFI callback interface 回调，参见 docs/architecture.md §3.1
 *
 * 当前阶段（walking skeleton 之前）：键盘 UI 直接把字符透传上屏，不经引擎。
 */
object WindCore {
    /** Rust 核心是否已加载。占位恒为 false，UI 层据此走透传路径。 */
    val isLoaded: Boolean
        get() = false
}
