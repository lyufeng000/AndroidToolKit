package com.lyufeng.phoneserialport.data

import com.lyufeng.phoneserialport.core.*
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.util.ArrayDeque

/** The callback runs outside the store lock. Do not retain its event payloads. */
data class EventPage(
    val events: List<SerialEvent>,
    val pageCount: Int,
    val contextVersion: Long,
    val historyTruncated: Boolean,
    val replayPreceding: ((SerialEvent) -> Unit) -> Unit,
)

class StaleEventPageException : IllegalStateException("Display history changed during replay")

private const val MAX_MEMORY_EVENTS = 8192

/** The unlimited display mode keeps its index on disk as well as its event payloads. */
class EventStore(private val directory: File, val sessionId: String) : Closeable {
    private val memory = ArrayDeque<SerialEvent>()
    private var bytes = 0L
    private var limit = 200*1024
    private var data: RandomAccessFile? = null
    private var index: RandomAccessFile? = null
    private var start = 0L
    private var count = 0L
    private var diskBytes = 0L
    private var undoMemory: List<SerialEvent>? = null
    private var undoStart: Long? = null
    private var undoDiskBytes: Long? = null
    private var contextVersion = 0L
    private var historyTruncated = false
    private var undoHistoryTruncated: Boolean? = null
    var trimmedBytes: Long = 0; private set
    @Synchronized fun contextVersion(): Long = contextVersion
    @Synchronized fun recoverToMemory() {
        contextVersion++; historyTruncated=true; undoHistoryTruncated=null
        runCatching {data?.close()};runCatching {index?.close()};data=null;index=null
        limit=200*1024;memory.clear();bytes=0;start=0;count=0;diskBytes=0;undoStart=null;undoDiskBytes=null;undoMemory=null
    }
    @Synchronized fun setLimit(value: Int) {
        if(limit == value) return
        require(value >= 0)
        contextVersion++
        if(value == 0) {
            directory.mkdirs()
            val d = File(directory,"$sessionId.events"); val i = File(directory,"$sessionId.index")
            try {
                data = RandomAccessFile(d,"rw").apply { setLength(0) }
                index = RandomAccessFile(i,"rw").apply { setLength(0) }
                start = 0; count = 0; diskBytes = 0
                memory.forEach(::appendDisk)
            } catch(e: Exception) {
                runCatching {data?.close()};runCatching {index?.close()};data=null;index=null
                start=0;count=0;diskBytes=0
                throw e
            }
            memory.clear(); bytes = 0; undoMemory = null; undoStart = null; undoDiskBytes = null; undoHistoryTruncated = null
        } else if(limit == 0) {
            val recovered=ArrayDeque<SerialEvent>()
            var recoveredBytes=0L
            var recoveredMessages=0
            recovery@ for(p in 0 until pageCount()) {
                val block=page(p)
                for(e in block.asReversed()) {
                    if(e.data.isEmpty() && recoveredMessages >= 512) continue
                    recovered.addFirst(e);recoveredBytes+=e.data.size
                    if(e.data.isEmpty()) recoveredMessages++
                    if(recovered.size == MAX_MEMORY_EVENTS) break@recovery
                }
                if(recoveredBytes >= value) break
            }
            data?.close(); index?.close(); data = null; index = null
            File(directory,"$sessionId.events").delete(); File(directory,"$sessionId.index").delete()
            val discarded=(diskBytes-recoveredBytes).coerceAtLeast(0)
            trimmedBytes += discarded
            if(discarded > 0) historyTruncated=true
            memory.addAll(recovered); bytes = recoveredBytes; diskBytes = 0; undoStart = null; undoDiskBytes = null
            undoHistoryTruncated=null
        }
        limit = value
        trim()
    }
    private fun appendDisk(e: SerialEvent) {
        val d = requireNotNull(data); val i = requireNotNull(index)
        val offset = d.length()
        d.seek(offset); d.writeLong(e.sequence); d.writeLong(e.timestamp); d.writeInt(e.direction.ordinal)
        d.writeUTF((e.message ?: "").take(16000)); d.writeInt(e.data.size); d.write(e.data); d.writeBoolean(e.streamReset)
        i.seek(count*8); i.writeLong(offset); count++; diskBytes += e.data.size
    }
    @Synchronized fun append(e: SerialEvent) {
        if(limit == 0) appendDisk(e) else {
            if(e.data.isEmpty() && memory.count {it.data.isEmpty()} >= 512) {
                val iterator=memory.iterator()
                while(iterator.hasNext()) if(iterator.next().data.isEmpty()) {iterator.remove();break}
            }
            memory.addLast(e); bytes += e.data.size; trim()
        }
    }
    private fun trim() {
        if(limit == 0) return
        while(bytes > limit || memory.size > MAX_MEMORY_EVENTS) {
            val overEventLimit = memory.size > MAX_MEMORY_EVENTS
            val first = memory.removeFirst(); val excess = (bytes-limit).coerceAtLeast(0)
            if(!overEventLimit && excess in 1 until first.data.size.toLong()) {
                val removed = excess.toInt(); memory.addFirst(first.copy(data=first.data.copyOfRange(removed,first.data.size)))
                bytes -= removed; trimmedBytes += removed; historyTruncated=true
            } else { bytes -= first.data.size; trimmedBytes += first.data.size; if(first.data.isNotEmpty()) historyTruncated=true }
        }
    }
    private fun trimMessages() {
        var extra=memory.count {it.data.isEmpty()}-512
        if(extra <= 0) return
        val iterator=memory.iterator()
        while(extra > 0 && iterator.hasNext()) if(iterator.next().data.isEmpty()) {iterator.remove();extra--}
    }
    @Synchronized fun page(pageFromEnd: Int = 0, pageSize: Int = 128): List<SerialEvent> {
        require(pageFromEnd >= 0 && pageSize > 0)
        if(limit != 0) return memory.toList().let { all -> val end = (all.size-pageFromEnd*pageSize).coerceIn(0,all.size); all.subList((end-pageSize).coerceAtLeast(0),end) }
        if(data == null || index == null) return emptyList()
        val end = (count-pageFromEnd.toLong()*pageSize).coerceAtLeast(start)
        return ((end-pageSize).coerceAtLeast(start) until end).map(::readDisk)
    }
    private fun readDisk(position: Long): SerialEvent {
        val d=requireNotNull(data); val i=requireNotNull(index)
        i.seek(position*8); d.seek(i.readLong())
        val seq=d.readLong(); val time=d.readLong(); val direction=Direction.entries[d.readInt()]; val message=d.readUTF()
        val size=d.readInt(); check(size in 0..1048576) { "Invalid event record" }; val content=ByteArray(size); d.readFully(content)
        return SerialEvent(sessionId,seq,time,direction,content,message.ifEmpty { null },streamReset=d.readBoolean())
    }

