package com.windinput.ime.skin

import java.io.File

/**
 * 皮肤的发现、选择与载入。纯 JVM（只依赖 File），Service 与设置页共用。
 *
 * 目录（与 PC 不冲突，全在移动端独有的 `mobile/` 下，见 docs/mobile-skin-design.md §4）：
 * - 内置：`<filesDir>/data/skins/<id>.toml`（assets 随 App 升级重放；经与用户皮肤**同一个**加载器）
 * - 用户：`<userRoot>/mobile/skins/<id>/skin.toml`
 * - 选择：`<userRoot>/mobile/skin_prefs.toml`，`active = "<id>"`；没有 = [DEFAULT_ID]
 *
 * [DEFAULT_ID] 永远指向 [SkinRuntime.DEFAULT]（不读文件），所以出厂外观不依赖任何文件，也无法被
 * 同名用户皮肤盖掉。载入失败（缺文件、读不出、版本过高）一律回退默认并给出提示，不抛。
 */
class SkinStore(
    private val builtinDir: File,
    private val userSkinsDir: File,
    private val prefsFile: File,
) {
    companion object {
        const val DEFAULT_ID = "default"
        const val DEFAULT_NAME = "清风·蓝（默认）"
        private val ID_OK = Regex("^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$")
    }

    data class Entry(val id: String, val name: String, val builtin: Boolean)

    /** [warnings] 含解析告警与对比度告警；[fellBack] = 想用的皮肤没载入成功，已退回默认 */
    class Activation(
        val id: String,
        val skin: SkinRuntime,
        val warnings: List<String>,
        val fellBack: Boolean,
    )

    private class Stamp(val id: String, val path: String?, val modified: Long, val length: Long)

    private var stamp: Stamp? = null
    private var last: Activation? = null

    /** 内置在前（默认置顶），再是用户皮肤；名字取 `[meta] name`，没有就用 id */
    fun list(): List<Entry> {
        val out = mutableListOf(Entry(DEFAULT_ID, DEFAULT_NAME, true))
        builtinDir.listFiles { f -> f.isFile && f.extension == "toml" }?.sortedBy { it.name }?.forEach { f ->
            val id = f.nameWithoutExtension
            if (id != DEFAULT_ID && ID_OK.matches(id)) out += Entry(id, nameOf(f) ?: id, true)
        }
        userSkinsDir.listFiles { f -> f.isDirectory }?.sortedBy { it.name }?.forEach { d ->
            val id = d.name
            if (id != DEFAULT_ID && ID_OK.matches(id) && out.none { it.id == id }) {
                File(d, "skin.toml").takeIf { it.isFile }?.let { out += Entry(id, nameOf(it) ?: id, false) }
            }
        }
        return out
    }

    fun activeId(): String = SkinPrefsFile.read(prefsFile).active

    fun setActive(id: String) {
        require(id == DEFAULT_ID || ID_OK.matches(id)) { "皮肤 id 不合法：$id" }
        SkinPrefsFile.write(prefsFile, SkinPrefsFile.read(prefsFile).copy(active = id))
    }

    /** 用户调的几何旋钮（没调过 = 空）；叠到 [SkinRuntime.active] 的几何上用 [GeometryAdjust.applyTo] */
    fun geometryAdjust(): GeometryAdjust = SkinPrefsFile.read(prefsFile).adjust

    /** 写入几何旋钮并保留所选皮肤；全 null = 恢复默认（段落一并删掉） */
    fun setGeometryAdjust(adjust: GeometryAdjust) {
        SkinPrefsFile.write(prefsFile, SkinPrefsFile.read(prefsFile).copy(adjust = adjust))
    }

    /**
     * 载入当前选中的皮肤并设为 [SkinRuntime.active]。按（id、文件修改时间、长度）缓存：没变就不重读，
     * Service 每次弹键盘调一次的代价就是几次 stat。[force] = 设置页「重新载入」。
     */
    @Synchronized
    fun activate(force: Boolean = false): Activation {
        val id = activeId()
        val file = fileOf(id)
        val now = Stamp(id, file?.path, file?.lastModified() ?: 0L, file?.length() ?: 0L)
        val old = stamp
        val cached = last
        if (!force && cached != null && old != null && old.id == now.id && old.path == now.path &&
            old.modified == now.modified && old.length == now.length
        ) {
            return cached
        }
        val result = load(id, file)
        stamp = now
        last = result
        SkinRuntime.active = result.skin
        return result
    }

    private fun load(id: String, file: File?): Activation {
        if (id == DEFAULT_ID) return Activation(id, SkinRuntime.DEFAULT, emptyList(), fellBack = false)
        if (file == null) {
            return Activation(DEFAULT_ID, SkinRuntime.DEFAULT, listOf("皮肤「$id」不存在，已用默认皮肤"), fellBack = true)
        }
        val parsed = try {
            SkinParser.parse(file.readText())
        } catch (e: Exception) {
            return Activation(DEFAULT_ID, SkinRuntime.DEFAULT, listOf("皮肤「$id」读取失败：${e.message}，已用默认皮肤"), fellBack = true)
        }
        // 整份读不出（语法错 / 版本过高）：SkinParser 给的是 DEFAULT + 警告，这里明确算回退
        val broken = parsed.tooNew || (parsed.skin === SkinRuntime.DEFAULT && parsed.warnings.any { "无法解析" in it })
        if (broken) {
            return Activation(DEFAULT_ID, SkinRuntime.DEFAULT, parsed.warnings + "皮肤「$id」不可用，已用默认皮肤", fellBack = true)
        }
        val contrast = SkinContrast.warnings(parsed.skin.light, "浅色") + SkinContrast.warnings(parsed.skin.dark, "深色")
        return Activation(id, parsed.skin, parsed.warnings + contrast, fellBack = false)
    }

    private fun fileOf(id: String): File? = when {
        id == DEFAULT_ID -> null
        File(builtinDir, "$id.toml").isFile -> File(builtinDir, "$id.toml")
        else -> File(File(userSkinsDir, id), "skin.toml").takeIf { it.isFile }
    }

    private fun nameOf(f: File): String? = try {
        org.tomlj.Toml.parse(f.readText()).getTable("meta")?.getString("name")?.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        null
    }
}
