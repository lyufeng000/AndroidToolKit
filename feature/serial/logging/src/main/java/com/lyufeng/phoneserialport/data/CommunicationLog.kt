package com.lyufeng.phoneserialport.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.lyufeng.phoneserialport.core.SerialEvent
import com.lyufeng.phoneserialport.terminal.HexCodec
import com.lyufeng.phoneserialport.terminal.StreamTextDecoder
import com.lyufeng.phoneserialport.core.Direction
import com.lyufeng.phoneserialport.core.SerialPresets
import java.io.Closeable
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CommunicationLog(private val context: Context,private val now: ()->Long = System::currentTimeMillis) : Closeable {
    private var stream: OutputStream? = null
    private var hex = true
    private var rx=StreamTextDecoder(Charsets.UTF_8)
    private var tx=StreamTextDecoder(Charsets.UTF_8)
    private val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS",Locale.ROOT)
    @Synchronized fun open(sessionId: String, settings: AppSettings) {
        close(); hex=settings.logHex
        rx=StreamTextDecoder(java.nio.charset.Charset.forName(settings.charset))
        tx=StreamTextDecoder(java.nio.charset.Charset.forName(settings.charset))
        val name="serial-${SimpleDateFormat("yyyyMMdd-HHmmss",Locale.ROOT).format(Date(now()))}-${sessionId.take(8)}-${SerialPresets.newId()}.log"
        stream = if(settings.directoryUri.isBlank()) context.openFileOutput(name,Context.MODE_PRIVATE)
        else {
            val tree=Uri.parse(settings.directoryUri)
            val parent=DocumentsContract.buildDocumentUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree))
            val document=DocumentsContract.createDocument(context.contentResolver,parent,"text/plain",name) ?: error("Cannot create log file")
            context.contentResolver.openOutputStream(document,"w") ?: error("Cannot open log file")
        }
    }
    @Synchronized fun append(event: SerialEvent) {
        val out=stream ?: return
        val boundary=if(event.streamReset && !hex) rx.finishAndReset().text+"[RX stream interrupted]\n" else ""
        val payload=boundary+(event.message ?: if(hex) HexCodec.encode(event.data) else if(event.direction == Direction.TX) tx.decode(event.data) else rx.decode(event.data))
        out.write("${time.format(Date(event.timestamp))}\t${event.sessionId}\t${event.sequence}\t${event.direction}\t$payload\n".toByteArray(Charsets.UTF_8))
    }
    @Synchronized fun flush() { stream?.flush() }
    @Synchronized override fun close() {
        val out=stream; stream=null
        if(out != null) try {
            if(!hex) {val tail=rx.decode(byteArrayOf(),true)+tx.decode(byteArrayOf(),true);if(tail.isNotEmpty()) out.write(tail.toByteArray(Charsets.UTF_8))}
            out.flush()
        } finally { out.close() }
    }
}
