package com.windinput.ime.keyboard

/**
 * 可以常驻在工具栏上的功能（快捷面板里的入口类格子）。
 *
 * 工具栏空着时只有「快捷功能」与「收起」两头，剪贴板、文本编辑这类最常用的要先进快捷面板
 * 再点一下（真机反馈）。常驻哪几个由用户在快捷面板的「编辑工具栏」里挑，存成 [id] 的列表
 * （设置项 ui.android.toolbar_pins）。
 *
 * @param glyph 矢量图标；为 null 时用 [text]（「拼」「;」这类本身就是字的入口）
 */
enum class ToolbarItem(
    val id: String,
    val label: String,
    val glyph: PanelIcon.Glyph?,
    val text: String? = null,
) {
    CLIPBOARD("clipboard", "剪贴板", PanelIcon.Glyph.CLIPBOARD),
    TEXT_EDIT("text_edit", "文本编辑", PanelIcon.Glyph.TEXT_EDIT),
    EMOJI("emoji", "表情", PanelIcon.Glyph.EMOJI),
    VOICE("voice", "语音输入", PanelIcon.Glyph.MIC),
    SWITCH_IME("switch_ime", "切换输入法", PanelIcon.Glyph.GLOBE),
    SETTINGS("settings", "设置", PanelIcon.Glyph.SETTINGS),
    ONE_HAND("one_hand", "单手", PanelIcon.Glyph.ONE_HAND),
    INCOGNITO("incognito", "无痕", PanelIcon.Glyph.INCOGNITO),

    /**
     * 临时拼音 / 快捷输入：电脑上是空码时按 ` / ; 进入，手机上这两个字符藏在上滑里没人找得到。
     * 点它等于替用户按一下引导键（引擎侧行为与电脑一致）。
     */
    TEMP_PINYIN("temp_pinyin", "临时拼音", null, "拼"),
    QUICK_INPUT("quick_input", "快捷输入", null, ";"),
    ;

    companion object {
        const val CONFIG_KEY = "ui.android.toolbar_pins"

        /** 出厂常驻：表情、剪贴板、文本编辑（主流输入法工具栏上都有，表情使用频率最高） */
        const val DEFAULT_PINS = "emoji,clipboard,text_edit"

        /** 工具栏放得下的上限：两头各一格之外，再多就挤得点不准 */
        const val MAX_PINS = 5

        /** 配置串 → 列表（未知 id 跳过、去重、截到上限），顺序即工具栏上的顺序 */
        fun parse(value: String): List<ToolbarItem> =
            value.split(',')
                .mapNotNull { id -> entries.firstOrNull { it.id == id.trim() } }
                .distinct()
                .take(MAX_PINS)

        fun format(items: List<ToolbarItem>): String = items.joinToString(",") { it.id }
    }
}
