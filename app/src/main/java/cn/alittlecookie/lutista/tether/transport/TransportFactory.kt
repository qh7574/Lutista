package cn.alittlecookie.lutista.tether.transport

import cn.alittlecookie.lutista.tether.driver.CameraTarget

interface TransportFactory {
    fun supports(target: CameraTarget): Boolean
    suspend fun create(target: CameraTarget): Transport?
}
