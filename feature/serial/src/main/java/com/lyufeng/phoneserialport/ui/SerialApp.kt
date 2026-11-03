@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.lyufeng.phoneserialport.ui

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lyufeng.phoneserialport.core.*
import com.lyufeng.phoneserialport.data.*
import com.lyufeng.phoneserialport.serial.UsbConnectionPhase
import com.lyufeng.phoneserialport.serial.SerialSession
import com.lyufeng.phoneserialport.serial.Ch341Divisor
import com.lyufeng.phoneserialport.transfer.FileMode
import kotlinx.coroutines.launch
import java.nio.charset.Charset

private val English=staticCompositionLocalOf { false }
@Composable internal fun tr(zh: String,en: String): String {
    val context=LocalContext.current
    val configuration=androidx.compose.ui.platform.LocalConfiguration.current
    val english=English.current
    val localized=remember(context,english,configuration) {
        val config=android.content.res.Configuration(configuration)
        config.setLocale(java.util.Locale.forLanguageTag(if(english) "en" else "zh"))
        context.createConfigurationContext(config)
    }
    val key="ui_"+zh.hashCode().toUInt().toString(16)
    val id=remember(zh) {context.resources.getIdentifier(key,"string",context.packageName)}
    return if(id != 0) localized.getString(id) else if(english) en else zh
}

@Composable
fun SerialApp(vm: SerialViewModel, exit: ()->Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val sessions by vm.manager.sessions.collectAsStateWithLifecycle()
    val allStates=sessions.values.map {it.state.collectAsStateWithLifecycle().value}
    val active by vm.active.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var menuOpen by remember { mutableStateOf(false) }
    var terminalTools by remember { mutableStateOf(false) }
    var exitDialog by remember { mutableStateOf(false) }
    val returnHome by vm.returnHome.collectAsStateWithLifecycle()
    val leaving = returnHome != ReturnHomeState.IDLE
    LaunchedEffect(returnHome) { if (returnHome == ReturnHomeState.READY && vm.consumeReturnHome()) exit() }
    val connectionPhases by vm.manager.connectionPhases.collectAsStateWithLifecycle()
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    val managerMessage by vm.manager.message.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val snack=remember { SnackbarHostState() }
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if(!granted) vm.message.value="Notification permission denied. Check system notification settings."
        vm.notificationPermissionFinished()
    }
    LaunchedEffect(message,managerMessage) { (message ?: managerMessage)?.let { snack.showSnackbar(it); vm.message.value=null; vm.manager.message.value=null } }
    var opened by remember { mutableStateOf(false) }
    LaunchedEffect(sessions) { opened=sessions.values.any { it.state.value.state == PortState.OPEN } }
    // The active snapshot triggers keep-screen updates when the port closes without changing the map.
    val activeState=active?.state?.collectAsStateWithLifecycle()?.value
    DisposableEffect(settings.keepScreenOn,allStates.map {it.state}) {
        val window=(context as? Activity)?.window
        if(settings.keepScreenOn && sessions.values.any { it.state.value.state == PortState.OPEN }) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    val hasConnectionWork = connectionPhases.values.any { it in listOf(UsbConnectionPhase.PERMISSION_PENDING, UsbConnectionPhase.OPENING, UsbConnectionPhase.CONNECTED) } ||
        sessions.values.any { it.state.value.state in listOf(PortState.OPEN, PortState.OPENING, PortState.CLOSING) }
    fun leave() { vm.requestReturnHome() }
    fun requestReturn() { if (!leaving) { if (hasConnectionWork) exitDialog = true else leave() } }
    BackHandler { if(tab != 0) tab=0 else requestReturn() }
    CompositionLocalProvider(English provides (settings.language == "en")) {
        Scaffold(
            topBar={ TopAppBar(title={ Text(when(tab) {1 -> tr("设备","Devices");2 -> tr("设置","Settings");3 -> tr("帮助","Help");else -> tr("串口调试","Serial debug")},style=MaterialTheme.typography.titleMedium) },
                expandedHeight=48.dp,
                navigationIcon={TextButton(onClick={if(tab != 0) tab=0 else requestReturn()}) {Text(tr("返回","Back"))}},actions={
                Box {
                    TextButton(onClick={menuOpen=true}) {Text(tr("菜单","Menu"))}
                    DropdownMenu(menuOpen,onDismissRequest={menuOpen=false}) {
                        DropdownMenuItem(text={Text(tr("终端","Terminal"))},onClick={menuOpen=false;tab=0})
                        DropdownMenuItem(text={Text(tr("设备","Devices"))},onClick={menuOpen=false;tab=1})
                        DropdownMenuItem(text={Text(tr("调试选项","Debug options"))},onClick={menuOpen=false;tab=0;terminalTools=true})
                        DropdownMenuItem(text={Text(tr("设置","Settings"))},onClick={menuOpen=false;tab=2})
                        DropdownMenuItem(text={Text(tr("帮助","Help"))},onClick={menuOpen=false;tab=3})
                        HorizontalDivider()
                        DropdownMenuItem(text={Text(tr("返回首页","Back to home"))},onClick={menuOpen=false;requestReturn()})
                    }
                }
            }) }, snackbarHost={ SnackbarHost(snack) },
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize().imePadding()) {
                when(tab) {
                    0 -> TerminalScreen(vm,settings,active,terminalTools,{terminalTools=false},{tab=1})
                    1 -> DevicesScreen(vm,settings) { connect ->
                        if(Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                            vm.afterNotificationPermission=connect
                            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else connect()
                    }
                    2 -> SettingsScreen(vm,settings) { if(Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS) }
                    else -> HelpScreen(vm,settings)
                }
            }
        }
        if(exitDialog || returnHome == ReturnHomeState.CLOSING) AlertDialog(onDismissRequest={if(!leaving) exitDialog=false},title={Text(tr("串口连接进行中","Serial connection active"))},
            text={Text(tr("断开所有串口并返回首页？","Disconnect all ports and return home?"))},
            confirmButton={TextButton(enabled=!leaving,onClick={leave()}) {Text(tr("断开并返回","Disconnect and return"))}},
            dismissButton={TextButton(enabled=!leaving,onClick={exitDialog=false}) {Text(tr("取消","Cancel"))}})
    }
}

