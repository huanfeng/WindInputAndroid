package com.windinput.ime.core

import android.util.Log
import java.io.File

/**
 * 键盘用户层 `<userRoot>/keyboard/` 整体迁到移动端独有目录 `<userRoot>/mobile/`，
 * 其中 `config.toml` 改名 `keyboard.toml`。
 *
 * 为什么：`<userRoot>` 与 PC 的 `%APPDATA%\WindInput` 对应，后续要经 WebDAV / S3 与 PC 同步合并。
 * 键盘自己的 `config.toml` 与 PC 引擎的 `config.toml` 同名，按文件名识别的同步会把两者混为一谈；
 * 移动端独有的东西（键盘配置、布局、图标，之后还有 `keymap.toml`、皮肤）一律收进 `mobile/`，
 * 且不使用 PC 已占用的文件名。
 *
 * | 旧                         | 新                          |
 * |----------------------------|-----------------------------|
 * | keyboard/config.toml       | mobile/keyboard.toml        |
 * | keyboard/<其余全部>         | mobile/<同名>               |
 *
 * 规则同 [LegacyUserDataMigration]：目标已有的**不覆盖**，全部复制成功才删旧的。没有完成标记——
 * `keyboard/` 不存在就是「已迁完」，所以天然幂等；中途失败时旧文件还在，下次启动接着搬。
 * 必须在键盘读配置之前同步完成，且排在 [LegacyUserDataMigration]（它会产出 `keyboard/`）之后。
 */
object MobileDirMigration {
    private const val TAG = "MobileDirMigration"

    /** @return 本次是否搬动了文件 */
    fun run(userRoot: File, log: (String) -> Unit = { Log.i(TAG, it) }): Boolean {
        val old = File(userRoot, "keyboard")
        if (!old.isDirectory) return false
        val mobile = File(userRoot, "mobile").apply { mkdirs() }
        var moved = false
        for (child in old.listFiles().orEmpty()) {
            val target = if (child.name == "config.toml") File(mobile, "keyboard.toml") else File(mobile, child.name)
            if (LegacyUserDataMigration.mergeInto(child, target, log)) moved = true
        }
        old.delete() // 只剩空壳时才删（对非空目录无效：有搬失败的文件就留着）
        if (moved) log("键盘用户层已迁到 ${mobile.absolutePath}")
        return moved
    }
}
