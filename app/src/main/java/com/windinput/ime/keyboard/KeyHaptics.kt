package com.windinput.ime.keyboard

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * 按键振动，强度可调（设置项 ui.android.haptic / haptic_strength）。
 *
 * 系统的 `performHapticFeedback(KEYBOARD_TAP)` 强度由厂商定死，不少机型偏重，调不了；
 * 这里自己调振动器，按机器能力从细到粗三档：
 * 1. 支持振动原语（API 30+）：`PRIMITIVE_CLICK` 按强度缩放——与系统按键振动同一种手感，只是更轻。
 * 2. 能调幅：短脉冲，幅度按强度换算。
 * 3. 都不能：只能缩短时长来变轻。
 */
object KeyHaptics {

    var enabled = true

    /** 强度 5–100（百分比） */
    var strength = DEFAULT_STRENGTH
        set(value) {
            field = value.coerceIn(1, 100)
        }

    const val DEFAULT_STRENGTH = 30

    private var vibrator: Vibrator? = null

    fun init(context: Context) {
        if (vibrator != null) return
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    /** 按键按下的一下。[view] 只在拿不到振动器时用来走系统反馈兜底 */
    fun tap(view: View) {
        if (!enabled) return
        val v = vibrator?.takeIf { it.hasVibrator() }
        if (v == null) {
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            return
        }
        v.vibrate(effect(v))
    }

    private fun effect(v: Vibrator): VibrationEffect {
        val scale = strength / 100f
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            v.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_CLICK)
        ) {
            return VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, scale)
                .compose()
        }
        if (v.hasAmplitudeControl()) {
            return VibrationEffect.createOneShot(PULSE_MS, amplitudeFor(strength))
        }
        return VibrationEffect.createOneShot(durationFor(strength), VibrationEffect.DEFAULT_AMPLITUDE)
    }

    private const val PULSE_MS = 12L

    /** 强度 → 幅度 1..255 */
    fun amplitudeFor(strength: Int): Int = (strength.coerceIn(1, 100) * 255 / 100).coerceIn(1, 255)

    /** 不能调幅时强度 → 时长：4ms（几乎只是一下颤）到 20ms */
    fun durationFor(strength: Int): Long = 4L + strength.coerceIn(1, 100) * 16L / 100
}
