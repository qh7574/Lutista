package cn.alittlecookie.lutista

import android.content.Intent
import android.hardware.usb.UsbManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import cn.alittlecookie.lutista.tether.debug.TetherDiagScreen
import cn.alittlecookie.lutista.tether.debug.TetherDiagViewModel
import cn.alittlecookie.lutista.tether.repository.TetherRepository
import org.koin.java.KoinJavaComponent.get
import cn.alittlecookie.lutista.ui.screen.MainScreen
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var showTetherDiag by mutableStateOf(false)
    private var tetherViewModel: TetherDiagViewModel? = null
    private val tetherRepository: TetherRepository by lazy { get(TetherRepository::class.java) }
    private var openTetherRequestId by mutableStateOf(0L)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showTetherDiag = isTetherDiagIntent(intent)
        if (showTetherDiag) obtainTetherViewModel()
        handleUsbAttach(intent)
        openTetherRequestId = savedInstanceState?.getLong(STATE_TETHER_OPEN_REQUEST) ?: 0L
        if (savedInstanceState == null && intent?.action == ACTION_TETHER_OPEN) {
            openTetherRequestId = System.nanoTime()
        }
        enableEdgeToEdge()
        setContent {
            if (showTetherDiag) TetherDiagScreen(obtainTetherViewModel()) else MainScreen(openTetherRequestId = openTetherRequestId)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            val enteringDiagnostics = isTetherDiagIntent(intent)
            if (showTetherDiag && !enteringDiagnostics) {
                lifecycleScope.launch {
                    tetherViewModel?.runtime?.disconnect()
                    showTetherDiag = false
                }
            } else {
                showTetherDiag = enteringDiagnostics
                if (showTetherDiag) obtainTetherViewModel()
            }
        }
        if (intent.action == ACTION_TETHER_OPEN) openTetherRequestId = System.nanoTime()
        handleUsbAttach(intent)
    }

    private fun isTetherDiagIntent(intent: Intent?): Boolean =
        BuildConfig.DEBUG && intent?.action == ACTION_TETHER_DIAG

    private fun obtainTetherViewModel(): TetherDiagViewModel = tetherViewModel ?: run {
        ViewModelProvider(this, TetherDiagViewModel.Factory)[TetherDiagViewModel::class.java]
            .also { tetherViewModel = it }
    }

    private fun handleUsbAttach(intent: Intent?) {
        if (intent?.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return
        // A descriptor broadcast never enables tether mode or creates a diagnostic runtime.
        if (showTetherDiag) lifecycleScope.launch { obtainTetherViewModel().runtime.connect() }
        else tetherRepository.onUsbAttached()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putLong(STATE_TETHER_OPEN_REQUEST, openTetherRequestId)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        // Configuration changes retain the ViewModel/runtime; a final destroy stops polling and USB.
        if (!isChangingConfigurations) tetherViewModel?.closeUsb()
        super.onDestroy()
    }

    companion object {
        private const val STATE_TETHER_OPEN_REQUEST = "tether_open_request"
        const val ACTION_TETHER_OPEN = "cn.alittlecookie.lutista.action.TETHER_OPEN"
        const val ACTION_TETHER_DIAG = "cn.alittlecookie.lutista.action.TETHER_DIAG"
    }
}
