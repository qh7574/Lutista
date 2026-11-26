package cn.alittlecookie.lutista.tether.driver

import cn.alittlecookie.lutista.tether.debug.TetherDebugConfig
import cn.alittlecookie.lutista.tether.debug.TetherDiagLogger
import cn.alittlecookie.lutista.tether.profile.CameraProfileRegistry
import cn.alittlecookie.lutista.tether.session.CameraSession
import cn.alittlecookie.lutista.tether.session.PtpScheduler
import cn.alittlecookie.lutista.tether.store.CapabilityCache
import cn.alittlecookie.lutista.tether.transport.TransportFactory
import kotlinx.coroutines.CoroutineScope

/** Assembles one owner per driver. Raw USB handles remain inside TransportFactory. */
class PtpUsbDriverFactory(
    private val transportFactory: TransportFactory,
    private val registry: CameraProfileRegistry,
    private val cache: CapabilityCache,
    private val config: TetherDebugConfig,
    private val logger: TetherDiagLogger,
    private val requestPermission: suspend (UsbTarget) -> Boolean,
) : CameraDriverFactory {
    override fun supports(target: CameraTarget): Boolean =
        target is UsbTarget && transportFactory.supports(target)

    override fun create(target: CameraTarget, sessionScope: CoroutineScope): CameraDriver? {
        val usbTarget = target as? UsbTarget ?: return null
        if (!transportFactory.supports(usbTarget)) return null
        val session = CameraSession(
            factory = transportFactory,
            scheduler = PtpScheduler(),
            logger = logger,
            scope = sessionScope,
            discover = { usbTarget },
            requestPermission = requestPermission,
        )
        return PtpUsbDriver(session, sessionScope, registry, cache, config, logger)
    }
}
