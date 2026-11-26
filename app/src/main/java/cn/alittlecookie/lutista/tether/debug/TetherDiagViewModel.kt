package cn.alittlecookie.lutista.tether.debug

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import cn.alittlecookie.lutista.tether.session.TetherRuntime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.koin.java.KoinJavaComponent.get

/** Owns one runtime across configuration changes. Final disposal never uses the Activity scope. */
class TetherDiagViewModel(val runtime: TetherRuntime) : ViewModel() {
    private val mutableCategories = MutableStateFlow(runtime.logger.knownCategories.associateWith { true })
    val categories = mutableCategories.asStateFlow()

    init {
        // The logger is shared; a new owner starts from the same all-enabled state shown by its switches.
        runtime.logger.knownCategories.forEach { runtime.logger.setCategoryEnabled(it, true) }
    }

    fun setCategoryEnabled(category: String, enabled: Boolean) {
        if (category !in runtime.logger.knownCategories) return
        runtime.logger.setCategoryEnabled(category, enabled)
        mutableCategories.value = mutableCategories.value + (category to enabled)
    }

    /** Runtime.dispose is idempotent and closes USB before cancelling its own scope. */
    fun closeUsb() = runtime.dispose()

    override fun onCleared() {
        closeUsb()
        super.onCleared()
    }

    object Factory : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(TetherDiagViewModel::class.java))
            return TetherDiagViewModel(get<TetherRuntime>(TetherRuntime::class.java)) as T
        }
    }

    companion object {
        // Reserved for a later foreground-service delivery; no channel/notification is created here.
        const val TETHER_NOTIFICATION_ID = 1002
        const val TETHER_CHANNEL_ID = "tether_channel"
    }
}
