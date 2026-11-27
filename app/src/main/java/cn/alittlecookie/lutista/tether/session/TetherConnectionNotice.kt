package cn.alittlecookie.lutista.tether.session

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice

/** User-confirmed recovery instructions; no property read or USB claim is required. */
enum class TetherConnectionNotice(val title: String, val message: String, val source: String) {
    NoUsableUsb(
        "未找到可用的 USB 相机",
        "请检查相机电源和 USB 数据线。若相机已进入 MTP 模式，请拔下 USB 连接线，按相机提示选择 PC(Tether) 后重新插入；部分相机需在重新插线时选择连接模式。",
        "usb_descriptor_no_usable_target",
    ),
    MtpUsbMode(
        "相机处于 MTP 模式",
        "当前 USB 描述符对应已实测的 DC-S1 MTP 模式。不能在当前连接上直接重试：请拔下 USB 连接线，按相机提示选择 PC(Tether) 后重新插入；若模式菜单在插线时出现，请在该菜单选择 PC(Tether)。",
        "usb_descriptor_04da_2372",
    ),
    WrongPtpMode(
        "相机连接模式不受支持",
        "相机返回的设备信息不支持当前联机模式。请拔下 USB 连接线，按相机提示选择 PC(Tether) 后重新插入，不能在当前连接上直接重试。",
        "parsed_device_info",
    ),
}

/** Exact descriptor observed on DC-S1 fw 2.4. Unknown PIDs and protocol timeouts are not MTP. */
internal fun UsbDevice.isVerifiedLumixMtpMode(): Boolean = vendorId == 0x04DA && productId == 0x2372

/** Match PtpUsbTransport: only interface zero is supported, with both bulk directions. */
internal fun UsbDevice.hasTetherBulkInterface(): Boolean {
    if (interfaceCount == 0) return false
    val intf = getInterface(0)
    return intf.interfaceClass == UsbConstants.USB_CLASS_STILL_IMAGE &&
        intf.interfaceSubclass == 1 && intf.interfaceProtocol == 1 &&
        (0 until intf.endpointCount).any { ep ->
            intf.getEndpoint(ep).let { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.direction == UsbConstants.USB_DIR_IN }
        } &&
        (0 until intf.endpointCount).any { ep ->
            intf.getEndpoint(ep).let { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.direction == UsbConstants.USB_DIR_OUT }
        }
}
