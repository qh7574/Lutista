package cn.alittlecookie.lutista.tether

import cn.alittlecookie.lutista.tether.protocol.DeviceInfoParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * QA 独立复核（T01）：用**另一套解析实现**交叉验证 [DeviceInfoParser]。
 *
 * 为什么不能只重跑工程师的测试：那些断言与实现出自同一份理解，一旦理解错了会一起错。
 * 这里按 PIMA 15740 的字段顺序从零写一个小解析器（`IndependentDeviceInfoReader`），
 * 只看字节、不看被测代码，然后与被测解析器的结果逐字段比对。
 *
 * 另外用「把 count 读成 u16」的错误实现跑同一份真机 dump，
 * 证明定稿 §1.5 说的「五个数组长度都是 u32」这个坑**真实存在**、且被测代码是对的。
 */
class QaDeviceInfoCrossCheckTest {

    private val raw: ByteArray = Fixtures.hex("device_info")
    private val ours = DeviceInfoParser.parseDetailed(raw)

    // ─────────────── ① 独立解析器算出的「消耗字节数」 ───────────────

    @Test
    fun `独立解析 - 恰好消耗 223 字节且各数组个数正确`() {
        val reader = IndependentDeviceInfoReader(raw)
        val snapshot = reader.parse(u32Counts = true)

        assertEquals("GetDeviceInfo 回包必须被完整消耗", raw.size, reader.position)
        assertEquals(223, reader.position)
        assertEquals(100, snapshot.standardVersion)
        assertEquals(0x1CL, snapshot.vendorExtensionId)
        assertEquals(100, snapshot.vendorExtensionVersion)
        assertEquals("", snapshot.vendorExtensionDesc)
        assertEquals(0, snapshot.functionalMode)
        assertEquals(19, snapshot.operations.size)
        assertEquals(16, snapshot.events.size)
        assertEquals(1, snapshot.deviceProperties.size)
        assertEquals(0, snapshot.captureFormats.size)
        assertEquals(6, snapshot.imageFormats.size)
        assertEquals("Panasonic", snapshot.manufacturer)
        assertEquals("DC-S1", snapshot.model)
        assertEquals("2.4", snapshot.deviceVersion)
        assertEquals(31, snapshot.serialNumber.length)
    }

    @Test
    fun `独立解析与被测解析器逐字段一致`() {
        val other = IndependentDeviceInfoReader(raw).parse(u32Counts = true)

        assertEquals(other.standardVersion, ours.standardVersion)
        assertEquals(other.vendorExtensionId, ours.vendorExtensionId)
        assertEquals(other.vendorExtensionVersion, ours.vendorExtensionVersion)
        assertEquals(other.vendorExtensionDesc, ours.vendorExtensionDesc)
        assertEquals(other.functionalMode, ours.functionalMode)
        assertEquals(other.operations.map { it.toLong() }.toSet(), ours.operationsSupported)
        assertEquals(other.events.map { it.toLong() }.toSet(), ours.eventsSupported)
        assertEquals(other.deviceProperties.map { it.toLong() }.toSet(), ours.devicePropertiesSupported)
        assertEquals(other.captureFormats.map { it.toLong() }.toSet(), ours.captureFormats)
        assertEquals(other.imageFormats.map { it.toLong() }.toSet(), ours.imageFormats)
        assertEquals(other.manufacturer, ours.manufacturer)
        assertEquals(other.model, ours.model)
        assertEquals(other.deviceVersion, ours.deviceVersion)
        assertEquals(other.serialNumber, ours.serialNumber)
    }

    // ─────────────── ② 「u32 count」这个坑是真的 ───────────────

    @Test
    fun `反证 - 把数组长度读成 u16 必然错位（定稿 §1_5 的坑确实存在）`() {
        val wrong = IndependentDeviceInfoReader(raw)
        val diverged = try {
            wrong.parse(u32Counts = false)
            // 若没抛异常，则位置/字段必须已经跑偏
            wrong.position != raw.size || wrong.lastOperationsCount != 19
        } catch (expected: RuntimeException) {
            // 计数读错后要么越界崩，要么一路跑偏
            true
        }
        assertTrue("用 u16 计数解析真机 dump 竟然没跑偏，说明真机数据不足以证明该坑", diverged)
    }

    // ─────────────── ③ 自校验：尾巴多/少字节都必须报错 ───────────────

