package com.windinput.ime.keyboard

import android.content.Context
import android.content.res.Configuration

/**
 * 键盘域调色板：所有自绘视图与面板的颜色**只从这里取**。
 *
 * 这是主题系统（docs/architecture.md §3.4「主题键盘域节点 + 共享调色板」）的落点：
 * 眼下只有内置的浅/深两套，将来 wind-theme 求值出来的调色板也是填进这同一组槽位，
 * 视图层一行不用改。槽位按**语义**命名而不是按颜色——`accentSoft` 在深色里并不"浅"。
 *
 * ⚠ 视图在构造时取色、不监听变化。这是有意的：IME 的输入视图在系统配置变化
 * （深浅色切换、旋转）时**整个重建**（`onCreateInputView` 重跑），所以每次构造
 * 取到的就是当时该用的那套；给每个 View 挂监听反而多一条不会被走到的路。
 */
class Palette(
    /** 键盘底色（键与键之间的缝） */
    val background: Int,
    /** 条区 / 面板顶栏 / 列表行的面 */
    val surface: Int,
    /** 普通键面 */
    val key: Int,
    /** 功能键面（Shift / 退格 / 切换等） */
    val keySpecial: Int,
    /** 按下高亮 */
    val keyPressed: Int,
    /** 主文字 */
    val text: Int,
    /** 次要文字：副字符提示、页码、空态说明 */
    val hint: Int,
    /** 强调色：模式标签底、选中的 chip、顶栏动作文字 */
    val accent: Int,
    /** 强调色上的文字 */
    val onAccent: Int,
    /** 强调色的淡底：选中项背景、候选按下 */
    val accentSoft: Int,
    /** 强调色的文字形态：气泡选中项、编辑面板的选择模式 */
    val accentText: Int,
    /** 编码栏里的编码文字 */
    val preedit: Int,
    /** 候选分隔线 */
    val separator: Int,
    /** 工具栏图标 */
    val icon: Int,
    /** 气泡描边 */
    val border: Int,
) {
    companion object {
        val light = Palette(
            background = 0xFFF0F2F5.toInt(),
            surface = 0xFFFAFAFC.toInt(),
            key = 0xFFFFFFFF.toInt(),
            keySpecial = 0xFFD9DDE3.toInt(),
            keyPressed = 0xFFBBD0F8.toInt(),
            text = 0xFF1E1E1E.toInt(),
            hint = 0xFF969696.toInt(),
            accent = 0xFF4A78C8.toInt(),
            onAccent = 0xFFFFFFFF.toInt(),
            accentSoft = 0xFFE6F0FF.toInt(),
            accentText = 0xFF3B82F6.toInt(),
            preedit = 0xFF32568C.toInt(),
            separator = 0xFFE8EAF0.toInt(),
            icon = 0xFF4B5563.toInt(),
            border = 0xFFC8C8C8.toInt(),
        )

        /**
         * 深色：不是把浅色反相。键面要比底色**亮**一级才有"键"的立体感（浅色里是
         * 白键压灰底，深色里同样是浅灰键压深底），强调色提亮一档保证在深底上可读。
         */
        val dark = Palette(
            background = 0xFF17181C.toInt(),
            surface = 0xFF232428.toInt(),
            key = 0xFF35373D.toInt(),
            keySpecial = 0xFF26282E.toInt(),
            keyPressed = 0xFF4F6494.toInt(),
            text = 0xFFF2F2F2.toInt(),
            hint = 0xFF9A9DA5.toInt(),
            accent = 0xFF6B8FD8.toInt(),
            onAccent = 0xFFFFFFFF.toInt(),
            accentSoft = 0xFF2C3A58.toInt(),
            accentText = 0xFF8AB4F8.toInt(),
            preedit = 0xFFA9C4F5.toInt(),
            separator = 0xFF3A3C42.toInt(),
            icon = 0xFFC0C4CC.toInt(),
            border = 0xFF4A4C52.toInt(),
        )

        /**
         * 当前生效的调色板。由 Service 在 `onCreateInputView` 里按系统深浅色设定，
         * 视图层只读。与 [KeyboardDimens] 同一种"Service 注入、视图取用"的模式。
         */
        @Volatile
        var current: Palette = light
            private set

        /**
         * 选一套设为当前。[mode] 来自设置项 ui.android.theme：`light` / `dark` / 其它 = 跟随系统。
         */
        fun apply(context: Context, mode: String): Palette {
            current = resolve(context, mode)
            return current
        }

        /**
         * 按设置与系统深浅色算出该用哪套，不改 [current]（Service 用它核对当前视图配色对不对）。
         *
         * 「跟随系统」读**系统全局**配置（`Resources.getSystem()`），不读 Service 自己的：
         * 刚装好、进程刚起来时 IME 服务拿到的配置可能是旧的——真机上出现过系统浅色、
         * 键盘却是深色，切一次输入法（服务重建）才对。
         */
        fun resolve(context: Context, mode: String): Palette = when (mode) {
            "light" -> light
            "dark" -> dark
            else -> {
                val uiMode = android.content.res.Resources.getSystem().configuration.uiMode
                    .takeIf { it and Configuration.UI_MODE_NIGHT_MASK != Configuration.UI_MODE_NIGHT_UNDEFINED }
                    ?: context.resources.configuration.uiMode
                val night = uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
                if (night) dark else light
            }
        }
    }
}
