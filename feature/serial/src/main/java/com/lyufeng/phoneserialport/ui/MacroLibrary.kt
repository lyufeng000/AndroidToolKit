@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.lyufeng.phoneserialport.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.Alignment
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.lyufeng.phoneserialport.data.*
import com.lyufeng.phoneserialport.core.SerialPresets
import com.lyufeng.phoneserialport.terminal.HexCodec
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

@Composable internal fun MacroLibraryDialog(
    macros: List<MacroEntity>, connected: Boolean, dismiss: ()->Unit,
    add: ()->Unit, importCommands: ()->Unit, edit: (MacroEntity)->Unit, use: (MacroEntity)->Unit,
    addGroup: ()->Unit = {}, allowUse: Boolean = true,
) {
    var search by remember {mutableStateOf("")}
    AlertDialog(onDismissRequest=dismiss,title={Text(tr("快捷宏","Quick macros"))},text={Column {
        OutlinedTextField(search,{search=it},label={Text(tr("搜索宏","Search macros"))},singleLine=true,modifier=Modifier.fillMaxWidth())
        LazyColumn(Modifier.heightIn(max=420.dp)) {
            items(macros.filter {it.name.contains(search,ignoreCase=true)},key={it.id}) {macro ->
                Row(Modifier.fillMaxWidth()) {
                    TextButton(onClick={use(macro)},enabled=allowUse && (connected || macro.insertOnly),modifier=Modifier.weight(1f)) {Text(macro.name)}
                    TextButton(onClick={edit(macro)}) {Text(tr("编辑","Edit"))}
                }
            }
        }
    }},confirmButton={FlowRow {
        TextButton(onClick=addGroup) {Text(tr("新建指令组","New command group"))}
        TextButton(onClick=add) {Text(tr("新增宏","Add macro"))}
        TextButton(onClick=dismiss) {Text(tr("关闭","Close"))}
    }},dismissButton={TextButton(onClick=importCommands) {Text(tr("导入 VOFA JSON","Import VOFA JSON"))}})
}

