package cn.alittlecookie.lutista.ui.screen

import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import cn.alittlecookie.lutista.data.repository.WatermarkRepository
import cn.alittlecookie.lutista.manager.LogManager
import cn.alittlecookie.lutista.manager.LutPrecision
import cn.alittlecookie.lutista.manager.OutputFormat
import cn.alittlecookie.lutista.manager.SettingsManager
import cn.alittlecookie.lutista.ui.theme.getAppBarColor
import cn.alittlecookie.lutista.ui.theme.lutistaBlurEffect
import cn.alittlecookie.lutista.ui.theme.rememberLutistaBlurBackdrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlaySpinnerPreference
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.window.WindowDialog
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    onThemeSettingsClick: () -> Unit = {}
) {
    val context = LocalContext.current
    val settingsManager: SettingsManager = koinInject()
    val logManager: LogManager = koinInject()
    val scope = rememberCoroutineScope()

    var outputFormat by remember { mutableStateOf(settingsManager.getDefaultOutputFormatSync()) }
    var jpgQuality by remember { mutableIntStateOf(settingsManager.getJpgQualitySync()) }
    var pngCompression by remember { mutableIntStateOf(settingsManager.getPngCompressionSync()) }
    var lutPrecision by remember { mutableStateOf(if (settingsManager.getLutPrecisionSync() == 65) LutPrecision.HIGH else LutPrecision.STANDARD) }
    var logEnabled by remember { mutableStateOf(settingsManager.isLogEnabledSync()) }

    LaunchedEffect(Unit) {
        settingsManager.defaultOutputFormat.collect { outputFormat = it }
    }
    LaunchedEffect(Unit) {
        settingsManager.jpgQuality.collect { jpgQuality = it }
    }
    LaunchedEffect(Unit) {
        settingsManager.pngCompression.collect { pngCompression = it }
    }
    LaunchedEffect(Unit) {
        settingsManager.lutPrecision.collect { lutPrecision = it }
    }
    LaunchedEffect(Unit) {
        settingsManager.logEnabled.collect { logEnabled = it }
    }

    val versionName = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "未知"
        } catch (_: Exception) {
            "未知"
        }
    }

    var useBlur by remember { mutableStateOf(settingsManager.getUseBlurSync()) }
    LaunchedEffect(Unit) {
        settingsManager.useBlur.collect { useBlur = it }
    }

    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    val backdrop = rememberLutistaBlurBackdrop(useBlur)
    val barColor = backdrop.getAppBarColor()

    Scaffold(
        topBar = {
            TopAppBar(
                modifier = Modifier.lutistaBlurEffect(backdrop),
                color = barColor,
                title = "设置",
                largeTitle = "设置",
                scrollBehavior = scrollBehavior
            )
        }
    ) { paddingValues ->
    LazyColumn(
        modifier = Modifier
            .then(backdrop?.let { Modifier.layerBackdrop(it) } ?: Modifier)
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection)
            .overScrollVertical(),
        contentPadding = PaddingValues(
            top = paddingValues.calculateTopPadding() + 12.dp,
            bottom = 92.dp,
            start = 12.dp,
            end = 12.dp
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            SmallTitle(
                text = "图片处理",
                insideMargin = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                modifier = Modifier.fillMaxWidth()
            )
            Card {
                val formatOptions = remember {
                    listOf(DropdownItem(text = "JPG"), DropdownItem(text = "PNG"))
                }
                OverlaySpinnerPreference(
                    title = "输出格式",
                    summary = "选择全局输出格式",
                    items = formatOptions,
                    selectedIndex = outputFormat.ordinal,
                    onSelectedIndexChange = { index ->
                        scope.launch {
                            settingsManager.setDefaultOutputFormat(
                                if (index == 0) OutputFormat.JPG else OutputFormat.PNG
                            )
                        }
                    }
                )

                val qualityValue =
                    if (outputFormat == OutputFormat.JPG) jpgQuality.toFloat() else pngCompression.toFloat()
                val qualityRange =
                    if (outputFormat == OutputFormat.JPG) 60f..100f else 0f..9f
                val qualitySteps =
                    if (outputFormat == OutputFormat.JPG) 40 else 9

                SliderPreference(
                    value = qualityValue,
                    onValueChange = { value ->
                        scope.launch {
                            if (outputFormat == OutputFormat.JPG) {
                                settingsManager.setJpgQuality(value.roundToInt())
                            } else {
                                settingsManager.setPngCompression(value.roundToInt())
                            }
                        }
                    },
                    title = "输出质量",
                    summary = "PNG格式始终为无损，滑块仅控制压缩等级",
                    valueText = qualityValue.roundToInt().toString(),
                    valueRange = qualityRange,
                    steps = qualitySteps
                )

                val precisionOptions = remember {
                    listOf(DropdownItem(text = "33位"), DropdownItem(text = "65位"))
                }
                OverlaySpinnerPreference(
                    title = "LUT精度",
                    summary = "控制LUT重采样的精度，同时对统一LUT生效",
                    items = precisionOptions,
                    selectedIndex = if (lutPrecision == LutPrecision.STANDARD) 0 else 1,
                    onSelectedIndexChange = { index ->
                        scope.launch {
                            settingsManager.setLutPrecision(
                                if (index == 0) LutPrecision.STANDARD else LutPrecision.HIGH
                            )
                        }
                    }
                )

                FilenameTemplatePreference(settingsManager)
            }
        }

        item {
            SmallTitle(
                text = "应用",
                insideMargin = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                modifier = Modifier.fillMaxWidth()
            )
            Card {
                ArrowPreference(
                    title = "主题设置",
                    onClick = onThemeSettingsClick
                )

                SwitchPreference(
                    title = "Logcat日志",
                    summary = "开启后打印Logcat到文件，仅保留最近7天的文件",
                    checked = logEnabled,
                    onCheckedChange = { enabled ->
                        scope.launch {
                            settingsManager.setLogEnabled(enabled)
                        }
                    }
                )

                ArrowPreference(
                    title = "导出并分享Logcat",
                    summary = "仅包含应用级别Logcat",
                    onClick = {
                        scope.launch {
                            val zipFile = runCatching { logManager.exportLogs() }
                                .onFailure { Log.w("SettingsScreen", "导出日志失败", it) }
                                .getOrNull()
                            if (zipFile == null) {
                                Toast.makeText(context, "暂无日志可导出", Toast.LENGTH_SHORT).show()
                                return@launch
                            }
                            // The provider authority in AndroidManifest.xml must stay in
                            // sync with this string; a mismatch makes getUriForFile throw
                            // IllegalArgumentException and kills the app on tap.
                            val authority = "${context.packageName}.fileprovider"
                            val uri = runCatching {
                                FileProvider.getUriForFile(context, authority, zipFile)
                            }.onFailure {
                                Log.e("SettingsScreen", "无法为 $zipFile 生成 content:// (authority=$authority)", it)
                            }.getOrNull()
                            if (uri == null) {
                                Toast.makeText(context, "日志文件无法分享", Toast.LENGTH_SHORT).show()
                                return@launch
                            }
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "application/zip"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                clipData = android.content.ClipData.newRawUri("logs", uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            val chooser = Intent.createChooser(shareIntent, "分享日志").apply {
                                // Required when this composable is hosted by a non-Activity
                                // context; harmless otherwise.
                                if (context !is android.app.Activity) {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                            }
                            runCatching { context.startActivity(chooser) }
                                .onFailure {
                                    Log.e("SettingsScreen", "启动分享失败", it)
                                    Toast.makeText(context, "没有可用的分享应用", Toast.LENGTH_SHORT).show()
                                }
                        }
                    }
                )

                CacheCleanPreference(context, settingsManager)

                ArrowPreference(
                    title = "关于应用",
                    summary = "Version: $versionName",
                    onClick = { /* TODO: 关于页面 */ }
                )
            }
        }
    }
    }
}

