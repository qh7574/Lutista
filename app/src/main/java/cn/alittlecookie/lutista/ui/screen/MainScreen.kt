package cn.alittlecookie.lutista.ui.screen

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import cn.alittlecookie.lutista.gpu.VulkanGpuEngine
import cn.alittlecookie.lutista.manager.SettingsManager
import cn.alittlecookie.lutista.ui.theme.LutistaTheme
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.nav.core.NavCornerClipMode
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.NavDisplayEffects
import top.yukonga.miuix.kmp.nav.core.NavKey
import top.yukonga.miuix.kmp.nav.core.rememberNavBackStack
import top.yukonga.miuix.kmp.nav.core.rememberNavSystemCornerRadius
import top.yukonga.miuix.kmp.nav.transition.NavSwipeDirection
import top.yukonga.miuix.kmp.nav.transition.NavTransitions
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/** MainTabScreen 里 HorizontalPager 的页序，供「返回主页时定位 Tab」使用。 */
internal const val TAB_HOME = 0
internal const val TAB_RECIPE = 1
internal const val TAB_SETTINGS = 2

/**
 * 应用内的路由。
 *
 * miuix-nav 的 [rememberNavBackStack] 会用 kotlinx.serialization 把整条栈写进 rememberSaveable，
 * 所以整棵路由树（含每个子类型）都必须标 @Serializable；路由一律用 data class / data object，
 * 因为 contentKey 的 `toString()` 同时是 rememberSaveable 的命名空间。
 */
@Serializable
sealed interface Screen : NavKey {
    @Serializable
    data object Main : Screen

    /**
     * 图片处理页。
     *
     * @property requestId 每次入栈都由 [MainScreen] 发一个进程内唯一的值。
     *   存在的理由：miuix-nav 的 contentKey 等于路由的 `toString()`，且整条栈上必须唯一；
     *   而「处理页 → 继续处理 → 再选同一批图」会构造出一条**值完全相等**的路由。
     *   没有这个字段时它会被 `pushRoute` 判成重复而静默丢弃（点了没反应）。
     *   默认值 0 让老调用点 / 反序列化旧 JSON 仍然可用。
     */
    @Serializable
    data class PhotoProcess(val uris: List<String>, val requestId: Long = 0L) : Screen

    @Serializable
    data class FilterEditor(val filterId: Long) : Screen

    @Serializable
    data class WatermarkEditor(val watermarkId: Long) : Screen

    @Serializable
    data object ThemeSettings : Screen
}

@Composable
fun MainScreen() {
    val settingsManager: SettingsManager = koinInject()

    var themeMode by remember { mutableStateOf(settingsManager.getThemeModeSync()) }
    var useMonet by remember { mutableStateOf(settingsManager.getUseMonetSync()) }
    var useDynamicColor by remember { mutableStateOf(settingsManager.getUseDynamicColorSync()) }
    var seedColor by remember { mutableStateOf(settingsManager.getSeedColorSync()) }
    var paletteStyle by remember { mutableStateOf(settingsManager.getPaletteStyleSync()) }
    var colorSpec by remember { mutableStateOf(settingsManager.getColorSpecSync()) }

    LaunchedEffect(Unit) {
        settingsManager.themeMode.collect { themeMode = it }
    }
    LaunchedEffect(Unit) {
        settingsManager.useMonet.collect { useMonet = it }
    }
    LaunchedEffect(Unit) {
        settingsManager.useDynamicColor.collect { useDynamicColor = it }
    }
    LaunchedEffect(Unit) {
        settingsManager.seedColor.collect { seedColor = it }
    }
    LaunchedEffect(Unit) {
        settingsManager.paletteStyle.collect { paletteStyle = it }
    }
    LaunchedEffect(Unit) {
        settingsManager.colorSpec.collect { colorSpec = it }
    }

    LutistaTheme(
        themeMode = themeMode,
        useMonet = useMonet,
        useDynamicColor = useDynamicColor,
        seedColor = seedColor,
        paletteStyle = paletteStyle,
        colorSpec = colorSpec
    ) {
        MainScreenContent()
    }
}

