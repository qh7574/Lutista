package cn.alittlecookie.lutista.manager

import org.junit.Assert.assertTrue
import org.junit.Test

class CrashReportTest {

    private val environment = listOf(
        "version: 1.0 (versionCode 1)",
        "gitSha: abc1234",
        "device: Xiaomi 23127PN0CC"
    )

    @Test
    fun `report carries environment thread history and stack`() {
        val error = IllegalStateException("filter pipeline blew up")

        val report = CrashReport.format(
            environment = environment,
            threadInfo = "main (id=2)",
            history = listOf("12:00:00.000 I(Test): before crash"),
            error = error,
            timestamp = "2026-11-20 12:00:01.000"
        )

        assertTrue(report.contains("gitSha: abc1234"))
        assertTrue(report.contains("device: Xiaomi 23127PN0CC"))
        assertTrue(report.contains("main (id=2)"))
        assertTrue(report.contains("12:00:00.000 I(Test): before crash"))
        assertTrue(report.contains("java.lang.IllegalStateException: filter pipeline blew up"))
        // 崩溃现场必须能定位到代码位置，才谈得上修复。
        assertTrue(report.contains("CrashReportTest"))
    }

    @Test
    fun `empty history is called out instead of silently missing`() {
        val report = CrashReport.format(
            environment = environment,
            threadInfo = "RenderThread (id=42)",
            history = emptyList(),
            error = RuntimeException("boom"),
            timestamp = "2026-11-20 12:00:01.000"
        )

        assertTrue(report.contains("最近 0 行日志"))
        assertTrue(report.contains("日志开关处于关闭状态"))
    }

    @Test
    fun `caused by chain is preserved`() {
        val root = IllegalArgumentException("bad lut")
        val wrapper = RuntimeException("decode failed", root)

        val report = CrashReport.format(
            environment = environment,
            threadInfo = "main (id=2)",
            history = listOf("line"),
            error = wrapper,
            timestamp = "2026-11-20 12:00:01.000"
        )

        assertTrue(report.contains("java.lang.RuntimeException: decode failed"))
        assertTrue(report.contains("Caused by: java.lang.IllegalArgumentException: bad lut"))
    }

    @Test
    fun `report tells how to retrace an obfuscated stack`() {
        val report = CrashReport.format(
            environment = environment,
            threadInfo = "main (id=2)",
            history = emptyList(),
            error = RuntimeException("boom"),
            timestamp = "2026-11-20 12:00:01.000"
        )

        assertTrue(report.contains("retrace"))
        assertTrue(report.contains("mapping.txt"))
    }
}
