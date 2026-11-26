package com.windinput.ime.settings

import android.util.Log
import uniffi.wind_android.ConfigException
import uniffi.wind_android.SchemeFormSet

/**
 * 方案设置「复制到其它方案」：按组挑出源方案的生效值，写进同类目标方案的覆盖层。照电脑版
 * （wind-setting `scheme_copy.rs`）：
 *
 * - 一次性复制，不建立跟随关系；写入走 `scheme_form` apply，与目标方案默认相同的项由核心删掉，
 *   所以复制后目标方案在这些组上的生效值与源方案一致。
 * - 同类才能互相复制，全拼与双拼算同类（双拼专有项按清单的 `schemes` 滤掉）。
 * - 辅助码不复制：每个方案配的字形码表各不相同。键盘自己的项（九宫格读法栏）不按方案存，也不复制。
 * - 方案属性（整句开关、标点、命令短语）不在表单里，不复制。
 */
object SchemeCopy {

    data class Target(val id: String, val name: String, val type: String)

    data class Result(val copied: List<String>, val failed: List<Pair<String, String>>)

    /** 能互相复制的类：全拼、双拼同属拼音 */
    fun family(type: String): String = if (type == "shuangpin") "pinyin" else type

    /** 源方案可复制的组（清单顺序）：组名 → 引擎项；辅助码组与键盘项除外 */
    fun blocks(type: String, manifest: SettingsManifestData): List<Pair<String, List<SettingItem>>> =
        manifest.schemeGroups(type)
            .filter { it.first != GROUP_AUX }
            .map { (name, items) -> name to items.filter { it.source == SettingSource.ENGINE } }
            .filter { it.second.isNotEmpty() }

    /** 写给 [targetType] 方案的项：[items] 里目标类型收、源方案有值的 */
    fun sets(
        items: List<SettingItem>,
        sourceEffective: Map<String, Any>,
        targetType: String,
        typeOf: (String) -> String?,
    ): List<SchemeFormSet> =
        items.filter { targetType in it.schemes }
            .mapNotNull { item ->
                val v = sourceEffective[item.key] ?: return@mapNotNull null
                SchemeFormSet(item.key, configValueOf(typeOf(item.key), if (v is List<*>) RawValue.choice(v) else v))
            }

    /** 读源方案最新的生效值，逐个写目标。一个目标失败不影响其它目标 */
    fun copy(
        sourceId: String,
        items: List<SettingItem>,
        targets: List<Target>,
        io: SchemeFormIo,
        typeOf: (String) -> String?,
    ): Result {
        val eff = ConfigSnapshot.flatten(io.load(sourceId).effective)
        val copied = mutableListOf<String>()
        val failed = mutableListOf<Pair<String, String>>()
        for (t in targets) {
            try {
                val sets = sets(items, eff, t.type, typeOf)
                if (sets.isNotEmpty()) io.apply(t.id, sets, emptyList())
                copied += t.name
            } catch (e: Exception) {
                runCatching { Log.w("SchemeCopy", "复制到 ${t.id} 失败", e) }
                failed += t.name to ((e as? ConfigException.Rejected)?.reason ?: e.message ?: e.toString())
            }
        }
        return Result(copied, failed)
    }
}