@Composable internal fun Section(title: String, content: @Composable ColumnScope.()->Unit) {
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text(title,style=MaterialTheme.typography.titleMedium,color=MaterialTheme.colorScheme.primary)
        content()
    } }
}
@Composable internal fun Toggle(label: String, checked: Boolean, enabled: Boolean = true, change: (Boolean)->Unit) {
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) { Text(label,Modifier.weight(1f)); Switch(checked,onCheckedChange=change,enabled=enabled) }
}
@Composable internal fun Choice(label: String, value: String, values: List<String>, enabled: Boolean = true,
    allowed: (String)->Boolean = {true}, change: (String)->Unit) {
    Selection(label,value,values,enabled,allowed,{it},change)
}
@Composable private fun <T> Selection(label: String,value: String,values: List<T>,enabled: Boolean = true,
    allowed: (T)->Boolean = {true},display: (T)->String,change: (T)->Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick={expanded=true},enabled=enabled) { Text("$label: $value ▾") }
        DropdownMenu(expanded,onDismissRequest={expanded=false}) {
            values.forEach { item -> DropdownMenuItem(text={Text(display(item))},enabled=allowed(item),onClick={expanded=false;change(item)}) }
        }
    }
}
private fun shortIdentifiers(ids: Collection<String>): Map<String,String> {
    val sorted=ids.distinct().sorted()
    fun shared(a: String,b: String): Int {var count=0;while(count < minOf(a.length,b.length) && a[count] == b[count]) count++;return count}
    return sorted.mapIndexed {index,id ->
        val previous=sorted.getOrNull(index-1)?.let {shared(id,it)} ?: 0
        val next=sorted.getOrNull(index+1)?.let {shared(id,it)} ?: 0
        id to id.take(maxOf(4,previous+1,next+1))
    }.toMap()
}
@Composable internal fun NumberField(label: String, value: Long, minimum: Long, maximum: Long, change: (Long)->Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    var invalid by remember { mutableStateOf(false) }
    OutlinedTextField(text,onValueChange={text=it;val number=it.toLongOrNull();invalid=number == null || number !in minimum..maximum; if(!invalid) change(number!!)},
        modifier=Modifier.widthIn(min=120.dp,max=260.dp),label={Text(label)},singleLine=true,isError=invalid,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),
        supportingText={if(invalid) Text("$minimum–$maximum")})
}

@Composable private fun ConfigEditor(config: SerialConfig, capabilities: SerialCapabilities?, change: (SerialConfig)->Unit) {
    var baud by remember(config.baudRate) { mutableStateOf(config.baudRate.toString()) }
    val configurable=capabilities?.configurationAvailable ?: true
    val validBaud=baud.toIntOrNull()?.let {it > 0 && (capabilities?.minimumBaud?.let {minimum -> it >= minimum} ?: true) && (capabilities?.maximumBaud?.let {maximum -> it <= maximum} ?: true)} ?: false
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
        OutlinedTextField(baud,onValueChange={baud=it;change(config.copy(baudRate=it.toIntOrNull() ?: 0))},
            label={Text(tr("自定义波特率","Custom baud rate"))},modifier=Modifier.weight(1f),singleLine=true,
            isError=configurable && !validBaud,enabled=configurable,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number))
        Choice(tr("预设","Preset"),config.baudRate.toString(),SerialPresets.baudRates.map(Int::toString),enabled=configurable,allowed={value -> val n=value.toInt(); (capabilities?.minimumBaud?.let {n >= it} ?: true) && (capabilities?.maximumBaud?.let {n <= it} ?: true)}) { baud=it;change(config.copy(baudRate=it.toInt())) }
    }
    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        Choice(tr("起始位","Start bits"),"1",listOf("1"),enabled=false) {}
        Choice(tr("数据位","Data bits"),config.dataBits.toString(),listOf("5","6","7","8"),enabled=configurable,allowed={capabilities == null || it.toInt() in capabilities.dataBits}) {change(config.copy(dataBits=it.toInt()))}
        Choice(tr("校验位","Parity"),config.parity.name,Parity.entries.map { it.name },enabled=configurable,allowed={capabilities == null || Parity.valueOf(it) in capabilities.parities}) {change(config.copy(parity=Parity.valueOf(it)))}
        Choice(tr("停止位","Stop bits"),config.stopBits.name,StopBits.entries.map { it.name },enabled=configurable,allowed={capabilities == null || StopBits.valueOf(it) in capabilities.stopBits}) {change(config.copy(stopBits=StopBits.valueOf(it)))}
        Choice(tr("流控","Flow control"),config.flowControl.name,FlowControl.entries.map { it.name },enabled=configurable,allowed={capabilities == null || FlowControl.valueOf(it) in capabilities.flowControls}) {change(config.copy(flowControl=FlowControl.valueOf(it)))}
    }
    Text(tr("起始位固定为 1。预设是候选值。可用参数以当前驱动能力为准。","Start bits are fixed at 1. Presets are candidates. The active driver defines available parameters."),style=MaterialTheme.typography.bodySmall)
    capabilities?.parameterKnowledge?.let {Text(capability(it.state)+": "+it.detail,style=MaterialTheme.typography.bodySmall)}
}

