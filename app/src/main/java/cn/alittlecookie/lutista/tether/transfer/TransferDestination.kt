package cn.alittlecookie.lutista.tether.transfer

/** Every camera original feeds Home's monitor folder; processed effects keep their own output. */
internal object TransferDestination {
    fun resolve(monitorPath: String): String {
        check(monitorPath.isNotBlank()) { "请先在主页面设置监控文件夹，再下载或接收相机原片" }
        return monitorPath
    }
}
