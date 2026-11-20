package cn.alittlecookie.lutista.ui.screen

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.alittlecookie.lutista.data.database.entity.FilterEntity
import cn.alittlecookie.lutista.data.model.FilterConfig
import cn.alittlecookie.lutista.data.repository.FilterRepository
import cn.alittlecookie.lutista.data.repository.WatermarkRepository
import cn.alittlecookie.lutista.domain.engine.ImageProcessingEngine
import cn.alittlecookie.lutista.manager.LogManager
import cn.alittlecookie.lutista.manager.OutputFormat
import cn.alittlecookie.lutista.manager.SettingsManager
import cn.alittlecookie.lutista.util.ExifResolution
import cn.alittlecookie.lutista.util.ExifUtils
import cn.alittlecookie.lutista.util.FilterConfigJson.toConfig
import cn.alittlecookie.lutista.watermark.model.Scene
import cn.alittlecookie.lutista.watermark.renderer.WatermarkProcessor
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Background
import top.yukonga.miuix.kmp.icon.extended.Create
import top.yukonga.miuix.kmp.icon.extended.FavoritesFill
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.menu.OverlayIconCascadingDropdownMenu
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog
import java.io.ByteArrayOutputStream

data class PhotoItem(
    val uri: String,
    val index: Int
)

data class PhotoEffectSettings(
    val filterId: Long = 0,
    val watermarkId: String = "none",
    val isCustomized: Boolean = false
)

