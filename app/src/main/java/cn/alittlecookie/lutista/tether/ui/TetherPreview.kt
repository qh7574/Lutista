package cn.alittlecookie.lutista.tether.ui

import android.net.Uri
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.distinctUntilChanged
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun TetherPreview(state: TetherUiState, onAction: (TetherUiAction) -> Unit, modifier: Modifier = Modifier) {
    val mediaItems = state.gallery.items
    val selectedIndex = mediaItems.indexOfFirst { it.handle == state.preview.handle }.coerceAtLeast(0)
    val pager = rememberPagerState(initialPage = selectedIndex, pageCount = { mediaItems.size })
    var overlays by remember { mutableStateOf(true) }
    var zoomed by remember { mutableStateOf(false) }
    val currentHandle by rememberUpdatedState(state.preview.handle)
    // This is local-file playback only. Camera session state never depends on the view lifecycle.
    var videoPosition by rememberSaveable(state.preview.videoLocalUri, state.preview.handle) { mutableLongStateOf(state.preview.videoPositionMillis) }
    var videoDuration by rememberSaveable(state.preview.videoLocalUri, state.preview.handle) { mutableLongStateOf(state.preview.videoDurationMillis) }
    var videoPlaying by rememberSaveable(state.preview.videoLocalUri, state.preview.handle) { mutableStateOf(state.preview.videoPlaying) }
    var videoView by remember { mutableStateOf<VideoView?>(null) }
    var videoPrepared by remember(state.preview.videoLocalUri, state.preview.handle) { mutableStateOf(false) }
    var videoError by remember(state.preview.videoLocalUri, state.preview.handle) { mutableStateOf<String?>(null) }
    val desiredVideoPosition by rememberUpdatedState(videoPosition)
    val desiredVideoPlaying by rememberUpdatedState(videoPlaying)
    LaunchedEffect(videoView, videoPrepared) {
        val view = videoView ?: return@LaunchedEffect
        while (isActive && videoPrepared) {
            videoPosition = view.currentPosition.toLong().coerceAtLeast(0)
            videoDuration = view.duration.toLong().coerceAtLeast(0)
            delay(250)
        }
    }
    LaunchedEffect(videoView, videoPrepared, videoPlaying) {
        if (videoPrepared) videoView?.let { if (videoPlaying) it.start() else it.pause() }
    }
    LaunchedEffect(state.preview.handle) {
        if (mediaItems.isNotEmpty() && pager.currentPage != selectedIndex) pager.scrollToPage(selectedIndex)
    }
    LaunchedEffect(pager, mediaItems) {
        snapshotFlow { pager.settledPage }.distinctUntilChanged().collect { index ->
            mediaItems.getOrNull(index)?.let { if (it.handle != currentHandle) onAction(TetherUiAction.ShowPreview(it.handle)) }
        }
    }
    if (mediaItems.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("此文件已不可用", color = MiuixTheme.colorScheme.onSurface) }
        return
    }
    val media = mediaItems.getOrNull(pager.currentPage) ?: return
    Box(modifier.fillMaxSize().clipToBounds().background(Color.Black)) {
        HorizontalPager(state = pager, userScrollEnabled = !zoomed, beyondViewportPageCount = 1, modifier = Modifier.fillMaxSize()) { index ->
            val item = mediaItems[index]
            var scale by remember(item.handle) { mutableFloatStateOf(1f) }
            var pan by remember(item.handle) { mutableStateOf(Offset.Zero) }
            val transformState = rememberTransformableState { zoom, movement, _ ->
                scale = (scale * zoom).coerceIn(1f, 4f)
                pan = if (scale > 1f) pan + movement else Offset.Zero
                zoomed = scale > 1f
            }
            Box(Modifier.fillMaxSize().clipToBounds()
                .pointerInput(item.handle) { detectTapGestures(
                    onTap = { overlays = !overlays },
                    onDoubleTap = { scale = if (scale > 1f) 1f else 2.5f; pan = Offset.Zero; zoomed = scale > 1f },
                ) }
                .transformable(state = transformState, canPan = { scale > 1f }), contentAlignment = Alignment.Center,
            ) {
                val localVideo = state.preview.videoLocalUri.takeIf { item.handle == state.preview.handle && item.kind == TetherMediaKind.Video && item.originalStatus == TetherOriginalStatus.Downloaded }
                if (localVideo != null) {
                    AndroidView(
                        factory = { context -> VideoView(context).also { videoView = it } },
                        modifier = Modifier.fillMaxSize(),
                        update = { view ->
                            view.setOnPreparedListener { player ->
                                videoPrepared = true
                                videoDuration = player.duration.toLong().coerceAtLeast(0)
                                view.seekTo(desiredVideoPosition.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                                if (desiredVideoPlaying) view.start()
                            }
                            view.setOnCompletionListener { videoPlaying = false; videoPosition = videoDuration }
                            view.setOnErrorListener { _, _, _ -> videoPlaying = false; videoError = "此视频无法由本机播放器解码，原片仍可保留。"; true }
                            if (view.tag != localVideo) {
                                videoPrepared = false
                                view.tag = localVideo
                                view.setVideoURI(Uri.parse(localVideo))
                            }
                        },
                        onRelease = { view -> if (videoView === view) videoView = null; view.stopPlayback() },
                    )
                } else {
                    TetherImageView(item.preview, item.name, Modifier.fillMaxSize().graphicsLayer {
                        scaleX = scale; scaleY = scale; translationX = pan.x; translationY = pan.y
                    })
                }
                if (state.preview.loading && item.handle == state.preview.handle) TetherBusy("正在读取预览…")
                if (item.kind == TetherMediaKind.Video && localVideo == null) {
                    if (item.originalStatus == TetherOriginalStatus.Downloading) TetherBusy(item.originalLabel())
                    else TetherButton(if (item.originalStatus == TetherOriginalStatus.Failed) "下载中断 · 重试" else "下载完整视频后播放",
                        { onAction(TetherUiAction.PlayVideo(item.handle)) }, enabled = state.gallery.destinationWritable && state.capabilities.canDownload)
                }
            }
        }
        if (overlays) {
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = .72f)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(media.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.MiddleEllipsis, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                    Text("${pager.currentPage + 1}/${mediaItems.size}", fontSize = 12.sp, color = Color.White)
                }
                Text(listOfNotNull(media.dimensions, media.sizeBytes?.let(::formatTetherBytes), media.capturedAt).joinToString(" · ").ifBlank { "正在读取文件信息…" }, fontSize = 11.sp, color = Color.LightGray)
                if (media.kind == TetherMediaKind.Raw) Text("RAW · 内嵌预览 · 不支持编辑，可传输原图", fontSize = 12.sp, color = Color(0xFFFFB4AB))
                Text(media.originalLabel(), fontSize = 12.sp, color = Color.LightGray)
                (videoError ?: state.preview.error ?: media.error)?.let { Text(it, fontSize = 12.sp, color = Color(0xFFFFB4AB)) }
                if (media.kind == TetherMediaKind.Video && state.preview.videoLocalUri != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TetherButton(if (videoPlaying) "暂停" else "播放", {
                            if (!videoPlaying && videoPosition >= videoDuration && videoDuration > 0) { videoPosition = 0; videoView?.seekTo(0) }
                            videoPlaying = !videoPlaying
                            onAction(TetherUiAction.ToggleVideoPlayback)
                        }, enabled = videoPrepared)
                        Slider(value = (videoPosition.toFloat() / videoDuration.coerceAtLeast(1)).coerceIn(0f, 1f),
                            onValueChange = {
                                videoPosition = (it * videoDuration).toLong()
                                videoView?.seekTo(videoPosition.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                                onAction(TetherUiAction.SeekVideo(videoPosition))
                            }, modifier = Modifier.weight(1f), enabled = videoPrepared && videoDuration > 0)
                        Text("${formatTetherTime(videoPosition)} / ${formatTetherTime(videoDuration)}", fontSize = 11.sp, color = Color.White)
                    }
                }
                TetherButton(
                    if (media.originalStatus == TetherOriginalStatus.Downloaded) "原片已保存" else "下载原片",
                    { onAction(TetherUiAction.Download(setOf(media.handle))) }, Modifier.fillMaxWidth(),
                    enabled = state.gallery.destinationWritable && state.capabilities.canDownload && media.originalStatus != TetherOriginalStatus.Downloading && media.originalStatus != TetherOriginalStatus.Downloaded,
                    primary = true,
                )
            }
        }
    }
}
