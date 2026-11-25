package cn.alittlecookie.lutista.tether

import cn.alittlecookie.lutista.tether.protocol.DeviceInfoParser
import cn.alittlecookie.lutista.tether.protocol.PtpConstants
import cn.alittlecookie.lutista.tether.protocol.PtpContainerCodec
import cn.alittlecookie.lutista.tether.protocol.PtpParseException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [DeviceInfoParser]：真机 dump 优先，代码构造 fixture 作对照。
 *
 * - **真机 dump**：`app/src/test/resources/fixtures/device_info.hex`（223 B，meta 里 `synthetic: false`，
 *   Panasonic DC-S1 固件 2.4）。这是唯一权威输入，所有正向断言都跑它。
 * - 代码构造的对照 fixture 用来证明「解析器不依赖具体字节来源」，并与真机 dump 逐字节比对一致。
 */
class DeviceInfoParserTest {

    /** 真机 dump（权威）。 */
    private val realDeviceInfo: ByteArray = Fixtures.hex("device_info")

    // 代码构造的对照 fixture（不是"待替换的合成数据"）：已与上面的真机 dump 逐字节比对一致
    // （见 `代码构造的 fixture 与真机 dump 字节一致`）。保留它只为「即使 dump 文件丢了也能继续跑单测」，
    // 以及证明解析器不依赖具体字节来源。
    private val syntheticDeviceInfo: ByteArray by lazy { buildDeviceInfo() }

    private val s1Operations = listOf(
        0x1001, 0x1002, 0x1003, 0x1004, 0x1005, 0x1006, 0x1007, 0x1008, 0x1009, 0x100A,
        0x100B, 0x100F, 0x1014, 0x1015, 0x1016, 0x101B, 0x9102, 0x9103, 0x9104,
    )

    private val s1Events = listOf(
        0x4004, 0x4005, 0x4006, 0x4008, 0x400C, 0xC108, 0xC109, 0xC101,
        0xC102, 0xC103, 0xC104, 0xC106, 0xC107, 0xC201, 0xC202, 0xC212,
    )

    private val s1ImageFormats = listOf(0x3001, 0x3006, 0x300D, 0x3800, 0x3801, 0xB003)

    /**
     * 序列号**已脱敏**：真机 dump 里本来是本机真实序列号，出于公开仓库安全考虑，
     * `device_info.hex` 中该字段的 13 个字符已被替换为 `'0'`（总长 31 字符不变，
     * `device_info.meta.json` 的 `sanitized` 字段记录了这件事）。
     *
     * 除序列号外，其余字段与真机 dump **逐字节一致**——别把这里的 31 个 `0` 当成 dump 出错。
     */
    private val s1Serial = "0".repeat(31)

    // ────────────────────────── 长度 / 布局 ──────────────────────────

    @Test
    fun `真机 dump 长度 223 B 且各数组个数与定稿一致`() {
        assertEquals(DeviceInfoParser.S1_DEVICE_INFO_SIZE, realDeviceInfo.size)
        assertEquals(223, realDeviceInfo.size)
        assertEquals(19, s1Operations.size)
        assertEquals(16, s1Events.size)
        assertEquals(6, s1ImageFormats.size)
    }

    @Test
    fun `代码构造的 fixture 与真机 dump 字节一致`() {
        assertArrayEquals(realDeviceInfo, syntheticDeviceInfo)
        assertEquals(223, syntheticDeviceInfo.size)
    }

    // ────────────────────────── 字段断言（真机 dump） ──────────────────────────

    @Test
    fun `解析 - vendorExtensionId 与版本字段`() {
        val parsed = DeviceInfoParser.parseDetailed(realDeviceInfo)
        assertEquals(0x1CL, parsed.vendorExtensionId)
        assertEquals(100, parsed.vendorExtensionVersion)
        assertEquals(100, parsed.standardVersion)
        assertEquals(0, parsed.functionalMode)
        assertEquals("", parsed.vendorExtensionDesc)
    }

