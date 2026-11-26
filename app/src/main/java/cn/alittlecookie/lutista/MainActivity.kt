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
import cn.alittlecookie.lutista.ui.screen.MainScreen
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var showTetherDiag by mutableStateOf(false)
    private var tetherViewModel: TetherDiagViewModel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showTetherDiag = isTetherDiagIntent(intent)
        if (showTetherDiag) obtainTetherViewModel()
        handleUsbAttach(intent)
        enableEdgeToEdge()
        setContent {
            if (showTetherDiag) TetherDiagScreen(obtainTetherViewModel()) else MainScreen()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        showTetherDiag = isTetherDiagIntent(intent)
        if (showTetherDiag) obtainTetherViewModel()
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
        val runtime = obtainTetherViewModel().runtime
        // Connect enumerates UsbManager truth; an intent parcel never authorizes opening a device.
        lifecycleScope.launch { runtime.connect() }
    }

    override fun onDestroy() {
        // Configuration changes retain the ViewModel/runtime; a final destroy stops polling and USB.
        if (!isChangingConfigurations) tetherViewModel?.closeUsb()
        super.onDestroy()
    }

    companion object {
        const val ACTION_TETHER_DIAG = "cn.alittlecookie.lutista.action.TETHER_DIAG"
    }
}
