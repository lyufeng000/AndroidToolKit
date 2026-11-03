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
