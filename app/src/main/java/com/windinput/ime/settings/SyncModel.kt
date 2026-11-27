package com.windinput.ime.settings

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * 「数据同步」页的纯逻辑（可测）：状态快照、存储表单、命令步骤序列、设备与分组、身份选项、文案。
 * 界面（SyncSection）只管画与调用。照桌面设置端 `wind-setting/src/pages/sync/model.rs`、`wizard.rs` 翻译，
 * JSON 形状同桌面 `sync.getSettings` / `sync.status` / `sync.command`。
 *
 * 与桌面的差别只有两处：设备类别固定为手机（核心只把 `android` 平台算作手机），
 * 「立即同步」走 `command("sync_now")` 而不是 core 的 `sync.now`。
 */
internal object SyncModel {

    // ───────────────────────────── 状态 ─────────────────────────────

    /** 阶段的中文说明；不认识的原样带出 */
    fun phaseText(phase: String): String = when (phase) {
        "not_installed" -> "未安装同步组件"
        "disabled" -> "未启用"
        "not_configured" -> "未配置存储后端"
        "starting" -> "启动中…"
        "needs_setup" -> "需要生成或粘贴同步密钥"
        "idle" -> "空闲，已是最新"
        "syncing" -> "同步中…"
        "error" -> "出错"
        "halted" -> "已停止，需要处理"
        "" -> "状态未知"
        else -> "状态：$phase"
    }

    /** 大批量删除护栏的标记（runner.rs 的 MASS_MARK） */
    private const val MASS_MARK = "为防误删已暂停"

    /** 文件正在被编辑、本轮暂缓的标记 */
    private const val EDITING_MARK = "正在被编辑，本轮暂不同步"

    /** 撞号错误里的标记（wind-sync 的 error.rs） */
    private const val DUPLICATE_MARK = "相同设备号"

    /** 同步线程正忙时对命令的回绝（runner.rs 的 BUSY_MSG） */
    private const val BUSY_MSG = "正在同步，请稍后再试"

    enum class ErrorKind { NONE, DANGER, NOTICE, MASS_DELETE }

    fun classifyError(phase: String, err: String): ErrorKind = when {
        err.isEmpty() -> ErrorKind.NONE
        phase == "halted" && MASS_MARK in err -> ErrorKind.MASS_DELETE
        phase == "error" && EDITING_MARK in err -> ErrorKind.NOTICE
        else -> ErrorKind.DANGER
    }

    /** 命令失败的文案与是否只是提示（「正在同步，请稍后再试」不算故障） */
    fun failureText(label: String, e: String): Pair<String, Boolean> =
        if (BUSY_MSG in e) e to true else "${label}失败：$e" to false

    data class Status(
        val installed: Boolean = true,
        val enabled: Boolean = false,
        val phase: String = "",
        val lastOk: Long? = null,
        val lastError: String = "",
        val pending: Int = 0,
        val conflicts: Int = 0,
        val hasSecret: Boolean = false,
        /** 本机已加入同步库（持有同步密钥） */
        val joined: Boolean = false,
        val device: String = "",
        val group: String = "",
        val groupName: String = "",
        /** 加入远端后还没确认本机身份：确认前核心不同步 */
        val needsIdentity: Boolean = false,
        /** 已移除（不在名册里）但远端仍有学习数据的设备号 */
        val removedWithData: List<String> = emptyList(),
        /** 远端空间占用的缓存；从未统计过为 null */
        val usage: Usage? = null,
        val usageBusy: Boolean = false,
        /** 上次统计失败的原因 */
        val usageError: String? = null,
    ) {
        val errorKind: ErrorKind get() = classifyError(phase, lastError)

        /** 已加入同步库：显示全部区块，否则只有介绍与「开始配置…」。总开关关了仍算已配置 */
        val configured: Boolean get() = installed && joined

        /** 撞号：另一台设备在用本机的设备号 */
        val duplicateDevice: Boolean get() = DUPLICATE_MARK in lastError
    }

    fun parseStatus(json: String): Status = try {
        val o = JSONObject(json)
        Status(
            installed = o.optBoolean("installed", true),
            enabled = o.optBoolean("enabled"),
            phase = o.optString("phase"),
            lastOk = if (o.isNull("last_ok")) null else o.optLong("last_ok"),
            lastError = if (o.isNull("last_error")) "" else o.optString("last_error"),
            pending = o.optInt("pending"),
            conflicts = o.optInt("conflicts"),
            hasSecret = o.optBoolean("has_secret"),
            joined = o.optBoolean("joined"),
            device = o.optString("device"),
            group = o.optString("group"),
            groupName = o.optString("group_name"),
            needsIdentity = o.optBoolean("needs_identity"),
            removedWithData = o.optJSONArray("removed_with_data")?.let { a ->
                (0 until a.length()).map { a.optString(it) }.filter { it.isNotEmpty() }
            } ?: emptyList(),
            usage = o.optJSONObject("usage")?.let(::parseUsage),
            usageBusy = o.optBoolean("usage_busy"),
            usageError = if (o.isNull("usage_error")) null else o.optString("usage_error").ifEmpty { null },
        )
    } catch (e: JSONException) {
        Status()
    }

    // ───────────────────────────── 空间占用 ─────────────────────────────

