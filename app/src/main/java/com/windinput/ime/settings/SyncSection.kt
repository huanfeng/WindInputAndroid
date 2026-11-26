package com.windinput.ime.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.windinput.ime.WindInputApp
import com.windinput.ime.core.SyncHub
import com.windinput.ime.settings.SyncModel.Form
import com.windinput.ime.settings.SyncModel.IdentityView
import com.windinput.ime.settings.SyncModel.Plan
import com.windinput.ime.settings.SyncModel.Step
import com.windinput.ime.settings.SyncModel.StorageStage
import com.windinput.ime.settings.SyncModel.WizStep
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.RadioButtonLocation
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import uniffi.wind_android.MobileSyncHandle
import uniffi.wind_android.SyncException

/** 状态刷新间隔：同步线程在后台推进，页面开着时轮询一次状态快照（很轻） */
private const val POLL_MS = 2000L

/**
 * 数据同步页：与电脑版「数据同步」同一套流程与文案（`wind-setting/src/pages/sync/`），纯逻辑在 [SyncModel]。
 *
 * - 未加入同步库：只有一句介绍与「开始配置…」；
 * - 已加入：状态、本机、设备、存储、安全几个区块，另有「重新配置…」。
 *
 * 电脑版的对话框（向导、更换存储、确认身份、改号、换组、查看密钥、重置远端）在这里是整页的「流程」：
 * 打开时替换页面内容，系统返回键先关流程；新生成的同步密钥没勾「我已保存」时关不掉。
 * 同步逻辑全在核心（进程内运行，见 [SyncHub]）。所有命令都在后台线程调（最长等 60 秒）。
 * 机密（存储密码、Secret、同步密钥）只在输入框状态里，提交后立刻清空，不写日志。
 */
@Composable
internal fun SyncSection() {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val ui = remember { SyncUiState(context, scope) }

    LaunchedEffect(Unit) {
        ui.reload()
        while (isActive) {
            delay(POLL_MS)
            // 首次读取失败（核心还没就绪）时接着重试，别一直停在「读取中…」
            if (ui.loaded) ui.refreshStatus() else ui.reload()
        }
    }
    BackHandler(enabled = ui.flow != null) { ui.closeFlow() }

    when (ui.flow) {
        null -> MainPage(ui)
        Flow.WIZARD -> WizardFlow(ui)
        Flow.STORAGE -> StorageFlow(ui)
        Flow.IDENTITY -> IdentityFlow(ui)
        Flow.RENAME -> RenameFlow(ui)
        Flow.GROUP -> GroupFlow(ui)
        Flow.KEY_VIEW -> KeyViewFlow(ui)
        Flow.RESET -> ResetFlow(ui)
    }
}

/** 替换页面内容的流程（对应电脑版的各个对话框） */
private enum class Flow { WIZARD, STORAGE, IDENTITY, RENAME, GROUP, KEY_VIEW, RESET }

// ───────────────────────────── 主页面 ─────────────────────────────