@Composable
fun PhotoProcessScreen(
    photoUris: List<String>,
    onBackClick: () -> Unit,
    onProcessComplete: (ProcessResult) -> Unit,
    onContinueProcessing: () -> Unit = onBackClick
) {
    BackHandler { onBackClick() }

    val context = LocalContext.current
    val filterRepository: FilterRepository = koinInject()
    val watermarkRepository: WatermarkRepository = koinInject()
    val engine: ImageProcessingEngine = koinInject()
    val watermarkProcessor: WatermarkProcessor = koinInject()
    val settingsManager: SettingsManager = koinInject()
    val logger: LogManager = koinInject()
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    val scope = rememberCoroutineScope()

    val photos = remember {
        photoUris.mapIndexed { index, uri -> PhotoItem(uri, index) }
    }

    var selectedPhotoIndex by remember { mutableIntStateOf(0) }
    var selectedTabIndex by remember { mutableIntStateOf(0) }
    var showProcessingDialog by remember { mutableStateOf(false) }
    var processingProgress by remember { mutableStateOf(0f) }
    var processingStatusText by remember { mutableStateOf("准备中...") }
    var isCancelled by remember { mutableStateOf(false) }
    var wasCancelled by remember { mutableStateOf(false) }
    var showResultScreen by remember { mutableStateOf(false) }
    var resultPhotoCount by remember { mutableIntStateOf(0) }

    var globalFilterId by remember { mutableStateOf(0L) }
    var globalWatermarkId by remember { mutableStateOf("none") }

    // Watermark scene cache: map watermarkId (String) -> Scene
    val watermarkSceneCache = remember { mutableMapOf<String, Scene?>() }

    val perImageSettings = remember { mutableStateListOf<PhotoEffectSettings>() }
    repeat(photos.size) { index ->
        if (perImageSettings.size <= index) {
            perImageSettings.add(PhotoEffectSettings())
        }
    }

    // Load saved global settings on entry
    LaunchedEffect(Unit) {
        globalFilterId = settingsManager.globalFilterId.first()
        globalWatermarkId = settingsManager.globalWatermarkId.first()
        for (i in perImageSettings.indices) {
            perImageSettings[i] = PhotoEffectSettings(
                filterId = globalFilterId,
                watermarkId = globalWatermarkId
            )
        }
    }

    fun getCurrentSettings(): PhotoEffectSettings {
        val settings = perImageSettings[selectedPhotoIndex]
        return if (settings.isCustomized) settings else PhotoEffectSettings(
            filterId = globalFilterId,
            watermarkId = globalWatermarkId
        )
    }

    fun updateCurrentSettings(filterId: Long? = null, watermarkId: String? = null) {
        val current = perImageSettings[selectedPhotoIndex]
        perImageSettings[selectedPhotoIndex] = current.copy(
            filterId = filterId ?: current.filterId,
            watermarkId = watermarkId ?: current.watermarkId,
            isCustomized = true
        )
    }

    suspend fun loadWatermarkScene(watermarkId: String): Scene? {
        if (watermarkId == "none") return null
        val id = watermarkId.toLongOrNull() ?: return null
        watermarkSceneCache[watermarkId]?.let { return it }
        return withContext(Dispatchers.IO) {
            val scene = watermarkRepository.getSceneById(id)
            watermarkSceneCache[watermarkId] = scene
            scene
        }
    }

    val filtersFromDb by filterRepository.getEnabledFilters().collectAsState(initial = emptyList())

    val filterOptions = remember(filtersFromDb) {
        buildList {
            add(FilterOption(0, "原图", null))
            addAll(filtersFromDb.map { filter ->
                FilterOption(filter.id, filter.name, null)
            })
        }
    }

    val watermarksFromDb by watermarkRepository.getEnabledWatermarks().collectAsState(initial = emptyList())

    val watermarkOptions = remember(watermarksFromDb) {
        buildList {
            add(WatermarkOption("none", "无水印", null))
            addAll(watermarksFromDb.map { wm ->
                WatermarkOption(wm.id.toString(), wm.name, null)
            })
        }
    }

    val globalSettingsEntries = listOf(
        DropdownEntry(
            items = listOf(
                DropdownItem(
                    text = "默认滤镜",
                    summary = filterOptions.find { it.id == globalFilterId }?.name ?: "原图",
                    children = filterOptions.map { option ->
                        DropdownItem(
                            text = option.name,
                            selected = globalFilterId == option.id,
                            onClick = {
                                globalFilterId = option.id
                                scope.launch { settingsManager.setGlobalFilterId(option.id) }
                                for (i in perImageSettings.indices) {
                                    if (!perImageSettings[i].isCustomized) {
                                        perImageSettings[i] = PhotoEffectSettings(
                                            filterId = option.id,
                                            watermarkId = globalWatermarkId
                                        )
                                    }
                                }
                            }
                        )
                    }
                ),
                DropdownItem(
                    text = "默认边框水印",
                    summary = watermarkOptions.find { it.id == globalWatermarkId }?.name ?: "无水印",
                    children = watermarkOptions.map { option ->
                        DropdownItem(
                            text = option.name,
                            selected = globalWatermarkId == option.id,
                            onClick = {
                                globalWatermarkId = option.id
                                scope.launch { settingsManager.setGlobalWatermarkId(option.id) }
                                for (i in perImageSettings.indices) {
                                    if (!perImageSettings[i].isCustomized) {
                                        perImageSettings[i] = PhotoEffectSettings(
                                            filterId = globalFilterId,
                                            watermarkId = option.id
                                        )
                                    }
                                }
                            }
                        )
                    }
                )
            )
        )
    )

    fun buildFilterConfig(filterId: Long): FilterConfig? {
        if (filterId == 0L) return null
        val entity = filtersFromDb.find { it.id == filterId } ?: return null
        // Use the single source of truth for entity -> config conversion.
        // A hand written FilterConfig used to drop curveConfig / lchConfig /
        // grainConfig / lutGlobalStrength, so the full resolution export lost
        // every curve, LCH and grain edit made in the filter editor.
        return entity.toConfig()
    }

    fun saveBitmapToGallery(context: Context, bitmap: Bitmap, displayName: String, exifResolution: ExifResolution?, outputFormat: OutputFormat, quality: Int): Uri? {
        val bitmapFormat = when (outputFormat) {
            OutputFormat.PNG -> Bitmap.CompressFormat.PNG
            else -> Bitmap.CompressFormat.JPEG
        }
        val mimeType = when (outputFormat) {
            OutputFormat.PNG -> "image/png"
            else -> "image/jpeg"
        }
        val bos = ByteArrayOutputStream()
        bitmap.compress(bitmapFormat, quality, bos)
        var outputBytes = bos.toByteArray()

        if (outputFormat != OutputFormat.PNG && exifResolution?.app1Segment != null) {
            val app1 = try {
                ExifUtils.setOrientationInApp1(exifResolution.app1Segment, 1)
            } catch (e: Exception) {
                Log.e("ExifUtils", "setOrientationInApp1 failed", e)
                exifResolution.app1Segment
            }
            outputBytes = ExifUtils.injectApp1ToJpeg(outputBytes, app1)
        }

        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Lutista")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues) ?: return null
        try {
            resolver.openOutputStream(uri)?.use { it.write(outputBytes) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, contentValues, null, null)
            }
            Log.d("ExifUtils", "save: $displayName → $uri (${outputBytes.size}B, hasApp1=${exifResolution?.app1Segment != null})")
            return uri
        } catch (e: Exception) {
            Log.e("ExifUtils", "save failed", e)
            try { resolver.delete(uri, null, null) } catch (_: Exception) {}
            return null
        }
    }

    fun startProcessing() {
        showProcessingDialog = true
        isCancelled = false
        wasCancelled = false
        processingProgress = 0f
        processingStatusText = "准备处理..."

        scope.launch {
            var processedCount = 0
            val totalPhotos = photos.size

            for ((photoIdx, photo) in photos.withIndex()) {
                if (isCancelled) break

                val settings = perImageSettings.getOrNull(photoIdx) ?: PhotoEffectSettings()
                val effectiveSettings = if (settings.isCustomized) settings else PhotoEffectSettings(
                    filterId = globalFilterId,
                    watermarkId = globalWatermarkId
                )

                try {
                    processingStatusText = "正在处理..."
                    processingProgress = photoIdx.toFloat() / totalPhotos

                    var cachedExif: ExifResolution? = null

                    val resultBitmap = withContext(Dispatchers.IO) {
                        ensureActive()

                        val photoUri = Uri.parse(photo.uri)
                        cachedExif = ExifUtils.resolveExifSource(context, photoUri)

                        val inputBitmap = context.contentResolver.openInputStream(photoUri)?.use { stream ->
                            BitmapFactory.decodeStream(stream)
                        }?.let { bitmap ->
                            val exif = cachedExif?.exif
                            if (exif != null) ExifUtils.applyOrientation(bitmap, exif) else bitmap
                        }
                        if (inputBitmap == null) {
                            logger.e("PhotoProcessScreen", "无法读取图片: ${photo.uri}")
                            return@withContext null
                        }

                        try {
                            ensureActive()
                            val filterConfig = buildFilterConfig(effectiveSettings.filterId)
                            var resultBitmap = if (filterConfig != null) {
                                engine.processImagePreview(inputBitmap, filterConfig)
                            } else {
                                inputBitmap.copy(Bitmap.Config.ARGB_8888, false)
                            }
                            inputBitmap.recycle()

                            // Apply watermark if selected
                            val wmScene = loadWatermarkScene(effectiveSettings.watermarkId)
                            if (wmScene != null) {
                                val watermarked = watermarkProcessor.applyWatermark(resultBitmap, wmScene)
                                if (watermarked != resultBitmap) resultBitmap.recycle()
                                resultBitmap = watermarked
                            }

                            resultBitmap
                        } catch (e: Exception) {
                            throw e
                        }
                    }

                    if (resultBitmap != null) {
                        ensureActive()
                        withContext(Dispatchers.IO) {
                            val outFormat = settingsManager.defaultOutputFormat.first()
                            val outQuality = when (outFormat) {
                                OutputFormat.PNG -> settingsManager.pngCompression.first()
                                else -> settingsManager.jpgQuality.first()
                            }
                            val ext = when (outFormat) {
                                OutputFormat.PNG -> "png"
                                else -> "jpg"
                            }
                            val template = settingsManager.outputFilenameTemplate.first()
                            val srcName = extractOriginalBasename(context, Uri.parse(photo.uri))
                            val filterName = filtersFromDb.find { it.id == effectiveSettings.filterId }?.name ?: ""
                            val daily = settingsManager.getDailyCounter()
                            val monthly = settingsManager.getMonthlyCounter()
                            val yearly = settingsManager.getYearlyCounter()
                            val baseName = cn.alittlecookie.lutista.util.FilenameExpander.expand(
                                template, srcName, filterName, daily, monthly, yearly
                            )
                            val displayName = "$baseName.$ext"
                            val outUri = saveBitmapToGallery(context, resultBitmap, displayName, cachedExif, outFormat, outQuality)
                            resultBitmap.recycle()
                        }
                        processedCount++
                        resultPhotoCount = processedCount
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    logger.e("PhotoProcessScreen", "处理图片失败: ${photo.uri}", e)
                }
            }

            showProcessingDialog = false
            if (isCancelled) {
                android.widget.Toast.makeText(
                    context,
                    "处理中断，已处理 $processedCount 张图片",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            } else {
                resultPhotoCount = processedCount
                showResultScreen = true
            }
        }
    }

    val pageAlpha by animateFloatAsState(
        targetValue = if (showResultScreen) 1f else 0f,
        animationSpec = tween(400)
    )
    if (showResultScreen || pageAlpha > 0f) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(alpha = pageAlpha)
        ) {
            ResultScreen(
                photoCount = resultPhotoCount,
                filterCount = perImageSettings.map { it.filterId }.distinct().size,
                watermarkCount = perImageSettings.map { it.watermarkId }.distinct().size,
                onContinueProcessing = onContinueProcessing,
                onBackToHome = onBackClick
            )
        }
    }
    if (showResultScreen) return

    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = "图片处理",
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = MiuixIcons.Back,
                            contentDescription = "返回"
                        )
                    }
                },
                actions = {
                    OverlayIconCascadingDropdownMenu(
                        entries = globalSettingsEntries
                    ) {
                        Icon(
                            imageVector = MiuixIcons.FavoritesFill,
                            contentDescription = "默认设置"
                        )
                    }
                }
            )
        },
        bottomBar = {
            BottomActionBar(
                photoCount = photos.size,
                onStartProcess = { startProcessing() }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            PhotoThumbnailBar(
                photos = photos,
                selectedIndex = selectedPhotoIndex,
                perImageSettings = perImageSettings,
                onPhotoSelected = { selectedPhotoIndex = it }
            )

            MainPreviewArea(
                photoUri = photos[selectedPhotoIndex].uri,
                currentSettings = getCurrentSettings(),
                filterOptions = filterOptions,
                engine = engine,
                filtersFromDb = filtersFromDb,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )

            val tabs = listOf("滤镜", "边框水印")
            TabRow(
                tabs = tabs,
                selectedTabIndex = selectedTabIndex,
                onTabSelected = { selectedTabIndex = it },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            if (selectedTabIndex == 0) {
                EffectOptionsList(
                    options = filterOptions.map { EffectItem(it.id.toString(), it.name, it.thumbnail) },
                    selectedId = getCurrentSettings().filterId.toString(),
                    globalId = globalFilterId.toString(),
                    onOptionSelected = { updateCurrentSettings(filterId = it.toLongOrNull() ?: 0L) }
                )
            } else {
                EffectOptionsList(
                    options = watermarkOptions.map { EffectItem(it.id, it.name, it.thumbnail) },
                    selectedId = getCurrentSettings().watermarkId,
                    globalId = globalWatermarkId,
                    onOptionSelected = { updateCurrentSettings(watermarkId = it) }
                )
            }
        }
    }

    if (showProcessingDialog) {
        ProcessingDialog(
            processedCount = resultPhotoCount,
            totalPhotos = photos.size,
            statusText = processingStatusText,
            show = showProcessingDialog,
            onCancel = {
                isCancelled = true
                wasCancelled = true
                resultPhotoCount = 0
                showProcessingDialog = false
            }
        )
    }
}

