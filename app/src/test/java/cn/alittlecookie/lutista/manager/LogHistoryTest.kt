package cn.alittlecookie.lutista.manager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

class LogHistoryTest {

    @Test
    fun `keeps only the newest lines`() {
        val history = LogHistory(capacity = 3)

        history.add("1")
        history.add("2")
        history.add("3")
        history.add("4")

        assertEquals(listOf("2", "3", "4"), history.snapshot())
        assertEquals(3, history.size())
    }

    @Test
    fun `ignores blank lines`() {
        val history = LogHistory(capacity = 4)

        history.add("a")
        history.add("")
        history.add("b")

        assertEquals(listOf("a", "b"), history.snapshot())
    }

    @Test
    fun `clear empties the buffer`() {
        val history = LogHistory(capacity = 4)
        history.add("a")
        history.add("b")

        history.clear()

        assertEquals(0, history.size())
        assertTrue(history.snapshot().isEmpty())
    }

    @Test
    fun `snapshot is a copy`() {
        val history = LogHistory(capacity = 4)
        history.add("a")

        val snapshot = history.snapshot()
        history.add("b")

        // 快照是写入时刻的视图，后续写入不得影响它。
        assertEquals(listOf("a"), snapshot)
        assertEquals(listOf("a", "b"), history.snapshot())
    }

    /**
     * 写入方是 logcat 捕获协程与 Timber 调用线程，崩溃时还要并发读快照。
     * 这里用 8 个线程各写 500 行，验证容量上限不被突破、不抛并发异常。
     */
    @Test
    fun `concurrent writers stay within capacity`() {
        val history = LogHistory(capacity = 100)
        val threads = 8
        val perThread = 500
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)

        repeat(threads) { t ->
            pool.execute {
                start.await()
                repeat(perThread) { i -> history.add("t$t-$i") }
                done.countDown()
            }
        }
        start.countDown()
        done.await()
        pool.shutdown()

        assertEquals(100, history.size())
        assertEquals(100, history.snapshot().size)
    }

    @Test
    fun `default capacity matches documented value`() {
        assertEquals(2000, LogHistory.DEFAULT_CAPACITY)
        assertEquals(0, LogHistory().size())
    }
}
