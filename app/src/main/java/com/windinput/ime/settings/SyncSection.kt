package com.windinput.ime.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.windinput.ime.WindInputApp
import com.windinput.ime.core.SyncHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import uniffi.wind_android.MobileSyncHandle
import uniffi.wind_android.SyncException
import com.windinput.ime.settings.SyncModel.BackendType

/** 状态刷新间隔：同步线程在后台推进，页面开着时轮询一次状态快照（很轻） */
private const val POLL_MS = 2000L

/** 危险操作的二次确认：与词库「清空」同一套节奏 */
private const val CONFIRM_KEY_RESET = "reset"

/**
 * 数据同步页：与电脑版「数据同步」分区同一套能力（WebDAV / S3、口令加密、设备名册），
 * 同步逻辑全在核心（进程内运行，见 [SyncHub]），这里只管输入与展示。
 * 所有命令都在后台线程调（最长等 60 秒），结果回主线程刷新。机密（密码、口令）只写不回显。
 */
@Composable
internal fun SyncSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ui = remember { SyncUiState() }

    LaunchedEffect(Unit) {
        ui.reload(context)
        while (isActive) {
            delay(POLL_MS)
            ui.refreshStatus(context)
        }
    }

    val st = ui.status
    val now = System.currentTimeMillis() / 1000

    // ── 状态 ──
    SectionTitle("状态")
    Card(modifier = Modifier.fillMaxWidth()) {
        NavRow("当前状态", SyncModel.statusSummary(st, now), icon = SettingsIcons.backup)
        if (st.lastError.isNotEmpty() && st.errorKind != SyncModel.ErrorKind.NONE) {
            NavRow(
                when (st.errorKind) {
                    SyncModel.ErrorKind.MASS_DELETE -> "等待确认：大批量删除"
                    SyncModel.ErrorKind.NOTICE -> "提示"
                    else -> "出错"
                },
                st.lastError,
                highlight = st.errorKind == SyncModel.ErrorKind.DANGER || st.errorKind == SyncModel.ErrorKind.MASS_DELETE,
            )
        }
        if (st.conflicts > 0) NavRow("有 ${st.conflicts} 个文件冲突", "两台设备同时改了同一文件，已保留双方版本", null)
        if (st.errorKind == SyncModel.ErrorKind.MASS_DELETE) {
            NavRow("确认这些删除是真的，继续同步", "同步为防误删暂停了：本机或远端有大量文件被删", icon = SettingsIcons.clear) {
                ui.command(scope, context, "confirm_mass_delete", "", "已确认，下一轮同步放行")
            }
        }
        NavRow(
            "立即同步",
            if (ui.busy) "处理中…" else if (st.canSyncNow) "现在就同步一次" else "启用并配置好存储、口令后可用",
            icon = SettingsIcons.refresh,
        ) { if (!ui.busy && st.canSyncNow) ui.command(scope, context, "sync_now", "", "已开始同步") }
    }
    ui.message?.let { Text(text = it, modifier = Modifier.padding(horizontal = 16.dp), color = MiuixTheme.colorScheme.onSurfaceVariantSummary) }

    Card(modifier = Modifier.fillMaxWidth()) {
        SwitchPreference(
            checked = ui.draft.enabled,
            onCheckedChange = { ui.saveDraft(scope, context, ui.draft.copy(enabled = it)) },
            title = "启用数据同步",
            summary = "键盘弹出时按需同步（至少隔 10 分钟）；同步内容在本机加密后才上传",
        )
    }

    // ── 后端 ──
    SectionTitle("存储服务")
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row2(
                "WebDAV" to (ui.draft.type == BackendType.WEBDAV),
                "S3 兼容" to (ui.draft.type == BackendType.S3),
            ) { ui.draft = ui.draft.copy(type = if (it == 0) BackendType.WEBDAV else BackendType.S3) }
            val d = ui.draft
            if (d.type == BackendType.S3) {
                Field("Endpoint（如 https://s3.example.com）", d.endpoint) { ui.draft = d.copy(endpoint = it) }
                Field("Region（可空）", d.region) { ui.draft = d.copy(region = it) }
                Field("Bucket", d.bucket) { ui.draft = d.copy(bucket = it) }
                Field("路径前缀（可空）", d.prefix) { ui.draft = d.copy(prefix = it) }
                Field("Access Key ID", d.accessKeyId) { ui.draft = d.copy(accessKeyId = it) }
            } else {
                Field("地址（如 https://dav.example.com/wind）", d.url) { ui.draft = d.copy(url = it) }
                Field("用户名", d.user) { ui.draft = d.copy(user = it) }
            }
            Field(SyncModel.passwordHint(d.type, st.hasSecret), ui.password, secret = true) { ui.password = it }
        }
        if (ui.draft.type == BackendType.S3) {
            SwitchPreference(
                checked = ui.draft.pathStyle,
                onCheckedChange = { ui.draft = ui.draft.copy(pathStyle = it) },
                title = "路径风格寻址",
                summary = "MinIO 等自建服务通常要开",
            )
        }
        Column(modifier = Modifier.padding(12.dp)) {
            TextButton(
                text = "保存并测试连接",
                modifier = Modifier.fillMaxWidth(),
                onClick = { if (!ui.busy) ui.saveAndTest(scope, context) },
            )
        }
    }

    // ── 范围 ──
    SectionTitle("同步范围")
    Card(modifier = Modifier.fillMaxWidth()) {
        val d = ui.draft
        SyncSwitch(d.dicts, "词库与短语", "个人词库、命令短语；与电脑共用") { ui.saveDraft(scope, context, d.copy(dicts = it)) }
        SyncSwitch(d.schemas, "方案", "导入的方案与方案覆盖；与电脑共用") { ui.saveDraft(scope, context, d.copy(schemas = it)) }
        SyncSwitch(d.config, "配置", "只在手机之间同步，不会影响电脑上的配置") { ui.saveDraft(scope, context, d.copy(config = it)) }
        SyncSwitch(d.themes, "主题", "只在手机之间同步，不会影响电脑上的主题") { ui.saveDraft(scope, context, d.copy(themes = it)) }
    }

    // ── 加密 ──
    SectionTitle("加密")
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = if (st.joined) "本机已加入加密同步。" else "首台设备设置口令；其他设备用同一口令加入，或导入密钥。",
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Field("口令", ui.pass, secret = true) { ui.pass = it }
            Field("再输一次口令", ui.passConfirm, secret = true) { ui.passConfirm = it }
            Field("旧口令（修改口令时填）", ui.oldPass, secret = true) { ui.oldPass = it }
            TextButton(
                text = if (st.joined) "修改口令" else "设置 / 加入口令",
                modifier = Modifier.fillMaxWidth(),
                onClick = { if (!ui.busy) ui.setPassphrase(scope, context) },
            )
            Field("密钥（从另一台设备导出）", ui.keyText, secret = true) { ui.keyText = it }
            TextButton(
                text = "导入密钥",
                modifier = Modifier.fillMaxWidth(),
                onClick = { if (!ui.busy) ui.importKey(scope, context) },
            )
        }
        NavRow("导出密钥", "复制到剪贴板，用来让别的设备免口令加入；请妥善保管", icon = SettingsIcons.export) {
            if (!ui.busy) ui.exportKey(scope, context)
        }
        NavRow(
            if (ui.confirming(CONFIRM_KEY_RESET)) "再点一次确认重置" else "重置远端",
            if (ui.confirming(CONFIRM_KEY_RESET)) "清空远端全部同步数据，以本机为准重新上传；用上面的「口令」作为新口令" else "清空远端并以本机为准重新开始",
            icon = SettingsIcons.clear,
            highlight = ui.confirming(CONFIRM_KEY_RESET),
        ) {
            if (!ui.busy && ui.confirmTap(scope, CONFIRM_KEY_RESET)) ui.resetRemote(scope, context)
        }
    }

    // ── 设备 ──
    SectionTitle("我的设备")
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Field("本机名称", ui.draft.deviceName) { ui.draft = ui.draft.copy(deviceName = it) }
            TextButton(
                text = "保存名称",
                modifier = Modifier.fillMaxWidth(),
                onClick = { if (!ui.busy) ui.saveDraft(scope, context, ui.draft) },
            )
        }
        NavRow("刷新设备列表", "列出同步库里的所有设备", icon = SettingsIcons.refresh) {
            if (!ui.busy) ui.listDevices(scope, context)
        }
        for (dev in ui.devices) {
            val key = "rm:${dev.id}"
            val summary = buildString {
                append(dev.platform.ifEmpty { "未知平台" })
                if (dev.appVersion.isNotEmpty()) append(" · ").append(dev.appVersion)
                append(" · ").append(SyncModel.ago(dev.lastSeen, now))
            }
            if (dev.removable) {
                NavRow(
                    dev.title,
                    if (ui.confirming(key)) "再点一次确认移除：删除它在远端的数据" else "$summary · 点按移除",
                    highlight = ui.confirming(key),
                ) {
                    if (!ui.busy && ui.confirmTap(scope, key)) ui.removeDevice(scope, context, dev.id)
                }
            } else {
                NavRow("${dev.title}（本机）", summary, null)
            }
        }
    }
    Text(
        text = "移除他机只清理它在远端的记录；那台设备若仍在运行，下次同步会重新出现。",
        modifier = Modifier.padding(horizontal = 16.dp),
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
    )
}