@Composable
private fun PhotoThumbnailBar(
    photos: List<PhotoItem>,
    selectedIndex: Int,
    perImageSettings: List<PhotoEffectSettings>,
    onPhotoSelected: (Int) -> Unit
) {
    val listState = rememberLazyListState()

    LazyRow(
        state = listState,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        itemsIndexed(photos) { index, photo ->
            PhotoThumbnail(
                photo = photo,
                isSelected = index == selectedIndex,
                isCustomized = perImageSettings.getOrNull(index)?.isCustomized == true,
                onClick = { onPhotoSelected(index) }
            )
        }
    }
}

@Composable
private fun PhotoThumbnail(
    photo: PhotoItem,
    isSelected: Boolean,
    isCustomized: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(64.dp)
            .clip(RoundedCornerShape(12.dp))
            .then(
                if (isSelected) {
                    Modifier.border(
                        width = 2.dp,
                        color = MiuixTheme.colorScheme.primary,
                        shape = RoundedCornerShape(12.dp)
                    )
                } else {
                    Modifier
                }
            )
            .clickable(onClick = onClick)
    ) {
        AsyncImage(
            model = photo.uri,
            contentDescription = "Photo ${photo.index + 1}",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )

        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(4.dp)
                .background(
                    color = Color.Black.copy(alpha = 0.6f),
                    shape = RoundedCornerShape(4.dp)
                )
                .padding(horizontal = 6.dp, vertical = 2.dp)
        ) {
            Text(
                text = "${photo.index + 1}",
                fontSize = 10.sp,
                color = Color.White
            )
        }

        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(4.dp)
                .size(16.dp)
                .background(
                    color = if (isCustomized) Color(0xFFFF9800) else Color(0xFF4CAF50),
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isCustomized) MiuixIcons.Create else MiuixIcons.Ok,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(10.dp)
            )
        }
    }
}

