package com.windinput.ime.settings

import com.windinput.ime.settings.SyncModel.BackendType
import com.windinput.ime.settings.SyncModel.ErrorKind
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncModelTest {

    @Test
    fun phaseTextMapsKnownAndKeepsUnknown() {
        assertEquals("空闲，已是最新", SyncModel.phaseText("idle"))
        assertEquals("同步中…", SyncModel.phaseText("syncing"))
        assertEquals("需要设置口令或导入密钥", SyncModel.phaseText("needs_setup"))
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
    fun statusParsesNullsAndFlags() {
        val s = SyncModel.parseStatus(
            """{"enabled":true,"phase":"idle","last_ok":1700000000,"last_error":null,"pending":2,"conflicts":1,"has_secret":true,"joined":true}""",
        )
        assertTrue(s.enabled && s.hasSecret && s.joined && s.canSyncNow)
        assertEquals(1700000000L, s.lastOk)
        assertEquals("", s.lastError)
        assertEquals(1, s.conflicts)
        assertNull(SyncModel.parseStatus("""{"phase":"idle","last_ok":null}""").lastOk)
        assertEquals("", SyncModel.parseStatus("不是 JSON").phase)
    }

    @Test
    fun syncNowOnlyWhenEnabledAndNotBusy() {
        assertFalse(SyncModel.Status(enabled = true, phase = "syncing").canSyncNow)
        assertFalse(SyncModel.Status(enabled = false, phase = "idle").canSyncNow)
        assertTrue(SyncModel.Status(enabled = true, phase = "error").canSyncNow)
    }

    @Test
    fun settingsRoundTripKeepsUnknownFieldsAndSwitchesBackend() {
        val base = """{"settings":{"enabled":false,"scope":{"config":true,"schemas":true,"themes":true,"dicts":true},"interval_min":30,"device_name":"","future":1},"has_secret":false}"""
        val d = SyncModel.parseSettings(base)
        assertEquals(BackendType.WEBDAV, d.type) // 缺后端时界面默认 WebDAV
        val out = JSONObject(
            SyncModel.toSettingsJson(base, d.copy(enabled = true, url = " https://x/y ", user = "u", deviceName = "Pixel", themes = false)),
        )
        assertEquals(30, out.getInt("interval_min"))
        assertEquals(1, out.getInt("future"))
        assertTrue(out.getBoolean("enabled"))
        assertFalse(out.getJSONObject("scope").getBoolean("themes"))
        assertEquals("https://x/y", out.getJSONObject("backend").getString("url"))
        assertEquals("webdav", out.getJSONObject("backend").getString("type"))
        assertEquals("Pixel", out.getString("device_name"))
        // 不得包含任何密码字段
        assertFalse(out.toString().contains("password"))
    }

    @Test
    fun s3BackendParsesAndWrites() {
        val json = """{"settings":{"enabled":true,"backend":{"type":"s3","endpoint":"http://h:9000","bucket":"b","access_key_id":"k","path_style":true,"region":"","prefix":"p"}}}"""
        val d = SyncModel.parseSettings(json)
        assertEquals(BackendType.S3, d.type)
        assertTrue(d.pathStyle && SyncModel.backendReady(d))
        val b = JSONObject(SyncModel.toSettingsJson(json, d)).getJSONObject("backend")
        assertEquals("s3", b.getString("type"))
        assertEquals("p", b.getString("prefix"))
        assertTrue(b.getBoolean("path_style"))
        assertFalse(SyncModel.backendReady(d.copy(bucket = "")))
    }

    @Test
    fun passwordIsWriteOnly() {
        assertNull(SyncModel.secretArgs(""))
        assertEquals("pw", JSONObject(SyncModel.secretArgs("pw")!!).getString("password"))
        assertTrue(SyncModel.passwordHint(BackendType.WEBDAV, true).contains("已保存"))
        assertFalse(SyncModel.passwordHint(BackendType.WEBDAV, false).contains("已保存"))
    }

    @Test
    fun passphraseMustMatchAndBeNonEmpty() {
        assertEquals("口令不能为空", SyncModel.checkPassphrase("", ""))
        assertEquals("两次输入的口令不一致", SyncModel.checkPassphrase("a", "b"))
        assertNull(SyncModel.checkPassphrase("abc", "abc"))
        val o = JSONObject(SyncModel.passphraseArgs("new", "old"))
        assertEquals("old", o.getString("old_passphrase"))
        assertFalse(JSONObject(SyncModel.passphraseArgs("new", "")).has("old_passphrase"))
    }

    @Test
    fun destructiveArgsCarryConfirm() {
        assertTrue(JSONObject(SyncModel.resetArgs("p")).getBoolean("confirm"))
        val r = JSONObject(SyncModel.removeDeviceArgs("d1"))
        assertEquals("d1", r.getString("dev"))
        assertTrue(r.getBoolean("confirm"))
    }

    @Test
    fun selfDeviceCannotBeRemoved() {
        val ds = SyncModel.parseDevices(
            """{"devices":[{"id":"aaaaaaaa11","name":"Pixel","platform":"mobile","is_self":true,"readable":true,"last_seen":5},
               {"id":"bbbbbbbb22","name":"","is_self":false,"readable":false}]}""",
        )
        assertEquals(2, ds.size)
        assertFalse(ds[0].removable)
        assertTrue(ds[1].removable)
        assertEquals("Pixel", ds[0].title)
        assertTrue(ds[1].title.startsWith("无法识别"))
    }

    @Test
    fun deviceNameDefaultsToModelOnlyWhenBlank() {
        assertEquals("Pixel 8", SyncModel.defaultDeviceName("  ", "Pixel 8"))
        assertEquals("我的手机", SyncModel.defaultDeviceName("我的手机", "Pixel 8"))
    }

    @Test
    fun relativeTime() {
        assertEquals("从未", SyncModel.ago(0, 100))
        assertEquals("刚刚", SyncModel.ago(95, 100))
        assertEquals("5 分钟前", SyncModel.ago(100, 400))
        assertEquals("2 小时前", SyncModel.ago(0 + 1, 1 + 7200))
        assertEquals("已是最新 · 上次成功 3 天前", SyncModel.statusSummary(SyncModel.Status(true, "idle", lastOk = 1), 1 + 3 * 86400))
    }

    @Test
    fun exportedKeyParses() {
        assertEquals("abc", SyncModel.parseExportedKey("""{"key":"abc"}"""))
        assertNull(SyncModel.parseExportedKey("""{"key":""}"""))
    }
}
