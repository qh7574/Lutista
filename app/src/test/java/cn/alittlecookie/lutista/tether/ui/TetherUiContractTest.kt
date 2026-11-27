package cn.alittlecookie.lutista.tether.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TetherUiContractTest {
    @Test
    fun `breakpoint is based on current viewport with a strict 1 point 2 ratio`() {
        assertFalse(TetherLayoutSpec.fromSize(600f, 500f).landscape)
        assertTrue(TetherLayoutSpec.fromSize(601f, 500f).landscape)
        assertFalse(TetherLayoutSpec.fromSize(360f, 800f).landscape)
    }

    @Test
    fun `landscape reserves the approved control widths independently of collapse`() {
        val expanded = TetherLayoutSpec.fromSize(900f, 400f)
        val collapsed = TetherLayoutSpec.fromSize(900f, 400f, sidebarCollapsed = true)
        assertEquals(208, expanded.leftPanelWidthDp)
        assertEquals(48, collapsed.leftPanelWidthDp)
        assertEquals(92, expanded.rightToolbarWidthDp)
        assertEquals(92, collapsed.rightToolbarWidthDp)
        assertEquals(1.4817f, expanded.previewAspectRatio, 0.00001f)
    }

    @Test
    fun `portrait peek respects the 45 percent preview cap on tall windows`() {
        val layout = TetherLayoutSpec.portrait(628f, 800f, 0)
        assertEquals(360f, layout.previewHeightDp, 0.001f)
        assertEquals(56f, layout.panelHeightDp, 0.001f)
        assertEquals(48f, layout.modeHeightDp, 0.001f)
    }

    @Test
    fun `expanded portrait panel overlays preview within its reserved region`() {
        val layout = TetherLayoutSpec.portrait(308f, 480f, 2)
        assertEquals(240f, layout.previewHeightDp, 0.001f)
        assertEquals(260f, layout.panelHeightDp, 0.001f)
        assertEquals(308f, layout.panelHeightDp + layout.modeHeightDp, 0.001f)
    }

    @Test
    fun `very short viewports never produce a negative or overflowing panel`() {
        listOf(0f, 20f, 48f, 90f, 180f).forEach { available ->
            (0..2).forEach { stage ->
                val layout = TetherLayoutSpec.portrait(available, available + 172f, stage)
                assertTrue(layout.panelHeightDp >= 0f)
                assertTrue(layout.previewHeightDp in 0f..available)
                assertTrue(layout.panelHeightDp + layout.modeHeightDp <= available)
            }
        }
    }

    @Test
    fun `missing and blank exposure values always use an em dash`() {
        assertEquals("—", TetherParameterUi().displayText)
        assertEquals("—", TetherParameterUi(value = 100, displayValue = " ").displayText)
    }

    @Test
    fun `parameter editing requires a writable nonempty descriptor and no active write`() {
        val option = TetherParameterOption(100, "100")
        assertFalse(TetherParameterUi(writable = true).enabled)
        assertFalse(TetherParameterUi(options = listOf(option)).enabled)
        assertFalse(TetherParameterUi(options = listOf(option), writable = true, writing = true).enabled)
        assertTrue(TetherParameterUi(options = listOf(option), writable = true).enabled)
    }
}