    @Test
    fun `解析 - 尾部的 4 个字符串`() {
        val parsed = DeviceInfoParser.parseDetailed(realDeviceInfo)
        assertEquals("Panasonic", parsed.manufacturer)
        assertEquals("DC-S1", parsed.model)
        assertEquals("2.4", parsed.deviceVersion)
        assertEquals(s1Serial, parsed.serialNumber)
        // 序列号字段是 31 个 UTF-16 单元（脱敏后全是 '0'），PTP string 长度字节因此是 32（含终止 0）
        assertEquals(31, parsed.serialNumber.length)
    }

    @Test
    fun `解析 - operations 19 条且不含任何私有码`() {
        val parsed = DeviceInfoParser.parseDetailed(realDeviceInfo)
        assertEquals(19, parsed.operationsSupported.size)
        assertEquals(s1Operations.map { it.toLong() }.toSet(), parsed.operationsSupported)
        // 声明里确实有 0x9102/0x9103/0x9104（私有会话码，用不上）
        assertTrue(parsed.operationsSupported.contains(0x9102L))
        // 修正 ① 的守卫：声明表里没有私有码，但它们实测可用 → 不能据此判 Unsupported
        assertFalse(parsed.operationsSupported.contains(0x9402L))
        assertFalse(parsed.operationsSupported.contains(0x9404L))
        assertFalse(parsed.operationsSupported.contains(0x9412L))
        assertFalse(parsed.operationsSupported.contains(0x9706L))
        assertTrue(parsed.operationsSupported.contains(0x1007L))
        assertTrue(parsed.operationsSupported.contains(0x101BL))
    }

    @Test
    fun `解析 - events 16 条且含 0xC108`() {
        val parsed = DeviceInfoParser.parseDetailed(realDeviceInfo)
        assertEquals(16, parsed.eventsSupported.size)
        assertEquals(s1Events.map { it.toLong() }.toSet(), parsed.eventsSupported)
        assertTrue(parsed.eventsSupported.contains(PtpConstants.Event.OBJECT_ADDED.toLong()))
        assertTrue(parsed.eventsSupported.contains(PtpConstants.Event.DEVICE_PROP_CHANGED.toLong()))
    }

    @Test
    fun `解析 - deviceProperties 仅 0x5001`() {
        val parsed = DeviceInfoParser.parseDetailed(realDeviceInfo)
        assertEquals(setOf(0x5001L), parsed.devicePropertiesSupported)
    }

    @Test
    fun `解析 - captureFormats 为空 imageFormats 6 条`() {
        val parsed = DeviceInfoParser.parseDetailed(realDeviceInfo)
        assertTrue(parsed.captureFormats.isEmpty())
        assertEquals(s1ImageFormats.map { it.toLong() }.toSet(), parsed.imageFormats)
    }

    @Test
    fun `解析 - toCaps 不填 USB 侧字段（由 transport 补齐）`() {
        val caps = DeviceInfoParser.parse(realDeviceInfo)
        assertEquals(0x1CL, caps.vendorExtensionId)
        assertEquals("DC-S1", caps.model)
        assertEquals(0, caps.bcdUsb)
        assertEquals(0, caps.bulkMaxPacketSize)
        assertEquals(0, caps.usbVendorId)
        assertEquals(0, caps.usbProductId)
    }

    // ────────────────────────── 自校验 ──────────────────────────

    @Test
    fun `自校验 - 尾部多 1 字节必须报错而不是静默截断`() {
        val corrupt = syntheticDeviceInfo + byteArrayOf(0x00)
        try {
            DeviceInfoParser.parse(corrupt)
            fail("应当抛 PtpParseException")
        } catch (expected: PtpParseException) {
            assertTrue(expected.message!!.contains("未对齐"))
        }
    }

    @Test
    fun `自校验 - 截断 1 字节必须报错`() {
        val corrupt = syntheticDeviceInfo.copyOfRange(0, syntheticDeviceInfo.size - 1)
        try {
            DeviceInfoParser.parse(corrupt)
            fail("应当抛 PtpParseException")
        } catch (expected: PtpParseException) {
            assertTrue(expected.message!!.contains("越界") || expected.message!!.contains("未对齐"))
        }
    }