@Composable
private fun MainPreviewArea(
    photoUri: String,
    currentSettings: PhotoEffectSettings,
    filterOptions: List<FilterOption>,
    engine: ImageProcessingEngine,
    filtersFromDb: List<FilterEntity>,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val watermarkRepository: WatermarkRepository = koinInject()
    val watermarkProcessor: WatermarkProcessor = koinInject()
    var isLongPressed by remember { mutableStateOf(false) }
    var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }

    // Cache: decoded RGBA8 bytes for current photo (survives filter changes)
    var cachedRgbaBytes by remember { mutableStateOf<ByteArray?>(null) }
    var cachedPhotoUri by remember { mutableStateOf("") }
    var cachedWidth by remember { mutableIntStateOf(0) }
    var cachedHeight by remember { mutableIntStateOf(0) }
    // Generation counter: discard stale GPU results when rapidly switching filters
    var filterGeneration by remember { mutableIntStateOf(0) }

    fun buildConfigForPreview(filterId: Long): FilterConfig? {
        if (filterId == 0L) return null
        val entity = filtersFromDb.find { it.id == filterId } ?: return null
        // Must stay identical to the export path (see buildFilterConfig above):
        // both now delegate to FilterEntity.toConfig().
        return entity.toConfig()
    }

    // Step 1: Decode image at preview resolution and cache RGBA bytes (only when photo changes)
    LaunchedEffect(photoUri) {
        withContext(Dispatchers.IO) {
            try {
                // First pass: get dimensions only
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(Uri.parse(photoUri))?.use {
                    BitmapFactory.decodeStream(it, null, bounds)
                }
                val maxPreviewSize = 1024
                val sampleSize = maxOf(
                    bounds.outWidth / maxPreviewSize,
                    bounds.outHeight / maxPreviewSize,
                    1
                ).coerceAtLeast(1)

                // Second pass: decode at reduced size
                val bmp = context.contentResolver.openInputStream(Uri.parse(photoUri))?.use { stream ->
                    BitmapFactory.decodeStream(stream, null, BitmapFactory.Options().apply {
                        inSampleSize = sampleSize
                    })
                }?.let { ExifUtils.correctOrientation(it, context, Uri.parse(photoUri)) }
                if (bmp != null) {
                    val w = bmp.width
                    val h = bmp.height
                    val bytes = ByteArray(w * h * 4)
                    bmp.copyPixelsToBuffer(java.nio.ByteBuffer.wrap(bytes))
                    cachedRgbaBytes = bytes
                    cachedPhotoUri = photoUri
                    cachedWidth = w
                    cachedHeight = h
                    bmp.recycle()
                }
            } catch (_: Exception) {
                cachedRgbaBytes = null
            }
        }
    }

    // Step 2: Apply filter + watermark using cached bytes (only when filter/watermark changes)
    LaunchedEffect(cachedPhotoUri, cachedRgbaBytes, currentSettings.filterId, currentSettings.watermarkId, filtersFromDb) {
        val gen = ++filterGeneration
        val bytes = cachedRgbaBytes
        if (bytes == null || cachedWidth == 0) {
            previewBitmap = null
            return@LaunchedEffect
        }
        val filterConfig = buildConfigForPreview(currentSettings.filterId)
        val t0 = System.nanoTime()
        withContext(Dispatchers.IO) {
            val result = if (filterConfig != null) {
                engine.processImageGpuFromBytes(bytes, cachedWidth, cachedHeight, filterConfig)
            } else {
                val bmp = Bitmap.createBitmap(cachedWidth, cachedHeight, Bitmap.Config.ARGB_8888)
                bmp.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(bytes))
                bmp
            }
            // Apply watermark if selected
            val wmId = currentSettings.watermarkId
            Log.d("PhotoProcess", "Preview watermark: id=$wmId")
            val wmScene = if (wmId != "none") watermarkRepository.getSceneById(wmId.toLongOrNull() ?: 0L) else null
            Log.d("PhotoProcess", "Preview watermark scene: ${wmScene?.let { "elements=${it.elements.size}" } ?: "null"}")
            val finalResult = if (result != null && wmScene != null) {
                watermarkProcessor.applyWatermark(result, wmScene)
            } else result ?: return@withContext

            val elapsed = (System.nanoTime() - t0) / 1_000_000
            Log.d("PreviewTiming", "filterId=${currentSettings.filterId} gen=$gen elapsed=${elapsed}ms size=${cachedWidth}x${cachedHeight}")
            if (gen == filterGeneration) {
                previewBitmap = finalResult
            }
        }
    }

    Card(
        modifier = modifier
            .padding(horizontal = 16.dp, vertical = 8.dp),
        cornerRadius = 16.dp
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        val longPressResult = withTimeoutOrNull(400L) {
                            waitForUpOrCancellation()
                        }
                        if (longPressResult == null) {
                            isLongPressed = true
                            waitForUpOrCancellation()
                            isLongPressed = false
                        }
                    }
                }
        ) {
            // Build original bitmap from cached bytes (same data as "原图" filter)
            val originalBitmap = remember(cachedRgbaBytes, cachedWidth, cachedHeight) {
                val bytes = cachedRgbaBytes
                if (bytes != null && cachedWidth > 0) {
                    val bmp = Bitmap.createBitmap(cachedWidth, cachedHeight, Bitmap.Config.ARGB_8888)
                    bmp.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(bytes))
                    bmp
                } else null
            }

            if (isLongPressed) {
                AsyncImage(
                    model = originalBitmap ?: photoUri,
                    contentDescription = "Original",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            } else if (previewBitmap != null) {
                AsyncImage(
                    model = previewBitmap,
                    contentDescription = "Preview with filter",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                AsyncImage(
                    model = photoUri,
                    contentDescription = "Preview",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            }

            val filterName = filterOptions.find { it.id == currentSettings.filterId }?.name ?: "默认"
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(12.dp)
                    .background(
                        color = Color.Black.copy(alpha = 0.6f),
                        shape = RoundedCornerShape(16.dp)
                    )
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text = filterName,
                    fontSize = 12.sp,
                    color = Color.White
                )
            }

            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(12.dp)
                    .background(
                        color = if (currentSettings.isCustomized) {
                            Color(0xFFFF9800).copy(alpha = 0.8f)
                        } else {
                            MiuixTheme.colorScheme.primary.copy(alpha = 0.8f)
                        },
                        shape = RoundedCornerShape(8.dp)
                    )
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = if (currentSettings.isCustomized) MiuixIcons.Create else MiuixIcons.Ok,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(12.dp)
                    )
                    Text(
                        text = if (currentSettings.isCustomized) "已单独设置效果\n长按查看原图" else "已应用全局效果\n长按查看原图",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.White
                    )
                }
            }

        }
    }
}