@Composable private fun DevicesScreen(vm: SerialViewModel, settings: AppSettings, requestNotification: (() -> Unit)->Unit) {
    val devices by vm.manager.devices.collectAsStateWithLifecycle()
    val connectionPhases by vm.manager.connectionPhases.collectAsStateWithLifecycle()
    val sessions by vm.manager.sessions.collectAsStateWithLifecycle()
    val snapshots=sessions.values.map {it.state.collectAsStateWithLifecycle().value}
    val memories by vm.deviceMemories.collectAsStateWithLifecycle()
    val listState=rememberLazyListState()
    var restored by remember {mutableStateOf(false)}
    LaunchedEffect(devices,memories) {
        if(!restored && devices.isNotEmpty() && memories.isNotEmpty()) {
            for(memory in memories) {
                val matches=devices.withIndex().filter {(_,d) -> d.vendorId == memory.vendorId && d.productId == memory.productId && (memory.serial == null || d.serial == memory.serial)}
                if(matches.size == 1) {listState.scrollToItem(2+matches.single().index);restored=true;break}
            }
        }
    }
    var draft by remember(settings.config) { mutableStateOf(settings.config) }
    var showMapping by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal=12.dp),state=listState,verticalArrangement=Arrangement.spacedBy(12.dp),contentPadding=PaddingValues(bottom=16.dp)) {
        item { Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Button(onClick={vm.manager.scan()}) {Text(tr("扫描设备","Scan devices"))}
            OutlinedButton(onClick={showMapping=true}) {Text(tr("手动映射","Manual mapping"))}
        } }
        item { Section(tr("待应用参数","Pending parameters")) { ConfigEditor(draft,null) {draft=it}; OutlinedButton(onClick={vm.save(settings.copy(config=draft))},enabled=runCatching {draft.validate()}.isSuccess) {Text(tr("保存默认参数","Save defaults"))} } }
        if(devices.isEmpty()) item {Text(tr("未发现 USB 设备。请检查 OTG 和数据线。","No USB devices found. Check OTG and the data cable."))}
        items(devices,key={it.key}) { device ->
            var driver by remember(device.key,device.driver) {mutableStateOf(device.driver ?: vm.manager.driverNames.first())}
            Section(device.product) {
                Text("VID:PID %04X:%04X".format(device.vendorId,device.productId))
                Text(device.key,style=MaterialTheme.typography.bodySmall)
                Text(when(connectionPhases[device.key]) {
                    com.lyufeng.phoneserialport.serial.UsbConnectionPhase.PERMISSION_PENDING -> tr("等待 USB 授权","Waiting for USB permission")
                    com.lyufeng.phoneserialport.serial.UsbConnectionPhase.OPENING -> tr("正在连接","Connecting")
                    com.lyufeng.phoneserialport.serial.UsbConnectionPhase.CONNECTED -> tr("已连接","Connected")
                    com.lyufeng.phoneserialport.serial.UsbConnectionPhase.DISCONNECTED -> tr("设备已断开","Detached")
                    com.lyufeng.phoneserialport.serial.UsbConnectionPhase.ERROR -> tr("连接错误","Connection error")
                    else -> tr("未连接","Not connected")
                })
                Text(if(device.permission) tr("已获 USB 权限","USB permission granted") else tr("尚未获得 USB 权限","USB permission required"))
                if(driver == "Ch34xSerialDriver") Text(tr("CH340/CH341 系列，精确后缀未确认。","CH340/CH341 family. Exact variant is unknown."))
                device.serial?.let { Text("Serial: $it") }
                memories.firstOrNull {it.vendorId == device.vendorId && it.productId == device.productId && (it.serial == null || it.serial == device.serial)}?.let {memory -> Text(tr("上次使用端口","Last used port")+": ${memory.port}")}
                Choice(tr("驱动","Driver"),driver,vm.manager.driverNames) {driver=it}
                val portCount=if(driver == com.lyufeng.phoneserialport.serial.WchDeviceHub.DRIVER) device.officialPortCount ?: device.portCount.coerceAtLeast(1)
                    else if(driver == device.driver) device.portCount.coerceAtLeast(1) else 1
                FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    repeat(portCount) { port -> Button(onClick={requestNotification {vm.manager.requestOpen(device.key,port,driver,draft)}},
                        enabled=snapshots.none { it.deviceName == device.key && it.portIndex == port && it.state == PortState.OPEN }) {Text(tr("打开端口 $port","Open port $port"))} }
                }
                OutlinedButton(onClick={vm.manager.disconnectDevice(device.key)}) {Text(tr("断开此设备","Disconnect device"))}
            }
        }
        item {Text(tr("独立端口会话","Independent port sessions"),style=MaterialTheme.typography.titleMedium)}
        items(sessions.values.toList(),key={it.id}) { session ->
            val snapshot by session.state.collectAsStateWithLifecycle()
            Section("${session.deviceKey} : ${session.portIndex}") {
                Text(portState(snapshot.state))
                Text(snapshot.appliedConfig?.summary() ?: tr("当前参数未知","Current parameters unknown"))
                snapshot.error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    Button(onClick={vm.select(session.id)}) {Text(tr("选择会话","Select session"))}
                    OutlinedButton(onClick={vm.manager.closePort(session.id)},enabled=snapshot.state == PortState.OPEN) {Text(tr("关闭端口","Close port"))}
                }
            }
        }
    }
    if(showMapping) MappingDialog(vm) {showMapping=false}
}

