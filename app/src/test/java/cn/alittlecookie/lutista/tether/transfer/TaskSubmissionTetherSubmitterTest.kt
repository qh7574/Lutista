package cn.alittlecookie.lutista.tether.transfer

import cn.alittlecookie.lutista.data.database.dao.FilterDao
import cn.alittlecookie.lutista.data.database.dao.WatermarkDao
import cn.alittlecookie.lutista.data.database.entity.FilterEntity
import cn.alittlecookie.lutista.data.database.entity.WatermarkEntity
import cn.alittlecookie.lutista.data.repository.FilterRepository
import cn.alittlecookie.lutista.data.repository.WatermarkRepository
import cn.alittlecookie.lutista.manager.SettingsManager
import cn.alittlecookie.lutista.service.TaskSubmissionManager
import cn.alittlecookie.lutista.tether.objects.CameraObject
import cn.alittlecookie.lutista.tether.objects.CameraObjectSource
import cn.alittlecookie.lutista.tether.objects.CameraSessionKey
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Real adapter and repository resolution, with only the DAO/settings/submission boundary faked. */
class TaskSubmissionTetherSubmitterTest {
    private class Fixture(enabledFilter: Boolean, withWatermark: Boolean) {
        val settings = mockk<SettingsManager>()
        val submissions = mockk<TaskSubmissionManager>()
        val filterDao = mockk<FilterDao>()
        val watermarkDao = mockk<WatermarkDao>()
        val adapter: TaskSubmissionTetherSubmitter

        init {
            every { settings.globalFilterId } returns flowOf(11L)
            every { settings.globalWatermarkId } returns flowOf(if (withWatermark) "22" else "none")
            every { settings.tetherAutoTransfer } returns flowOf(true)
            coEvery { filterDao.getFilterById(11L) } returns FilterEntity(
                id = 11L, name = "Global", exposure = 0.5f, isEnabled = enabledFilter
            )
            if (withWatermark) coEvery { watermarkDao.getWatermarkById(22L) } returns WatermarkEntity(
                id = 22L, name = "Global watermark",
                configJson = """{"baseImage":{"imageUri":"","borderColor":-1},"elements":[{"type":"text","id":"label","content":"Tether","fontSize":24}]}"""
            )
            adapter = TaskSubmissionTetherSubmitter(submissions, settings,
                FilterRepository(filterDao), WatermarkRepository(watermarkDao))
        }
    }

    @Test fun disabledFilterWithoutWatermarkIsNotAnAutomaticEffect() = runBlocking {
        val fixture = Fixture(enabledFilter = false, withWatermark = false)
        assertTrue(fixture.adapter.automaticEnabled())
        assertNull(fixture.adapter.resolveEffects())
        val source = mockk<CameraObjectSource>()
        val session = CameraSessionKey("serial", 1L)
        every { source.sessionKey } returns session
        coEvery { source.getObjectInfo(7L) } returns CameraObject(7, 1, 0x3801, 0, 4, "IMG.JPG", "raw", false)
        val store = mockk<TransferStore>()
        val targets = mockk<TransferTargetStorage>()
        val engine = TransferEngine(this, store, targets, fixture.adapter)
        try {
            engine.startSession(source)
            engine.onObjectAdded(session, 7L)
            engine.awaitIdle()
            coVerify(exactly = 0) { store.insertIfAbsent(any()) }
            coVerify(exactly = 0) { targets.prepare(any(), any(), any()) }
            coVerify(exactly = 0) { fixture.submissions.submitTether(any(), any(), any(), any(), any(), any()) }
        } finally { engine.close() }
        coVerify(exactly = 2) { fixture.filterDao.getFilterById(11L) }
    }

    @Test fun disabledFilterWithWatermarkSubmitsExplicitZeroFilter() = runBlocking {
        val fixture = Fixture(enabledFilter = false, withWatermark = true)
        val effects = fixture.adapter.resolveEffects()!!
        assertEquals(TetherEffects(filterId = 0L, watermarkId = 22L), effects)
        val original = DownloadedTransfer("content://tree/original", "/original/IMG.JPG", 4)
        coEvery { fixture.submissions.submitTether("/original/IMG.JPG", "content://tree/original", "AUTO:key", 0L, 22L, "") } returns 77L
        assertEquals(77L, fixture.adapter.submit("AUTO:key", original, effects))
        coVerify(exactly = 1) { fixture.submissions.submitTether("/original/IMG.JPG", "content://tree/original", "AUTO:key", 0L, 22L, "") }
    }

    @Test fun enabledFilterStillResolvesThroughRealRepositoryAndConfigParser() = runBlocking {
        val fixture = Fixture(enabledFilter = true, withWatermark = false)
        assertEquals(TetherEffects(filterId = 11L, watermarkId = 0L), fixture.adapter.resolveEffects())
    }
}
