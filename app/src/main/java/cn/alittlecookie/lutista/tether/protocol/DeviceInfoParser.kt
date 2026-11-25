package cn.alittlecookie.lutista.tether.protocol

import cn.alittlecookie.lutista.tether.transport.PtpDeviceCaps

/**
 * `GetDeviceInfo(0x1001)` 全量回包的解析（纯函数）。
 *
 * 布局（PIMA 15740，S1 实测 223 字节全部对齐；顺序不可调）：
 * ```
 * u16 StandardVersion
 * u32 VendorExtensionID
 * u16 VendorExtensionVersion
 * STR VendorExtensionDesc
 * u16 FunctionalMode
 * u16[] OperationsSupported        (u32 count + u16 × n)
 * u16[] EventsSupported            (u32 count + u16 × n)
 * u16[] DevicePropertiesSupported  (u32 count + u16 × n)
 * u16[] CaptureFormats             (u32 count + u16 × n)
 * u16[] ImageFormats               (u32 count + u16 × n)
 * STR Manufacturer
 * STR Model
 * STR DeviceVersion
 * STR SerialNumber
 * ```
 *
 * **自校验是硬要求**：解析消耗的字节数必须等于回包长度，否则抛 [PtpParseException]。
 * 只要各段长度算错一处后面全是垃圾——这段数据是唯一可信的属性/事件清单来源。
 */
object DeviceInfoParser {

    /** S1 实测回包长度（仅用于测试/诊断断言，不参与解析逻辑）。 */
    const val S1_DEVICE_INFO_SIZE: Int = 223

    /**
     * 解析 GetDeviceInfo 回包 → [PtpDeviceCaps]。
     *
     * USB 侧字段（`bcdUsb` / `bulkMaxPacketSize` / `usbVendorId` / `usbProductId`）**不在**回包里，
     * 由 transport 层补齐；本方法填 0。
     *
     * @throws PtpParseException 长度越界或解析未对齐。
     */
    fun parse(payload: ByteArray): PtpDeviceCaps = parseDetailed(payload).toCaps()

    /**
     * 解析成全字段快照（诊断面板 / 单测用；比 [PtpDeviceCaps] 多出标准版本、功能模式等字段）。
     */
    fun parseDetailed(payload: ByteArray): ParsedDeviceInfo {
        if (payload.isEmpty()) throw PtpParseException("GetDeviceInfo 回包为空")

        val cursor = Cursor(payload)
        val standardVersion = cursor.u16()
        val vendorExtensionId = cursor.u32()
        val vendorExtensionVersion = cursor.u16()
        val vendorExtensionDesc = cursor.string()
        val functionalMode = cursor.u16()
        val operations = cursor.u16Array("OperationsSupported")
        val events = cursor.u16Array("EventsSupported")
        val deviceProperties = cursor.u16Array("DevicePropertiesSupported")
        val captureFormats = cursor.u16Array("CaptureFormats")
        val imageFormats = cursor.u16Array("ImageFormats")
        val manufacturer = cursor.string()
        val model = cursor.string()
        val deviceVersion = cursor.string()
        val serialNumber = cursor.string()

        if (cursor.remaining != 0) {
            throw PtpParseException(
                "GetDeviceInfo 解析未对齐：已消耗 ${cursor.position} 字节，" +
                    "回包 ${payload.size} 字节，剩余 ${cursor.remaining} 字节" +
                    "（各段长度算错或回包被截断，禁止静默丢弃）",
            )
        }

        return ParsedDeviceInfo(
            standardVersion = standardVersion,
            vendorExtensionId = vendorExtensionId,
            vendorExtensionVersion = vendorExtensionVersion,
            vendorExtensionDesc = vendorExtensionDesc,
            functionalMode = functionalMode,
            operationsSupported = operations,
            eventsSupported = events,
            devicePropertiesSupported = deviceProperties,
            captureFormats = captureFormats,
            imageFormats = imageFormats,
            manufacturer = manufacturer,
            model = model,
            deviceVersion = deviceVersion,
            serialNumber = serialNumber,
        )
    }

    /** GetDeviceInfo 的全字段快照（协议层原样，不做任何裁剪）。 */
    data class ParsedDeviceInfo(
        val standardVersion: Int,
        val vendorExtensionId: Long,
        val vendorExtensionVersion: Int,
        val vendorExtensionDesc: String,
        val functionalMode: Int,
        val operationsSupported: Set<Long>,
        val eventsSupported: Set<Long>,
        val devicePropertiesSupported: Set<Long>,
        val captureFormats: Set<Long>,
        val imageFormats: Set<Long>,
        val manufacturer: String,
        val model: String,
        val deviceVersion: String,
        val serialNumber: String,
    ) {
        /**
         * 转成 transport 层的 [PtpDeviceCaps]。
         * USB 描述符相关字段填 0，由 `PtpUsbTransport` 用真实值覆盖。
         */
        fun toCaps(): PtpDeviceCaps = PtpDeviceCaps(
            vendorExtensionId = vendorExtensionId,
            manufacturer = manufacturer,
            model = model,
            deviceVersion = deviceVersion,
            serialNumber = serialNumber,
            operationsSupported = operationsSupported,
            eventsSupported = eventsSupported,
            devicePropertiesSupported = devicePropertiesSupported,
            imageFormats = imageFormats,
            bcdUsb = 0,
            bulkMaxPacketSize = 0,
        )
    }

    /**
     * 极简游标。所有越界都抛 [PtpParseException]，绝不返回默认值。
     */
    private class Cursor(private val data: ByteArray) {
        var position: Int = 0
            private set

        val remaining: Int get() = data.size - position

        fun u8(): Int {
            require(1, "u8")
            return data[position++].toInt() and 0xFF
        }

        fun u16(): Int {
            require(2, "u16")
            val value = PtpContainerCodec.readU16(data, position)
            position += 2
            return value
        }

        fun u32(): Long {
            require(4, "u32")
            val value = PtpContainerCodec.readU32(data, position)
            position += 4
            return value
        }

        fun string(): String {
            val result = PtpStringReader.read(data, position, data.size)
            position += result.bytesConsumed
            return result.value
        }

        /** `u32 count` + `u16 × count`。 */
        fun u16Array(fieldName: String): Set<Long> {
            val count = u32()
            if (count > Int.MAX_VALUE.toLong()) {
                throw PtpParseException("$fieldName 计数非法：$count")
            }
            val n = count.toInt()
            if (n > remaining / 2) {
                throw PtpParseException(
                    "$fieldName 计数 $n 超出剩余 ${remaining} 字节（需要 ${n * 2} 字节）",
                )
            }
            if (n == 0) return emptySet()
            val out = LinkedHashSet<Long>(n * 2)
            for (i in 0 until n) out += u16().toLong()
            return out
        }

        private fun require(bytes: Int, what: String) {
            if (position + bytes > data.size) {
                throw PtpParseException(
                    "读取 $what 越界：position=$position 需要 $bytes 字节，回包仅 ${data.size} 字节",
                )
            }
        }
    }
}