@Composable private fun portState(state: PortState): String = when(state) {
    PortState.CLOSED -> tr("已关闭","Closed"); PortState.OPENING -> tr("正在打开","Opening"); PortState.OPEN -> tr("已连接","Connected")
    PortState.CLOSING -> tr("正在关闭","Closing"); PortState.DISCONNECTED -> tr("设备已断开","Detached"); PortState.ERROR -> tr("连接错误","Connection error")
}
@Composable private fun MappingDialog(vm: SerialViewModel, dismiss: ()->Unit) {
    var vendor by remember {mutableStateOf("1A86")}; var product by remember {mutableStateOf("7523")}; var driver by remember {mutableStateOf(vm.manager.driverNames.first())}
    val valid=vendor.toIntOrNull(16)?.let { it in 0..65535 } == true && product.toIntOrNull(16)?.let { it in 0..65535 } == true
    AlertDialog(onDismissRequest=dismiss,title={Text(tr("手动设备映射","Manual device mapping"))},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(vendor,{vendor=it},label={Text("VID (HEX)")},singleLine=true)
        OutlinedTextField(product,{product=it},label={Text("PID (HEX)")},singleLine=true)
        Choice(tr("驱动","Driver"),driver,vm.manager.driverNames) {driver=it}
        Text(tr("映射仅指定驱动，不证明设备具备对应硬件能力。","A mapping selects a driver. It does not prove hardware support."))
    }},confirmButton={TextButton(enabled=valid,onClick={vm.saveMapping(vendor.toInt(16),product.toInt(16),driver);dismiss()}) {Text(tr("保存","Save"))}},dismissButton={TextButton(onClick=dismiss) {Text(tr("取消","Cancel"))}})
}

private data class DisplayPart(val record: DisplayRecord, val start: Int, val end: Int) {
    val key: String get() = "${record.key}:$start"
}

private fun displayParts(records: List<DisplayRecord>): List<DisplayPart> = buildList {
    for(record in records) {
        var start=0
        while(start < record.text.length) {
            // Bound the height of each lazy item, including responses made up of line breaks.
            var end=start+minOf(256,record.text.length-start)
            if(end < record.text.length) {
                val before=record.text[end-1]
                val after=record.text[end]
                if((before.isHighSurrogate() && after.isLowSurrogate()) || (before == '\r' && after == '\n')) end--
            }
            add(DisplayPart(record,start,end))
            start=end
        }
    }
}

