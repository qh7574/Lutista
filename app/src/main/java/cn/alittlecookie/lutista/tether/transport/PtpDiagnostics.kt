package cn.alittlecookie.lutista.tether.transport

/** Read-only access to transport truth. Event collection still requires the owner I/O thread. */
interface PtpDiagnostics {
    val isOpen: Boolean
    val isFatal: Boolean
    val deviceCaps: PtpDeviceCaps?
    val discardedEventContainers: Int
    fun collectPendingEvents(): List<PtpEvent>
}

class UsbPtpDiagnostics(private val delegate: PtpUsbTransport) : PtpDiagnostics {
    override val isOpen get() = delegate.isOpen
    override val isFatal get() = delegate.isFatal
    override val deviceCaps get() = delegate.deviceCaps
    override val discardedEventContainers get() = delegate.discardedEventContainers
    override fun collectPendingEvents() = delegate.collectPendingEvents()
}
