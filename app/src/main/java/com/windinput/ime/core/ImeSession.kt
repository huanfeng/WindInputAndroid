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
     * @param preeditCaret 编码区插入符（[preedit] 内字节偏移）。**联想态恒为 0**：
     *   那时 [preedit] 装的是「联想输入」标识而不是编码，缓冲是空的，见 [composing]
     */
    data class Candidates(
        val items: List<String>,
        val preedit: String = "",
        val preeditCaret: Int = 0,
        val modeLabel: String = "",
        val selected: Int = 0,
        val page: Int = 1,
        val totalPages: Int = 1,
    ) : UiEvent {
        /**
         * 引擎缓冲里真有未上屏的编码（不是联想态）。
         *
         * 联想态的 [preedit] 非空（编码栏要显示「联想输入」标识），只看它会把联想当成
         * 打字中：回车写成「确定」、滑退格去清一串不存在的编码。缓冲为空时插入符恒为 0，
         * 打字时插入符在编码末尾（手机上没有在编码里移插入符的操作），故用它区分。
         *
         * ⚠ 权宜判据：根治是核心在候选帧里直接给出「缓冲是否为空」，见 IMPLEMENTATION_PLAN.md。
         */
        val composing: Boolean get() = preedit.isNotEmpty() && preeditCaret > 0
    }

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
 * 候选全量的一个窗口。
 *
 * [UiEvent.Candidates] 推的是**当页**候选（够画编码栏与页码），装不下一条可滚动的
 * 长列表。滚动候选栏与展开面板的数据从这里按需拉，见 [ImeSession.candidateWindow]。
 *
 * @param offset 窗口起点（原样回送，供校验拿到的是哪一段）
 * @param total 全量总数：`offset + items.size < total` 即表示还有得取
 * @param selected 当前高亮的**绝对**下标（空格上屏的目标）
 */
data class CandidatePage(
    val items: List<String>,
    val offset: Int,
    val total: Int,
    val selected: Int,
)

/**
 * 双拼布局条目（设置页「双拼布局」数据源）。
 *
 * 清单由核心扫描 `schemas/shuangpin` 下的 `.toml` 得出而不是 K 侧写死：用户可以往那个目录
 * 丢自己的布局文件，硬编码清单看不见它。
 */
data class LayoutEntry(
    val id: String,
    val name: String,
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

    /** 设置双拼布局（[id] 取自 [LayoutEntry.id]） */
    data class SetShuangpinLayout(val id: String) : EngineCmd
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

    /**
     * 暂停 / 恢复学习（无痕）：每次 onStartInput 按当前输入框显式设一次，见 [suspendsLearning]。
     * 暂停期间照常出候选，只是这次输入什么都不记。
     */
    fun setLearningSuspended(on: Boolean) = Unit

    /** @return true = 会话消费该键；false = 由调用方执行默认行为 */
    fun keyPressed(action: KeyAction, shifted: Boolean): Boolean

    /** 点选候选，[index] 是**全量里的绝对下标**（不受分页限制）。@return true = 已处理 */
    fun candidateSelected(index: Int): Boolean

    /**
     * 拉取候选全量的一个窗口。
     *
     * ⚠ **别在每次按键都拉一大段**：序列化与候选栏的文字测量都在主线程上，
     * 全量候选可达上千条。起手拉够铺满一两屏，用户滚到尾部再续取。
     * 核心侧另有硬上限（`candidate_pull::MAX_WINDOW`），传再大也不会真搬全量。
     */
    fun candidateWindow(offset: Int, limit: Int): CandidatePage =
        CandidatePage(emptyList(), offset, 0, 0)

    /** 复位会话状态（清空组合区与候选） */
    fun reset()

    /** 可选方案列表（快捷面板的方案选择器） */
    fun schemas(): List<SchemaEntry> = emptyList()

    /** 可选双拼布局（设置页「双拼布局」） */
    fun shuangpinLayouts(): List<LayoutEntry> = emptyList()

    /** 下达引擎命令；结果经 [UiEvent.Status] 异步回报 */
    fun runCommand(cmd: EngineCmd) = Unit

    /**
     * 当前是不是英文方案。英文方案下 Shift+字母**进引擎组合**（保留大小写，`Hell` 照样补全
     * 出 `Hello`）；中文方案下仍直接上屏——那里引擎的 Shift+字母是「临时英文」语义。
     */
    fun setLatinSchema(on: Boolean) = Unit
}