@Composable
private fun MainScreenContent() {
    val vulkanGpuEngine: VulkanGpuEngine = koinInject()
    val context = LocalContext.current

    var showVulkanDialog by remember { mutableStateOf(false) }
    // 回到主页时要落到哪个 Tab；null 表示当前没有待处理的定位请求。
    // 由 MainTabScreen 定位成功后回调清空（见 requestedPage 参数）。
    var pendingMainTab by remember { mutableStateOf<Int?>(null) }
    var recipeSubTab by remember { mutableIntStateOf(0) }
    // 编辑器是否有未保存修改（由页面通过 onHasChangesChange 上报）。
    // 用途：决定侧滑关闭是否允许 —— 有未保存修改时必须关掉手势，否则 swipeDismiss 的提交
    // 会直接调 NavDisplay.onBack，绕过页面内「保存更改?」守卫，静默丢弃修改。
    // 为什么不是「onBack 里不 pop 只弹框」：miui 的手势提交把深度驱动的收敛交给 renderer 的
    // retarget（`LaunchedEffect(target)` 按 backStack.lastIndex 触发），不 pop 就没有新 target，
    // 页面会永久卡在半滑出的位置（真机实测，见交付说明），连对话框都跟着位移层被切到屏幕外。
    var editorHasChanges by remember { mutableStateOf(false) }

    // 必须显式写出超类型 <Screen>：rememberNavBackStack 用 reified T 采集 List<T> 的序列化器，
    // 写成 <Screen.Main> 会让栈里只剩 object 类型，push 子类型后保存/恢复时序列化失败。
    val backStack = rememberNavBackStack<Screen>(Screen.Main)

    // P2：PhotoProcess 每次入栈都要有不同身份，否则「继续处理 → 选同一批图」会因值相等被判成重复。
    // 起始值取当前时间，避免进程重启后与已恢复的栈里残留的 requestId 撞车；此处只做自增、不参与 UI。
    var photoRequestSeq by remember { mutableLongStateOf(System.currentTimeMillis()) }

    /** 用新 requestId 构造处理页路由（同一批图片再次入栈也不会被判成重复）。 */
    fun newPhotoProcessScreen(uris: List<String>): Screen.PhotoProcess {
        photoRequestSeq += 1
        return Screen.PhotoProcess(uris = uris, requestId = photoRequestSeq)
    }

    // 入栈统一走这里。miuix-nav 要求整条栈上的 contentKey 唯一，重复会在 reconcile 阶段抛
    // IllegalArgumentException；这里保留「与栈顶相同则不叠」的安全网（P2 之后真正的重复只可能来自
    // 同一 requestId 的重复入栈）。
    fun pushRoute(screen: Screen) {
        if (backStack.lastOrNull() == screen) return
        backStack.add(screen)
    }

    /**
     * 唯一的出栈入口（幂等）。
     *
     * P0：栈底 [Screen.Main] 永不出栈。连按两次系统返回时，第一个事件 pop 之后，
     * 「页面内 BackHandler」和「NavDisplay.onBack」这两条回调都还没被 recompose 摘掉，
     * 第二个事件会再进来 pop 一次 —— 把唯一的 Screen.Main 也弹掉就命中 NavDisplay 内部的
     * `require(backStack.isNotEmpty())`，直接 IllegalArgumentException 崩溃。
     * 因此所有出栈点（页面返回箭头 / 系统返回键 / 侧滑 dismiss / 处理完成 / NavDisplay.onBack）
     * 都必须走这里，不能各自 `removeLastOrNull()`。
     *
     * 顺带把「返回主页要落到哪个 Tab」收在一处，避免三条返回路径各写一份映射而漂移。
     */
    fun popRoute() {
        if (backStack.size <= 1) return
        mainTabAfterPop(backStack.lastOrNull())?.let { pendingMainTab = it }
        backStack.removeLastOrNull()
    }

    /**
     * 打开编辑器页。
     *
     * 关键不变量：每开一个新编辑器都要把 [editorHasChanges] 归零。
     * 否则上一次会话遗留的 true 会让新页面一进来就没有侧滑关闭（表现为「侧滑突然失效」）。
     */
    fun pushEditorRoute(screen: Screen) {
        editorHasChanges = false
        pushRoute(screen)
    }

    LaunchedEffect(Unit) {
        while (!vulkanGpuEngine.initialized) {
            kotlinx.coroutines.delay(100)
        }
        if (!vulkanGpuEngine.isAvailable) {
            showVulkanDialog = true
        }
    }

    if (showVulkanDialog) {
        WindowDialog(show = true, onDismissRequest = { showVulkanDialog = false }) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("设备不支持", fontSize = 22.sp, fontWeight = FontWeight.Bold,
                    color = MiuixTheme.colorScheme.onSurface)
                Text("您的设备不支持 Vulkan GPU 加速。\n请下载支持 CPU 处理的版本。",
                    fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    textAlign = TextAlign.Center, lineHeight = 20.sp)
                Button(onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://github.com/qh7574/Lutista-Android")))
                }, modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(color = MiuixTheme.colorScheme.primary)) {
                    Text("下载 CPU 版本", color = Color.White)
                }
                Button(onClick = { showVulkanDialog = false }, modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(color = MiuixTheme.colorScheme.surfaceVariant)) {
                    Text("取消", color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
            }
        }
        return
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(50)
    ) { uris ->
        if (uris.isNotEmpty()) {
            pushRoute(newPhotoProcessScreen(uris.map { it.toString() }))
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { /* no-op: ACCESS_MEDIA_LOCATION is silently granted */ }

    fun launchPicker() {
        photoPickerLauncher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        )
    }

    // Request all required permissions at startup
    LaunchedEffect(Unit) {
        val permissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) permissions.add(Manifest.permission.POST_NOTIFICATIONS)

            if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES)
                != PackageManager.PERMISSION_GRANTED
            ) permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
            ) permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_MEDIA_LOCATION)
                != PackageManager.PERMISSION_GRANTED
            ) permissions.add(Manifest.permission.ACCESS_MEDIA_LOCATION)
        }

        if (permissions.isNotEmpty()) {
            notificationPermissionLauncher.launch(permissions.toTypedArray())
        }
    }

    val settingsManager: SettingsManager = koinInject()
    val scope = rememberCoroutineScope()

    val monitorFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            context.contentResolver.takePersistableUriPermission(
                it, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            scope.launch { settingsManager.setMonitorPath(it.toString()) }
        }
    }

    val outputFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            context.contentResolver.takePersistableUriPermission(
                it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            scope.launch { settingsManager.setOutputPath(it.toString()) }
        }
    }

    // 转场与滑动关闭：全局用 MIUIX 的默认预设（全宽滑入 + 被覆盖层 1/4 视差 + 轻微透明衰减），
    // 每个可 push 的页面显式开启「从左往右」滑动关闭（预设本身不默认开启任何 swipeDismiss）。
    val navTransition = NavTransitions.MiuixDefault
    val navEffects = NavDisplayEffects(
        // 进入页贴合屏幕圆角，跟随设备实际圆角（读不到时是 0.dp，即不裁切）。
        enableCornerClip = true,
        cornerClipRadius = rememberNavSystemCornerRadius(),
        // 滑入式转场只需要裁屏幕边缘那一侧的两个角。
        cornerClipMode = NavCornerClipMode.Leading,
        // 页面整体滑动时露出的底色，用主题背景色，避免出现黑边。
        backdropColor = MiuixTheme.colorScheme.background
    )

    // 编辑器页侧滑关闭的开关。干净（无未保存修改）时允许 miui 的滑动手势直接退回；
    // 有未保存修改时把方向设为 None 让手势根本不engage，返回只能走「返回箭头 / 系统返回键」，
    // 这两条都会命中页面内的 requestBack() 守卫。这样既不丢改动，也不会像「onBack 里不 pop」
    // 那样把手势的深度驱动留在半路（详见 editorHasChanges 的注释）。
    val editorSwipeDismiss =
        if (editorHasChanges) NavSwipeDirection.None else NavSwipeDirection.LeftToRight

    NavDisplay(
        backStack = backStack,
        // 系统返回键 / 预测性返回 / 侧滑关闭都汇到这里（swipeDismiss 的 onCommit 就是 onBack）。
        // 具体出栈与 Tab 落点判定全部收在 popRoute() 里，栈底保护也在那里。
        onBack = { popRoute() },
        transition = navTransition,
        effects = navEffects
    ) {
        entry<Screen.Main> {
            MainTabScreen(
                onPhotoSelected = { uris ->
                    pushRoute(newPhotoProcessScreen(uris))
                },
                launchPicker = { launchPicker() },
                onEditFilter = { filterId ->
                    pushEditorRoute(Screen.FilterEditor(filterId))
                },
                onEditWatermark = { watermarkId ->
                    pushEditorRoute(Screen.WatermarkEditor(watermarkId))
                },
                onMonitorFolderClick = { monitorFolderLauncher.launch(null) },
                onOutputFolderClick = { outputFolderLauncher.launch(null) },
                initialPage = pendingMainTab ?: TAB_HOME,
                requestedPage = pendingMainTab,
                onRequestedPageHandled = { pendingMainTab = null },
                recipeSubTab = recipeSubTab,
                onRecipeSubTabChange = { recipeSubTab = it },
                onThemeSettingsClick = { pushRoute(Screen.ThemeSettings) }
            )
        }
        entry<Screen.PhotoProcess>(swipeDismiss = NavSwipeDirection.LeftToRight) { screen ->
            PhotoProcessScreen(
                photoUris = screen.uris,
                onBackClick = { popRoute() },
                onProcessComplete = { popRoute() },
                onContinueProcessing = { launchPicker() }
            )
        }
        entry<Screen.FilterEditor>(swipeDismiss = editorSwipeDismiss) { screen ->
            FilterEditorScreen(
                filterId = screen.filterId,
                onBackClick = { popRoute() },
                onHasChangesChange = { editorHasChanges = it }
            )
        }
        entry<Screen.WatermarkEditor>(swipeDismiss = editorSwipeDismiss) { screen ->
            WatermarkEditorScreen(
                watermarkId = screen.watermarkId,
                onBackClick = { popRoute() },
                onHasChangesChange = { editorHasChanges = it }
            )
        }
        entry<Screen.ThemeSettings>(swipeDismiss = NavSwipeDirection.LeftToRight) {
            ThemeSettingsPage(
                onBackClick = { popRoute() }
            )
        }
    }
}

