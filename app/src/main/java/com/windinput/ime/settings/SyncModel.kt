package com.windinput.ime.settings

import org.json.JSONArray
import org.json.JSONObject

/**
 * 「数据同步」页的纯逻辑：JSON 解析、阶段文案、输入校验。界面（SyncSection）只管画与调用。
 * JSON 形状同桌面 `sync.getSettings` / `sync.status` / `sync.command`。
 */
internal object SyncModel {

    /** 大批量删除护栏的标记（主仓 runner.rs 的 MASS_MARK） */
    const val MASS_MARK = "为防误删已暂停"

    /** 文件正在被编辑、本轮暂缓的标记 */
    const val EDITING_MARK = "正在被编辑，本轮暂不同步"

    /** 阶段的中文说明；不认识的原样带出 */
    fun phaseText(phase: String): String = when (phase) {
        "not_installed" -> "未安装同步组件"
        "disabled" -> "未启用"
        "not_configured" -> "未配置存储后端"
        "starting" -> "启动中…"
        "needs_setup" -> "需要设置口令或导入密钥"
        "idle" -> "空闲，已是最新"
        "syncing" -> "同步中…"
        "error" -> "出错"
        "halted" -> "已停止，需要处理"
        "" -> "状态未知"
        else -> "状态：$phase"
    }

    enum class ErrorKind { NONE, DANGER, NOTICE, MASS_DELETE }

    fun classifyError(phase: String, err: String): ErrorKind = when {
        err.isEmpty() -> ErrorKind.NONE
        phase == "halted" && MASS_MARK in err -> ErrorKind.MASS_DELETE
        phase == "error" && EDITING_MARK in err -> ErrorKind.NOTICE
        else -> ErrorKind.DANGER
    }

    data class Status(
        val enabled: Boolean = false,
        val phase: String = "",
        val lastOk: Long? = null,
        val lastError: String = "",
        val pending: Int = 0,
        val conflicts: Int = 0,
        val hasSecret: Boolean = false,
        val joined: Boolean = false,
    ) {
        val errorKind: ErrorKind get() = classifyError(phase, lastError)

        /** 立即同步只在配置齐全且没在跑时有意义 */
        val canSyncNow: Boolean get() = enabled && phase in setOf("idle", "error")
    }

    fun parseStatus(json: String): Status = try {
        val o = JSONObject(json)
        Status(
            enabled = o.optBoolean("enabled"),
            phase = o.optString("phase"),
            lastOk = if (o.isNull("last_ok")) null else o.optLong("last_ok"),
            lastError = if (o.isNull("last_error")) "" else o.optString("last_error"),
            pending = o.optInt("pending"),
            conflicts = o.optInt("conflicts"),
            hasSecret = o.optBoolean("has_secret"),
            joined = o.optBoolean("joined"),
        )
    } catch (e: org.json.JSONException) {
        Status()
    }

    enum class BackendType { NONE, WEBDAV, S3 }

    /** 设置页编辑的草稿：后端各字段 + 范围 + 设备名。密码不在这里（只写不回显） */
    data class Draft(
        val enabled: Boolean = false,
        val type: BackendType = BackendType.WEBDAV,
        val url: String = "",
        val user: String = "",
        val endpoint: String = "",
        val region: String = "",
        val bucket: String = "",
        val prefix: String = "",
        val accessKeyId: String = "",
        val pathStyle: Boolean = false,
        val config: Boolean = true,
        val schemas: Boolean = true,
        val themes: Boolean = true,
        val dicts: Boolean = true,
        val deviceName: String = "",
    )

    /** 解析 `get_settings` 的回执（`{settings, has_secret}`，也接受裸 settings） */
    fun parseSettings(json: String): Draft = try {
        val root = JSONObject(json)
        val s = root.optJSONObject("settings") ?: root
        val b = s.optJSONObject("backend")
        val scope = s.optJSONObject("scope")
        var d = Draft(
            enabled = s.optBoolean("enabled"),
            config = scope?.optBoolean("config", true) ?: true,
            schemas = scope?.optBoolean("schemas", true) ?: true,
            themes = scope?.optBoolean("themes", true) ?: true,
            dicts = scope?.optBoolean("dicts", true) ?: true,
            deviceName = s.optString("device_name"),
        )
        when (b?.optString("type")) {
            "webdav" -> d = d.copy(type = BackendType.WEBDAV, url = b.optString("url"), user = b.optString("user"))
            "s3" -> d = d.copy(
                type = BackendType.S3,
                endpoint = b.optString("endpoint"),
                region = b.optString("region"),
                bucket = b.optString("bucket"),
                prefix = b.optString("prefix"),
                accessKeyId = b.optString("access_key_id"),
                pathStyle = b.optBoolean("path_style"),
            )
        }
        d
    } catch (e: org.json.JSONException) {
        Draft()
    }

