package cn.alittlecookie.lutista.tether.ui

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import cn.alittlecookie.lutista.manager.SettingsManager
import cn.alittlecookie.lutista.tether.repository.TetherRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/** UI navigation only; the process repository retains USB and transfers across Activity recreation. */
@Composable
fun TetherRoute(
    onBack: () -> Unit,
    initialTab: String? = null,
    onOpenGlobalFilterSettings: () -> Unit = onBack,
) {
    val repository: TetherRepository = koinInject()
    val settings: SettingsManager = koinInject()
    val state by repository.uiState.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var initialConsumed by rememberSaveable { mutableStateOf(false) }
    var exiting by remember { mutableStateOf(false) }
    val directoryPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
                scope.launch { settings.setOutputPath(uri.toString()) }
            } catch (failure: SecurityException) {
                Toast.makeText(context, "无法保留目录写入授权，请重新选择目录", Toast.LENGTH_LONG).show()
            }
        }
    }

    // Save consumption with the route: rotation must not reset a running page or issue a second Start.
    LaunchedEffect(initialTab, initialConsumed) {
        if (!initialConsumed) {
            initialConsumed = true
            if (initialTab == "liveview") repository.onAction(TetherUiAction.OpenLiveView)
            else if (state.page != TetherPage.LiveView) repository.onAction(TetherUiAction.OpenGallery)
        }
    }

    fun exit(destination: TetherExitDestination, restore: Boolean) {
        if (exiting) return
        exiting = true
        scope.launch {
            try {
                if (repository.leaveLiveView(destination, restore)) {
                    when (destination) {
                        TetherExitDestination.Back -> onBack()
                        TetherExitDestination.Gallery -> repository.onAction(TetherUiAction.OpenGallery)
                        TetherExitDestination.GlobalFilterSettings -> onOpenGlobalFilterSettings()
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } finally {
                exiting = false
            }
        }
    }

    TetherScreen(
        state = state.copy(busy = state.busy || exiting),
        onAction = { action ->
            when (action) {
                TetherUiAction.ChooseDownloadDirectory -> directoryPicker.launch(null)
                TetherUiAction.OpenGlobalFilterSettings -> {
                    if (state.page == TetherPage.LiveView) exit(TetherExitDestination.GlobalFilterSettings, false)
                    else onOpenGlobalFilterSettings()
                }
                is TetherUiAction.ExitLiveView -> exit(action.destination, action.restoreOriginalStyle)
                is TetherUiAction.StopRecordingAndExit -> exit(action.destination, action.restoreOriginalStyle)
                else -> repository.onAction(action)
            }
        },
        onBack = { if (!exiting) onBack() },
    )
}
