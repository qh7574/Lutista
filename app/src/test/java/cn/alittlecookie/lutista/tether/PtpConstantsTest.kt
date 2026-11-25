package cn.alittlecookie.lutista.tether

import cn.alittlecookie.lutista.tether.protocol.PtpConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 把「照抄文档」这件事钉死：本文件断言的都是真机实测/规范原文的**数值**，
 * 任何人改动常量都会在这里失败。
 *
 * 特别是响应码表——`0x2013` 曾被误命名为 `SpecificationByFormatUnsupported`（那是 `0x2014`），
 * `0x201D` 曾被误说成不是 `InvalidParameter`（它正是）。见 §`响应码名称陷阱` 的两条断言。
 */
class PtpConstantsTest {

    @Test
    fun `响应码 - 真机实际遇到的 6 个值`() {
        assertEquals(0x2001, PtpConstants.Rc.OK)
        assertEquals(0x2002, PtpConstants.Rc.GENERAL_ERROR)
        assertEquals(0x2006, PtpConstants.Rc.PARAMETER_NOT_SUPPORTED)
        assertEquals(0x2008, PtpConstants.Rc.INVALID_STORAGE_ID)
        assertEquals(0x2013, PtpConstants.Rc.STORE_NOT_AVAILABLE)
        assertEquals(0x201D, PtpConstants.Rc.INVALID_PARAMETER)
    }

    @Test
    fun `响应码名称陷阱 - 0x2013 不是 SpecificationByFormatUnsupported 也不是 SessionAlreadyOpened`() {
        // PIMA 15740 §11.3.19 StoreNotAvailable = 0x2013
        assertEquals(0x2013, PtpConstants.Rc.STORE_NOT_AVAILABLE)
        // §11.3.20 SpecificationByFormatUnsupported = 0x2014（差 1，极易写错）
        assertNotEquals(0x2014, PtpConstants.Rc.STORE_NOT_AVAILABLE)
        // §11.3.30 SessionAlreadyOpened = 0x201E
        assertNotEquals(0x201E, PtpConstants.Rc.STORE_NOT_AVAILABLE)
        // §11.3.29 InvalidParameter = 0x201D（0x201C 是 InvalidDevicePropValue）
        assertEquals(0x201D, PtpConstants.Rc.INVALID_PARAMETER)
        assertNotEquals(0x201C, PtpConstants.Rc.INVALID_PARAMETER)
    }

    @Test
    fun `事件码 - 只命名实际消费的 4 个`() {
        assertEquals(0x4006, PtpConstants.Event.DEVICE_PROP_CHANGED)
        assertEquals(0xC108, PtpConstants.Event.OBJECT_ADDED)
        assertEquals(0xC101, PtpConstants.Event.NOISE_STATE_CHANGED)
        assertEquals(0xC107, PtpConstants.Event.NOISE_CAPACITY_CHANGED)
    }

    @Test
    fun `对象格式 - RW2 与 JPG 与 MOV 与 DPOF`() {
        assertEquals(0x3001, PtpConstants.Format.ASSOCIATION)
        assertEquals(0x3006, PtpConstants.Format.DPOF)
        assertEquals(0x3801, PtpConstants.Format.JPEG)
        assertEquals(0x3800, PtpConstants.Format.RAW_RW2)
        assertEquals(0x300D, PtpConstants.Format.MOV)
        // DPOF / Association 都不是照片格式：谁若把它们当"可预览"就会在相机里有打印订单时多出条目
        assertNotEquals(PtpConstants.Format.DPOF, PtpConstants.Format.JPEG)
        assertNotEquals(PtpConstants.Format.ASSOCIATION, PtpConstants.Format.JPEG)
    }

    @Test
    fun `私有 opcode - 实测可用的 9 条`() {
        assertEquals(0x9402, PtpConstants.PanasonicOp.GET_PROP_VALUE)
        assertEquals(0x9403, PtpConstants.PanasonicOp.SET_PROP_VALUE)
        assertEquals(0x9108, PtpConstants.PanasonicOp.GET_PROP_DESC)
        assertEquals(0x9404, PtpConstants.PanasonicOp.CAPTURE)
        assertEquals(0x9405, PtpConstants.PanasonicOp.AF_AE_CONTROL)
        assertEquals(0x9412, PtpConstants.PanasonicOp.LIVEVIEW_CONTROL)
        assertEquals(0x9414, PtpConstants.PanasonicOp.GET_LIVEVIEW_FORMATS)
        assertEquals(0x9416, PtpConstants.PanasonicOp.MANUAL_FOCUS_DRIVE)
        assertEquals(0x9706, PtpConstants.PanasonicOp.GET_LIVEVIEW_FRAME)
    }

