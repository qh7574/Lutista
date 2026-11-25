package cn.alittlecookie.lutista.tether

import cn.alittlecookie.lutista.tether.transport.RxBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [RxBuffer]：跨 URB 残留、连续读出、compact/扩容、边界。
 */
class RxBufferTest {

    @Test
    fun `初始状态为空`() {
        val buffer = RxBuffer(64)
        assertTrue(buffer.isEmpty)
        assertEquals(0, buffer.size)
        assertEquals(64, buffer.capacity)
    }

    @Test
    fun `追加与读出`() {
        val buffer = RxBuffer(64)
        buffer.append(byteArrayOf(1, 2, 3, 4))
        assertEquals(4, buffer.size)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), buffer.peek(4))
        assertArrayEquals(byteArrayOf(1, 2), buffer.read(2))
        assertEquals(2, buffer.size)
        assertArrayEquals(byteArrayOf(3, 4), buffer.read(2))
        assertTrue(buffer.isEmpty)
    }

    @Test
    fun `跨 URB 残留 - 第一次读剩一半 第二次追加后拼起来`() {
        val buffer = RxBuffer(64)
        buffer.append(byteArrayOf(10, 11, 12, 13, 14))
        assertArrayEquals(byteArrayOf(10, 11, 12), buffer.read(3))

        // 第二个 URB 追加，残留的 [13, 14] 必须还在前面
        buffer.append(byteArrayOf(15, 16, 17))
        assertEquals(5, buffer.size)
        assertArrayEquals(byteArrayOf(13, 14, 15, 16, 17), buffer.read(5))
    }

    @Test
    fun `连续读出 - 多次小口读不会打乱顺序`() {
        val buffer = RxBuffer(64)
        val source = ByteArray(20) { it.toByte() }
        buffer.append(source)
        val collected = ArrayList<Byte>()
        while (!buffer.isEmpty) {
            val slice = buffer.read(minOf(3, buffer.size))
            slice.forEach { collected += it }
        }
        assertArrayEquals(source, collected.toByteArray())
    }

    @Test
    fun `copyTo 支持源偏移且不移动游标`() {
        val buffer = RxBuffer(64)
        buffer.append(byteArrayOf(1, 2, 3, 4, 5))
        val dest = ByteArray(6) { 0x7F }
        buffer.copyTo(dest, destOffset = 1, count = 3, srcOffset = 2)
        assertArrayEquals(byteArrayOf(0x7F, 3, 4, 5, 0x7F, 0x7F), dest)
        assertEquals(5, buffer.size)
    }

    @Test
    fun `compact - 消费后追加会复用头部空间`() {
        val buffer = RxBuffer(16)
        buffer.append(ByteArray(12) { it.toByte() })
        assertEquals(12, buffer.size)
        buffer.skip(8)
        // 尾部只剩 4 字节，追加 8 字节必须靠 compact 而不是扩容
        buffer.append(ByteArray(8) { (100 + it).toByte() })
        assertEquals(16, buffer.capacity)
        assertEquals(12, buffer.size)
        val out = buffer.read(12)
        assertArrayEquals(ByteArray(4) { (8 + it).toByte() } + ByteArray(8) { (100 + it).toByte() }, out)
    }

    @Test
    fun `reserveTail - 空间不足时倍增扩容`() {
        val buffer = RxBuffer(16)
        buffer.append(ByteArray(16) { 1 })
        assertEquals(16, buffer.size)
        val tail = buffer.reserveTail(64)
        assertTrue("tail=$tail", tail >= 64)
        assertTrue(buffer.capacity > 16)
        buffer.append(ByteArray(64) { 7 })
        assertEquals(80, buffer.size)
    }

    @Test
    fun `read 0 字节返回空数组且不动游标`() {
        val buffer = RxBuffer(16)
        buffer.append(byteArrayOf(1, 2))
        assertArrayEquals(ByteArray(0), buffer.read(0))
        assertEquals(2, buffer.size)
    }

    @Test
    fun `skip 整段后状态归零且下次追加从头开始`() {
        val buffer = RxBuffer(16)
        buffer.append(byteArrayOf(1, 2, 3))
        buffer.skip(3)
        assertEquals(0, buffer.readOffset)
        assertEquals(0, buffer.size)
        buffer.append(byteArrayOf(9))
        assertArrayEquals(byteArrayOf(9), buffer.read(1))
    }

    @Test
    fun `边界 - skip 超过可用字节抛 IllegalArgumentException`() {
        val buffer = RxBuffer(16)
        buffer.append(byteArrayOf(1, 2))
        try {
            buffer.skip(3)
            fail("应当抛 IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("skip 越界"))
        }
    }

    @Test
    fun `边界 - read 超过可用字节抛 IllegalArgumentException`() {
        val buffer = RxBuffer(16)
        buffer.append(byteArrayOf(1, 2))
        try {
            buffer.read(3)
            fail("应当抛 IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("copyTo 源范围非法"))
        }
    }

    @Test
    fun `边界 - append 的 offset 与 length 非法`() {
        val buffer = RxBuffer(16)
        try {
            buffer.append(byteArrayOf(1, 2), offset = 1, length = 5)
            fail("应当抛 IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("append 范围非法"))
        }
    }

    @Test
    fun `边界 - 追加 0 字节是空操作`() {
        val buffer = RxBuffer(16)
        buffer.append(byteArrayOf(1, 2))
        buffer.append(byteArrayOf(3, 4), offset = 2, length = 0)
        assertEquals(2, buffer.size)
        assertEquals(16, buffer.capacity)
    }

    @Test
    fun `reserveTail - 超大 required 抛 IllegalStateException 而不是死循环`() {
        // 静态推导（QA）：newCapacity 倍增到 2^30 时 *2 溢出成负数、再变 0，
        // 之后 `0 - end < required` 恒真 → 不分配内存、不报错、只静默烧 CPU。
        // 这里不走"真触发"的路径（会挂死 CI/OOM），只断言它明确抛出。
        val buffer = RxBuffer(16)
        buffer.append(ByteArray(16) { 1 })
        try {
            buffer.reserveTail(Int.MAX_VALUE - 16)
            fail("超出可扩容范围必须抛 IllegalStateException")
        } catch (expected: IllegalStateException) {
            assertTrue("异常信息应指明溢出：${expected.message}", expected.message!!.contains("溢出"))
        }
    }

    @Test
    fun `clear 保留容量并清空游标`() {
        val buffer = RxBuffer(32)
        buffer.append(ByteArray(20))
        buffer.clear()
        assertEquals(0, buffer.size)
        assertEquals(32, buffer.capacity)
        assertEquals(0, buffer.readOffset)
    }
}
