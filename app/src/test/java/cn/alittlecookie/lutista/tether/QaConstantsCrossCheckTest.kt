package cn.alittlecookie.lutista.tether

import cn.alittlecookie.lutista.tether.protocol.DeviceInfoParser
import cn.alittlecookie.lutista.tether.protocol.PtpConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QA 独立复核（T01）：把 [PtpConstants] 的数值与**可查证来源**逐条对齐。
 *
 * 判据有两个，都不依赖工程师的注释：
 * 1. 响应码 / opcode 名称：libgphoto2 `ptp.h` 原文（本机 `/tmp/lgp/ptp.h`，Response Codes 段）；
 * 2. 项目内取值：`.docs/联机模式-PTP结论-定稿-2026-11-25.md` §1.2 / §1.3 / §3 / §5 / §6。
 *
 * 这里用反射枚举 `const val`，因此**新增常量也会被检查到**（姓名写错 / 数值抄错会直接红）。
 */
class QaConstantsCrossCheckTest {

    /**
     * 取某个 `object` 里所有 `const val Int`（编译成 static int 字段）。
     *
     * 必须跳过 Compose 编译器注入的 `$stable` 等合成字段，否则会多算一个常量。
     */
    private fun intConstants(cls: Class<*>): Map<String, Int> = cls.declaredFields
        .filter { it.type == Int::class.javaPrimitiveType && !it.name.startsWith("\$") }
        .associate { field ->
            field.isAccessible = true
            field.name to field.getInt(null)
        }

    private fun longConstants(cls: Class<*>): Map<String, Long> = cls.declaredFields
        .filter { it.type == Long::class.javaPrimitiveType && !it.name.startsWith("\$") }
        .associate { field ->
            field.isAccessible = true
            field.name to field.getLong(null)
        }

    // ────────────────────── 响应码：ptp.h 原文逐条 ──────────────────────

    @Test
    fun `响应码与 ptp_h 原文一致`() {
        val rc = intConstants(PtpConstants.Rc::class.java)
        // ptp.h: #define PTP_RC_OK 0x2001
        assertEquals(0x2001, rc["OK"])
        // ptp.h: #define PTP_RC_GeneralError 0x2002
        assertEquals(0x2002, rc["GENERAL_ERROR"])
        // ptp.h: #define PTP_RC_ParameterNotSupported 0x2006
        assertEquals(0x2006, rc["PARAMETER_NOT_SUPPORTED"])
        // ptp.h: #define PTP_RC_InvalidStorageId 0x2008
        assertEquals(0x2008, rc["INVALID_STORAGE_ID"])
        // ptp.h: #define PTP_RC_StoreNotAvailable 0x2013
        assertEquals(0x2013, rc["STORE_NOT_AVAILABLE"])
        // ptp.h: #define PTP_RC_InvalidParameter 0x201D
        assertEquals(0x201D, rc["INVALID_PARAMETER"])
    }

    @Test
    fun `响应码邻居关系 - 0x2013 与 0x2014 与 0x201C 与 0x201D 与 0x201E 不许张冠李戴`() {
        val rc = intConstants(PtpConstants.Rc::class.java)
        // ptp.h: 0x2014 = SpecificationByFormatUnsupported，0x201E = SessionAlreadyOpened
        assertNotEquals(0x2014, rc["STORE_NOT_AVAILABLE"])
        assertNotEquals(0x201E, rc["STORE_NOT_AVAILABLE"])
        // ptp.h: 0x201C = InvalidDevicePropValue，0x201D = InvalidParameter
        assertNotEquals(0x201C, rc["INVALID_PARAMETER"])
        assertNotEquals(0x201E, rc["INVALID_PARAMETER"])
        // 本项目未定义 0x2014 / 0x201E 常量（用不到），但绝不能把它们的数值挪给别人
        assertTrue(
            "0x2014/0x201E 若被误用为其它语义，会重演定稿 §6 警告过的误判",
            rc.values.none { it == 0x2014 || it == 0x201E },
        )
    }

    @Test
    fun `响应码常量之间没有重复值`() {
        val rc = intConstants(PtpConstants.Rc::class.java)
        assertTrue("Rc 常量数量异常：$rc", rc.size >= 6)
        assertEquals("存在数值重复的常量：$rc", rc.size, rc.values.toSet().size)
    }

    // ────────────────────── opcode：定稿 §1.2 / §1.3 ──────────────────────