    @Test
    fun `自校验 - 截断到 220 字节必须报错`() {
        val corrupt = realDeviceInfo.copyOfRange(0, 220)
        try {
            DeviceInfoParser.parse(corrupt)
            fail("应当抛 PtpParseException")
        } catch (expected: PtpParseException) {
            assertTrue(expected.message!!.contains("越界") || expected.message!!.contains("未对齐"))
        }
    }

    @Test
    fun `自校验 - 数组 count 撒谎写成 200 必须抛异常而不是越界崩溃`() {
        // OperationsSupported 的 count 位于偏移 11（2 + 4 + 2 + 1 + 2）
        val corrupt = realDeviceInfo.copyOf()
        PtpContainerCodec.writeU32(corrupt, 11, 200L)
        try {
            DeviceInfoParser.parse(corrupt)
            fail("应当抛 PtpParseException")
        } catch (expected: PtpParseException) {
            assertTrue(expected.message!!.contains("OperationsSupported"))
            assertTrue(expected.message!!.contains("200"))
        }
    }

    @Test
    fun `自校验 - 空回包报错`() {
        try {
            DeviceInfoParser.parse(ByteArray(0))
            fail("应当抛 PtpParseException")
        } catch (expected: PtpParseException) {
            assertTrue(expected.message!!.contains("为空"))
        }
    }

    @Test
    fun `自校验 - 字符串声称的长度越界必须报错`() {
        // 偏移 8 是 VendorExtensionDesc 的 count 字节（真机为 0 = 空串），改成 0xFF 制造越界
        val corrupt = realDeviceInfo.copyOf()
        corrupt[8] = 0xFF.toByte()
        try {
            DeviceInfoParser.parse(corrupt)
            fail("应当抛 PtpParseException")
        } catch (expected: PtpParseException) {
            assertTrue(expected.message!!.contains("越界"))
        }
    }

    @Test
    fun `自校验 - 数组计数声称的长度超出回包必须报错`() {
        // OperationsSupported 的 count 位于偏移 11（2+4+2+1+2），改成 0xFFFF
        val corrupt = realDeviceInfo.copyOf()
        PtpContainerCodec.writeU32(corrupt, 11, 0xFFFFL)
        try {
            DeviceInfoParser.parse(corrupt)
            fail("应当抛 PtpParseException")
        } catch (expected: PtpParseException) {
            assertTrue(expected.message!!.contains("OperationsSupported"))
        }
    }

    // ────────────────────────── 代码构造的对照 fixture ──────────────────────────

    private fun buildDeviceInfo(): ByteArray {
        val out = java.io.ByteArrayOutputStream(256)
        out.writeU16(100) // StandardVersion
        out.writeU32(0x1CL) // VendorExtensionID = Panasonic
        out.writeU16(100) // VendorExtensionVersion
        out.write(PtpString("")) // VendorExtensionDesc（真机为空）
        out.writeU16(0) // FunctionalMode
        out.writeU16Array(s1Operations) // OperationsSupported
        out.writeU16Array(s1Events) // EventsSupported
        out.writeU16Array(listOf(0x5001)) // DevicePropertiesSupported
        out.writeU16Array(emptyList()) // CaptureFormats
        out.writeU16Array(s1ImageFormats) // ImageFormats
        out.write(PtpString("Panasonic"))
        out.write(PtpString("DC-S1"))
        out.write(PtpString("2.4"))
        out.write(PtpString(s1Serial))
        return out.toByteArray()
    }

    private fun PtpString(value: String): ByteArray {
        if (value.isEmpty()) return byteArrayOf(0x00)
        val body = (value + "\u0000").toByteArray(Charsets.UTF_16LE)
        return byteArrayOf((body.size / 2).toByte()) + body
    }

    private fun java.io.ByteArrayOutputStream.writeU16(value: Int) {
        write(byteArrayOf((value and 0xFF).toByte(), ((value ushr 8) and 0xFF).toByte()))
    }

    private fun java.io.ByteArrayOutputStream.writeU32(value: Long) {
        val bytes = ByteArray(4)
        PtpContainerCodec.writeU32(bytes, 0, value and 0xFFFF_FFFFL)
        write(bytes)
    }

    private fun java.io.ByteArrayOutputStream.writeU16Array(values: List<Int>) {
        writeU32(values.size.toLong())
        values.forEach { writeU16(it) }
    }
}
