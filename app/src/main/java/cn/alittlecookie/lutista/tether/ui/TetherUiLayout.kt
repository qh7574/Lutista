package cn.alittlecookie.lutista.tether.ui

/** Size policy shared by every tether page; it never depends on a camera session or orientation. */
data class TetherLayoutSpec(
    val landscape: Boolean,
    val leftRailWidthDp: Int,
    val rightRailWidthDp: Int,
    val previewAspectRatio: Float = 1.4817f,
) {
    companion object {
        /** 横屏取景左右两侧栏宽度：左栏放参数，右栏放快门与开关。 */
        const val LEFT_RAIL_WIDTH_DP = 104
        const val RIGHT_RAIL_WIDTH_DP = 84

        fun fromSize(widthDp: Float, heightDp: Float) = TetherLayoutSpec(
            landscape = widthDp > heightDp * 1.2f,
            leftRailWidthDp = LEFT_RAIL_WIDTH_DP,
            rightRailWidthDp = RIGHT_RAIL_WIDTH_DP,
        )
    }
}

/** 竖屏取景底部参数面板的三段高度（收起 / 预览 / 展开）。 */
internal val TetherPortraitPanelHeights = listOf(96f, 268f, 372f)
