package com.windinput.ime.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 候选条区域三态：空闲工具栏 / 候选列表(带展开按钮) / 剪贴板建议条
 * （fcitx5 KawaiiBar 状态机的精简版）。
 * 优先级：候选 > 剪贴板建议 > 工具栏；建议条 30s 超时自动消失。
 */
@SuppressLint("ViewConstructor")
class BarView(
    context: Context,
    candidateListener: CandidateBarView.Listener,
    private val listener: Listener,
) : FrameLayout(context) {

    interface Listener {
        fun onToolbarAction(action: ToolbarAction)
        fun onExpandCandidates()
        fun onPasteSuggestion(text: String)
    }

    enum class ToolbarAction { EMOJI, CLIPBOARD, TEXT_EDIT, HIDE_IME }

    private companion object {
        const val SUGGESTION_TIMEOUT_MS = 30_000L
    }

    private enum class State { TOOLBAR, CANDIDATES, CLIP_SUGGESTION }

    val candidateBar = CandidateBarView(context).apply { listener = candidateListener }
    private val candidateRow: View
    private val toolbar = buildToolbar()
    private val suggestionRow: LinearLayout
    private val suggestionText: TextView

    private var state = State.TOOLBAR
    private var hasCandidates = false

    private val dismissSuggestion = Runnable { hideClipSuggestion() }

    init {
        val density = resources.displayMetrics.density

        // 候选行 = 候选列表 + 右侧展开按钮
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(
            candidateBar,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f),
        )
        row.addView(
            TextView(context).apply {
                text = "∨"
                textSize = 16f
                gravity = Gravity.CENTER
                setTextColor(0xFF646464.toInt())
                setPadding((14 * density).toInt(), 0, (14 * density).toInt(), 0)
                setOnClickListener { listener.onExpandCandidates() }
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        candidateRow = row

        // 剪贴板建议条
        suggestionText = TextView(context).apply {
            textSize = 15f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(0xFF1E1E1E.toInt())
        }
        suggestionRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xFFFAFAFC.toInt())
            addView(
                TextView(context).apply {
                    text = "📋"
                    textSize = 16f
                    gravity = Gravity.CENTER
                    setPadding((12 * density).toInt(), 0, (8 * density).toInt(), 0)
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.MATCH_PARENT,
                ),
            )
            addView(
                suggestionText,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f),
            )
            addView(
                TextView(context).apply {
                    text = "✕"
                    textSize = 16f
                    gravity = Gravity.CENTER
                    setTextColor(0xFF969696.toInt())
                    setPadding((14 * density).toInt(), 0, (14 * density).toInt(), 0)
                    setOnClickListener { hideClipSuggestion() }
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.MATCH_PARENT,
                ),
            )
        }

        val barHeight = (44 * density).toInt()
        addView(toolbar, LayoutParams(LayoutParams.MATCH_PARENT, barHeight))
        addView(candidateRow, LayoutParams(LayoutParams.MATCH_PARENT, barHeight))
        addView(suggestionRow, LayoutParams(LayoutParams.MATCH_PARENT, barHeight))
        applyState(State.TOOLBAR)
    }

    fun setCandidates(items: List<String>) {
        candidateBar.setCandidates(items)
        hasCandidates = items.isNotEmpty()
        if (hasCandidates) {
            applyState(State.CANDIDATES)
        } else if (state == State.CANDIDATES) {
            applyState(State.TOOLBAR)
        }
    }

    /** 复制新内容后的建议条（候选优先级更高，仅空闲时展示） */
    fun showClipSuggestion(text: String) {
        if (hasCandidates) return
        suggestionText.text = text
        suggestionText.setOnClickListener {
            hideClipSuggestion()
            listener.onPasteSuggestion(text)
        }
        applyState(State.CLIP_SUGGESTION)
        removeCallbacks(dismissSuggestion)
        postDelayed(dismissSuggestion, SUGGESTION_TIMEOUT_MS)
    }

    private fun hideClipSuggestion() {
        removeCallbacks(dismissSuggestion)
        if (state == State.CLIP_SUGGESTION) {
            applyState(if (hasCandidates) State.CANDIDATES else State.TOOLBAR)
        }
    }

    private fun applyState(newState: State) {
        state = newState
        toolbar.visibility = if (newState == State.TOOLBAR) View.VISIBLE else View.GONE
        candidateRow.visibility = if (newState == State.CANDIDATES) View.VISIBLE else View.GONE
        suggestionRow.visibility =
            if (newState == State.CLIP_SUGGESTION) View.VISIBLE else View.GONE
    }

    private fun buildToolbar(): View {
        val density = resources.displayMetrics.density
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xFFFAFAFC.toInt())
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, (44 * density).toInt())
        }

        fun button(label: String, action: ToolbarAction, weight: Float = 1f) {
            row.addView(
                TextView(context).apply {
                    text = label
                    textSize = 18f
                    gravity = Gravity.CENTER
                    setTextColor(0xFF646464.toInt())
                    setOnClickListener { listener.onToolbarAction(action) }
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, weight),
            )
        }

        // 占位图标（文本形态）；正式版换主题化矢量图标
        button("😀", ToolbarAction.EMOJI)
        button("📋", ToolbarAction.CLIPBOARD)
        button("✂", ToolbarAction.TEXT_EDIT)
        button("▾", ToolbarAction.HIDE_IME)
        return row
    }
}