    @Test
    fun `私有 opcode 9 条与定稿 §1_3 完全一致`() {
        val op = intConstants(PtpConstants.PanasonicOp::class.java)
        assertEquals(
            mapOf(
                "GET_PROP_VALUE" to 0x9402,
                "SET_PROP_VALUE" to 0x9403,
                "GET_PROP_DESC" to 0x9108,
                "CAPTURE" to 0x9404,
                "AF_AE_CONTROL" to 0x9405,
                "LIVEVIEW_CONTROL" to 0x9412,
                "GET_LIVEVIEW_FORMATS" to 0x9414,
                "MANUAL_FOCUS_DRIVE" to 0x9416,
                "GET_LIVEVIEW_FRAME" to 0x9706,
            ),
            op,
        )
    }

    @Test
    fun `标准 opcode 落在 0x1001 到 0x101B 且与定稿一致`() {
        val op = intConstants(PtpConstants.Op::class.java)
        assertEquals(0x1001, op["GET_DEVICE_INFO"])
        assertEquals(0x1002, op["OPEN_SESSION"])
        assertEquals(0x1003, op["CLOSE_SESSION"])
        assertEquals(0x1004, op["GET_STORAGE_IDS"])
        assertEquals(0x1005, op["GET_STORAGE_INFO"])
        assertEquals(0x1006, op["GET_NUM_OBJECTS"])
        assertEquals(0x1007, op["GET_OBJECT_HANDLES"])
        assertEquals(0x1008, op["GET_OBJECT_INFO"])
        assertEquals(0x1009, op["GET_OBJECT"])
        assertEquals(0x100A, op["GET_THUMB"])
        assertEquals(0x100B, op["DELETE_OBJECT"])
        assertEquals(0x100F, op["FORMAT_STORE"])
        assertEquals(0x1014, op["GET_DEVICE_PROP_DESC"])
        assertEquals(0x1015, op["GET_DEVICE_PROP_VALUE"])
        assertEquals(0x1016, op["SET_DEVICE_PROP_VALUE"])
        assertEquals(0x101B, op["GET_PARTIAL_OBJECT"])
        // 16 条标准码必须彼此不重复
        assertEquals(op.toString(), op.size, op.values.toSet().size)
    }

    @Test
    fun `opcode 参数常量与定稿 §5 一致`() {
        val param = longConstants(PtpConstants.Param::class.java)
        // §1.3 / §5.1：0x9404 的快门参数
        assertEquals(0x03000011L, param["CAPTURE_SHUTTER"])
        // §5.2：0x9405 的 5 个参数
        assertEquals(0x03000021L, param["AF_LOCK_AE"])
        assertEquals(0x03000022L, param["AF_LOCK_AF"])
        assertEquals(0x03000023L, param["AF_LOCK_AE_AF"])
        assertEquals(0x03000024L, param["AF_ONESHOT"])
        assertEquals(0x03000025L, param["AF_LOCK_CLEAR"])
        // §5.3：取景开关 / 格式表
        assertEquals(0x0D000010L, param["LIVEVIEW_ON"])
        assertEquals(0x0D000011L, param["LIVEVIEW_OFF"])
        assertEquals(0x0D800012L, param["LIVEVIEW_FORMAT_TABLE"])
        // 0x9415 的写格式参数与 0x9414 的读参数只差最后一位，极易写反
        assertEquals(0x0D800011L, param["LIVEVIEW_FORMAT_SET"])
        assertNotEquals(param["LIVEVIEW_FORMAT_TABLE"], param["LIVEVIEW_FORMAT_SET"])
    }

