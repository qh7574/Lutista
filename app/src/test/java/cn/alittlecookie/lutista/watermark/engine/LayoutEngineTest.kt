package cn.alittlecookie.lutista.watermark.engine

import android.util.Log
import cn.alittlecookie.lutista.watermark.model.BaseImageElement
import cn.alittlecookie.lutista.watermark.model.BorderWidth
import cn.alittlecookie.lutista.watermark.model.Scene
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LayoutEngineTest {

    @Before
    fun stubAndroidLog() {
        // LayoutEngine 直接调用 android.util.Log.d，纯 JVM 单测下它只有空壳实现（抛 Stub!）
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
    }

    @After
    fun restoreAndroidLog() {
        unmockkStatic(Log::class)
    }

    @Test
    fun `layout with empty scene returns canvas size`() {
        val converter = UnitConverter(4000, 3000)
        val engine = LayoutEngine(converter)
        val scene = Scene(
            baseImage = BaseImageElement(imageUri = "/test.jpg")
        )
        val result = engine.layout(scene)

        assertEquals(4000, result.canvasWidth)
        assertEquals(3000, result.canvasHeight)
        assertTrue(result.elementBounds.isEmpty())
    }

    @Test
    fun `layout with border increases canvas size`() {
        val converter = UnitConverter(4000, 3000)
        val engine = LayoutEngine(converter)
        val border = BorderWidth(10f, 10f, 10f, 10f)
        val scene = Scene(
            baseImage = BaseImageElement(
                imageUri = "/test.jpg",
                borderWidth = border
            )
        )
        val result = engine.layout(scene)

        val borderPx = converter.gmToPx(10f)
        val expectedW = (4000 + borderPx * 2).toInt()
        val expectedH = (3000 + borderPx * 2).toInt()

        assertEquals(expectedW, result.canvasWidth)
        assertEquals(expectedH, result.canvasHeight)
    }

    @Test
    fun `layout with zero border equals image size`() {
        val converter = UnitConverter(4000, 3000)
        val engine = LayoutEngine(converter)
        val scene = Scene(
            baseImage = BaseImageElement(imageUri = "/test.jpg")
        )
        val result = engine.layout(scene)

        assertEquals(4000, result.canvasWidth)
        assertEquals(3000, result.canvasHeight)
    }

    @Test
    fun `canvas size matches hand-computed pixel values`() {
        val converter = UnitConverter(4000, 3000)
        val engine = LayoutEngine(converter)
        val border = BorderWidth(5f, 10f, 5f, 10f)
        val scene = Scene(
            baseImage = BaseImageElement(
                imageUri = "/test.jpg",
                borderWidth = border
            )
        )
        val result = engine.layout(scene)

        // 独立期望值，不经 UnitConverter 推算（否则与被测代码同公式，等于自证）：
        // gmBase = √(4000 × 3000) / 1000 ≈ 3.4641 px/gm
        // 宽 = 4000 + left(10gm) + right(10gm) = 4000 + 34.64 × 2 ≈ 4069
        // 高 = 3000 + top(5gm)  + bottom(5gm) = 3000 + 17.32 × 2 ≈ 3034
        assertEquals(4069, result.canvasWidth)
        assertEquals(3034, result.canvasHeight)
    }
}
