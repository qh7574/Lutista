package cn.alittlecookie.lutista.tether.debug

import cn.alittlecookie.lutista.BuildConfig
import cn.alittlecookie.lutista.manager.SettingsManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

/** Release does not collect diagnostic preferences; disabled debug does not collect fault switches. */
@OptIn(ExperimentalCoroutinesApi::class)
class TetherDebugConfig(private val settings: SettingsManager) {
    data class Snapshot(
        val enabled: Boolean = false,
        val capabilityOverride: CapabilityOverride = CapabilityOverride.None,
        val allowValueWrite: Boolean = false,
        val faultSmallRx: Boolean = false,
        val faultConcurrent: Boolean = false,
        val faultClaimFail: Boolean = false,
    )

    val state: Flow<Snapshot> = if (BuildConfig.DEBUG) {
        settings.tetherDebugEnabled.flatMapLatest { enabled ->
            if (!enabled) flowOf(Snapshot()) else combine(
                settings.tetherDebugCapOverride,
                settings.tetherDebugAllowValueWrite,
                settings.tetherDebugFaultSmallRx,
                settings.tetherDebugFaultConcurrent,
                settings.tetherDebugFaultClaimFail,
            ) { overrideName, allowWrite, smallRx, concurrent, claimFail ->
                Snapshot(true, CapabilityOverride.fromPreference(overrideName), allowWrite, smallRx, concurrent, claimFail)
            }
        }.distinctUntilChanged()
    } else flowOf(Snapshot())

    suspend fun snapshot(): Snapshot = if (BuildConfig.DEBUG) state.first() else Snapshot()
    suspend fun setEnabled(value: Boolean) {
        if (BuildConfig.DEBUG) settings.setTetherDebugEnabled(value)
    }
    suspend fun setOverride(value: CapabilityOverride) {
        if (BuildConfig.DEBUG) settings.setTetherDebugCapOverride(value.name)
    }
    suspend fun setAllowValueWrite(value: Boolean) {
        if (BuildConfig.DEBUG) settings.setTetherDebugAllowValueWrite(value)
    }
    suspend fun setFaultSmallRx(value: Boolean) {
        if (BuildConfig.DEBUG) settings.setTetherDebugFaultSmallRx(value)
    }
    suspend fun setFaultConcurrent(value: Boolean) {
        if (BuildConfig.DEBUG) settings.setTetherDebugFaultConcurrent(value)
    }
    suspend fun setFaultClaimFail(value: Boolean) {
        if (BuildConfig.DEBUG) settings.setTetherDebugFaultClaimFail(value)
    }
}
