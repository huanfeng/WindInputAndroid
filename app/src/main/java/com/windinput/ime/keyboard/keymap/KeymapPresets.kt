package com.windinput.ime.keyboard.keymap

/**
 * 出厂键位预设（`data/keymap_presets/<id>.toml`，格式同 keymap.toml）。
 * 设置项 `ui.android.keymap_preset` 选其一；[DEFAULT] = 不套任何预设（出厂键盘，一键不改）。
 * 选项文案在 settings_manifest.toml，这里只管 id 的校验——认不出的 id 一律当默认。
 */
object KeymapPresets {
    const val DEFAULT = "default"
    const val SETTING_KEY = "ui.android.keymap_preset"

    val IDS = listOf("cn_marks", "cn_brackets", "edit_swipe_down")

    fun fileFor(id: String): String? = if (id in IDS) "keymap_presets/$id.toml" else null
}
