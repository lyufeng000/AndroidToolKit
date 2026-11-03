@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.lyufeng.phoneserialport.ui

import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.Lifecycle
import com.lyufeng.phoneserialport.data.AppSettings
import com.lyufeng.phoneserialport.core.SerialPresets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.charset.Charset
import java.io.File

@Composable internal fun SettingsScreen(vm: SerialViewModel, settings: AppSettings, notificationPermission: ()->Unit) {
    val context=LocalContext.current
    val notificationRevision by vm.notificationRevision.collectAsStateWithLifecycle()
    var resumeRevision by remember {mutableIntStateOf(0)}
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {resumeRevision++}
    val notificationsEnabled=remember(notificationRevision,resumeRevision) {NotificationManagerCompat.from(context).areNotificationsEnabled()}
    val channelImportance=remember(notificationRevision,resumeRevision) {
        if(Build.VERSION.SDK_INT >= 26) context.getSystemService(NotificationManager::class.java).getNotificationChannel("serial_connection")?.importance else null
    }
    val directory=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) {uri -> uri?.let(vm::saveDirectory)}
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) {uri -> uri?.let(vm::exportConfig)}
    val import=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {uri -> uri?.let(vm::importConfig)}
    var confirmReset by remember {mutableStateOf<String?>(null)}
    var importConfirm by remember {mutableStateOf<Uri?>(null)}
    fun systemSettings(intent: Intent) {try {context.startActivity(intent)} catch(e: Exception) {vm.message.value=e.message}}
    LazyColumn(Modifier.fillMaxSize().padding(horizontal=12.dp),verticalArrangement=Arrangement.spacedBy(12.dp),contentPadding=PaddingValues(bottom=16.dp)) {
        item { Section(tr("串口与连接","Serial and connection")) {
            Toggle(tr("自动重连","Auto reconnect"),settings.autoReconnect) {vm.save(settings.copy(autoReconnect=it))}
            Toggle(tr("连接期间常亮","Keep screen on while connected"),settings.keepScreenOn) {vm.save(settings.copy(keepScreenOn=it))}
            Toggle(tr("显示控制线区域","Show control lines"),settings.showControlLines) {vm.save(settings.copy(showControlLines=it))}
            Text(tr("自动重连会重新检查 USB 权限。后台启动受系统限制时会提示返回应用。不会重放未完成的发送。","Reconnect checks USB permission again. If Android blocks background start, return to the app. Unfinished commands are never replayed."))
        } }
        item { Section(tr("接收与终端","Receive and terminal")) {
            Choice(tr("字符集","Charset"),settings.charset,remember {Charset.availableCharsets().keys.toList()}) {vm.save(settings.copy(charset=it))}
            Choice(tr("接收分隔","Receive delimiter"),settings.receiveEnding,listOf("CRLF","CR","LF","STX_ETX","NUL","NONE")) {vm.save(settings.copy(receiveEnding=it))}
            Choice(tr("显示缓存","Display buffer"),if(settings.bufferBytes == 0) tr("不限（磁盘分页）","Unlimited (disk pages)") else "${settings.bufferBytes/1024} KiB",listOf("2 KiB","10 KiB","50 KiB","200 KiB","Unlimited")) {
                vm.save(settings.copy(bufferBytes=when(it) {"2 KiB"->2048;"10 KiB"->10240;"50 KiB"->51200;"200 KiB"->204800;else->0}))
            }
            Text(tr("缓存按原始字节计量。“不限”仍受可用磁盘空间限制。持续日志独立于显示缓存。","The display limit counts raw bytes. Unlimited mode uses disk space. Communication logs are independent."))
            Toggle(tr("显示时间戳","Show timestamps"),settings.timestamps) {vm.save(settings.copy(timestamps=it))}
            Toggle(tr("时间戳包含日期","Include date"),settings.datePrefix) {vm.save(settings.copy(datePrefix=it))}
            Text(if(settings.datePrefix) "yyyy-MM-dd HH:mm:ss.SSS" else "HH:mm:ss.SSS")
            Text(java.text.SimpleDateFormat(if(settings.datePrefix) "yyyy-MM-dd HH:mm:ss.SSS" else "HH:mm:ss.SSS",java.util.Locale.ROOT).format(java.util.Date()))
            Toggle(tr("显示状态消息","Show status messages"),settings.showStatus) {vm.save(settings.copy(showStatus=it))}
            Toggle(tr("可视化控制字符","Show control characters"),settings.controlCharacters) {vm.save(settings.copy(controlCharacters=it))}
            Toggle(tr("等宽字体","Monospace font"),settings.monospace) {vm.save(settings.copy(monospace=it))}
            Choice(tr("字号","Font size"),settings.fontSize.toString(),(6..30 step 2).map(Int::toString)) {vm.save(settings.copy(fontSize=it.toInt()))}
            NumberField(tr("刷新间隔 ms","Refresh interval, ms"),settings.refreshMs,16,1000) {vm.save(settings.copy(refreshMs=it))}
            NumberField(tr("VT100 列数","VT100 columns"),settings.terminalColumns.toLong(),20,240) {vm.save(settings.copy(terminalColumns=it.toInt()))}
            NumberField(tr("VT100 行数","VT100 rows"),settings.terminalRows.toLong(),5,120) {vm.save(settings.copy(terminalRows=it.toInt()))}
        } }
        item { Section(tr("发送与宏","Send and macros")) {
            Toggle(tr("本地回显","Local echo"),settings.echo) {vm.save(settings.copy(echo=it))}
            Toggle(tr("发送成功后清空输入","Clear input after successful send"),settings.clearAfterSend) {vm.save(settings.copy(clearAfterSend=it))}
            Toggle(tr("回车发送","Send with Enter"),settings.enterToSend) {vm.save(settings.copy(enterToSend=it))}
            Toggle(tr("使用 KiB 计数","Count in KiB"),settings.countInKiB) {vm.save(settings.copy(countInKiB=it))}
            Toggle(tr("清屏时重置计数","Reset counters on clear"),settings.clearResetsCounters) {vm.save(settings.copy(clearResetsCounters=it))}
            Choice(tr("宏栏行数","Macro rows"),settings.macroRows.toString(),(0..5).map(Int::toString)) {vm.save(settings.copy(macroRows=it.toInt()))}
            NumberField(tr("行延迟 ms","Line delay, ms"),settings.lineDelayMs.toLong(),0,9999) {vm.save(settings.copy(lineDelayMs=it.toInt()))}
            NumberField(tr("字符延迟 ms","Character delay, ms"),settings.charDelayMs.toLong(),0,9999) {vm.save(settings.copy(charDelayMs=it.toInt()))}
            Toggle(tr("按完整字符延迟（文本）","Delay by complete character (text)"),settings.delayByCharacter) {vm.save(settings.copy(delayByCharacter=it))}
            NumberField(tr("重复次数，0 为不限","Repeat count, 0 means unlimited"),settings.repeatCount.toLong(),0,Int.MAX_VALUE.toLong()) {vm.save(settings.copy(repeatCount=it.toInt()))}
            NumberField(tr("重复间隔 ms","Repeat interval, ms"),settings.repeatIntervalMs,10,86400000) {vm.save(settings.copy(repeatIntervalMs=it))}
        } }
        item { Section(tr("日志与文件","Logs and files")) {
            Toggle(tr("持续记录收发日志","Record communication logs"),settings.logEnabled) {vm.save(settings.copy(logEnabled=it))}
            Toggle(tr("日志使用 HEX","Use HEX in logs"),settings.logHex) {vm.save(settings.copy(logHex=it))}
            Text(if(settings.directoryUri.isBlank()) tr("保存目录：应用内部目录","Save directory: app internal storage") else settings.directoryUri,style=MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick={directory.launch(null)}) {Text(tr("选择目录","Choose directory"))}
                OutlinedButton(onClick={vm.save(settings.copy(directoryUri=""))}) {Text(tr("恢复应用目录","Use app directory"))}
            }
            Text(tr("目录权限失效时会停止日志并显示错误。已有文件会正常收尾。可重新选择目录或恢复应用目录。","If directory access fails, logging stops and shows an error. Existing files are closed. Select the directory again or use app storage."))
        } }
        item { Section(tr("配置管理","Configuration")) {
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick={export.launch("serial-config-${System.currentTimeMillis()}.json")}) {Text(tr("导出配置","Export configuration"))}
                OutlinedButton(onClick={import.launch(arrayOf("application/json","text/plain","application/octet-stream"))}) {Text(tr("导入配置","Import configuration"))}
            }
            Text(tr("导入会替换设置、宏和设备映射。连接状态、历史命令和文件访问权限不随配置恢复。","Import replaces settings, macros and mappings. Connection state, command history and file permissions are not restored."))
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick={confirmReset="SETTINGS"}) {Text(tr("重置设置","Reset settings"))}
                OutlinedButton(onClick={confirmReset="MACROS"}) {Text(tr("重置宏","Reset macros"))}
                OutlinedButton(onClick={confirmReset="MAPPINGS"}) {Text(tr("重置设备映射","Reset mappings"))}
            }
        } }
        item { Section(tr("系统与语言","System and language")) {
            Choice(tr("语言","Language"),settings.language,listOf("zh","en")) {vm.save(settings.copy(language=it))}
            Text(if(notificationsEnabled) tr("应用通知已启用","App notifications enabled") else tr("应用通知未启用","App notifications disabled"))
            if(Build.VERSION.SDK_INT >= 26) Text(tr("连接渠道：","Connection channel: ")+ (channelImportance?.let {if(it == NotificationManager.IMPORTANCE_NONE) tr("已关闭","Disabled") else tr("已启用","Enabled")} ?: tr("尚未创建","Not created")))
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick=notificationPermission) {Text(tr("申请通知权限","Request notification permission"))}
                OutlinedButton(onClick={systemSettings(if(Build.VERSION.SDK_INT >= 26) Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,context.packageName) else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:${context.packageName}")))}) {Text(tr("通知设置","Notification settings"))}
                OutlinedButton(onClick={systemSettings(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))}) {Text(tr("电池设置","Battery settings"))}
            }
            var contact by remember(settings.contact) {mutableStateOf(settings.contact)}
            OutlinedTextField(contact,{contact=it},label={Text(tr("联系方式（可留空）","Contact (optional)"))},modifier=Modifier.fillMaxWidth())
            OutlinedButton(onClick={vm.save(settings.copy(contact=contact.trim()))}) {Text(tr("保存联系方式","Save contact"))}
            OutlinedButton(onClick={vm.save(settings.copy(hintsSeen=false))}) {Text(tr("重新显示使用提示","Show usage hints again"))}
        } }
    }
    confirmReset?.let {domain -> AlertDialog(onDismissRequest={confirmReset=null},title={Text(tr("确认重置","Confirm reset"))},text={Text(tr("仅重置选中的配置域。","Reset only the selected configuration domain."))},confirmButton={TextButton(onClick={vm.reset(domain);confirmReset=null}) {Text(tr("重置","Reset"))}},dismissButton={TextButton(onClick={confirmReset=null}) {Text(tr("取消","Cancel"))}}) }
    if(!settings.hintsSeen) AlertDialog(onDismissRequest={vm.save(settings.copy(hintsSeen=true))},title={Text(tr("使用提示","Usage hints"))},text={Text(tr("先在设备页选择端口。默认参数为 115200 / 8N1 / 无流控。目标速率尚未实测。请先查看参数与诊断。","Select a port on the Devices page. Defaults are 115200 / 8N1 / no flow control. The target rate is not measured. Check Parameters first."))},confirmButton={TextButton(onClick={vm.save(settings.copy(hintsSeen=true))}) {Text(tr("知道了","OK"))}})
}

