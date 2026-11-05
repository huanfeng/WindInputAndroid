package com.windinput.ime.core

import com.windinput.ime.keyboard.KeyAction

/**
 * 占位会话实现：在 Rust 核心（wind-android）接入前，
 * 用最小的「字母进组合区 → 伪候选 → 选词上屏」逻辑打通 UI 链路。
 *
 * 伪候选无任何输入法意义，仅供验证候选条渲染与点选路径；
 * Rust 核心接入后整个类删除，由 UniFFI 绑定的真实现替换。
 */
class FakeSession : ImeSession {

    override var listener: ((UiEvent) -> Unit)? = null

    private val buffer = StringBuilder()

    override fun focusGained() = Unit

    override fun focusLost() = reset()

    override fun keyPressed(action: KeyAction, shifted: Boolean): Boolean {
        when (action) {
            is KeyAction.Text -> {
                if (!action.char.isLetter()) {
                    // 标点：有组合区时先上屏首选再透传标点
                    if (buffer.isNotEmpty()) {
                        commit(buffer.toString())
                    }
                    return false
                }
                buffer.append(if (shifted) action.char.uppercaseChar() else action.char)
                publish()
                return true
            }
            KeyAction.Backspace -> {
                if (buffer.isEmpty()) return false
                buffer.deleteCharAt(buffer.length - 1)
                publish()
                return true
            }
            KeyAction.Space -> {
                if (buffer.isEmpty()) return false
                commit(candidates().first())
                return true
            }
            KeyAction.Enter -> {
                if (buffer.isEmpty()) return false
                // 回车上屏原始编码串（与桌面临时英文语义一致）
                commit(buffer.toString())
                return true
            }
            else -> return false
        }
    }

    override fun candidateSelected(index: Int): Boolean {
        val items = candidates()
        if (buffer.isEmpty() || index !in items.indices) return false
        commit(items[index])
        return true
    }

    override fun reset() {
        if (buffer.isEmpty()) return
        buffer.setLength(0)
        publish()
    }

    private fun candidates(): List<String> {
        if (buffer.isEmpty()) return emptyList()
        val raw = buffer.toString()
        return listOf(raw, raw.uppercase(), raw.replaceFirstChar { it.uppercase() }).distinct()
    }

    private fun publish() {
        listener?.invoke(
            UiEvent.Candidates(
                composing = buffer.isNotEmpty(),
                hasCandidates = buffer.isNotEmpty(),
                preedit = buffer.toString(),
                modeLabel = "占",
            ),
        )
    }

    private fun commit(text: String) {
        buffer.setLength(0)
        listener?.invoke(UiEvent.Edits(listOf(EditOp.Commit(text))))
        publish()
    }
}
