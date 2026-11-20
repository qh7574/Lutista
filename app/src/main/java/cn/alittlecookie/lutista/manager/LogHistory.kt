package cn.alittlecookie.lutista.manager

/**
 * 定长日志环形缓冲，只保留最近 [capacity] 行。
 *
 * 用途：崩溃时没有机会再读 logcat（进程立刻退出），必须靠内存里已有的历史把
 * 「崩溃前发生了什么」带出来。写入方有两路，且都在非主线程：
 * - [FileLogTree]（Timber 调用）
 * - [LogcatCaptureManager]（进程内所有 `android.util.Log` 与 native 日志）
 *
 * 因此实现必须线程安全；日志量不大，直接对整段加锁比无锁结构更不容易出错。
 * 关闭日志开关时会 [clear]，符合「关闭即不记录」的语义。
 */
class LogHistory(private val capacity: Int = DEFAULT_CAPACITY) {

    init {
        require(capacity > 0) { "capacity must be positive, was $capacity" }
    }

    private val lock = Any()
    private val lines = ArrayDeque<String>(capacity)

    /** 追加一行；超出容量时丢弃最旧的一行。空行忽略，避免污染崩溃回放。 */
    fun add(line: String) {
        if (line.isEmpty()) return
        synchronized(lock) {
            if (lines.size >= capacity) {
                lines.removeFirst()
            }
            lines.addLast(line)
        }
    }

    /** 按时间顺序返回一份快照副本；调用方可以随意遍历，不影响缓冲。 */
    fun snapshot(): List<String> = synchronized(lock) { lines.toList() }

    fun clear() {
        synchronized(lock) { lines.clear() }
    }

    fun size(): Int = synchronized(lock) { lines.size }

    companion object {
        const val DEFAULT_CAPACITY = 2000
    }
}
