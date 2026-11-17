package com.windinput.ime.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Process
import android.util.Log
import com.windinput.ime.core.CrashLog
import com.windinput.ime.core.LogLevel
import com.windinput.ime.core.UiTrace
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 「关于」里的反馈与诊断：打开 issue 页、经系统分享发出一份诊断信息。
 *
 * 诊断信息 = 版本、系统、机型 + 本应用最近的日志尾部 + 最近一次 K 侧崩溃栈。**不含用户文字**：
 * - 日志只取 INFO 及以上（核心的约定是 info 及以上不记用户输入；带文字的逐键日志是 DEBUG），
 * - 测试台（设置首页「试一试」）把输入框原文记在 WindTestPad 标签下（e2e 靠它读结果），整个排除。
 * 发不发、发给谁都由用户在系统分享面板里选，本应用自己不联网。
 */
object Diagnostics {

    const val ISSUE_URL = "https://github.com/huanfeng/WindInputAndroid/issues/new"

    /** 日志尾部取几行：够看清出错前后，又不至于贴进 issue 里一大屏 */
    const val LOG_LINES = 300

    /** 压缩包里的日志取几行：跨进程（含崩溃前的那次运行），比分享文本里的尾部大得多 */
    const val ZIP_LOG_LINES = 5000

    private const val TAG = "Diagnostics"
    private const val ZIP_DIR = "diagnostics"

    /** 只读本进程的日志（应用读不到别的进程），过滤见类注释 */
    internal fun logcatCommand(pid: Int): List<String> = listOf(
        "logcat", "-d", "-t", LOG_LINES.toString(), "--pid", pid.toString(), "-v", "time",
        "WindTestPad:S", "*:I",
    )

    /**
     * 压缩包用的日志命令：不按 pid 过滤。应用读 logcat 本来就只能读到**自己 uid** 的日志，
     * 去掉 pid 过滤后能带上崩溃前那次运行（旧进程）的日志——按 pid 取的话重启后全是新进程的。
     * 过滤规则同 [logcatCommand]（INFO 及以上，测试台标签静音）。[crashBuffer] 读系统的 crash 缓冲区。
     */
    internal fun zipLogcatCommand(crashBuffer: Boolean): List<String> = buildList {
        add("logcat"); add("-d")
        if (crashBuffer) { add("-b"); add("crash") }
        addAll(listOf("-t", ZIP_LOG_LINES.toString(), "-v", "threadtime", "WindTestPad:S", "*:I"))
    }

    private fun readLogcat(cmd: List<String>): List<String> = runCatching {
        val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
        proc.inputStream.bufferedReader().use { it.readLines() }.also { proc.waitFor() }
    }.onFailure { Log.w(TAG, "读日志失败", it) }.getOrDefault(emptyList())

    /**
     * 打成压缩包（**后台线程调用**）：diagnostics.txt（同分享文本）、logcat.txt、logcat-crash.txt、crash.txt。
     * 放 cacheDir/diagnostics/，每次先清掉旧包；经 FileProvider 分享，内容范围同分享文本（不含用户输入）。
     */
    fun buildZip(context: Context, versionName: String): File {
        val dir = File(context.cacheDir, ZIP_DIR).apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val out = File(dir, "WindInput-diagnostics-$versionName-$stamp.zip")
        val entries = linkedMapOf(
            "diagnostics.txt" to collect(context, versionName),
            "logcat.txt" to readLogcat(zipLogcatCommand(false)).joinToString("\n"),
            "logcat-crash.txt" to readLogcat(zipLogcatCommand(true)).joinToString("\n"),
            "crash.txt" to (CrashLog.last(context) ?: "（无）"),
        )
        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            for ((name, text) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
        }
        return out
    }

    fun shareZip(context: Context, zip: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", zip)
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/zip")
            .putExtra(Intent.EXTRA_SUBJECT, "清风输入法诊断包")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, "导出诊断包").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** 拼成最终文本（纯函数，单测覆盖） */
    fun compose(
        header: List<Pair<String, String>>,
        logTail: List<String>,
        crash: String?,
        ui: List<String> = emptyList(),
    ): String = buildString {
        append("清风输入法诊断信息\n")
        for ((k, v) in header) append(k).append("：").append(v).append('\n')
        append("\n── 最近一次崩溃 ──\n")
        append(crash?.trimEnd() ?: "（无）").append('\n')
        if (ui.isNotEmpty()) {
            append("\n── 界面诊断（键盘避让/insets，不含用户输入）──\n")
            for (line in ui) append(line).append('\n')
        }
        append("\n── 最近日志（INFO 及以上，").append(logTail.size).append(" 行）──\n")
        if (logTail.isEmpty()) append("（读不到日志）\n")
        for (line in logTail) append(line).append('\n')
    }

    /** 收集并拼好（读日志要起子进程，**后台线程调用**） */
    fun collect(context: Context, versionName: String): String {
        val header = listOf(
            "版本" to versionName,
            "系统" to "Android ${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）",
            "机型" to "${Build.MANUFACTURER} ${Build.MODEL}",
            "架构" to Build.SUPPORTED_ABIS.joinToString(),
            "核心日志级别" to LogLevel.read(
                (context.applicationContext as com.windinput.ime.WindInputApp).dataDirs.engineConfigFile,
                File(context.filesDir, "data"),
            ),
        )
        val logs = runCatching {
            val proc = ProcessBuilder(logcatCommand(Process.myPid())).redirectErrorStream(true).start()
            proc.inputStream.bufferedReader().use { it.readLines() }.also { proc.waitFor() }
        }.onFailure { Log.w(TAG, "读日志失败", it) }.getOrDefault(emptyList())
        return compose(header, logs, CrashLog.last(context), UiTrace.deviceInfo(context) + UiTrace.snapshot())
    }

    fun openIssues(context: Context) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ISSUE_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { Log.w(TAG, "打不开反馈页", it) }
    }

    fun share(context: Context, text: String) {
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "清风输入法诊断信息")
            .putExtra(Intent.EXTRA_TEXT, text)
        context.startActivity(Intent.createChooser(send, "分享诊断信息").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
