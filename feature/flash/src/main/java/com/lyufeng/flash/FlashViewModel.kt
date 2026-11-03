package com.lyufeng.flash

import android.app.Application
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class FlashViewModel(app: Application) : AndroidViewModel(app) {
    private val usb = app.getSystemService(UsbManager::class.java)
    var probes by mutableStateOf<List<Probe>>(emptyList()); private set
    var targets by mutableStateOf<List<TargetProfile>>(emptyList()); private set
    var probeKind by mutableStateOf(ProbeKind.ST_LINK)
    var probe by mutableStateOf<Probe?>(null)
    var wire by mutableStateOf(Wire.SWD)
    var target by mutableStateOf<TargetProfile?>(null)
    var project by mutableStateOf<ImportedProject?>(null); private set
    var firmware by mutableStateOf<File?>(null)
    var plan by mutableStateOf<BuildPlan?>(null)
    var sourceMode by mutableStateOf(false)
    var address by mutableStateOf("0x08000000")
    var speed by mutableStateOf("1000")
    var underReset by mutableStateOf(false)
    var resetAfter by mutableStateOf(true)
    var importing by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null)
    var permissionPending by mutableStateOf(false); private set
    var cpu by mutableStateOf("cortex-m3")
    var defines by mutableStateOf("")
    var linker by mutableStateOf<File?>(null)
    private var pending: Pair<String, FlashRequest>? = null
    private val permissionAction = "${app.packageName}.FLASH_USB_PERMISSION"
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED, UsbManager.ACTION_USB_DEVICE_DETACHED -> refresh()
                permissionAction -> {
                    val request = pending ?: return
                    if (intent.data?.lastPathSegment != request.first) return
                    pending = null; permissionPending = false
                    val device = usb.deviceList[request.second.options.probe.key]
                    if (device != null && usb.hasPermission(device)) FlashCoordinator.start(context, request.second)
                    else error = "未获得烧录器 USB 权限，或设备已经断开"
                }
            }
        }
    }
    init {
        ContextCompat.registerReceiver(app, receiver, IntentFilter().apply {
            addAction(permissionAction); addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED); addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        refresh()
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { FlashAssets(app).targets() } }
                .onSuccess { targets = it; target = it.firstOrNull { t -> t.id == "stm32f1x" } }
                .onFailure { error = "芯片目录加载失败：${it.message}" }
        }
    }
    fun refresh() {
        pending?.let { if (usb.deviceList[it.second.options.probe.key] == null) { pending = null; permissionPending = false; error = "烧录器已断开" } }
        probes = usb.deviceList.values.mapNotNull { d ->
            val product = runCatching { d.productName }.getOrNull()
            val dapInterface = dapLinkInterface(d, product)
            val kind = when {
                dapInterface != null -> ProbeKind.DAP_LINK
                d.vendorId == 0x1366 -> ProbeKind.J_LINK
                d.vendorId == 0x0483 && d.productId in setOf(0x3744,0x3748,0x374b,0x374d,0x374e,0x374f,0x3752,0x3753,0x3754) -> ProbeKind.ST_LINK
                else -> null
            } ?: return@mapNotNull null
            val label = "${product ?: kind.label} · ${d.deviceName.substringAfterLast('/')} · %04X:%04X".format(d.vendorId, d.productId)
            Probe(d.deviceName, label, kind, d.productId, d.vendorId, dapInterface?.id)
        }.sortedBy { it.key }
        probe = probes.firstOrNull { it.key == probe?.key } ?: probes.firstOrNull { it.kind == probeKind }
    }
    private fun dapLinkInterface(device: UsbDevice, product: String?): UsbInterface? {
        fun matchesName(name: String?) = name != null &&
            (name.contains("CMSIS-DAP", ignoreCase = true) || name.contains("DAPLink", ignoreCase = true))

        val candidates = (0 until device.interfaceCount).map(device::getInterface).filter { intf ->
            if (intf.alternateSetting != 0 || intf.interfaceClass != UsbConstants.USB_CLASS_VENDOR_SPEC ||
                intf.endpointCount < 2) return@filter false
            val out = intf.getEndpoint(0)
            val input = intf.getEndpoint(1)
            out.type == UsbConstants.USB_ENDPOINT_XFER_BULK && out.direction == UsbConstants.USB_DIR_OUT &&
                input.type == UsbConstants.USB_ENDPOINT_XFER_BULK && input.direction == UsbConstants.USB_DIR_IN
        }
        candidates.firstOrNull { matchesName(it.name) }?.let { return it }
        val knownId = device.vendorId == ProbeKind.DAP_LINK.vid && device.productId == 0x0204
        return if (knownId || matchesName(product)) candidates.singleOrNull() else null
    }
    fun selectKind(kind: ProbeKind) {
        probeKind = kind; probe = probes.firstOrNull { it.kind == kind }
        if (target?.accepts(kind) == false) target = targets.firstOrNull { it.id == "stm32f1x" }
    }
    fun selectTarget(value: TargetProfile) {
        target = value
        if (wire !in value.wires) wire = value.wires.first()
        address = value.defaultAddress
    }
    fun import(uri: Uri, directory: Boolean) {
        if (importing || FlashCoordinator.state.value.busy) return
        importing = true; error = null
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { ProjectImporter(getApplication()).import(uri, directory) } }
                .onSuccess { value ->
                    val old = project
                    project = value; firmware = value.firmware.singleOrNull(); plan = value.buildPlans.firstOrNull()
                    sourceMode = value.firmware.isEmpty() && value.sources.isNotEmpty()
                    linker = value.root.walkTopDown().firstOrNull { it.isFile && it.extension == "ld" }
                    if (old != null) withContext(Dispatchers.IO) { old.root.deleteRecursively() }
                }.onFailure { error = "导入失败：${it.message}" }
            importing = false
        }
    }
    fun request(): FlashRequest {
        val selectedProbe = probe ?: error("请连接并选择 ${probeKind.label}")
        require(selectedProbe.kind == probeKind) { "所选设备与路线不一致" }
        val selectedTarget = target ?: error("请选择芯片系列")
        val options = FlashOptions(selectedProbe, wire, selectedTarget, speed.toIntOrNull() ?: 0, address, underReset, resetAfter)
        val chosenPlan = if (sourceMode) plan ?: genericPlan() else null
        require(if (sourceMode) chosenPlan != null else firmware != null) { "请选择待烧录的固件文件" }
        FlashValidation.check(options, if (sourceMode) null else firmware)
        return FlashRequest(options, if (sourceMode) null else firmware, chosenPlan)
    }
    private fun genericPlan(): BuildPlan {
        val p = project ?: error("请先导入源码目录或 ZIP")
        val script = linker ?: error("源码编译需要 GNU .ld 链接脚本。Keil 专用工程请提供 GCC 启动文件与链接脚本。")
        require(cpu in SourceProjects.cpus && p.sources.isNotEmpty()) { "请选择 CPU，并提供 C / C++ / GNU 汇编源文件" }
        val includes = p.root.walkTopDown().filter { it.isFile && it.extension.lowercase() in setOf("h", "hpp") }.map { it.parentFile!! }.distinct().toList()
        return BuildPlan("通用源码", p.root, p.sources, includes, defines.split(';').map { it.trim() }.filter { it.isNotEmpty() }, script, cpu)
    }
    fun begin(request: FlashRequest) {
        error = null
        try {
            val device = usb.deviceList[request.options.probe.key] ?: error("烧录器已断开")
            if (usb.hasPermission(device)) FlashCoordinator.start(getApplication(), request)
            else {
                val token = UUID.randomUUID().toString()
                pending = token to request; permissionPending = true
                val intent = Intent(permissionAction).setPackage(getApplication<Application>().packageName)
                    .setData(Uri.parse("flash-permission://request/$token"))
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
                usb.requestPermission(device, PendingIntent.getBroadcast(getApplication(), 44, intent, flags))
            }
        } catch (e: Exception) { pending = null; permissionPending = false; error = e.message }
    }
    override fun onCleared() { pending = null; getApplication<Application>().unregisterReceiver(receiver) }
}
