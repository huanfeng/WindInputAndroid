package com.windinput.ime.keyboard

import android.content.Context
import android.content.res.Configuration
import com.windinput.ime.skin.SkinRuntime

/**
 * 键盘域调色板：所有自绘视图与面板的颜色**只从这里取**。
 *
 * 这是主题系统（docs/architecture.md §3.4「主题键盘域节点 + 共享调色板」）的落点：
 * 数据来自 [SkinRuntime]（皮肤的不可变运行时形态，见 docs/mobile-skin-design.md）：本类是它的
 * 颜色视图，视图层一行不用改。移动端皮肤在 Kotlin 侧求值，不复用 PC 的 wind-theme。槽位按**语义**命名而不是按颜色——`accentSoft` 在深色里并不"浅"。
 *
 * ⚠ 视图在构造时取色、不监听变化。这是有意的：IME 的输入视图在系统配置变化
 * （深浅色切换、旋转）时**整个重建**（`onCreateInputView` 重跑），所以每次构造
 * 取到的就是当时该用的那套；给每个 View 挂监听反而多一条不会被走到的路。
 */
data class Palette(
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
    // ── 阶段 1 新增槽位（docs/mobile-skin-design.md §3.2）：全部可省略，缺省按回退链取现有槽位，
    //    所以不写它们的皮肤（含内置默认）与此前逐像素一致 ──
    /** 普通键的主字（缺省 [text]） */
    val keyText: Int = text,
    /** 功能键的字与图标（缺省 [text]） */
    val keySpecialText: Int = text,
    /** 键面角标，即上滑 / 下滑符号（缺省 [hint]） */
    val keyHint: Int = hint,
    /** 强调键面：回车等（缺省 [accent]） */
    val keyAccent: Int = accent,
    /** 键面描边（0 = 不描边） */
    val keyBorder: Int = 0,
    /** 键面下沿投影色（0 = 不画；偏移固定 1dp） */
    val keyShadow: Int = 0,
    /** 条区（编码行 + 候选行 + 工具栏）底色（缺省 [background]） */
    val barBackground: Int = background,
    /** 候选字（缺省 [text]） */
    val candidateText: Int = text,
    /** 选中候选的文字（缺省 [accentText]） */
    val candidateSelected: Int = accentText,
    /** 候选注释（缺省 [hint]） */
    val candidateComment: Int = hint,
    /** 长按气泡 / 候选菜单底（缺省 [key]） */
    val popupBackground: Int = key,
    /** 气泡描边（缺省 [border]） */
    val popupBorder: Int = border,
    /** 气泡文字（缺省 [text]） */
    val popupText: Int = text,
    /** 气泡选中项底（缺省 [accentSoft]） */
    val popupSelected: Int = accentSoft,
    /** 符号 / 表情 / 剪贴板等面板内容区（缺省 [surface]） */
    val panelBackground: Int = surface,
    /** 背景图上的遮罩色（含透明度；0 = 无）。阶段 3 的背景图用，此阶段只解析 */
    val scrim: Int = 0,
) {
    companion object {
        /**
         * 内置浅 / 深两套的**取值**（数值在 [SkinRuntime.DEFAULT]，黄金测试钉死与重构前逐项相等）。
         * 是函数式取值而不是存起来的字段：见 SkinRuntime.DEFAULT 注释里的类初始化环。
         * 两次取到的是同一个实例——Service 靠 `!==` 判断配色有没有变。
         */
        val light: Palette get() = SkinRuntime.DEFAULT.light
        val dark: Palette get() = SkinRuntime.DEFAULT.dark

        /**
         * 当前生效的调色板。由 Service 在 `onCreateInputView` 里按系统深浅色设定，
         * 视图层只读。与 [KeyboardDimens] 同一种"Service 注入、视图取用"的模式。
         */
        @Volatile
        private var applied: Palette? = null

        val current: Palette get() = applied ?: light

        /**
         * 选一套设为当前。[mode] 来自设置项 ui.android.theme：`light` / `dark` / 其它 = 跟随系统。
         */
        fun apply(context: Context, mode: String): Palette {
            return resolve(context, mode).also { applied = it }
        }

        /**
         * 按设置与系统深浅色算出该用哪套，不改 [current]（Service 用它核对当前视图配色对不对）。
         *
         * 「跟随系统」读**系统全局**配置（`Resources.getSystem()`），不读 Service 自己的：
         * 刚装好、进程刚起来时 IME 服务拿到的配置可能是旧的——真机上出现过系统浅色、
         * 键盘却是深色，切一次输入法（服务重建）才对。
         */
        fun resolve(context: Context, mode: String): Palette = when (mode) {
            "light" -> SkinRuntime.active.light
            "dark" -> SkinRuntime.active.dark
            else -> {
                val uiMode = android.content.res.Resources.getSystem().configuration.uiMode
                    .takeIf { it and Configuration.UI_MODE_NIGHT_MASK != Configuration.UI_MODE_NIGHT_UNDEFINED }
                    ?: context.resources.configuration.uiMode
                val night = uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
                SkinRuntime.active.palette(night)
            }
        }
    }
}
