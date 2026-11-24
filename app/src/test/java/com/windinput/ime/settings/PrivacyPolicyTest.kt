package com.windinput.ime.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 隐私政策里写的事实要与清单一致：改了权限却忘了改政策，这里失败 */
class PrivacyPolicyTest {

    private val permissions = Regex("""<uses-permission\s+android:name="([^"]+)"""")
        .findAll(File("src/main/AndroidManifest.xml").readText())
        .map { it.groupValues[1] }
        .toSet()

    /** 政策写的是「只申请振动与网络（网络用于检查更新与用户启用的数据同步）」 */
    @Test
    fun manifestRequestsOnlyVibrateAndInternet() {
        assertEquals(
            setOf("android.permission.VIBRATE", "android.permission.INTERNET"),
            permissions,
        )
    }

    @Test
    fun policyCoversRequiredTopics() {
        val titles = PRIVACY_POLICY.map { it.first }
        for (t in listOf("联网", "启用时的系统提示", "数据只存本地", "权限用途", "删除数据")) {
            assertTrue("隐私政策缺「$t」", t in titles)
        }
    }
}
