package com.lyufeng.phoneserialport

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import com.lyufeng.phoneserialport.core.*
import com.lyufeng.phoneserialport.data.AppSettings
import com.lyufeng.phoneserialport.data.CommunicationLog
import com.lyufeng.phoneserialport.data.VofaCommand
import com.lyufeng.phoneserialport.serial.SerialSession
import com.lyufeng.phoneserialport.transfer.FileMode
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[37],application=Application::class)
class SerialSessionLifecycleTest {
    private val sessions=mutableListOf<SerialSession>()
    private val failures=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private suspend fun session(driver: FakeDriver=FakeDriver(),options: AppSettings=AppSettings(),port: Int=0,
        context: Context=RuntimeEnvironment.getApplication(),dispatcher: CoroutineDispatcher=Dispatchers.IO): SerialSession {
        val result=SerialSession(context,"session-${sessions.size}","test-device",port,driver,options,dispatcher) {s,error ->
            failures.launch {s.close(true,error.message)}
        }
        sessions+=result;result.start(options.config)
        return result
    }
    private suspend fun await(condition: ()->Boolean) = withTimeout(3000) {while(!condition()) delay(5)}
    @After fun cleanup() = runBlocking {
        sessions.forEach {it.close();it.store.close()}
        failures.cancel()
    }
    @Test fun partialWriteCountsConfirmedBytesWithoutRetryingTheCommand() = runBlocking {
        val driver=FakeDriver().apply {writeResponse={WriteResult(3,IOException("partial"))}}
        val s=session(driver)
        val result=s.send(byteArrayOf(1,2,3,4,5,6))
        assertEquals(3,result.confirmed);assertNotNull(result.error)
        await {s.state.value.counters.sent == 3L}
        assertEquals(1,driver.writes.size)
        await {s.store.current().filter {it.direction == Direction.TX}.sumOf {it.data.size} == 3}
    }
    @Test fun failedConfigurationRollsBackBeforeReportingTheAppliedValue() = runBlocking {
        val driver=FakeDriver();val s=session(driver);val original=s.state.value.appliedConfig
        driver.rejectConfiguration={it.baudRate == 57600}
        val error=runCatching {s.apply(SerialConfig(baudRate=57600))}.exceptionOrNull()
        assertNotNull(error);assertEquals(original,s.state.value.appliedConfig)
        assertEquals(original,driver.current);assertTrue(s.state.value.error!!.contains("rollback=true"))
    }
    @Test fun applyingUnchangedParametersDoesNotTouchTheDeviceAgain() = runBlocking {
        val driver=FakeDriver();val s=session(driver)
        driver.rejectConfiguration={true}
        s.apply(SerialConfig())
        assertEquals(PortState.OPEN,s.state.value.state)
        assertNull(s.state.value.error)
        assertEquals(SerialConfig(),s.state.value.appliedConfig)
    }
    @Test fun openingKeepsBaudDiagnosticsWithoutAddingAdvisoriesToTheTerminal() = runBlocking {
        val driver=FakeDriver().apply {baudEstimate=1_200_000.0}
        val s=session(driver,AppSettings(config=SerialConfig(baudRate=1152000)))
        await {s.store.current().any {it.direction == Direction.STATUS && it.message?.contains("Port opened") == true}}
        assertFalse(s.store.current().any {it.message?.contains("Estimated baud deviation") == true})
        assertEquals(1152000,s.state.value.appliedConfig!!.baudRate)
        assertTrue(s.state.value.baudReport!!.estimatedOutsideTolerance)
        assertNull(s.state.value.baudReport!!.externallyMeasured)
    }
    @Test fun changingParametersMarksTheNextReceiveAsANewByteStream() = runBlocking {
        val driver=FakeDriver();val s=session(driver)
        driver.receive(byteArrayOf(0xe4.toByte()))
        await {s.store.current().any {it.direction == Direction.RX}}
        s.apply(SerialConfig(baudRate=57600))
        driver.receive(byteArrayOf(0xb8.toByte(),0x82.toByte()))
        await {s.store.current().count {it.direction == Direction.RX} == 2}
        val incoming=s.store.current().filter {it.direction == Direction.RX}
        assertFalse(incoming.first().streamReset)
        assertTrue(incoming.last().streamReset)
        assertFalse(s.terminal.snapshot().rows.flatten().any {it.char == '丂'})
    }
    @Test fun stoppingAVofaLoopCancelsFurtherWritesAndKeepsThePortOpen()=runBlocking {
        val driver=FakeDriver();val s=session(driver)
        s.runVofa(VofaCommand("loop",group=true,repeats=-1,delayMs=1,children=listOf(VofaCommand("SC","53 43 0A",delayMs=2))))
        await {driver.writes.size >= 2}
        s.stopSending()
        await {!s.state.value.repeating}
        val count=driver.writes.size
        delay(30)
        assertEquals(count,driver.writes.size)
        assertTrue(driver.writes.all {it.contentEquals("SC\n".toByteArray())})
        assertEquals(PortState.OPEN,s.state.value.state)
    }
    @Test fun commandGroupReservesThePortBeforeStartingAndRejectsOtherWriters()=runBlocking {
        val dispatcher=HeldDispatcher();val driver=FakeDriver();val s=session(driver,dispatcher=dispatcher)
        val command=VofaCommand("reserved","AA",delayMs=0)
        try {
            s.runVofa(command)
            assertEquals("reserved",s.state.value.commandGroupName)
            assertNotNull(s.send(byteArrayOf(2)).error)
            assertThrows(IllegalStateException::class.java) {s.runVofa(command)}
            assertThrows(IllegalStateException::class.java) {s.repeatSend(byteArrayOf(2),1,10)}
            assertThrows(IllegalStateException::class.java) {s.runAtCommands()}
            assertThrows(IllegalStateException::class.java) {s.transfer("blocked",1,FileMode.RAW) {byteArrayOf(2).inputStream()}}
            assertTrue(driver.writes.isEmpty())
        } finally {s.stopSending();dispatcher.runQueued()}
        await {s.state.value.commandGroupName == null}
        assertFalse(s.state.value.repeating)
        assertEquals(1,s.send(byteArrayOf(3)).confirmed)
    }
    @Test fun simultaneousCommandGroupStartsAcceptOnlyOneTask()=runBlocking {
        val dispatcher=HeldDispatcher();val driver=FakeDriver();val s=session(driver,dispatcher=dispatcher)
        try {
            val accepted=(1..16).map {async(Dispatchers.Default) {
                runCatching {s.runVofa(VofaCommand("group-$it","AA",delayMs=0))}.isSuccess
            }}.awaitAll()
            assertEquals(1,accepted.count {it})
            assertNotNull(s.state.value.commandGroupName)
            assertTrue(driver.writes.isEmpty())
        } finally {s.stopSending();dispatcher.runQueued()}
        await {s.state.value.commandGroupName == null}
    }
    @Test fun commandGroupRejectsPendingRepeatAtAndFileTasks()=runBlocking {
        val dispatcher=HeldDispatcher();val s=session(dispatcher=dispatcher)
        val group=VofaCommand("group","AA",delayMs=0)
        try {
            s.repeatSend(byteArrayOf(1),1,10)
            assertThrows(IllegalStateException::class.java) {s.runVofa(group)}
            s.stopRepeat();dispatcher.runQueued()
            s.runAtCommands()
            assertThrows(IllegalStateException::class.java) {s.runVofa(group)}
            s.stopRepeat();dispatcher.runQueued()
            s.transfer("pending",1,FileMode.RAW) {byteArrayOf(1).inputStream()}
            assertThrows(IllegalStateException::class.java) {s.runVofa(group)}
        } finally {s.stopSending();s.cancelTransfer();dispatcher.runQueued()}
        Unit
    }
    @Test fun commandGroupRejectsActiveAndQueuedManualSends()=runBlocking {
        val driver=FakeDriver();val s=session(driver)
        val sending=async {s.send("AB".toByteArray(),AppSettings(charDelayMs=60000))}
        await {driver.writes.size == 1}
        val pending=async(start=CoroutineStart.UNDISPATCHED) {s.send(byteArrayOf(2))}
        try {
            assertThrows(IllegalStateException::class.java) {s.runVofa(VofaCommand("group","AA",delayMs=0))}
        } finally {s.stopSending();sending.join();pending.join()}
        assertTrue(sending.isCancelled);assertTrue(pending.isCancelled)
    }
    @Test fun commandGroupOwnsNestedStepListsBeforeItsTaskStarts()=runBlocking {
        val dispatcher=HeldDispatcher();val driver=FakeDriver();val s=session(driver,dispatcher=dispatcher)
        val nested=mutableListOf(VofaCommand("original","AA",delayMs=0))
        val children=mutableListOf(VofaCommand("nested",group=true,children=nested,delayMs=0))
        try {
            s.runVofa(VofaCommand("saved",group=true,children=children,delayMs=0))
            nested[0]=VofaCommand("changed","BB",delayMs=0);children.clear()
            withTimeout(3000) {
                while(s.state.value.commandGroupName != null) {dispatcher.runQueued();delay(5)}
            }
            assertEquals(1,driver.writes.size)
            assertArrayEquals(byteArrayOf(0xAA.toByte()),driver.writes.single())
        } finally {s.stopSending();dispatcher.runQueued()}
    }
    @Test fun commandGroupKeepsItsReservationUntilBlockedDriverWriteFinishes()=runBlocking {
        val entered=AtomicBoolean();val released=java.util.concurrent.CountDownLatch(1)
        val driver=FakeDriver().apply {writeResponse={entered.set(true);released.await();WriteResult(it.size)}}
        val s=session(driver);val otherDriver=FakeDriver();val other=session(otherDriver,port=1)
        val command=VofaCommand("blocked group",group=true,delayMs=0,children=listOf(
            VofaCommand("first","AA",delayMs=0),VofaCommand("second","BB",delayMs=0)))
        try {
            s.runVofa(command);await {entered.get()}
            s.stopSending()
            assertEquals("blocked group",s.state.value.commandGroupName)
            assertNotNull(s.send(byteArrayOf(2)).error)
            assertThrows(IllegalStateException::class.java) {s.runVofa(command)}
            assertThrows(IllegalStateException::class.java) {s.repeatSend(byteArrayOf(2),1,10)}
            assertThrows(IllegalStateException::class.java) {s.transfer("blocked",1,FileMode.RAW) {byteArrayOf(2).inputStream()}}
            driver.receive("RX".toByteArray())
            assertEquals(1,other.send(byteArrayOf(3)).confirmed)
            await {s.state.value.counters.received == 2L}
            assertEquals(1,driver.writes.size)
        } finally {released.countDown()}
        await {s.state.value.commandGroupName == null && !s.state.value.transmitting}
        assertEquals(1,driver.writes.size)
        driver.writeResponse={WriteResult(it.size)}
        assertEquals(1,s.send(byteArrayOf(4)).confirmed)
        assertEquals(PortState.OPEN,s.state.value.state)
    }
    @Test fun commandGroupStopsOnPartialOrUnknownWritesWithoutRetrying()=runBlocking {
        for(response in listOf(WriteResult(1,IOException("partial")),WriteResult(1),WriteResult(2,completionUnknown=true))) {
            val driver=FakeDriver().apply {writeResponse={response}}
            val s=session(driver)
            s.runVofa(VofaCommand("failure",group=true,delayMs=0,children=listOf(
                VofaCommand("first","AA BB",delayMs=0),VofaCommand("second","CC",delayMs=0))))
            await {s.state.value.commandGroupName == null}
            assertEquals(1,driver.writes.size)
            assertNotNull(s.state.value.error)
            assertFalse(s.state.value.repeating)
            driver.writeResponse={WriteResult(it.size)}
            assertEquals(1,s.send(byteArrayOf(4)).confirmed)
        }
    }
    @Test fun disconnectDuringGroupDelayCancelsRemainingStepsAndKeepsOtherPortOpen()=runBlocking {
        val driver=FakeDriver();val s=session(driver);val other=session(port=1)
        s.runVofa(VofaCommand("disconnect",group=true,delayMs=0,children=listOf(
            VofaCommand("first","AA",delayMs=60000),VofaCommand("second","BB",delayMs=0))))
        await {driver.writes.size == 1}
        driver.detach()
        await {s.state.value.state == PortState.DISCONNECTED && s.state.value.commandGroupName == null}
        assertEquals(1,driver.writes.size)
        assertFalse(s.state.value.repeating)
        assertEquals(1,other.send(byteArrayOf(3)).confirmed)
    }
    @Test fun failedRollbackClearsCurrentConfigurationInsteadOfClaimingTheOldValue() = runBlocking {
        val driver=FakeDriver();val s=session(driver);val original=s.state.value.appliedConfig
        driver.rejectConfiguration={true}
        assertNotNull(runCatching {s.apply(SerialConfig(baudRate=57600))}.exceptionOrNull())
        assertNull(s.state.value.appliedConfig);assertNull(s.state.value.baudReport)
        assertEquals(original,s.state.value.lastConfirmedConfig)
        assertTrue(s.state.value.error!!.contains("rollback=false"))
    }
    @Test fun closingOnePortDoesNotStopTheOtherSession() = runBlocking {
        val first=FakeDriver();val second=FakeDriver()
        val a=session(first,port=0);val b=session(second,port=1)
        a.close()
        assertTrue(first.closed.get());assertFalse(second.closed.get())
        assertEquals(4,b.send("PING".toByteArray()).confirmed)
        second.receive("RX2".toByteArray());await {b.state.value.counters.received == 3L}
        assertEquals(PortState.CLOSED,a.state.value.state);assertEquals(PortState.OPEN,b.state.value.state)
    }
    @Test fun detachCancelsRepeatAndReleasesTimedBreak() = runBlocking {
        val driver=FakeDriver();val s=session(driver)
        s.repeatSend(byteArrayOf(7),0,10);s.control("BREAK",true,9999)
        await {driver.writes.size >= 2 && driver.lines["BREAK"] == true}
        driver.detach()
        await {s.state.value.state == PortState.DISCONNECTED}
        val writes=driver.writes.size
        delay(80)
        assertEquals(writes,driver.writes.size);assertFalse(s.state.value.repeating)
        assertEquals(false,driver.lines["BREAK"]);assertEquals(1,driver.closeCount.get())
    }
    @Test fun finishingTheOldRepeatDoesNotMarkTheReplacementAsStopped() = runBlocking {
        val dispatcher=HeldDispatcher();val driver=FakeDriver();val s=session(driver,dispatcher=dispatcher)
        try {
            s.repeatSend(byteArrayOf(1),0,10000);dispatcher.runFirst()
            await {driver.writes.size == 1 && dispatcher.hasQueued}
            s.repeatSend(byteArrayOf(2),0,10000);dispatcher.runLast()
            await {driver.writes.size == 2}
            dispatcher.runFirst()
            assertTrue(s.state.value.repeating)
            s.stopRepeat();dispatcher.runQueued()
            assertFalse(s.state.value.repeating)
            assertEquals(2,driver.writes.size)
        } finally {s.stopRepeat();dispatcher.runQueued()}
    }
    @Test fun replacingRepeatWithAtCommandsKeepsTheNewTaskStoppable() = runBlocking {
        val dispatcher=HeldDispatcher();val driver=FakeDriver();val s=session(driver,dispatcher=dispatcher)
        try {
            s.repeatSend(byteArrayOf(1),0,10000);dispatcher.runFirst()
            await {driver.writes.size == 1 && dispatcher.hasQueued}
            s.runAtCommands();dispatcher.runLast();dispatcher.runFirst()
            assertTrue(s.state.value.repeating)
            s.stopRepeat();dispatcher.runQueued()
            assertFalse(s.state.value.repeating)
            assertEquals(1,driver.writes.size)
        } finally {s.stopRepeat();dispatcher.runQueued()}
    }
    @Test fun repeatOwnsItsInputBytesBeforeTheTaskStarts() = runBlocking {
        val dispatcher=HeldDispatcher();val driver=FakeDriver();val s=session(driver,dispatcher=dispatcher)
        try {
            val source=byteArrayOf(1,2)
            s.repeatSend(source,1,10);source.fill(9)
            dispatcher.runFirst();await {driver.writes.size == 1 && dispatcher.hasQueued};dispatcher.runQueued()
            assertArrayEquals(byteArrayOf(1,2),driver.writes.single())
            assertFalse(s.state.value.repeating)
        } finally {s.stopRepeat();dispatcher.runQueued()}
    }
    @Test fun repeatCanBeStoppedBeforeItStartsAndRejectsOversizedCommands() = runBlocking {
        val dispatcher=HeldDispatcher();val driver=FakeDriver();val s=session(driver,dispatcher=dispatcher)
        s.repeatSend(byteArrayOf(1),0,10);s.stopRepeat();dispatcher.runQueued()
        assertFalse(s.state.value.repeating);assertTrue(driver.writes.isEmpty())
        assertThrows(IllegalArgumentException::class.java) {s.repeatSend(ByteArray(1024*1024+1),0,10)}
        assertFalse(s.state.value.repeating);assertTrue(driver.writes.isEmpty())
    }
    @Test fun pacedPartialWriteReportsThePositionInTheWholeCommand() = runBlocking {
        val driver=FakeDriver().apply {writeResponse={if(writes.size == 3) WriteResult(0,IOException("stopped")) else WriteResult(it.size)}}
        val s=session(driver)
        val result=s.send("ABCD".toByteArray(),AppSettings(charDelayMs=1))
        assertEquals(2,result.confirmed);assertEquals(3,driver.writes.size)
        assertTrue(s.state.value.error!!.contains("2/4"))
        await {s.state.value.counters.sent == 2L}
    }
    @Test fun stopInterruptsPacingAndDiscardsQueuedCommandsWithoutReplayingThem() = runBlocking {
        val driver=FakeDriver();val s=session(driver)
        val first=async {s.send("ABCDE".toByteArray(),AppSettings(charDelayMs=9999))}
        await {driver.writes.size == 1 && s.state.value.transmitting}
        val queued=async(start=CoroutineStart.UNDISPATCHED) {s.send("QUEUED".toByteArray())}
        s.stopSending()
        withTimeout(1500) {first.join();queued.join();while(s.state.value.transmitting) delay(5)}
        assertTrue(first.isCancelled);assertTrue(queued.isCancelled)
        assertEquals(1,driver.writes.size);assertArrayEquals("A".toByteArray(),driver.writes.single())
        assertEquals(4,s.send("NEXT".toByteArray()).confirmed)
        await {s.state.value.counters.sent == 5L}
        assertEquals(2,driver.writes.size)
    }
    @Test fun closingDuringPacedSendReportsTheConfirmedPrefix() = runBlocking {
        val driver=FakeDriver();val s=session(driver)
        val sending=async {s.send("ABCD".toByteArray(),AppSettings(charDelayMs=9999))}
        await {driver.writes.size == 1}
        s.close()
        val result=withTimeout(1500) {sending.await()}
        assertEquals(1,result.confirmed);assertNotNull(result.error);assertFalse(result.completionUnknown)
        assertEquals(1L,s.state.value.counters.sent);assertFalse(s.state.value.transmitting)
    }
    @Test fun stoppingOnePortDoesNotCancelTheOtherPortsActiveSend() = runBlocking {
        val first=FakeDriver();val second=FakeDriver();val a=session(first);val b=session(second,port=1)
        val sendingA=async {a.send("ABCD".toByteArray(),AppSettings(charDelayMs=9999))}
        val sendingB=async {b.send("HELLO".toByteArray(),AppSettings(charDelayMs=30))}
        await {first.writes.size == 1 && second.writes.isNotEmpty()}
        a.stopSending()
        withTimeout(1500) {sendingA.join()}
        assertTrue(sendingA.isCancelled)
        assertEquals(5,withTimeout(1500) {sendingB.await()}.confirmed)
        assertEquals("HELLO",second.writes.flatMap {it.toList()}.toByteArray().toString(Charsets.UTF_8))
        assertFalse(second.closed.get());assertEquals(1,first.writes.size)
    }
    @Test fun atSequenceSendsAllElevenCommandsWithoutManualPacingDelays() = runBlocking {
        val driver=FakeDriver();val s=session(driver,options=AppSettings(charDelayMs=9999,lineDelayMs=9999))
        s.runAtCommands()
        withTimeout(5000) {while(s.state.value.repeating) delay(5)}
        assertEquals(listOf("ATE1","ATI","AT+CIMI","AT+CGSN","AT+CPIN?","AT+CSCA?","AT+CPMS?","AT+CGPADDR=1","AT+CCLK?","AT+CGATT?","AT+CSQ"),
            driver.writes.map {it.toString(Charsets.UTF_8).removeSuffix("\r\n")})
        assertTrue(driver.writes.all {it.takeLast(2) == listOf(13.toByte(),10.toByte())})
    }
    @Test fun atSequenceStopsAfterTheFirstPartialFailure() = runBlocking {
        val driver=FakeDriver().apply {writeResponse={if(it.toString(Charsets.UTF_8).startsWith("AT+CIMI")) WriteResult(2,IOException("partial AT")) else WriteResult(it.size)}}
        val s=session(driver);s.runAtCommands()
        await {!s.state.value.repeating}
        assertEquals(3,driver.writes.size);await {s.state.value.counters.sent == 13L}
        assertEquals(PortState.OPEN,s.state.value.state)
    }
    @Test fun replacingTimedBreakDoesNotReleaseTheNewHold() = runBlocking {
        val driver=FakeDriver();val s=session(driver)
        s.control("BREAK",true,9999);await {driver.lines["BREAK"] == true}
        s.control("BREAK",true,9999);await {driver.breakAssertions.get() == 2}
        delay(40)
        assertEquals(true,driver.lines["BREAK"])
        s.control("BREAK",false);await {driver.lines["BREAK"] == false}
    }
    @Test fun cancelledXmodemReleasesExclusiveScheduling() = runBlocking {
        val driver=FakeDriver();val s=session(driver)
        s.transfer("test.bin",3,FileMode.XMODEM_CRC) {byteArrayOf(1,2,3).inputStream()}
        await {s.state.value.transfer?.status == "RUNNING"}
        assertNotNull(s.send("MANUAL".toByteArray()).error)
        s.cancelTransfer();await {s.state.value.transfer?.status == "CANCELLED"}
        var sent=false
        withTimeout(3000) {while(!sent) {sent=s.send("MANUAL".toByteArray()).error == null;if(!sent) delay(1)}}
        assertEquals(1,driver.writes.count {it.contentEquals("MANUAL".toByteArray())})
    }
    @Test fun logStorageFailureIsVisibleAndDoesNotCloseThePort() = runBlocking {
        val context=object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun openFileOutput(name: String,mode: Int): FileOutputStream = throw FileNotFoundException("storage unavailable")
        }
        val s=session(options=AppSettings(logEnabled=true),context=context)
        assertEquals(PortState.OPEN,s.state.value.state)
        assertTrue(s.state.value.error!!.contains("Communication log failed"))
        assertEquals(2,s.send("OK".toByteArray()).confirmed)
    }
    @Test fun reopeningLogAtTheSameTimePreservesThePreviousFile() {
        val context=RuntimeEnvironment.getApplication()
        val log=CommunicationLog(context) {1234567890000L}
        log.open("log-rotation",AppSettings())
        log.append(SerialEvent("log-rotation",1,direction=Direction.RX,data=byteArrayOf(0x41)))
        log.close()
        val first=context.filesDir.listFiles()!!.single {it.name.startsWith("serial-")}
        val original=first.readBytes()
        log.open("log-rotation",AppSettings(logHex=false))
        log.append(SerialEvent("log-rotation",2,direction=Direction.RX,data=byteArrayOf(0x42)))
        log.close()
        assertArrayEquals(original,first.readBytes())
        val files=context.filesDir.listFiles()!!.filter {it.name.startsWith("serial-")}
        assertEquals(2,files.size)
        assertTrue(files.first {it != first}.readText().contains("B"))
    }
    @Test fun failingLogWriteClosesExistingDataAndKeepsThePortOpen() = runBlocking {
        val app=RuntimeEnvironment.getApplication()
        val closed=AtomicBoolean();val writes=AtomicInteger()
        var file: java.io.File?=null
        val context=object : ContextWrapper(app) {
            override fun openFileOutput(name: String,mode: Int): FileOutputStream {
                file=java.io.File(app.filesDir,name)
                return object : FileOutputStream(file) {
                    override fun write(bytes: ByteArray) {
                        if(writes.incrementAndGet() == 2) throw IOException("storage write failed")
                        super.write(bytes)
                    }
                    override fun close() {closed.set(true);super.close()}
                }
            }
        }
        val driver=FakeDriver();val s=session(driver,options=AppSettings(logEnabled=true),context=context)
        await {writes.get() == 1}
        driver.receive("RX".toByteArray())
        await {closed.get() && s.state.value.error?.contains("Communication log failed") == true}
        assertTrue(file!!.readText().contains("Port opened"))
        assertEquals(PortState.OPEN,s.state.value.state)
        assertNull(s.send("OK".toByteArray()).error)
    }
    @Test fun resettingCountersIncludesQueueAndDisplayLossWithoutClearingData() = runBlocking {
        val driver=FakeDriver();val s=session(driver,options=AppSettings(bufferBytes=2048))
        repeat(3) {driver.receive(ByteArray(2048) {1})}
        await {s.state.value.counters.received == 6144L && s.state.value.counters.displayTrimmed == 4096L}
        s.resetCounters()
        delay(70)
        assertEquals(SerialCounters(),s.state.value.counters)
        assertEquals(2048,s.store.current().sumOf {it.data.size})
    }
    @Test fun unlimitedClearUndoRestoresTerminalStateBeyondTheLastDiskPage() = runBlocking {
        val driver=FakeDriver();val s=session(driver,options=AppSettings(bufferBytes=0))
        repeat(20) {batch ->
            repeat(15) {driver.receive(byteArrayOf('A'.code.toByte()))}
            await {s.terminal.snapshot().rows.sumOf {row->row.count {it.char == 'A'}} == (batch+1)*15}
        }
        s.clear()
        driver.receive("XYZ".toByteArray())
        await {s.terminal.snapshot().rows[0][2].char == 'Z'}
        s.undoClear()
        val restored=s.terminal.snapshot()
        assertEquals(3,restored.cursorRow);assertEquals(63,restored.cursorColumn)
        assertEquals('A',restored.rows[0][0].char)
        assertEquals("XYZ",restored.rows[3].drop(60).take(3).map {it.char}.joinToString(""))
        driver.receive("B".toByteArray());await {s.terminal.snapshot().rows[3][63].char == 'B'}
    }
    @Test fun clearUndoRetainsSplitCharactersEscapeStateAndSavedCursorAttributes() = runBlocking {
        val driver=FakeDriver();val s=session(driver)
        driver.receive("P\u001b[?1h\u001b[1;31m\u001b[2;2H\u001b7\u001b[".toByteArray())
        await {s.terminal.snapshot().cursorRow == 1 && s.terminal.cursorKeysApplication}
        s.clear()
        val character="串".toByteArray()
        driver.receive("3;4H".toByteArray()+character.copyOf(1))
        await {s.terminal.snapshot().rows[0][3].char == 'H'}
        s.undoClear()
        driver.receive(character.copyOfRange(1,character.size)+"\u001b8Z".toByteArray())
        await {s.terminal.snapshot().rows[1][1].char == 'Z'}
        val screen=s.terminal.snapshot()
        assertEquals('P',screen.rows[0][0].char)
        assertEquals('串',screen.rows[2][3].char)
        assertEquals(1,screen.rows[1][1].foreground);assertTrue(screen.rows[1][1].bold)
        assertTrue(s.terminal.cursorKeysApplication)
    }
    @Test fun splitCharactersAndEventSequenceSurviveConcurrentReceiveAndSend() = runBlocking {
        val driver=FakeDriver();val s=session(driver)
        val bytes="串口".toByteArray()
        bytes.forEach {driver.receive(byteArrayOf(it))}
        assertEquals(4,s.send("PING".toByteArray()).confirmed)
        await {s.state.value.counters.received == bytes.size.toLong() && s.terminal.snapshot().rows[0][1].char == '口'}
        val events=s.store.current()
        assertTrue(events.zipWithNext().all {(a,b)->a.sequence < b.sequence})
        assertArrayEquals(bytes,events.filter {it.direction == Direction.RX}.fold(byteArrayOf()) {all,e->all+e.data})
    }
    @Test fun cancellingBeforeTransferBodyStartsDoesNotLeaveThePortLocked() = runBlocking {
        val dispatcher=HeldDispatcher();val driver=FakeDriver();val s=session(driver,dispatcher=dispatcher)
        val opened=AtomicInteger()
        s.transfer("not-opened.bin",3,FileMode.RAW) {opened.incrementAndGet();byteArrayOf(1,2,3).inputStream()}
        s.cancelTransfer();dispatcher.runQueued()
        assertEquals(0,opened.get())
        assertEquals("CANCELLED",s.state.value.transfer?.status)
        assertNull(s.send("NEXT".toByteArray()).error)
    }
    @Test fun cancellingBlockedFileReadClosesTheSourceAndReleasesThePort() = runBlocking {
        val input=BlockingInput();val s=session()
        s.transfer("blocked.bin",100,FileMode.RAW) {input}
        await {input.reading.get()}
        s.cancelTransfer()
        try {
            await {input.closed.get() && s.state.value.transfer?.status == "CANCELLED"}
            assertNull(s.send("NEXT".toByteArray()).error)
        } finally {input.close()}
    }

    private class HeldDispatcher : CoroutineDispatcher() {
        private val queue=ConcurrentLinkedDeque<Runnable>()
        val hasQueued get()=queue.isNotEmpty()
        override fun dispatch(context: CoroutineContext,block: Runnable) {queue.add(block)}
        fun runQueued() {while(true) (queue.poll() ?: break).run()}
        fun runFirst() {checkNotNull(queue.pollFirst()).run()}
        fun runLast() {checkNotNull(queue.pollLast()).run()}
    }
    private class BlockingInput : InputStream() {
        val reading=AtomicBoolean();val closed=AtomicBoolean()
        private val released=java.util.concurrent.CountDownLatch(1)
        override fun read(): Int {reading.set(true);released.await();throw IOException("source closed")}
        override fun read(bytes: ByteArray,offset: Int,length: Int): Int = read()
        override fun close() {closed.set(true);released.countDown()}
    }
    private class FakeDriver : SerialDriverAdapter {
        override val driverName="TestDriver"
        override val capabilities=SerialCapabilities(controlLines=setOf("DTR","RTS","CTS","DSR","CD","RI"),breakControl=Capability(CapabilityState.SUPPORTED))
        val closed=AtomicBoolean(false);val closeCount=AtomicInteger()
        val breakAssertions=AtomicInteger()
        val writes=Collections.synchronizedList(mutableListOf<ByteArray>())
        val lines=ConcurrentHashMap<String,Boolean>()
        @Volatile var current: SerialConfig?=null
        @Volatile var baudEstimate: Double?=null
        @Volatile var rejectConfiguration: (SerialConfig)->Boolean = {false}
        @Volatile var writeResponse: (ByteArray)->WriteResult = {WriteResult(it.size)}
        private val incoming=LinkedBlockingQueue<Any>()
        override fun apply(config: SerialConfig): BaudRateReport {
            current=config
            if(rejectConfiguration(config)) throw IOException("configuration write failed")
            return BaudRateReport(config.baudRate,divisorEstimated=baudEstimate)
        }
        fun receive(bytes: ByteArray) {incoming.offer(bytes.copyOf())}
        fun detach() {incoming.offer(IOException("USB detached"))}
        override fun read(buffer: ByteArray): Int {
            if(closed.get()) throw IOException("closed")
            val next=incoming.poll(20,TimeUnit.MILLISECONDS) ?: return 0
            if(next is IOException) throw next
            val data=next as ByteArray;data.copyInto(buffer);return data.size
        }
        override fun write(bytes: ByteArray,timeoutMs: Int): WriteResult {
            if(closed.get()) return WriteResult(0,IOException("closed"))
            writes+=bytes.copyOf();return writeResponse(bytes)
        }
        override fun control(name: String,enabled: Boolean) {
            if(closed.get()) throw IOException("closed")
            lines[name]=enabled
            if(name == "BREAK" && enabled) breakAssertions.incrementAndGet()
        }
        override fun readControlLines(): Map<String,Boolean> = lines.toMap()
        override fun close() {if(closed.compareAndSet(false,true)) {closeCount.incrementAndGet();incoming.offer(byteArrayOf())}}
    }
}
