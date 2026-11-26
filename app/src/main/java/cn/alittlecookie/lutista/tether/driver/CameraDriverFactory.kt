package cn.alittlecookie.lutista.tether.driver

import kotlinx.coroutines.CoroutineScope

/** Each successful create owns a new session; the caller owns its lifetime scope. */
interface CameraDriverFactory {
    fun supports(target: CameraTarget): Boolean
    fun create(target: CameraTarget, sessionScope: CoroutineScope): CameraDriver?
}
