package com.lyufeng.flash

import java.io.File

enum class ProbeKind(val label: String, val vid: Int) {
    ST_LINK("ST-Link", 0x0483), J_LINK("J-Link", 0x1366), DAP_LINK("DAPLink", 0x0d28)
}
enum class Wire(val label: String) { SWD("SWD"), JTAG("JTAG") }
data class Probe(
    val key: String, val label: String, val kind: ProbeKind, val pid: Int,
    val vid: Int = kind.vid, val usbInterface: Int? = null,
)
data class TargetProfile(val id: String, val label: String, val wires: Set<Wire>, val defaultAddress: String = "") {
    fun accepts(probe: ProbeKind) = probe != ProbeKind.ST_LINK || id.startsWith("stm32")
}
data class ImportedProject(val root: File, val label: String, val firmware: List<File>, val sources: List<File>, val buildPlans: List<BuildPlan>, val buildNote: String? = null)
data class BuildPlan(
    val name: String, val root: File, val sources: List<File>, val includes: List<File>, val defines: List<String>,
    val linker: File, val cpu: String, val flags: List<String> = emptyList(), val libraries: List<File> = emptyList(),
    val linkFlags: List<String> = emptyList(),
)
data class FlashOptions(
    val probe: Probe, val wire: Wire, val target: TargetProfile, val speedKhz: Int,
    val address: String, val connectUnderReset: Boolean, val resetAfter: Boolean,
)
enum class FlashPhase(val label: String) {
    IDLE("等待配置"), IMPORTING("正在导入"), BUILDING("正在编译"), CONNECTING("正在连接"),
    PROGRAMMING("正在烧录与校验"), SUCCESS("烧录完成，校验通过"), FAILED("操作失败"), CANCELLED("已停止，目标固件可能不完整"),
}
data class FlashState(
    val phase: FlashPhase = FlashPhase.IDLE, val busy: Boolean = false,
    val logs: List<String> = emptyList(), val error: String? = null,
)

object FlashValidation {
    fun address(value: String): Long {
        require(value.matches(Regex("0[xX][0-9a-fA-F]{1,8}"))) { "请输入 32 位十六进制地址，例如 0x08000000" }
        return value.substring(2).toLong(16)
    }
    fun check(options: FlashOptions, firmware: File?) {
        require(options.target.accepts(options.probe.kind)) { "此 ST-Link 路线仅提供 STM32 支持，请改选其他烧录路线" }
        require(options.wire in options.target.wires) { "此目标配置不支持所选接口" }
        require(options.speedKhz in 50..15000) { "接口速率范围为 50–15000 kHz" }
        if (firmware != null) {
            require(firmware.isFile && firmware.length() > 0) { "固件文件为空或不存在" }
            require(firmware.extension.lowercase() in FirmwareFiles.extensions) { "不支持此固件格式" }
            if (firmware.extension.equals("bin", true)) address(options.address)
        }
    }
    // Inputs are staged under app-owned paths. Tcl braces cannot be escaped within a brace word.
    fun tclPath(file: File): String {
        val path = file.absolutePath.replace('\\', '/')
        require(path.none { it == '{' || it == '}' || it == '\n' || it == '\r' }) { "文件路径含有不支持的字符" }
        return "{$path}"
    }
}

object FirmwareFiles {
    val extensions = setOf("bin", "hex", "elf", "axf", "s19", "srec")
    val sourceExtensions = setOf("c", "cpp", "cc", "cxx", "s", "asm")
    fun scan(root: File) = root.walkTopDown().filter { it.isFile }.toList()
}
