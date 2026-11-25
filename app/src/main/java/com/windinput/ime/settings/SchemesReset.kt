package com.windinput.ime.settings

import android.util.Log

/**
 * 「恢复所有方案设置」：每个已装方案，表单里与方案默认不同的项发 reset（覆盖层删键），
 * 再清自定义行为（标点 / 短语等）。扩展词库开关、导入的方案、词库都不动。
 * 一个方案失败不影响其它方案。
 */
object SchemesReset {
    /** 返回表单项有改动、被恢复了的方案数 */
    fun resetAll(ids: List<String>, io: SchemeFormIo, resetBehavior: (String) -> Unit): Int {
        var changed = 0
        for (id in ids) {
            try {
                val form = io.load(id)
                val eff = ConfigSnapshot.flatten(form.effective)
                val def = ConfigSnapshot.flatten(form.defaultValues)
                val keys = def.keys.filter { eff[it] != def[it] }
                if (keys.isNotEmpty()) {
                    io.apply(id, emptyList(), keys)
                    changed++
                }
            } catch (e: Exception) {
                logWarn("恢复方案 $id 的设置失败", e)
            }
            try {
                resetBehavior(id)
            } catch (e: Exception) {
                logWarn("恢复方案 $id 的自定义行为失败", e)
            }
        }
        return changed
    }

    // JVM 单测里 android.util.Log 是桩，调用会抛；这里只是记录，失败不影响结果
    private fun logWarn(msg: String, e: Exception) {
        runCatching { Log.w("SchemesReset", msg, e) }
    }
}
