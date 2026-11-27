package cn.alittlecookie.lutista.tether.ui

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.widget.ImageView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.alittlecookie.lutista.tether.render.RenderedFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android Bitmap/ImageView ownership checks; requires no camera, USB, renderer, or GPU. */
@RunWith(AndroidJUnit4::class)
class LiveViewImageBindingInstrumentedTest {
    @Test
    fun replacementAndCloseClearEveryViewBeforeBitmapRelease() = onMain {
        val firstView = ImageView(InstrumentationRegistry.getInstrumentation().targetContext)
        val secondView = ImageView(firstView.context)
        var released = 0
        val release: (Bitmap) -> Unit = { bitmap ->
            assertTrue((firstView.drawable as? BitmapDrawable)?.bitmap !== bitmap)
            assertTrue((secondView.drawable as? BitmapDrawable)?.bitmap !== bitmap)
            released++
            bitmap.recycle()
        }
        val firstBitmap = Bitmap.createBitmap(16, 12, Bitmap.Config.ARGB_8888)
        val secondBitmap = Bitmap.createBitmap(16, 12, Bitmap.Config.ARGB_8888)
        val first = RenderedFrame(firstBitmap, 16, 12, 1L, false, release)
        val second = RenderedFrame(secondBitmap, 16, 12, 2L, false, release)
        val binding = LiveViewImageBinding()
        try {
            binding.attach(firstView)
            binding.attach(secondView)
            binding.offer(first)
            assertSame(firstBitmap, (firstView.drawable as BitmapDrawable).bitmap)
            binding.offer(second)
            assertTrue(first.isClosed)
            assertTrue(firstBitmap.isRecycled)
            assertEquals(1, released)
            assertSame(secondBitmap, (firstView.drawable as BitmapDrawable).bitmap)
            assertSame(secondBitmap, (secondView.drawable as BitmapDrawable).bitmap)
            assertFalse(secondBitmap.isRecycled)
            binding.close()
            binding.close()
            assertNull(firstView.drawable)
            assertNull(secondView.drawable)
            assertTrue(second.isClosed)
            assertTrue(secondBitmap.isRecycled)
            assertEquals(2, released)
        } finally {
            binding.close()
            first.close()
            second.close()
        }
    }

    @Test
    fun rotationLikeDetachAndAttachRetainFrameWithoutDuplicatePresentation() = onMain {
        val portraitView = ImageView(InstrumentationRegistry.getInstrumentation().targetContext)
        val landscapeView = ImageView(portraitView.context)
        val bitmap = Bitmap.createBitmap(20, 12, Bitmap.Config.ARGB_8888)
        val frame = RenderedFrame(bitmap, 20, 12, 1L, false) { it.recycle() }
        var presentations = 0
        val binding = LiveViewImageBinding { presentations++ }
        try {
            binding.offer(frame)
            assertEquals(0, presentations)
            binding.attach(portraitView)
            assertEquals(1, presentations)
            binding.detach(portraitView)
            assertNull(portraitView.drawable)
            assertFalse(bitmap.isRecycled)
            binding.attach(landscapeView)
            binding.attach(landscapeView)
            assertSame(bitmap, (landscapeView.drawable as BitmapDrawable).bitmap)
            assertEquals(1, presentations)
            binding.detach(landscapeView)
            binding.close()
            assertTrue(bitmap.isRecycled)
            assertNull(landscapeView.drawable)
        } finally {
            binding.close()
            frame.close()
        }
    }

    private fun onMain(block: () -> Unit) =
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
}