@Composable private fun TerminalScreen(vm: SerialViewModel, settings: AppSettings, session: SerialSession?, toolsOpen: Boolean, closeTools: () -> Unit, openDevices: () -> Unit) {
    val sessions by vm.manager.sessions.collectAsStateWithLifecycle()
    val input by vm.input.collectAsStateWithLifecycle()
    var configDialog by remember {mutableStateOf(false)}
    var historyDialog by remember {mutableStateOf(false)}
    var macroEditor by remember {mutableStateOf<MacroEntity?>(null)}
    var newMacro by remember {mutableStateOf(false)}
    var newCommandGroup by remember {mutableStateOf(false)}
    var macroLibrary by remember {mutableStateOf(false)}
    var fileDialog by remember {mutableStateOf(false)}
    var exportFormat by rememberSaveable {mutableStateOf("TEXT")}
    var fileMode by rememberSaveable {mutableStateOf(FileMode.RAW.name)}
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {uri -> if(uri != null) vm.transfer(uri,FileMode.valueOf(fileMode))}
    val save=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) {uri -> if(uri != null) vm.exportData(uri,exportFormat)}
    val vofaPicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {uri -> if(uri != null) vm.importVofa(uri)}
    val context=LocalContext.current; val scope=rememberCoroutineScope()
    val clipboard=LocalClipboardManager.current
    val records by vm.display.collectAsStateWithLifecycle()
    val snapshot=session?.state?.collectAsStateWithLifecycle()?.value
    val paused by vm.paused.collectAsStateWithLifecycle()
    val currentPage by vm.page.collectAsStateWithLifecycle()
    val pages by vm.pageCount.collectAsStateWithLifecycle()
    val macros by vm.macros.collectAsStateWithLifecycle()
    val sessionLabels=remember(sessions.keys) {shortIdentifiers(sessions.keys)}
    val macroLabels=remember(macros) {shortIdentifiers(macros.map {it.id})}
    val connected=snapshot?.state == PortState.OPEN
    val groupRunning=snapshot?.commandGroupName != null
    val canTransmit=connected && snapshot?.transfer?.status != "RUNNING" && !groupRunning
    var breakDuration by rememberSaveable {mutableIntStateOf(100)}
    var breakHeld by remember(session?.id) {mutableStateOf(false)}
    LaunchedEffect(session?.id,snapshot?.error) {snapshot?.error?.let {vm.message.value=it}}
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact=maxHeight < 400.dp
        val visibleMacros=macros.filter {it.row < settings.macroRows}
        val groups=if(compact) listOf(visibleMacros) else
            (0 until settings.macroRows.coerceAtLeast(1)).map {row -> visibleMacros.filter {it.row == row}}
                .filter {it.isNotEmpty()}.ifEmpty {listOf(emptyList())}
        Column(Modifier.fillMaxSize().padding(horizontal=10.dp).padding(bottom=6.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            groups.forEachIndexed {index,group ->
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp),verticalAlignment=Alignment.CenterVertically) {
                    if(index == 0) {
                        if(snapshot?.repeating == true && !groupRunning) TextButton(onClick={session?.stopSending()}) {Text(tr("停止","Stop"))}
                        TextButton(onClick={macroLibrary=true},contentPadding=PaddingValues(horizontal=8.dp)) {Text(tr("＋ 宏","+ Macro"))}
                    }
                    Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp),verticalAlignment=Alignment.CenterVertically) {
                    group.forEach {macro ->
                        Surface(shape=MaterialTheme.shapes.small,border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant)) {
                            Row(verticalAlignment=Alignment.CenterVertically) {
                                TextButton(onClick={vm.useMacro(macro)},enabled=!groupRunning && (canTransmit || macro.insertOnly),
                                    contentPadding=PaddingValues(horizontal=10.dp)) {Text(macro.name)}
                                IconButton(onClick={macroEditor=macro},modifier=Modifier.size(36.dp)) {Text("✎")}
                            }
                        }
                    }
                    }
                }
            }
            if(groupRunning) Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Text(tr("正在执行：","Running: ")+snapshot?.commandGroupName,Modifier.weight(1f),style=MaterialTheme.typography.labelMedium,maxLines=1)
                TextButton(onClick={session?.stopSending()}) {Text(tr("停止","Stop"))}
            }
        Surface(Modifier.fillMaxWidth().weight(1f),shape=MaterialTheme.shapes.small,color=MaterialTheme.colorScheme.surfaceContainerLow) {
            if(settings.displayMode == "VT100" && session != null) TerminalGrid(session,settings,paused)
            else {
                val parts=remember(records) { displayParts(records) }
                val list=rememberLazyListState()
                LaunchedEffect(parts.lastOrNull()?.key,paused) {if(!paused && parts.isNotEmpty()) list.scrollToItem(parts.lastIndex)}
                SelectionContainer { LazyColumn(Modifier.fillMaxSize().padding(8.dp),state=list) {
                    items(parts,key={it.key}) { part ->
                        val record=part.record
                        val color=when(record.direction) { Direction.RX -> MaterialTheme.colorScheme.onSurface; Direction.TX -> MaterialTheme.colorScheme.primary; Direction.ERROR -> MaterialTheme.colorScheme.error; Direction.STATUS -> MaterialTheme.colorScheme.secondary }
                        Text(buildString {
                            if(part.start == 0) {
                                if(record.time.isNotEmpty()) append(record.time).append(' ')
                                append(when(record.direction) {Direction.TX -> "→ "; Direction.RX -> "← "; else -> "• "})
                            }
                            append(record.text,part.start,part.end)
                        },
                            color=color,fontSize=settings.fontSize.sp,fontFamily=if(settings.monospace) FontFamily.Monospace else FontFamily.Default)
                    }
                } }
            }
        }

            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
                val canSend=canTransmit && input.isNotEmpty()
                OutlinedTextField(input,{vm.input.value=it},Modifier.weight(1f).heightIn(min=56.dp,max=if(compact) 64.dp else 112.dp)
                    .onPreviewKeyEvent {event ->
                        if(settings.enterToSend && event.key in listOf(Key.Enter,Key.NumPadEnter) && !event.isShiftPressed) {
                            if(event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0 && canSend) vm.send()
                            true
                        } else false
                    },label={Text(if(settings.sendHex) tr("发送 HEX","Send HEX") else tr("发送文本","Send text"))},maxLines=if(compact) 1 else 3,
                    keyboardOptions=KeyboardOptions(imeAction=if(settings.enterToSend) ImeAction.Send else ImeAction.Default),
                    keyboardActions=KeyboardActions(onSend={if(canSend) vm.send()}))
                Button(onClick=vm::send,enabled=canSend,modifier=Modifier.height(56.dp)) {Text(tr("发送","Send"))}
            }
        }
        if(toolsOpen) AlertDialog(onDismissRequest=closeTools,title={Text(tr("调试选项","Debug options"))},text={
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text(tr("连接与会话","Connection and session"),style=MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            Selection(tr("会话","Session"),session?.let { "${it.deviceKey}:${it.portIndex}" } ?: "—",sessions.values.toList(),
                display={"${it.deviceKey}:${it.portIndex} [${sessionLabels.getValue(it.id)}]"}) {vm.select(it.id)}
            OutlinedButton(onClick={closeTools();configDialog=true}) {Text(tr("参数与诊断","Parameters"))}
            OutlinedButton(onClick={session?.let {vm.manager.closePort(it.id)}},enabled=connected) {Text(tr("关闭端口","Close port"))}
        }

                snapshot?.let {
                    Text("${portState(it.state)} · ${it.appliedConfig?.summary() ?: "—"}",style=MaterialTheme.typography.bodySmall)
                    val c=it.counters
                    Text("RX ${c.received} B · TX ${c.sent} B",style=MaterialTheme.typography.bodySmall)
                    Text(tr("队列溢出","Queue overflow")+" ${c.rxOverflow} B · "+tr("显示裁剪","Display trim")+" ${c.displayTrimmed} B",style=MaterialTheme.typography.bodySmall)
                    if(it.flowBlocked) Text(tr("发送受流控阻塞","Transmit blocked by flow control"),color=MaterialTheme.colorScheme.error)
                }
        HorizontalDivider()
        Text(tr("接收与显示","Receive and display"),style=MaterialTheme.typography.titleSmall)
        Choice(tr("字号","Font size"),settings.fontSize.toString(),(6..30).map(Int::toString)) {vm.save(settings.copy(fontSize=it.toInt()))}
        FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            Choice(tr("显示","Display"),settings.displayMode,listOf("TEXT","HEX","SIMPLE","VT100")) {vm.save(settings.copy(displayMode=it))}
            Choice(tr("编码","Encoding"),settings.charset,listOf("UTF-8","GBK","GB18030","US-ASCII","ISO-8859-1","UTF-16LE","UTF-16BE")) {vm.save(settings.copy(charset=it))}
            OutlinedButton(onClick={vm.paused.value=!paused}) {Text(if(paused) tr("继续显示","Resume") else tr("暂停显示","Pause"))}
            OutlinedButton(onClick=vm::clear) {Text(tr("清屏","Clear"))}
            OutlinedButton(onClick=vm::undo) {Text(tr("撤销清屏","Undo clear"))}
            OutlinedButton(onClick={session?.resetCounters()}) {Text(tr("重置计数","Reset counters"))}
            OutlinedButton(onClick={clipboard.setText(AnnotatedString(records.joinToString("") {it.text}))}) {Text(tr("复制当前页","Copy page"))}
            Choice(tr("保存格式","Save format"),exportFormat,listOf("TEXT","HEX","RAW")) {exportFormat=it}
            OutlinedButton(onClick={save.launch("serial-${System.currentTimeMillis()}.${if(exportFormat == "RAW") "bin" else "txt"}")},enabled=session != null) {Text(tr("保存","Save"))}
            OutlinedButton(onClick={scope.launch {try {context.startActivity(Intent.createChooser(vm.createShare(exportFormat),null))} catch(e: Exception) {vm.message.value=e.message}}},enabled=session != null) {Text(tr("分享","Share"))}
        }
        if(settings.bufferBytes == 0) Row(verticalAlignment=Alignment.CenterVertically) {
            TextButton(onClick={vm.page.value=(currentPage+1).coerceAtMost(pages-1)},enabled=currentPage < pages-1) {Text(tr("较早页","Older"))}
            Text("${currentPage+1} / $pages")
            TextButton(onClick={vm.page.value=(currentPage-1).coerceAtLeast(0)},enabled=currentPage > 0) {Text(tr("较新页","Newer"))}
        }

        snapshot?.transfer?.let {p ->
            Row(verticalAlignment=Alignment.CenterVertically) {
                Text("${p.name} · ${p.mode} · ${p.completed}/${if(p.total >= 0) p.total else "?"} · ${p.status}",Modifier.weight(1f),style=MaterialTheme.typography.labelSmall)
                TextButton(onClick={session?.cancelTransfer()},enabled=p.status == "RUNNING") {Text(tr("取消","Cancel"))}
            }
        }
        if(settings.displayMode == "VT100") FlowRow {
            listOf("Esc" to "\u001b","Tab" to "\t","Ctrl+C" to "\u0003","Ctrl+D" to "\u0004","↑" to "\u001b[A","↓" to "\u001b[B","→" to "\u001b[C","←" to "\u001b[D","Home" to "\u001b[H","End" to "\u001b[F","Del" to "\u001b[3~","Enter" to "\r").forEach { (label,key) -> TextButton(enabled=canTransmit,onClick={vm.controlKey(if(session?.terminal?.cursorKeysApplication == true && label in listOf("↑","↓","→","←")) key.replace("[","O") else key)}) {Text(label)} }
        }
        if(settings.showControlLines && snapshot != null) FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            listOf("DTR","RTS").forEach {line ->
                FilterChip(selected=snapshot.lines[line] == true,onClick={session?.control(line,snapshot.lines[line] != true)},
                    enabled=connected && line in snapshot.capabilities.controlLines && !(line == "RTS" && snapshot.appliedConfig?.flowControl == FlowControl.RTS_CTS) && !(line == "DTR" && snapshot.appliedConfig?.flowControl == FlowControl.DTR_DSR),label={Text(line)})
            }
            listOf("CTS","DSR","CD","RI").forEach {line ->Text(" $line:${snapshot.lines[line]?.let {if(it) "1" else "0"} ?: "?"}",style=MaterialTheme.typography.labelSmall)}
            TextButton(onClick={session?.control("BREAK",true,100)},enabled=connected && snapshot.capabilities.breakControl.state == CapabilityState.SUPPORTED) {Text("BREAK 100 ms")}
            FilterChip(selected=breakHeld,onClick={breakHeld=!breakHeld;session?.control("BREAK",breakHeld)},enabled=connected && snapshot.capabilities.breakControl.state == CapabilityState.SUPPORTED,label={Text(tr("保持 BREAK","Hold BREAK"))})
            NumberField("BREAK ms",breakDuration.toLong(),0,9999) {breakDuration=it.toInt()}
            TextButton(onClick={session?.control("BREAK",true,breakDuration)},enabled=connected && snapshot.capabilities.breakControl.state == CapabilityState.SUPPORTED) {Text(tr("定时 BREAK","Timed BREAK"))}
        }

        HorizontalDivider()
        Text(tr("发送与文件","Send and files"),style=MaterialTheme.typography.titleSmall)
        Toggle(tr("回车发送","Send with Enter"),settings.enterToSend) {vm.save(settings.copy(enterToSend=it))}
        Toggle(tr("发送成功后清空输入","Clear input after successful send"),settings.clearAfterSend) {vm.save(settings.copy(clearAfterSend=it))}
        FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            FilterChip(selected=settings.sendHex,onClick={vm.toggleHex(!settings.sendHex)},label={Text("HEX")})
            Choice(tr("结束符","Ending"),settings.ending,listOf("AUTO","CRLF","CR","LF","STX_ETX","NUL","NONE")) {vm.save(settings.copy(ending=it))}
            OutlinedButton(onClick={closeTools();historyDialog=true}) {Text(tr("历史","History"))}
            OutlinedButton(onClick={closeTools();newMacro=true}) {Text(tr("新增宏","Add macro"))}
            OutlinedButton(onClick={closeTools();newCommandGroup=true}) {Text(tr("新建指令组","New command group"))}
            OutlinedButton(onClick={closeTools();macroLibrary=true}) {Text(tr("管理宏","Manage macros"))}
            OutlinedButton(onClick={closeTools();vofaPicker.launch(arrayOf("*/*"))}) {Text(tr("导入 VOFA JSON","Import VOFA JSON"))}
            if(compact && macros.isNotEmpty() && !groupRunning) Selection(tr("宏","Macros"),"—",macros,
                display={"${it.name} [${macroLabels.getValue(it.id)}]"}) {vm.useMacro(it)}
            OutlinedButton(onClick=vm::repeatInput,enabled=canTransmit) {Text(tr("重复发送","Repeat"))}
            OutlinedButton(onClick={session?.stopSending()},enabled=snapshot?.repeating == true || snapshot?.transmitting == true) {Text(tr("停止","Stop"))}
            OutlinedButton(onClick=vm::atCommands,enabled=canTransmit) {Text(tr("11 条 AT 指令","11 AT commands"))}
            OutlinedButton(onClick={closeTools();fileDialog=true},enabled=canTransmit) {Text(tr("发送文件","Send file"))}
        }
            }
        },confirmButton={TextButton(onClick=closeTools) {Text(tr("完成","Done"))}})
    }
    if(configDialog) ParameterDialog(vm,settings,session) {configDialog=false}
    if(historyDialog) {
        val history by vm.history.collectAsStateWithLifecycle()
        AlertDialog(onDismissRequest={historyDialog=false},title={Text(tr("最近 50 条发送历史","Last 50 commands"))},text={LazyColumn {items(history,key={it.id}) {h -> TextButton(onClick={vm.useHistory(h);historyDialog=false}) {Text(h.content.take(200))}}}},confirmButton={TextButton(onClick={historyDialog=false}) {Text(tr("关闭","Close"))}})
    }
    if(newMacro || macroEditor != null) MacroDialog(macroEditor,vm,{newMacro=false;macroEditor=null})
    if(newCommandGroup) VofaMacroDialog(null,vm) {newCommandGroup=false}
    if(macroLibrary) MacroLibraryDialog(macros,connected,{macroLibrary=false},
        {macroLibrary=false;newMacro=true},{macroLibrary=false;vofaPicker.launch(arrayOf("*/*"))},
        {macroLibrary=false;macroEditor=it},{macroLibrary=false;vm.useMacro(it)},
        addGroup={macroLibrary=false;newCommandGroup=true},allowUse=!groupRunning)
    if(fileDialog) AlertDialog(onDismissRequest={fileDialog=false},title={Text(tr("文件发送方式","File transfer mode"))},text={Column {
        FileMode.entries.forEach {mode -> Row(Modifier.clickable {fileMode=mode.name},verticalAlignment=Alignment.CenterVertically) {RadioButton(selected=fileMode == mode.name,onClick={fileMode=mode.name});Text(mode.name)}}
    }},confirmButton={TextButton(onClick={fileDialog=false;picker.launch(arrayOf("*/*"))}) {Text(tr("选择文件","Select file"))}},dismissButton={TextButton(onClick={fileDialog=false}) {Text(tr("取消","Cancel"))}})
}

