package com.windinput.ime.skin

import com.windinput.ime.keyboard.Palette
import kotlin.math.pow

/**
 * 皮肤配色的可读性检查（纯函数）。只给**警告**，不阻塞加载：用户可能就是喜欢低对比。
 * 比值按 WCAG 相对亮度；半透明前景先按背景合成。
 */
object SkinContrast {
    /** 文字与底色低于它就警告 */
    const val MIN_RATIO = 3.0

    fun ratio(fg: Int, bg: Int): Double {
        val solidBg = bg or (0xFF shl 24)
        val l1 = luminance(over(fg, solidBg))
        val l2 = luminance(solidBg)
        val hi = maxOf(l1, l2)
        val lo = minOf(l1, l2)
        return (hi + 0.05) / (lo + 0.05)
    }

    /** 前景（含透明度）压在不透明背景上的合成色 */
    private fun over(fg: Int, bg: Int): Int {
        val a = (fg ushr 24) / 255.0
        fun mix(shift: Int) = (((fg shr shift) and 0xFF) * a + ((bg shr shift) and 0xFF) * (1 - a)).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }

    private fun luminance(c: Int): Double {
        fun ch(shift: Int): Double {
            val v = ((c shr shift) and 0xFF) / 255.0
            return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * ch(16) + 0.7152 * ch(8) + 0.0722 * ch(0)
    }

    /** 关键「字 / 底」配对：键面字、功能键字、强调键字、候选字、气泡字 */
    fun warnings(p: Palette, label: String): List<String> = buildList {
        fun check(what: String, fg: Int, bg: Int) {
            val r = ratio(fg, bg)
            if (r < MIN_RATIO) add("$label：$what 对比度 ${"%.1f".format(r)}:1，低于 $MIN_RATIO:1，可能难以辨认")
        }
        check("键面文字", p.keyText, p.key)
        check("功能键文字", p.keySpecialText, p.keySpecial)
        check("强调键文字", p.onAccent, p.keyAccent)
        check("候选文字", p.candidateText, p.barBackground)
        check("气泡文字", p.popupText, p.popupBackground)
    }
}
