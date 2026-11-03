package com.lyufeng.phoneserialport

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lyufeng.phoneserialport.core.BaudRateReport
import com.lyufeng.phoneserialport.core.PortState
import com.lyufeng.phoneserialport.core.SerialCapabilities
import com.lyufeng.phoneserialport.core.SerialConfig
import com.lyufeng.phoneserialport.core.SerialDriverAdapter
import com.lyufeng.phoneserialport.core.WriteResult
import com.lyufeng.phoneserialport.data.AppSettings
import com.lyufeng.phoneserialport.data.VofaCommand
import com.lyufeng.phoneserialport.serial.SerialSession
import com.lyufeng.phoneserialport.transfer.FileMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Runs the production scheduler on Android. No adapter in these tests opens a USB device. */
@RunWith(AndroidJUnit4::class)
class CommandGroupDeviceTest {
    private data class RecordedWrite(val bytes: ByteArray,val startedNs: Long,val finishedNs: Long)

    private class RecordingDriver : SerialDriverAdapter {
        override val driverName="DeviceTestMemoryAdapter"
        override val capabilities=SerialCapabilities()
        val writes=CopyOnWriteArrayList<RecordedWrite>()
        @Volatile var response: (ByteArray)->WriteResult = {WriteResult(it.size)}
        override fun apply(config: SerialConfig)=BaudRateReport(config.baudRate)
        override fun read(buffer: ByteArray): Int {Thread.sleep(10);return 0}
        override fun write(bytes: ByteArray,timeoutMs: Int): WriteResult {
            val started=SystemClock.elapsedRealtimeNanos()
            val result=response(bytes)
            writes+=RecordedWrite(bytes.copyOf(),started,SystemClock.elapsedRealtimeNanos())
            return result
        }
        override fun control(name: String,enabled: Boolean) {}
        override fun readControlLines(): Map<String,Boolean> = emptyMap()
        override fun close() {}
    }

    private val sessions=mutableListOf<SerialSession>()
    private suspend fun session(driver: RecordingDriver=RecordingDriver()): SerialSession {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val id="device-command-group-${UUID.randomUUID()}"
        return SerialSession(context,id,"memory-only",0,driver,AppSettings()) {session,error ->
            Log.e(TAG,"Unexpected memory-adapter read failure",error)
            runBlocking {session.close(disconnected=true,error=error.message)}
        }.also {sessions+=it;it.start(SerialConfig())}
    }
    private suspend fun await(check: ()->Boolean) = withTimeout(8000) {
        while(!check()) delay(5)
    }
    private suspend fun stopped(session: SerialSession) = await {
        session.state.value.commandGroupName == null && !session.state.value.transmitting
    }
    @After fun cleanup(): Unit = runBlocking {
        sessions.forEach {session ->
            session.stopSending()
            session.close()
            session.store.close()
        }
    }

    @Test fun orderDelaysDisabledStepsAndFiniteLoopsUseAndroidScheduler(): Unit = runBlocking {
        val driver=RecordingDriver().apply {response={Thread.sleep(30);WriteResult(it.size)}}
        val session=session(driver)
        session.runVofa(VofaCommand("device-order",group=true,repeats=2,delayMs=50,children=listOf(
            VofaCommand("A","AA",delayMs=120),
            VofaCommand("disabled","CC",included=false,delayMs=9999),
            VofaCommand("B","BB",delayMs=180),
        )))
        stopped(session)
        val completed=SystemClock.elapsedRealtimeNanos()
        val writes=driver.writes.toList()
        assertEquals(listOf(0xAA,0xBB,0xAA,0xBB),writes.map {it.bytes.single().toInt() and 0xFF})
        val afterWriteMs=(0..2).map {(writes[it+1].startedNs-writes[it].finishedNs)/1_000_000.0}
        val finalWaitMs=(completed-writes.last().finishedNs)/1_000_000.0
        Log.i(TAG,"Actual post-write intervals ms=$afterWriteMs; final wait ms=$finalWaitMs")
        assertTrue("Step A must wait after its driver write: $afterWriteMs",afterWriteMs[0] >= 118)
        assertTrue("Step B and group delay must precede the next round: $afterWriteMs",afterWriteMs[1] >= 228)
        assertTrue("The second round must keep the step delay: $afterWriteMs",afterWriteMs[2] >= 118)
        assertTrue("The final step and group delay must run: $finalWaitMs",finalWaitMs >= 228)
        assertEquals(PortState.OPEN,session.state.value.state)
    }