@Composable private fun TerminalGrid(session: SerialSession, settings: AppSettings, paused: Boolean) {
    val revision by session.revision.collectAsStateWithLifecycle()
    var screen by remember(session.id) {mutableStateOf(session.terminal.snapshot())}
    LaunchedEffect(revision,paused) {if(!paused) screen=session.terminal.snapshot()}
    val colors=listOf(Color.Black,Color(0xffcc3333),Color(0xff22aa55),Color(0xffbbaa33),Color(0xff4477ee),Color(0xffbb55cc),Color(0xff22aaaa),Color(0xffdddddd),Color(0xff777777),Color(0xffff7777),Color(0xff55ff88),Color(0xffffff66),Color(0xff88aaff),Color(0xffff88ff),Color(0xff77ffff),Color.White)
    val text=remember(screen) { buildAnnotatedString {
        screen.rows.forEachIndexed {y,row -> row.forEachIndexed {x,cell ->
            val reverse=cell.inverse xor (y == screen.cursorRow && x == screen.cursorColumn)
            withStyle(SpanStyle(color=colors[(if(reverse) cell.background else cell.foreground).coerceIn(0,15)],background=colors[(if(reverse) cell.foreground else cell.background).coerceIn(0,15)],fontWeight=if(cell.bold) FontWeight.Bold else FontWeight.Normal,textDecoration=if(cell.underline) TextDecoration.Underline else null)) {append(cell.char)}
        }; if(y < screen.rows.lastIndex) append('\n') }
    } }
    SelectionContainer {Box(Modifier.fillMaxSize().background(Color.Black).verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(8.dp)) {Text(text,fontFamily=FontFamily.Monospace,fontSize=settings.fontSize.sp,lineHeight=(settings.fontSize+2).sp,softWrap=false)} }
}

