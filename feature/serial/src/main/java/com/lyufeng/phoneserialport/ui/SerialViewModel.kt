package com.lyufeng.phoneserialport.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lyufeng.phoneserialport.SerialApplication
import com.lyufeng.phoneserialport.core.*
import com.lyufeng.phoneserialport.data.*
import com.lyufeng.phoneserialport.serial.SerialSession
import com.lyufeng.phoneserialport.terminal.*
import com.lyufeng.phoneserialport.transfer.FileMode
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.io.OutputStream
import java.nio.charset.Charset

enum class ReturnHomeState { IDLE, CLOSING, READY }

data class DisplayRecord(val key: Long, val direction: Direction, val text: String, val time: String)

@OptIn(ExperimentalCoroutinesApi::class)
class SerialViewModel(application: Application) : AndroidViewModel(application) {
    private val app=application as SerialApplication
    val manager=app.devices
    private val storage=app.container
    val message=MutableStateFlow<String?>(null)
    private val mutableReturnHome = MutableStateFlow(ReturnHomeState.IDLE)
    val returnHome: StateFlow<ReturnHomeState> = mutableReturnHome.asStateFlow()
    fun requestReturnHome() {
        if (!mutableReturnHome.compareAndSet(ReturnHomeState.IDLE, ReturnHomeState.CLOSING)) return
        viewModelScope.launch {
            try {
                manager.disconnectForHome()
                mutableReturnHome.value = ReturnHomeState.READY
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                message.value = e.message ?: "Disconnect failed"
                mutableReturnHome.value = ReturnHomeState.IDLE
            }
        }
    }
    fun consumeReturnHome(): Boolean = mutableReturnHome.compareAndSet(ReturnHomeState.READY, ReturnHomeState.IDLE)
    val settings=storage.settings.settings.catch { message.value=it.message; emit(AppSettings()) }.stateIn(viewModelScope,SharingStarted.Eagerly,AppSettings())
    val macros=storage.dao.macros().stateIn(viewModelScope,SharingStarted.Eagerly,emptyList())
    val history=storage.dao.history().stateIn(viewModelScope,SharingStarted.Eagerly,emptyList())
    val mappings=storage.dao.observeMappings().stateIn(viewModelScope,SharingStarted.Eagerly,emptyList())
    val deviceMemories=storage.dao.observeMemories().stateIn(viewModelScope,SharingStarted.Eagerly,emptyList())
    val input=MutableStateFlow("")
    val notificationRevision=MutableStateFlow(0L)
    var afterNotificationPermission: (() -> Unit)? = null
    fun notificationPermissionFinished() {notificationRevision.value++;val next=afterNotificationPermission;afterNotificationPermission=null;next?.invoke()}
    val activeId=MutableStateFlow<String?>(null)
    val page=MutableStateFlow(0)
    val paused=MutableStateFlow(false)
    val active=combine(manager.sessions,activeId) { all,id -> all[id] }.stateIn(viewModelScope,SharingStarted.Eagerly,null)
    val display=MutableStateFlow<List<DisplayRecord>>(emptyList())
    val pageCount=MutableStateFlow(1)
    init {
        viewModelScope.launch {
            manager.sessions.collect { all ->
                val old=all[activeId.value]
                if(old == null || old.state.value.state !in listOf(PortState.OPEN,PortState.OPENING)) activeId.value=all.values.lastOrNull { it.state.value.state == PortState.OPEN }?.id ?: all.keys.lastOrNull()
            }
        }
        viewModelScope.launch {
            combine(active,settings,page,paused) { session,options,p,hold -> arrayOf(session,options,p,hold) }
                .flatMapLatest { values ->
                    val session=values[0] as SerialSession?; val options=values[1] as AppSettings; val p=values[2] as Int; val hold=values[3] as Boolean
                    if(session == null) flowOf(emptyList())
                    else {
                        var renderedVersion: Long? = null
                        var renderedState: PortState? = null
                        var renderedRevision: Long? = null
                        var displayFailureReported=false
                        var nextReplayNanos=0L
                        val revisions=if(options.bufferBytes == 0 && p == 0) merge(session.revision,flow {
                            while(currentCoroutineContext().isActive) { delay(1000); emit(session.revision.value) }
                        }) else session.revision
                        revisions.map { if(hold) null else withContext(Dispatchers.IO) {
                            pageCount.value=session.store.pageCount()
                            val disk=options.bufferBytes == 0
                            val version=session.store.contextVersion()
                            val portState=session.state.value.state
                            val revision=session.revision.value
                            val now=System.nanoTime()
                            // Keep an older page stable as new events arrive. Bound live-page
                            // replay to at most 1 Hz and reduce it further for slow histories.
                            if(disk && renderedVersion == version && renderedState == portState &&
                                (p > 0 || revision == renderedRevision || now < nextReplayNanos)) null
                            else try {
                                val snapshot=session.store.pageWithContext(if(disk) p else 0)
                                val replayContext=currentCoroutineContext()
                                val records=render(snapshot.events,options,TextRenderContext(
                                    replayPreceding={ consume -> snapshot.replayPreceding { event ->
                                        replayContext.ensureActive()
                                        consume(event)
                                    } },
                                    historyTruncated=snapshot.historyTruncated,
                                    endOfInput=(!disk || p == 0) && portState in listOf(PortState.CLOSED,PortState.DISCONNECTED,PortState.ERROR),
                                ))
                                pageCount.value=snapshot.pageCount
                                renderedVersion=snapshot.contextVersion
                                renderedState=portState
                                renderedRevision=revision
                                displayFailureReported=false
                                val elapsed=(System.nanoTime()-now).coerceAtLeast(0)
                                nextReplayNanos=System.nanoTime()+maxOf(1_000_000_000L,elapsed.coerceAtMost(Long.MAX_VALUE/4)*4)
                                records
                            } catch(e: CancellationException) { throw e }
                            catch(_: StaleEventPageException) { null }
                            catch(e: Exception) {
                                if(!displayFailureReported) {
                                    Log.e("SerialViewModel","Unable to render serial data",e)
                                    message.value="Display failed: ${e.message ?: e.javaClass.simpleName}"
                                    displayFailureReported=true
                                }
                                null
                            }
                        } }.filterNotNull()
                    }
                }.collect { display.value=it }
        }
    }
    private fun render(
        events: List<SerialEvent>,
        options: AppSettings,
        context: TextRenderContext = TextRenderContext(),
    ): List<DisplayRecord> = EventTextRenderer.formatEvents(events, options, context).map {
        DisplayRecord(it.key, it.direction, it.text, it.time)
    }
    fun select(id: String) { activeId.value=id; page.value=0; paused.value=false }
    fun save(value: AppSettings) { launch { storage.configuration.saveSettings(value) } }
    private fun launch(block: suspend ()->Unit) { viewModelScope.launch(Dispatchers.IO) { try { block() } catch(e: CancellationException) { throw e } catch(e: Exception) { message.value=e.message ?: e.javaClass.simpleName } } }
    fun send() {
        val session=active.value ?: run { message.value="Select an open serial port"; return }
        val options=settings.value; val text=input.value
        launch {
            val bytes=encodeCommand(text,options.sendHex,options.charset,options.ending,options.receiveEnding)
            val result=session.send(bytes,options.copy(delayByCharacter=options.delayByCharacter && !options.sendHex))
            val failure=when {
                result.completionUnknown -> "USB write completion unknown"
                result.error != null || result.confirmed != bytes.size -> "USB write failed"
                else -> null
            }
            if(failure != null) {
                val detail=result.error?.let { ": ${it.message ?: it.javaClass.simpleName}" }.orEmpty()
                message.value="$failure (${result.confirmed}/${bytes.size} bytes confirmed)$detail"
            } else if(options.clearAfterSend) {
                input.compareAndSet(text,"")
            }
            if(result.confirmed > 0) {
                try {
                    storage.dao.addHistory(HistoryEntity(content=text,hex=options.sendHex,charset=options.charset))
                } catch(e: CancellationException) { throw e }
                catch(e: Exception) {
                    Log.w("SerialViewModel","Unable to save send history",e)
                    if(failure == null) message.value="USB write confirmed, but send history could not be saved"
                }
            }
        }
    }
    fun toggleHex(value: Boolean) {
        launch {
            val s=settings.value
            val converted=if(input.value.isEmpty()) "" else if(value) HexCodec.encode(encodeCommand(input.value,false,s.charset,"NONE","NONE")) else {
                val decoder=Charset.forName(s.charset).newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                decoder.decode(java.nio.ByteBuffer.wrap(HexCodec.decode(input.value))).toString()
            }
            input.value=converted; storage.configuration.saveSettings(s.copy(sendHex=value))
        }
    }
    fun repeatInput() { launch {
        val s=settings.value; val port=active.value ?: error("Select an open serial port")
        port.repeatSend(encodeCommand(input.value,s.sendHex,s.charset,s.ending,s.receiveEnding),s.repeatCount,s.repeatIntervalMs)
    } }
    fun saveMacro(macro: MacroEntity,onSaved: ()->Unit = {},onFailure: (String)->Unit = {}) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                require(macro.name.isNotBlank() && macro.row in 0..4 && macro.delayMs in 0..9999)
                val sequence=VofaCommands.isSequence(macro.content)
                if(sequence) VofaCommands.command(macro.content) else if(macro.hex) HexCodec.decode(macro.content)
                storage.configuration.editRecords {it.saveMacro(macro)}
                if(sequence && settings.value.macroRows <= macro.row) {
                    storage.configuration.saveSettings(settings.value.copy(macroRows=macro.row+1))
                }
                withContext(Dispatchers.Main) {onSaved()}
            } catch(e: CancellationException) {throw e}
            catch(e: Exception) {
                val detail=e.message ?: e.javaClass.simpleName
                withContext(Dispatchers.Main) {message.value=detail;onFailure(detail)}
            }
        }
    }
    fun deleteMacro(id: String,onDeleted: ()->Unit = {},onFailure: (String)->Unit = {}) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                storage.configuration.editRecords {it.deleteMacro(id)}
                withContext(Dispatchers.Main) {onDeleted()}
            } catch(e: CancellationException) {throw e}
            catch(e: Exception) {
                val detail=e.message ?: e.javaClass.simpleName
                withContext(Dispatchers.Main) {message.value=detail;onFailure(detail)}
            }
        }
    }
    fun useMacro(macro: MacroEntity) {
        if(VofaCommands.isSequence(macro.content)) {
            val port=active.value ?: run {message.value="Select an open serial port";return}
            launch {port.runVofa(VofaCommands.command(macro.content))}
            return
        }
        if(macro.insertOnly) { input.value=macro.content; save(settings.value.copy(sendHex=macro.hex)); return }
        val port=active.value ?: run {message.value="Select an open serial port";return}
        launch {
            val s=settings.value
            val result=port.send(encodeCommand(macro.content,macro.hex,s.charset,s.ending,s.receiveEnding),s.copy(lineDelayMs=macro.delayMs,delayByCharacter=s.delayByCharacter && !macro.hex))
            if(result.error != null) throw result.error
            storage.dao.addHistory(HistoryEntity(content=macro.content,hex=macro.hex,charset=s.charset))
        }
    }
    fun importVofa(uri: Uri) {launch {
        val data=(app.contentResolver.openInputStream(uri) ?: error("Cannot read VOFA file")).use {it.readNBytesCompat(VofaCommands.MAX_BYTES+1)}
        val imported=VofaCommands.decodeExport(data).map {MacroEntity(name=it.name,content=VofaCommands.content(it))}
        var count=0
        storage.configuration.editRecords {dao ->
            val known=dao.allMacros().map {it.name to it.content}.toMutableSet()
            imported.forEach {if(known.add(it.name to it.content)) {dao.saveMacro(it);count++}}
        }
        if(settings.value.macroRows == 0) storage.configuration.saveSettings(settings.value.copy(macroRows=1))
        message.value="已导入 $count 个快捷宏，跳过 ${imported.size-count} 个重复项"
    }}
    fun useHistory(h: HistoryEntity) { input.value=h.content; save(settings.value.copy(sendHex=h.hex,charset=h.charset)) }
    fun saveMapping(vendor: Int, product: Int, driver: String) { launch {
        require(vendor in 0..65535 && product in 0..65535 && driver in manager.driverNames)
        storage.configuration.editRecords {it.saveMapping(DeviceMappingEntity("$vendor:$product",vendor,product,driver))}; manager.scan()
    } }
    fun apply(config: SerialConfig) { launch { val port=active.value ?: error("Select an open serial port"); port.apply(config); storage.configuration.saveSettings(settings.value.copy(config=config)) } }
    fun refreshDiagnostics() {launch {active.value?.refreshDiagnostics() ?: error("Select an open serial port")}}
    fun configureGpio(pin: GpioPin, enabled: Boolean = pin.enabled, output: Boolean = pin.output) {
        launch {active.value?.configureGpio(pin.index,enabled,output) ?: error("Select an open serial port")}
    }
    fun writeGpio(pin: GpioPin, high: Boolean) {launch {active.value?.writeGpio(pin.index,high) ?: error("Select an open serial port")}}
    fun atCommands() { launch { active.value?.runAtCommands() ?: error("Select an open serial port") } }
    fun controlKey(bytes: String) { launch { val result=active.value?.send(bytes.toByteArray(Charsets.US_ASCII),settings.value.copy(charDelayMs=0,lineDelayMs=0)); result?.error?.let { throw it } } }
    fun clear() { active.value?.clear() }
    fun undo() { active.value?.undoClear() }
    fun reset(domain: String) { launch { when(domain) {
        "SETTINGS" -> storage.configuration.saveSettings(AppSettings())
        "MACROS" -> storage.configuration.editRecords {it.clearMacros()}
        "MAPPINGS" -> { storage.configuration.editRecords {it.clearMappings()}; manager.scan() }
    } } }
    fun transfer(uri: Uri, mode: FileMode) { launch {
        val session=active.value ?: error("Select an open serial port")
        var name=uri.lastPathSegment ?: "file"; var total=-1L
        app.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE),null,null,null)?.use { c -> if(c.moveToFirst()) { name=c.getString(0) ?: name; if(!c.isNull(1)) total=c.getLong(1) } }
        session.transfer(name,total,mode) { app.contentResolver.openInputStream(uri) ?: error("Cannot read file") }
    } }
    fun saveDirectory(uri: Uri) { launch {
        app.contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        storage.configuration.saveSettings(settings.value.copy(directoryUri=uri.toString()))
    } }
    private fun exportEvents(output: OutputStream, format: String) {
        val session=active.value ?: error("No session data")
        val decoder=StreamTextDecoder(Charset.forName(settings.value.charset))
        session.store.export { e -> if(e.direction == Direction.RX) when(format) {
            "RAW" -> output.write(e.data)
            "HEX" -> output.write((HexCodec.encode(e.data)+"\n").toByteArray())
            else -> {
                if(e.streamReset) output.write((decoder.finishAndReset().text+"\n[RX stream interrupted]\n").toByteArray(Charsets.UTF_8))
                output.write(decoder.decode(e.data).toByteArray(Charsets.UTF_8))
            }
        } }
        if(format == "TEXT") output.write(decoder.decode(byteArrayOf(),true).toByteArray(Charsets.UTF_8))
    }
    fun exportData(uri: Uri, format: String) { launch { (app.contentResolver.openOutputStream(uri,"w") ?: error("Cannot write file")).use { exportEvents(it,format) }; message.value="Saved" } }
    suspend fun createShare(format: String): Intent = withContext(Dispatchers.IO) {
        val directory=File(app.cacheDir,"share").apply { mkdirs() }
        val file=File(directory,"serial-${System.currentTimeMillis()}.${if(format == "RAW") "bin" else "txt"}")
        file.outputStream().use { exportEvents(it,format) }
        val uri=FileProvider.getUriForFile(app,"${app.packageName}.files",file)
        Intent(Intent.ACTION_SEND).setType(if(format == "RAW") "application/octet-stream" else "text/plain").putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    fun exportConfig(uri: Uri) { launch {
        val data=storage.configuration.exportArchive()
        (app.contentResolver.openOutputStream(uri,"w") ?: error("Cannot export configuration")).use { it.write(data) }
        message.value="Configuration exported"
    } }
    fun importConfig(uri: Uri) { launch {
        val data=(app.contentResolver.openInputStream(uri) ?: error("Cannot read configuration")).use { it.readNBytesCompat(ConfigurationArchive.MAX_BYTES+1) }
        storage.configuration.importArchive(data,manager.driverNames)
        manager.scan(); message.value="Configuration imported"
    } }
}

private fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray {
    val out=java.io.ByteArrayOutputStream(); val buffer=ByteArray(4096)
    while(out.size() < max) { val n=read(buffer,0,minOf(buffer.size,max-out.size())); if(n < 0) break; out.write(buffer,0,n) }
    return out.toByteArray()
}
