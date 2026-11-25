package cn.alittlecookie.lutista.tether

import cn.alittlecookie.lutista.tether.protocol.PtpConstants
import cn.alittlecookie.lutista.tether.protocol.PtpParseException
import cn.alittlecookie.lutista.tether.transport.PreflightFailure
import cn.alittlecookie.lutista.tether.transport.PreflightOutcomes
import cn.alittlecookie.lutista.tether.transport.PtpException
import cn.alittlecookie.lutista.tether.transport.PtpProtocolException
import cn.alittlecookie.lutista.tether.transport.PtpTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [PreflightOutcomes]：`open()` 内 `GetDeviceInfo` 预检的结果分类。
 *
 * 这条策略决定「预检失败是否把整条连接判死」，所以必须被钉死：预检失败**只产出诊断**，
 * 绝不置 fatal（fatal 只属于会话内事务）。
 */
class PreflightOutcomesTest {

    @Test
    fun `预检成功 - 返回 null`() {
        assertNull(PreflightOutcomes.resolve(PtpConstants.Rc.OK, null))
    }

    @Test
    fun `预检被拒 - 非 0x2001 归为 REJECTED`() {
        assertEquals(
            PreflightFailure.REJECTED,
            PreflightOutcomes.resolve(PtpConstants.Rc.GENERAL_ERROR, null),
        )
        assertEquals(
            PreflightFailure.REJECTED,
            PreflightOutcomes.resolve(PtpConstants.Rc.PARAMETER_NOT_SUPPORTED, null),
        )
        assertEquals(
            PreflightFailure.REJECTED,
            PreflightOutcomes.resolve(PtpConstants.Rc.INVALID_PARAMETER, null),
        )
    }

    @Test
    fun `预检超时 - bulk -1 归为 TIMEOUT（不置 fatal）`() {
        assertEquals(
            PreflightFailure.TIMEOUT,
            PreflightOutcomes.resolve(null, PtpTimeoutException("bulk IN timeout")),
        )
    }

    @Test
    fun `预检解析失败 - 归为 PARSE_ERROR`() {
        assertEquals(
            PreflightFailure.PARSE_ERROR,
            PreflightOutcomes.resolve(null, PtpParseException("GetDeviceInfo 解析未对齐")),
        )
        // 通道错位等结构性问题同样归到解析侧：对调用方的处置动作一致
        assertEquals(
            PreflightFailure.PARSE_ERROR,
            PreflightOutcomes.resolve(null, PtpProtocolException("容器长度非法")),
        )
        assertEquals(
            PreflightFailure.PARSE_ERROR,
            PreflightOutcomes.resolve(null, PtpException("其它传输层异常")),
        )
        assertEquals(
            PreflightFailure.PARSE_ERROR,
            PreflightOutcomes.resolve(null, IllegalStateException("claim 失败")),
        )
    }

    @Test
    fun `异常优先于响应码 - 两者都有时按异常分类`() {
        assertEquals(
            PreflightFailure.TIMEOUT,
            PreflightOutcomes.resolve(PtpConstants.Rc.OK, PtpTimeoutException("超时")),
        )
        assertEquals(
            PreflightFailure.PARSE_ERROR,
            PreflightOutcomes.resolve(PtpConstants.Rc.GENERAL_ERROR, PtpParseException("bad")),
        )
    }

    @Test
    fun `啥都没有 - 返回 null（不臆造失败）`() {
        assertNull(PreflightOutcomes.resolve(null, null))
    }
}