@Composable
private fun SyncSwitch(checked: Boolean, title: String, summary: String, onChange: (Boolean) -> Unit) {
    SwitchPreference(checked = checked, onCheckedChange = onChange, title = title, summary = summary)
}

@Composable
private fun Field(label: String, value: String, secret: Boolean = false, onChange: (String) -> Unit) {
    TextField(
        value = value,
        onValueChange = onChange,
        label = label,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
    )
}

/** 两个并排的单选按钮；选中的一个用主色 */
@Composable
private fun Row2(a: Pair<String, Boolean>, b: Pair<String, Boolean>, onPick: (Int) -> Unit) {
    androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(a, b).forEachIndexed { i, (label, on) ->
            TextButton(
                text = if (on) "✓ $label" else label,
                modifier = Modifier.weight(1f),
                onClick = { onPick(i) },
            )
        }
    }
}

/** 页面状态：草稿、状态快照、设备列表、忙标记与二次确认。命令都在 IO 线程跑 */
private class SyncUiState {
    var draft by mutableStateOf(SyncModel.Draft())
    var status by mutableStateOf(SyncModel.Status())
    var devices by mutableStateOf<List<SyncModel.Device>>(emptyList())
    var busy by mutableStateOf(false)
    var message by mutableStateOf<String?>(null)

