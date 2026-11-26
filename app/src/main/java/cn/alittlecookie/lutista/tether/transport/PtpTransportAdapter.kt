package cn.alittlecookie.lutista.tether.transport

/** Transparent adapter; preserves response, payload references, and exception identity. */
class PtpTransportAdapter(
    private val delegate: PtpTransport,
    val diagnostics: PtpDiagnostics,
) : Transport {
    override suspend fun transaction(
        op: PtpOp,
        expectedData: Boolean,
        dataOut: ByteArray?,
        dataSink: ((ByteArray, Int) -> Unit)?,
        timeoutMs: Int,
    ): PtpResponse = delegate.transaction(op, expectedData, dataOut, dataSink, timeoutMs)

    override suspend fun drainEvents(timeoutMs: Int): List<PtpEvent> =
        delegate.drainEvents(timeoutMs.coerceIn(1, 2))

    override suspend fun close(timeoutMs: Int) = delegate.close(timeoutMs)
}
