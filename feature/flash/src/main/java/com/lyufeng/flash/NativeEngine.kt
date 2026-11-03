package com.lyufeng.flash

import android.content.Context
import android.os.ParcelFileDescriptor
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.CancellationException

object NativeProcess {
    init { System.loadLibrary("flashprocess") }
    external fun spawn(arguments: Array<String>, environment: Array<String>, usbfd: Int): IntArray
    external fun waitFor(pid: Int): Int
    external fun terminate(pid: Int)
}

class ToolProcess(private val context: Context, private val log: (String) -> Unit) {
    private val gate = Any()
    private var pid = -1
    @Volatile private var cancelled = false
    fun cancel() = synchronized(gate) { cancelled = true; if (pid > 0) NativeProcess.terminate(pid) }
    fun checkCancelled() { if (cancelled) throw CancellationException("操作已停止") }
    fun run(tool: String, arguments: List<String>, usbfd: Int = -1, timeoutSeconds: Long = 180): String {
        checkCancelled()
        val native = File(context.applicationInfo.nativeLibraryDir)
        val executable = File(native, tool)
        require(executable.isFile) { "当前手机架构没有所需工具：$tool" }
        val temp = File(context.cacheDir, "flash/tmp").apply { mkdirs() }
        val environment = arrayOf("LD_LIBRARY_PATH=${native.absolutePath}", "TMPDIR=${temp.absolutePath}",
            "HOME=${temp.absolutePath}", "PATH=${native.absolutePath}:/system/bin", "TOOLKIT_USB_FD=198", "LANG=C")
        val child = synchronized(gate) {
            checkCancelled()
            NativeProcess.spawn((listOf(executable.absolutePath) + arguments).toTypedArray(), environment, usbfd).also {
                check(it[0] > 0) { "无法启动工具，系统错误：${-it[0]}" }; pid = it[0]
            }
        }
        val timer = Executors.newSingleThreadScheduledExecutor()
        val timedOut = java.util.concurrent.atomic.AtomicBoolean(false)
        val timeout = timer.schedule({ synchronized(gate) { if (pid == child[0]) {
            timedOut.set(true); NativeProcess.terminate(pid)
        } } }, timeoutSeconds, TimeUnit.SECONDS)
        val tail = ArrayDeque<String>()
        var code: Int
        try {
            ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.adoptFd(child[1])).bufferedReader().use { reader ->
                reader.forEachLine { line ->
                    val bounded = line.take(2000)
                    log(bounded); tail.addLast(bounded); if (tail.size > 16) tail.removeFirst()
                }
            }
        } finally {
            code = NativeProcess.waitFor(child[0])
            synchronized(gate) { if (pid == child[0]) pid = -1 }
            timeout.cancel(false); timer.shutdownNow()
        }
        checkCancelled()
        check(!timedOut.get()) { "工具运行超时，请检查接线、设备供电或源码工程" }
        check(code == 0) { "工具返回错误 $code\n${tail.joinToString("\n")}" }
        return tail.joinToString("\n")
    }
}

class FlashAssets(private val context: Context) {
    fun prepare(name: String): File {
        // Version the directory to prevent reuse of scripts or headers from an older APK.
        val root = File(context.filesDir, "flash/runtime-v1/$name")
        val marker = File(root, ".ready")
        @Suppress("DEPRECATION")
        val stamp = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime.toString()
        if (!marker.isFile || marker.readText() != stamp) {
            root.deleteRecursively(); root.mkdirs()
            copy(name, root)
            marker.writeText(stamp)
        }
        return root
    }
    private fun copy(asset: String, dest: File) {
        val children = context.assets.list(asset).orEmpty()
        if (children.isEmpty()) {
            dest.parentFile!!.mkdirs()
            context.assets.open(asset).use { input -> dest.outputStream().use { input.copyTo(it) } }
        } else { dest.mkdirs(); children.forEach { copy("$asset/$it", File(dest, it)) } }
    }
    fun targets(): List<TargetProfile> {
        return context.assets.open("targets.tsv").bufferedReader().useLines { lines -> lines.filter { it.isNotBlank() }.map { line ->
            val parts = line.split('\t')
            TargetProfile(parts[0], parts[1], parts[2].split(',').map { Wire.valueOf(it) }.toSet(), parts.getOrElse(3) { "" })
        }.toList() }
    }
}

