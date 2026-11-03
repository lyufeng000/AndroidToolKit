package com.lyufeng.phoneserialport

import com.lyufeng.phoneserialport.core.*
import com.lyufeng.phoneserialport.data.*
import com.lyufeng.phoneserialport.serial.Ch341Divisor
import com.lyufeng.phoneserialport.terminal.*
import com.lyufeng.phoneserialport.transfer.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.charset.Charset
import java.nio.file.Files

class SerialLogicTest {
    @Test fun hexAcceptsWhitespaceAndRejectsPartialOrInvalidBytes() {
        assertArrayEquals(byteArrayOf(0,10,-1),HexCodec.decode("00 0a\nFF"))
        assertEquals("00 0A FF",HexCodec.encode(byteArrayOf(0,10,-1)))
        assertThrows(IllegalArgumentException::class.java) { HexCodec.decode("ABC") }
        assertThrows(IllegalArgumentException::class.java) { HexCodec.decode("GG") }
    }
    @Test fun decoderRetainsSplitUtf8AndGbkCharacters() {
        for(name in listOf("UTF-8","GBK")) {
            val charset=Charset.forName(name);val source="串口✓".let {if(name == "GBK") "串口数据" else it}
            val bytes=source.toByteArray(charset);val decoder=StreamTextDecoder(charset)
            assertEquals(source,bytes.joinToString("") {decoder.decode(byteArrayOf(it))}+decoder.decode(byteArrayOf(),true))
        }
    }
    @Test fun crlfAndStxBoundariesSurviveSplitPackets() {
        val formatter=ReceiveFormatter("CRLF",false)
        assertEquals("abc",formatter.feed("abc\r"))
        assertEquals("\ndef",formatter.feed("\ndef\r"))
        assertEquals("\rx",formatter.feed("x"))
        val stx=ReceiveFormatter("STX_ETX",false)
        assertEquals("hello\n",stx.feed("\u0002hello\u0003"))
    }
    @Test fun commandEndingsOperateOnBytesInBothModes() {
        assertArrayEquals(byteArrayOf(2,65,3),encodeCommand("41",true,"UTF-8","STX_ETX","NONE"))
        assertArrayEquals(byteArrayOf(65,13,10),encodeCommand("A",false,"UTF-8","AUTO","CRLF"))
        assertThrows(IllegalArgumentException::class.java) {encodeCommand("",false,"UTF-8","CRLF","NONE")}
    }
    @Test fun uartFrameValidationRejectsUnsupportedStopBitsAndFlowControl() {
        val caps=SerialCapabilities()
        caps.validate(SerialConfig())
        assertThrows(IllegalArgumentException::class.java) {caps.validate(SerialConfig(stopBits=StopBits.ONE_POINT_FIVE))}
        assertThrows(IllegalArgumentException::class.java) {caps.validate(SerialConfig(flowControl=FlowControl.RTS_CTS))}
        assertThrows(IllegalArgumentException::class.java) {SerialConfig(startBits=2).validate()}
    }
    @Test fun baudReportNeverConfusesRequestEstimateAndMeasurement() {
        assertEquals(1200000.0,Ch341Divisor.linuxEstimate(1152000)!!,0.01)
        assertEquals(1000000.0,Ch341Divisor.androidEstimate(1152000)!!,0.01)
        val report=BaudRateReport(1152000,divisorEstimated=1200000.0)
        assertNull(report.driverReported);assertNull(report.externallyMeasured);assertFalse(report.measuredPass)
        assertFalse(report.copy(externallyMeasured=1200000.0).measuredPass)
        assertTrue(report.copy(externallyMeasured=1152000.0).measuredPass)
    }
    @Test fun vt100KeepsEscapeStateAndSupportsSavedCursorAndAttributes() {
        val vt=VtTerminal(80,24)
        vt.feed("abc\u001b["); vt.feed("2;3H\u001b[1;31mZ\u001b7\u001b[4;8HQ\u001b8R")
        val screen=vt.snapshot()
        assertEquals('Z',screen.rows[1][2].char);assertEquals('R',screen.rows[1][3].char)
        assertEquals(1,screen.rows[1][2].foreground);assertTrue(screen.rows[1][2].bold)
        vt.feed("\u001b[2J");assertTrue(vt.snapshot().rows.flatten().all {it.char == ' '})
    }
    @Test fun vt100ScrollRegionDoesNotEraseOutsideRows() {
        val vt=VtTerminal(20,5)
        vt.feed("TOP\u001b[5;1HBOTTOM\u001b[2;4r\u001b[4;1Hline\nnext")
        assertEquals("TOP",vt.snapshot().rows[0].take(3).map {it.char}.joinToString(""))
        assertEquals("BOTTOM",vt.snapshot().rows[4].take(6).map {it.char}.joinToString(""))
    }
    @Test fun vt100DoesNotJoinAnEscapeSequenceAcrossAStreamGap() {
        val vt=VtTerminal(80,24)
        vt.feed("OLD\u001b[")
        vt.resetInputState()
        vt.feed("31mTEXT")
        assertEquals("OLD31mTEXT",vt.snapshot().rows.first().take(10).map {it.char}.joinToString(""))
    }
    @Test fun vt100ClampsLargeCursorArgumentsAndIgnoresMalformedParameters() {
        val vt=VtTerminal(20,5)
        vt.feed("\u001b[2;2H\u001b[2147483647BA")
        assertEquals('A',vt.snapshot().rows[4][1].char)
        vt.feed("\u001b[-999AX\u001b[999999999999999999999999CY")
        assertEquals('X',vt.snapshot().rows[4][2].char)
        assertEquals('Y',vt.snapshot().rows[4][19].char)
        vt.feed("\u001b[2;2H\u001b[2147483647X")
        assertTrue(vt.snapshot().rows[1].all {it.char == ' '})
    }
    @Test fun vt100ResetRestoresWrapCursorKeysAndSavedCursor() {
        val vt=VtTerminal(20,5)
        vt.feed("\u001b[?1h\u001b[?7l\u001b[3;4H\u001b7\u001bc")
        assertFalse(vt.cursorKeysApplication)
        vt.feed("\u001b8")
        assertEquals(0,vt.snapshot().cursorRow);assertEquals(0,vt.snapshot().cursorColumn)
        vt.feed("A".repeat(20)+"B")
        assertEquals('B',vt.snapshot().rows[1][0].char)
    }
    @Test fun vt100ChangingAttributesAtTheRightMarginKeepsPendingWrap() {
        val vt=VtTerminal(20,5)
        vt.feed("A".repeat(20)+"\u001b[31mB")
        assertEquals('A',vt.snapshot().rows[0][19].char)
        assertEquals('B',vt.snapshot().rows[1][0].char)
        assertEquals(1,vt.snapshot().rows[1][0].foreground)
    }
    @Test fun xmodemKnownCrcAndBlockWrap() {
        assertEquals(0x31c3,XmodemPacket.crc("123456789".toByteArray()))
        val packet=XmodemPacket.build(256,ByteArray(128) {1},true)
        assertEquals(133,packet.size);assertEquals(0,packet[1].toInt());assertEquals(255,packet[2].toInt() and 255)
    }
    @Test fun xmodemRetriesPacketThenCompletesEndHandshake() = runBlocking {
        val responses=Channel<Int>(100);responses.send('C'.code)
        val writes=mutableListOf<ByteArray>();var packets=0;var confirmed=0L
        XmodemSender(responses,{bytes ->
            writes+=bytes
            if(bytes.size == 1) responses.send(XmodemPacket.ACK)
            else responses.send(if(++packets == 1) XmodemPacket.NAK else XmodemPacket.ACK)
        },{confirmed=it},timeoutMs=100).send(ByteArrayInputStream(byteArrayOf(1,2,3)),FileMode.XMODEM_CRC)
        assertEquals(3L,confirmed);assertArrayEquals(writes[0],writes[1]);assertEquals(3,writes.size)
    }
    @Test fun xmodemStopsAfterInitialSendPlusThreeRetries() = runBlocking {
        val responses=Channel<Int>(100);responses.send(XmodemPacket.NAK);var packets=0
        try {
            XmodemSender(responses,{bytes ->if(bytes.size > 1) {packets++;responses.send(XmodemPacket.NAK)}},{},timeoutMs=100).send(ByteArrayInputStream(byteArrayOf(1)),FileMode.XMODEM)
            fail("Expected retry limit")
        } catch(e: IllegalStateException) {assertEquals(4,packets)}
    }
    @Test fun xmodemHandlesRemoteCancellationAndHandshakeTimeout() = runBlocking {
        val cancel=Channel<Int>(10);cancel.send(24);cancel.send(24)
        try {XmodemSender(cancel,{}, {},timeoutMs=20).send(ByteArrayInputStream(byteArrayOf(1)),FileMode.XMODEM);fail("Expected cancel")}
        catch(e: IllegalStateException) {assertTrue(e.message!!.contains("cancelled"))}
        val empty=Channel<Int>(10)
        try {XmodemSender(empty,{}, {},timeoutMs=10).send(ByteArrayInputStream(byteArrayOf(1)),FileMode.XMODEM);fail("Expected timeout")}
        catch(e: IllegalStateException) {assertTrue(e.message!!.contains("timed out"))}
    }
    @Test fun displayStoreCountsRawBytesAndRestoresOneClear() {
        val dir=Files.createTempDirectory("serial-store-").toFile()
        try {
            val store=EventStore(dir,"test");store.setLimit(2048)
            store.append(SerialEvent("test",1,direction=Direction.RX,data=ByteArray(3000) {7}))
            assertEquals(2048,store.current().sumOf {it.data.size});assertEquals(952L,store.trimmedBytes)
            store.clear();assertTrue(store.current().isEmpty());store.undo();assertEquals(2048,store.current().sumOf {it.data.size})
            store.close()
        } finally {dir.deleteRecursively()}
    }
    @Test fun unlimitedDisplayUsesDiskIndexAndPaginatesWithoutDuplicatingEvents() {
        val dir=Files.createTempDirectory("serial-pages-").toFile()
        try {
            val store=EventStore(dir,"test");store.setLimit(0)
            repeat(300) {store.append(SerialEvent("test",it.toLong(),direction=Direction.RX,data=byteArrayOf(1)))}
            assertEquals(3,store.pageCount());assertEquals(128,store.page().size)
            val exported=mutableListOf<Long>();store.export {exported+=it.sequence}
            assertEquals((0L until 300L).toList(),exported)
            store.clear();assertTrue(store.page().isEmpty());store.undo();assertEquals(3,store.pageCount());store.close()
        } finally {dir.deleteRecursively()}
    }
    @Test fun returningFromDiskToFixedBufferCountsEveryDiscardedByte() {
        val dir=Files.createTempDirectory("serial-limit-").toFile()
        try {
            val store=EventStore(dir,"limit");store.setLimit(0)
            repeat(400) {store.append(SerialEvent("limit",it.toLong(),direction=Direction.RX,data=ByteArray(1024)))}
            store.setLimit(200*1024)
            assertEquals(200*1024,store.current().sumOf {it.data.size})
            assertEquals(200*1024L,store.trimmedBytes)
            store.close()
        } finally {dir.deleteRecursively()}
    }
    @Test fun diskBufferAccountingFollowsClearAndOneUndo() {
        for(undo in listOf(false,true)) {
            val dir=Files.createTempDirectory("serial-clear-limit-").toFile()
            try {
                val store=EventStore(dir,"limit");store.setLimit(0)
                repeat(200) {store.append(SerialEvent("limit",it.toLong(),direction=Direction.RX,data=ByteArray(1024)))}
                store.clear()
                repeat(100) {store.append(SerialEvent("limit",200L+it,direction=Direction.RX,data=ByteArray(1024)))}
                if(undo) {store.undo();store.undo()}
                store.setLimit(10*1024)
                assertEquals((if(undo) 290L else 90L)*1024,store.trimmedBytes)
                assertEquals(10*1024,store.current().sumOf {it.data.size})
                store.close()
            } finally {dir.deleteRecursively()}
        }
    }
    @Test fun failedDiskModeCreationPreservesTheExistingMemoryWindow() {
        val dir=Files.createTempDirectory("serial-disk-failure-").toFile()
        try {
            val store=EventStore(dir,"blocked")
            store.append(SerialEvent("blocked",1,direction=Direction.RX,data=byteArrayOf(1,2)))
            java.io.File(dir,"blocked.index").mkdir()
            assertThrows(java.io.IOException::class.java) {store.setLimit(0)}
            store.append(SerialEvent("blocked",2,direction=Direction.RX,data=byteArrayOf(3)))
            assertEquals(listOf(1L,2L),store.current().map {it.sequence})
            assertEquals(0L,store.trimmedBytes)
            store.close()
        } finally {dir.deleteRecursively()}
    }
    @Test fun undoDoesNotGrowTheBoundedStatusWindowIndefinitely() {
        val dir=Files.createTempDirectory("serial-status-window-").toFile()
        try {
            val store=EventStore(dir,"status")
            repeat(3) {cycle ->
                repeat(600) {store.append(SerialEvent("status",(cycle*600+it).toLong(),direction=Direction.STATUS,message="status"))}
                store.clear()
                repeat(600) {store.append(SerialEvent("status",10000L+cycle*600+it,direction=Direction.STATUS,message="new"))}
                store.undo()
                assertEquals(512,store.current().size)
            }
            store.close()
        } finally {dir.deleteRecursively()}
    }
    @Test fun settingsRoundTripValidatesTypesDefaultsAndVersion() {
        val settings=AppSettings(macroRows=5,bufferBytes=0,terminalColumns=120)
        assertEquals(settings,SettingsCodec.decode(SettingsCodec.encode(settings)))
        assertEquals(115200,SettingsCodec.decode(JSONObject()).config.baudRate)
        assertTrue(115200 in SerialPresets.baudRates)
        assertTrue(1152000 in SerialPresets.baudRates)
        assertEquals(SerialPresets.baudRates.sorted(),SerialPresets.baudRates)
        assertEquals(SerialPresets.baudRates.distinct(),SerialPresets.baudRates)
        assertThrows(IllegalArgumentException::class.java) {SettingsCodec.decode(JSONObject().put("macroRows","5"))}
        assertThrows(IllegalArgumentException::class.java) {SettingsCodec.decode(JSONObject().put("version",999))}
    }
    @Test fun xmodemOneKUsesStxAndPadsLastBlock() = runBlocking {
        val responses=Channel<Int>(10);responses.send('C'.code);val packets=mutableListOf<ByteArray>()
        XmodemSender(responses,{bytes ->packets+=bytes;responses.send(XmodemPacket.ACK)},{},timeoutMs=100)
            .send(ByteArrayInputStream(byteArrayOf(42)),FileMode.XMODEM_1K)
        assertEquals(1029,packets[0].size);assertEquals(2,packets[0][0].toInt());assertEquals(42,packets[0][3].toInt());assertEquals(0x1a,packets[0][4].toInt())
    }
    @Test fun xmodemSupportsTwoEotReceiver() = runBlocking {
        val responses=Channel<Int>(10);responses.send('C'.code);var ends=0
        XmodemSender(responses,{bytes ->responses.send(if(bytes.size == 1 && ++ends == 1) XmodemPacket.NAK else XmodemPacket.ACK)},{},timeoutMs=100)
            .send(ByteArrayInputStream(byteArrayOf(1)),FileMode.XMODEM_CRC)
        assertEquals(2,ends)
    }
    @Test fun xmodemSequenceWrapsAfterBlock255() = runBlocking {
        val responses=Channel<Int>(10);responses.send('C'.code);val numbers=mutableListOf<Int>();var done=0L
        XmodemSender(responses,{bytes ->if(bytes.size > 1) numbers+=bytes[1].toInt() and 255;responses.send(XmodemPacket.ACK)},{done=it},timeoutMs=100)
            .send(ByteArrayInputStream(ByteArray(128*257)),FileMode.XMODEM_CRC)
        assertEquals(listOf(254,255,0,1),numbers.takeLast(4));assertEquals(128L*257,done)
    }
    @Test fun xmodemCancellationInterruptsHandshake() = runBlocking {
        val responses=Channel<Int>(10);var transmitted=false
        val transfer=launch {XmodemSender(responses,{transmitted=true},{},timeoutMs=10000).send(ByteArrayInputStream(byteArrayOf(1)),FileMode.XMODEM)}
        yield();transfer.cancelAndJoin();assertTrue(transfer.isCancelled);assertFalse(transmitted)
    }
    @Test fun nestedConfigTypesCannotBeCoercedFromStrings() {
        assertThrows(IllegalArgumentException::class.java) {SettingsCodec.decode(JSONObject().put("config",JSONObject().put("baudRate","1152000")))}
    }
    @Test fun adapterReportsConfirmedPartialWriteWithoutRetryingWholeCommand() {
        var writes=0
        val driver=java.lang.reflect.Proxy.newProxyInstance(javaClass.classLoader,arrayOf(com.hoho.android.usbserial.driver.UsbSerialDriver::class.java)) {_,_,_->null}
        val port=java.lang.reflect.Proxy.newProxyInstance(javaClass.classLoader,arrayOf(com.hoho.android.usbserial.driver.UsbSerialPort::class.java)) {_,method,_->when(method.name) {
            "getDriver" -> driver
            "write" -> {writes++;throw com.hoho.android.usbserial.driver.SerialTimeoutException("short write",3)}
            else -> null
        }} as com.hoho.android.usbserial.driver.UsbSerialPort
        val result=com.lyufeng.phoneserialport.serial.UsbDriverAdapter(port).write(ByteArray(10),100)
        assertEquals(3,result.confirmed);assertNotNull(result.error);assertFalse(result.completionUnknown);assertEquals(1,writes)
    }
    @Test fun adapterDoesNotInventCompletionOnIoFailure() {
        val driver=java.lang.reflect.Proxy.newProxyInstance(javaClass.classLoader,arrayOf(com.hoho.android.usbserial.driver.UsbSerialDriver::class.java)) {_,_,_->null}
        val port=java.lang.reflect.Proxy.newProxyInstance(javaClass.classLoader,arrayOf(com.hoho.android.usbserial.driver.UsbSerialPort::class.java)) {_,method,_->when(method.name) {
            "getDriver" -> driver
            "write" -> throw java.io.IOException("detached")
            else -> null
        }} as com.hoho.android.usbserial.driver.UsbSerialPort
        val result=com.lyufeng.phoneserialport.serial.UsbDriverAdapter(port).write(ByteArray(10),100)
        assertEquals(0,result.confirmed);assertTrue(result.completionUnknown);assertNotNull(result.error)
    }
    @Test fun displayByteLimitDoesNotBecomeAPacketCountLimit() {
        val dir=Files.createTempDirectory("serial-tiny-packets-").toFile()
        try {
            val store=EventStore(dir,"test");store.setLimit(10240)
            repeat(6000) {store.append(SerialEvent("test",it.toLong(),direction=Direction.RX,data=byteArrayOf(1)))}
            assertEquals(6000,store.current().sumOf {it.data.size});assertEquals(0L,store.trimmedBytes);store.close()
        } finally {dir.deleteRecursively()}
    }
    @Test fun completeCharacterPacingDoesNotRewriteUtf16OrInsertExtraBom() {
        for(name in listOf("UTF-8","UTF-16","GB18030")) {
            val charset=Charset.forName(name);val bytes="串口😀AB".toByteArray(charset)
            val chunks=characterChunks(bytes,charset)
            assertArrayEquals(bytes,chunks.fold(byteArrayOf()) {all,part->all+part})
            assertEquals(5,chunks.size)
        }
    }
}
