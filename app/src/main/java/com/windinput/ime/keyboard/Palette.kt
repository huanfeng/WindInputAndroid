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
    /** 列表行 / 面板内容区的面（条区与面板顶栏用 [background]，与键盘同色） */
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
        /**
         * 蓝色只出现在三个角色里（同一色相）：实心强调色 [accent]、半透明淡底 [accentSoft]、
         * 强调字 [accentText]；按下态、编码字、分隔线等一律中性灰——此前混着六种蓝，
         * 看着不统一。参考电脑端「清风·蓝」，手机端实心色略深（白字对比 4.57）、淡底用透明度叠。
         * 条区与键盘区共用 [background]：两块异色会在交界处拉出一道硬边。
         * 底色取 E0E3E9（微信 D6D8DE、豆包 E0E2E6、小米 E5E6E8 之间）：白键压在上面要有清楚的对比，EDEFF3 太亮、键面发飘。
         */
        val light = Palette(
            background = 0xFFE0E3E9.toInt(),
            surface = 0xFFF7F8FA.toInt(),
            key = 0xFFFFFFFF.toInt(),
            keySpecial = 0xFFC9CFD9.toInt(),
            keyPressed = 0xFFBAC2CF.toInt(),
            text = 0xFF1A1D24.toInt(),
            hint = 0xFF8A91A0.toInt(),
            accent = 0xFF2F6FEB.toInt(),
            onAccent = 0xFFFFFFFF.toInt(),
            accentSoft = 0x1A2F6FEB,
            accentText = 0xFF2563EB.toInt(),
            preedit = 0xFF5A6072.toInt(),
            separator = 0xFFCBD0D9.toInt(),
            icon = 0xFF4A5163.toInt(),
            border = 0xFFE2E5EB.toInt(),
        )

        /**
         * 深色：不是把浅色反相。键面要比底色**亮**一级才有"键"的立体感（浅色里是
         * 白键压灰底，深色里同样是浅灰键压深底），强调色提亮一档保证在深底上可读。
         */
        val dark = Palette(
            background = 0xFF12151C.toInt(),
            surface = 0xFF1B1F2A.toInt(),
            key = 0xFF2E3442.toInt(),
            keySpecial = 0xFF232833.toInt(),
            keyPressed = 0xFF444C5E.toInt(),
            text = 0xFFF2F3F7.toInt(),
            hint = 0xFF8F97A8.toInt(),
            accent = 0xFF3A74E2.toInt(),
            onAccent = 0xFFFFFFFF.toInt(),
            accentSoft = 0x3360A5FA,
            accentText = 0xFF7AB0FF.toInt(),
            preedit = 0xFF9AA3B4.toInt(),
            separator = 0xFF2A3040.toInt(),
            icon = 0xFFB4BBC9.toInt(),
            border = 0xFF3A4152.toInt(),
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