class SourceCompiler(private val context: Context, private val process: ToolProcess, private val log: (String) -> Unit) {
    fun compile(plan: BuildPlan): File {
        val native = File(context.applicationInfo.nativeLibraryDir)
        require(File(native, "libclang-tool.so").isFile) { "当前源码编译需要 arm64 Android 手机" }
        log("正在准备编译器头文件和标准库，首次使用需要稍候")
        val tools = FlashAssets(context).prepare("toolchain")
        val build = File(context.cacheDir, "flash/build/${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val objects = mutableListOf<File>()
        val common = listOf("--target=arm-none-eabi", "-mcpu=${plan.cpu}", "-mthumb", "-ffreestanding", "-ffunction-sections", "-fdata-sections", "-fno-common", "-fno-ident", "-Os", "-g0",
            "-resource-dir=${File(tools, "clang")}", "-isystem", File(tools, "arm/include").absolutePath) +
            plan.includes.flatMap { listOf("-I", it.absolutePath) } + plan.defines.map { "-D$it" } + plan.flags
        val cppVersion = File(tools, "arm/include/c++").listFiles()?.firstOrNull { it.isDirectory }
        for ((index, source) in plan.sources.withIndex()) {
            process.checkCancelled()
            log("编译 ${index + 1}/${plan.sources.size} · ${source.name}")
            val obj = File(build, "$index.o")
            val isCpp = source.extension.lowercase() in setOf("cpp", "cc", "cxx")
            val language = when { isCpp -> "c++"; source.extension.lowercase() in setOf("s", "asm") -> "assembler-with-cpp"; else -> "c" }
            val cpp = if (isCpp) listOfNotNull(cppVersion, cppVersion?.let { File(it, "arm-none-eabi") }, cppVersion?.let { File(it, "backward") })
                .flatMap { listOf("-isystem", it.absolutePath) } + listOf("-fno-exceptions", "-fno-rtti") else emptyList()
            process.run("libclang-tool.so", common + cpp + listOf("-x", language, "-c", source.absolutePath, "-o", obj.absolutePath), timeoutSeconds = 120)
            objects.add(obj)
        }
        val lib = File(tools, "arm/lib/${multilib(plan)}")
        require(lib.isDirectory) { "此 CPU / FPU 组合没有匹配的标准库，请调整编译参数" }
        val elf = File(build, "firmware.elf")
        val standardLibs = (if (plan.sources.any { it.extension.lowercase() in setOf("cpp", "cc", "cxx") }) listOf("stdc++", "supc++") else emptyList()) + listOf("c", "m", "gcc", "nosys")
        log("正在链接 · ${plan.name}")
        process.run("liblld-tool.so", listOf("-flavor", "gnu", "-T", plan.linker.absolutePath, "--gc-sections", "-L${plan.root}", "-L${plan.linker.parent}", "-L$lib", "-o", elf.absolutePath) +
            plan.linkFlags + objects.map { it.absolutePath } + listOf("--start-group") + plan.libraries.map { it.absolutePath } + standardLibs.map { "-l$it" } + listOf("--end-group"))
        require(elf.isFile && elf.length() > 52) { "编译器没有生成有效 ELF 固件" }
        log("编译完成 · ${elf.length()} 字节")
        return elf
    }
    fun multilib(plan: BuildPlan): String {
        val abi = plan.flags.firstOrNull { it.startsWith("-mfloat-abi=") }?.substringAfter('=') ?: "soft"
        val fpu = plan.flags.firstOrNull { it.startsWith("-mfpu=") }?.substringAfter('=') ?: ""
        val suffix = if (abi == "soft") "nofp" else abi
        val fp = if (abi == "soft") "" else if (fpu.contains("fpv5-d16")) "+dp" else "+fp"
        val arch = when (plan.cpu) {
            "cortex-m0", "cortex-m0plus", "cortex-m1" -> "v6-m"
            "cortex-m3" -> "v7-m"
            "cortex-m4", "cortex-m7" -> "v7e-m$fp"
            "cortex-m23" -> "v8-m.base"
            "cortex-m33", "cortex-m35p" -> "v8-m.main$fp"
            else -> { require(abi == "hard") { "Cortex-M55 / M85 工程需要指定匹配的 FPU 与 hard-float 参数" }; "v8.1-m.main+mve" }
        }
        return "$arch/$suffix"
    }
}

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