@Composable
private fun FilenameTemplatePreference(settingsManager: SettingsManager) {
    val scope = rememberCoroutineScope()
    var showDialog by remember { mutableStateOf(false) }
    var template by remember { mutableStateOf(settingsManager.getOutputFilenameTemplateSync()) }
    var editText by remember { mutableStateOf(template) }

    LaunchedEffect(Unit) {
        settingsManager.outputFilenameTemplate.collect { template = it }
    }

    ArrowPreference(
        title = "输出图片文件名",
        summary = "当前格式：$template",
        onClick = {
            editText = template
            showDialog = true
        }
    )

    if (showDialog) {
        WindowDialog(
            show = showDialog,
            onDismissRequest = { showDialog = false }
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 0.dp, vertical = 0.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "输出图片文件名",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "支持变量：\n· <fname>：文件名\n· <filter>：滤镜名\n· <yyyyMMdd...>：日期格式（JAVA）\n· <D+1>/<M+1>/<Y+1>：日/月/年累加\n \n非 <> 包裹的内容将不会替换；\n文件名部分系统无法解析将使用缓存键值。",
                    fontSize = 13.sp,
                    color = Color(0xFF888888)
                )
                Spacer(modifier = Modifier.height(12.dp))
                TextField(
                    value = editText,
                    onValueChange = { editText = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    colors = ButtonDefaults.buttonColorsPrimary(),
                    onClick = {
                        scope.launch {
                            settingsManager.setOutputFilenameTemplate(editText)
                        }
                        showDialog = false
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("确定", color = Color.White)
                }
            }
        }
    }
}

