package cn.alittlecookie.lutista.tether.transport

import cn.alittlecookie.lutista.tether.debug.PtpDiagTransport
import cn.alittlecookie.lutista.tether.debug.TetherDiagLogger
import cn.alittlecookie.lutista.tether.driver.CameraTarget
import cn.alittlecookie.lutista.tether.driver.UsbTarget
import cn.alittlecookie.lutista.tether.session.UsbHub

/** Opens a new physical transport for every connection, on the scheduler owner thread. */
class UsbTransportFactory(
    private val hub: UsbHub,
    private val logger: TetherDiagLogger,
) : TransportFactory {
    override fun supports(target: CameraTarget): Boolean = target is UsbTarget

    override suspend fun create(target: CameraTarget): Transport? {
        UsbIoThread.requireIoThread("UsbTransportFactory.create")
        if (target !is UsbTarget || !target.permissionGranted) return null
        val connection = hub.openConnection(target.device) ?: return null
        val raw = PtpUsbTransport(diag = if (logger.enabled) logger else null)
        val diagnostics = UsbPtpDiagnostics(raw)
        val traced: PtpTransport = PtpDiagTransport(raw, diagnostics, logger)
        try {
            traced.open(target.device, connection)
            return PtpTransportAdapter(traced, diagnostics)
        } catch (failure: Throwable) {
            // open() can fail before the transport assumes ownership of this handle.
            try { raw.close() } catch (closeFailure: Throwable) { failure.addSuppressed(closeFailure) }
            try { connection.close() } catch (closeFailure: Throwable) { failure.addSuppressed(closeFailure) }
            throw failure
        }
    }
}
