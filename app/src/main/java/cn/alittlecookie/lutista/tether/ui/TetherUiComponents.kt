package cn.alittlecookie.lutista.tether.ui

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.widget.ImageView
import androidx.appcompat.widget.AppCompatImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

private class BoundTetherImageView(context: Context) : AppCompatImageView(context) {
    var source: TetherImageSource? = null
    var bitmap: Bitmap? = null
    var uri: String? = null
    fun bind(image: TetherImage) {
        if (source !== image.source) {
            source?.detach(this)
            source = image.source
            bitmap = null
            uri = null
            setImageDrawable(null)
            source?.attach(this)
        }
        if (source != null) return
        if (bitmap !== image.bitmap || uri != image.uri) {
            bitmap = image.bitmap
            uri = image.uri
            when {
                image.bitmap != null -> setImageBitmap(image.bitmap)
                image.uri != null -> setImageURI(Uri.parse(image.uri))
                else -> setImageDrawable(null)
            }
        }
    }
    fun releaseBinding() {
        source?.detach(this)
        source = null
        bitmap = null
        uri = null
        setImageDrawable(null)
    }
}

/** Frames can be delivered directly to ImageView; Compose never observes individual live frames. */
@Composable
internal fun TetherImageView(image: TetherImage, description: String, modifier: Modifier = Modifier) {
    AndroidView(
        factory = { context ->
            BoundTetherImageView(context).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                adjustViewBounds = false
                contentDescription = description
            }
        },
        modifier = modifier,
        update = { view -> view.contentDescription = description; view.bind(image) },
        onRelease = { it.releaseBinding() },
    )
}

@Composable
internal fun TetherButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = false,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        colors = ButtonDefaults.buttonColors(
            color = if (primary) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Text(text, fontSize = 13.sp, color = if (primary) Color.White else MiuixTheme.colorScheme.onSurface)
    }
}

@Composable
internal fun TetherNotice(text: String, modifier: Modifier = Modifier, action: String? = null, onAction: () -> Unit = {}) {
    Row(
        modifier.clip(RoundedCornerShape(16.dp)).background(MiuixTheme.colorScheme.secondaryContainer).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text, modifier = Modifier.weight(1f), fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        if (action != null) TetherButton(action, onAction)
    }
}

@Composable
internal fun TetherBusy(label: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        CircularProgressIndicator(size = 28.dp)
        Text(label, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}

@Composable
internal fun TetherConfirmDialog(
    title: String,
    message: String,
    confirm: String = "确认",
    dismiss: String = "取消",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    extra: (@Composable () -> Unit)? = null,
) {
    OverlayDialog(show = true, onDismissRequest = onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(title, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = MiuixTheme.colorScheme.onSurface)
            Text(message, fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            extra?.invoke()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TetherButton(dismiss, onDismiss, Modifier.weight(1f))
                TetherButton(confirm, onConfirm, Modifier.weight(1f), primary = true)
            }
        }
    }
}

internal fun TetherMediaUi.originalLabel(): String = when (originalStatus) {
    TetherOriginalStatus.OnCamera -> "原片在相机"
    TetherOriginalStatus.Downloading -> "正在下载原片 ${((downloadProgress ?: 0f).coerceIn(0f, 1f) * 100).toInt()}%"
    TetherOriginalStatus.Downloaded -> "原片已保存"
    TetherOriginalStatus.Failed -> "原片下载失败"
}
internal fun formatTetherBytes(value: Long): String = when {
    value >= 1024L * 1024 * 1024 -> String.format(java.util.Locale.ROOT, "%.1f GB", value / (1024.0 * 1024 * 1024))
    value >= 1024L * 1024 -> String.format(java.util.Locale.ROOT, "%.1f MB", value / (1024.0 * 1024))
    value >= 1024 -> "${value / 1024} KB"
    else -> "$value B"
}
internal fun formatTetherTime(millis: Long): String {
    val seconds = millis.coerceAtLeast(0) / 1000
    return String.format(java.util.Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60)
}