@Composable
private fun EffectOptionsList(
    options: List<EffectItem>,
    selectedId: String,
    globalId: String,
    onOptionSelected: (String) -> Unit
) {
    val listState = rememberLazyListState()

    LazyRow(
        state = listState,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        itemsIndexed(options) { _, item ->
            EffectOptionCard(
                item = item,
                isSelected = item.id == selectedId,
                isGlobalDefault = item.id == globalId,
                onClick = { onOptionSelected(item.id) }
            )
        }
    }
}

@Composable
private fun EffectOptionCard(
    item: EffectItem,
    isSelected: Boolean,
    isGlobalDefault: Boolean,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(88.dp)
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .size(88.dp)
                .clip(RoundedCornerShape(14.dp))
                .then(
                    if (isSelected) {
                        Modifier.border(
                            width = 2.dp,
                            color = MiuixTheme.colorScheme.primary,
                            shape = RoundedCornerShape(14.dp)
                        )
                    } else {
                        Modifier
                    }
                )
        ) {
            if (item.thumbnail != null) {
                AsyncImage(
                    model = item.thumbnail,
                    contentDescription = item.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MiuixTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = item.name.first().toString(),
                        fontSize = 24.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
            }

            if (isGlobalDefault) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp)
                        .background(
                            color = MiuixTheme.colorScheme.primary,
                            shape = RoundedCornerShape(4.dp)
                        )
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "默认",
                        fontSize = 9.sp,
                        color = Color.White
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = item.name,
            fontSize = 12.sp,
            color = if (isSelected) {
                MiuixTheme.colorScheme.primary
            } else {
                MiuixTheme.colorScheme.onSurface
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun BottomActionBar(
    photoCount: Int,
    onStartProcess: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        cornerRadius = 16.dp
    ) {
        Button(
            onClick = onStartProcess,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            colors = ButtonDefaults.buttonColors(
                color = MiuixTheme.colorScheme.primary
            )
        ) {
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = MiuixIcons.Demibold.Background,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(24.dp))
                Text(
                    text = "开始处理",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
                Spacer(modifier = Modifier.width(16.dp))
                Box(
                    modifier = Modifier
                        .background(
                            color = Color.White.copy(alpha = 0.3f),
                            shape = RoundedCornerShape(12.dp)
                        )
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "$photoCount 张",
                        fontSize = 12.sp,
                        color = Color.White
                    )
                }
            }
        }
    }
}