    @Test fun stopDuringLongDelayCancelsLaterStepsAndAllowsAnotherSend(): Unit = runBlocking {
        val driver=RecordingDriver();val session=session(driver)
        session.runVofa(VofaCommand("device-stop",group=true,delayMs=0,children=listOf(
            VofaCommand("A","AA",delayMs=60000),VofaCommand("B","BB",delayMs=0),
        )))
        await {driver.writes.size == 1}
        session.stopSending()
        stopped(session)
        delay(150)
        assertEquals(1,driver.writes.size)
        assertEquals(1,session.send(byteArrayOf(0xCC.toByte())).confirmed)
        assertEquals(listOf(0xAA,0xCC),driver.writes.map {it.bytes.single().toInt() and 0xFF})
    }

    @Test fun groupReservationRejectsEveryOtherSendingEntryPoint(): Unit = runBlocking {
        val driver=RecordingDriver();val session=session(driver)
        val command=VofaCommand("device-exclusive",group=true,delayMs=0,children=listOf(
            VofaCommand("A","AA",delayMs=60000),VofaCommand("B","BB",delayMs=0),
        ))
        session.runVofa(command)
        await {driver.writes.size == 1}
        assertNotNull(session.send(byteArrayOf(0xCC.toByte())).error)
        assertThrows(IllegalStateException::class.java) {session.runVofa(command)}
        assertThrows(IllegalStateException::class.java) {session.repeatSend(byteArrayOf(0xCC.toByte()),1,10)}
        assertThrows(IllegalStateException::class.java) {session.runAtCommands()}
        assertThrows(IllegalStateException::class.java) {
            session.transfer("memory-only",1,FileMode.RAW) {byteArrayOf(0xCC.toByte()).inputStream()}
        }
        assertEquals(1,driver.writes.size)
        session.stopSending();stopped(session)
        assertEquals(1,session.send(byteArrayOf(0xCC.toByte())).confirmed)
    }

    @Test fun partialFailedAndUnknownWritesStopWithoutSendingTheNextStep(): Unit = runBlocking {
        for(response in listOf(WriteResult(1),WriteResult(1,IOException("device-test failure")),WriteResult(2,completionUnknown=true))) {
            val driver=RecordingDriver().apply {this.response={response}}
            val session=session(driver)
            session.runVofa(VofaCommand("device-write-result",group=true,delayMs=0,children=listOf(
                VofaCommand("A","AA AA",delayMs=0),VofaCommand("B","BB",delayMs=0),
            )))
            stopped(session)
            assertEquals(1,driver.writes.size)
            assertNotNull(session.state.value.error)
            assertFalse(session.state.value.repeating)
            assertEquals(PortState.OPEN,session.state.value.state)
        }
    }

    @Test fun changesToTheOriginalNestedListsDoNotAlterTheRunningGroup(): Unit = runBlocking {
        val driver=RecordingDriver();val session=session(driver)
        val nested=mutableListOf(VofaCommand("saved","BB",delayMs=0))
        val children=mutableListOf(VofaCommand("first","AA",delayMs=200),VofaCommand("nested",group=true,children=nested,delayMs=0))
        session.runVofa(VofaCommand("device-snapshot",group=true,children=children,delayMs=0))
        await {driver.writes.size == 1}
        nested[0]=VofaCommand("changed","CC",delayMs=0)
        children.clear()
        stopped(session)
        assertEquals(listOf(0xAA,0xBB),driver.writes.map {it.bytes.single().toInt() and 0xFF})
    }

    @Test fun stoppingABlockedWriteKeepsThePortReservedUntilDriverCleanup(): Unit = runBlocking {
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val driver=RecordingDriver().apply {response={bytes ->
            entered.countDown()
            check(release.await(5,TimeUnit.SECONDS)) {"Test did not release the memory adapter"}
            WriteResult(bytes.size)
        }}
        val session=session(driver)
        try {
            session.runVofa(VofaCommand("device-cleanup",group=true,delayMs=0,children=listOf(
                VofaCommand("A","AA",delayMs=0),VofaCommand("B","BB",delayMs=0),
            )))
            assertTrue(entered.await(3,TimeUnit.SECONDS))
            session.stopSending()
            delay(50)
            assertEquals("device-cleanup",session.state.value.commandGroupName)
            assertNotNull(session.send(byteArrayOf(0xCC.toByte())).error)
        } finally {release.countDown()}
        stopped(session)
        assertEquals(1,driver.writes.size)
        driver.response={WriteResult(it.size)}
        assertEquals(1,session.send(byteArrayOf(0xCC.toByte())).confirmed)
        assertEquals(listOf(0xAA,0xCC),driver.writes.map {it.bytes.single().toInt() and 0xFF})
    }

    private companion object {const val TAG="CommandGroupDeviceTest"}
}
