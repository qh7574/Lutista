package cn.alittlecookie.lutista.tether.session

/** Process-wide exclusion between formal and diagnostic runtimes; ownership uses identity. */
class TetherConnectionLease internal constructor() {
    private val lock = Any()
    private var holder: Any? = null

    /** The same owner may acquire repeatedly; acquisitions are intentionally not reference-counted. */
    fun tryAcquire(owner: Any): Boolean = synchronized(lock) {
        if (holder == null || holder === owner) { holder = owner; true } else false
    }

    /** An unrelated owner cannot release a live connection. Repeated releases are harmless. */
    fun release(owner: Any) {
        synchronized(lock) { if (holder === owner) holder = null }
    }

    companion object {
        @JvmField val global = TetherConnectionLease()
    }
}