@Composable
private fun ProcessingDialog(
    processedCount: Int,
    totalPhotos: Int,
    statusText: String,
    show: Boolean,
    onCancel: () -> Unit
) {
    WindowDialog(
        show = show,
        onDismissRequest = onCancel
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            CircularProgressIndicator(size = 56.dp)

            Text(
                text = "$processedCount / $totalPhotos",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = MiuixTheme.colorScheme.onSurface
            )

            Text(
                text = statusText,
                fontSize = 14.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )

            Button(
                onClick = onCancel,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    color = MiuixTheme.colorScheme.secondaryContainer
                )
            ) {
                Text("取消处理", color = MiuixTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun ResultScreen(
    photoCount: Int,
    filterCount: Int,
    watermarkCount: Int,
    onContinueProcessing: () -> Unit,
    onBackToHome: () -> Unit
) {
    var phase by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        phase = 1; delay(100)
        phase = 2; delay(120)
        phase = 3; delay(120)
        phase = 4
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MiuixTheme.colorScheme.background)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        PhaseElement(show = phase >= 1) {
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .background(color = Color(0xFF4CAF50), shape = CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = MiuixIcons.Demibold.Ok,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(40.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        PhaseElement(show = phase >= 2) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "处理完成",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MiuixTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "已成功处理 $photoCount 张图片",
                    fontSize = 15.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        PhaseElement(show = phase >= 3) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatisticItem("处理图片", photoCount)
                StatisticItem("滤镜效果", filterCount)
                StatisticItem("边框水印", watermarkCount)
            }
        }

        Spacer(modifier = Modifier.height(48.dp))

        PhaseElement(show = phase >= 4) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Button(
                    onClick = onContinueProcessing,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    colors = ButtonDefaults.buttonColors(color = MiuixTheme.colorScheme.primary)
                ) {
                    Text(text = "继续处理图片", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                }
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = onBackToHome,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    colors = ButtonDefaults.buttonColors(color = MiuixTheme.colorScheme.secondary)
                ) {
                    Text(text = "返回主页", fontSize = 17.sp, color = MiuixTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Composable
private fun PhaseElement(show: Boolean, content: @Composable () -> Unit) {
    val progress by animateFloatAsState(
        targetValue = if (show) 1f else 0f,
        animationSpec = tween(500)
    )
    Box(
        modifier = Modifier.graphicsLayer {
            alpha = progress
            translationY = (1f - progress) * 40f
        }
    ) {
        content()
    }
}

@Composable
private fun StatisticItem(label: String, value: Int) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "$value",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = MiuixTheme.colorScheme.onSurface
        )
        Text(
            text = label,
            fontSize = 12.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
        )
    }
}

data class FilterOption(
    val id: Long,
    val name: String,
    val thumbnail: String?
)

data class WatermarkOption(
    val id: String,
    val name: String,
    val thumbnail: String?
)

data class EffectItem(
    val id: String,
    val name: String,
    val thumbnail: String?
)

data class ProcessResult(
    val photoCount: Int,
    val filterCount: Int,
    val watermarkCount: Int
)

private fun extractOriginalBasename(context: Context, uri: Uri): String {
    // Try full projection first with column name matching
    try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val names = cursor.columnNames
            val dispIdx = names.indexOfFirst {
                it.equals("_display_name", ignoreCase = true) ||
                it.equals("display_name", ignoreCase = true)
            }
            if (dispIdx >= 0 && cursor.moveToFirst()) {
                val name = cursor.getString(dispIdx)
                if (!name.isNullOrEmpty()) return name.substringBeforeLast('.')
            }
            // Fallback: pick first column that looks like a filename
            if (cursor.moveToFirst()) {
                for (i in names.indices) {
                    val v = cursor.getString(i) ?: continue
                    if (v.contains('.') && v.length in 5..200) return v.substringBeforeLast('.')
                }
            }
        }
    } catch (_: Exception) {}

    // Fallback: extract ID and query MediaStore directly
    val m = Regex("""(\d+)""").find(uri.lastPathSegment ?: "")
    if (m != null) {
        try {
            val mediaUri = Uri.withAppendedPath(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, m.value
            )
            context.contentResolver.query(mediaUri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) {
                    val name = cursor.getString(idx)
                    if (!name.isNullOrEmpty()) return name.substringBeforeLast('.')
                }
            }
        } catch (_: Exception) {}
    }

    return "unknown"
}
