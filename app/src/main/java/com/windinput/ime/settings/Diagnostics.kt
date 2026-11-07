package com.windinput.ime.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Process
import android.util.Log
import com.windinput.ime.core.CrashLog
import com.windinput.ime.core.LogLevel
import java.io.File

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

    private const val TAG = "Diagnostics"

    /** 只读本进程的日志（应用读不到别的进程），过滤见类注释 */
    internal fun logcatCommand(pid: Int): List<String> = listOf(
        "logcat", "-d", "-t", LOG_LINES.toString(), "--pid", pid.toString(), "-v", "time",
        "WindTestPad:S", "*:I",
    )

    /** 拼成最终文本（纯函数，单测覆盖） */
    fun compose(header: List<Pair<String, String>>, logTail: List<String>, crash: String?): String = buildString {
        append("清风输入法诊断信息\n")
        for ((k, v) in header) append(k).append("：").append(v).append('\n')
        append("\n── 最近一次崩溃 ──\n")
        append(crash?.trimEnd() ?: "（无）").append('\n')
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
            "核心日志级别" to LogLevel.read(File(context.filesDir, "rust"), File(context.filesDir, "data")),
        )
        val logs = runCatching {
            val proc = ProcessBuilder(logcatCommand(Process.myPid())).redirectErrorStream(true).start()
            proc.inputStream.bufferedReader().use { it.readLines() }.also { proc.waitFor() }
        }.onFailure { Log.w(TAG, "读日志失败", it) }.getOrDefault(emptyList())
        return compose(header, logs, CrashLog.last(context))
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
