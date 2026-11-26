package cn.alittlecookie.lutista.tether.debug

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.alittlecookie.lutista.BuildConfig
import cn.alittlecookie.lutista.tether.profile.Feature
import cn.alittlecookie.lutista.tether.transport.PtpDeviceCaps
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val VISIBLE_LOG_LINES = 200

/** Explicit debug action only. No capture/live-view command is exposed by this screen. */
@Composable
fun TetherDiagScreen(viewModel: TetherDiagViewModel) {
    if (!BuildConfig.DEBUG) return
    val runtime = viewModel.runtime
    val scope = rememberCoroutineScope()
    val state by runtime.state.collectAsStateWithLifecycle()
    val caps by runtime.caps.collectAsStateWithLifecycle()
    val deviceCaps by runtime.deviceCaps.collectAsStateWithLifecycle()
    val stats by runtime.stats.collectAsStateWithLifecycle()
    val config by runtime.configState.collectAsStateWithLifecycle()
    val message by runtime.message.collectAsStateWithLifecycle()
    val logLines by runtime.logLines.collectAsStateWithLifecycle()
    val transportOpen by runtime.transportOpen.collectAsStateWithLifecycle()
    val transportFatal by runtime.transportFatal.collectAsStateWithLifecycle()
    val devices by runtime.hub.deviceFlow.collectAsStateWithLifecycle()
    val permissionGranted by runtime.hub.permissionGranted.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val violationCount by produceState(runtime.violationCount, runtime) {
        while (isActive) {
            value = runtime.violationCount
            delay(250)
        }
    }
    val descriptors = remember(devices) { descriptorText(devices) }
    val visibleLogs = remember(logLines) { logLines.takeLast(VISIBLE_LOG_LINES) }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().safeDrawingPadding(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item {
                    DiagnosticSection("Tether T02 诊断（DEBUG）") {
                        Text("仅手动诊断；原始结果见下方消息与日志。")
                        DiagnosticText("state: " + state.phase + "; mode: " + state.mode +
                            "\nmodel: " + state.model + "\nstate.message: " + state.message +
                            "\ntransportOpen: " + transportOpen.displayTransportState() +
                            "\nisFatal (transport): " + transportFatal.displayTransportState() +
                            "\nviolationCount: " + violationCount +
                            "\nconnectionMs: " + state.connectionMs +
                            "\npermissionGranted: " + permissionGranted)
                        Text("message（实际返回）：" + message)
                        DiagnosticAction("Connect") { scope.launch { runtime.connect() } }
                        DiagnosticAction("Close USB / 停止轮询") { scope.launch { runtime.disconnect() } }
                        DiagnosticAction("Reprobe") { scope.launch { runtime.reprobe() } }
                        DiagnosticAction("Read-only evidence", config.enabled) { scope.launch { runtime.runEvidence() } }
                        DiagnosticAction(
                            "手动读取 → 同值写回 → 回读",
                            BuildConfig.DEBUG && config.enabled && config.allowValueWrite &&
                                config.capabilityOverride != CapabilityOverride.UnknownPtp,
                        ) { scope.launch { runtime.writeBackSameValue() } }
                    }
                }
                item {
                    DiagnosticSection("配置（实际 TetherDebugConfig）") {
                        DiagnosticToggle("enabled / 诊断日志", config.enabled) {
                            scope.launch { runtime.config.setEnabled(it) }
                        }
                        DiagnosticToggle("allowValueWrite / 手动同值写许可", config.allowValueWrite, config.enabled) {
                            scope.launch { runtime.config.setAllowValueWrite(it) }
                        }
                        DiagnosticToggle("faultSmallRx", config.faultSmallRx, config.enabled) {
                            scope.launch { runtime.config.setFaultSmallRx(it) }
                        }
                        DiagnosticToggle("faultConcurrent", config.faultConcurrent, config.enabled) {
                            scope.launch { runtime.config.setFaultConcurrent(it) }
                        }
                        DiagnosticToggle("faultClaimFail", config.faultClaimFail, config.enabled) {
                            scope.launch { runtime.config.setFaultClaimFail(it) }
                        }
                        Text("CapabilityOverride（切换后可手动 Reprobe）")
                        CapabilityOverride.entries.forEach { override ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(
                                    selected = config.capabilityOverride == override,
                                    enabled = config.enabled,
                                    onClick = { scope.launch { runtime.config.setOverride(override) } },
                                )
                                Text(override.name)
                            }
                        }
                    }
                }
                item {
                    DiagnosticSection("Logger 类别（8 类）") {
                        runtime.logger.knownCategories.sorted().forEach { category ->
                            DiagnosticToggle(category, categories[category] ?: true, config.enabled) {
                                viewModel.setCategoryEnabled(category, it)
                            }
                        }
                    }
                }
                item {
                    DiagnosticSection("Fault 实验") {
                        Text("仅显示 runtime.message 中实际 TargetObserved / Blocked 等结果。")
                        DiagnosticAction("smallRx", config.enabled && config.faultSmallRx) {
                            scope.launch { runtime.runFault("smallRx") }
                        }
                        DiagnosticAction("concurrent", config.enabled && config.faultConcurrent) {
                            scope.launch { runtime.runFault("concurrent") }
                        }
                        DiagnosticAction("claim", config.enabled && config.faultClaimFail) {
                            scope.launch { runtime.runFault("claim") }
                        }
                        DiagnosticAction("timeout（会关闭当前会话）", config.enabled) {
                            scope.launch { runtime.runFault("timeout") }
                        }
                    }
                }
                item {
                    DiagnosticSection("PtpDeviceCaps · source=open_return") {
                        DiagnosticText(deviceCaps?.let(::deviceCapsText) ?: "尚无 open 返回值")
                    }
                }
                item {
                    DiagnosticSection("USB 三端点 · descriptor_only") {
                        Text("仅枚举 USB 描述符；这里未 claim，不代表 T01 实际选中的端点。")
                        DiagnosticText(descriptors)
                    }
                }
                item {
                    DiagnosticSection("CameraCapabilities") {
                        DiagnosticText("model=" + caps.model + "; serial=" + caps.serial +
                            "; firmware=" + caps.firmware + "\nprofileId=" + caps.profileId +
                            "; usableShape=" + caps.usableShape + "\nprobedAt=" + caps.probedAt +
                            "; totalProbeMs=" + caps.totalProbeMs + "; probedModePos=" + caps.probedModePos)
                    }
                }
                items(Feature.entries) { feature ->
                    val capability = caps[feature]
                    DiagnosticText(feature.name + "（" + feature.uiName + "）\n" +
                        capability.availability + "; evidence=" + capability.evidence +
                        "; writable=" + capability.writable + "; detail=" + capability.detail)
                }
                item {
                    DiagnosticSection("事件计数 · EventPump.Stats") {
                        DiagnosticText("received=" + stats.received + "; objectAdded=" + stats.objectAdded +
                            "; processedHandles=" + stats.processedHandles + "\nduplicates=" + stats.duplicates +
                            "; replayed=" + stats.replayed + "; noise=" + stats.noise + "; unknown=" + stats.unknown +
                            "\nmalformed=" + stats.malformed + "; discardedEventContainers=" + stats.discardedEventContainers +
                            "; backpressure=" + stats.backpressure + "; unobserved=" + stats.unobserved)
                    }
                }
                item {
                    DiagnosticSection("日志（最近 " + VISIBLE_LOG_LINES + " 行，可滚动）") {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth().height(280.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            items(visibleLogs) { line -> DiagnosticText(line) }
                            if (visibleLogs.isEmpty()) item { Text("暂无日志") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagnosticSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider()
        Text(title, style = MaterialTheme.typography.titleMedium)
        content()
    }
}

@Composable
private fun DiagnosticText(text: String) {
    SelectionContainer {
        Text(text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun DiagnosticAction(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text(label) }
}

@Composable
private fun DiagnosticToggle(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f).padding(end = 12.dp))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

private fun Boolean?.displayTransportState(): String = this?.toString() ?: "未持有传输（null）"

private fun hex(value: Long): String = "0x" + value.toString(16).uppercase()
private fun hexSet(values: Set<Long>): String = values.sorted().joinToString(prefix = "[", postfix = "]") { hex(it) }

private fun deviceCapsText(caps: PtpDeviceCaps): String = buildString {
    appendLine("vendorExtensionId=" + hex(caps.vendorExtensionId))
    appendLine("manufacturer=" + caps.manufacturer)
    appendLine("model=" + caps.model)
    appendLine("deviceVersion=" + caps.deviceVersion)
    appendLine("serialNumber=" + caps.serialNumber)
    appendLine("operationsSupported=" + hexSet(caps.operationsSupported))
    appendLine("eventsSupported=" + hexSet(caps.eventsSupported))
    appendLine("devicePropertiesSupported=" + hexSet(caps.devicePropertiesSupported))
    appendLine("imageFormats=" + hexSet(caps.imageFormats))
    appendLine("bcdUsb=" + hex(caps.bcdUsb.toLong()))
    appendLine("bulkMaxPacketSize=" + caps.bulkMaxPacketSize)
    appendLine("usbVendorId=" + caps.usbVendorId + " (" + hex(caps.usbVendorId.toLong()) + ")")
    appendLine("usbProductId=" + caps.usbProductId + " (" + hex(caps.usbProductId.toLong()) + ")")
    append("preflightFailure=" + caps.preflightFailure)
}

private fun descriptorText(devices: List<UsbDevice>): String = buildString {
    if (devices.isEmpty()) append("descriptor_only: 无当前 USB 设备")
    for (device in devices) {
        appendLine("descriptor_only device=" + device.deviceName + "; vid=" + device.vendorId + "; pid=" + device.productId)
        for (index in 0 until device.interfaceCount) {
            val intf = device.getInterface(index)
            appendLine("descriptor_only interface=" + intf.id + "; alt=" + intf.alternateSetting +
                "; class=" + intf.interfaceClass + "; subclass=" + intf.interfaceSubclass + "; protocol=" + intf.interfaceProtocol)
            appendLine("  bulk-in=" + endpointText(intf, UsbConstants.USB_ENDPOINT_XFER_BULK, UsbConstants.USB_DIR_IN))
            appendLine("  bulk-out=" + endpointText(intf, UsbConstants.USB_ENDPOINT_XFER_BULK, UsbConstants.USB_DIR_OUT))
            appendLine("  interrupt-in=" + endpointText(intf, UsbConstants.USB_ENDPOINT_XFER_INT, UsbConstants.USB_DIR_IN))
        }
    }
}

private fun endpointText(intf: UsbInterface, type: Int, direction: Int): String {
    val endpoints = (0 until intf.endpointCount).map(intf::getEndpoint)
        .filter { it.type == type && it.direction == direction }
    return endpoints.joinToString { endpoint: UsbEndpoint ->
        "address=" + hex(endpoint.address.toLong()) + "; maxPacket=" + endpoint.maxPacketSize + "; interval=" + endpoint.interval
    }.ifEmpty { "未发现（descriptor_only）" }
}