    // 机密输入：只写不回显，提交成功后清空，不进 draft
    var password by mutableStateOf("")
    var pass by mutableStateOf("")
    var passConfirm by mutableStateOf("")
    var oldPass by mutableStateOf("")
    var keyText by mutableStateOf("")

    private var baseJson = "{}"
    private var confirmKey by mutableStateOf<String?>(null)
    private var confirmAt = 0L

    fun confirming(key: String) = confirmKey == key

    /** 二次确认：第一下进入待确认（5 秒后复位），隔 [CONFIRM_MIN_GAP_MS] 之后的第二下返回 true */
    fun confirmTap(scope: CoroutineScope, key: String): Boolean {
        val now = SystemClock.uptimeMillis()
        if (confirmKey != key) {
            confirmKey = key
            confirmAt = now
            scope.launch {
                delay(CONFIRM_RESET_MS)
                if (confirmKey == key) confirmKey = null
            }
            return false
        }
        if (now - confirmAt < CONFIRM_MIN_GAP_MS) return false
        confirmKey = null
        return true
    }

    private fun handle(context: Context): MobileSyncHandle? {
        val core = CoreAccess.obtainCore(context) ?: return null
        return SyncHub.get(core, (context.applicationContext as WindInputApp).dataDirs)
    }

    /** [keepDraft]：操作失败时保留用户正在编辑的草稿，只刷新基线与状态 */
    suspend fun reload(context: Context, keepDraft: Boolean = false) {
        val loaded = withContext(Dispatchers.IO) {
            handle(context)?.let { h ->
                // 本机名称默认取机型名：首次进页就写下，别的设备的列表里才认得出
                var json = h.getSettings()
                val d = SyncModel.parseSettings(json)
                if (d.deviceName.isBlank()) {
                    val named = SyncModel.defaultDeviceName(d.deviceName, Build.MODEL)
                    if (named.isNotEmpty()) {
                        runCatching { h.setSettings(SyncModel.toSettingsJson(json, d.copy(deviceName = named))) }
                        json = h.getSettings()
                    }
                }
                json to h.status()
            }
        }
        if (loaded == null) {
            message = "输入法核心未就绪，同步不可用"
            return
        }
        baseJson = loaded.first
        if (!keepDraft) draft = SyncModel.parseSettings(loaded.first)
        status = SyncModel.parseStatus(loaded.second)
    }

    suspend fun refreshStatus(context: Context) {
        val s = withContext(Dispatchers.IO) { handle(context)?.status() } ?: return
        status = SyncModel.parseStatus(s)
    }