@Composable internal fun VofaMacroDialog(original: MacroEntity?,vm: SerialViewModel,dismiss: ()->Unit) {
    val draftId=remember(original?.id) {original?.id ?: SerialPresets.newId()}
    var root by remember(draftId) {mutableStateOf(original?.let {VofaCommands.command(it.content)} ?: VofaCommand("",group=true,delayMs=0))}
    var path by remember(draftId) {mutableStateOf(emptyList<Int>())}
    var row by remember(draftId) {mutableIntStateOf(original?.row ?: 0)}
    var saving by remember(draftId) {mutableStateOf(false)}
    var failure by remember(draftId) {mutableStateOf<String?>(null)}
    var selectMacro by remember(draftId) {mutableStateOf(false)}
    val invalidNumbers=remember(draftId) {mutableStateMapOf<String,Boolean>()}
    val numbersValid=invalidNumbers.values.none {it}
    val canNavigate=numbersValid && !saving
    val macros by vm.macros.collectAsState()
    val node=path.fold(root) {parent,index -> parent.children[index]}
    fun replace(updated: VofaCommand) {
        fun update(parent: VofaCommand,level: Int): VofaCommand = if(level == path.size) updated
            else parent.copy(children=parent.children.mapIndexed {index,child -> if(index == path[level]) update(child,level+1) else child})
        root=update(root,0)
        failure=null
    }
    val validation=commandValidationError(root)
    val once=tr("单次","Once")
    val counted=tr("指定次数","Set count")
    val unlimited=tr("无限循环","Repeat forever")
    val saveFailure=tr("保存失败，请重试：","Save failed. Try again: ")
    val deleteFailure=tr("删除失败，请重试：","Delete failed. Try again: ")
    val copyFailure=tr("无法复制该宏：","Cannot copy this macro: ")
    AlertDialog(onDismissRequest={if(!saving) dismiss()},title={Text(if(original == null) tr("新建指令组","New command group") else tr("编辑快捷宏","Edit quick macro"))},text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            if(validation != null) Text(validation,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
            failure?.let {Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}
            if(!numbersValid) Text(tr("请先修正延时和重复次数。","Correct the delay and repeat count first."),color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
            if(path.isNotEmpty()) TextButton(enabled=canNavigate,onClick={path=path.dropLast(1)}) {Text(tr("返回上一级","Back to parent"))}
            OutlinedTextField(node.name,{replace(node.copy(name=it))},label={Text(tr("名称","Name"))},modifier=Modifier.fillMaxWidth(),enabled=!saving)
            OutlinedTextField(node.note,{replace(node.copy(note=it))},label={Text(tr("备注","Note"))},modifier=Modifier.fillMaxWidth(),enabled=!saving)
            if(!node.group) {
                val decoded=runCatching {Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(HexCodec.decode(node.hex))).toString()}.getOrNull()
                var hexMode by remember(path) {mutableStateOf(node.hexView || decoded == null)}
                Toggle(tr("HEX 编辑","Edit as HEX"),hexMode,enabled=!saving && decoded != null) {hexMode=it;replace(node.copy(hexView=it))}
                OutlinedTextField(if(hexMode) node.hex else decoded.orEmpty(),{
                    replace(node.copy(hex=if(hexMode) it else HexCodec.encode(it.toByteArray(Charsets.UTF_8)),hexView=hexMode))
                },label={Text(if(hexMode) tr("发送字节 HEX","Send bytes as HEX") else tr("发送文本（含结束符）","Text including ending"))},modifier=Modifier.fillMaxWidth(),maxLines=4,enabled=!saving)
                if(!hexMode) Text("HEX: ${node.hex}",style=MaterialTheme.typography.bodySmall)
            }
            if(path.isNotEmpty()) Toggle(tr("加入命令组","Include in group"),node.included,enabled=!saving) {replace(node.copy(included=it))}
            key(path,"delay") {
                GroupNumberField(if(node.group) tr("循环间隔 ms","Group delay, ms") else tr("发送后延时 ms","Delay after send, ms"),node.delayMs,0,86_400_000,!saving,
                    invalid={invalidNumbers["delay"]=it}) {replace(node.copy(delayMs=it))}
            }
            if(node.group) {
                Choice(tr("循环方式","Repeat mode"),when(node.repeats) {-1 -> unlimited;1 -> once;else -> counted},listOf(once,counted,unlimited),enabled=canNavigate) {
                    replace(node.copy(repeats=when(it) {unlimited -> -1;once -> 1;else -> node.repeats.coerceAtLeast(2)}))
                }
                if(node.repeats > 1) key(path,"count") {
                    GroupNumberField(tr("循环次数","Repeat count"),node.repeats.toLong(),1,1_000_000,!saving,
                        invalid={invalidNumbers["count"]=it}) {replace(node.copy(repeats=it.toInt()))}
                }
                Text(tr("第一步立即发送。每步发送完成后等待设定延时。最后一步的延时也会执行。","The first step sends immediately. Each step waits after sending, including the last step."),style=MaterialTheme.typography.bodySmall)
            } else key(path,"count") {
                GroupNumberField(tr("重复次数，-1 为无限","Repeat count, -1 means unlimited"),node.repeats.toLong(),-1,1_000_000,!saving,
                    invalid={invalidNumbers["count"]=it},allowZero=false) {replace(node.copy(repeats=it.toInt()))}
            }
            if(path.isEmpty()) Choice(tr("行","Row"),(row+1).toString(),(1..5).map(Int::toString),enabled=!saving) {row=it.toInt()-1}
            if(node.group) {
                HorizontalDivider()
                node.children.forEachIndexed {index,child ->
                    Column(Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                            TextButton(enabled=canNavigate,onClick={path=path+index},modifier=Modifier.weight(1f)) {Text("${index+1}. ${child.name}")}
                            TextButton(onClick={
                                val children=node.children.toMutableList();children[index]=children[index-1];children[index-1]=child
                                replace(node.copy(children=children))
                            },enabled=canNavigate && index > 0) {Text("↑")}
                            TextButton(onClick={
                                val children=node.children.toMutableList();children[index]=children[index+1];children[index+1]=child
                                replace(node.copy(children=children))
                            },enabled=canNavigate && index < node.children.lastIndex) {Text("↓")}
                        }
                        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                            Text((if(child.included) tr("已启用","Enabled") else tr("未启用","Disabled"))+" · ${child.delayMs} ms",Modifier.weight(1f),style=MaterialTheme.typography.bodySmall)
                            TextButton(enabled=canNavigate,onClick={replace(node.copy(children=node.children.filterIndexed {i,_ -> i != index}))}) {Text(tr("删除","Delete"))}
                        }
                    }
                }
                val newName=tr("新命令","New command")
                TextButton(enabled=canNavigate,onClick={val index=node.children.size;replace(node.copy(children=node.children+VofaCommand(newName)));path=path+index}) {Text(tr("添加步骤","Add step"))}
                TextButton(onClick={selectMacro=true},enabled=canNavigate && macros.isNotEmpty()) {Text(tr("从已有宏添加","Add from saved macro"))}
            }
            if(path.isEmpty() && original != null) TextButton(enabled=!saving,onClick={
                saving=true;failure=null
                vm.deleteMacro(original.id,onDeleted=dismiss,onFailure={saving=false;failure=deleteFailure+it})
            }) {Text(tr("删除宏","Delete macro"))}
        }
    },confirmButton={TextButton(enabled=validation == null && numbersValid && !saving,onClick={
        saving=true;failure=null
        val macro=runCatching {(original ?: MacroEntity(id=draftId,name=root.name,content="")).copy(name=root.name,content=VofaCommands.content(root),row=row)}
        macro.fold(onSuccess={vm.saveMacro(it,onSaved=dismiss,onFailure={detail -> saving=false;failure=saveFailure+detail})},
            onFailure={saving=false;failure=saveFailure+it.message.orEmpty()})
    }) {Text(if(saving) tr("正在保存…","Saving…") else tr("保存","Save"))}},
        dismissButton={TextButton(enabled=!saving,onClick=dismiss) {Text(tr("取消","Cancel"))}})
    if(selectMacro) {
        var search by remember {mutableStateOf("")}
        AlertDialog(onDismissRequest={selectMacro=false},title={Text(tr("选择已有宏","Choose saved macro"))},text={Column {
            OutlinedTextField(search,{search=it},label={Text(tr("搜索宏","Search macros"))},modifier=Modifier.fillMaxWidth(),singleLine=true)
            LazyColumn(Modifier.heightIn(max=360.dp)) {
                items(macros.filter {it.name.contains(search,ignoreCase=true)},key={it.id}) {macro ->
                    TextButton(onClick={
                        runCatching {MacroCommands.copyStep(macro,vm.settings.value)}.fold(
                            onSuccess={replace(node.copy(children=node.children+it));selectMacro=false},
                            onFailure={failure=copyFailure+it.message.orEmpty();selectMacro=false})
                    },modifier=Modifier.fillMaxWidth()) {Text(macro.name)}
                }
            }
        }},confirmButton={TextButton(onClick={selectMacro=false}) {Text(tr("取消","Cancel"))}})
    }
}

