package cn.alittlecookie.lutista.tether.render

import cn.alittlecookie.lutista.data.database.entity.FilterEntity
import cn.alittlecookie.lutista.data.model.FilterConfig
import cn.alittlecookie.lutista.data.model.GrainConfig
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class LiveViewFilterSourceTest {
    @Test fun `no-op and grain-only are empty and effective color disables grain`() {
        assertTrue(LiveViewFilterSource.previewChain(null).isEmpty)
        assertTrue(LiveViewFilterSource.previewChain(FilterConfig()).isEmpty)
        assertTrue(LiveViewFilterSource.previewChain(FilterConfig(grainConfig = GrainConfig(globalStrength = 1f))).isEmpty)
        assertTrue(LiveViewFilterSource.previewChain(FilterConfig(lutPath1 = "unused.cube", lutConcentration1 = 0f)).isEmpty)
        val chain = LiveViewFilterSource.previewChain(FilterConfig(id = 7, exposure = 10f,
            grainConfig = GrainConfig(globalStrength = 1f)))
        assertEquals(1, chain.gpuPassCount)
        assertEquals(7L, chain.color!!.filterId)
        assertEquals(0f, chain.color!!.filter.grainConfig.globalStrength, 0f)
        assertTrue(chain.color!!.disabledFields.contains("grain"))
    }

    @Test fun `global selection tracks same-id edits disable delete and missing selection`() = runBlocking {
        withTimeout(5_000) {
            val original = FilterEntity(id = 7, name = "Global", exposure = 10f)
            val ids = MutableStateFlow(7L)
            val filters = MutableStateFlow(listOf(original))
            val enabled = MutableStateFlow(false)
            val source = LiveViewFilterSource(ids, filters)
            val values = Channel<PreviewFilterChain>(Channel.UNLIMITED)
            val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                source.chain(enabled).collect { values.send(it) }
            }
            try {
                assertTrue(values.receive().isEmpty)
                enabled.value = true
                assertEquals(10f, values.receive().color!!.filter.exposure, 0f)
                filters.value = listOf(original.copy(exposure = 20f))
                assertEquals(20f, values.receive().color!!.filter.exposure, 0f)
                filters.value = listOf(original.copy(isEnabled = false))
                assertTrue(values.receive().isEmpty)
                filters.value = listOf(original)
                assertNotNull(values.receive().color)
                filters.value = emptyList()
                assertTrue(values.receive().isEmpty)
                ids.value = 999
                assertTrue(source.chain(true).first().isEmpty)
                ids.value = 0
                assertTrue(source.chain(true).first().isEmpty)
            } finally { collector.cancelAndJoin(); values.cancel() }
        }
    }
}
