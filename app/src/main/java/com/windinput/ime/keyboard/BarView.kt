package com.windinput.ime.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 候选条区域：空闲工具栏 ↔ 候选列表 双态切换
 * （fcitx5 KawaiiBar 五态状态机的最小版，后续扩展剪贴板建议/数字行等态）。
 */
@SuppressLint("ViewConstructor")
class BarView(
    context: Context,
    candidateListener: CandidateBarView.Listener,
    private val toolbarListener: ToolbarListener,
) : FrameLayout(context) {

    interface ToolbarListener {
        fun onToolbarAction(action: ToolbarAction)
    }

    enum class ToolbarAction { EMOJI, CLIPBOARD, TEXT_EDIT, HIDE_IME }

    val candidateBar = CandidateBarView(context).apply { listener = candidateListener }
    private val toolbar = buildToolbar()

    init {
        addView(toolbar)
        addView(candidateBar)
        showToolbar()
    }

    fun setCandidates(items: List<String>) {
        candidateBar.setCandidates(items)
        if (items.isEmpty()) showToolbar() else showCandidates()
    }

    private fun showToolbar() {
        candidateBar.visibility = View.GONE
        toolbar.visibility = View.VISIBLE
    }

    private fun showCandidates() {
        toolbar.visibility = View.GONE
        candidateBar.visibility = View.VISIBLE
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
                    setOnClickListener { toolbarListener.onToolbarAction(action) }
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
