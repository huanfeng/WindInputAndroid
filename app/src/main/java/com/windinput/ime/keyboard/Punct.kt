package com.windinput.ime.keyboard

/**
 * 标点与符号：**键面一律用 ASCII 定义**，中英两种形态都由这里派生。
 *
 * # 为什么布局文件里不能直接写「，」「。」
 *
 * 曾经写的就是中文字面。后果有两层：中文模式下上滑出来的 `!?:;` 全是英文（TOML 里写的
 * 就是英文），英文模式下逗号句号却是中文（TOML 里写的就是中文）——键面上写死的东西
 * 不会随模式变。正确的分工是：布局只说「这个键是逗号」，**中英取决于当前模式**。
 *
 * # 两条路径，两个真值
 *
 * - **输出**：ASCII 字符按美式键盘译成 wind-keys VK（+Shift 位）喂给引擎，中文标点转换、
 *   智能引号配对、全角半角都是引擎的事（桌面就是这么走的）。英文模式下引擎不吃，
 *   回到宿主默认行为时上屏的自然是 ASCII。K 侧**不做**任何标点转换。
 * - **显示**：键面 / 角标 / 长按气泡按 [display] 换成中文形态。这只是提示，与引擎的实际
 *   输出可能在个别字符上有差异（引擎的映射表可配置）。宁可显示偏一格，不能让 K 侧长出
 *   第二份转换逻辑——那才是「显示的和打出来的对不上」的根源。
 */
object Punct {

    /** 美式键盘：字符 → (VK, 需要 Shift)。SSOT 在主仓 wind-keys/keymap.rs 的注释里 */
    private val US_LAYOUT: Map<Char, Pair<UInt, Boolean>> = buildMap {
        // 数字行
        for (d in '0'..'9') put(d, (0x30u + (d - '0').toUInt()) to false)
        ")!@#$%^&*(".forEachIndexed { i, c -> put(c, (0x30u + i.toUInt()) to true) }
        // OEM 键（VK_OEM_*），未 Shift / Shift
        val oem = listOf(
            0xBAu to (';' to ':'),
            0xBBu to ('=' to '+'),
            0xBCu to (',' to '<'),
            0xBDu to ('-' to '_'),
            0xBEu to ('.' to '>'),
            0xBFu to ('/' to '?'),
            0xC0u to ('`' to '~'),
            0xDBu to ('[' to '{'),
            0xDCu to ('\\' to '|'),
            0xDDu to (']' to '}'),
            0xDEu to ('\'' to '"'),
        )
        for ((vk, pair) in oem) {
            put(pair.first, vk to false)
            put(pair.second, vk to true)
        }
    }

    /** 字符 → VK；不在美式键盘上的字符（中文标点字面、emoji）返回 null，走宿主直输 */
    fun vkOf(c: Char): Pair<UInt, Boolean>? = US_LAYOUT[c]

    /**
     * 中文标点显示形态（通行约定，与搜狗 / Rime 默认一致）。
     * 成对符号只显示**左**边一个：键面放不下一对，且引擎会自己配对。
     */
    private val CHINESE_DISPLAY: Map<Char, String> = mapOf(
        ',' to "，", '.' to "。", '?' to "？", '!' to "！", ':' to "：", ';' to "；",
        '(' to "（", ')' to "）", '<' to "《", '>' to "》",
        '[' to "「", ']' to "」", '{' to "『", '}' to "』",
        '\\' to "、", '~' to "～", '^' to "…", '$' to "￥", '`' to "·", '_' to "—",
        '"' to "“", '\'' to "‘",
    )

    /** 按当前模式给出键面显示文本 */
    fun display(c: Char, chinese: Boolean): String =
        if (chinese) CHINESE_DISPLAY[c] ?: c.toString() else c.toString()
}