    /** 远端空间占用（核心 `status.usage`），单位字节 */
    data class Usage(
        val computedAt: Long = 0,
        val total: Long = 0,
        /** 文件数据：各设备共用、按内容去重 */
        val blobs: Long = 0,
        /** 其中等待回收的旧版本；有设备清单解不开时无法确定（null） */
        val blobsUnreferenced: Long? = null,
        val devices: List<DeviceUsage> = emptyList(),
        /** 设备清单等小项 */
        val other: Long = 0,
        val provider: ProviderUsage? = null,
    ) {
        /** 存储服务的剩余空间；没有总容量也没有剩余为 null */
        val providerFree: Long?
            get() = provider?.let { p -> p.available ?: p.quota?.let { maxOf(it - p.used, 0L) } }
    }

    /** 存储服务口径：WebDAV 只有此目录已用与剩余，总容量只有官方服务给 */
    data class ProviderUsage(val used: Long = 0, val available: Long? = null, val quota: Long? = null)

    data class DeviceUsage(val id: String, val learned: Long = 0, val stats: Long = 0)

    private fun JSONObject.optLongOrNull(key: String): Long? = if (has(key) && !isNull(key)) optLong(key) else null

    private fun parseUsage(o: JSONObject): Usage {
        val devs = o.optJSONArray("devices")?.let { a ->
            (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map {
                DeviceUsage(it.optString("id"), it.optLong("learned"), it.optLong("stats"))
            }.filter { it.id.isNotEmpty() }
        } ?: emptyList()
        return Usage(
            computedAt = o.optLong("computed_at"),
            total = o.optLong("total"),
            blobs = o.optLong("blobs"),
            blobsUnreferenced = o.optLongOrNull("blobs_unreferenced"),
            devices = devs,
            other = o.optLong("other"),
            provider = o.optJSONObject("provider")?.let {
                ProviderUsage(it.optLong("used"), it.optLongOrNull("available"), it.optLongOrNull("quota"))
            },
        )
    }

    /** 等待回收量无法确定时的显示 */
    const val UNREFERENCED_UNKNOWN = "无法确定（有设备清单解不开）"

    /** 明细页底部的说明 */
    const val USAGE_NOTE = "按加密后的大小统计。等待回收的旧版本放满 7 天、且仍没有设备用到时，会在同步时自动删除。"

    /** 空间大小：1024 进制，B 取整，KB 起一位小数 */
    fun usageSize(n: Long): String {
        if (n < 1024) return "$n B"
        val units = arrayOf("KB", "MB", "GB")
        var v = n / 1024.0
        var i = 0
        while (v >= 1024.0 && i + 1 < units.size) {
            v /= 1024.0
            i++
        }
        return String.format(java.util.Locale.ROOT, "%.1f %s", v, units[i])
    }

    /** 存储卡「已用空间」行的值 */
    fun usageLine(s: Status): String {
        if (s.usageBusy) return "统计中…"
        val u = s.usage ?: return "未统计"
        val free = u.providerFree
        return "已用 ${usageSize(u.total)}" + (if (free != null) " · 网盘剩余 ${usageSize(free)}" else "")
    }

    /** 「已用空间」行的说明：统计时间与上次失败原因 */
    fun usageDesc(s: Status, today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): String {
        val parts = mutableListOf<String>()
        s.usage?.let { parts += "统计于" + lastSeenText(it.computedAt, today, zone) }
        s.usageError?.let { parts += "上次统计失败：$it" }
        return parts.joinToString("；")
    }

    /** 「刷新」可点：没有命令在途、不在统计、也不在同步 */
    fun canRefreshUsage(s: Status, busy: Boolean): Boolean = !busy && !s.usageBusy && s.phase != "syncing"

    /** 明细里的设备名：名册显示名；名册没取到显示设备号；取到但没有它的标为已移除 */
    fun deviceUsageLabel(id: String, roster: List<Device>): String {
        if (roster.isEmpty()) return id
        val d = roster.firstOrNull { it.id == id } ?: return "已移除的设备（$id）"
        return if (d.readable && d.name.isNotBlank()) d.name else id
    }

    /** 明细页的各行 (名称, 值) */
    fun usageRows(u: Usage, roster: List<Device>): List<Pair<String, String>> {
        val rows = mutableListOf(
            "合计" to usageSize(u.total),
            "文件数据（全组共享、已去重）" to usageSize(u.blobs),
            "其中等待回收的旧版本" to (u.blobsUnreferenced?.let(::usageSize) ?: UNREFERENCED_UNKNOWN),
        )
        for (d in u.devices) {
            rows += deviceUsageLabel(d.id, roster) to "学习数据 ${usageSize(d.learned)} · 打字统计 ${usageSize(d.stats)}"
        }
        rows += "设备清单等" to usageSize(u.other)
        u.provider?.let { p ->
            rows += "此目录已用" to usageSize(p.used)
            u.providerFree?.let { rows += "网盘剩余" to usageSize(it) }
            p.quota?.let { rows += "总容量" to usageSize(it) }
        }
        return rows
    }

    // ───────────────────────────── 已移除设备的数据 ─────────────────────────────

    /** 设备卡末行的值；没有已移除设备的数据返回空串（行隐藏） */
    fun purgeSummary(devs: List<String>): String = if (devs.isEmpty()) "" else "已移除设备的数据：${devs.size} 台"

    const val PURGE_WARN = "清除后其他设备的次数会减去它们的部分，无法恢复"

    /** 逐个清除：每台设备一条 `purge_device` */
    fun purgeSteps(devs: List<String>): List<Step> =
        devs.map { Step.Command("purge_device", JSONObject().put("device", it).toString()) }

    // ───────────────────────────── 同步内容 ─────────────────────────────

    /** 各类内容是否参与同步（本机设置，不随同步传播），缺省全开 */
    data class Content(val learned: Boolean = true, val completion: Boolean = true, val stats: Boolean = true)

    /** 内容开关改动：只改 content，其余设置原样（setSettings 是整份覆盖） */
    fun contentSwitchSteps(saved: Form, content: Content): List<Step> =
        listOf(Step.SetSettings(saved.copy(content = content).toSettingsJson()))

    /** 状态行的值：等确认身份或撞号时核心不同步，不能照阶段写「已是最新」 */
    fun statusValue(s: Status): String = when {
        s.needsIdentity -> "需要确认本机身份"
        s.duplicateDevice -> "需要给本机改号"
        s.errorKind == ErrorKind.NOTICE -> "稍后自动重试"
        else -> phaseText(s.phase)
    }

    /** 「立即同步」可点：总开关开着，且不在确认身份前、撞号时（核心会拒绝） */
    fun canSyncNow(s: Status): Boolean = s.enabled && !s.needsIdentity && !s.duplicateDevice

    fun pendingText(s: Status): String = if (s.pending == 0) "没有待上传的改动" else "${s.pending} 项改动待上传"

    /** 「上次成功」行的值 */
    fun lastOkText(lastOk: Long?, zone: ZoneId = ZoneId.systemDefault()): String =
        if (lastOk == null || lastOk <= 0) {
            "尚未同步过"
        } else {
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(zone).format(Instant.ofEpochSecond(lastOk))
        }

    // ───────────────────────────── 存储表单 ─────────────────────────────

    /** 默认同步间隔（分钟），与核心 DEFAULT_INTERVAL_MIN 一致 */
    const val DEFAULT_INTERVAL_MIN = 15

    /** 同步间隔上限（分钟），与核心 MAX_INTERVAL_MIN 一致 */
    const val MAX_INTERVAL_MIN = 24 * 60

    const val INTERVAL_DESC = "1 到 1440 分钟（24 小时）；本机有改动时会很快同步；学习数据最多每 30 分钟同步一次"

    /**
     * 同步设置的纯数据形态，对应核心 `SyncSettings`。**不含机密**。
     * [raw] 是读到的 settings 对象原文：写回时只改认识的字段，新版核心加的字段原样带回。
     */
    data class Form(
        val enabled: Boolean = false,
        /** false = WebDAV，true = S3 */
        val s3: Boolean = false,
        val wdUrl: String = "",
        val wdUser: String = "",
        val s3Endpoint: String = "",
        val s3Region: String = "",
        val s3Bucket: String = "",
        val s3Prefix: String = "",
        val s3AccessKeyId: String = "",
        val s3PathStyle: Boolean = false,
        /** 不校验存储服务的 HTTPS 证书（自签名证书的自建服务）。不算换位置，不要求重填密码 */
        val skipCertVerify: Boolean = false,
        val intervalMin: Int = DEFAULT_INTERVAL_MIN,
        /** 本机在设备名册里显示的名称；设备本地，不随同步传播 */
        val deviceName: String = "",
        /** 各类内容是否参与同步 */
        val content: Content = Content(),
        val raw: String = "{}",
    ) {
        /** 选中后端的必填项是否全空 */
        val backendBlank: Boolean
            get() = if (s3) {
                listOf(s3Endpoint, s3Bucket, s3AccessKeyId).all { it.isBlank() }
            } else {
                wdUrl.isBlank() && wdUser.isBlank()
            }

        /** `set_settings` 的入参。没启用且后端全空时不写后端（核心会拒绝空地址） */
        fun toSettingsJson(): String {
            val s = try { JSONObject(raw) } catch (e: JSONException) { JSONObject() }
            s.put("enabled", enabled)
            s.put("interval_min", intervalMin)
            s.put("device_name", deviceName.trim())
            if (skipCertVerify) s.put("skip_cert_verify", true) else s.remove("skip_cert_verify")
            // 全开是缺省，不写
            if (content != Content()) {
                s.put("content", JSONObject().put("learned", content.learned).put("completion", content.completion).put("stats", content.stats))
            } else {
                s.remove("content")
            }
            // 旧版安卓页写过的范围开关，核心已不认
            s.remove("scope")
            if (enabled || !backendBlank) {
                s.put(
                    "backend",
                    if (s3) {
                        JSONObject().put("type", "s3")
                            .put("endpoint", s3Endpoint.trim())
                            .put("region", s3Region.trim())
                            .put("bucket", s3Bucket.trim())
                            .put("prefix", s3Prefix.trim())
                            .put("access_key_id", s3AccessKeyId.trim())
                            .put("path_style", s3PathStyle)
                    } else {
                        JSONObject().put("type", "webdav").put("url", wdUrl.trim()).put("user", wdUser.trim())
                    },
                )
            } else {
                s.remove("backend")
            }
            return s.toString()
        }
    }

    /** 解析 `get_settings` 的回执（`{settings, has_secret}`，也接受裸 settings）；缺的字段按默认 */
    fun parseForm(json: String): Form = try {
        val root = JSONObject(json)
        val s = root.optJSONObject("settings") ?: root
        val interval = if (s.has("interval_min")) s.optInt("interval_min", DEFAULT_INTERVAL_MIN) else DEFAULT_INTERVAL_MIN
        var f = Form(
            enabled = s.optBoolean("enabled"),
            intervalMin = interval,
            deviceName = s.optString("device_name"),
            skipCertVerify = s.optBoolean("skip_cert_verify"),
            content = s.optJSONObject("content")?.let {
                Content(it.optBoolean("learned", true), it.optBoolean("completion", true), it.optBoolean("stats", true))
            } ?: Content(),
            raw = s.toString(),
        )
        val b = s.optJSONObject("backend")
        when (b?.optString("type")) {
            "webdav" -> f = f.copy(wdUrl = b.optString("url"), wdUser = b.optString("user"))
            "s3" -> f = f.copy(
                s3 = true,
                s3Endpoint = b.optString("endpoint"),
                s3Region = b.optString("region"),
                s3Bucket = b.optString("bucket"),
                s3Prefix = b.optString("prefix"),
                s3AccessKeyId = b.optString("access_key_id"),
                s3PathStyle = b.optBoolean("path_style"),
            )
        }
        f
    } catch (e: JSONException) {
        Form()
    }

    /** 存储位置（后端类型与定位字段）是否变了。核心在位置变化时会清掉已存的密码；旧位置全空不算 */
    fun backendLocationChanged(old: Form, new: Form): Boolean {
        if (old.backendBlank) return false
        if (old.s3 != new.s3) return true
        return if (new.s3) {
            listOf(old.s3Endpoint, old.s3Region, old.s3Bucket, old.s3Prefix, old.s3AccessKeyId) !=
                listOf(new.s3Endpoint, new.s3Region, new.s3Bucket, new.s3Prefix, new.s3AccessKeyId)
        } else {
            old.wdUrl.trim() != new.wdUrl.trim() || old.wdUser.trim() != new.wdUser.trim()
        }
    }

    const val SKIP_CERT_LABEL = "跳过证书验证（自签名证书的自建服务）"

    /** 勾选「跳过证书验证」后的提示 */
    const val SKIP_CERT_WARN = "跳过后无法确认连到的是不是你的服务器，存储密码可能被冒充者截获；同步内容仍在本机加密。只在自建服务用自签名证书时打开。"

    /** 存储区的摘要：类型与地址；跳过证书验证时标出来 */
    fun storageSummary(f: Form): String {
        val base = when {
            f.backendBlank -> "未设置"
            f.s3 -> "S3 · ${f.s3Endpoint.trim()} · ${f.s3Bucket.trim()}" +
                f.s3Prefix.trim().let { if (it.isEmpty()) "" else "/$it" }
            else -> "WebDAV · ${f.wdUrl.trim()} · 用户 ${f.wdUser.trim()}"
        }
        return if (f.skipCertVerify && !f.backendBlank) "$base · 跳过证书验证" else base
    }

    /** 本次要提交的 `set_secret` 参数；**空的不发**（留空 = 不修改）。WebDAV 密码与 S3 secret 共用 password 字段 */
    fun secretArgs(secret: String): String? =
        if (secret.isEmpty()) null else JSONObject().put("password", secret).toString()

    /** 机密输入框的占位：只反映「本机有没有」，从不带内容 */
    fun secretPlaceholder(hasSecret: Boolean, savedBackend: Boolean): String = when {
        hasSecret -> "已保存，留空则不修改"
        // 只存了密码、还没同步密钥时 has_secret 也为假
        savedBackend -> "如已保存过密码可留空"
        else -> "未设置"
    }

    /** 把输入的间隔收成合法整数（1 分钟到 24 小时）；不是数字时用默认值 */
    fun clampInterval(v: Double): Int {
        if (v.isNaN() || v.isInfinite()) return DEFAULT_INTERVAL_MIN
        return Math.round(v).coerceIn(1L, MAX_INTERVAL_MIN.toLong()).toInt()
    }

    fun clampInterval(text: String): Int = clampInterval(text.trim().toDoubleOrNull() ?: Double.NaN)

    /** 键盘弹出时两轮同步的最小间隔（秒）：取 `get_settings` 回执里的同步间隔，夹到 1–1440 分钟 */
    fun intervalSecs(settingsJson: String): ULong =
        clampInterval(parseForm(settingsJson).intervalMin.toDouble()).toULong() * 60uL

    /** 同步间隔改了才要保存：返回带新间隔的表单，其余原样 */
    fun intervalChange(saved: Form, v: Double): Form? {
        val n = clampInterval(v)
        return if (n != saved.intervalMin) saved.copy(intervalMin = n) else null
    }

    // ───────────────────────────── 命令步骤 ─────────────────────────────

    /** 一步调用：写设置 / 发命令 / 读状态。界面在后台线程依次执行，遇错即停 */
    sealed interface Step {
        data class SetSettings(val json: String) : Step
        data class Command(val op: String, val args: String = "{}") : Step
        object ReadStatus : Step {
            override fun toString() = "ReadStatus"
        }
    }

    /** 步骤序列或不能执行的原因 */
    sealed interface Plan {
        data class Run(val steps: List<Step>) : Plan
        data class Invalid(val reason: String) : Plan
    }

    /** 保存序列：**先写设置，再 `set_secret` / `test_connection`**。核心在位置变化时会清掉已存密码，顺序反了新密码会被清掉 */
    fun saveSteps(settings: Form, cmds: List<Step.Command>): List<Step> = listOf(Step.SetSettings(settings.toSettingsJson())) + cmds

    /** 总开关：只改启用开关，存储设置、密钥与远端数据都保留 */
    fun masterSwitchSteps(saved: Form, on: Boolean): List<Step> = listOf(Step.SetSettings(saved.copy(enabled = on).toSettingsJson()))

    /**
     * 「更换存储」：存设置 → 存密码 → 测试连接。位置变了要重填密码（核心会清掉旧的）；只切换证书校验不要求。
     * 测试回执决定下一步，见 [storageStageAfterTest]。
     */
    fun storageChangeSteps(saved: Form, new: Form, secret: String): Plan {
        val secretArgs = secretArgs(secret)
        if (backendLocationChanged(saved, new) && secretArgs == null) {
            return Plan.Invalid("更换了存储位置，请填写新位置的密码")
        }
        val cmds = listOfNotNull(secretArgs?.let { Step.Command("set_secret", it) }) + Step.Command("test_connection")
        return Plan.Run(saveSteps(new, cmds))
    }

    /** 「更换存储」所处的阶段 */
    enum class StorageStage {
        /** 填存储参数 */
        FORM,

        /** 新位置还没有同步库：给「在新位置创建同步库」 */
        CREATE,

        /** 新位置已有同步库：粘贴同步密钥加入 */
        JOIN,

        /** 已在新位置建库：显示新密钥，勾选已保存才能关 */
        CREATED,
    }

    private fun flag(json: String, key: String): Boolean = try {
        JSONObject(json).optBoolean(key)
    } catch (e: JSONException) {
        false
    }

    /** 测试连接回执 → 下一阶段；null = 本机已加入该位置的同步库，直接完成 */
    fun storageStageAfterTest(reply: String): StorageStage? = when {
        !flag(reply, "remote_initialized") -> StorageStage.CREATE
        flag(reply, "joined") -> null
        else -> StorageStage.JOIN
    }

    /** 测试连接回执的提示 */
    fun connectionText(reply: String): String = when {
        !flag(reply, "remote_initialized") -> "连接成功，存储里还没有同步库，下一步生成同步密钥"
        flag(reply, "joined") -> "连接成功，本机已加入"
        else -> "连接成功，存储里已有同步库，下一步粘贴同步密钥加入"
    }

    /** 生成同步密钥并建库。单发：后面再带一步的话，那一步失败会连同回执里的密钥一起丢掉 */
    fun createKeySteps(): List<Step> = listOf(Step.Command("create_key"))

    /** 粘贴同步密钥加入，之后读一次状态（看是否待确认身份）。格式整理交给核心，原样转发 */
    fun importKeySteps(key: String): Plan =
        if (key.isBlank()) {
            Plan.Invalid("请粘贴同步密钥")
        } else {
            Plan.Run(listOf(Step.Command("import_key", JSONObject().put("key", key).toString()), Step.ReadStatus))
        }

    /** 回执里的同步密钥；空串当作没有 */
    fun keyFromReply(json: String): String? = try {
        JSONObject(json).optString("key").ifEmpty { null }
    } catch (e: JSONException) {
        null
    }

    /** 同步密钥相关的失败文案：密钥不对原样显示，其余带上动作名 */
    fun keyErrorText(label: String, e: String): String = if (e.trim() == WRONG_KEY) WRONG_KEY else failureText(label, e).first

    /** 同步密钥按组分两行显示（前 7 组一行），手机屏一行放不下整串 */
    fun keyLines(key: String): String {
        val groups = key.split('-')
        if (groups.size <= 7) return key
        return groups.take(7).joinToString("-") + "\n" + groups.drop(7).joinToString("-")
    }

    /** 核心在建库时远端已有同步库的报错（片段与整句） */
    const val REMOTE_TAKEN = "远端已有同步库"
    const val REMOTE_TAKEN_MSG = "远端已有同步库，请粘贴同步密钥加入"

    /** 核心在加入时远端还没有同步库的报错 */
    const val REMOTE_EMPTY = "远端还没有同步库"

    /** 核心对密钥不对的报错 */
    const val WRONG_KEY = "同步密钥不对"

    const val KEY_SAVED_HINT = "请保存好同步密钥，其他设备加入时要用；丢失后无法解开已同步的数据"
    const val KEY_ACK = "我已保存同步密钥"
    const val KEY_PLACEHOLDER = "粘贴同步密钥"
    const val RESET_DESC = "清空远端全部同步数据并生成新的同步密钥，其他设备需要用新密钥重新加入"
    const val RESET_WARN = "这会删除远端存储里全部同步数据（含其他设备上传的内容），以本机当前数据重新上传，并生成新的同步密钥。其他设备需要用新密钥重新加入。此操作不可撤销。"
    const val MASS_DELETE_WARN = "这些文件在本机看似被删除。确认后，这些删除会同步到所有设备，其他设备上对应的文件也会被删掉。请确认不是误操作。"
    const val REMOVE_DEVICE_WARN = "移除后该设备的同步记录会从存储中删除。若该设备仍在使用，下次同步时会重新出现；要彻底阻止，请重置远端（会生成新的同步密钥）。"
    const val INTRO = "用 WebDAV 或 S3 在多台设备间同步，数据先在本机加密"

    fun removeDeviceArgs(id: String): String = JSONObject().put("dev", id).put("confirm", true).toString()

    fun resetArgs(): String = JSONObject().put("confirm", true).toString()

    // ───────────────────────────── 向导 ─────────────────────────────

    enum class WizStep(val number: Int?) {
        STORAGE(1), TEST(2), KEY(3), IDENTITY(4), DONE(null)
    }

    /**
     * 当前步骤成功后去哪一步。[joined] 是测试连接时本机已加入（重新配置）；[mode] 是第 3 步的做法：
     * 生成同步密钥为 "init"（首台设备，核心自动确认身份，跳过第 4 步），粘贴加入为 "import"。
     */
    fun nextStep(step: WizStep, joined: Boolean, mode: String, needsIdentity: Boolean): WizStep = when (step) {
        WizStep.STORAGE -> WizStep.TEST
        WizStep.TEST -> if (!joined) WizStep.KEY else if (needsIdentity) WizStep.IDENTITY else WizStep.DONE
        WizStep.KEY -> if (mode == "init") WizStep.DONE else WizStep.IDENTITY
        WizStep.IDENTITY, WizStep.DONE -> WizStep.DONE
    }

    /** 「上一步」的去处：同步密钥生效之后就回不去了 */
    fun backStep(step: WizStep): WizStep? = when (step) {
        WizStep.TEST, WizStep.KEY -> WizStep.STORAGE
        else -> null
    }

    /** 完成页的一句话，按从哪一步结束区分 */
    fun doneText(from: WizStep, mode: String): String = when {
        from == WizStep.KEY && mode == "init" -> "同步已开启。本机是第一台设备，其他设备加入时粘贴刚才的同步密钥即可。"
        from == WizStep.TEST -> "本机此前已加入，同步已重新开启。"
        from == WizStep.IDENTITY -> "已确认本机身份，同步会在后台开始。"
        else -> "已加入同步库，同步会在后台开始。"
    }

    /** 第 1 步的校验：要有存储地址；换了位置、或此前没有存储参数又没存过机密时要填密码。返回错误，null = 通过 */
    fun checkWizardStorage(saved: Form, new: Form, hasSecret: Boolean, secret: String): String? {
        if (new.backendBlank) return "请填写存储地址"
        val need = backendLocationChanged(saved, new) || (saved.backendBlank && !hasSecret)
        if (secret.isEmpty() && need) return if (new.s3) "请填写 Secret" else "请填写密码"
        return null
    }

    /** 第 2 步：存设置（启用）→ 存密码 → 测试连接 → 读状态（看是否待确认身份） */
    fun wizardTestSteps(form: Form, secret: String): List<Step> {
        val cmds = listOfNotNull(secretArgs(secret)?.let { Step.Command("set_secret", it) }) + Step.Command("test_connection")
        return saveSteps(form.copy(enabled = true), cmds) + Step.ReadStatus
    }

    /** 第 3 步：远端为空时生成同步密钥（不看输入框），已有时粘贴同步密钥加入 */
    fun wizardKeySteps(remoteInitialized: Boolean, key: String): Plan =
        if (remoteInitialized) importKeySteps(key) else Plan.Run(createKeySteps())

    /** 测试连接失败后能否「重试」（不再带密码）：这次没发新密码，或 set_secret（第 [secretStep] 步）已成功 */
    fun canRetryTest(secretStep: Int?, failedAt: Int): Boolean = secretStep == null || failedAt > secretStep

    // ───────────────────────────── 设备与分组 ─────────────────────────────

    /** 设备名称上限（字符数，与核心对齐） */
    const val DEVICE_NAME_MAX = 64

    /** 设备号长度上限（与核心对齐） */
    const val DEVICE_ID_MAX = 24

    /** 组名长度上限（与核心对齐） */
    const val GROUP_NAME_MAX = 64

    /** 本机所属的设备类别：核心（runner.rs 的 class_of_platform）只把 android 算作手机 */
    private const val MY_PLATFORM = "android"

    fun isSameClass(platform: String): Boolean = platform == MY_PLATFORM

    data class Device(
        val id: String,
        val name: String = "",
        val platform: String = "",
        val appVersion: String = "",
        val lastSeen: Long = 0,
        val isSelf: Boolean = false,
        /** false = 设备信息解不开（别的密钥写的或已损坏），名称为空 */
        val readable: Boolean = true,
        val group: String = "",
        val groupName: String = "",
    ) {
        val title: String get() = if (readable && name.isNotBlank()) name else "未知设备（无法解密）"

        /** 只有其他设备能移除 */
        val removable: Boolean get() = !isSelf

        /** 副标题：平台 · 版本 · 最后在线；不可读的设备没有平台与版本 */
        fun detail(today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): String {
            val parts = mutableListOf<String>()
            if (readable) {
                parts += platformText(platform)
                if (appVersion.isNotEmpty()) parts += "v" + appVersion.removePrefix("v")
            }
            parts += lastSeenText(lastSeen, today, zone)
            return parts.joinToString(" · ")
        }
    }

    fun platformText(p: String): String = when (p) {
        "windows" -> "Windows"
        "macos" -> "macOS"
        "linux" -> "Linux"
        "android" -> "Android"
        "ios" -> "iOS"
        "" -> "未知平台"
        else -> p
    }

    /** 「最后在线」：今天 / 昨天带时分，更早显示日期；ts <= 0 视为未知 */
    fun lastSeenText(ts: Long, today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): String {
        if (ts <= 0) return "最后在线未知"
        val t = Instant.ofEpochSecond(ts).atZone(zone)
        val hm = DateTimeFormatter.ofPattern("HH:mm")
        return when (ChronoUnit.DAYS.between(t.toLocalDate(), today)) {
            0L -> "今天 ${hm.format(t)}"
            1L -> "昨天 ${hm.format(t)}"
            else -> DateTimeFormatter.ofPattern("yyyy-MM-dd").format(t)
        }
    }

    /** 解析 `list_devices` 回执；本机排最前，其余按最后在线从新到旧 */
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
                group = o.optString("group"),
                groupName = o.optString("group_name"),
            )
        }.filter { it.id.isNotEmpty() }.sortedWith(selfThenRecent)
    } catch (e: JSONException) {
        emptyList()
    }

    private val selfThenRecent = compareByDescending<Device> { it.isSelf }.thenByDescending { it.lastSeen }

    data class Group(
        val id: String,
        /** 组内第一个非空组名；没有就用组号 */
        val name: String,
        val devices: List<Device>,
        val isMine: Boolean,
    )

    /** 按组归并设备：本组在前，其余组按组内最近活跃从新到旧；组内本机在前，其余同样按最近活跃 */
    fun groupDevices(devs: List<Device>, myGroup: String): List<Group> {
        val byId = LinkedHashMap<String, MutableList<Device>>()
        for (d in devs) byId.getOrPut(d.group) { mutableListOf() }.add(d)
        return byId.map { (id, list) ->
            val name = list.firstOrNull { it.groupName.isNotEmpty() }?.groupName ?: id
            Group(id, name, list.sortedWith(selfThenRecent), id == myGroup)
        }.sortedWith(
            compareByDescending<Group> { it.isMine }.thenByDescending { g -> g.devices.maxOfOrNull { it.lastSeen } ?: 0L },
        )
    }

    /** 本机区「同步组」的值：组名（没有就用组号）· N 台设备；名册还没到时不写台数 */
    fun groupLine(group: String, groupName: String, devs: List<Device>): String {
        val name = groupName.ifEmpty { group }
        val n = groupDevices(devs, group).firstOrNull { it.id == group }?.devices?.size ?: 0
        return if (n == 0) name else "$name · $n 台设备"
    }

    /** 「加入已有组」可选的组：与本机同类设备所在的其他组（不含本组、无组号的） */
    fun joinableGroups(myGroup: String, devs: List<Device>): List<Group> =
        groupDevices(devs.filter { it.readable && isSameClass(it.platform) }, myGroup)
            .filter { it.id.isNotEmpty() && it.id != myGroup }

    // ───────────────────────────── 本机身份 ─────────────────────────────

    /** 确认本机身份时可选的做法 */
    sealed interface IdentityChoice {
        /** 恢复为远端已有的某台设备（设备号） */
        data class Restore(val dev: String) : IdentityChoice
        object NewDevice : IdentityChoice {
            override fun toString() = "NewDevice"
        }

        /** 加入已有的组（组号） */
        data class Join(val group: String) : IdentityChoice
        object NewGroup : IdentityChoice {
            override fun toString() = "NewGroup"
        }
    }

    /**
     * 身份选项与默认项。远端已有本机设备号（同一台重装）→ 恢复或另作新设备，默认恢复；
     * 否则列出同类（android）设备可恢复、它们所在的组可加入、或新建组，默认新建组。
     */
    fun identityOptions(myId: String, devs: List<Device>): Pair<List<IdentityChoice>, IdentityChoice> {
        if (devs.any { it.readable && it.id == myId }) {
            val restore = IdentityChoice.Restore(myId)
            return listOf(restore, IdentityChoice.NewDevice) to restore
        }
        val same = devs.filter { it.readable && isSameClass(it.platform) }.sortedByDescending { it.lastSeen }
        val opts = mutableListOf<IdentityChoice>()
        same.mapTo(opts) { IdentityChoice.Restore(it.id) }
        groupDevices(same, "").filter { it.id.isNotEmpty() }.mapTo(opts) { IdentityChoice.Join(it.id) }
        opts += IdentityChoice.NewGroup
        return opts to IdentityChoice.NewGroup
    }

    /** 所选身份对应的命令；撞号时核心自动加后缀 */
    fun identityCommands(c: IdentityChoice): List<Step.Command> = when (c) {
        is IdentityChoice.Restore -> listOf(Step.Command("claim_device", JSONObject().put("dev", c.dev).toString()))
        IdentityChoice.NewDevice, IdentityChoice.NewGroup -> listOf(Step.Command("confirm_identity"))
        // 核心的 join_group 自己处理未确认的身份；先 confirm 会建出单人组，加入失败就卡住
        is IdentityChoice.Join -> listOf(
            Step.Command("join_group", JSONObject().put("group", c.group).put("mode", "theirs").toString()),
        )
    }

    /** 身份选项的界面形态 */
    data class IdentityView(
        /** 远端已有本机设备号时「恢复为 …（上次活跃：…）」的文案；空 = 列表形态 */
        val mine: String = "",
        val myId: String = "",
        /** 列表形态：可恢复的设备 (设备号, 文案)、可加入的组 (组号, 「组名 · N 台」) */
        val devices: List<Pair<String, String>> = emptyList(),
        val groups: List<Pair<String, String>> = emptyList(),
        /** 预选项：「本机」形态 0 = 恢复 / 1 = 作为新设备；列表形态 0 = 恢复 / 1 = 加入组 / 2 = 新建组 */
        val defaultMode: Int = 0,
        /** 预选项落在子列表里时的下标 */
        val defaultSub: Int = 0,
    )

    fun identityView(
        opts: List<IdentityChoice>,
        default: IdentityChoice,
        myId: String,
        devs: List<Device>,
        today: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
    ): IdentityView {
        val find = { id: String -> devs.firstOrNull { it.id == id } }
        if (IdentityChoice.NewDevice in opts) {
            val seen = find(myId)?.lastSeen ?: 0
            return IdentityView(
                mine = "恢复为 $myId（上次活跃：${lastSeenText(seen, today, zone)}）",
                myId = myId,
                defaultMode = if (default == IdentityChoice.NewDevice) 1 else 0,
            )
        }
        // 组的台数按名册里全部可读设备算（与设备区一致）：加入后同步的就是这一组
        val groups = groupDevices(devs.filter { it.readable }, "")
        val devItems = mutableListOf<Pair<String, String>>()
        val groupItems = mutableListOf<Pair<String, String>>()
        for (o in opts) {
            when (o) {
                is IdentityChoice.Restore -> find(o.dev)?.let { d ->
                    devItems += o.dev to "${d.title}（${o.dev}） · ${d.detail(today, zone)}"
                }
                is IdentityChoice.Join -> {
                    val g = groups.firstOrNull { it.id == o.group }
                    groupItems += o.group to "${g?.name ?: o.group} · ${g?.devices?.size ?: 0} 台"
                }
                else -> {}
            }
        }
        val (mode, sub) = when (default) {
            is IdentityChoice.Restore -> 0 to devItems.indexOfFirst { it.first == default.dev }.coerceAtLeast(0)
            is IdentityChoice.Join -> 1 to groupItems.indexOfFirst { it.first == default.group }.coerceAtLeast(0)
            else -> 2 to 0
        }
        return IdentityView(myId = myId, devices = devItems, groups = groupItems, defaultMode = mode, defaultSub = sub)
    }

    /** 界面上的选择 → 身份；下标对不上时不猜 */
    fun identityPick(v: IdentityView, mode: Int, dev: Int, group: Int): IdentityChoice? {
        if (v.mine.isNotEmpty()) {
            return when (mode) {
                0 -> IdentityChoice.Restore(v.myId)
                1 -> IdentityChoice.NewDevice
                else -> null
            }
        }
        return when (mode) {
            0 -> v.devices.getOrNull(dev)?.let { IdentityChoice.Restore(it.first) }
            1 -> v.groups.getOrNull(group)?.let { IdentityChoice.Join(it.first) }
            2 -> IdentityChoice.NewGroup
            else -> null
        }
    }

    // ───────────────────────────── 改号与更换组 ─────────────────────────────

    /** 校验并规整设备名：去首尾空白，超长拒绝（不静默截断）。返回 (规整后的值, 错误) */
    fun checkDeviceName(name: String): Pair<String, String?> {
        val n = name.trim()
        return if (n.codePointCount(0, n.length) > DEVICE_NAME_MAX) n to "设备名称不能超过 $DEVICE_NAME_MAX 个字" else n to null
    }

    /** 校验设备号：[a-z0-9-]、1–24 个、不以 - 开头或结尾 */
    fun checkDeviceId(id: String): Pair<String, String?> {
        val s = id.trim()
        val err = when {
            s.isEmpty() || s.length > DEVICE_ID_MAX -> "设备号要 1 到 $DEVICE_ID_MAX 个字符"
            !s.all { it in 'a'..'z' || it in '0'..'9' || it == '-' } -> "设备号只能用小写字母、数字和短横线"
            s.startsWith('-') || s.endsWith('-') -> "设备号不能以短横线开头或结尾"
            else -> null
        }
        return s to err
    }

    /** 改号保存的步骤：设备号变了先 `rename_device`，显示名称变了再写设置；都没变为空 */
    fun renameSteps(curId: String, newId: String, saved: Form, newName: String): Plan {
        val (id, idErr) = checkDeviceId(newId)
        if (idErr != null) return Plan.Invalid(idErr)
        val (name, nameErr) = checkDeviceName(newName)
        if (nameErr != null) return Plan.Invalid(nameErr)
        val steps = mutableListOf<Step>()
        if (id != curId) steps += Step.Command("rename_device", JSONObject().put("id", id).toString())
        if (name != saved.deviceName.trim()) steps += Step.SetSettings(saved.copy(deviceName = name).toSettingsJson())
        return Plan.Run(steps)
    }

    /** 改号已成功而显示名称没存上时，要让用户知道 */
    fun renameFailure(index: Int, e: String): String = if (index > 0) "设备号已改，显示名称未保存（$e）" else e

    /** 校验组名：去首尾空白，超长拒绝；[required] 时不能为空 */
    fun checkGroupName(name: String, required: Boolean): Pair<String, String?> {
        val n = name.trim()
        val err = when {
            required && n.isEmpty() -> "组名不能为空"
            n.codePointCount(0, n.length) > GROUP_NAME_MAX -> "组名不能超过 $GROUP_NAME_MAX 个字"
            else -> null
        }
        return n to err
    }

    /** 更换组的三种做法 */
    sealed interface GroupChange {
        /** 加入已有组；[theirs] = 用组内设置替换本机，否则用本机替换组内 */
        data class Join(val group: String, val theirs: Boolean) : GroupChange

        /** 退出成新的独立组，组名可空 */
        data class NewGroup(val name: String) : GroupChange
        data class Rename(val name: String) : GroupChange
    }

    fun groupChangeCommands(c: GroupChange): List<Step.Command> = when (c) {
        is GroupChange.Join -> listOf(
            Step.Command("join_group", JSONObject().put("group", c.group).put("mode", if (c.theirs) "theirs" else "mine").toString()),
        )
        is GroupChange.NewGroup -> listOf(
            Step.Command("leave_group", if (c.name.isEmpty()) "{}" else JSONObject().put("name", c.name).toString()),
        )
        is GroupChange.Rename -> listOf(Step.Command("set_group_name", JSONObject().put("name", c.name).toString()))
    }

    /** 加入已有组前的确认文案 */
    fun joinConfirmText(theirs: Boolean): String =
        if (theirs) "本机设置将被组内设置替换（已备份）" else "组内其他设备的设置将被本机替换"

    /** 本机名称默认取机型名：首次进页就写下，别的设备的列表里才认得出 */
    fun defaultDeviceName(current: String, model: String): String = current.trim().ifEmpty { model.trim() }
}
