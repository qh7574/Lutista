package cn.alittlecookie.lutista.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import cn.alittlecookie.lutista.manager.SettingsManager
import cn.alittlecookie.lutista.ui.theme.getAppBarColor
import cn.alittlecookie.lutista.ui.theme.lutistaBlurEffect
import cn.alittlecookie.lutista.ui.theme.rememberLutistaBlurBackdrop
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Album
import top.yukonga.miuix.kmp.icon.extended.Filter
import top.yukonga.miuix.kmp.icon.extended.Settings

enum class MainPage(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    HOME("主页", MiuixIcons.Album),
    RECIPE("配方室", MiuixIcons.Filter),
    SETTINGS("设置", MiuixIcons.Settings)
}

/**
 * 主容器：底部 Tab + HorizontalPager 承载三个主页面。
 *
 * Tab 索引本身由上层（`MainScreenContent`）持有，本组件只负责渲染与上报：
 * - [initialPage]：pager 首次组合时落在哪一页（只在首次组合生效，见下方注释）；
 * - [requestedPage]：一次性定位请求（出栈后纠正 Tab）；
 * - [onSettledPageChange]：把用户最终的落点回写给上层。
 *
 * 这样拆分的原因：miuix-nav 的 entry 状态虽然由 `NavSaveableStateHolder` 提供，但它是
 * `NavDisplay` 内部的 `rememberSaveableStateHolder()`；只要 `NavDisplay` 整体离开组合再回来
 * （本工程的例子是无 Vulkan 设备上的「设备不支持」弹窗早退），该 holder 实例连同 entry 的
 * 已恢复状态一起被丢弃，pager 会退回 [initialPage]。把 Tab 索引提升到 NavDisplay 之外后，
 * 它落在 Activity 级 SaveableStateRegistry 上，可以真正跨 Activity 重建恢复。
 */
@Composable
fun MainTabScreen(
    onPhotoSelected: (List<String>) -> Unit,
    launchPicker: () -> Unit = {},
    onEditFilter: (Long) -> Unit = {},
    onEditWatermark: (Long) -> Unit = {},
    onMonitorFolderClick: () -> Unit = {},
    onOutputFolderClick: () -> Unit = {},
    onThemeSettingsClick: () -> Unit = {},
    initialPage: Int = 0,
    requestedPage: Int? = null,
    onRequestedPageHandled: () -> Unit = {},
    onSettledPageChange: (Int) -> Unit = {},
    recipeSubTab: Int = 0,
    onRecipeSubTabChange: (Int) -> Unit = {},
    tetherEnabled: Boolean = false,
    tetherBusy: Boolean = false,
    tetherSummary: String = "未连接",
    onTetherEnabledChange: (Boolean) -> Unit = {},
    onTetherClick: () -> Unit = {},
) {
    val pages = MainPage.entries
    val pagerState = rememberPagerState(initialPage = initialPage, pageCount = { 3 })
    val coroutineScope = rememberCoroutineScope()

    // 把「当前稳定页」回写给上层，让 Tab 索引落在 NavDisplay 之外的可保存状态里。
    // 为什么必须是 settledPage 而不是 currentPage：拖动/惯性滑动过程中 currentPage 会连续变化，
    // 用它回写会让上层状态每帧抖一次；settledPage 只在手势收敛后变一次。
    // 本效果同时承担「首帧对齐」：pager 以 initialPage 建好后立刻回写一次。
    LaunchedEffect(pagerState.settledPage) {
        onSettledPageChange(pagerState.settledPage)
    }

    // initialPage 只在首次组合生效（rememberPagerState 内部是 rememberSaveable）。
    // miuix-nav 的 MiuixDefault 转场会保留被覆盖的一层 composition（opaqueDepth = 1），
    // 从编辑器返回主页时本组件并不会重建，因此 initialPage 不会重新生效。
    // requestedPage 是上层下发的一次性定位请求：定位完成后通过 onRequestedPageHandled 清空，
    // 这样既能在返回时纠正 Tab，也不会和用户手动滑动/切换 Tab 互相打架。
    //
    // 与 onSettledPageChange 的分工（避免双向同步打架）：数据流是单向的
    // pager.settledPage → onSettledPageChange；上层 mainTab 变化**不会**反过来驱动 pager，
    // 唯一的反向通道是这个一次性的 requestedPage。scrollToPage 完成后 settledPage 变化，
    // 再由上一条效果把新落点回写，因此「出栈后跳 Tab」这条路径依然闭环。
    LaunchedEffect(requestedPage) {
        val target = requestedPage ?: return@LaunchedEffect
        if (pagerState.currentPage != target) {
            pagerState.scrollToPage(target)
        }
        onRequestedPageHandled()
    }

    val settingsManager: SettingsManager = koinInject()
    var useBlur by remember { mutableStateOf(settingsManager.getUseBlurSync()) }
    LaunchedEffect(Unit) {
        settingsManager.useBlur.collect { useBlur = it }
    }

    val bottomBarBackdrop = rememberLutistaBlurBackdrop(useBlur)
    val barColor = bottomBarBackdrop.getAppBarColor()

    Scaffold(
        bottomBar = {
            Box(
                modifier = Modifier
                    .lutistaBlurEffect(bottomBarBackdrop)
                    .background(barColor)
            ) {
                NavigationBar(color = barColor) {
                    pages.forEachIndexed { index, page ->
                        NavigationBarItem(
                            selected = pagerState.currentPage == index,
                            onClick = {
                                coroutineScope.launch {
                                    pagerState.animateScrollToPage(index)
                                }
                            },
                            icon = page.icon,
                            label = page.label
                        )
                    }
                }
            }
        }
    ) { paddingValues ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .then(
                    bottomBarBackdrop?.let { Modifier.layerBackdrop(it) } ?: Modifier
                ),
            userScrollEnabled = true,
            beyondViewportPageCount = 1
        ) { page ->
            when (page) {
                0 -> HomeScreen(
                    launchPicker = launchPicker,
                    onMonitorFolderClick = onMonitorFolderClick,
                    onOutputFolderClick = onOutputFolderClick,
                    tetherEnabled = tetherEnabled,
                    tetherBusy = tetherBusy,
                    tetherSummary = tetherSummary,
                    onTetherEnabledChange = onTetherEnabledChange,
                    onTetherClick = onTetherClick,
                )
                1 -> RecipeRoomScreen(
                    bottomBarHeight = paddingValues.calculateBottomPadding(),
                    onEditFilter = onEditFilter,
                    onEditWatermark = onEditWatermark,
                    selectedSubTab = recipeSubTab,
                    onSubTabChange = onRecipeSubTabChange
                )
                2 -> SettingsScreen(
                    onThemeSettingsClick = onThemeSettingsClick
                )
            }
        }
    }
}
