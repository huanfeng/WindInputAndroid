package com.windinput.ime.settings

import com.windinput.ime.core.CrashLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

class DiagnosticsTest {

    @Test
    fun composeListsHeaderCrashAndLogs() {
        val text = Diagnostics.compose(
            listOf("版本" to "0.3.0", "机型" to "Xiaomi LX04"),
            listOf("I/WindIme: a", "W/RustSession: b"),
            "时间：x\njava.lang.IllegalStateException: boom\n",
        )
        assertTrue(text.contains("版本：0.3.0\n机型：Xiaomi LX04\n"))
        assertTrue(text.contains("IllegalStateException: boom"))
        assertTrue(text.contains("（INFO 及以上，2 行）"))
        assertTrue(text.endsWith("W/RustSession: b\n"))
    }

    @Test
    fun composeMarksMissingCrashAndLogs() {
        val text = Diagnostics.compose(emptyList(), emptyList(), null)
        assertTrue(text.contains("── 最近一次崩溃 ──\n（无）"))
        assertTrue(text.contains("（读不到日志）"))
    }

    /** 不含用户文字：只要 INFO 及以上，测试台记原文的标签整个静音，只读本进程 */
    @Test
    fun logcatFiltersOutVerboseAndTestPad() {
        val cmd = Diagnostics.logcatCommand(1234)
        assertEquals(listOf("WindTestPad:S", "*:I"), cmd.takeLast(2))
        assertEquals("1234", cmd[cmd.indexOf("--pid") + 1])
        assertTrue("-d" in cmd)
    }

    @Test
    fun crashRecordHasThreadAndStack() {
        val text = CrashLog.format(Date(0), "main", IllegalStateException("boom"))
        assertTrue(text.contains("线程：main"))
        assertTrue(text.contains("java.lang.IllegalStateException: boom"))
        assertTrue(text.contains("DiagnosticsTest"))
    }

    @Test
    fun hugeStacksAreClipped() {
        val deep = RuntimeException("x".repeat(40_000))
        val text = CrashLog.format(Date(0), "t", deep)
        assertTrue(text.length < 17_000)
        assertTrue(text.endsWith("（已截断）"))
    }
}
