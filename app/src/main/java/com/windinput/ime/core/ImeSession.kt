package com.windinput.ime.core

import com.windinput.ime.keyboard.KeyAction

/**
 * 输入会话的 UI 事件：Rust 核心 UiCommand 的 Kotlin 侧投影（结构化数据，UI 自行渲染）。
 *
 * 与主仓协议语义对应（docs/architecture.md §3.1）：候选列表/编码区/上屏文本/模式状态。
 */
/**
 * 一次编辑操作（核心 `wind_host::EditOp` 的 Kotlin 投影）。
 *
 * 按键结果从「上屏文本 + 组合区」的三元组改成指令流，是因为前者只装得下最基本的
 * 插入动作：智能符号配对、回退替换、配对跳出这些在移动端曾被**静默丢弃**
 * （不报错、不掉键，单纯功能没有）。指令由核心的穷尽映射产出，新增动作会在核心编译期报错。
 */
sealed interface EditOp {
    data class Commit(val text: String) : EditOp

    /** @param caret 插入符在 [text] 内的字符位置 */
    data class SetComposition(val text: String, val caret: Int) : EditOp

    data class DeleteBackward(val count: Int) : EditOp

    /** 删除光标前 [count] 个字符后插入 [text]（一步完成，宿主撤销栈才记成一次） */
    data class ReplaceBackward(val count: Int, val text: String) : EditOp

    /** 光标水平移动（正右负左），单位字符 */
    data class MoveCursor(val delta: Int) : EditOp
}

sealed interface UiEvent {
    /** 编辑指令流：按序执行 */
    data class Edits(val ops: List<EditOp>) : UiEvent

    /**
     * 候选帧：编码栏与候选栏一次渲染所需的全部数据。
     *
     * @param preedit 编码区正文（五笔码/拼音串），编码栏显示它
     * @param modeLabel 模式短称（五/拼/英/符），编码栏左侧角标；空 = 不显示
     * @param page 当前页（1 起），[totalPages] ≤ 1 时不显示翻页指示
     */
    data class Candidates(
        val items: List<String>,
        val preedit: String = "",
        val modeLabel: String = "",
        val selected: Int = 0,
        val page: Int = 1,
        val totalPages: Int = 1,
    ) : UiEvent

    /**
     * 引擎模式真值变化。
     *
     * **由引擎推送，K 侧不自记**：方案切换会改写 [iconLabel]，密码框会强制英文，
     * 这些都发生在引擎内部；本地另记一份开关态必然与引擎漂移。
     */
    data class Status(
        val chineseMode: Boolean,
        val iconLabel: String,
        val fullWidth: Boolean,
        val chinesePunct: Boolean,
        val s2tEnabled: Boolean,
        val s2tShown: Boolean,
    ) : UiEvent

    /** 一次性提示（方案切换/模式切换/词库就绪/错误） */
    data class Toast(val text: String) : UiEvent
}

/** 输入方案条目（方案选择器数据源）；[index] 即下发给引擎的选择下标 */
data class SchemaEntry(
    val index: Int,
    val id: String,
    val name: String,
    val iconLabel: String,
    val active: Boolean,
)

/**
 * 引擎命令：快捷面板上的开关落到引擎的唯一通路。
 *
 * 只列**移动端真正会用**的项——桌面菜单里的截图/打开目录/诊断 HUD 不在此列
 * （见主仓 wind-android `EngineCommand` 的说明）。
 */
sealed interface EngineCmd {
    /** 切英文模式 */
    data object English : EngineCmd

    /** 选中第 N 个方案（下标取自 [SchemaEntry.index]），并回到中文模式 */
    data class SelectSchema(val index: Int) : EngineCmd

    data object TogglePunct : EngineCmd

    data object ToggleWidth : EngineCmd

    data object ToggleS2t : EngineCmd

    /** 重载用户配置（设置页改完即时生效） */
    data object ReloadConfig : EngineCmd
}

/**
 * 输入会话接口：UniFFI 绑定（主仓 wind-android crate）的落位形状。
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

    /** 可选方案列表（快捷面板的方案选择器） */
    fun schemas(): List<SchemaEntry> = emptyList()

    /** 下达引擎命令；结果经 [UiEvent.Status] 异步回报 */
    fun runCommand(cmd: EngineCmd) = Unit
}
