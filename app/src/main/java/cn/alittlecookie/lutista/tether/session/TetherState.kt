package cn.alittlecookie.lutista.tether.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The v7 diagram names ten phases (its prose calls these nine states). */
enum class TetherPhase { Idle, Detecting, NotAttached, PermissionNeeded, Connecting, WrongMode, Connected, Recovering, Detached, Fatal }
enum class ConnectedMode { Idle, AutoTransfer, Gallery, LiveView, Capturing, Transferring }
data class TetherState(
    val phase: TetherPhase = TetherPhase.Idle,
    val model: String = "",
    val message: String = "已关闭",
    val connectionMs: Long? = null,
    val mode: ConnectedMode = ConnectedMode.Idle,
)

class TetherStateMachine {
    private val mutable = MutableStateFlow(TetherState())
    val state = mutable.asStateFlow()
    @Synchronized
    fun move(next: TetherState, lifecycleReset: Boolean = false) {
        val before = mutable.value.phase
        if (before == TetherPhase.Fatal && next.phase != TetherPhase.Fatal) {
            check(lifecycleReset) { "Fatal can only be cleared by close/connect" }
        }
        check(next.phase == before || lifecycleReset || next.phase in allowed.getValue(before)) {
            "Illegal tether transition: $before -> "+next.phase
        }
        mutable.value = next
    }
    companion object {
        private val allowed = mapOf(
            TetherPhase.Idle to setOf(TetherPhase.Detecting),
            TetherPhase.Detecting to setOf(TetherPhase.NotAttached, TetherPhase.PermissionNeeded, TetherPhase.Connecting, TetherPhase.Fatal),
            TetherPhase.NotAttached to setOf(TetherPhase.Idle, TetherPhase.Detecting),
            TetherPhase.PermissionNeeded to setOf(TetherPhase.Connecting, TetherPhase.NotAttached, TetherPhase.Fatal),
            TetherPhase.Connecting to setOf(TetherPhase.Connected, TetherPhase.WrongMode, TetherPhase.Fatal, TetherPhase.Detached),
            TetherPhase.WrongMode to setOf(TetherPhase.Connecting, TetherPhase.Idle, TetherPhase.Detecting),
            TetherPhase.Connected to setOf(TetherPhase.Recovering, TetherPhase.Detached, TetherPhase.Fatal, TetherPhase.Idle),
            TetherPhase.Recovering to setOf(TetherPhase.Connected, TetherPhase.Detached, TetherPhase.Fatal),
            TetherPhase.Detached to setOf(TetherPhase.Idle, TetherPhase.Detecting),
            TetherPhase.Fatal to emptySet(),
        )
    }
}