    @Test
    fun `属性 ID 与定稿 §3 的值属性配对关系一致`() {
        val prop = longConstants(PtpConstants.Prop::class.java)
        assertEquals(0x02000020L, prop["ISO_CONTAINER"])
        assertEquals(0x02000021L, prop["ISO_VALUE"])
        assertEquals(0x02000030L, prop["SHUTTER_CONTAINER"])
        assertEquals(0x02000031L, prop["SHUTTER_VALUE"])
        assertEquals(0x02000040L, prop["APERTURE_CONTAINER"])
        assertEquals(0x02000041L, prop["APERTURE_VALUE"])
        assertEquals(0x02000042L, prop["APERTURE_MAX"])
        assertEquals(0x02000050L, prop["WHITE_BALANCE_CONTAINER"])
        assertEquals(0x02000051L, prop["WHITE_BALANCE_VALUE"])
        assertEquals(0x02000060L, prop["EXPOSURE_COMPENSATION_CONTAINER"])
        assertEquals(0x02000061L, prop["EXPOSURE_COMPENSATION_VALUE"])
        assertEquals(0x02000071L, prop["AF_MODE"])
        assertEquals(0x02000082L, prop["MODE_POS"])
        assertEquals(0x02000083L, prop["PHOTO_STYLE"])
        assertEquals(0x06000010L, prop["VIDEO_RESOLUTION"])
        assertEquals(0x06000011L, prop["VIDEO_MOVIE_MODE"])
        assertEquals(0x06000014L, prop["VIDEO_RECORD_MODE"])
        assertEquals(0x00005001L, prop["BATTERY_LEVEL"])

        // 每个容器属性（0x..X0）都必须有对应的值属性（0x..X1），否则写属性无处落
        listOf("ISO", "SHUTTER", "APERTURE", "WHITE_BALANCE", "EXPOSURE_COMPENSATION").forEach { name ->
            val container = prop["${name}_CONTAINER"]!!
            val value = prop["${name}_VALUE"]!!
            assertEquals("$name 值属性必须是容器属性 + 1", container + 1, value)
            assertEquals("$name 值属性必须是 0x..X1", 1L, value and 0x0F)
        }
    }

    // ────────────────────── 事件码：只命名 4 个 ──────────────────────

    @Test
    fun `事件码只命名 4 个且都在相机声明的 16 个之内`() {
        val event = intConstants(PtpConstants.Event::class.java)
        assertEquals("定稿 §1.4：只命名实际消费的 4 个事件，其余按码丢弃", 4, event.size)
        assertEquals(0x4006, event["DEVICE_PROP_CHANGED"])
        assertEquals(0xC108, event["OBJECT_ADDED"])
        assertEquals(0xC101, event["NOISE_STATE_CHANGED"])
        assertEquals(0xC107, event["NOISE_CAPACITY_CHANGED"])

        // 4 个被命名的码必须都能在真机 GetDeviceInfo 的 EventsSupported 里找到，
        // 否则说明有人凭记忆写了一个总线上不存在的码。
        val eventsSupported = DeviceInfoParser
            .parseDetailed(Fixtures.hex("device_info"))
            .eventsSupported
        assertEquals(16, eventsSupported.size)
        event.forEach { (name, code) ->
            assertTrue(
                "$name(0x${code.toString(16)}) 不在相机声明的 EventsSupported 里",
                code.toLong() in eventsSupported,
            )
        }
    }

    @Test
    fun `模式位取值与定稿 §3_6 一致`() {
        val mode = longConstants(PtpConstants.ModePos::class.java)
        assertEquals(0L, mode["P"])
        assertEquals(1L, mode["A"])
        assertEquals(2L, mode["S"])
        assertEquals(3L, mode["M"])
        assertEquals(5L, mode["VIDEO"])
        assertEquals(7L, mode["INTELLIGENT_AUTO"])
    }

    @Test
    fun `哨兵值与容器尺寸 - 定稿 §1_1 与 §2_3`() {
        assertEquals(12, PtpConstants.CONTAINER_HEADER_SIZE)
        assertEquals(1L, PtpConstants.DEFAULT_SESSION_ID)
        assertEquals(0xFFFF_FFFFL, PtpConstants.ALL_HANDLES)
        assertEquals(0xFFFF_FFFFL, PtpConstants.ALL_STORAGE_IDS)
        assertEquals(0L, PtpConstants.ANY_OBJECT_FORMAT)
        assertEquals(0xFFFF_FFFFL, PtpConstants.ROOT_PARENT_HANDLE)
        assertEquals(0xFFFF_FFFFL, PtpConstants.EVENT_TRANSACTION_ID)
        assertEquals(0xFFFF_FFFFL, PtpConstants.VALUE_UNDEFINED)
        assertEquals(0xFFFEL, PtpConstants.VALUE_NO_LENS_DATA)
        // 0xFFFE 与 0xFFFFFFFF 语义不同：前者是无镜头数据，后者是 AUTO/BULB
        assertNotEquals(PtpConstants.VALUE_NO_LENS_DATA, PtpConstants.VALUE_UNDEFINED)
    }
}