    /** 在后台线程跑一件事，成功 / 失败的文案进 [message]；结束后刷新状态 */
    private fun launchOp(scope: CoroutineScope, context: Context, op: (MobileSyncHandle) -> String?) {
        busy = true
        message = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                val h = handle(context) ?: return@withContext "输入法核心未就绪" to true
                try {
                    (op(h) ?: "") to false
                } catch (e: SyncException) {
                    ((e as? SyncException.Failed)?.reason ?: e.toString()) to true
                } catch (e: Exception) {
                    "操作失败：${e.message ?: e}" to true
                }
            }
            busy = false
            message = result.first.ifEmpty { null }
            runCatching { reload(context, keepDraft = result.second) }
        }
    }

    fun saveDraft(scope: CoroutineScope, context: Context, d: SyncModel.Draft) {
        draft = d
        launchOp(scope, context) { h ->
            h.setSettings(SyncModel.toSettingsJson(baseJson, d))
            "已保存"
        }
    }

    fun command(scope: CoroutineScope, context: Context, op: String, args: String, done: String) {
        launchOp(scope, context) { h ->
            h.command(op, args)
            done
        }
    }

    fun saveAndTest(scope: CoroutineScope, context: Context) {
        val d = draft
        val pw = password
        if (!SyncModel.backendReady(d)) {
            message = "请先填好存储服务的地址（S3 还需 Bucket 与 Access Key ID）"
            return
        }
        launchOp(scope, context) { h ->
            h.setSettings(SyncModel.toSettingsJson(baseJson, d))
            SyncModel.secretArgs(pw)?.let { h.command("set_secret", it) }
            val r = h.command("test_connection", "")
            password = ""
            val init = runCatching { org.json.JSONObject(r).optBoolean("remote_initialized") }.getOrDefault(false)
            if (init) "连接正常；远端已有同步数据，请用口令加入" else "连接正常；远端还是空的，设置口令即可开始"
        }
    }

    fun setPassphrase(scope: CoroutineScope, context: Context) {
        SyncModel.checkPassphrase(pass, passConfirm)?.let {
            message = it
            return
        }
        val args = SyncModel.passphraseArgs(pass, oldPass)
        launchOp(scope, context) { h ->
            val r = h.command("set_passphrase", args)
            pass = ""
            passConfirm = ""
            oldPass = ""
            when (runCatching { org.json.JSONObject(r).optString("mode") }.getOrDefault("")) {
                "init" -> "口令已设置，远端已初始化"
                "join" -> "已用口令加入同步"
                "change" -> "口令已修改"
                else -> "口令已设置"
            }
        }
    }

    fun importKey(scope: CoroutineScope, context: Context) {
        val key = keyText
        if (key.isBlank()) {
            message = "请粘贴密钥"
            return
        }
        launchOp(scope, context) { h ->
            h.command("import_key", SyncModel.importKeyArgs(key))
            keyText = ""
            "密钥已导入，本机已加入同步"
        }
    }

    fun exportKey(scope: CoroutineScope, context: Context) {
        launchOp(scope, context) { h ->
            val key = SyncModel.parseExportedKey(h.command("export_key", "")) ?: return@launchOp "没有可导出的密钥"
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("sync-key", key)
            // 标记敏感：系统剪贴板预览与别的剪贴板工具不显示内容
            clip.description.extras = android.os.PersistableBundle().apply {
                putBoolean("android.content.extra.IS_SENSITIVE", true)
            }
            cm.setPrimaryClip(clip)
            "密钥已复制到剪贴板，粘贴到别的设备后请清空剪贴板"
        }
    }

    fun resetRemote(scope: CoroutineScope, context: Context) {
        val p = pass
        if (p.isEmpty()) {
            message = "重置后需要新口令：先在「口令」里填好"
            return
        }
        launchOp(scope, context) { h ->
            h.command("reset_remote", SyncModel.resetArgs(p))
            pass = ""
            passConfirm = ""
            "远端已重置，本机内容将重新上传"
        }
    }

    fun listDevices(scope: CoroutineScope, context: Context) {
        launchOp(scope, context) { h ->
            devices = SyncModel.parseDevices(h.command("list_devices", ""))
            if (devices.isEmpty()) "没有设备" else null
        }
    }

    fun removeDevice(scope: CoroutineScope, context: Context, id: String) {
        launchOp(scope, context) { h ->
            h.command("remove_device", SyncModel.removeDeviceArgs(id))
            devices = SyncModel.parseDevices(h.command("list_devices", ""))
            "已移除"
        }
    }
}
