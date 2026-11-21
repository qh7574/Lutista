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
    recipeSubTab: Int = 0,
    onRecipeSubTabChange: (Int) -> Unit = {}
) {
    val pages = MainPage.entries
    val pagerState = rememberPagerState(initialPage = initialPage, pageCount = { 3 })
    val coroutineScope = rememberCoroutineScope()

    // initialPage 只在首次组合生效（rememberPagerState 内部是 rememberSaveable）。
    // miuix-nav 的 MiuixDefault 转场会保留被覆盖的一层 composition（opaqueDepth = 1），
    // 从编辑器返回主页时本组件并不会重建，因此 initialPage 不会重新生效。
    // requestedPage 是上层下发的一次性定位请求：定位完成后通过 onRequestedPageHandled 清空，
    // 这样既能在返回时纠正 Tab，也不会和用户手动滑动/切换 Tab 互相打架。
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
                    onOutputFolderClick = onOutputFolderClick
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
