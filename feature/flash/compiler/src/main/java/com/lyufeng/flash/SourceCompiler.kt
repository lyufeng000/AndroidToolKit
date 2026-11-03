package com.lyufeng.flash

import android.content.Context
import java.io.File

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
