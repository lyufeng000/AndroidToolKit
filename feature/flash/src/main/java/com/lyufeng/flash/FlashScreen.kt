package com.lyufeng.flash

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlashScreen(onReturnHome: () -> Unit, vm: FlashViewModel = viewModel()) {
    val state by FlashCoordinator.state.collectAsStateWithLifecycle()
    val enabled = !state.busy && !vm.importing && !vm.permissionPending
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.import(uri, false) }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> if (uri != null) vm.import(uri, true) }
    val logPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) scope.launch {
            runCatching { withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { out ->
                out.write(state.logs.joinToString("\n")); state.error?.let { out.write("\n$it") }
            } ?: error("无法写入日志文件") } }.onFailure { vm.error = "日志导出失败：${it.message}" }
        }
    }
    var review by remember { mutableStateOf<FlashRequest?>(null) }
    var help by remember { mutableStateOf(false) }
    var licenses by remember { mutableStateOf(false) }
    var licenseFile by remember { mutableStateOf("NOTICE.txt") }
    var licenseText by remember { mutableStateOf("") }
    val licenseFiles = remember { context.assets.list("licenses").orEmpty().toList() }
    LaunchedEffect(licenses, licenseFile) {
        if (licenses) licenseText = withContext(Dispatchers.IO) { context.assets.open("licenses/$licenseFile").bufferedReader().use { it.readText() } }
    }
    BackHandler(onBack = onReturnHome)
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
        Scaffold(
            topBar = { TopAppBar(title = { Text("代码烧入") }, navigationIcon = {
                TextButton(onClick = onReturnHome) { Text("返回") }
            }, actions = { TextButton(onClick = { help = true }) { Text("说明") } }) },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Section("1  选择烧录路线") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ProbeKind.entries.forEach { kind -> FilterChip(selected = vm.probeKind == kind, enabled = enabled,
                            onClick = { vm.selectKind(kind) }, label = { Text(kind.label) }) }
                    }
                    SelectField("USB 烧录器", vm.probe?.takeIf { it.kind == vm.probeKind }?.label ?: "请选择设备",
                        vm.probes.filter { it.kind == vm.probeKind }, { it.label }, enabled) { vm.probe = it }
                    TextButton(onClick = vm::refresh, enabled = enabled) { Text("刷新设备") }
                    if (vm.probes.none { it.kind == vm.probeKind }) Text("未发现 ${vm.probeKind.label}。请连接 OTG 和烧录器。", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Wire.entries.forEach { wire -> FilterChip(selected = vm.wire == wire, enabled = enabled && (vm.target == null || wire in vm.target!!.wires),
                            onClick = { vm.wire = wire }, label = { Text(wire.label) }) }
                    }
                }
                Section("2  选择代码或固件") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { folderPicker.launch(null) }, enabled = enabled) { Text("选择文件夹") }
                        OutlinedButton(onClick = { filePicker.launch(arrayOf("*/*")) }, enabled = enabled) { Text("选择 ZIP / 固件") }
                    }
                    if (vm.importing) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("正在导入项目…") }
                    vm.project?.let { project ->
                        Text(project.label, fontWeight = FontWeight.Medium)
                        Text("${project.firmware.size} 个固件 · ${project.sources.size} 个源文件", style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = !vm.sourceMode, enabled = enabled, onClick = { vm.sourceMode = false }, label = { Text("直接烧录固件") })
                            FilterChip(selected = vm.sourceMode, enabled = enabled, onClick = { vm.sourceMode = true }, label = { Text("先编译源码") })
                        }
                        if (!vm.sourceMode) {
                            SelectField("待烧录固件", vm.firmware?.relativeTo(project.root)?.invariantSeparatorsPath ?: "请选择固件文件",
                                project.firmware, { it.relativeTo(project.root).invariantSeparatorsPath }, enabled) { vm.firmware = it }
                            if (project.firmware.isEmpty()) Text("此项目没有已编译固件。请选择“先编译源码”。")
                        } else {
                            SelectField("编译配置", vm.plan?.name ?: "通用源码配置", listOf<BuildPlan?>(null) + project.buildPlans,
                                { it?.name ?: "通用源码配置" }, enabled) { vm.plan = it }
                            if (vm.plan == null) {
                                SelectField("CPU", vm.cpu, SourceProjects.cpus.toList(), { it }, enabled) { vm.cpu = it }
                                SelectField("链接脚本", vm.linker?.relativeTo(project.root)?.invariantSeparatorsPath ?: "请选择 .ld 文件",
                                    remember(project) { project.root.walkTopDown().filter { it.isFile && it.extension == "ld" }.toList() },
                                    { it.relativeTo(project.root).invariantSeparatorsPath }, enabled) { vm.linker = it }
                                OutlinedTextField(vm.defines, { vm.defines = it }, label = { Text("宏定义，以 ; 分隔") }, enabled = enabled,
                                    modifier = Modifier.fillMaxWidth(), placeholder = { Text("USE_HAL_DRIVER;STM32F103xB") })
                            } else Text("${vm.plan!!.cpu} · ${vm.plan!!.sources.size} 个源文件", style = MaterialTheme.typography.bodySmall)
                            project.buildNote?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
                Section("3  选择目标设备类型") {
                    SelectField("芯片系列 / 目标配置", vm.target?.label ?: "请选择芯片系列", vm.targets.filter { it.accepts(vm.probeKind) },
                        { "${it.label} · ${it.id}" }, enabled, searchable = true, selectedLabel = vm.target?.let { "${it.label} · ${it.id}" }) { vm.selectTarget(it) }
                    if (!vm.sourceMode && vm.firmware?.extension.equals("bin", true)) {
                        OutlinedTextField(vm.address, { vm.address = it }, enabled = enabled, singleLine = true,
                            label = { Text("BIN 写入地址") }, modifier = Modifier.fillMaxWidth())
                    }
                    OutlinedTextField(vm.speed, { vm.speed = it }, enabled = enabled, singleLine = true,
                        label = { Text("接口速率（kHz）") }, modifier = Modifier.fillMaxWidth())
                    CheckRow("连接时保持复位（需要 NRST 接线）", vm.underReset, enabled) { vm.underReset = it }
                    CheckRow("校验后复位并运行", vm.resetAfter, enabled) { vm.resetAfter = it }
                }
                (vm.error ?: state.error)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (vm.permissionPending) Text("请在系统窗口中授予烧录器 USB 访问权限")
                if (state.busy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(state.phase.label)
                    OutlinedButton(onClick = FlashCoordinator::cancel, modifier = Modifier.fillMaxWidth()) { Text("停止操作") }
                } else {
                    Button(onClick = { runCatching { vm.request() }.onSuccess { review = it }.onFailure { vm.error = it.message } },
                        enabled = enabled, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("开始烧录") }
                    if (state.phase != FlashPhase.IDLE) Text(state.phase.label, fontWeight = FontWeight.SemiBold)
                }
                if (state.logs.isNotEmpty()) Section("操作日志") {
                    TextButton(onClick = { logPicker.launch("flash-log.txt") }) { Text("导出日志") }
                    SelectionContainer { Text(state.logs.joinToString("\n"), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
        review?.let { request -> AlertDialog(onDismissRequest = { review = null }, title = { Text("检查烧录配置") }, text = {
            Text("烧录器：${request.options.probe.label}\n接口：${request.options.wire.label}\n目标：${request.options.target.label}\n文件：${request.image?.name ?: request.plan?.name}\n${if (request.image?.extension.equals("bin", true)) "地址：${request.options.address}\n" else ""}烧录会擦除写入区域。请确认目标型号和接线，并保持设备供电。")
        }, confirmButton = { TextButton(onClick = { review = null; vm.begin(request) }) { Text("烧录并校验") } },
            dismissButton = { TextButton(onClick = { review = null }) { Text("返回检查") } }) }
        if (help) AlertDialog(onDismissRequest = { help = false }, title = { Text("代码烧入说明") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("接线：手机 → USB OTG → 烧录器 → SWD / JTAG → 单片机。SWD 通常需要 SWDIO、SWCLK、GND 和目标电压参考。连接时复位还需要 NRST。请按烧录器手册接线。")
                Text("固件：BIN 需要填写写入地址。HEX、ELF、AXF、S19 和 SREC 使用文件中的地址。多文件项目需要明确选择一个固件。")
                Text("源码：支持 toolkit-build.json、可识别的 STM32CubeIDE 工程，以及通用源码配置。需要 GNU 启动文件和 .ld 链接脚本。编译器不执行工程内的安装脚本或构建脚本。")
                Text("Keil、PlatformIO 和 CMake 工程可以通过 toolkit-build.json 提供源文件、头文件目录、宏定义和链接脚本。工程依赖需要放在所选目录内。专用编译器、ARMASM、Scatter 文件和外部 Flash 初始化脚本不自动转换。")
                Text("烧录内核：OpenOCD 0.12.0。源码编译：Clang / LLD 21.1.8。首次编译需要准备头文件与标准库。无需 root。")
                TextButton(onClick = { licenses = true }) { Text("开源许可") }
            }
        }, confirmButton = { TextButton(onClick = { help = false }) { Text("关闭") } })
        if (licenses) AlertDialog(onDismissRequest = { licenses = false }, title = { Text("开源许可") }, text = {
            Column {
                SelectField("许可证", licenseFile, licenseFiles, { it }, true) { licenseFile = it }
                SelectionContainer { Text(licenseText, modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall) }
            }
        }, confirmButton = { TextButton(onClick = { licenses = false }) { Text("关闭") } })
    }
}

@Composable
private fun Section(title: String, body: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        body()
    } }
}

@Composable
private fun CheckRow(label: String, checked: Boolean, enabled: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled) { change(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onCheckedChange = change, enabled = enabled)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun <T> SelectField(label: String, current: String, choices: List<T>, display: (T) -> String, enabled: Boolean,
    searchable: Boolean = false, selectedLabel: String? = null, choose: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = { open = true; query = "" }, enabled = enabled && choices.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
            Text(selectedLabel ?: current, modifier = Modifier.weight(1f)); Text("⌄")
        }
    }
    if (open) AlertDialog(onDismissRequest = { open = false }, title = { Text(label) }, text = {
        Column {
            if (searchable || choices.size > 8) OutlinedTextField(query, { query = it }, singleLine = true,
                label = { Text("搜索") }, modifier = Modifier.fillMaxWidth())
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                items(choices.filter { display(it).contains(query, ignoreCase = true) }) { item ->
                    TextButton(onClick = { choose(item); open = false }, modifier = Modifier.fillMaxWidth()) {
                        Text(display(item), modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { open = false }) { Text("关闭") } })
}
