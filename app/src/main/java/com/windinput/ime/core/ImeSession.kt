package com.windinput.ime.core

import com.windinput.ime.keyboard.KeyAction

/**
 * 输入会话的 UI 事件：Rust 核心 UiCommand 的 Kotlin 侧投影（结构化数据，UI 自行渲染）。
 *
 * 与主仓协议语义对应（docs/architecture.md §3.1）：候选列表/预编辑/上屏文本。
 * UniFFI 绑定落地后，此模型由生成代码替换或适配。
 */
sealed interface UiEvent {
    /** 预编辑串变化（空串 = 清除组合区） */
    data class Composition(val preedit: String) : UiEvent

    /** 候选列表刷新 */
    data class Candidates(val items: List<String>) : UiEvent

    /** 上屏文本 */
    data class CommitText(val text: String) : UiEvent
}

/**
 * 输入会话接口：未来 UniFFI 生成绑定（wind-android crate）的落位形状。
 *
 * 语义镜像主仓 `wind_bridge::MessageHandler`：
 * - focusGained/focusLost ↔ CMD_FOCUS_GAINED/LOST（对应 onStartInput/onFinishInput）
 * - keyPressed 返回值 = 是否吃键：false 时由 Service 执行默认行为
 *   （与桌面「C++ 吃键集 ⊆ Rust 出字集」契约同构）
 */
interface ImeSession {
    /** UI 事件回调（主线程调用） */
    var listener: ((UiEvent) -> Unit)?

    fun focusGained()

    fun focusLost()

    /** @return true = 会话消费该键；false = 由调用方执行默认行为 */
    fun keyPressed(action: KeyAction, shifted: Boolean): Boolean

    /** 点选候选。@return true = 已处理 */
    fun candidateSelected(index: Int): Boolean

    /** 复位会话状态（清空组合区与候选） */
    fun reset()
}
