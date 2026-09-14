package com.windinput.ime.keyboard

import android.content.Context
import android.os.Build
import android.view.View
import android.view.WindowInsets
import android.widget.LinearLayout

/**
 * 输入法根视图：候选条 + 面板区的容器，负责**手势导航条/导航栏避让**。
 *
 * Android 10+ 的手势条与三键导航栏会占据 IME 窗口底部，键盘最后一排会被挤掉。
 * Android 15(API 35) 起 edge-to-edge 强制生效，这条更不能省。
 *
 * ⚠ 用**底部占位 View** 而不是 `setPadding` 避让：IME 窗口高度取自根视图的测量
 * 结果，padding 只会压缩内容区（最后一排被裁），占位 View 才会把 inset 高度
 * 计入测量、让窗口整体长高。
 */
class InputRootView(context: Context) : LinearLayout(context) {

    private val bottomSpacer = View(context)
    private var insetBottom = -1

    init {
        orientation = VERTICAL
        // 与键盘同色，否则底部露出异色带
        setBackgroundColor(Palette.current.background)
        fitsSystemWindows = false
        addView(bottomSpacer, LayoutParams(LayoutParams.MATCH_PARENT, 0))
    }

    /** 占位 View 必须始终在最后一个（子 View 由 Service 依次 addView） */
    override fun addView(child: View, params: android.view.ViewGroup.LayoutParams) {
        if (child === bottomSpacer) {
            super.addView(child, params)
        } else {
            super.addView(child, childCount - 1, params)
        }
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        val bottom = if (Build.VERSION.SDK_INT >= 30) {
            val nav = insets.getInsets(WindowInsets.Type.navigationBars()).bottom
            val gesture = insets.getInsets(WindowInsets.Type.systemGestures()).bottom
            maxOf(nav, gesture)
        } else {
            @Suppress("DEPRECATION")
            insets.systemWindowInsetBottom
        }
        if (bottom != insetBottom) {
            insetBottom = bottom
            bottomSpacer.layoutParams = bottomSpacer.layoutParams.apply { height = bottom }
            requestLayout()
        }
        return super.onApplyWindowInsets(insets)
    }
}
