package cn.alittlecookie.lutista.tether.transport

/** Connection-independent PTP I/O. The factory owns opening the physical connection. */
interface Transport {
    suspend fun transaction(
        op: PtpOp,
        expectedData: Boolean = false,
        dataOut: ByteArray? = null,
        dataSink: ((ByteArray, Int) -> Unit)? = null,
        timeoutMs: Int = 4_000,
    ): PtpResponse
    suspend fun drainEvents(timeoutMs: Int = 2): List<PtpEvent>
    suspend fun close(timeoutMs: Int = 1_000)
}
