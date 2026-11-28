package cn.alittlecookie.lutista.tether.repository

import cn.alittlecookie.lutista.tether.objects.CameraThumbKey
import cn.alittlecookie.lutista.tether.ui.TetherMediaKind
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 全屏预览的取图方式必须按类型分派：RAW 走 FULL_JPEG 会因为没有「整份下载」路径而整张空白
 * （真机 logs_1795845419086 的 RW2 大图预览加载不出来）。
 */
class TetherPreviewKindTest {
    @Test
    fun jpegUsesFullDownloadForTheSharpestPreview() {
        assertEquals(CameraThumbKey.Kind.FULL_JPEG, previewKindFor(TetherMediaKind.Jpeg))
    }

    @Test
    fun rawFallsBackToItsEmbedded1920Preview() {
        assertEquals(CameraThumbKey.Kind.RAW_PREVIEW_1920, previewKindFor(TetherMediaKind.Raw))
    }

    @Test
    fun videoAndUnknownUseTheThumbnail() {
        assertEquals(CameraThumbKey.Kind.THUMB_160, previewKindFor(TetherMediaKind.Video))
        assertEquals(CameraThumbKey.Kind.THUMB_160, previewKindFor(TetherMediaKind.Unknown))
    }
}