@Composable private fun capability(state: CapabilityState) = when(state) {
    CapabilityState.SUPPORTED -> tr("支持","Supported")
    CapabilityState.HARDWARE_UNSUPPORTED -> tr("硬件不支持","Hardware unsupported")
    CapabilityState.DRIVER_UNIMPLEMENTED -> tr("驱动未实现","Driver not implemented")
    CapabilityState.UNKNOWN -> tr("能力未知","Unknown capability")
    CapabilityState.READ_FAILED -> tr("读取失败","Read failed")
}
@Composable private fun ParameterDialog(vm: SerialViewModel, settings: AppSettings, session: SerialSession?, dismiss: ()->Unit) {
    val snapshot=session?.state?.collectAsStateWithLifecycle()?.value
    var draft by remember {mutableStateOf(snapshot?.appliedConfig ?: settings.config)}
    val validDraft=runCatching {snapshot?.capabilities?.validate(draft) ?: draft.validate()}.isSuccess
    AlertDialog(onDismissRequest=dismiss,title={Text(tr("参数与能力","Parameters and capabilities"))},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text(tr("待应用","Pending")+": ${draft.summary()}")
        Text(tr("已应用","Applied")+": ${snapshot?.appliedConfig?.summary() ?: "—"}")
        ConfigEditor(draft,snapshot?.capabilities) {draft=it}
        Text(tr("波特率报告","Baud-rate report"),style=MaterialTheme.typography.titleSmall)
        val report=snapshot?.baudReport
        val diagnostics=snapshot?.diagnostics
        Text(tr("请求值","Requested")+": ${report?.requested ?: draft.baudRate}")
        Text(tr("驱动报告值","Driver reported")+": ${report?.driverReported ?: capability(snapshot?.capabilities?.baudRead?.state ?: CapabilityState.UNKNOWN)}")
        Text(tr("驱动分频推算值","Driver divisor estimate")+": ${report?.divisorEstimated?.let { "%.2f".format(it) } ?: "—"}")
        report?.estimateSource?.let {Text(it,style=MaterialTheme.typography.bodySmall)}
        report?.driverReportSource?.let {Text(it,style=MaterialTheme.typography.bodySmall)}
        report?.estimatedDeviationPercent?.let {Text(tr("推算偏差","Estimated deviation")+": "+"%+.4f%%".format(it),color=if(report.estimatedOutsideTolerance) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)}
        if(snapshot?.driver == "Ch34xSerialDriver") {
            val candidate=Ch341Divisor.linuxEstimate(draft.baudRate)
            Text(tr("待应用速率的最近分频推算","Nearest divisor estimate for pending baud rate")+": ${candidate?.let { "%.2f".format(it) } ?: "—"}")
        }
        val caps=snapshot?.capabilities ?: SerialCapabilities()
        OutlinedButton(onClick=vm::refreshDiagnostics,enabled=snapshot?.state == PortState.OPEN && snapshot.transfer?.status != "RUNNING") {Text(tr("刷新诊断与 GPIO","Refresh diagnostics and GPIO"))}
        diagnostics?.chip?.let {Text(tr("芯片识别","Chip identification")+": $it")}
        diagnostics?.sdkVersion?.let {Text("WCH SDK package: $it")}
        listOf(tr("起始位调整","Start-bit adjustment") to caps.startBits,tr("主频回读","Clock readback") to caps.clockRead,
            tr("UART 错误状态","UART errors") to caps.uartErrors,"GPIO" to caps.gpio,tr("设备复位","Device reset") to caps.reset).forEach { (name,cap) ->
            OutlinedButton(onClick={},enabled=false) {Text("$name: ${capability(cap.state)}")}
            if(cap.detail.isNotBlank()) Text(cap.detail,style=MaterialTheme.typography.bodySmall)
        }
        diagnostics?.clock?.value?.let {Text(tr("主频（SDK 原始值）","Clock (raw SDK value)")+": ${it.frequency} · ${it.switchEnabled} · ${it.status}")}
        diagnostics?.errors?.forEach { (name,reading) ->
            Text("$name: ${reading.value ?: capability(reading.capability.state)}")
            if(reading.capability.detail.isNotBlank()) Text(reading.capability.detail,style=MaterialTheme.typography.bodySmall)
        }
        Text(tr("错误计数来自 SDK 累积状态。未获取时不能显示为 0。","Error counts come from SDK cumulative state. Unavailable counts must not appear as zero."))
        diagnostics?.gpio?.value?.forEach {pin ->
            HorizontalDivider()
            Text("GPIO ${pin.index}")
            Toggle(tr("启用 GPIO","Enable GPIO"),pin.enabled,enabled=snapshot?.state == PortState.OPEN) {vm.configureGpio(pin,enabled=it)}
            Choice(tr("方向","Direction"),if(pin.output) "OUT" else "IN",listOf("IN","OUT"),enabled=pin.enabled && snapshot?.state == PortState.OPEN) {vm.configureGpio(pin,output=it == "OUT")}
            Text(tr("当前电平","Current level")+": ${if(pin.high) "1" else "0"}")
            if(pin.output) Toggle(tr("输出高电平","Output high"),pin.high,enabled=pin.enabled && snapshot?.state == PortState.OPEN) {vm.writeGpio(pin,it)}
        }
    }},confirmButton={TextButton(onClick={if(session != null) vm.apply(draft) else vm.save(settings.copy(config=draft))},enabled=validDraft && (session == null || snapshot?.state == PortState.OPEN) && snapshot?.transfer?.status != "RUNNING") {Text(tr("应用参数","Apply"))}},dismissButton={TextButton(onClick=dismiss) {Text(tr("关闭","Close"))}})
}

