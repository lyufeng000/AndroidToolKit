package com.lyufeng.flash

import java.io.File

object FlashCommand {
    fun script(options: FlashOptions, firmware: File, scripts: File): String {
        FlashValidation.check(options, firmware)
        val isSt = options.probe.kind == ProbeKind.ST_LINK
        val legacy = isSt && options.probe.pid == 0x3744
        val interfaceFile = when (options.probe.kind) {
            ProbeKind.ST_LINK -> if (legacy) "stlink" else "stlink-dap"
            ProbeKind.J_LINK -> "jlink"
            ProbeKind.DAP_LINK -> "cmsis-dap"
        }
        val wire = options.wire.name.lowercase()
        val transport = if (isSt) { if (legacy) "hla_$wire" else "dapdirect_$wire" } else wire
        val target = File(scripts, "target/${options.target.id}.cfg")
        require(target.isFile) { "目标芯片配置不存在" }
        val address = if (firmware.extension.equals("bin", true)) " 0x${FlashValidation.address(options.address).toString(16)}" else ""
        return buildString {
            appendLine("gdb_port disabled")
            appendLine("tcl_port disabled")
            appendLine("telnet_port disabled")
            appendLine("source [find interface/$interfaceFile.cfg]")
            if (options.probe.kind == ProbeKind.DAP_LINK) {
                appendLine("cmsis-dap backend usb_bulk")
                appendLine("cmsis-dap vid_pid ${options.probe.vid} ${options.probe.pid}")
                options.probe.usbInterface?.let { appendLine("cmsis-dap usb interface $it") }
            }
            appendLine("transport select $transport")
            appendLine("adapter speed ${options.speedKhz}")
            appendLine("source ${FlashValidation.tclPath(target)}")
            if (options.connectUnderReset) appendLine("reset_config srst_only srst_nogate connect_assert_srst")
            val reset = if (options.resetAfter) " reset" else ""
            appendLine("program ${FlashValidation.tclPath(firmware)}$address verify$reset exit")
        }
    }
}
