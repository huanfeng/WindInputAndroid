package com.windinput.ime.core

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.windinput.ime.keyboard.KeyAction
import java.io.File
import uniffi.wind_android.UiEventListener
import uniffi.wind_android.WindCore

/**
 * Rust 核心会话：经 UniFFI 绑定驱动 headless Coordinator（walking skeleton）。
 *
 * 通道（对应主仓 wind-android crate 文档）：
 * - 正向：软键 → VK 码 → keyDown，同步拿 上屏/组合区 结果
 * - 反向：UiCommand 泵线程回调（候选/隐藏），post 回主线程再发 UiEvent
 * - 候选点选：骨架期合成数字键（页内 1-9），走正向通道拿上屏文本
 */
class RustSession(
    private val core: WindCore,
) : ImeSession {

    companion object {
        private const val TAG = "RustSession"

        /** 一帧的微秒数（60Hz）；JNI 同步调用超过它就会掉帧 */
        private const val FRAME_BUDGET_US = 16_000L

        // wind-keys VK 码（SSOT 在主仓 wind-keys/keymap.rs）
        private const val VK_BACK = 0x08u
        private const val VK_RETURN = 0x0Du
        private const val VK_ESCAPE = 0x1Bu
        private const val VK_SPACE = 0x20u
        private const val VK_OEM_COMMA = 0xBCu
        private const val VK_OEM_PERIOD = 0xBEu

        /** 进程内唯一核心（见 [getOrCreate]） */
        @Volatile
        private var instance: RustSession? = null

        /**
         * 取进程内**唯一**的会话，没有则构造。
         *
         * # 为什么必须是单例
         *
         * `InputMethodService.onCreate` 不是只调一次：切换输入法、系统配置变化都会
         * 重建服务。每次新建一个 `WindCore` 的后果是两份 77132 条词库常驻、两个
         * 预热线程、两个 prepare 线程，**争抢同一个 redb 文件锁与协调器 state 锁**
         * ——实测每次按键被拖到 140~480ms，触摸事件随之堆积、连点被误判成滑动。
         *
         * 核心是无状态可复用的（焦点/组合都随 focusGained 重置），复用比重建更正确。
         */
        @Synchronized
        fun getOrCreate(dataDir: File, rustRoot: File): RustSession? {
            instance?.let {
                Log.i(TAG, "复用已有核心（IME 服务重建）")
                return it
            }
            return create(dataDir, rustRoot)?.also { instance = it }
        }

        /**
         * 创建会话；.so 加载或核心构造失败返回 null（调用方回退 FakeSession）。
         * @param dataDir 出厂数据目录（filesDir/data，含 schemas/）
         * @param rustRoot Rust 用户/缓存根（filesDir/rust）
         */
        private fun create(dataDir: File, rustRoot: File): RustSession? {
            return try {
                rustRoot.mkdirs()
                uniffi.wind_android.initLogging("info,wind_coordinator=debug")
                // 泵线程在构造期即可能回调（构造期下发），经可空引用避免竞态
                var sessionRef: RustSession? = null
                val core = WindCore(
                    dataDir.absolutePath,
                    rustRoot.absolutePath,
                    "wubi86",
                    object : UiEventListener {
                        override fun onCandidates(update: uniffi.wind_android.CandidateUpdate) {
                            sessionRef?.post(
                                UiEvent.Candidates(
                                    items = update.items,
                                    preedit = update.preedit,
                                    modeLabel = update.modeLabel,
                                    selected = update.selected.toInt(),
                                    page = update.page.toInt(),
                                    totalPages = update.totalPages.toInt(),
                                ),
                            )
                        }

                        override fun onHideCandidates() {
                            sessionRef?.post(UiEvent.Candidates(emptyList()))
                        }

                        override fun onStatus(status: uniffi.wind_android.InputStatus) {
                            sessionRef?.post(
                                UiEvent.Status(
                                    chineseMode = status.chineseMode,
                                    iconLabel = status.iconLabel,
                                    fullWidth = status.fullWidth,
                                    chinesePunct = status.chinesePunct,
                                    s2tEnabled = status.s2tEnabled,
                                    s2tShown = status.s2tShown,
                                ),
                            )
                        }

                        override fun onToast(text: String) {
                            sessionRef?.post(UiEvent.Toast(text))
                        }
                    },
                )
                Log.i(TAG, "Rust core ready: data=${dataDir.absolutePath}")
                RustSession(core).also { sessionRef = it }
            } catch (e: Throwable) {
                Log.e(TAG, "Rust core init failed, falling back to FakeSession", e)
                null
            }
        }
    }

    override var listener: ((UiEvent) -> Unit)? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 泵线程在**非主线程**回调，一律 post 回主线程再交给 UI */
    private fun post(event: UiEvent) {
        mainHandler.post { listener?.invoke(event) }
    }

    override fun focusGained() = core.focusGained()

    override fun focusLost() = core.focusLost()

    override fun schemas(): List<SchemaEntry> =
        core.schemas().mapIndexed { i, s ->
            SchemaEntry(
                index = i,
                id = s.id,
                name = s.name,
                iconLabel = s.iconLabel,
                active = s.active,
            )
        }

    override fun shuangpinLayouts(): List<LayoutEntry> =
        core.shuangpinLayouts().map { LayoutEntry(id = it.id, name = it.name, active = it.active) }

    override fun runCommand(cmd: EngineCmd) {
        val native = when (cmd) {
            EngineCmd.English -> uniffi.wind_android.EngineCommand.English
            is EngineCmd.SelectSchema ->
                uniffi.wind_android.EngineCommand.SelectSchema(cmd.index.toUInt())
            EngineCmd.TogglePunct -> uniffi.wind_android.EngineCommand.TogglePunct
            EngineCmd.ToggleWidth -> uniffi.wind_android.EngineCommand.ToggleWidth
            EngineCmd.ToggleS2t -> uniffi.wind_android.EngineCommand.ToggleS2t
            EngineCmd.ReloadConfig -> uniffi.wind_android.EngineCommand.ReloadConfig
            is EngineCmd.SetShuangpinLayout ->
                uniffi.wind_android.EngineCommand.SetShuangpinLayout(cmd.id)
        }
        core.runCommand(native)
    }

    override fun keyPressed(action: KeyAction, shifted: Boolean): Boolean {
        val vk = toVk(action, shifted) ?: return false
        return dispatch(vk)
    }

    override fun candidateSelected(index: Int): Boolean {
        // 骨架期：页内 1-9 合成数字键（点选上屏与键盘选字同一条主输入路）
        if (index !in 0..8) return false
        return dispatch(0x31u + index.toUInt())
    }

    override fun reset() {
        dispatch(VK_ESCAPE)
    }

    /** 喂键并派发编辑指令流。@return 是否被引擎消费 */
    private fun dispatch(vk: UInt): Boolean {
        // JNI 往返耗时单独计：它在**主线程**上同步阻塞，是输入延迟里唯一不可
        // 让位给渲染的一段。超过一帧（16ms）就必须改成异步，故按帧标注。
        val t0 = SystemClock.elapsedRealtimeNanos()
        val result = core.keyDown(vk, 0u)
        val costUs = (SystemClock.elapsedRealtimeNanos() - t0) / 1000
        if (costUs > FRAME_BUDGET_US) {
            Log.w(TAG, "keyDown 阻塞主线程 ${costUs}µs（超一帧）vk=0x${vk.toString(16)}")
        }
        Log.d(
            TAG,
            "vk=0x${vk.toString(16)} ${costUs}µs -> consumed=${result.consumed} " +
                "ops=" + result.ops.joinToString { "k${it.kind}:'${it.text}'" },
        )
        if (result.ops.isNotEmpty()) {
            listener?.invoke(UiEvent.Edits(result.ops.map(::toEditOp)))
        }
        return result.consumed
    }

    /**
     * FFI 的扁平 Record → Kotlin 密封类。
     *
     * `kind` 判别位的取值由核心 `wind-android` 的 `EditOp` 定义，两边必须同步；
     * 未知取值**抛异常而不是忽略**——静默忽略正是「新动作上了核心、移动端悄无声息
     * 地没有」的老路。
     */
    private fun toEditOp(op: uniffi.wind_android.EditOp): EditOp = when (op.kind.toInt()) {
        0 -> EditOp.Commit(op.text)
        1 -> EditOp.SetComposition(op.text, op.count.toInt())
        2 -> EditOp.DeleteBackward(op.count.toInt())
        3 -> EditOp.ReplaceBackward(op.count.toInt(), op.text)
        4 -> EditOp.MoveCursor(op.delta)
        else -> error("未知 EditOp.kind=${op.kind}，核心与 K 侧的判别位定义已漂移")
    }

    /** 软键 → wind-keys VK 码；null = 不进引擎（调用方走默认行为） */
    private fun toVk(action: KeyAction, shifted: Boolean): UInt? = when (action) {
        is KeyAction.Text -> {
            val c = action.char
            when {
                // Shift 态大写字母骨架期绕过引擎直输
                c in 'a'..'z' && !shifted -> 0x41u + (c - 'a').toUInt()
                c in '0'..'9' -> 0x30u + (c - '0').toUInt()
                c == '，' || c == ',' -> VK_OEM_COMMA
                c == '。' || c == '.' -> VK_OEM_PERIOD
                else -> null
            }
        }
        KeyAction.Space -> VK_SPACE
        KeyAction.Backspace -> VK_BACK
        KeyAction.Enter -> VK_RETURN
        else -> null
    }
}