    @Test
    fun `自校验 - 尾部多 1 字节与少 1 字节都必须抛 PtpParseException`() {
        val cases = mapOf(
            "尾部多 1 字节" to (raw + byteArrayOf(0)),
            "截断 1 字节" to raw.copyOfRange(0, raw.size - 1),
            "截断到 12 字节" to raw.copyOfRange(0, 12),
            "空回包" to ByteArray(0),
        )
        for ((label, bytes) in cases) {
            try {
                DeviceInfoParser.parse(bytes)
                fail("$label 应当抛 PtpParseException")
            } catch (expected: cn.alittlecookie.lutista.tether.protocol.PtpParseException) {
                assertNotNull("$label 的异常信息不应为空", expected.message)
            }
        }
    }

    // ─────────────── ④ 定稿 §1.5 的字段值 ───────────────

    @Test
    fun `定稿 §1_5 的字段值与本项目常量一致`() {
        assertEquals(0x1CL, ours.vendorExtensionId)
        assertTrue("0x9402 不在声明表里（定稿 §1.2 的坑）", 0x9402L !in ours.operationsSupported)
        assertTrue("0xC108 必须在声明表里", 0xC108L in ours.eventsSupported)
        assertTrue("0x5001 是唯一的 DevicePropertiesSupported", ours.devicePropertiesSupported == setOf(0x5001L))
        assertTrue("captureFormats 必须为空", ours.captureFormats.isEmpty())
        assertTrue("0x3801 JPEG 在 imageFormats 里", 0x3801L in ours.imageFormats)
        assertTrue("0x3800 RAW 在 imageFormats 里", 0x3800L in ours.imageFormats)
    }

    // ────────────────────────────── 独立解析器 ──────────────────────────────

    /**
     * 从零写的 GetDeviceInfo 解析器（PIMA 15740 字段顺序）。
     *
     * `u32Counts = false` 时把五个数组的 count 当 u16 读，用来**反证**定稿 §1.5 的坑。
     */
    private class IndependentDeviceInfoReader(private val data: ByteArray) {

        var position: Int = 0
            private set

        var lastOperationsCount: Int = -1
            private set

        data class Snapshot(
            val standardVersion: Int,
            val vendorExtensionId: Long,
            val vendorExtensionVersion: Int,
            val vendorExtensionDesc: String,
            val functionalMode: Int,
            val operations: List<Int>,
            val events: List<Int>,
            val deviceProperties: List<Int>,
            val captureFormats: List<Int>,
            val imageFormats: List<Int>,
            val manufacturer: String,
            val model: String,
            val deviceVersion: String,
            val serialNumber: String,
        )

        fun parse(u32Counts: Boolean): Snapshot {
            position = 0
            val standardVersion = u16()
            val vendorExtensionId = u32()
            val vendorExtensionVersion = u16()
            val vendorExtensionDesc = ptpString()
            val functionalMode = u16()
            val operations = u16Array(u32Counts).also { lastOperationsCount = it.size }
            val events = u16Array(u32Counts)
            val deviceProperties = u16Array(u32Counts)
            val captureFormats = u16Array(u32Counts)
            val imageFormats = u16Array(u32Counts)
            val manufacturer = ptpString()
            val model = ptpString()
            val deviceVersion = ptpString()
            val serialNumber = ptpString()
            return Snapshot(
                standardVersion, vendorExtensionId, vendorExtensionVersion, vendorExtensionDesc,
                functionalMode, operations, events, deviceProperties, captureFormats, imageFormats,
                manufacturer, model, deviceVersion, serialNumber,
            )
        }

        private fun u8(): Int = data[position++].toInt() and 0xFF

        private fun u16(): Int {
            val value = (data[position].toInt() and 0xFF) or ((data[position + 1].toInt() and 0xFF) shl 8)
            position += 2
            return value
        }

        private fun u32(): Long {
            val low = u16().toLong()
            val high = u16().toLong()
            return low or (high shl 16)
        }

        /** count 含终止 0：字符数 = count - 1。 */
        private fun ptpString(): String {
            val count = u8()
            if (count == 0) return ""
            val text = String(data, position, (count - 1) * 2, Charsets.UTF_16LE)
            position += count * 2
            return text
        }

        private fun u16Array(u32Count: Boolean): List<Int> {
            val count = if (u32Count) u32().toInt() else u16()
            return List(count) { u16() }
        }
    }
}
