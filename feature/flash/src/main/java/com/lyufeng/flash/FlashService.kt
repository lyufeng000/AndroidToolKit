package com.lyufeng.flash

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import android.os.IBinder
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.UUID

data class FlashRequest(val options: FlashOptions, val image: File?, val plan: BuildPlan?)

object FlashCoordinator {
    private val mutable = MutableStateFlow(FlashState())
    val state = mutable.asStateFlow()
    private val gate = Any()
    private var pending: Pair<String, FlashRequest>? = null
    private var process: ToolProcess? = null
    private var cancelled = false
    fun start(context: Context, request: FlashRequest) = synchronized(gate) {
        check(!mutable.value.busy) { "已有烧录任务正在运行" }
        cancelled = false
        val token = UUID.randomUUID().toString()
        pending = token to request
        mutable.value = FlashState(FlashPhase.CONNECTING, busy = true)
        try { ContextCompat.startForegroundService(context, Intent(context, FlashService::class.java).putExtra("token", token)) }
        catch (error: Exception) { pending = null; fail(error) }
    }
    fun take(token: String?): FlashRequest? = synchronized(gate) {
        if (pending?.first != token) null else pending?.second.also { pending = null }
    }
    fun attach(value: ToolProcess) = synchronized(gate) { process = value; if (cancelled) value.cancel() }
    fun cancel() = synchronized(gate) { cancelled = true; process?.cancel() }
    fun log(line: String) = mutable.update { it.copy(logs = (it.logs + line.take(2000)).takeLast(400)) }
    fun phase(value: FlashPhase) = mutable.update { it.copy(phase = value) }
    fun finish() = synchronized(gate) {
        process = null
        mutable.update { it.copy(phase = if (cancelled) FlashPhase.CANCELLED else FlashPhase.SUCCESS, busy = false) }
    }
    fun fail(error: Throwable) = synchronized(gate) {
        process = null
        val stopped = cancelled || error is java.util.concurrent.CancellationException
        mutable.update { it.copy(phase = if (stopped) FlashPhase.CANCELLED else FlashPhase.FAILED, busy = false,
            error = if (stopped) null else error.message ?: error.javaClass.simpleName) }
    }
}

class FlashService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var process: ToolProcess? = null
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel("flash", "代码烧入", NotificationManager.IMPORTANCE_LOW))
        }
        val open = packageManager.getLaunchIntentForPackage(packageName)?.setAction("com.lyufeng.androidtoolkit.OPEN_FLASH")
        val notification = NotificationCompat.Builder(this, "flash").setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("代码烧入").setContentText("正在编译、烧录或校验，请保持 USB 连接")
            .setOngoing(true).apply { if (open != null) setContentIntent(PendingIntent.getActivity(this@FlashService, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)) }.build()
        startForeground(203, notification)
        val request = FlashCoordinator.take(intent?.getStringExtra("token"))
        if (request == null) { if (job?.isActive != true) stopSelf(startId); return START_NOT_STICKY }
        val previousJob = job
        job = scope.launch {
            previousJob?.join()
            val wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:flash")
            try {
                wake.acquire(30 * 60 * 1000L)
                val runner = ToolProcess(this@FlashService, FlashCoordinator::log)
                process = runner; FlashCoordinator.attach(runner)
                val image = request.plan?.let {
                    FlashCoordinator.phase(FlashPhase.BUILDING)
                    SourceCompiler(this@FlashService, runner, FlashCoordinator::log).compile(it)
                } ?: request.image ?: error("请先选择固件或源码工程")
                runner.checkCancelled()
                FlashValidation.check(request.options, image)
                FlashCoordinator.phase(FlashPhase.CONNECTING)
                val usb = getSystemService(UsbManager::class.java)
                val device = usb.deviceList[request.options.probe.key] ?: error("所选烧录器已断开")
                require(usb.hasPermission(device)) { "USB 访问权限已失效，请重新开始" }
                require(device.vendorId == request.options.probe.vid && device.productId == request.options.probe.pid) { "USB 设备已改变，请重新选择" }
                val scripts = FlashAssets(this@FlashService).prepare("openocd")
                val config = File(cacheDir, "flash/session-${UUID.randomUUID()}.cfg").apply {
                    parentFile!!.mkdirs(); writeText(FlashCommand.script(request.options, image, scripts))
                }
                val connection = usb.openDevice(device) ?: error("无法打开烧录器。请检查其他应用是否占用设备。")
                try {
                    FlashCoordinator.log("${request.options.probe.kind.label} · ${request.options.wire.label} · ${request.options.target.label}")
                    FlashCoordinator.phase(FlashPhase.PROGRAMMING)
                    val result = runner.run("libopenocd.so", listOf("-s", scripts.absolutePath, "-f", config.absolutePath), connection.fileDescriptor, 600)
                    check(result.contains("** Verified OK **")) { "烧录进程未报告校验通过，请检查日志" }
                } finally { connection.close() }
                config.delete()
                FlashCoordinator.finish()
            } catch (error: Throwable) { FlashCoordinator.fail(error) }
            finally { process = null; if (wake.isHeld) wake.release(); stopSelf(startId) }
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() { process?.cancel(); scope.cancel(); super.onDestroy() }
}