@Composable private fun MacroDialog(original: MacroEntity?, vm: SerialViewModel, dismiss: ()->Unit) {
    if(original != null && VofaCommands.isSequence(original.content)) {VofaMacroDialog(original,vm,dismiss);return}
    var name by remember {mutableStateOf(original?.name ?: "")}; var content by remember {mutableStateOf(original?.content ?: "")}
    var hex by remember {mutableStateOf(original?.hex ?: false)}; var insert by remember {mutableStateOf(original?.insertOnly ?: false)}
    var row by remember {mutableIntStateOf(original?.row ?: 0)}; var delay by remember {mutableIntStateOf(original?.delayMs ?: 0)}
    AlertDialog(onDismissRequest=dismiss,title={Text(tr("编辑宏","Edit macro"))},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(name,{name=it},label={Text(tr("名称","Name"))})
        OutlinedTextField(content,{content=it},label={Text(tr("内容","Content"))},maxLines=6)
        Toggle("HEX",hex) {hex=it}; Toggle(tr("仅插入输入框","Insert only"),insert) {insert=it}
        Choice(tr("行","Row"),(row+1).toString(),(1..5).map(Int::toString)) {row=it.toInt()-1}
        NumberField(tr("宏行延迟 ms","Macro line delay, ms"),delay.toLong(),0,9999) {delay=it.toInt()}
        if(original != null) OutlinedButton(onClick={vm.deleteMacro(original.id);dismiss()}) {Text(tr("删除宏","Delete macro"))}
    }},confirmButton={TextButton(enabled=name.isNotBlank(),onClick={vm.saveMacro(MacroEntity(original?.id ?: SerialPresets.newId(),name,content,hex,insert,row,delay));dismiss()}) {Text(tr("保存","Save"))}},dismissButton={TextButton(onClick=dismiss) {Text(tr("取消","Cancel"))}})
}