    /**
     * 把草稿写回 `base`（`get_settings` 里的 settings 对象）：只改自己认识的字段，
     * 其余（如 interval_min）原样保留，免得新版加了字段被这里抹掉。
     * 后端字段全空且为 WebDAV/S3 草稿时仍写出（核心校验会指出缺哪项）。
     */
    fun toSettingsJson(baseJson: String, d: Draft): String {
        val root = try { JSONObject(baseJson) } catch (e: org.json.JSONException) { JSONObject() }
        val s = root.optJSONObject("settings") ?: root
        s.put("enabled", d.enabled)
        s.put(
            "scope",
            JSONObject().put("config", d.config).put("schemas", d.schemas).put("themes", d.themes).put("dicts", d.dicts),
        )
        s.put("device_name", d.deviceName.trim())
        when (d.type) {
            BackendType.NONE -> s.remove("backend")
            BackendType.WEBDAV -> s.put(
                "backend",
                JSONObject().put("type", "webdav").put("url", d.url.trim()).put("user", d.user.trim()),
            )
            BackendType.S3 -> s.put(
                "backend",
                JSONObject().put("type", "s3")
                    .put("endpoint", d.endpoint.trim())
                    .put("region", d.region.trim())
                    .put("bucket", d.bucket.trim())
                    .put("prefix", d.prefix.trim())
                    .put("access_key_id", d.accessKeyId.trim())
                    .put("path_style", d.pathStyle),
            )
        }
        return s.toString()
    }

    /** 后端是否填到可以测试连接 */
    fun backendReady(d: Draft): Boolean = when (d.type) {
        BackendType.NONE -> false
        BackendType.WEBDAV -> d.url.isNotBlank()
        BackendType.S3 -> d.endpoint.isNotBlank() && d.bucket.isNotBlank() && d.accessKeyId.isNotBlank()
    }

    /** 密码栏文案：已保存过就不回显，提示留空不改 */
    fun passwordHint(type: BackendType, hasSecret: Boolean): String {
        val what = if (type == BackendType.S3) "Secret Access Key" else "密码"
        return if (hasSecret) "$what（已保存，留空则不修改）" else what
    }

    /** `set_secret` 的参数：只写不回显；留空 = 不动（返回 null） */
    fun secretArgs(password: String): String? =
        if (password.isEmpty()) null else JSONObject().put("password", password).toString()

    /** 口令校验：非空，两次一致。返回错误说明，null = 通过 */
    fun checkPassphrase(pass: String, confirm: String): String? = when {
        pass.isEmpty() -> "口令不能为空"
        pass != confirm -> "两次输入的口令不一致"
        else -> null
    }

    fun passphraseArgs(pass: String, old: String): String {
        val o = JSONObject().put("passphrase", pass)
        if (old.isNotEmpty()) o.put("old_passphrase", old)
        return o.toString()
    }

    fun resetArgs(pass: String): String = JSONObject().put("confirm", true).put("passphrase", pass).toString()

    fun importKeyArgs(key: String): String = JSONObject().put("key", key.trim()).toString()

    fun removeDeviceArgs(id: String): String = JSONObject().put("dev", id).put("confirm", true).toString()

    fun parseExportedKey(json: String): String? = try {
        JSONObject(json).optString("key").ifEmpty { null }
    } catch (e: org.json.JSONException) {
        null
    }

    data class Device(
        val id: String,
        val name: String,
        val platform: String,
        val appVersion: String,
        val lastSeen: Long,
        val isSelf: Boolean,
        val readable: Boolean,
    ) {
        val title: String get() = if (readable) name.ifEmpty { id.take(8) } else "无法识别的设备（${id.take(8)}）"

        /** 本机不可移除；读不出来的旧设备可以移除 */
        val removable: Boolean get() = !isSelf
    }

    fun parseDevices(json: String): List<Device> = try {
        val arr: JSONArray = JSONObject(json).optJSONArray("devices") ?: JSONArray()
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Device(
                id = o.optString("id"),
                name = o.optString("name"),
                platform = o.optString("platform"),
                appVersion = o.optString("app_version"),
                lastSeen = o.optLong("last_seen"),
                isSelf = o.optBoolean("is_self"),
                readable = o.optBoolean("readable", true),
            )
        }
    } catch (e: org.json.JSONException) {
        emptyList()
    }

    /** 本机名称：空则用机型名 */
    fun defaultDeviceName(current: String, model: String): String = current.trim().ifEmpty { model.trim() }

    /** 「N 分钟前」式的相对时间；0 或负数当未知 */
    fun ago(unixSecs: Long, nowSecs: Long): String {
        if (unixSecs <= 0) return "从未"
        val d = (nowSecs - unixSecs).coerceAtLeast(0)
        return when {
            d < 60 -> "刚刚"
            d < 3600 -> "${d / 60} 分钟前"
            d < 86400 -> "${d / 3600} 小时前"
            else -> "${d / 86400} 天前"
        }
    }

    fun statusSummary(s: Status, nowSecs: Long): String = when {
        !s.enabled -> "未启用"
        s.phase == "idle" -> "已是最新 · 上次成功 ${ago(s.lastOk ?: 0, nowSecs)}"
        else -> phaseText(s.phase)
    }
}
