package com.lyufeng.phoneserialport.terminal

import com.lyufeng.phoneserialport.core.Direction
import com.lyufeng.phoneserialport.core.SerialEvent
import com.lyufeng.phoneserialport.data.AppSettings
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Supply preceding events in time order, starting at a known stream boundary.
 * They restore state but do not appear in the result. A fixed byte lookback cannot
 * restore every charset or terminal state. Mark actual gaps in resetBefore.
 * Set endOfInput only when no further bytes belong to this stream.
 */
data class TextRenderContext(
    val precedingEvents: List<SerialEvent> = emptyList(),
    val resetBefore: Set<Long> = emptySet(),
    val endOfInput: Boolean = false,
    val replayPreceding: (((SerialEvent) -> Unit) -> Unit)? = null,
    val historyTruncated: Boolean = false,
)

data class RenderedTextEvent(
    val key: Long,
    val direction: Direction,
    val text: String,
    val time: String,
    val rawBytes: ByteArray,
    val issues: List<DecodeIssue>,
) {
    val rawHex: String get() = HexCodec.encode(rawBytes)
}

/** Rebuild one view. Decoder state never leaks between calls or sessions. */
object EventTextRenderer {
    private class Record(val event: SerialEvent, var text: String = "") {
        val issues = mutableListOf<DecodeIssue>()
    }

    fun formatEvents(
        events: List<SerialEvent>,
        options: AppSettings,
        context: TextRenderContext = TextRenderContext(),
    ): List<RenderedTextEvent> {
        val charset = Charset.forName(options.charset)
        val rx = StreamTextDecoder(charset)
        val tx = StreamTextDecoder(charset)
        var receive = ReceiveFormatter(options.receiveEnding, options.controlCharacters)
        var simple = SimpleTerminalFilter()
        val records = mutableListOf<Record>()
        var lastRx: Record? = null
        var lastTx: Record? = null
        var boundaryDiagnostic = context.historyTruncated

        fun filterRx(text: String): String {
            if(options.displayMode != "SIMPLE") return text
            val filtered = simple.feed(text)
            if('\u000c' !in filtered) return filtered
            records.clear()
            return filtered.substringAfterLast('\u000c')
        }

        fun appendTail(record: Record?, decoded: DecodedText, isRx: Boolean) {
            val text = if(isRx) filterRx(receive.feed(decoded.text)) else decoded.text
            record?.let { it.text += text; it.issues += decoded.issues }
        }

        fun finishSegment() {
            appendTail(lastRx, rx.finishAndReset(), true)
            appendTail(lastTx, tx.finishAndReset(), false)
            lastRx?.let { it.text += filterRx(receive.finish()) }
        }

        fun process(event: SerialEvent, visible: Boolean) {
            val streamReset=event.streamReset || event.sequence in context.resetBefore
            if(streamReset) {
                finishSegment()
                receive = ReceiveFormatter(options.receiveEnding, options.controlCharacters)
                simple = SimpleTerminalFilter()
                lastRx = null
                lastTx = null
                // This marker supplies a known stream boundary after a lost prefix.
                if(!visible) boundaryDiagnostic=false
            }
            val shown = visible && !(event.direction == Direction.TX && !options.echo) &&
                !(event.direction in listOf(Direction.STATUS, Direction.ERROR) && !options.showStatus)
            val record = if(shown) Record(event) else null
            val text = when {
                event.message != null -> if(event.direction == Direction.RX) filterRx(event.message) else event.message
                options.displayMode == "HEX" -> HexCodec.encode(event.data)
                event.direction == Direction.RX || event.direction == Direction.TX -> {
                    val isRx = event.direction == Direction.RX
                    val decoded = (if(isRx) rx else tx).decodeDetailed(event.data)
                    record?.issues?.addAll(decoded.issues)
                    if(isRx) lastRx = record else lastTx = record
                    if(isRx) filterRx(receive.feed(decoded.text)) else decoded.text
                }
                else -> ""
            }
            record?.let {
                val boundary=if(boundaryDiagnostic) "[上下文已裁剪：字符集和终端状态无法精确恢复，RAW/HEX 保留现存字节]\n" else ""
                val reset=if(streamReset) "[流中断：已重置解码状态]\n" else ""
                it.text = boundary + reset + text
                boundaryDiagnostic=false
                records += it
            }
        }

        val replay=context.replayPreceding
        if(replay != null) replay { process(it, false) }
        else context.precedingEvents.forEach { process(it, false) }
        events.forEach { process(it, true) }
        if(context.endOfInput) finishSegment()
        else lastRx?.let { it.text += filterRx(receive.finish()) }

        val time = SimpleDateFormat(if(options.datePrefix) "yyyy-MM-dd HH:mm:ss.SSS" else "HH:mm:ss.SSS", Locale.ROOT)
        return records.mapNotNull { record ->
            val diagnostic = record.issues.take(8).joinToString("") {
                "\n[${charset.name()} 编码错误 @${it.offset}，RAW HEX: ${it.rawHex}]"
            } + if(record.issues.size > 8) "\n[另有 ${record.issues.size-8} 处编码错误，完整字节保留在 RAW/HEX]" else ""
            val text = record.text + diagnostic
            if(text.isEmpty()) null else RenderedTextEvent(
                record.event.sequence, record.event.direction, text,
                if(options.timestamps) time.format(Date(record.event.timestamp)) else "",
                record.event.data.copyOf(), record.issues.toList(),
            )
        }
    }
}