@Composable
private fun MainPage(ui: SyncUiState) {
    val st = ui.status
    if (!ui.loaded) {
        Muted("读取中…")
        MessageLine(ui)
        return
    }
    if (!st.configured) {
        Card(modifier = Modifier.fillMaxWidth()) {
            NavRow("开始配置…", SyncModel.INTRO, icon = SettingsIcons.backup) {
                if (!ui.busy) ui.openWizard()
            }
        }
        MessageLine(ui)
        return
    }

    // ── 向导 ──
    SectionTitle("向导")
    Card(modifier = Modifier.fillMaxWidth()) {
        NavRow("重新配置…", "已配置；要换一个同步库可重新配置") { if (!ui.busy) ui.openWizard() }
    }

    // ── 状态 ──
    SectionTitle("状态")
    Card(modifier = Modifier.fillMaxWidth()) {
        SwitchPreference(
            checked = st.enabled,
            onCheckedChange = { ui.setMaster(it) },
            title = "数据同步",
            summary = "关闭后暂停同步，配置都保留",
        )
        val canSync = SyncModel.canSyncNow(st) && !ui.busy
        NavRow(
            "立即同步",
            "${SyncModel.statusValue(st)} · ${SyncModel.pendingText(st)}",
            icon = SettingsIcons.refresh,
            onClick = if (canSync) ({ ui.syncNow() }) else null,
        )
        if (st.needsIdentity) {
            NavRow("需要确认本机身份", "确认之前本机不会同步", highlight = true) {
                if (!ui.busy) ui.openIdentity()
            }
        }
        when (st.errorKind) {
            SyncModel.ErrorKind.DANGER -> {
                NavRow("出错", st.lastError, highlight = true)
                if (st.duplicateDevice) NavRow("给本机改号…", "另一台设备在用本机的设备号") { ui.openRename() }
            }
            SyncModel.ErrorKind.NOTICE -> NavRow("提示", "${st.lastError}。会自动重试，无需处理")
            SyncModel.ErrorKind.MASS_DELETE -> {
                NavRow("同步已暂停", st.lastError, highlight = true)
                val armed = ui.confirming(CONFIRM_MASS)
                NavRow(
                    if (armed) "再点一次确认删除并同步" else "确认删除…",
                    SyncModel.MASS_DELETE_WARN,
                    icon = SettingsIcons.clear,
                    highlight = armed,
                ) { if (!ui.busy && ui.confirmTap(CONFIRM_MASS)) ui.confirmMassDelete() }
            }
            SyncModel.ErrorKind.NONE -> {}
        }
        NavRow("上次成功", SyncModel.lastOkText(st.lastOk))
        if (st.conflicts > 0) {
            NavRow(
                "冲突",
                "有 ${st.conflicts} 个冲突副本：两台设备改了同一个文件，另一份已另存为文件名含 .conflict- 的副本，请比对后手动保留需要的一份并删除另一份",
            )
        }
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TextField(
                value = ui.intervalText,
                onValueChange = { ui.intervalText = it },
                label = "同步间隔（分钟）",
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            Muted(SyncModel.INTERVAL_DESC)
            TextButton(
                text = "保存同步间隔",
                enabled = !ui.busy && ui.intervalText.trim() != ui.saved.intervalMin.toString(),
                modifier = Modifier.fillMaxWidth(),
                onClick = { ui.saveInterval() },
            )
        }
    }
    MessageLine(ui)

    // ── 本机 ──
    SectionTitle("本机")
    Card(modifier = Modifier.fillMaxWidth()) {
        NavRow("显示名称", ui.saved.deviceName.ifBlank { "未设置" }) { if (!ui.busy) ui.openRename() }
        NavRow("设备号", st.device.ifEmpty { "读取中…" }) { if (!ui.busy) ui.openRename() }
        val line = SyncModel.groupLine(st.group, st.groupName, ui.devices)
        if (st.needsIdentity) {
            // 身份未确认时核心拒绝换组
            NavRow("同步组", "$line（确认本机身份后可修改）")
        } else {
            NavRow("同步组", line) { if (!ui.busy) ui.openGroup() }
        }
    }

    // ── 设备 ──
    SectionTitle("设备")
    Card(modifier = Modifier.fillMaxWidth()) {
        if (ui.devicesErr.isNotEmpty()) NavRow("读取设备列表失败", ui.devicesErr, highlight = true)
        val today = LocalDate.now()
        for (g in SyncModel.groupDevices(ui.devices, st.group)) {
            Text(
                text = "${g.name} · ${g.devices.size} 台",
                modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            for (d in g.devices) {
                if (!d.removable) {
                    NavRow("${d.title}（本机）", d.detail(today))
                } else {
                    val key = "rm:${d.id}"
                    val armed = ui.confirming(key)
                    NavRow(
                        d.title,
                        if (armed) "再点一次确认移除。${SyncModel.REMOVE_DEVICE_WARN}" else "${d.detail(today)} · 点按移除",
                        highlight = armed,
                    ) { if (!ui.busy && ui.confirmTap(key)) ui.removeDevice(d.id) }
                }
            }
        }
        NavRow("刷新设备列表", null, icon = SettingsIcons.refresh) { ui.loadDevices() }
    }

    // ── 存储 ──
    SectionTitle("存储")
    Card(modifier = Modifier.fillMaxWidth()) {
        NavRow("更换存储…", SyncModel.storageSummary(ui.saved)) { if (!ui.busy) ui.openStorage() }
    }

    // ── 安全 ──
    SectionTitle("安全")
    Card(modifier = Modifier.fillMaxWidth()) {
        NavRow("同步密钥 / 查看…", "其他设备加入时要粘贴它；请离线保存", icon = SettingsIcons.privacy) {
            if (!ui.busy) ui.openKeyView()
        }
        NavRow("重置远端…", SyncModel.RESET_DESC, icon = SettingsIcons.clear) { if (!ui.busy) ui.openReset() }
    }
}

private const val CONFIRM_MASS = "mass"

// ───────────────────────────── 流程 ─────────────────────────────

/** 设置数据同步：存储 → 测试连接 → 同步密钥 → 本机身份 → 完成 */
@Composable
private fun WizardFlow(ui: SyncUiState) {
    val step = ui.wizStep
    SectionTitle("设置数据同步" + (step.number?.let { "（$it / 4）" } ?: ""))
    Card(modifier = Modifier.fillMaxWidth()) {
        Body {
            when (step) {
                WizStep.STORAGE -> {
                    Head("连接存储", "同步数据加密后存放在你自己的 WebDAV 或 S3 兼容存储里。")
                    StorageFields(ui)
                }
                WizStep.TEST -> {
                    Head("测试连接", "保存存储设置，并检查能否连上、存储里有没有同步数据。")
                    when {
                        ui.busy -> Muted("正在连接…")
                        ui.wizTestOk -> Text(text = ui.wizTestText, color = MiuixTheme.colorScheme.primary)
                        else -> {
                            Muted("连不上时点「上一步」检查地址与密码，或稍后重试。")
                            if (ui.wizCanRetry) {
                                TextButton(text = "重试", modifier = Modifier.fillMaxWidth(), onClick = { ui.retryTest() })
                            }
                        }
                    }
                }
                WizStep.KEY -> if (!ui.wizRemoteInit) {
                    Head("生成同步密钥", "同步数据在本机用同步密钥加密后才上传，存储服务看不到内容。")
                    if (ui.newKey.isEmpty()) {
                        Muted("存储里还没有同步库。生成同步密钥后，本机就是第一台设备；其他设备加入时粘贴这串密钥。")
                        TextButton(
                            text = "生成同步密钥",
                            enabled = !ui.busy,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { ui.wizardNext() },
                        )
                    }
                } else {
                    Head("加入同步库", "存储里已有同步库。粘贴在其他设备上看到的同步密钥（「安全」→「同步密钥 / 查看…」）。")
                    KeyInput(ui.importKey) { ui.importKey = it }
                }
                WizStep.IDENTITY -> {
                    Head("本机身份", "存储里已有设备。选择本机以什么身份加入；确认之前本机不会同步。")
                }
                WizStep.DONE -> Head("设置完成", ui.wizDone)
            }
            ErrorLine(ui.wizErr)
        }
        if (step == WizStep.KEY && !ui.wizRemoteInit && ui.newKey.isNotEmpty()) NewKeyBlock(ui)
        if (step == WizStep.IDENTITY) IdentityOptions(ui)
    }
    val primary = when (step) {
        WizStep.IDENTITY -> "确定"
        WizStep.DONE -> "完成"
        else -> "下一步"
    }
    val primaryEnabled = !ui.busy && when (step) {
        WizStep.TEST -> ui.wizTestOk
        // 远端为空：用正文的「生成同步密钥」，生成后勾了「我已保存」才能下一步
        WizStep.KEY -> ui.wizRemoteInit || (ui.newKey.isNotEmpty() && ui.keyAck)
        WizStep.IDENTITY -> ui.idLoaded
        else -> true
    }
    Buttons {
        if (step != WizStep.DONE && ui.newKey.isEmpty()) Btn("取消", Modifier.weight(1f), enabled = !ui.keyPending) { ui.closeFlow() }
        if (SyncModel.backStep(step) != null && ui.newKey.isEmpty()) {
            Btn("上一步", Modifier.weight(1f), enabled = !ui.busy) { ui.wizardBack() }
        }
        Btn(primary, Modifier.weight(1f), enabled = primaryEnabled) { ui.wizardNext() }
    }
    MessageLine(ui)
}

/** 更换存储：先填参数并测试新位置；没有同步库就新建并显示新密钥，已有就粘贴同步密钥加入，已加入直接完成 */
@Composable
private fun StorageFlow(ui: SyncUiState) {
    val stage = ui.storageStage
    SectionTitle("更换存储")
    Card(modifier = Modifier.fillMaxWidth()) {
        Body {
            when (stage) {
                StorageStage.FORM -> {
                    Muted("换到别的存储位置时要重填该位置的密码。保存后会检查新位置：还没有同步库就在那里新建，已有就粘贴它的同步密钥加入。原存储里的数据不会被删除。")
                    StorageFields(ui)
                }
                StorageStage.CREATE ->
                    Muted("设置已保存。新位置还没有同步库：创建后本机的数据会上传到这里，并生成新的同步密钥，其他设备要用新密钥加入。")
                StorageStage.JOIN -> {
                    Muted("设置已保存。新位置已有同步库：粘贴它的同步密钥加入（可在已加入的设备上「安全」→「同步密钥 / 查看…」）。")
                    KeyInput(ui.importKey) { ui.importKey = it }
                }
                StorageStage.CREATED -> Text(text = "已在新位置创建同步库。新的同步密钥：", color = MiuixTheme.colorScheme.primary)
            }
            ErrorLine(ui.storageErr)
        }
        if (stage == StorageStage.CREATED) NewKeyBlock(ui)
    }
    Buttons {
        if (stage != StorageStage.CREATED) Btn("取消", Modifier.weight(1f), enabled = !ui.keyPending) { ui.closeFlow() }
        when (stage) {
            StorageStage.FORM -> Btn("保存", Modifier.weight(1f), enabled = !ui.busy) { ui.saveStorage() }
            StorageStage.CREATE -> Btn("在新位置创建同步库", Modifier.weight(1f), enabled = !ui.busy) { ui.createStorageLibrary() }
            StorageStage.JOIN -> Btn("加入", Modifier.weight(1f), enabled = !ui.busy) { ui.joinStorage() }
            StorageStage.CREATED -> Btn("完成", Modifier.weight(1f), enabled = ui.keyAck) { ui.closeFlow() }
        }
    }
    MessageLine(ui)
}

/** 状态区「需要确认本机身份」打开：内容同向导第 4 步 */
@Composable
private fun IdentityFlow(ui: SyncUiState) {
    SectionTitle("确认本机身份")
    Card(modifier = Modifier.fillMaxWidth()) {
        Body { Muted("本机已加入同步，但还没确认身份，确认之前不会同步。选择本机以什么身份加入：") }
        IdentityOptions(ui)
    }
    Buttons {
        Btn("取消", Modifier.weight(1f)) { ui.closeFlow() }
        Btn("确定", Modifier.weight(1f), enabled = !ui.busy && ui.idLoaded) { ui.confirmIdentity() }
    }
    MessageLine(ui)
}

/** 身份选项（向导第 4 步与确认身份共用） */
@Composable
private fun IdentityOptions(ui: SyncUiState) {
    if (!ui.idLoaded) {
        Body {
            if (ui.busy) Muted("正在读取设备列表…")
            ErrorLine(ui.idErr)
            if (!ui.busy) TextButton(text = "重新读取", modifier = Modifier.fillMaxWidth(), onClick = { ui.loadIdentity() })
        }
        return
    }
    val v = ui.idView
    if (v.mine.isNotEmpty()) {
        Choice(v.mine, "沿用这台设备原来的设备号和同步组，用组内设置替换本机", ui.idMode == 0) { ui.idMode = 0 }
        Choice("作为新设备", "本机改用新的设备号（如 ${v.myId}-2），单独成组", ui.idMode == 1) { ui.idMode = 1 }
    } else {
        if (v.devices.isNotEmpty()) {
            Choice("恢复为已有设备", "沿用它的设备号和同步组，用组内设置替换本机", ui.idMode == 0) { ui.idMode = 0 }
            if (ui.idMode == 0) SubChoices(v.devices, ui.idDevPick) { ui.idDevPick = it }
        }
        if (v.groups.isNotEmpty()) {
            Choice("加入已有组", "用组内设置替换本机", ui.idMode == 1) { ui.idMode = 1 }
            if (ui.idMode == 1) SubChoices(v.groups, ui.idGroupPick) { ui.idGroupPick = it }
        }
        Choice("新建独立组", "本机单独成组，保留本机设置", ui.idMode == 2) { ui.idMode = 2 }
    }
    if (ui.idErr.isNotEmpty()) Body { ErrorLine(ui.idErr) }
}

@Composable
private fun RenameFlow(ui: SyncUiState) {
    SectionTitle("修改设备名称与设备号")
    Card(modifier = Modifier.fillMaxWidth()) {
        Body {
            Field("设备号（小写字母、数字、短横线，最长 24 个字符）", ui.renameId) { ui.renameId = it }
            Muted("设备号用来区分同一存储里的各台设备；与另一台设备撞号时，给本机换一个就行。")
            Field("显示名称（其他设备上看到的名字）", ui.renameName) { ui.renameName = it }
            ErrorLine(ui.renameErr)
        }
    }
    Buttons {
        Btn("取消", Modifier.weight(1f)) { ui.closeFlow() }
        Btn("保存", Modifier.weight(1f), enabled = !ui.busy) { ui.saveRename() }
    }
    MessageLine(ui)
}

/** 修改同步组：加入已有组（带替换方向与确认文案）/ 新建独立组 / 重命名本组 */
@Composable
private fun GroupFlow(ui: SyncUiState) {
    SectionTitle("修改同步组")
    Card(modifier = Modifier.fillMaxWidth()) {
        Choice("加入已有组", null, ui.groupMode == 0) { ui.groupMode = 0 }
        if (ui.groupMode == 0) {
            if (ui.groupChoices.isEmpty()) {
                Body { Muted("没有可加入的其他组") }
            } else {
                SubChoices(ui.groupChoices, ui.groupPick) { ui.groupPick = it }
                Body { Muted("以哪边的设置为准") }
                Choice("用组内设置替换本机", null, ui.groupMine == 0) { ui.groupMine = 0 }
                Choice("用本机设置替换组内", null, ui.groupMine == 1) { ui.groupMine = 1 }
                Body { Text(text = SyncModel.joinConfirmText(ui.groupMine == 0), color = MiuixTheme.colorScheme.error) }
            }
        }
        Choice("新建独立组", "本机退出当前组，单独成组", ui.groupMode == 1) { ui.groupMode = 1 }
        if (ui.groupMode == 1) Body { Field("组名（可不填）", ui.groupNewName) { ui.groupNewName = it } }
        Choice("重命名本组", "只改组的显示名，组内设备不变", ui.groupMode == 2) { ui.groupMode = 2 }
        if (ui.groupMode == 2) Body { Field("新的组名", ui.groupRename) { ui.groupRename = it } }
        if (ui.groupErr.isNotEmpty()) Body { ErrorLine(ui.groupErr) }
    }
    Buttons {
        Btn("取消", Modifier.weight(1f)) { ui.closeFlow() }
        Btn("确定", Modifier.weight(1f), enabled = !ui.busy) { ui.saveGroup() }
    }
    MessageLine(ui)
}

/** 查看同步密钥：回来前显示「正在读取…」；关闭即清掉 */
@Composable
private fun KeyViewFlow(ui: SyncUiState) {
    SectionTitle("同步密钥")
    Card(modifier = Modifier.fillMaxWidth()) {
        Body {
            Muted("其他设备加入同步时粘贴这串同步密钥。它能解开全部同步数据。")
            if (ui.exportedKey.isEmpty()) Muted("正在读取…") else KeyBox(ui, ui.exportedKey)
            Text(
                text = "请离线保存（如密码管理器），不要发到聊天或网盘。任何拿到它的人都能读取你的同步数据。",
                color = MiuixTheme.colorScheme.error,
            )
        }
    }
    Buttons { Btn("完成", Modifier.weight(1f)) { ui.closeFlow() } }
    MessageLine(ui)
}

/** 重置远端：先确认后果；成功后在同一页显示新的同步密钥，勾选已保存才能关 */
@Composable
private fun ResetFlow(ui: SyncUiState) {
    SectionTitle("重置远端")
    Card(modifier = Modifier.fillMaxWidth()) {
        if (ui.newKey.isEmpty()) {
            Body { Text(text = SyncModel.RESET_WARN, color = MiuixTheme.colorScheme.error) }
            SwitchPreference(checked = ui.resetAck, onCheckedChange = { ui.resetAck = it }, title = "我了解后果，确认重置远端")
        } else {
            Body { Text(text = "远端已重置，将以本机数据为准重新上传。新的同步密钥：", color = MiuixTheme.colorScheme.primary) }
            NewKeyBlock(ui)
        }
    }
    Buttons {
        if (ui.newKey.isEmpty()) {
            Btn("取消", Modifier.weight(1f), enabled = !ui.busy) { ui.closeFlow() }
            Btn("重置远端", Modifier.weight(1f), enabled = ui.resetAck && !ui.busy) { ui.confirmReset() }
        } else {
            Btn("完成", Modifier.weight(1f), enabled = ui.keyAck) { ui.closeFlow() }
        }
    }
    MessageLine(ui)
}

// ───────────────────────────── 小部件 ─────────────────────────────

@Composable
private fun Body(content: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

@Composable
private fun Buttons(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

@Composable
private fun Btn(text: String, modifier: Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    TextButton(text = text, enabled = enabled, modifier = modifier, onClick = onClick)
}

@Composable
private fun Head(title: String, lead: String) {
    Text(text = title)
    Muted(lead)
}

@Composable
private fun Muted(text: String) {
    Text(text = text, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
}

@Composable
private fun ErrorLine(text: String) {
    if (text.isNotEmpty()) Text(text = text, color = MiuixTheme.colorScheme.error)
}

/** 页内的操作结果提示 */
@Composable
private fun MessageLine(ui: SyncUiState) {
    ui.message?.let {
        Text(
            text = it,
            modifier = Modifier.padding(horizontal = 16.dp),
            color = if (ui.messageError) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

@Composable
private fun Field(label: String, value: String, secret: Boolean = false, onChange: (String) -> Unit) {
    TextField(
        value = value,
        onValueChange = onChange,
        label = label,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        // 密码键盘：别让输入法把存储密码记进联想或云端词库
        keyboardOptions = if (secret) {
            KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password)
        } else {
            KeyboardOptions.Default
        },
    )
}

/** 粘贴同步密钥的输入框（明文，便于核对） */
@Composable
private fun KeyInput(value: String, onChange: (String) -> Unit) {
    TextField(
        value = value,
        onValueChange = onChange,
        label = SyncModel.KEY_PLACEHOLDER,
        modifier = Modifier.fillMaxWidth(),
        singleLine = false,
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
    )
}

/** 单选的一行：选中项右侧打勾 */
@Composable
private fun Choice(title: String, summary: String?, selected: Boolean, onClick: () -> Unit) {
    RadioButtonPreference(
        title = title,
        summary = summary,
        selected = selected,
        onClick = onClick,
        radioButtonLocation = RadioButtonLocation.End,
    )
}

/** 选中某项后展开的子列表（设备或组） */
@Composable
private fun SubChoices(items: List<Pair<String, String>>, pick: Int, onPick: (Int) -> Unit) {
    Column(modifier = Modifier.padding(start = 24.dp)) {
        items.forEachIndexed { i, (_, label) -> Choice(label, null, pick == i) { onPick(i) } }
    }
}

/** 显示同步密钥：等宽两行 + 「复制」（复制的是原串） */
@Composable
private fun KeyBox(ui: SyncUiState, key: String) {
    Text(text = SyncModel.keyLines(key), fontFamily = FontFamily.Monospace)
    TextButton(text = "复制", modifier = Modifier.fillMaxWidth(), onClick = { ui.copyKey(key) })
}

/** 刚生成的同步密钥：密钥 + 提示 + 「我已保存同步密钥」 */
@Composable
private fun NewKeyBlock(ui: SyncUiState) {
    Body {
        KeyBox(ui, ui.newKey)
        Text(text = SyncModel.KEY_SAVED_HINT, color = MiuixTheme.colorScheme.error)
    }
    SwitchPreference(checked = ui.keyAck, onCheckedChange = { ui.keyAck = it }, title = SyncModel.KEY_ACK)
}

/** 存储类型与参数（向导与更换存储共用） */
@Composable
private fun StorageFields(ui: SyncUiState) {
    val f = ui.edit
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("WebDAV" to false, "S3 兼容存储" to true).forEach { (label, s3) ->
            TextButton(
                text = if (f.s3 == s3) "✓ $label" else label,
                modifier = Modifier.weight(1f),
                onClick = { ui.edit = ui.edit.copy(s3 = s3) },
            )
        }
    }
    val hint = SyncModel.secretPlaceholder(ui.status.hasSecret, !ui.saved.backendBlank)
    if (f.s3) {
        Field("Endpoint（如 https://s3.example.com）", f.s3Endpoint) { ui.edit = ui.edit.copy(s3Endpoint = it) }
        Field("Bucket", f.s3Bucket) { ui.edit = ui.edit.copy(s3Bucket = it) }
        Field("Region（如 auto，可留空）", f.s3Region) { ui.edit = ui.edit.copy(s3Region = it) }
        Field("Access Key ID", f.s3AccessKeyId) { ui.edit = ui.edit.copy(s3AccessKeyId = it) }
        Field("Secret（$hint）", ui.secret, secret = true) { ui.secret = it }
        Field("路径前缀（可留空）", f.s3Prefix) { ui.edit = ui.edit.copy(s3Prefix = it) }
    } else {
        Field("WebDAV 地址（如 https://dav.example.com/dav/）", f.wdUrl) { ui.edit = ui.edit.copy(wdUrl = it) }
        Field("用户名", f.wdUser) { ui.edit = ui.edit.copy(wdUser = it) }
        Field("密码（$hint）", ui.secret, secret = true) { ui.secret = it }
    }
    if (f.s3) {
        SwitchPreference(
            checked = f.s3PathStyle,
            onCheckedChange = { ui.edit = ui.edit.copy(s3PathStyle = it) },
            title = "Path-style 寻址",
            summary = "MinIO 等自建服务通常要开",
        )
    }
    SwitchPreference(
        checked = f.skipCertVerify,
        onCheckedChange = { ui.edit = ui.edit.copy(skipCertVerify = it) },
        title = SyncModel.SKIP_CERT_LABEL,
    )
    if (f.skipCertVerify) Text(text = SyncModel.SKIP_CERT_WARN, color = MiuixTheme.colorScheme.error)
}

// ───────────────────────────── 界面状态 ─────────────────────────────

/** 一串步骤的执行结果：全部成功带回每一步的回执，失败带回第几步出错 */
private sealed interface StepsResult {
    data class Ok(val replies: List<String>) : StepsResult
    data class Failed(val at: Int, val error: String) : StepsResult
}

/** 依次执行，遇错即停。必须在后台线程调（command 会阻塞） */
private fun execute(h: MobileSyncHandle, steps: List<Step>): StepsResult {
    val replies = ArrayList<String>(steps.size)
    steps.forEachIndexed { i, s ->
        try {
            replies += when (s) {
                is Step.SetSettings -> h.setSettings(s.json)
                is Step.Command -> h.command(s.op, s.args)
                Step.ReadStatus -> h.status()
            }
        } catch (e: SyncException) {
            return StepsResult.Failed(i, (e as? SyncException.Failed)?.reason ?: e.toString())
        } catch (e: Exception) {
            return StepsResult.Failed(i, e.message ?: e.toString())
        }
    }
    return StepsResult.Ok(replies)
}

/** 页面状态。命令都在 IO 线程跑，同时只有一串在途（[busy]），结果回主线程 */
private class SyncUiState(private val context: Context, private val scope: CoroutineScope) {
    var status by mutableStateOf(SyncModel.Status())
    /** 最近一次读到 / 成功保存的设置：存储摘要、改间隔、总开关都以它为准 */
    var saved by mutableStateOf(Form())
    var devices by mutableStateOf<List<SyncModel.Device>>(emptyList())
    var devicesErr by mutableStateOf("")
    var busy by mutableStateOf(false)
    /** 会带回新同步密钥的命令在途（建库、建库撞库后的取回）：回执回来前不许关流程，否则密钥被丢、用户从没见过 */
    var keyPending by mutableStateOf(false)
    var message by mutableStateOf<String?>(null)
    var messageError by mutableStateOf(false)
    var flow by mutableStateOf<Flow?>(null)
    /** 读到过一次设置与状态：之前不显示「开始配置…」，免得已配置的用户先看到它闪一下 */
    var loaded by mutableStateOf(false)
    var intervalText by mutableStateOf(SyncModel.DEFAULT_INTERVAL_MIN.toString())
    private var devicesLoaded = false

    // 存储表单（向导与更换存储共用）；机密只在 [secret] 里，提交即清空
    var edit by mutableStateOf(Form())
    var secret by mutableStateOf("")

    // 同步密钥：粘贴的、刚生成的（向导建库、更换存储建库、重置远端）、「查看…」读到的
    var importKey by mutableStateOf("")
    var newKey by mutableStateOf("")
    var keyAck by mutableStateOf(false)
    var exportedKey by mutableStateOf("")
    var resetAck by mutableStateOf(false)

    // 向导
    var wizStep by mutableStateOf(WizStep.STORAGE)
    var wizTestOk by mutableStateOf(false)
    var wizTestText by mutableStateOf("")
    var wizCanRetry by mutableStateOf(false)
    /** 最近一次第 2 步发出的表单与其中 set_secret 的步号（重试用；不含机密） */
    private var wizSent: Pair<Form, Int?>? = null
    var wizRemoteInit by mutableStateOf(false)
    private var wizJoined = false
    private var wizNeedsIdentity = false
    var wizErr by mutableStateOf("")
    var wizDone by mutableStateOf("")

    // 本机身份（向导第 4 步与确认身份共用）
    var idView by mutableStateOf(IdentityView())
    var idLoaded by mutableStateOf(false)
    var idMode by mutableStateOf(0)
    var idDevPick by mutableStateOf(0)
    var idGroupPick by mutableStateOf(0)
    var idErr by mutableStateOf("")

    // 更换存储
    var storageStage by mutableStateOf(StorageStage.FORM)
    var storageErr by mutableStateOf("")

    // 改号
    var renameId by mutableStateOf("")
    var renameName by mutableStateOf("")
    var renameErr by mutableStateOf("")

    // 换组：0 加入已有组 / 1 新建独立组 / 2 重命名本组
    var groupMode by mutableStateOf(0)
    var groupChoices by mutableStateOf<List<Pair<String, String>>>(emptyList())
    var groupPick by mutableStateOf(0)
    /** 加入时：0 用组内设置替换本机 / 1 用本机设置替换组内 */
    var groupMine by mutableStateOf(0)
    var groupNewName by mutableStateOf("")
    var groupRename by mutableStateOf("")
    var groupErr by mutableStateOf("")

    // 二次确认（移除设备、确认大批量删除）：与词库「清空」同一套节奏
    private var confirmKey by mutableStateOf<String?>(null)
    private var confirmAt = 0L

    fun confirming(key: String) = confirmKey == key

    /** 第一下进入待确认（5 秒后复位），隔 [CONFIRM_MIN_GAP_MS] 之后的第二下返回 true */
    fun confirmTap(key: String): Boolean {
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

    private fun handle(): MobileSyncHandle? {
        val core = CoreAccess.obtainCore(context) ?: return null
        return SyncHub.get(core, (context as WindInputApp).dataDirs)
    }

    // ── 读取 ──

    /** 读设置与状态（已加入时连设备名册）。有命令在途时不读设置：读到的可能是写之前的 */
    suspend fun reload() {
        val got = withContext(Dispatchers.IO) {
            handle()?.let { h ->
                // 本机名称默认取机型名，别的设备的列表里才认得出。只在已加入后写：没配置过同步的用户
                // 只是打开这一页，不该落下设置文件（SyncHub 靠它判断要不要建句柄）
                var json = h.getSettings()
                val f = SyncModel.parseForm(json)
                if (f.deviceName.isBlank() && SyncModel.parseStatus(h.status()).configured) {
                    val named = SyncModel.defaultDeviceName(f.deviceName, Build.MODEL)
                    if (named.isNotEmpty()) {
                        runCatching { h.setSettings(f.copy(deviceName = named).toSettingsJson()) }
                        json = h.getSettings()
                    }
                }
                json to h.status()
            }
        }
        if (got == null) {
            showMessage("输入法核心未就绪，同步不可用", error = true)
            return
        }
        if (!busy) applyForm(SyncModel.parseForm(got.first))
        applyStatus(SyncModel.parseStatus(got.second))
        loaded = true
    }

    suspend fun refreshStatus() {
        val s = withContext(Dispatchers.IO) { handle()?.status() } ?: return
        applyStatus(SyncModel.parseStatus(s))
    }

    private fun applyStatus(s: SyncModel.Status) {
        status = s
        if (s.configured && !devicesLoaded) {
            devicesLoaded = true
            loadDevices()
        }
    }

    /**
     * 已保存的设置落到界面。存储表单只在没有向导 / 更换存储打开时跟着回到已保存的样子：
     * 命令失败后的兜底刷新不能冲掉填了一半的内容（草稿保护）。
     */
    private fun applyForm(f: Form) {
        saved = f
        if (flow != Flow.WIZARD && flow != Flow.STORAGE) resetStorageForm()
        intervalText = f.intervalMin.toString()
    }

    private fun resetStorageForm() {
        edit = saved
        secret = ""
    }

    /** 拉一次设备名册（后台，不占 busy） */
    fun loadDevices() {
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                handle()?.let { execute(it, listOf(Step.Command("list_devices"))) }
            } ?: return@launch
            when (r) {
                is StepsResult.Ok -> {
                    devicesErr = ""
                    devices = SyncModel.parseDevices(r.replies.first())
                }
                is StepsResult.Failed -> {
                    val (t, notice) = SyncModel.failureText("读取设备列表", r.error)
                    if (!notice) devicesErr = t
                }
            }
        }
    }

    /** 命令之后：设置、状态、名册都再读一次 */
    private fun refreshAll() {
        scope.launch {
            runCatching { reload() }
            if (status.configured) loadDevices()
        }
    }

    // ── 执行 ──

    /** 后台依次执行，遇错即停；结果回主线程交给 [done]，之后兜底刷新 */
    private fun run(
        steps: List<Step>,
        refresh: Boolean = true,
        yieldsKey: Boolean = false,
        done: (StepsResult) -> Unit,
    ) {
        if (busy) return
        busy = true
        keyPending = yieldsKey
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                handle()?.let { execute(it, steps) } ?: StepsResult.Failed(0, "输入法核心未就绪")
            }
            busy = false
            keyPending = false
            done(r)
            if (refresh) refreshAll()
        }
    }

    /** 简单命令：成功 / 失败各给一句提示 */
    private fun runSimple(label: String, steps: List<Step>, ok: String, onOk: (List<String>) -> Unit = {}) {
        message = null
        run(steps) { r ->
            when (r) {
                is StepsResult.Ok -> {
                    if (ok.isNotEmpty()) showMessage(ok)
                    onOk(r.replies)
                }
                is StepsResult.Failed -> failed(label, r.error)
            }
        }
    }

    private fun showMessage(text: String, error: Boolean = false) {
        message = text
        messageError = error
    }

    private fun failed(label: String, e: String) {
        val (t, notice) = SyncModel.failureText(label, e)
        showMessage(t, error = !notice)
    }

    // ── 状态区 ──

    fun syncNow() = runSimple("立即同步", listOf(Step.Command("sync_now")), "已开始同步")

    /** 总开关：只改启用开关；忙时不发，开关仍显示已保存的状态 */
    fun setMaster(on: Boolean) {
        if (busy) return
        val steps = SyncModel.masterSwitchSteps(saved, on)
        runSimple(if (on) "启用同步" else "停用同步", steps, if (on) "已启用同步" else "已停用同步") {
            saved = saved.copy(enabled = on)
        }
    }

    fun saveInterval() {
        val n = SyncModel.clampInterval(intervalText)
        val f = SyncModel.intervalChange(saved, n.toDouble())
        intervalText = n.toString()
        if (f == null) return
        saved = f
        runSimple("保存同步间隔", listOf(Step.SetSettings(f.toSettingsJson())), "")
    }

    fun confirmMassDelete() =
        runSimple("确认删除", listOf(Step.Command("confirm_mass_delete")), "已确认，将把这些删除同步到所有设备")

    fun removeDevice(id: String) =
        runSimple("移除设备", listOf(Step.Command("remove_device", SyncModel.removeDeviceArgs(id))), "已移除该设备")

    // ── 流程开关 ──

    /** 正显示新生成的同步密钥、还没勾「我已保存」：不让前进或关闭 */
    private fun keyUnacked() = newKey.isNotEmpty() && !keyAck

    private fun clearSecrets() {
        secret = ""
        importKey = ""
    }

    private fun clearNewKey() {
        newKey = ""
        keyAck = false
    }

    /** 关流程（取消、返回键、完成）：已完成的步骤不回滚，输入的机密与读到的密钥清掉 */
    fun closeFlow() {
        if (keyPending) {
            showMessage("正在生成同步密钥，请稍候")
            return
        }
        if (keyUnacked()) {
            showMessage("请先保存同步密钥并勾选「${SyncModel.KEY_ACK}」", error = true)
            return
        }
        // 重置在途（新密钥还没回来）时不关，免得密钥回来没处显示
        if (flow == Flow.RESET && busy) return
        flow = null
        clearSecrets()
        clearNewKey()
        exportedKey = ""
        resetStorageForm()
    }

    private fun open(f: Flow) {
        message = null
        flow = f
    }

    fun openWizard() {
        resetStorageForm()
        clearSecrets()
        clearNewKey()
        wizStep = WizStep.STORAGE
        wizTestOk = false
        wizTestText = ""
        wizCanRetry = false
        wizSent = null
        wizRemoteInit = false
        wizJoined = false
        wizNeedsIdentity = false
        wizErr = ""
        wizDone = ""
        resetIdentity()
        open(Flow.WIZARD)
    }

    fun openStorage() {
        resetStorageForm()
        clearSecrets()
        clearNewKey()
        storageStage = StorageStage.FORM
        storageErr = ""
        open(Flow.STORAGE)
    }

    fun openIdentity() {
        resetIdentity()
        open(Flow.IDENTITY)
        loadIdentity()
    }

    fun openRename() {
        renameId = status.device
        renameName = saved.deviceName
        renameErr = ""
        open(Flow.RENAME)
    }

    /** 列出同类设备所在的其他组；没有可加入的就默认「新建独立组」 */
    fun openGroup() {
        groupChoices = SyncModel.joinableGroups(status.group, devices).map { it.id to "${it.name} · ${it.devices.size} 台" }
        groupMode = if (groupChoices.isEmpty()) 1 else 0
        groupPick = 0
        groupMine = 0
        groupNewName = ""
        groupRename = status.groupName
        groupErr = ""
        open(Flow.GROUP)
    }

    /** 先进页（显示「正在读取…」），密钥回来再填进去；失败就退回 */
    fun openKeyView() {
        exportedKey = ""
        open(Flow.KEY_VIEW)
        run(listOf(Step.Command("export_key")), refresh = false) { r ->
            if (flow != Flow.KEY_VIEW) return@run
            val key = (r as? StepsResult.Ok)?.let { SyncModel.keyFromReply(it.replies.first()) }
            when {
                key != null -> exportedKey = key
                r is StepsResult.Failed -> {
                    flow = null
                    failed("读取同步密钥", r.error)
                }
                else -> {
                    flow = null
                    showMessage("同步线程没有返回同步密钥", error = true)
                }
            }
        }
    }

    fun openReset() {
        resetAck = false
        clearNewKey()
        open(Flow.RESET)
    }

    fun confirmReset() {
        if (!resetAck) return
        run(listOf(Step.Command("reset_remote", SyncModel.resetArgs()))) { r ->
            when (r) {
                is StepsResult.Failed -> if (flow == Flow.RESET) failed("重置远端", r.error)
                is StepsResult.Ok -> {
                    val key = SyncModel.keyFromReply(r.replies.first())
                    if (flow == Flow.RESET && key != null) {
                        keyAck = false
                        newKey = key
                    } else {
                        // 没拿到新密钥就不留页（可在「查看…」里看）
                        if (flow == Flow.RESET) flow = null
                        showMessage("远端已重置，将以本机数据为准重新上传")
                    }
                }
            }
        }
    }

    fun copyKey(key: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("sync-key", key)
        // 标记敏感：系统剪贴板预览与别的剪贴板工具不显示内容
        clip.description.extras = android.os.PersistableBundle().apply {
            putBoolean("android.content.extra.IS_SENSITIVE", true)
        }
        cm.setPrimaryClip(clip)
        showMessage("已复制同步密钥，粘贴到别的设备后请清空剪贴板")
    }

    // ── 改号与换组 ──

    fun saveRename() {
        if (busy) return
        if (status.device.isEmpty()) {
            renameErr = "还没读到本机设备号，请稍后再试"
            return
        }
        when (val p = SyncModel.renameSteps(status.device, renameId, saved, renameName)) {
            is Plan.Invalid -> renameErr = p.reason
            is Plan.Run -> if (p.steps.isEmpty()) {
                flow = null
            } else {
                renameErr = ""
                run(p.steps) { r ->
                    when (r) {
                        is StepsResult.Ok -> {
                            if (flow == Flow.RENAME) flow = null
                            showMessage("修改设备信息成功")
                        }
                        is StepsResult.Failed -> {
                            val t = SyncModel.failureText("修改设备信息", SyncModel.renameFailure(r.at, r.error)).first
                            if (flow == Flow.RENAME) renameErr = t else showMessage(t, error = true)
                        }
                    }
                }
            }
        }
    }

    private fun groupChange(): Pair<SyncModel.GroupChange?, String?> = when (groupMode) {
        0 -> groupChoices.getOrNull(groupPick)?.let { SyncModel.GroupChange.Join(it.first, theirs = groupMine == 0) to null }
            ?: (null to "没有可加入的组")
        1 -> SyncModel.checkGroupName(groupNewName, false).let { (n, e) -> (if (e == null) SyncModel.GroupChange.NewGroup(n) else null) to e }
        else -> SyncModel.checkGroupName(groupRename, true).let { (n, e) -> (if (e == null) SyncModel.GroupChange.Rename(n) else null) to e }
    }

    fun saveGroup() {
        if (busy) return
        val (change, err) = groupChange()
        if (change == null) {
            groupErr = err ?: ""
            return
        }
        groupErr = ""
        run(SyncModel.groupChangeCommands(change)) { r ->
            when (r) {
                is StepsResult.Ok -> {
                    if (flow == Flow.GROUP) flow = null
                    showMessage("修改同步组成功")
                }
                is StepsResult.Failed -> {
                    val t = SyncModel.failureText("修改同步组", r.error).first
                    if (flow == Flow.GROUP) groupErr = t else showMessage(t, error = true)
                }
            }
        }
    }

    // ── 更换存储 ──

    /** 先校验，再按 [SyncModel.storageChangeSteps] 发送；机密随即清空 */
    fun saveStorage() {
        if (busy) return
        val new = edit
        when (val p = SyncModel.storageChangeSteps(saved, new, secret)) {
            is Plan.Invalid -> storageErr = p.reason
            is Plan.Run -> {
                storageErr = ""
                clearSecrets()
                run(p.steps) { r ->
                    // 设置已写下（全部成功，或失败在设置之后）就把发出的表单当作已保存值
                    if (!(r is StepsResult.Failed && r.at == 0)) saved = new
                    if (flow != Flow.STORAGE) return@run
                    when (r) {
                        is StepsResult.Ok -> when (val stage = SyncModel.storageStageAfterTest(r.replies.last())) {
                            null -> {
                                closeFlow()
                                showMessage("已更换存储")
                            }
                            else -> storageStage = stage
                        }
                        is StepsResult.Failed -> storageErr = if (r.at > 0) {
                            // 测试连接是最后一步：失败在它上面时设置与密码已经存下
                            "设置已保存，但连接新位置失败：${r.error}"
                        } else {
                            SyncModel.keyErrorText("保存存储设置", r.error)
                        }
                    }
                }
            }
        }
    }

    /** 新位置还没有同步库：在那里建库 */
    fun createStorageLibrary() {
        storageErr = ""
        run(SyncModel.createKeySteps(), yieldsKey = true) { r ->
            if (flow != Flow.STORAGE) return@run
            when (r) {
                is StepsResult.Ok -> SyncModel.keyFromReply(r.replies.first())?.let { showNewKey(true, it) } ?: closeFlow()
                is StepsResult.Failed -> createFailed(true, r.error)
            }
        }
    }

    /** 新位置已有同步库：粘贴同步密钥加入；之后要确认身份就接着打开确认身份 */
    fun joinStorage() {
        when (val p = SyncModel.importKeySteps(importKey)) {
            is Plan.Invalid -> storageErr = p.reason
            is Plan.Run -> {
                storageErr = ""
                run(p.steps) { r ->
                    if (flow != Flow.STORAGE) return@run
                    when (r) {
                        is StepsResult.Ok -> {
                            val st = SyncModel.parseStatus(r.replies.last())
                            status = st
                            closeFlow()
                            if (st.needsIdentity) openIdentity()
                        }
                        is StepsResult.Failed -> if (SyncModel.REMOTE_EMPTY in r.error) {
                            keyForm(storage = true, join = false, why = r.error)
                        } else {
                            storageErr = SyncModel.keyErrorText("加入同步库", r.error)
                        }
                    }
                }
            }
        }
    }

    // ── 向导 ──

    /** 「下一步」/「确定」/「完成」：按当前步骤校验、发命令；回执到了才前进 */
    fun wizardNext() {
        if (busy) return
        when (wizStep) {
            WizStep.STORAGE -> {
                val new = edit
                val s = secret
                SyncModel.checkWizardStorage(saved, new, status.hasSecret, s)?.let {
                    wizErr = it
                    return
                }
                // wizardTestSteps 里 set_secret 紧跟在写设置之后
                val secretStep = if (s.isNotEmpty()) 1 else null
                val steps = SyncModel.wizardTestSteps(new, s)
                wizSent = new to secretStep
                clearSecrets()
                wizTestOk = false
                wizTestText = ""
                go(WizStep.TEST, WizStep.STORAGE, "")
                runWizardTest(steps)
            }
            WizStep.TEST -> if (wizTestOk) {
                go(SyncModel.nextStep(WizStep.TEST, wizJoined, "", wizNeedsIdentity), WizStep.TEST, "")
            }
            WizStep.KEY -> when {
                // 已生成同步密钥：勾了「我已保存」才完成
                newKey.isNotEmpty() -> if (keyAck) go(WizStep.DONE, WizStep.KEY, "init")
                else -> when (val p = SyncModel.wizardKeySteps(wizRemoteInit, importKey)) {
                    is Plan.Invalid -> wizErr = p.reason
                    is Plan.Run -> {
                        wizErr = ""
                        if (wizRemoteInit) runWizardImport(p.steps) else runWizardCreate(p.steps)
                    }
                }
            }
            WizStep.IDENTITY -> confirmIdentity()
            WizStep.DONE -> closeFlow()
        }
    }

    /** 第 2 步「重试」：密码已存上，只重发设置与测试 */
    fun retryTest() {
        val (form, _) = wizSent ?: return
        wizSent = form to null
        wizErr = ""
        wizCanRetry = false
        runWizardTest(SyncModel.wizardTestSteps(form, ""))
    }

    /** 「上一步」：同步密钥已生成就回不去了 */
    fun wizardBack() {
        if (newKey.isNotEmpty()) return
        SyncModel.backStep(wizStep)?.let {
            importKey = ""
            go(it, wizStep, "")
        }
    }

    private fun go(step: WizStep, from: WizStep, mode: String) {
        wizErr = ""
        if (step == WizStep.DONE) wizDone = SyncModel.doneText(from, mode)
        wizStep = step
        if (step == WizStep.IDENTITY) {
            resetIdentity()
            loadIdentity()
        }
    }

    private fun runWizardTest(steps: List<Step>) {
        run(steps) { r ->
            if (flow != Flow.WIZARD) return@run
            when (r) {
                is StepsResult.Ok -> {
                    // 最后一步是读状态，倒数第二步是测试连接
                    val reply = r.replies[r.replies.size - 2]
                    val st = SyncModel.parseStatus(r.replies.last())
                    status = st
                    wizRemoteInit = JSONObject(reply).optBoolean("remote_initialized")
                    wizJoined = JSONObject(reply).optBoolean("joined")
                    wizNeedsIdentity = st.needsIdentity
                    wizTestText = SyncModel.connectionText(reply)
                    wizErr = ""
                    wizCanRetry = false
                    wizTestOk = true
                }
                is StepsResult.Failed -> {
                    wizTestOk = false
                    wizCanRetry = SyncModel.canRetryTest(wizSent?.second, r.at)
                    wizErr = SyncModel.keyErrorText("测试连接", r.error)
                }
            }
        }
    }

    private fun runWizardCreate(steps: List<Step>) {
        run(steps, yieldsKey = true) { r ->
            if (flow != Flow.WIZARD) return@run
            when (r) {
                // 库已建好但没拿到密钥：直接完成，可在「安全」里查看
                is StepsResult.Ok -> SyncModel.keyFromReply(r.replies.first())?.let { showNewKey(false, it) }
                    ?: go(WizStep.DONE, WizStep.KEY, "init")
                is StepsResult.Failed -> createFailed(false, r.error)
            }
        }
    }

    private fun runWizardImport(steps: List<Step>) {
        run(steps) { r ->
            if (flow != Flow.WIZARD) return@run
            when (r) {
                is StepsResult.Ok -> {
                    val st = SyncModel.parseStatus(r.replies.last())
                    status = st
                    clearSecrets()
                    go(SyncModel.nextStep(WizStep.KEY, false, "import", st.needsIdentity), WizStep.KEY, "import")
                }
                is StepsResult.Failed -> if (SyncModel.REMOTE_EMPTY in r.error) {
                    // 远端其实是空的：改成生成形态
                    keyForm(storage = false, join = false, why = r.error)
                } else {
                    wizErr = SyncModel.keyErrorText("加入同步库", r.error)
                }
            }
        }
    }

    /**
     * 建库时报远端已有同步库：可能是上次建库其实成功了而回执丢了。先测一次连接看本机密钥能不能解开它，
     * 能就取回同步密钥补给用户确认保存，不能就只能粘贴加入。
     */
    private fun createFailed(storage: Boolean, e: String) {
        val myFlow = if (storage) Flow.STORAGE else Flow.WIZARD
        if (SyncModel.REMOTE_TAKEN !in e) {
            val t = SyncModel.keyErrorText("生成同步密钥", e)
            if (storage) storageErr = t else wizErr = t
            return
        }
        run(listOf(Step.Command("test_connection")), yieldsKey = true) { r ->
            if (flow != myFlow) return@run
            val joined = r is StepsResult.Ok && runCatching { JSONObject(r.replies.first()).optBoolean("joined") }.getOrDefault(false)
            if (r is StepsResult.Failed) {
                val t = SyncModel.keyErrorText("测试连接", r.error)
                if (storage) storageErr = t else wizErr = t
            } else if (!joined) {
                keyForm(storage, join = true, why = SyncModel.REMOTE_TAKEN_MSG)
            } else {
                run(listOf(Step.Command("export_key")), yieldsKey = true) { x ->
                    if (flow != myFlow) return@run
                    val key = (x as? StepsResult.Ok)?.let { SyncModel.keyFromReply(it.replies.first()) }
                    if (key != null) showNewKey(storage, key) else keyForm(storage, join = true, why = SyncModel.REMOTE_TAKEN_MSG)
                }
            }
        }
    }

    /** 切到粘贴（[join]）或生成形态，并说明原因；粘贴框清空 */
    private fun keyForm(storage: Boolean, join: Boolean, why: String) {
        importKey = ""
        if (storage) {
            storageStage = if (join) StorageStage.JOIN else StorageStage.CREATE
            storageErr = why
        } else {
            wizRemoteInit = join
            wizErr = why
        }
    }

    /** 显示刚生成（或取回）的同步密钥，等用户勾选已保存 */
    private fun showNewKey(storage: Boolean, key: String) {
        keyAck = false
        newKey = key
        if (storage) {
            storageErr = ""
            storageStage = StorageStage.CREATED
        } else {
            wizErr = ""
        }
    }

    // ── 本机身份 ──

    private fun resetIdentity() {
        idView = IdentityView()
        idLoaded = false
        idMode = 0
        idDevPick = 0
        idGroupPick = 0
        idErr = ""
    }

    fun loadIdentity() {
        idLoaded = false
        idErr = ""
        run(listOf(Step.Command("list_devices")), refresh = false) { r ->
            if (flow != Flow.WIZARD && flow != Flow.IDENTITY) return@run
            when (r) {
                is StepsResult.Ok -> {
                    val devs = SyncModel.parseDevices(r.replies.first())
                    val my = status.device
                    val (opts, default) = SyncModel.identityOptions(my, devs)
                    val v = SyncModel.identityView(opts, default, my, devs, LocalDate.now())
                    idMode = v.defaultMode
                    idDevPick = v.defaultSub
                    idGroupPick = v.defaultSub
                    idView = v
                    idErr = ""
                    idLoaded = true
                }
                is StepsResult.Failed -> idErr = SyncModel.keyErrorText("读取设备列表", r.error)
            }
        }
    }

    fun confirmIdentity() {
        if (busy || !idLoaded) return
        val pick = SyncModel.identityPick(idView, idMode, idDevPick, idGroupPick)
        if (pick == null) {
            idErr = "请选择一项"
            return
        }
        idErr = ""
        run(SyncModel.identityCommands(pick)) { r ->
            if (flow != Flow.WIZARD && flow != Flow.IDENTITY) return@run
            when (r) {
                is StepsResult.Ok -> if (flow == Flow.WIZARD) {
                    go(WizStep.DONE, WizStep.IDENTITY, "")
                } else {
                    flow = null
                    showMessage("已确认本机身份，同步会在后台开始")
                }
                is StepsResult.Failed -> idErr = SyncModel.keyErrorText("确认身份", r.error)
            }
        }
    }
}
