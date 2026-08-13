package com.windinput.ime.core

import android.os.Handler
import android.os.Looper
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

        // wind-keys VK 码（SSOT 在主仓 wind-keys/keymap.rs）
        private const val VK_BACK = 0x08u
        private const val VK_RETURN = 0x0Du
        private const val VK_ESCAPE = 0x1Bu
        private const val VK_SPACE = 0x20u
        private const val VK_OEM_COMMA = 0xBCu
        private const val VK_OEM_PERIOD = 0xBEu

        /**
         * 创建会话；.so 加载或核心构造失败返回 null（调用方回退 FakeSession）。
         * @param dataDir 出厂数据目录（filesDir/data，含 schemas/）
         * @param rustRoot Rust 用户/缓存根（filesDir/rust）
         */
        fun create(dataDir: File, rustRoot: File): RustSession? {
            return try {
                rustRoot.mkdirs()
                // 泵线程在构造期即可能回调（构造期下发），经可空引用避免竞态
                var sessionRef: RustSession? = null
                val core = WindCore(
                    dataDir.absolutePath,
                    rustRoot.absolutePath,
                    "wubi86",
                    object : UiEventListener {
                        override fun onCandidates(
                            preedit: String,
                            items: List<String>,
                            selected: UInt,
                        ) {
                            sessionRef?.postCandidates(items)
                        }

                        override fun onHideCandidates() {
                            sessionRef?.postCandidates(emptyList())
                        }
                    },
                )
                RustSession(core).also { sessionRef = it }
            } catch (e: Throwable) {
                Log.e(TAG, "Rust core init failed, falling back to FakeSession", e)
                null
            }
        }
    }

    override var listener: ((UiEvent) -> Unit)? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    private fun postCandidates(items: List<String>) {
        mainHandler.post { listener?.invoke(UiEvent.Candidates(items)) }
    }

    override fun focusGained() = core.focusGained()

    override fun focusLost() = core.focusLost()

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

    /** 喂键并应用同步结果（commit/组合区）。@return 是否被引擎消费 */
    private fun dispatch(vk: UInt): Boolean {
        val result = core.keyDown(vk)
        if (result.commitText.isNotEmpty()) {
            listener?.invoke(UiEvent.CommitText(result.commitText))
        }
        if (result.compositionChanged) {
            listener?.invoke(UiEvent.Composition(result.composition))
        }
        return result.consumed
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
