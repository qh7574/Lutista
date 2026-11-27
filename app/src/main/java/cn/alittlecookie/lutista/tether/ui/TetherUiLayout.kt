package cn.alittlecookie.lutista.tether.ui

/** Size policy shared by every tether page; it never depends on a camera session or orientation. */
data class TetherLayoutSpec(
    val landscape: Boolean,
    val leftPanelWidthDp: Int,
    val rightToolbarWidthDp: Int = 92,
    val previewAspectRatio: Float = 1.4817f,
) {
    companion object {
        fun fromSize(widthDp: Float, heightDp: Float, sidebarCollapsed: Boolean = false) = TetherLayoutSpec(
            landscape = widthDp > heightDp * 1.2f,
            leftPanelWidthDp = if (sidebarCollapsed) 48 else 208,
        )

        /** availableHeight excludes the fixed header and shutter toolbar. */
        internal fun portrait(availableHeightDp: Float, fullHeightDp: Float, stage: Int): TetherPortraitLayout {
            val available = availableHeightDp.coerceAtLeast(0f)
            val mode = 48f.coerceAtMost(available)
            val requestedPanel = listOf(56f, 224f, 330f)[stage.coerceIn(0, 2)]
            val panel = requestedPanel.coerceAtMost((available - mode).coerceAtLeast(0f))
            // On short windows the panel overlays the preview while the toolbar keeps its own space.
            val maxPreview = (fullHeightDp * .45f).coerceAtLeast(240f).coerceAtMost(available)
            val minPreview = 240f.coerceAtMost(maxPreview)
            val preview = (available - mode - panel).coerceIn(minPreview, maxPreview)
            return TetherPortraitLayout(panelHeightDp = panel, previewHeightDp = preview, modeHeightDp = mode)
        }
    }
}

internal data class TetherPortraitLayout(
    val panelHeightDp: Float,
    val previewHeightDp: Float,
    val modeHeightDp: Float,
)
