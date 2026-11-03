package com.lyufeng.phoneserialport.serial

import android.content.Context
import com.lyufeng.phoneserialport.core.*
import com.lyufeng.phoneserialport.data.*
import com.lyufeng.phoneserialport.terminal.*
import com.lyufeng.phoneserialport.transfer.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class SerialSession(
    private val context: Context,
    val id: String,
    val deviceKey: String,
    val portIndex: Int,
    val adapter: SerialDriverAdapter,
    initialSettings: AppSettings,
    private val taskDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val onFailure: (SerialSession, Throwable) -> Unit,
) {
    private val job=SupervisorJob()
    private val scope=CoroutineScope(job+Dispatchers.IO)
    private val outputLock=Mutex()
    private val sends=PendingWriteQueue(32) {size -> rejected.addAndGet(size.toLong());emit(Direction.ERROR,message="Transmit queue is full or closed")}
    private val events=Channel<SerialEvent>(128)
    private val logs=Channel<SerialEvent>(256)
    private val sequence=AtomicLong()
    private val received=AtomicLong(); private val sent=AtomicLong(); private val overflow=AtomicLong(); private val rejected=AtomicLong()
    private val droppedDisplay=AtomicLong()
    private val receiveGap=java.util.concurrent.atomic.AtomicBoolean(false)
    private val displayGap=java.util.concurrent.atomic.AtomicBoolean(false)
    private val mutable=MutableStateFlow(SessionSnapshot(id,deviceKey,portIndex,adapter.driverName,state=PortState.OPENING))
    val state: StateFlow<SessionSnapshot> = mutable
    val revision=MutableStateFlow(0L)
    val store=EventStore(File(context.cacheDir,"display"),id)
    val terminal=VtTerminal(initialSettings.terminalColumns,initialSettings.terminalRows)
    private val displayLock=Any()
    private data class UndoDisplay(val terminal: VtTerminal,val decoder: StreamTextDecoder)
    private var undoDisplay: UndoDisplay?=null
    private var decoder=StreamTextDecoder(java.nio.charset.Charset.forName(initialSettings.charset))
    private val log=CommunicationLog(context)
    @Volatile private var settings=initialSettings
    @Volatile private var protocol: Channel<Int>? = null
    @Volatile private var exclusive=false
    private var repeatJob: Job?=null
    private var commandGroupOwner: Any?=null
    private var transferJob: Job?=null
    private val transferInput=AtomicReference<InputStream?>()
    private var breakJob: Job?=null
    private var logJob: Job?=null
    private var eventJob: Job?=null
    @Volatile private var closed=false
    private val closeStarted=java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var dirty=false
    @Volatile private var lastOverflow=0L

    suspend fun start(config: SerialConfig) {
        val configurable=adapter.capabilities.configurationAvailable
        val report=if(configurable) adapter.apply(config) else null
        store.setLimit(settings.bufferBytes)
        mutable.update { it.copy(state=PortState.OPEN,appliedConfig=if(configurable) config else null,lastConfirmedConfig=if(configurable) config else null,capabilities=adapter.capabilities,baudReport=report) }
        eventJob=scope.launch {
            for(event in events) {
                synchronized(displayLock) {
                try { store.append(event) } catch(e: Exception) {
                    store.recoverToMemory()
                    undoDisplay=null
                    mutable.update { it.copy(error="Display storage failed: ${e.message}; using 200 KiB") }
                }
                if(event.direction == Direction.RX) {
                    if(event.streamReset) {
                        terminal.resetInputState()
                        terminal.feed(decoder.finishAndReset().text)
                        undoDisplay?.let {it.terminal.resetInputState();it.terminal.feed(it.decoder.finishAndReset().text)}
                    }
                    terminal.feed(decoder.decode(event.data))
                    undoDisplay?.let {it.terminal.feed(it.decoder.decode(event.data))}
                }
                dirty=true
                }
            }
        }
        logJob=scope.launch {
            for(event in logs) try { log.append(event) } catch(e: Exception) { logFailure(e) }
        }
        configureLogging(initialSettings=true)
        scope.launch {
            var flushTicks=0
            while(isActive) {
                delay(settings.refreshMs)
                val dropped=overflow.get()
                if(dropped != lastOverflow) { if(dropped > lastOverflow) emit(Direction.ERROR,message="Application receive queue overflow: $dropped bytes"); lastOverflow=dropped }
                mutable.update { it.copy(counters=SerialCounters(received.get(),sent.get(),dropped,rejected.get(),store.trimmedBytes+droppedDisplay.get())) }
                if(dirty) { revision.value++; dirty=false }
                if(++flushTicks >= 20) { flushTicks=0; try { log.flush() } catch(e: Exception) { logFailure(e) } }
            }
        }
        scope.launch {
            try {
                val buffer=ByteArray(4096)
                while(isActive) {
                    val count=adapter.read(buffer)
                    if(adapter.takeReceiveDiscontinuity()) receiveGap.set(true)
                    val driverQueueDropped=adapter.takeReceiveOverflow()
                    if(driverQueueDropped > 0) {overflow.addAndGet(driverQueueDropped);received.addAndGet(driverQueueDropped);emit(Direction.ERROR,message="Driver adapter receive queue overflow: $driverQueueDropped bytes")}
                    if(count > 0) {
                        val bytes=buffer.copyOf(count); received.addAndGet(count.toLong())
                        protocol?.let { channel -> for(b in bytes) if(channel.trySend(b.toInt() and 255).isFailure) { transferJob?.cancel(CancellationException("Protocol response queue overflow")); break } }
                        emit(Direction.RX,bytes)
                    }
                }
            } catch(e: Exception) { if(!closed && e !is CancellationException) onFailure(this@SerialSession,e) }
        }
        scope.launch {
            for(task in sends.requests) {
                val progress=AtomicReference(WriteResult(0))
                val writing=scope.async(start=CoroutineStart.LAZY) {
                    outputLock.withLock {
                        if(exclusive || closed) WriteResult(0,IllegalStateException("Port is busy or closed"))
                        else writePaced(task.bytes,task.settings,{task.completion.isCancelled}) {progress.set(it)}
                    }
                }
                if(!sends.activate(task) {writing.cancel(CancellationException("Send stopped or port closed"))}) {writing.cancel();continue}
                mutable.update {it.copy(transmitting=true)}
                val cancellation=task.completion.invokeOnCompletion {if(task.completion.isCancelled) writing.cancel()}
                try {
                    writing.start()
                    task.completion.complete(writing.await())
                } catch(e: Exception) {val completed=progress.get();task.completion.complete(completed.copy(error=e,completionUnknown=completed.completionUnknown || e !is CancellationException))}
                finally {
                    cancellation.dispose();writing.cancel()
                    withContext(NonCancellable) {
                        writing.join()
                        sends.finish(task)
                        mutable.update {it.copy(transmitting=false)}
                    }
                }
            }
        }
        scope.launch {
            while(isActive) {
                if(!exclusive) try {
                    val lines=adapter.readControlLines()
                    mutable.update { it.copy(lines=lines,flowBlocked=when(it.appliedConfig?.flowControl) { FlowControl.RTS_CTS -> lines["CTS"] == false; FlowControl.DTR_DSR -> lines["DSR"] == false; else -> false }) }
                } catch(e: Exception) { mutable.update { it.copy(lines=emptyMap(),error="Control line read failed: ${e.message}") } }
                delay(1000)
            }
        }
        emit(Direction.STATUS,message=if(configurable) "Port opened: ${config.summary()}" else "Byte-stream port opened. UART parameter configuration is not available in this driver.")
    }
    @Synchronized private fun emit(direction: Direction, bytes: ByteArray = byteArrayOf(), message: String? = null) {
        val event=SerialEvent(id,sequence.incrementAndGet(),direction=direction,data=bytes,message=message,
            streamReset=direction == Direction.RX && receiveGap.getAndSet(false))
        val displayEvent=if(direction == Direction.RX && displayGap.getAndSet(false)) event.copy(streamReset=true) else event
        if(events.trySend(displayEvent).isFailure) {
            if(direction == Direction.RX) {displayGap.set(true);overflow.addAndGet(bytes.size.toLong())}
            else if(direction == Direction.TX) droppedDisplay.addAndGet(bytes.size.toLong())
            mutable.update {it.copy(error="Application event queue overflow. RX dropped=${overflow.get()} B, TX display dropped=${droppedDisplay.get()} B")}
        }
        if(settings.logEnabled && logs.trySend(event).isFailure) {
            mutable.update { it.copy(error="Communication log queue overflow; logging stopped") }
            settings=settings.copy(logEnabled=false)
            scope.launch { runCatching { log.close() } }
        }
        if(direction == Direction.ERROR || direction == Direction.STATUS) {
            val line="${event.timestamp}\t$id\t$message\n"
            scope.launch { runCatching { File(context.filesDir,"diagnostic.log").appendText(line) } }
        }
    }
    private fun logFailure(e: Exception) {
        settings=settings.copy(logEnabled=false)
        runCatching { log.close() }
        mutable.update { it.copy(error="Communication log failed: ${e.message}. Existing data closed.") }
        emit(Direction.ERROR,message="Storage failure: ${e.message}")
    }
    private fun configureLogging(initialSettings: Boolean = false, previous: AppSettings? = null) {
        if(!initialSettings && previous?.logEnabled == settings.logEnabled && previous.directoryUri == settings.directoryUri && previous.logHex == settings.logHex && previous.charset == settings.charset) return
        try { if(settings.logEnabled) log.open(id,settings) else log.close() } catch(e: Exception) { logFailure(e) }
    }
    fun updateSettings(value: AppSettings) {
        val previous=settings
        settings=value
        scope.launch {
            try {
                synchronized(displayLock) {
                    store.setLimit(value.bufferBytes)
                    if(previous.charset != value.charset) {
                        decoder=StreamTextDecoder(java.nio.charset.Charset.forName(value.charset))
                        undoDisplay=undoDisplay?.copy(decoder=StreamTextDecoder(java.nio.charset.Charset.forName(value.charset)))
                    }
                    if(previous.terminalColumns != value.terminalColumns || previous.terminalRows != value.terminalRows) {
                        terminal.resize(value.terminalColumns,value.terminalRows)
                        undoDisplay?.terminal?.resize(value.terminalColumns,value.terminalRows)
                    }
                }
                configureLogging(previous=previous)
                dirty=true
            } catch(e: Exception) { emit(Direction.ERROR,message=e.message) }
        }
    }
    suspend fun apply(config: SerialConfig) = outputLock.withLock {
        check(!exclusive && !closed) { "Port is busy or closed" }
        adapter.capabilities.validate(config)
        val old=mutable.value.appliedConfig
        // Reapplying unchanged line settings can corrupt bytes on CH34x devices.
        if(old == config) return@withLock
        try {
            val report=adapter.apply(config)
            receiveGap.set(true)
            mutable.update { it.copy(appliedConfig=config,lastConfirmedConfig=config,baudReport=report,capabilities=adapter.capabilities,error=null) }
            emit(Direction.STATUS,message="Parameters applied: ${config.summary()}")
        } catch(e: Exception) {
            receiveGap.set(true)
            // A driver can change baud before rejecting another field. Roll back or show unknown state.
            val rollback=old?.let { runCatching { adapter.apply(it) }.isSuccess } == true
            mutable.update { it.copy(appliedConfig=if(rollback) old else null,baudReport=if(rollback) it.baudReport else null,error="Configuration failed: ${e.message}; rollback=$rollback") }
            emit(Direction.ERROR,message=mutable.value.error); throw e
        }
    }
    suspend fun send(bytes: ByteArray, options: AppSettings = settings): WriteResult {
        return sendOwned(bytes,options,null)
    }
    private suspend fun sendOwned(bytes: ByteArray,options: AppSettings,owner: Any?): WriteResult {
        currentCoroutineContext().ensureActive()
        require(bytes.size <= 1024*1024) { "Command exceeds 1 MiB. Use file transfer." }
        val parent=currentCoroutineContext()[Job]
        val completion=synchronized(this) {
            if(commandGroupOwner != null && commandGroupOwner !== owner)
                return WriteResult(0,IllegalStateException("指令组正在运行，请先停止"))
            if(exclusive || closed)
                return WriteResult(0,IllegalStateException("Port is busy or closed"))
            sends.enqueue(bytes,options,parent)
        }
        return completion.await()
    }
    private suspend fun writePaced(bytes: ByteArray, options: AppSettings, cancelled: ()->Boolean,progress: (WriteResult)->Unit): WriteResult {
        var offset=0
        val chunks=if(options.charDelayMs > 0 && options.delayByCharacter) {
            characterChunks(bytes,java.nio.charset.Charset.forName(options.charset))
        } else if(options.charDelayMs > 0) bytes.map { byteArrayOf(it) }
        else if(options.lineDelayMs > 0) {
            val lines=mutableListOf<ByteArray>(); var start=0; var index=0
            while(index < bytes.size) {
                if(bytes[index] == 13.toByte() || bytes[index] == 10.toByte()) {
                    if(bytes[index] == 13.toByte() && bytes.getOrNull(index+1) == 10.toByte()) index++
                    lines+=bytes.copyOfRange(start,index+1); start=index+1
                }
                index++
            }
            if(start < bytes.size) lines+=bytes.copyOfRange(start,bytes.size)
            lines
        } else listOf(bytes)
        for(chunk in chunks) {
            currentCoroutineContext().ensureActive()
            if(cancelled()) return WriteResult(offset,CancellationException("Send cancelled at $offset/${bytes.size}"))
            val result=writeConfirmed(chunk,offset,bytes.size)
            offset+=result.confirmed
            progress(result.copy(confirmed=offset))
            if(result.error != null || result.confirmed != chunk.size || result.completionUnknown) return result.copy(confirmed=offset)
            if(options.charDelayMs > 0) delay(options.charDelayMs.toLong())
            val lineEnd=chunk.lastOrNull() == 10.toByte() || (chunk.lastOrNull() == 13.toByte() && bytes.getOrNull(offset) != 10.toByte())
            if(options.lineDelayMs > 0 && lineEnd) delay(options.lineDelayMs.toLong())
        }
        return WriteResult(offset)
    }
    private fun writeConfirmed(bytes: ByteArray,offset: Int = 0,total: Int = bytes.size): WriteResult {
        val result=adapter.write(bytes,2000)
        if(result.confirmed > 0) { sent.addAndGet(result.confirmed.toLong()); emit(Direction.TX,bytes.copyOf(result.confirmed)) }
        result.error?.let { error ->
            val detail="Write stopped at ${offset+result.confirmed}/$total; completion unknown=${result.completionUnknown}; ${error.message}"
            mutable.update { it.copy(error=detail) }; emit(Direction.ERROR,message=detail)
        }
        return result
    }
    fun repeatSend(bytes: ByteArray, count: Int, interval: Long) {
        require(count >= 0 && interval >= 10)
        require(bytes.isNotEmpty() && bytes.size <= 1024*1024) {"Repeat command must contain 1 byte to 1 MiB. Use file transfer for larger data."}
        val command=bytes.copyOf()
        startRepeating {
            var n=0
            while(isActive && (count == 0 || n < count)) {
                val result=send(command);if(result.error != null) break
                n++;if(count == 0 || n < count) delay(interval)
            }
        }
    }
    @Synchronized fun stopRepeat() { repeatJob?.cancel() }
    @Synchronized fun stopSending() {stopRepeat();sends.cancelPending()}
    @Synchronized fun runVofa(command: VofaCommand) {
        check(!closed) {"Port is busy or closed"}
        check(!exclusive && commandGroupOwner == null && repeatJob == null && sends.isIdle) {"当前串口正在发送，请先停止"}
        var count=0
        fun snapshot(node: VofaCommand,depth: Int): VofaCommand {
            require(depth <= 16 && ++count <= 2048) {"VOFA command tree is too large"}
            return node.copy(children=node.children.toList().map {snapshot(it,depth+1)})
        }
        val saved=snapshot(command,0)
        VofaCommands.validate(saved)
        val owner=Any()
        commandGroupOwner=owner
        val task=scope.launch(taskDispatcher,start=CoroutineStart.LAZY) {
            try {
                VofaCommands.run(saved,{bytes ->
                    val result=sendOwned(bytes,settings.copy(charDelayMs=0,lineDelayMs=0),owner)
                    result.error?.let {throw it}
                    check(result.confirmed == bytes.size && !result.completionUnknown) {"Macro write was not fully confirmed"}
                })
            } catch(e: CancellationException) {throw e}
            catch(e: Exception) {mutable.update {it.copy(error=e.message)};emit(Direction.ERROR,message="Scheduled send failed: ${e.message}")}
        }
        repeatJob=task
        mutable.update {it.copy(repeating=true,commandGroupName=saved.name)}
        task.invokeOnCompletion {
            // A cancelled caller can finish while a blocking driver write is still running.
            scope.launch(NonCancellable) {
                sends.awaitIdle()
                synchronized(this@SerialSession) {
                    if(commandGroupOwner === owner) {
                        commandGroupOwner=null
                        if(repeatJob === task) repeatJob=null
                        mutable.update {it.copy(repeating=false,commandGroupName=null)}
                    }
                }
            }
        }
        task.start()
    }
    fun runAtCommands() {
        startRepeating {
            for(command in SerialPresets.atCommands) {
                delay(100)
                if(send("$command\r\n".toByteArray(),settings.copy(charDelayMs=0,lineDelayMs=0)).error != null) break
                delay(100)
            }
        }
    }
    @Synchronized private fun startRepeating(block: suspend CoroutineScope.()->Unit) {
        check(commandGroupOwner == null) {"指令组正在运行，请先停止"}
        check(!exclusive && !closed) { "Port is busy or closed" }
        repeatJob?.cancel()
        val task=scope.launch(taskDispatcher,start=CoroutineStart.LAZY) {
            try {block()} catch(e: CancellationException) {throw e}
            catch(e: Exception) {mutable.update {it.copy(error=e.message)};emit(Direction.ERROR,message="Scheduled send failed: ${e.message}")}
        }
        repeatJob=task
        mutable.update {it.copy(repeating=true)}
        task.invokeOnCompletion {synchronized(this) {if(repeatJob === task) {repeatJob=null;mutable.update {it.copy(repeating=false)}}}}
        task.start()
    }
    fun control(name: String, value: Boolean, duration: Int? = null) {
        scope.launch {
            try {
                outputLock.withLock {
                    check(!exclusive && !closed) { "Port is busy or closed" }
                    val flow=mutable.value.appliedConfig?.flowControl
                    check(!(name == "RTS" && flow == FlowControl.RTS_CTS) && !(name == "DTR" && flow == FlowControl.DTR_DSR)) { "Line is owned by flow control" }
                    if(name == "BREAK") { breakJob?.cancel(); breakJob=null }
                    adapter.control(name,value)
                    if(name == "BREAK" && duration != null && value) {
                        breakJob=scope.launch {
                            delay(duration.toLong())
                            outputLock.withLock {
                                if(!closed) {
                                    try { adapter.control("BREAK",false); mutable.update { it.copy(lines=adapter.readControlLines()) } }
                                    catch(e: Exception) { emit(Direction.ERROR,message="BREAK release failed: ${e.message}");mutable.update { it.copy(error=e.message) } }
                                }
                            }
                        }
                    }
                }
                mutable.update { it.copy(lines=adapter.readControlLines()) }
            } catch(e: Exception) { emit(Direction.ERROR,message="Control failed: ${e.message}"); mutable.update { it.copy(error=e.message) } }
        }
    }
    @Synchronized fun transfer(name: String, total: Long, mode: FileMode, open: () -> InputStream) {
        check(commandGroupOwner == null) {"指令组正在运行，请先停止"}
        check(!exclusive && !closed) { "Port is busy or closed" }
        exclusive=true
        repeatJob?.cancel()
        val responses=Channel<Int>(65536)
        val finalProgress=AtomicReference<TransferProgress?>()
        if(mode !in listOf(FileMode.RAW,FileMode.LINES)) protocol=responses
        mutable.update { it.copy(transfer=TransferProgress(name,mode.name,0,total,"RUNNING")) }
        val task=scope.launch(taskDispatcher,start=CoroutineStart.LAZY) {
            var complete=0L
            fun progress(n: Long,status: String = "RUNNING") {
                complete=n
                val progress=TransferProgress(name,mode.name,n,total,status)
                if(status == "RUNNING") mutable.update { it.copy(transfer=progress) } else finalProgress.set(progress)
            }
            progress(0)
            try {
                outputLock.withLock {
                    val source=object : FilterInputStream(open()) {
                        private val sourceClosed=java.util.concurrent.atomic.AtomicBoolean()
                        override fun close() { if(sourceClosed.compareAndSet(false,true)) super.close() }
                    }
                    transferInput.set(source)
                    try { source.use { input ->
                        currentCoroutineContext().ensureActive()
                        suspend fun write(bytes: ByteArray) {
                            currentCoroutineContext().ensureActive()
                            val r=writeConfirmed(bytes)
                            r.error?.let {if(mode == FileMode.RAW) progress(complete+r.confirmed); throw it}
                        }
                        when(mode) {
                            FileMode.RAW -> { val buffer=ByteArray(256); while(true) { val n=input.read(buffer); if(n < 0) break; write(buffer.copyOf(n)); progress(complete+n) } }
                            FileMode.LINES -> {
                                val reader=InputStreamReader(input,java.nio.charset.Charset.forName(settings.charset))
                                val line=StringBuilder(); var pendingCR=false
                                suspend fun sendLine() { val raw=line.toString().toByteArray(java.nio.charset.Charset.forName(settings.charset)); write(applyEnding(raw,if(settings.ending == "AUTO") settings.receiveEnding else settings.ending)); progress(complete+raw.size); line.clear(); delay(settings.lineDelayMs.toLong()) }
                                while(true) { val n=reader.read(); if(n < 0) break; val c=n.toChar(); if(pendingCR) { pendingCR=false; if(c == '\n') continue }; when(c) { '\r' -> { sendLine(); pendingCR=true }; '\n' -> sendLine(); else -> { line.append(c); require(line.length <= 1024*1024) { "File line exceeds 1 MiB" } } } }
                                if(line.isNotEmpty()) sendLine()
                            }
                            else -> XmodemSender(responses,::write,{ progress(it) }).send(input,mode)
                        }
                    } } finally { transferInput.compareAndSet(source,null) }
                }
                progress(if(mode == FileMode.LINES && total >= 0) total else complete,"COMPLETE")
            } catch(e: CancellationException) {
                if(!closed && mode !in listOf(FileMode.RAW,FileMode.LINES)) withContext(NonCancellable) { outputLock.withLock { writeConfirmed(byteArrayOf(24,24)) } }
                progress(complete,"CANCELLED")
            } catch(e: Exception) {
                if(!closed && mode !in listOf(FileMode.RAW,FileMode.LINES)) runCatching {outputLock.withLock {writeConfirmed(byteArrayOf(24,24))}}
                if(!currentCoroutineContext().isActive) progress(complete,"CANCELLED")
                else { progress(complete,"FAILED"); mutable.update { it.copy(error=e.message) }; emit(Direction.ERROR,message="File transfer failed: ${e.message}") }
            }
        }
        transferJob=task
        // Completion handlers run even when cancellation prevents the coroutine body from starting.
        task.invokeOnCompletion { cause ->
            synchronized(this) {
                if(transferJob === task) {
                    if(protocol === responses) protocol=null
                    responses.close()
                    transferJob=null
                    exclusive=false
                    mutable.update { snapshot -> snapshot.copy(transfer=finalProgress.get()
                        ?: snapshot.transfer?.copy(status=if(cause is CancellationException) "CANCELLED" else "FAILED")) }
                }
            }
        }
        task.start()
    }
    @Synchronized fun cancelTransfer() {
        transferJob?.cancel()
        val source=transferInput.getAndSet(null)
        // A session shutdown must not cancel the cleanup before it closes a blocked document source.
        if(source != null) scope.launch(Dispatchers.IO+NonCancellable) { runCatching { source.close() } }
    }
    suspend fun refreshDiagnostics() = outputLock.withLock {
        check(!closed && !exclusive) {"Port is busy or closed"}
        val diagnostics=adapter.diagnose()
        mutable.update {it.copy(diagnostics=diagnostics,capabilities=adapter.capabilities,
            baudReport=it.baudReport?.copy(driverReported=diagnostics.baud.value))}
    }
    suspend fun configureGpio(index: Int, enabled: Boolean, output: Boolean) {
        outputLock.withLock {check(!closed && !exclusive) {"Port is busy or closed"};adapter.configureGpio(index,enabled,output)}
        refreshDiagnostics()
    }
    suspend fun writeGpio(index: Int, high: Boolean) {
        outputLock.withLock {check(!closed && !exclusive) {"Port is busy or closed"};adapter.writeGpio(index,high)}
        refreshDiagnostics()
    }
    fun clear(): Unit = synchronized(displayLock) {
        store.clear()
        undoDisplay=UndoDisplay(terminal.fork(),decoder)
        terminal.reset();decoder=StreamTextDecoder(java.nio.charset.Charset.forName(settings.charset))
        if(settings.clearResetsCounters) resetCounters()
        revision.value++
    }
    fun undoClear(): Unit = synchronized(displayLock) {
        val previous=undoDisplay ?: return@synchronized
        store.undo();terminal.restoreFrom(previous.terminal);decoder=previous.decoder;undoDisplay=null
        revision.value++
    }
    fun resetCounters() {
        received.set(0);sent.set(0);overflow.set(0);rejected.set(0);droppedDisplay.set(0);lastOverflow=0
        store.resetTrimmedCount()
        mutable.update { it.copy(counters=SerialCounters()) }
    }
    suspend fun close(disconnected: Boolean = false, error: String? = null) {
        if(!closeStarted.compareAndSet(false,true)) return
        closed=true
        mutable.update { it.copy(state=PortState.CLOSING) }
        repeatJob?.cancel(); cancelTransfer(); breakJob?.cancel()
        // Closing the per-port USB handle interrupts the blocking read without closing another port's handle.
        sends.close()
        runCatching { adapter.control("BREAK",false) }
        runCatching { adapter.close() }
        val finalDriverDrop=adapter.takeReceiveOverflow()
        if(finalDriverDrop > 0) {
            overflow.addAndGet(finalDriverDrop);received.addAndGet(finalDriverDrop)
            emit(Direction.ERROR,message="Driver adapter receive queue overflow before close: $finalDriverDrop bytes")
        }
        events.close(); logs.close()
        withTimeoutOrNull(2000) { eventJob?.join(); logJob?.join() }
        runCatching { log.close() }
        job.cancel()
        mutable.update { it.copy(state=if(disconnected) PortState.DISCONNECTED else PortState.CLOSED,error=error,repeating=false,transmitting=false,
            counters=SerialCounters(received.get(),sent.get(),overflow.get(),rejected.get(),store.trimmedBytes+droppedDisplay.get()),
            transfer=it.transfer?.takeIf { p -> p.status != "RUNNING" } ?: it.transfer?.copy(status="CANCELLED")) }
        revision.value++
    }
}