    /**
     * Replay the exact prefix after the current clear boundary. Read one event at
     * a time. Appends do not invalidate this snapshot. Clear, undo and mode changes do.
     */
    @Synchronized fun pageWithContext(pageFromEnd: Int = 0, pageSize: Int = 128): EventPage {
        require(pageFromEnd >= 0 && pageSize > 0)
        if(limit != 0) return EventPage(memory.toList(),pageCount(),contextVersion,historyTruncated) {}
        val version=contextVersion
        val from=start
        val end=(count-pageFromEnd.toLong()*pageSize).coerceAtLeast(start)
        val first=(end-pageSize).coerceAtLeast(start)
        val events=if(data == null || index == null) emptyList() else (first until end).map(::readDisk)
        return EventPage(events,pageCount(),version,historyTruncated) { consume ->
            for(position in from until first) {
                val event=synchronized(this) {
                    if(version != contextVersion) throw StaleEventPageException()
                    readDisk(position)
                }
                consume(event)
            }
            synchronized(this) { if(version != contextVersion) throw StaleEventPageException() }
        }
    }
    @Synchronized fun current(): List<SerialEvent> = if(limit == 0) page() else memory.toList()
    @Synchronized fun pageCount(): Int = if(limit == 0) ((count-start+127)/128).toInt().coerceAtLeast(1) else ((memory.size+127)/128).coerceAtLeast(1)
    @Synchronized fun clear() {
        contextVersion++; undoHistoryTruncated=historyTruncated; historyTruncated=false
        if(limit == 0) { undoStart=start; undoDiskBytes=diskBytes; start=count; diskBytes=0 } else { undoMemory=memory.toList(); memory.clear(); bytes=0 }
    }
    @Synchronized fun undo() {
        val available=if(limit == 0) undoStart != null else undoMemory != null
        if(available) {
            contextVersion++
            historyTruncated=historyTruncated || (undoHistoryTruncated ?: false)
        }
        if(limit == 0) { undoStart?.let { start=it; diskBytes += undoDiskBytes ?: 0 }; undoStart=null; undoDiskBytes=null }
        else { undoMemory?.let { previous -> val latest=memory.toList(); memory.clear(); memory.addAll(previous); memory.addAll(latest); bytes=memory.sumOf { it.data.size.toLong() }; trimMessages();trim() }; undoMemory=null }
        undoHistoryTruncated=null
    }
    @Synchronized fun resetTrimmedCount() {trimmedBytes=0}
    @Synchronized fun export(write: (SerialEvent)->Unit) {
        if(limit != 0) memory.forEach(write)
        else {
            val total=pageCount()
            for(p in total-1 downTo 0) page(p).forEach(write)
        }
    }
    @Synchronized override fun close() { contextVersion++; data?.close(); index?.close(); data=null; index=null }
}
