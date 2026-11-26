package cn.alittlecookie.lutista.tether.debug

import cn.alittlecookie.lutista.BuildConfig
import cn.alittlecookie.lutista.manager.SettingsManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class TetherDebugConfigTest {
    @Test fun disabledConfigDoesNotEvenReadSwitchFlowsAndReturnsSafeDefaults() = runBlocking {
        val settings = mockk<SettingsManager>()
        every { settings.tetherDebugEnabled } returns flowOf(false)
        val config = TetherDebugConfig(settings)
        assertEquals(TetherDebugConfig.Snapshot(), config.snapshot())
        verify(exactly = 0) { settings.tetherDebugCapOverride }
        verify(exactly = 0) { settings.tetherDebugAllowValueWrite }
        verify(exactly = 0) { settings.tetherDebugFaultSmallRx }
        verify(exactly = 0) { settings.tetherDebugFaultConcurrent }
        verify(exactly = 0) { settings.tetherDebugFaultClaimFail }
    }

    @Test fun snapshotsFollowStoredFlowsAndMasterDisableResetsEveryEffectiveSwitch() = runBlocking {
        val settings = mockk<SettingsManager>()
        val enabled = MutableStateFlow(true)
        val overrideName = MutableStateFlow("UnknownPtp")
        val allow = MutableStateFlow(true)
        val small = MutableStateFlow(true)
        val concurrent = MutableStateFlow(true)
        val claim = MutableStateFlow(true)
        every { settings.tetherDebugEnabled } returns enabled
        every { settings.tetherDebugCapOverride } returns overrideName
        every { settings.tetherDebugAllowValueWrite } returns allow
        every { settings.tetherDebugFaultSmallRx } returns small
        every { settings.tetherDebugFaultConcurrent } returns concurrent
        every { settings.tetherDebugFaultClaimFail } returns claim
        val config = TetherDebugConfig(settings)
        val allOn = config.snapshot()
        assertEquals(if (BuildConfig.DEBUG) TetherDebugConfig.Snapshot(true, CapabilityOverride.UnknownPtp, true, true, true, true) else TetherDebugConfig.Snapshot(), allOn)
        enabled.value = false
        assertEquals(TetherDebugConfig.Snapshot(), config.snapshot())
        assertTrue(allow.value) // disabling affects effective state, not saved preferences.
        assertEquals("UnknownPtp", overrideName.value)
        enabled.value = true
        overrideName.value = "corrupt stored override"
        small.value = false
        val updated = config.snapshot()
        assertEquals(CapabilityOverride.None, updated.capabilityOverride)
        assertFalse(updated.faultSmallRx)
        assertEquals(BuildConfig.DEBUG, updated.faultConcurrent)
        assertEquals(BuildConfig.DEBUG, updated.allowValueWrite)
    }

    @Test fun settersPersistOnlyTheirExactTetherSettingsAndAreReleaseGated() = runBlocking {
        val settings = mockk<SettingsManager>()
        every { settings.tetherDebugEnabled } returns flowOf(false)
        coEvery { settings.setTetherDebugEnabled(any()) } just runs
        coEvery { settings.setTetherDebugCapOverride(any()) } just runs
        coEvery { settings.setTetherDebugAllowValueWrite(any()) } just runs
        coEvery { settings.setTetherDebugFaultSmallRx(any()) } just runs
        coEvery { settings.setTetherDebugFaultConcurrent(any()) } just runs
        coEvery { settings.setTetherDebugFaultClaimFail(any()) } just runs
        val config = TetherDebugConfig(settings)
        config.setEnabled(true)
        config.setOverride(CapabilityOverride.NoLiveView)
        config.setAllowValueWrite(true)
        config.setFaultSmallRx(true)
        config.setFaultConcurrent(true)
        config.setFaultClaimFail(true)
        val calls = if (BuildConfig.DEBUG) 1 else 0
        coVerify(exactly = calls) { settings.setTetherDebugEnabled(true) }
        coVerify(exactly = calls) { settings.setTetherDebugCapOverride("NoLiveView") }
        coVerify(exactly = calls) { settings.setTetherDebugAllowValueWrite(true) }
        coVerify(exactly = calls) { settings.setTetherDebugFaultSmallRx(true) }
        coVerify(exactly = calls) { settings.setTetherDebugFaultConcurrent(true) }
        coVerify(exactly = calls) { settings.setTetherDebugFaultClaimFail(true) }
        assertEquals(TetherDebugConfig.Snapshot(), config.snapshot())
    }
}