@Composable
private fun CacheCleanPreference(context: android.content.Context, settingsManager: SettingsManager) {
    val scope = rememberCoroutineScope()
    var showDialog by remember { mutableStateOf(false) }
    val watermarkRepository: WatermarkRepository = koinInject()

    ArrowPreference(
        title = "缓存清理",
        summary = "清理所有log和无用资源",
        onClick = { showDialog = true }
    )

    if (showDialog) {
        OverlayDialog(
            show = showDialog,
            onDismissRequest = { showDialog = false }
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 0.dp, vertical = 0.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "清理所有的Log和无用资源",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = { showDialog = false },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(color = MiuixTheme.colorScheme.secondaryContainer)
                    ) {
                        Text("取消", color = MiuixTheme.colorScheme.onSecondaryContainer)
                    }
                    Button(
                        onClick = {
                            showDialog = false
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    cleanupCache(context, watermarkRepository)
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(color = MiuixTheme.colorScheme.error)
                    ) {
                        Text("清除", color = Color.White)
                    }
                }
            }
        }
    }
}

private suspend fun cleanupCache(context: android.content.Context, watermarkRepository: WatermarkRepository) {
    val logExportDir = java.io.File(context.cacheDir, "log_export")
    deleteDir(logExportDir)

    val logsDir = java.io.File(context.filesDir, "logs")
    deleteDir(logsDir)

    val assetsDir = java.io.File(context.filesDir, "watermark_assets")
    if (assetsDir.isDirectory) {
        val usedFiles = mutableSetOf<String>()
        try {
            val all = watermarkRepository.getAllWatermarks().first()
            val refPattern = Regex(""""(imageUri|fontUri)"\s*:\s*"([^"]+)"""")
            all.forEach { entity ->
                refPattern.findAll(entity.configJson).forEach { m ->
                    usedFiles.add(m.groupValues[2].substringAfterLast('/'))
                }
            }
        } catch (_: Exception) {}
        assetsDir.listFiles()?.forEach { file ->
            if (file.isFile && file.name !in usedFiles) {
                file.delete()
            }
        }
    }
}

private fun deleteDir(dir: java.io.File) {
    dir.listFiles()?.forEach { it.deleteRecursively() }
}