@Composable internal fun HelpScreen(vm: SerialViewModel, settings: AppSettings) {
    val context=LocalContext.current
    var selectedLog by remember {mutableStateOf<File?>(null)}
    var logText by remember {mutableStateOf("")}
    var files by remember {mutableStateOf(emptyList<File>())}
    LaunchedEffect(Unit) {files=withContext(Dispatchers.IO) {context.filesDir.listFiles()?.filter {it.extension == "log"}?.sortedByDescending {it.lastModified()} ?: emptyList()}}
    LaunchedEffect(selectedLog) {selectedLog?.let {file -> logText=withContext(Dispatchers.IO) {runCatching {java.io.RandomAccessFile(file,"r").use {r -> r.seek((r.length()-100*1024).coerceAtLeast(0)); val bytes=ByteArray((r.length()-r.filePointer).toInt());r.readFully(bytes);String(bytes,Charsets.UTF_8)}}.getOrElse {it.message ?: "Read failed"}}}}
    LazyColumn(Modifier.fillMaxSize().padding(horizontal=12.dp),verticalArrangement=Arrangement.spacedBy(12.dp),contentPadding=PaddingValues(bottom=16.dp)) {
        item {Section(tr("关于","About")) {
            Text(tr("串口调试","Serial debug")+" 1.0")
            Text(LocalContext.current.packageName)
            Text("usb-serial-for-android 3.11.0 · MIT")
            Text(tr("致谢：usb-serial-for-android、AndroidX、Kotlin。","Thanks: usb-serial-for-android, AndroidX and Kotlin."))
            Text(tr("已集成 WCH 官方 Android SDK 2.3 包。选择 WchOfficial 驱动后，可查询芯片、主频、UART 错误和 GPIO。能力按设备分别确认。该 SDK 未提供公开复位接口。","WCH Android SDK package 2.3 is included. Select WchOfficial to query the chip, clock, UART errors and GPIO. Capabilities depend on the device. This SDK exposes no reset API."))
            if(settings.contact.isNotBlank()) {
                Text(settings.contact)
                val address=settings.contact.removePrefix("mailto:")
                if('@' in address) OutlinedButton(onClick={try {context.startActivity(Intent(Intent.ACTION_SENDTO,Uri.parse("mailto:$address")))} catch(e: Exception) {vm.message.value=e.message}}) {Text(tr("联系","Contact"))}
            }
        } }
        item {Section(tr("连接与速率说明","Connection and rate")) {
            Text(tr("主要目标为小米15Pro与 CH340G。当前未完成真机兼容验收。仅凭 VID/PID 不能确认 CH340G 后缀。","Primary targets are Xiaomi 15 Pro and CH340G. Device acceptance is pending. VID/PID alone does not prove the CH340G variant."))
            Text(tr("1152000 必须用独立仪器测量 UART 位宽。允许偏差为 ±1%。请求值、驱动报告值、分频推算值和实测值分别显示。1200000 不能标记为达标。","Measure UART bit width with an independent instrument. The target is 1152000 ±1%. Requested, reported, estimated and measured rates remain separate. 1200000 does not pass."))
            Text(tr("起始位固定为 1。其他参数按驱动能力开放。灰色入口会说明硬件不支持、驱动未实现或能力未知。读取失败单独报告。","Start bits are fixed at 1. Driver capabilities control other parameters. Disabled actions show the capability state. Read errors are reported separately."))
        } }
        item {Section(tr("收发、文件与终端","Data, files and terminal")) {
            Text(tr("发送字节计数仅表示驱动确认写入 USB 的字节，不代表对端已经收到。发生部分写入时不会自动重发整条命令。","Transmit counts show bytes accepted by USB. They do not prove delivery to the peer. A partial write never repeats the whole command."))
            Text(tr("应用队列溢出与显示裁剪分别计数。普通串口字节流不能直接确定通信丢失数量。需要对端序号与校验。","Queue overflow and display trimming have separate counters. A raw serial stream cannot quantify communication loss. Peer sequence numbers and checks are required."))
            Text(tr("Xmodem 支持校验和、CRC 和 1K 发送。每次等待 10 s。初次发送后最多重试 3 次。文件接收不在范围内。","Xmodem supports checksum, CRC and 1K sending. Each wait lasts 10 s. A send permits up to 3 retries. File reception is outside scope."))
            Text(tr("VT100 默认 80×24。支持光标、擦除、滚动区域、字符属性、光标保存恢复及常用控制键。可在设置页调整尺寸。","VT100 starts at 80×24. It supports cursor movement, erase, scroll regions, text attributes, cursor save and restore, and common keys. Change dimensions in Settings."))
            Text(tr("11 条 AT 指令面向串口对端模组。ATE1 会启用模组回显。可使用停止按钮中断序列。","The 11 AT commands address the peer modem. ATE1 enables modem echo. Use Stop to cancel the sequence."))
            SelectionContainer {Text(SerialPresets.atCommands.joinToString("\n"))}
        } }
        item {Section(tr("锁屏与后台运行","Lock screen and background")) {
            Text(tr("连接由 connectedDevice 前台服务持有。通知支持返回应用与断开全部端口。请通过系统通知和电池设置管理后台限制。","A connectedDevice foreground service owns connections. The notification opens the app or disconnects all ports. Use system notification and battery settings."))
            Text(tr("锁屏持续收发仍需小米15Pro真机验证。进程重新启动后会重新检查设备与权限。不会恢复虚假的已连接状态。","Lock-screen operation needs Xiaomi 15 Pro testing. After process restart, the app checks devices and permission again. It never restores a false connected state."))
        } }
        item {Text(tr("应用内部日志","Internal log files"),style=MaterialTheme.typography.titleMedium)}
        items(files,key={it.name}) {file -> OutlinedButton(onClick={selectedLog=file}) {Text("${file.name} · ${file.length()} B")} }
    }
    selectedLog?.let {file -> AlertDialog(onDismissRequest={selectedLog=null},title={Text(file.name)},text={SelectionContainer {Text(logText,Modifier.heightIn(max=420.dp).verticalScroll(rememberScrollState()))}},confirmButton={TextButton(onClick={
        try {val uri=FileProvider.getUriForFile(context,"${context.packageName}.files",file);context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),null))} catch(e: Exception) {vm.message.value=e.message}
    }) {Text(tr("分享日志","Share log"))}},dismissButton={TextButton(onClick={selectedLog=null}) {Text(tr("关闭","Close"))}}) }
}