    @Test
    fun `属性 ID - 容器属性 0xX0 与值属性 0xX1 的配对关系`() {
        assertEquals(0x02000020L, PtpConstants.Prop.ISO_CONTAINER)
        assertEquals(0x02000021L, PtpConstants.Prop.ISO_VALUE)
        assertEquals(0x02000030L, PtpConstants.Prop.SHUTTER_CONTAINER)
        assertEquals(0x02000031L, PtpConstants.Prop.SHUTTER_VALUE)
        assertEquals(0x02000040L, PtpConstants.Prop.APERTURE_CONTAINER)
        assertEquals(0x02000041L, PtpConstants.Prop.APERTURE_VALUE)
        assertEquals(0x02000050L, PtpConstants.Prop.WHITE_BALANCE_CONTAINER)
        assertEquals(0x02000051L, PtpConstants.Prop.WHITE_BALANCE_VALUE)
        assertEquals(0x02000060L, PtpConstants.Prop.EXPOSURE_COMPENSATION_CONTAINER)
        assertEquals(0x02000061L, PtpConstants.Prop.EXPOSURE_COMPENSATION_VALUE)
        assertEquals(0x02000071L, PtpConstants.Prop.AF_MODE)
        assertEquals(0x02000082L, PtpConstants.Prop.MODE_POS)
        assertEquals(0x02000083L, PtpConstants.Prop.PHOTO_STYLE)
        assertEquals(0x06000010L, PtpConstants.Prop.VIDEO_RESOLUTION)
        assertEquals(0x06000011L, PtpConstants.Prop.VIDEO_MOVIE_MODE)
        assertEquals(0x06000014L, PtpConstants.Prop.VIDEO_RECORD_MODE)
        assertEquals(0x00005001L, PtpConstants.Prop.BATTERY_LEVEL)
    }

    @Test
    fun `opcode 参数 - 快门与 AF 与取景`() {
        // 0x03000011 是 0x9404 的快门参数，错用到 0x9405 会返回 0x2002
        assertEquals(0x03000011L, PtpConstants.Param.CAPTURE_SHUTTER)
        assertEquals(0x03000021L, PtpConstants.Param.AF_LOCK_AE)
        assertEquals(0x03000022L, PtpConstants.Param.AF_LOCK_AF)
        assertEquals(0x03000023L, PtpConstants.Param.AF_LOCK_AE_AF)
        assertEquals(0x03000024L, PtpConstants.Param.AF_ONESHOT)
        assertEquals(0x03000025L, PtpConstants.Param.AF_LOCK_CLEAR)
        assertEquals(0x0D000010L, PtpConstants.Param.LIVEVIEW_ON)
        assertEquals(0x0D000011L, PtpConstants.Param.LIVEVIEW_OFF)
        assertEquals(0x0D800011L, PtpConstants.Param.LIVEVIEW_FORMAT_SET)
        assertEquals(0x0D800012L, PtpConstants.Param.LIVEVIEW_FORMAT_TABLE)
    }

    @Test
    fun `标准 opcode - 枚举路径依赖的几条`() {
        assertEquals(0x1001, PtpConstants.Op.GET_DEVICE_INFO)
        assertEquals(0x1002, PtpConstants.Op.OPEN_SESSION)
        assertEquals(0x1003, PtpConstants.Op.CLOSE_SESSION)
        assertEquals(0x1004, PtpConstants.Op.GET_STORAGE_IDS)
        assertEquals(0x1007, PtpConstants.Op.GET_OBJECT_HANDLES)
        assertEquals(0x1009, PtpConstants.Op.GET_OBJECT)
        assertEquals(0x100A, PtpConstants.Op.GET_THUMB)
        assertEquals(0x101B, PtpConstants.Op.GET_PARTIAL_OBJECT)
    }

    @Test
    fun `哨兵值 - 会话 ID 与通配符`() {
        assertEquals(1L, PtpConstants.DEFAULT_SESSION_ID)
        assertEquals(0xFFFF_FFFFL, PtpConstants.EVENT_TRANSACTION_ID)
        assertEquals(0xFFFF_FFFFL, PtpConstants.ALL_HANDLES)
        assertEquals(0xFFFF_FFFFL, PtpConstants.ROOT_PARENT_HANDLE)
        assertEquals(0L, PtpConstants.ANY_OBJECT_FORMAT)
        assertEquals(0xFFFEL, PtpConstants.VALUE_NO_LENS_DATA)
    }
}
