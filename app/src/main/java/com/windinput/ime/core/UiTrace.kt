package com.windinput.ime.core

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.view.WindowInsets
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * 界面诊断轨迹：键盘布局相关的关键事件（窗口 insets 原始值、避让取值与最终高度等）记在进程内的
 * 小环形缓冲里，「分享诊断信息」带上。
 *
 * 为什么不靠 logcat：logcat 尾部被系统/厂商日志刷屏，且 INFO 以下默认不带；而「键盘下方空白」
 * 这类问题的关键就是**各类 insets 的原始值**，没有它只能猜。这里只记数值与开关，不含任何用户输入。
 */
object UiTrace {

    private const val MAX = 60
    private val lines = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun add(msg: String) {
        if (lines.size >= MAX) lines.removeFirst()
        lines.addLast("${fmt.format(Date())} $msg")
    }

    @Synchronized
    fun snapshot(): List<String> = lines.toList()

    /** 各类 insets 的底部/左右原始值，一行写完（API 30+）；更早的系统只有一个 systemWindowInsetBottom */
    fun describeInsets(insets: WindowInsets): String {
        if (Build.VERSION.SDK_INT < 30) {
            @Suppress("DEPRECATION")
            return "systemWindowInsetBottom=${insets.systemWindowInsetBottom}"
        }
        fun b(name: String, type: Int) = insets.getInsets(type).let { "$name=${it.bottom}" + if (it.left != 0 || it.right != 0) "(L${it.left}R${it.right})" else "" }
        return listOf(
            b("nav", WindowInsets.Type.navigationBars()),
            b("gesture", WindowInsets.Type.systemGestures()),
            b("mandGesture", WindowInsets.Type.mandatorySystemGestures()),
            b("tappable", WindowInsets.Type.tappableElement()),
            b("statusBar", WindowInsets.Type.statusBars()),
            b("ime", WindowInsets.Type.ime()),
            b("cutout", WindowInsets.Type.displayCutout()),
        ).joinToString(" ")
    }

    /** 设备/系统侧的静态信息：导航模式、屏幕与窗口尺寸、密度。读不到的写 ?，不抛 */
    fun deviceInfo(context: Context): List<String> {
        val dm = context.resources.displayMetrics
        val cfg = context.resources.configuration
        fun secure(k: String) = runCatching { Settings.Secure.getInt(context.contentResolver, k).toString() }.getOrDefault("?")
        fun global(k: String) = runCatching { Settings.Global.getInt(context.contentResolver, k).toString() }.getOrDefault("?")
        val out = mutableListOf(
            "屏幕 ${dm.widthPixels}x${dm.heightPixels}px 密度 ${dm.density}（${dm.densityDpi}dpi） 字体缩放 ${cfg.fontScale}",
            "导航模式 navigation_mode=${secure("navigation_mode")}（0 三键 / 1 两键 / 2 手势） " +
                "小米 force_fsg_nav_bar=${global("force_fsg_nav_bar")} hide_gesture_line=${global("hide_gesture_line")}",
        )
        if (Build.VERSION.SDK_INT >= 30) {
            runCatching {
                val wm = context.getSystemService(android.view.WindowManager::class.java)
                val m = wm.currentWindowMetrics
                out += "窗口 ${m.bounds.width()}x${m.bounds.height()}px  " + describeInsets(m.windowInsets)
            }
        }
        return out
    }
}
