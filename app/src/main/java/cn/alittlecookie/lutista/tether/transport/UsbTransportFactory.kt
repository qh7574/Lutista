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
        // 预检失败到最后一档时允许传输层换一条 usbfs 通道（关 fd 重新 openDevice）：
        // 这是 Android 上唯一还能重建主机侧端点状态的动作，见 PtpUsbTransport.reopenConnection。
        val raw = PtpUsbTransport(
            diag = if (logger.enabled) logger else null,
            reopenConnection = { hub.openConnection(target.device) },
        )
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
