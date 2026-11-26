package com.windinput.ime.settings

import com.windinput.ime.settings.SyncModel.Device
import com.windinput.ime.settings.SyncModel.ErrorKind
import com.windinput.ime.settings.SyncModel.Form
import com.windinput.ime.settings.SyncModel.GroupChange
import com.windinput.ime.settings.SyncModel.IdentityChoice
import com.windinput.ime.settings.SyncModel.Plan
import com.windinput.ime.settings.SyncModel.Step
import com.windinput.ime.settings.SyncModel.StorageStage
import com.windinput.ime.settings.SyncModel.WizStep
import java.time.LocalDate
import java.time.ZoneOffset
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncModelTest {

    private val webdav = Form(wdUrl = "https://dav.example.com/w", wdUser = "u", raw = "{}")

    private fun runSteps(p: Plan): List<Step> = (p as Plan.Run).steps

    private fun cmd(s: Step): Step.Command = s as Step.Command

    private fun args(s: Step): JSONObject = JSONObject(cmd(s).args)

    // ── 状态 ──

    @Test
    fun phaseTextMapsKnownAndKeepsUnknown() {
        assertEquals("空闲，已是最新", SyncModel.phaseText("idle"))
        assertEquals("需要生成或粘贴同步密钥", SyncModel.phaseText("needs_setup"))
        assertEquals("状态：x", SyncModel.phaseText("x"))
        assertEquals("状态未知", SyncModel.phaseText(""))
    }

    @Test
    fun errorClassification() {
        assertEquals(ErrorKind.NONE, SyncModel.classifyError("idle", ""))
        assertEquals(ErrorKind.MASS_DELETE, SyncModel.classifyError("halted", "为防误删已暂停：删了 50 个"))
        assertEquals(ErrorKind.NOTICE, SyncModel.classifyError("error", "a.toml 正在被编辑，本轮暂不同步"))
        assertEquals(ErrorKind.DANGER, SyncModel.classifyError("error", "401"))
        assertEquals(ErrorKind.DANGER, SyncModel.classifyError("halted", "别的原因"))
    }

    @Test
    fun statusParsesIdentityFieldsAndConfigured() {
        val s = SyncModel.parseStatus(
            """{"installed":true,"enabled":true,"phase":"idle","last_ok":1700000000,"last_error":null,"pending":2,"conflicts":1,
               "has_secret":true,"joined":true,"device":"pixel","group":"g1","group_name":"手机","needs_identity":true}""",
        )
        assertTrue(s.configured && s.hasSecret && s.needsIdentity)
        assertEquals("pixel", s.device)
        assertEquals("手机", s.groupName)
        assertEquals("", s.lastError)
        assertFalse(SyncModel.parseStatus("""{"joined":false}""").configured)
        assertEquals("", SyncModel.parseStatus("不是 JSON").phase)
    }

    @Test
    fun statusValueAndSyncNowRespectIdentityAndDuplicate() {
        val idle = SyncModel.Status(enabled = true, phase = "idle", joined = true)
        assertEquals("空闲，已是最新", SyncModel.statusValue(idle))
        assertTrue(SyncModel.canSyncNow(idle))
        assertEquals("需要确认本机身份", SyncModel.statusValue(idle.copy(needsIdentity = true)))
        assertFalse(SyncModel.canSyncNow(idle.copy(needsIdentity = true)))
        val dup = idle.copy(phase = "error", lastError = "另一台设备在用相同设备号")
        assertTrue(dup.duplicateDevice)
        assertEquals("需要给本机改号", SyncModel.statusValue(dup))
        assertFalse(SyncModel.canSyncNow(dup))
        // 总开关关了不能立即同步
        assertFalse(SyncModel.canSyncNow(idle.copy(enabled = false)))
    }

    @Test
    fun busyFailureIsNoticeOthersCarryLabel() {
        assertEquals("正在同步，请稍后再试" to true, SyncModel.failureText("立即同步", "正在同步，请稍后再试"))
        assertEquals("移除设备失败：网络错误" to false, SyncModel.failureText("移除设备", "网络错误"))
        assertEquals("同步密钥不对", SyncModel.keyErrorText("加入同步库", "同步密钥不对"))
        assertEquals("加入同步库失败：超时", SyncModel.keyErrorText("加入同步库", "超时"))
    }

    // ── 设置 JSON ──

    @Test
    fun settingsRoundTripKeepsUnknownFieldsAndSkipCert() {
        val json = """{"settings":{"enabled":true,"backend":{"type":"webdav","url":"https://x/y","user":"u"},
            "interval_min":30,"device_name":"Pixel","skip_cert_verify":true,"future":1},"has_secret":true}"""
        val f = SyncModel.parseForm(json)
        assertTrue(f.skipCertVerify && f.enabled && !f.s3)
        assertEquals(30, f.intervalMin)
        val out = JSONObject(f.toSettingsJson())
        assertTrue(out.getBoolean("skip_cert_verify"))
        assertEquals(1, out.getInt("future"))
        assertEquals("https://x/y", out.getJSONObject("backend").getString("url"))
        assertEquals(f.copy(raw = out.toString()), SyncModel.parseForm(out.toString()))
        // 关掉时不写该字段（核心默认 false）
        assertFalse(JSONObject(f.copy(skipCertVerify = false).toSettingsJson()).has("skip_cert_verify"))
        // 不得包含任何机密字段
        assertFalse(out.toString().contains("password"))
    }

    @Test
    fun settingsDefaultsAndDropsLegacyScope() {
        val f = SyncModel.parseForm("""{"settings":{"enabled":false,"scope":{"config":true}}}""")
        assertEquals(15, f.intervalMin)
        assertFalse(f.skipCertVerify)
        val out = JSONObject(f.toSettingsJson())
        assertFalse(out.has("scope"))
        // 没启用且后端全空：不写后端
        assertFalse(out.has("backend"))
    }

    @Test
    fun s3BackendParsesAndWrites() {
        val json = """{"settings":{"enabled":true,"backend":{"type":"s3","endpoint":"https://h","bucket":"b","access_key_id":"k","path_style":true,"region":"","prefix":"p"}}}"""
        val f = SyncModel.parseForm(json)
        assertTrue(f.s3 && f.s3PathStyle && !f.backendBlank)
        val b = JSONObject(f.toSettingsJson()).getJSONObject("backend")
        assertEquals("s3", b.getString("type"))
        assertEquals("p", b.getString("prefix"))
        assertTrue(b.getBoolean("path_style"))
    }

    @Test
    fun storageSummaryMarksSkipCert() {
        assertEquals("WebDAV · https://dav.example.com/w · 用户 u", SyncModel.storageSummary(webdav))
        assertEquals(
            "WebDAV · https://dav.example.com/w · 用户 u · 跳过证书验证",
            SyncModel.storageSummary(webdav.copy(skipCertVerify = true)),
        )
        assertEquals("未设置", SyncModel.storageSummary(Form(skipCertVerify = true)))
    }

    @Test
    fun intervalClampsTo1Through1440() {
        assertEquals(1, SyncModel.clampInterval(0.0))
        assertEquals(1440, SyncModel.clampInterval(99999.0))
        assertEquals(15, SyncModel.clampInterval(Double.NaN))
        assertEquals(15, SyncModel.clampInterval("abc"))
        assertEquals(30, SyncModel.clampInterval(" 30 "))
        assertNull(SyncModel.intervalChange(webdav, 15.0))
        assertEquals(60, SyncModel.intervalChange(webdav, 60.0)?.intervalMin)
    }

    @Test
    fun secretArgsAndPlaceholderNeverCarryContent() {
        assertNull(SyncModel.secretArgs(""))
        assertEquals("pw", JSONObject(SyncModel.secretArgs("pw")!!).getString("password"))
        assertEquals("已保存，留空则不修改", SyncModel.secretPlaceholder(true, true))
        assertEquals("如已保存过密码可留空", SyncModel.secretPlaceholder(false, true))
        assertEquals("未设置", SyncModel.secretPlaceholder(false, false))
    }

    // ── 更换存储 ──

    @Test
    fun storageChangeToNewLocationRequiresPassword() {
        val moved = webdav.copy(wdUrl = "https://other.example.com/w")
        assertEquals(
            Plan.Invalid("更换了存储位置，请填写新位置的密码"),
            SyncModel.storageChangeSteps(webdav, moved, ""),
        )
        // 切换后端类型也算换位置
        assertTrue(SyncModel.storageChangeSteps(webdav, webdav.copy(s3 = true, s3Endpoint = "https://s3"), "") is Plan.Invalid)
    }

    @Test
    fun storageChangeOrderIsSettingsThenSecretThenTest() {
        val moved = webdav.copy(wdUrl = "https://other.example.com/w")
        val steps = runSteps(SyncModel.storageChangeSteps(webdav, moved, "pw"))
        assertEquals(3, steps.size)
        assertEquals("https://other.example.com/w", JSONObject((steps[0] as Step.SetSettings).json).getJSONObject("backend").getString("url"))
        assertEquals("set_secret", cmd(steps[1]).op)
        assertEquals("pw", args(steps[1]).getString("password"))
        assertEquals("test_connection", cmd(steps[2]).op)
    }

    @Test
    fun storageChangeOnlySkipCertNeedsNoPassword() {
        val steps = runSteps(SyncModel.storageChangeSteps(webdav, webdav.copy(skipCertVerify = true), ""))
        assertEquals(2, steps.size)
        assertTrue(JSONObject((steps[0] as Step.SetSettings).json).getBoolean("skip_cert_verify"))
        assertEquals("test_connection", cmd(steps[1]).op)
    }

    @Test
    fun testReplyDecidesStorageStage() {
        assertEquals(StorageStage.CREATE, SyncModel.storageStageAfterTest("""{"ok":true,"remote_initialized":false,"joined":false}"""))
        assertEquals(StorageStage.JOIN, SyncModel.storageStageAfterTest("""{"ok":true,"remote_initialized":true,"joined":false}"""))
        assertNull(SyncModel.storageStageAfterTest("""{"ok":true,"remote_initialized":true,"joined":true}"""))
        assertEquals("连接成功，本机已加入", SyncModel.connectionText("""{"remote_initialized":true,"joined":true}"""))
    }

    // ── 同步密钥 ──

    @Test
    fun keyStepsAndReplies() {
        assertEquals(listOf(Step.Command("create_key")), SyncModel.createKeySteps())
        assertEquals(Plan.Invalid("请粘贴同步密钥"), SyncModel.importKeySteps("  "))
        val steps = runSteps(SyncModel.importKeySteps("abcd-efgh"))
        assertEquals("import_key", cmd(steps[0]).op)
        assertEquals("abcd-efgh", args(steps[0]).getString("key"))
        assertEquals(Step.ReadStatus, steps[1])
        assertEquals("k", SyncModel.keyFromReply("""{"ok":true,"key":"k"}"""))
        assertNull(SyncModel.keyFromReply("""{"key":""}"""))
        assertEquals("a-b-c-d-e-f-g\nh-i", SyncModel.keyLines("a-b-c-d-e-f-g-h-i"))
        assertEquals("a-b", SyncModel.keyLines("a-b"))
    }

    // ── 向导 ──

    @Test
    fun wizardStepFlow() {
        assertEquals(WizStep.TEST, SyncModel.nextStep(WizStep.STORAGE, false, "", false))
        assertEquals(WizStep.KEY, SyncModel.nextStep(WizStep.TEST, false, "", false))
        // 重新配置：本机已加入，待确认身份才进第 4 步
        assertEquals(WizStep.IDENTITY, SyncModel.nextStep(WizStep.TEST, true, "", true))
        assertEquals(WizStep.DONE, SyncModel.nextStep(WizStep.TEST, true, "", false))
        // 首台设备生成同步密钥后跳过身份步骤；粘贴加入后必须选身份
        assertEquals(WizStep.DONE, SyncModel.nextStep(WizStep.KEY, false, "init", false))
        assertEquals(WizStep.IDENTITY, SyncModel.nextStep(WizStep.KEY, false, "import", false))
        assertEquals(WizStep.STORAGE, SyncModel.backStep(WizStep.KEY))
        assertNull(SyncModel.backStep(WizStep.IDENTITY))
    }

    @Test
    fun wizardStorageCheck() {
        assertEquals("请填写存储地址", SyncModel.checkWizardStorage(Form(), Form(), false, ""))
        assertEquals("请填写密码", SyncModel.checkWizardStorage(Form(), webdav, false, ""))
        assertEquals("请填写 Secret", SyncModel.checkWizardStorage(Form(), Form(s3 = true, s3Endpoint = "https://s3"), false, ""))
        // 已有存储参数、位置没变：可留空用已存密码
        assertNull(SyncModel.checkWizardStorage(webdav, webdav, false, ""))
        assertNull(SyncModel.checkWizardStorage(Form(), webdav, false, "pw"))
    }

    @Test
    fun wizardTestStepsEnableAndEndWithStatus() {
        val steps = SyncModel.wizardTestSteps(webdav, "pw")
        assertTrue(JSONObject((steps[0] as Step.SetSettings).json).getBoolean("enabled"))
        assertEquals(listOf("set_secret", "test_connection"), steps.subList(1, 3).map { cmd(it).op })
        assertEquals(Step.ReadStatus, steps.last())
        assertEquals(3, SyncModel.wizardTestSteps(webdav, "").size)
        assertTrue(SyncModel.canRetryTest(null, 0))
        assertFalse(SyncModel.canRetryTest(1, 1))
        assertTrue(SyncModel.canRetryTest(1, 2))
    }

    // ── 设备与身份 ──

    private val devs = listOf(
        Device("pc", "台式机", "windows", lastSeen = 300, group = "pc"),
        Device("pixel", "Pixel", "android", lastSeen = 200, group = "phones", groupName = "手机"),
        Device("mi", "小米", "android", lastSeen = 100, group = "phones"),
        Device("ipad", "iPad", "ios", lastSeen = 400, group = "ipad"),
        Device("bad", readable = false, lastSeen = 500),
    )

    @Test
    fun identityOptionsOnlyListAndroidDevicesAndGroups() {
        val (opts, default) = SyncModel.identityOptions("newphone", devs)
        assertEquals(
            listOf(IdentityChoice.Restore("pixel"), IdentityChoice.Restore("mi"), IdentityChoice.Join("phones"), IdentityChoice.NewGroup),
            opts,
        )
        assertEquals(IdentityChoice.NewGroup, default)
    }

    @Test
    fun identityOptionsForReinstalledDeviceDefaultToRestore() {
        val (opts, default) = SyncModel.identityOptions("pixel", devs)
        assertEquals(listOf(IdentityChoice.Restore("pixel"), IdentityChoice.NewDevice), opts)
        assertEquals(IdentityChoice.Restore("pixel"), default)
    }

    @Test
    fun identityChoiceMapsToCommands() {
        val claim = SyncModel.identityCommands(IdentityChoice.Restore("pixel")).single()
        assertEquals("claim_device", claim.op)
        assertEquals("pixel", args(claim).getString("dev"))
        val join = SyncModel.identityCommands(IdentityChoice.Join("phones")).single()
        assertEquals("join_group", join.op)
        assertEquals("theirs", args(join).getString("mode"))
        assertEquals(listOf(Step.Command("confirm_identity")), SyncModel.identityCommands(IdentityChoice.NewGroup))
        assertEquals(listOf(Step.Command("confirm_identity")), SyncModel.identityCommands(IdentityChoice.NewDevice))
    }

    @Test
    fun identityViewAndPick() {
        val today = LocalDate.of(2026, 1, 2)
        val (opts, default) = SyncModel.identityOptions("newphone", devs)
        val v = SyncModel.identityView(opts, default, "newphone", devs, today, ZoneOffset.UTC)
        assertEquals(listOf("pixel", "mi"), v.devices.map { it.first })
        assertEquals("手机 · 2 台", v.groups.single().second)
        assertEquals(2, v.defaultMode)
        assertEquals(IdentityChoice.Restore("mi"), SyncModel.identityPick(v, 0, 1, 0))
        assertEquals(IdentityChoice.Join("phones"), SyncModel.identityPick(v, 1, 0, 0))
        assertEquals(IdentityChoice.NewGroup, SyncModel.identityPick(v, 2, 0, 0))
        assertNull(SyncModel.identityPick(v, 0, 9, 0))
        val mine = SyncModel.identityOptions("pixel", devs).let { (o, d) -> SyncModel.identityView(o, d, "pixel", devs, today, ZoneOffset.UTC) }
        assertTrue(mine.mine.startsWith("恢复为 pixel"))
        assertEquals(IdentityChoice.NewDevice, SyncModel.identityPick(mine, 1, 0, 0))
    }

    @Test
    fun groupDevicesPutsMineFirstAndNamesGroups() {
        val groups = SyncModel.groupDevices(devs, "phones")
        assertEquals("phones", groups.first().id)
        assertEquals("手机", groups.first().name)
        assertEquals(listOf("pixel", "mi"), groups.first().devices.map { it.id })
        assertEquals("手机 · 2 台设备", SyncModel.groupLine("phones", "手机", devs))
        assertEquals("g", SyncModel.groupLine("g", "", emptyList()))
    }

    @Test
    fun joinableGroupsOnlyAndroidAndNotMine() {
        val more = devs + Device("tab", "平板", "android", lastSeen = 50, group = "tabs")
        assertEquals(listOf("phones"), SyncModel.joinableGroups("tabs", more).map { it.id })
        assertEquals(listOf("tabs"), SyncModel.joinableGroups("phones", more).map { it.id })
    }

    @Test
    fun groupChangeCommands() {
        val join = SyncModel.groupChangeCommands(GroupChange.Join("phones", theirs = false)).single()
        assertEquals("join_group", join.op)
        assertEquals("mine", args(join).getString("mode"))
        assertEquals(listOf(Step.Command("leave_group", "{}")), SyncModel.groupChangeCommands(GroupChange.NewGroup("")))
        assertEquals("新组", args(SyncModel.groupChangeCommands(GroupChange.NewGroup("新组")).single()).getString("name"))
        val rename = SyncModel.groupChangeCommands(GroupChange.Rename("家里")).single()
        assertEquals("set_group_name", rename.op)
        assertEquals("家里", args(rename).getString("name"))
    }

    @Test
    fun renameStepsValidateAndOnlySendChanges() {
        val saved = webdav.copy(deviceName = "Pixel")
        assertEquals(Plan.Invalid("设备号只能用小写字母、数字和短横线"), SyncModel.renameSteps("pixel", "Pixel", saved, "Pixel"))
        assertEquals(Plan.Invalid("设备号不能以短横线开头或结尾"), SyncModel.renameSteps("pixel", "-p", saved, "Pixel"))
        assertEquals(Plan.Run(emptyList()), SyncModel.renameSteps("pixel", "pixel", saved, " Pixel "))
        val steps = runSteps(SyncModel.renameSteps("pixel", "pixel-2", saved, "新名"))
        assertEquals("rename_device", cmd(steps[0]).op)
        assertEquals("pixel-2", args(steps[0]).getString("id"))
        assertEquals("新名", JSONObject((steps[1] as Step.SetSettings).json).getString("device_name"))
        assertEquals("设备号已改，显示名称未保存（x）", SyncModel.renameFailure(1, "x"))
        assertEquals("x", SyncModel.renameFailure(0, "x"))
    }

    @Test
    fun groupNameChecks() {
        assertEquals("组名不能为空", SyncModel.checkGroupName(" ", required = true).second)
        assertNull(SyncModel.checkGroupName(" ", required = false).second)
        assertEquals("组名不能超过 64 个字", SyncModel.checkGroupName("字".repeat(65), false).second)
    }

    @Test
    fun deviceDetailAndLastSeen() {
        val zone = ZoneOffset.UTC
        val today = LocalDate.of(2026, 1, 2)
        val noon = today.atTime(12, 30).toEpochSecond(zone)
        assertEquals("今天 12:30", SyncModel.lastSeenText(noon, today, zone))
        assertEquals("昨天 12:30", SyncModel.lastSeenText(noon - 86400, today, zone))
        assertEquals("最后在线未知", SyncModel.lastSeenText(0, today, zone))
        assertEquals("Android · v1.2 · 今天 12:30", Device("p", "P", "android", appVersion = "v1.2", lastSeen = noon).detail(today, zone))
        assertEquals("未知设备（无法解密）", Device("x", readable = false).title)
    }

    @Test
    fun parseDevicesSortsSelfFirst() {
        val list = SyncModel.parseDevices(
            """{"devices":[{"id":"a","last_seen":5},{"id":"me","is_self":true,"last_seen":1,"group":"g","group_name":"组"},{"id":"","last_seen":9}]}""",
        )
        assertEquals(listOf("me", "a"), list.map { it.id })
        assertEquals("组", list.first().groupName)
    }

    @Test
    fun keyboardSyncIntervalFollowsSettings() {
        assertEquals(15uL * 60uL, SyncModel.intervalSecs("""{"settings":{"enabled":true}}"""))
        assertEquals(30uL * 60uL, SyncModel.intervalSecs("""{"settings":{"interval_min":30}}"""))
        assertEquals(1440uL * 60uL, SyncModel.intervalSecs("""{"settings":{"interval_min":99999}}"""))
        assertEquals(60uL, SyncModel.intervalSecs("""{"settings":{"interval_min":0}}"""))
        assertEquals(15uL * 60uL, SyncModel.intervalSecs("不是 JSON"))
    }
}
