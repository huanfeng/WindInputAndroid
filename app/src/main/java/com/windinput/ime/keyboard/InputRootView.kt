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

    /**
     * 当前的避让高度。**不能从 0 起步**：系统的 insets 要等窗口第一次布局之后才派发，
     * 从 0 起步的话键盘刚弹出那一下按无避让的高度测量，最后一排被手势条盖住，
     * insets 到了才长高（肉眼可见地「先被遮住、再恢复」）。所以先用上次收到的真值，
     * 没有就按当前窗口的 WindowMetrics 估一个，insets 到了再校正（通常不变，不会跳）。
     * API 30+ 按 WindowMetrics 估：它跟着当前横竖屏走。
     *
     * 估值在部分系统上不可靠（真机反馈：首次弹出仍被小横条盖住，之后才恢复）。故再按
     * **横竖屏分别**记住上次收到的真值、跨进程持久化（进程被杀后的「首次」也有据可依），
     * 首帧取两者较大的——宁可首帧多垫几个像素，也不能让最后一排被盖住。
     */
    private val portrait = context.resources.configuration.orientation !=
        android.content.res.Configuration.ORIENTATION_LANDSCAPE
    private val estimated = initialInset(context)
    private var insetBottom = maxOf(estimated, savedInset(context, portrait))

    /**
     * 用户设的额外留空（px）：底部加在避让占位上（窗口跟着长高，最后一排不被裁），左右用内边距
     * （只收窄内容，不影响窗口高度）。设置项 ui.android.keyboard_bottom_gap / keyboard_side_gap。
     */
    fun setGaps(bottomPx: Int, sidePx: Int) {
        extraBottom = bottomPx
        setPadding(sidePx, 0, sidePx, 0)
        applySpacer()
    }

    private var extraBottom = 0

    private fun applySpacer() {
        val h = insetBottom.coerceAtLeast(0) + extraBottom
        if (bottomSpacer.layoutParams.height != h) {
            bottomSpacer.layoutParams = bottomSpacer.layoutParams.apply { height = h }
            requestLayout()
        }
    }

    init {
        orientation = VERTICAL
        // 与键盘同色，否则底部露出异色带
        setBackgroundColor(Palette.current.background)
        fitsSystemWindows = false
        addView(bottomSpacer, LayoutParams(LayoutParams.MATCH_PARENT, insetBottom))
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
        lastInsetBottom = bottom
        // 横屏时挖孔与三键导航栏在左右两侧：窗口铺到底（服务里开了 cutout ALWAYS），
        // 键往里收，背景照样铺满——否则侧边露出一条底下的应用
        if (Build.VERSION.SDK_INT >= 30) {
            val side = insets.getInsets(WindowInsets.Type.displayCutout() or WindowInsets.Type.navigationBars())
            if (paddingLeft != side.left || paddingRight != side.right) setPadding(side.left, 0, side.right, 0)
        }
        if (bottom != savedInset(context, portrait)) saveInset(context, portrait, bottom)
        if (bottom != insetBottom) {
            android.util.Log.i("InputRootView", "导航避让首帧 $insetBottom（估值 $estimated）→ 实际 $bottom")
            insetBottom = bottom
            applySpacer()
        }
        return super.onApplyWindowInsets(insets)
    }

    companion object {
        /** 进程内记住上次的真值：API 26–29 没有 WindowMetrics，重建输入视图时用它 */
        private var lastInsetBottom = -1

        private fun prefs(context: Context) =
            context.getSharedPreferences("ime_insets", Context.MODE_PRIVATE)

        private fun savedInset(context: Context, portrait: Boolean): Int =
            prefs(context).getInt(if (portrait) "bottom_portrait" else "bottom_landscape", 0)

        private fun saveInset(context: Context, portrait: Boolean, value: Int) {
            prefs(context).edit().putInt(if (portrait) "bottom_portrait" else "bottom_landscape", value).apply()
        }

        private fun initialInset(context: Context): Int =
            if (Build.VERSION.SDK_INT >= 30) {
                estimateBottomInset(context)
            } else {
                lastInsetBottom.takeIf { it >= 0 } ?: estimateBottomInset(context)
            }

        private fun estimateBottomInset(context: Context): Int {
            if (Build.VERSION.SDK_INT >= 30) {
                // 估不出来就退回 0（旧行为）：只是首帧不准，insets 到了照样校正，不值得因此崩
                return runCatching {
                    val wm = context.getSystemService(android.view.WindowManager::class.java)
                    val insets = wm.currentWindowMetrics.windowInsets
                    maxOf(
                        insets.getInsets(WindowInsets.Type.navigationBars()).bottom,
                        insets.getInsets(WindowInsets.Type.systemGestures()).bottom,
                    )
                }.getOrDefault(0)
            }
            // 26–29：没有 WindowMetrics，取系统资源里的导航栏高度
            val id = context.resources.getIdentifier("navigation_bar_height", "dimen", "android")
            return if (id != 0) context.resources.getDimensionPixelSize(id) else 0
        }
    }
}