/**
 * 出栈后主页面应落到哪个 Tab；`null` 表示不需要纠正 Tab。
 *
 * 抽成 internal 纯函数（入参是即将出栈的路由，而不是整条栈）的唯一目的是让 JVM 单测能覆盖
 * 「三条返回路径落点一致」这条契约：页面内返回箭头 / 系统返回键 / 侧滑 dismiss 最终都汇到
 * [MainScreenContent] 里的 `popRoute()`，再由本函数决定落点，因此落点只可能在这一处漂移。
 *
 * 入参类型是 [NavKey] 而不是 [Screen]：miuix-nav 的 `NavBackStack` 就是
 * `SnapshotStateList<NavKey>`，这样调用点不必强转；非 [Screen] 的 key 一律返回 `null`。
 *
 * @param route 即将出栈的栈顶路由（`null` 表示栈为空）。
 */
internal fun mainTabAfterPop(route: NavKey?): Int? = when (route) {
    is Screen.FilterEditor, is Screen.WatermarkEditor -> TAB_RECIPE
    is Screen.ThemeSettings -> TAB_SETTINGS
    // Main / PhotoProcess / null / 未知 key：不需要纠正 Tab
    //（PhotoProcess 从主页进入，回来后仍停在原 Tab）。
    else -> null
}
