package com.lyufeng.phoneserialport

import com.lyufeng.phoneserialport.core.Direction
import com.lyufeng.phoneserialport.core.SerialEvent
import com.lyufeng.phoneserialport.data.AppSettings
import com.lyufeng.phoneserialport.data.EventStore
import com.lyufeng.phoneserialport.data.StaleEventPageException
import com.lyufeng.phoneserialport.terminal.*
import java.io.File
import java.nio.charset.Charset
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class EventTextRendererTest {
    private val options = AppSettings(timestamps = false, receiveEnding = "NONE")

    private fun event(sequence: Long, bytes: ByteArray, direction: Direction = Direction.RX) =
        SerialEvent("test", sequence, timestamp = 0, direction = direction, data = bytes)

    private fun text(records: List<RenderedTextEvent>) = records.joinToString("") { it.text }

    @Test fun rebuildingSplitPacketsKeepsRxAndTxSeparate() {
        for(name in listOf("UTF-8", "GBK", "UTF-16LE")) {
            val charset = Charset.forName(name)
            val rx = "串口".toByteArray(charset)
            val tx = "发送".toByteArray(charset)
            val events = mutableListOf<SerialEvent>()
            var sequence = 0L
            for(index in 0 until maxOf(rx.size, tx.size)) {
                if(index < rx.size) events += event(++sequence, byteArrayOf(rx[index]))
                if(index < tx.size) events += event(++sequence, byteArrayOf(tx[index]), Direction.TX)
            }
            val settings = options.copy(charset = name)
            val first = EventTextRenderer.formatEvents(events, settings)
            val rebuilt = EventTextRenderer.formatEvents(events, settings)
            assertEquals("串口", text(first.filter { it.direction == Direction.RX }))
            assertEquals("发送", text(first.filter { it.direction == Direction.TX }))
            assertEquals(text(first), text(rebuilt))
            assertTrue(first.all { it.issues.isEmpty() })
        }
    }

    @Test fun pageContextRestoresSplitCharacterWithoutDisplayingPrefix() {
        val bytes = "前中".toByteArray(Charsets.UTF_8)
        val prefix = event(1, bytes.copyOfRange(0, 4))
        val page = listOf(event(2, bytes.copyOfRange(4, bytes.size)))
        val restored = EventTextRenderer.formatEvents(page, options, TextRenderContext(precedingEvents = listOf(prefix)))
        assertEquals("中", text(restored))
        assertEquals(listOf(2L), restored.map { it.key })
        assertTrue(restored.single().issues.isEmpty())
        assertTrue(EventTextRenderer.formatEvents(page, options).single().issues.isNotEmpty())
    }

    @Test fun contextRestoresStatefulCharsetAndSimpleEscapeState() {
        val charset = Charset.forName("ISO-2022-JP")
        val bytes = "日本".toByteArray(charset)
        val restored = EventTextRenderer.formatEvents(
            listOf(event(2, bytes.copyOfRange(5, bytes.size))), options.copy(charset = charset.name()),
            TextRenderContext(precedingEvents = listOf(event(1, bytes.copyOfRange(0, 5)))),
        )
        assertEquals("本", text(restored))
        val simple = EventTextRenderer.formatEvents(
            listOf(event(2, "31mOK".toByteArray())), options.copy(displayMode = "SIMPLE"),
            TextRenderContext(precedingEvents = listOf(event(1, "hidden\u001b[".toByteArray()))),
        )
        assertEquals("OK", text(simple))
    }

    @Test fun validReplacementCharacterDoesNotTriggerEncodingIssue() {
        val bytes = "A\uFFFDB".toByteArray(Charsets.UTF_8)
        val decoder = StreamTextDecoder(Charsets.UTF_8)
        val decoded = decoder.decodeDetailed(bytes, true)
        assertEquals("A\uFFFDB", decoded.text)
        assertTrue(decoded.issues.isEmpty())
        val rendered = EventTextRenderer.formatEvents(listOf(event(1, bytes)), options)
        assertEquals("A\uFFFDB", text(rendered))
        assertTrue(rendered.single().issues.isEmpty())
    }

    @Test fun decoderReportsExactBadBytesAndRetainsFollowingText() {
        val decoder = StreamTextDecoder(Charsets.UTF_8)
        assertEquals("A", decoder.decode(byteArrayOf(0x41, 0xE4.toByte())))
        val decoded = decoder.decodeDetailed(byteArrayOf(0x42, 0xFF.toByte(), 0x43), true)
        assertEquals("\uFFFDB\uFFFDC", decoded.text)
        assertEquals(listOf(1L, 3L), decoded.issues.map { it.offset })
        assertEquals(listOf("E4", "FF"), decoded.issues.map { it.rawHex })
        assertTrue(decoded.issues.all { it.kind == DecodeErrorKind.MALFORMED })
    }

    @Test fun invalidEncodingRemainsVisibleWhenStatusIsHiddenAndHexIsExact() {
        val bytes = byteArrayOf(0x41, 0xFF.toByte(), 0x42)
        val events = listOf(event(1, bytes))
        val rendered = EventTextRenderer.formatEvents(events, options.copy(showStatus = false)).single()
        assertTrue(rendered.text.startsWith("A\uFFFDB"))
        assertTrue(rendered.text.contains("RAW HEX: FF"))
        assertEquals("41 FF 42", rendered.rawHex)
        assertArrayEquals(bytes, events.single().data)
        assertEquals("41 FF 42", text(EventTextRenderer.formatEvents(events, options.copy(displayMode = "HEX"))))
    }

    @Test fun liveIncompleteCharacterWaitsUntilEndOfInput() {
        val events = listOf(event(1, byteArrayOf(0xE4.toByte(), 0xB8.toByte())))
        assertTrue(EventTextRenderer.formatEvents(events, options).isEmpty())
        val ended = EventTextRenderer.formatEvents(events, options, TextRenderContext(endOfInput = true)).single()
        assertTrue(ended.text.startsWith("\uFFFD"))
        assertEquals("E4 B8", ended.issues.single().rawHex)
    }

    @Test fun knownGapCannotJoinUnrelatedBytesIntoAValidCharacter() {
        val events = listOf(
            event(1, byteArrayOf(0xE4.toByte())),
            event(3, byteArrayOf(0xB8.toByte(), 0x82.toByte())),
        )
        assertEquals("丂", text(EventTextRenderer.formatEvents(events, options)))
        val split = EventTextRenderer.formatEvents(events, options, TextRenderContext(resetBefore = setOf(3L)))
        assertFalse(text(split).contains("丂"))
        assertEquals("E4", split.first().issues.single().rawHex)
        assertEquals("B8 82", split.last().rawHex)
    }

    @Test fun trailingCrIsVisibleAndRebuildStillMergesNextLf() {
        val settings = options.copy(receiveEnding = "CRLF")
        val first = event(1, "abc\r".toByteArray())
        assertEquals("abc\r", text(EventTextRenderer.formatEvents(listOf(first), settings)))
        val second = event(2, "\ndef\r".toByteArray())
        assertEquals("abc\ndef\r", text(EventTextRenderer.formatEvents(listOf(first, second), settings)))
        assertEquals("\ndef\r", text(EventTextRenderer.formatEvents(
            listOf(second), settings, TextRenderContext(precedingEvents = listOf(first)),
        )))
        val formatter = ReceiveFormatter("CRLF", false)
        assertEquals("abc", formatter.feed("abc\r"))
        assertEquals("\r", formatter.finish())
        assertEquals("", formatter.finish())
    }

    @Test fun explicitResetFinishesTheOldDecoderAndAcceptsNewBytes() {
        val decoder = StreamTextDecoder(Charsets.UTF_8)
        assertEquals("", decoder.decode(byteArrayOf(0xE4.toByte())))
        val tail = decoder.finishAndReset()
        assertEquals("E4", tail.issues.single().rawHex)
        val next = decoder.decodeDetailed("中".toByteArray(Charsets.UTF_8), true)
        assertEquals("中", next.text)
        assertTrue(next.issues.isEmpty())
    }

    @Test fun largeDecodeDrainsOutputWithoutLosingCharacters() {
        val expected = "串口\uD83D\uDE00".repeat(3000)
        val result = StreamTextDecoder(Charsets.UTF_8).decodeDetailed(expected.toByteArray(Charsets.UTF_8), true)
        assertEquals(expected, result.text)
        assertTrue(result.issues.isEmpty())
    }

    private fun withStore(block: (EventStore) -> Unit) {
        val base=File("D:/Data/.temp").apply { mkdirs() }
        val directory=Files.createTempDirectory(base.toPath(),"serial-page-context-").toFile()
        val store=EventStore(directory,"test")
        try { block(store) } finally { store.close(); directory.deleteRecursively() }
    }

    @Test fun diskReplayStartsAtClearBoundaryAndRunsOutsideStoreLock() = withStore { store ->
        store.setLimit(0)
        store.append(event(1,"old".toByteArray()))
        store.clear()
        for(sequence in 2L..141L) store.append(event(sequence,byteArrayOf(65)))
        val snapshot=store.pageWithContext()
        val prefix=mutableListOf<Long>()
        snapshot.replayPreceding {
            assertFalse(Thread.holdsLock(store))
            prefix += it.sequence
        }
        assertEquals((2L..13L).toList(),prefix)
        assertEquals((14L..141L).toList(),snapshot.events.map { it.sequence })
        assertEquals(2,snapshot.pageCount)
        store.undo()
        assertThrows(StaleEventPageException::class.java) { snapshot.replayPreceding {} }
        val restored=store.pageWithContext()
        val restoredPrefix=mutableListOf<Long>()
        restored.replayPreceding { restoredPrefix += it.sequence }
        assertEquals((1L..13L).toList(),restoredPrefix)
    }

    @Test fun diskReplayRestoresCharsetStateFromAnArbitrarilyOldEscape() = withStore { store ->
        store.setLimit(0)
        val charset=Charset.forName("ISO-2022-JP")
        val bytes=byteArrayOf(0x1B,0x24,0x42)+
            ByteArray(600) { if(it % 2 == 0) 0x46.toByte() else 0x7C.toByte() }+
            byteArrayOf(0x1B,0x28,0x42)
        assertEquals(606,bytes.size)
        bytes.forEachIndexed { index, byte -> store.append(event(index.toLong(),byteArrayOf(byte))) }
        val snapshot=store.pageWithContext()
        val rendered=EventTextRenderer.formatEvents(snapshot.events,options.copy(charset=charset.name()),
            TextRenderContext(replayPreceding=snapshot.replayPreceding))
        // The page starts with the second byte of a character. Include that
        // character. The final ESC ( B adds no text.
        assertEquals(478L,snapshot.events.first().sequence)
        assertEquals("日".repeat(63),text(rendered))
        assertTrue(rendered.all { it.issues.isEmpty() })
    }

    @Test fun appendDoesNotChangeAnExistingPageOrInvalidateItsReplay() = withStore { store ->
        store.setLimit(0)
        for(sequence in 1L..260L) store.append(event(sequence,byteArrayOf(65)))
        val snapshot=store.pageWithContext(1)
        store.append(event(261,byteArrayOf(66)))
        val prefix=mutableListOf<Long>()
        snapshot.replayPreceding { prefix += it.sequence }
        assertEquals(listOf(1L,2L,3L,4L),prefix)
        assertEquals((5L..132L).toList(),snapshot.events.map { it.sequence })
        assertEquals(snapshot.contextVersion,store.contextVersion())
        store.clear()
        assertNotEquals(snapshot.contextVersion,store.contextVersion())
        assertThrows(StaleEventPageException::class.java) { snapshot.replayPreceding {} }
    }

    @Test fun diskRoundTripPreservesStreamResetAndRawData() = withStore { store ->
        store.setLimit(0)
        val bytes=byteArrayOf(0xFF.toByte(),0,0x41)
        store.append(event(1,bytes).copy(streamReset=true))
        store.append(event(2,byteArrayOf(66)))
        val restored=store.pageWithContext().events
        assertTrue(restored[0].streamReset)
        assertFalse(restored[1].streamReset)
        assertArrayEquals(bytes,restored[0].data)
    }

    @Test fun resetMarkerIsVisibleWithStatusHiddenAndDoesNotDependOnSequenceGaps() {
        val first=event(1,byteArrayOf(0xE4.toByte()))
        val second=event(3,byteArrayOf(0xB8.toByte(),0x82.toByte()))
        assertEquals("丂",text(EventTextRenderer.formatEvents(listOf(first,second),options)))
        val reset=EventTextRenderer.formatEvents(listOf(first,second.copy(streamReset=true)),options.copy(showStatus=false))
        assertTrue(reset.last().text.startsWith("[流中断：已重置解码状态]"))
        assertFalse(text(reset).contains("丂"))
        assertEquals("E4",reset.first().issues.single().rawHex)
        assertEquals("B8 82",reset.last().rawHex)
    }

    @Test fun trimmedBoundaryStaysExplicitAfterCountersResetAndModeChanges() = withStore { store ->
        store.setLimit(2048)
        store.append(event(1,byteArrayOf(0xE4.toByte(),0xB8.toByte(),0xAD.toByte())+ByteArray(2046) {65}))
        store.resetTrimmedCount()
        assertEquals(0L,store.trimmedBytes)
        val snapshot=store.pageWithContext()
        assertTrue(snapshot.historyTruncated)
        val rendered=EventTextRenderer.formatEvents(snapshot.events,options.copy(showStatus=false),
            TextRenderContext(historyTruncated=snapshot.historyTruncated))
        assertTrue(text(rendered).startsWith("[上下文已裁剪"))
        assertEquals("B8",rendered.first().issues.first().rawHex)
        assertArrayEquals(snapshot.events.first().data,rendered.first().rawBytes)
        store.setLimit(0)
        assertTrue(store.pageWithContext().historyTruncated)
        store.clear()
        assertFalse(store.pageWithContext().historyTruncated)
        store.undo()
        assertTrue(store.pageWithContext().historyTruncated)
    }

    @Test fun diagnosticTextShowsOnlyEightSamplesAndKeepsAllBytes() {
        val bytes=ByteArray(1000) {0xFF.toByte()}
        val rendered=EventTextRenderer.formatEvents(listOf(event(1,bytes)),options).single()
        assertEquals(1000,rendered.issues.size)
        assertEquals(8,Regex("RAW HEX:").findAll(rendered.text).count())
        assertTrue(rendered.text.contains("另有 992 处编码错误"))
        assertArrayEquals(bytes,rendered.rawBytes)
        assertEquals(HexCodec.encode(bytes),rendered.rawHex)
    }
}
