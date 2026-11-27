package cn.alittlecookie.lutista.tether.objects

import java.security.MessageDigest

object CameraObjectKeys {
    /** Persistent identity: camera clock stays raw; session-local handles are deliberately excluded. */
    fun transferDedupeKey(serial: String, name: String, size: Long, dateTakenRaw: String): String {
        require(serial.isNotBlank())
        require(size >= 0)
        val digest = MessageDigest.getInstance("SHA-256")
        listOf(serial, name, size.toString(), dateTakenRaw).forEach { field ->
            val bytes = field.toByteArray(Charsets.UTF_8)
            digest.update(byteArrayOf(
                (bytes.size ushr 24).toByte(), (bytes.size ushr 16).toByte(),
                (bytes.size ushr 8).toByte(), bytes.size.toByte(),
            ))
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    fun transferDedupeKey(serial: String, info: CameraObject): String =
        transferDedupeKey(serial, info.name, info.size, info.dateTakenRaw)
}