@Composable private fun GroupNumberField(
    label: String,value: Long,minimum: Long,maximum: Long,enabled: Boolean,
    invalid: (Boolean)->Unit,allowZero: Boolean = true,change: (Long)->Unit,
) {
    var text by remember {mutableStateOf(value.toString())}
    val number=text.toLongOrNull()
    val valid=number != null && number in minimum..maximum && (allowZero || number != 0L)
    OutlinedTextField(text,onValueChange={
        text=it
        val parsed=it.toLongOrNull()
        val accepted=parsed != null && parsed in minimum..maximum && (allowZero || parsed != 0L)
        invalid(!accepted)
        if(accepted) change(parsed!!)
    },modifier=Modifier.fillMaxWidth(),label={Text(label)},singleLine=true,isError=!valid,enabled=enabled,
        keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),
        supportingText={if(!valid) Text(if(allowZero) "$minimum–$maximum" else "-1 / 1–$maximum")})
}

@Composable private fun commandValidationError(root: VofaCommand): String? {
    val error=runCatching {VofaCommands.validate(root)}.exceptionOrNull()?.message ?: return null
    return when(error) {
        "Command name is empty or too long" -> tr("请填写所有步骤和指令组的名称，每个名称最多 256 个字符。","Name each step and group. Each name must use at most 256 characters.")
        "Command group is empty" -> tr("指令组至少需要一个步骤。","Add at least one step to the group.")
        "Command group has no enabled steps" -> tr("指令组至少需要一个已启用步骤。","Enable at least one step in the group.")
        "Invalid HEX character" -> tr("HEX 只能包含 0–9、A–F 和空格。","HEX accepts 0–9, A–F and spaces.")
        "HEX requires two digits per byte" -> tr("HEX 每个字节需要两位字符。","Each HEX byte needs two digits.")
        "Invalid command bytes" -> tr("每条指令需要 1 字节至 1 MiB 的内容。","Each command needs 1 byte to 1 MiB of data.")
        "Invalid command delay or repeat count" -> tr("延时范围为 0–86400000 ms。重复次数为 1–1000000，或使用 -1 无限循环。","Delay must be 0–86400000 ms. Repeat count must be 1–1000000, or -1 for unlimited repeats.")
        "VOFA command tree is too large" -> tr("指令组最多包含 2048 项，嵌套最多 16 层。","Command groups support at most 2048 nodes and 16 nested levels.")
        else -> tr("指令组内容无效，请检查步骤。","Invalid command group. Check its steps.")
    }
}
