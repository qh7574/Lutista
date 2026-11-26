package cn.alittlecookie.lutista.tether.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TetherStateTest {
    @Test fun `initial state is idle and has no invented connection time`() {
        val state = TetherStateMachine().state.value
        assertEquals(TetherPhase.Idle, state.phase)
        assertEquals(ConnectedMode.Idle, state.mode)
        assertEquals("", state.model)
        assertEquals(null, state.connectionMs)
    }

    @Test fun `permission denial can retry and then connect`() {
        val machine = TetherStateMachine()
        move(machine, TetherPhase.Detecting, TetherPhase.PermissionNeeded, TetherPhase.NotAttached,
            TetherPhase.Detecting, TetherPhase.PermissionNeeded, TetherPhase.Connecting, TetherPhase.Connected)
        assertEquals(TetherPhase.Connected, machine.state.value.phase)
    }

    @Test fun `wrong mode can retry and connected recovery can detach`() {
        val machine = TetherStateMachine()
        move(machine, TetherPhase.Detecting, TetherPhase.Connecting, TetherPhase.WrongMode,
            TetherPhase.Connecting, TetherPhase.Connected, TetherPhase.Recovering,
            TetherPhase.Connected, TetherPhase.Detached, TetherPhase.Detecting)
        assertEquals(TetherPhase.Detecting, machine.state.value.phase)
    }

    @Test fun `illegal transition throws without changing the published state`() {
        val machine = TetherStateMachine()
        move(machine, TetherPhase.Detecting, TetherPhase.Connecting, TetherPhase.Connected)
        val before = machine.state.value
        val failure = runCatching { machine.move(TetherState(TetherPhase.PermissionNeeded)) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(failure!!.message!!.contains("Connected -> PermissionNeeded"))
        assertSame(before, machine.state.value)
    }

    @Test fun `connected submode and timing updates preserve the connected phase`() {
        val machine = TetherStateMachine()
        move(machine, TetherPhase.Detecting, TetherPhase.Connecting, TetherPhase.Connected)
        val next = TetherState(TetherPhase.Connected, "DC-S1", "gallery", 481,
            ConnectedMode.Gallery)
        machine.move(next)
        assertSame(next, machine.state.value)
        machine.move(next.copy(mode = ConnectedMode.LiveView))
        assertEquals(ConnectedMode.LiveView, machine.state.value.mode)
        assertEquals(481L, machine.state.value.connectionMs)
    }

    @Test fun `fatal cannot be cleared by any ordinary state transition`() {
        val machine = TetherStateMachine()
        move(machine, TetherPhase.Detecting, TetherPhase.Fatal)
        val before = machine.state.value
        for (phase in TetherPhase.entries.filter { it != TetherPhase.Fatal }) {
            val failure = runCatching { machine.move(TetherState(phase)) }.exceptionOrNull()
            assertTrue("Fatal -> "+phase+" must be rejected", failure is IllegalStateException)
            assertEquals("Fatal can only be cleared by close/connect", failure!!.message)
            assertSame(before, machine.state.value)
        }
        machine.move(before.copy(message = "still fatal"))
        assertEquals("still fatal", machine.state.value.message)
    }

    @Test fun `explicit close and connect lifecycle resets release fatal`() {
        val machine = TetherStateMachine()
        move(machine, TetherPhase.Detecting, TetherPhase.Fatal)
        machine.move(TetherState(), lifecycleReset = true)
        assertEquals(TetherPhase.Idle, machine.state.value.phase)
        move(machine, TetherPhase.Detecting, TetherPhase.Connecting, TetherPhase.Fatal)
        machine.move(TetherState(TetherPhase.Detecting), lifecycleReset = true)
        move(machine, TetherPhase.Connecting, TetherPhase.Connected)
        assertEquals(TetherPhase.Connected, machine.state.value.phase)
    }

    private fun move(machine: TetherStateMachine, vararg phases: TetherPhase) {
        phases.forEach { machine.move(TetherState(it)) }
    }
}
