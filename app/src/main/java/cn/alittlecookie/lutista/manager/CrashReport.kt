package cn.alittlecookie.lutista.manager

/**
 * 崩溃报告的纯格式化逻辑（不碰文件系统、不依赖 Android），方便单元测试。
 *
 * 之所以单独抽出来：崩溃处理运行在已经不稳定的线程上，能做的只有字符串拼接 + 一次
 * 阻塞写。把「拼什么」和「写到哪」分开，前者可以用测试锁住格式，后者只剩最少的代码。
 */
object CrashReport {

    /**
     * @param environment 环境信息（版本、SHA、机型、系统、ABI…），逐行拼接
     * @param threadInfo  崩溃线程描述，例如 `main (id=2)`
     * @param history     崩溃前的日志回放，按时间正序
     * @param error       未捕获异常
     * @param timestamp   可读时间戳，由调用方决定格式（测试中可以传固定值）
     */
    fun format(
        environment: List<String>,
        threadInfo: String,
        history: List<String>,
        error: Throwable,
        timestamp: String
    ): String = buildString {
        appendLine("==== Lutista 崩溃报告 ====")
        appendLine("时间: $timestamp")
        appendLine()
        appendLine("---- 环境 ----")
        environment.forEach { appendLine(it) }
        appendLine()
        appendLine("---- 崩溃线程 ----")
        appendLine(threadInfo)
        appendLine()
        // 混淆后的堆栈需要 mapping.txt 才能还原：这里必须写下对应的 gitSha / versionCode，
        // 否则拿到日志也不知道该用哪一份 mapping 去 retrace。
        appendLine("---- 还原方式 ----")
        appendLine("release/perf 包为 R8 混淆产物，用与之 versionCode + gitSha 对应的")
        appendLine("app/build/outputs/mapping/<variant>/mapping.txt 执行 retrace 还原下方堆栈。")
        appendLine()
        appendLine("---- 最近 ${history.size} 行日志（旧 -> 新）----")
        if (history.isEmpty()) {
            appendLine("(无：崩溃发生在日志缓冲建立之前，或日志开关处于关闭状态)")
        } else {
            history.forEach { appendLine(it) }
        }
        appendLine()
        appendLine("---- 异常 ----")
        appendLine(error.stackTraceToString())
    }
}
