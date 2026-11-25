package cn.alittlecookie.lutista.tether.transport

import cn.alittlecookie.lutista.BuildConfig
import timber.log.Timber
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * `ptp-io` 线程断言。
 *
 * 存在理由（定稿 §6 约束 3 / 约束 7）：所有 USB I/O 必须走同一个后台线程——
 * 主线程做 USB I/O 会 ANR；同一个 `UsbDeviceConnection` 多线程并发 `bulkTransfer` 全部返回 -1。
 *
 * 行为：
 * - **debug**：不匹配直接抛 [IllegalStateException]（**程序错误**：调用方违反了线程契约，
 *   不是相机拒绝，也不是协议错误——调用方不要把它 catch 成「相机不支持」）；
 * - **release**：只计数 + 打点（绝不因为断言把用户的会话打断）。
 *
 * 用法（T02 的 `PtpScheduler` 负责）：**创建 `ptp-io` 线程后第一件事就是 [bind]**，
 * 线程退出/会话关闭时 [unbind]；所有 transport 方法入口调 [requireIoThread]。
 * 忘了 bind 的后果是 debug 下所有 transport 调用立即抛 —— 这是故意设计，不要改成静默放行。
 */
object UsbIoThread {

    /** IO 线程名（调度器创建线程时必须用这个名字）。 */
    const val THREAD_NAME: String = "ptp-io"

    private val boundThread = AtomicReference<Thread?>(null)
    private val violations = AtomicInteger(0)

    /** 绑定当前（或指定）线程为 IO 线程。可重复调用以覆盖绑定。 */
    fun bind(thread: Thread = Thread.currentThread()) {
        boundThread.set(thread)
    }

    /** 解绑（线程退出 / 会话关闭时调用）。 */
    fun unbind() {
        boundThread.set(null)
    }

    /** 当前线程是否就是绑定的 IO 线程。 */
    fun isIoThread(): Boolean = boundThread.get() === Thread.currentThread()

    /** 是否已经绑定过 IO 线程。 */
    fun isBound(): Boolean = boundThread.get() != null

    /**
     * 断言当前在 IO 线程。
     *
     * debug 抛 [IllegalStateException]；release 只计数 + `Timber.w` 打点。
     *
     * @param what 调用点说明（进日志/异常信息，例 `"PtpUsbTransport.transaction"`）。
     */
    fun requireIoThread(what: String = "PtpTransport") {
        if (isIoThread()) return
        violations.incrementAndGet()
        val current = Thread.currentThread()
        val bound = boundThread.get()
        val message = "$what 必须在 ${THREAD_NAME} 线程调用，" +
            "实际线程=${current.name}（已绑定=${bound?.name ?: "未绑定"}）"
        if (BuildConfig.DEBUG) throw IllegalStateException(message)
        Timber.w(message)
    }

    /** 断言失败次数（诊断面板用；release 下唯一能被观测到的信号）。 */
    fun violationCount(): Int = violations.get()

    /** 仅测试用：清空绑定与计数。 */
    fun resetForTest() {
        boundThread.set(null)
        violations.set(0)
    }
}
