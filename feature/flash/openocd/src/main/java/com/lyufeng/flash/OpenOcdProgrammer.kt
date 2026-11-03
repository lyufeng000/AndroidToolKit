package com.lyufeng.flash

import android.content.Context
import android.hardware.usb.UsbManager
import java.io.File
import java.util.UUID

class OpenOcdProgrammer(
    private val context: Context,
    private val runner: ToolProcess,
    private val phase: (FlashPhase) -> Unit,
    private val log: (String) -> Unit,
) {
    fun program(options: FlashOptions, image: File) {
        FlashValidation.check(options, image)
        phase(FlashPhase.CONNECTING)
        val usb = context.getSystemService(UsbManager::class.java)
        val device = usb.deviceList[options.probe.key] ?: error("所选烧录器已断开")
        require(usb.hasPermission(device)) { "USB 访问权限已失效，请重新开始" }
        require(device.vendorId == options.probe.vid && device.productId == options.probe.pid) { "USB 设备已改变，请重新选择" }
        val scripts = FlashAssets(context).prepare("openocd")
        val config = File(context.cacheDir, "flash/session-${UUID.randomUUID()}.cfg").apply {
            parentFile!!.mkdirs()
            writeText(FlashCommand.script(options, image, scripts))
        }
        val connection = usb.openDevice(device) ?: error("无法打开烧录器。请检查其他应用是否占用设备。")
        try {
            log("${options.probe.kind.label} · ${options.wire.label} · ${options.target.label}")
            phase(FlashPhase.PROGRAMMING)
            val arguments = listOf("-s", scripts.absolutePath, "-f", config.absolutePath)
            val result = runner.run("libopenocd.so", arguments, connection.fileDescriptor, 600)
            check(result.contains("** Verified OK **")) { "烧录进程未报告校验通过，请检查日志" }
        } finally {
            connection.close()
        }
        config.delete()
    }
}
