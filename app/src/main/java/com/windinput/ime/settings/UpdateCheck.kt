package com.windinput.ime.settings

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 「关于 → 检查更新」。
 *
 * **只在用户点了「检查更新」才联网**：启动、打开设置页、键盘工作时都不会发任何请求。
 * 请求就是对官网静态文件的一次 GET（与浏览器打开网页相同），不带输入内容，也不带设备标识。
 * 不自己下载安装：发现新版本只把 APK 直链交给浏览器，下载与安装由用户在系统里完成。
 *
 * 元数据由文档站 WindInputDocs 从 GitHub Release 同步生成（data/latest-android.json，
 * 见该仓 scripts/sync_android_release.py），APK 经下载网关（dl.windinput.com，会计数）。
 */
internal object UpdateCheck {
    /** 文档站产物里的 latest-android.json。用站点域名本身，不依赖 dl 域的路径改写 */
    const val ENDPOINT = "https://windinput.com/latest-android.json"

    private const val TIMEOUT_MS = 8_000
    /** 元数据只有几百字节；对方异常返回大文件（或把 HTML 回落页当 JSON）时早点放弃 */
    private const val MAX_BYTES = 64 * 1024

    data class Latest(
        val version: String,
        val apkUrl: String,
        val size: Long,
        val releaseNotesUrl: String,
    )

    sealed interface Result {
        data class UpToDate(val current: String, val latest: String) : Result
        data class Available(val latest: Latest) : Result
        data class Failed(val reason: String) : Result
    }

    /** 阻塞调用，放在 IO 线程。 */
    fun check(currentVersion: String): Result {
        val body = try {
            fetch()
        } catch (e: java.io.IOException) {
            return Result.Failed("网络不可用或被系统限制联网")
        } catch (e: SecurityException) {
            return Result.Failed("没有网络权限")
        }
        val latest = parse(body) ?: return Result.Failed("官网返回的内容无法识别，稍后再试")
        return if (isNewer(currentVersion, latest.version)) Result.Available(latest)
        else Result.UpToDate(currentVersion, latest.version)
    }

    private fun fetch(): String {
        val conn = URL(ENDPOINT).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.instanceFollowRedirects = true
            conn.requestMethod = "GET"
            if (conn.responseCode != 200) throw java.io.IOException("HTTP ${conn.responseCode}")
            val out = java.io.ByteArrayOutputStream()
            conn.inputStream.use { input ->
                val buf = ByteArray(4096)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > MAX_BYTES) throw java.io.IOException("响应过大")
                }
            }
            return out.toString(Charsets.UTF_8.name())
        } finally {
            conn.disconnect()
        }
    }

    /** 解析失败（含静态托管回落成 HTML 首页的情况）返回 null。只认 https 的 APK 直链。 */
    internal fun parse(json: String): Latest? = try {
        val o = JSONObject(json)
        val version = o.getString("version")
        val apk = o.getString("apkUrl")
        if (version.isBlank() || !apk.startsWith("https://")) null
        else Latest(version, apk, o.optLong("size"), o.optString("releaseNotesUrl"))
    } catch (e: org.json.JSONException) {
        null
    }

    /**
     * latest 是否比 current 新。按 `主.次.补丁` 数字比；核心相同时，正式版新于预发布版
     * （0.5.4 > 0.5.4-dev.3），都是预发布则比后缀里的数字（dev.4 > dev.3）。
     * 解析不出来的当作「不更新」，宁可漏提示也不误导。
     */
    internal fun isNewer(current: String, latest: String): Boolean {
        val (cCore, cPre) = split(current) ?: return false
        val (lCore, lPre) = split(latest) ?: return false
        val core = compare(lCore, cCore)
        if (core != 0) return core > 0
        if (cPre == null && lPre == null) return false
        if (cPre == null) return false
        if (lPre == null) return true
        return compare(lPre, cPre) > 0
    }

    private fun split(v: String): Pair<List<Int>, List<Int>?>? {
        val s = v.trim().removePrefix("v")
        val core = s.substringBefore('-')
        val nums = core.split('.').map { it.toIntOrNull() ?: return null }
        if (nums.isEmpty()) return null
        val pre = if ('-' in s) Regex("""\d+""").findAll(s.substringAfter('-')).map { it.value.toInt() }.toList() else null
        return nums to pre
    }

    private fun compare(a: List<Int>, b: List<Int>): Int {
        for (i in 0 until maxOf(a.size, b.size)) {
            val d = a.getOrElse(i) { 0 }.compareTo(b.getOrElse(i) { 0 })
            if (d != 0) return d
        }
        return 0
    }
}
