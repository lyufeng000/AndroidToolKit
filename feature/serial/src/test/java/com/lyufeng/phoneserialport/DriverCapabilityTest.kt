package com.lyufeng.phoneserialport

import android.app.Application
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbInterface
import com.hoho.android.usbserial.driver.*
import com.lyufeng.phoneserialport.core.*
import com.lyufeng.phoneserialport.data.AppSettings
import com.lyufeng.phoneserialport.serial.SerialSession
import com.lyufeng.phoneserialport.serial.UsbDriverAdapter
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[37],application=Application::class)
class DriverCapabilityTest {
    private val sessions=mutableListOf<SerialSession>()
    private fun device(interfaces: Int=1): UsbDevice {
        val device=ReflectionHelpers.newInstance(UsbDevice::class.java)
        ReflectionHelpers.setField(device,"mName","/dev/bus/usb/001/002")
        ReflectionHelpers.setField(device,"mInterfaces",Array(interfaces) {ReflectionHelpers.newInstance(UsbInterface::class.java)})
        return device
    }
    @After fun cleanup()=runBlocking {sessions.forEach {it.close();it.store.close()}}
    @Test fun ftdiDisablesFiveAndSixDataBitsAndOnePointFiveStopBits() {
        val caps=UsbDriverAdapter(FtdiSerialDriver(device()).ports.first()).capabilities
        assertEquals(setOf(7,8),caps.dataBits)
        assertFalse(StopBits.ONE_POINT_FIVE in caps.stopBits)
        assertThrows(IllegalArgumentException::class.java) {caps.validate(SerialConfig(dataBits=5))}
        assertThrows(IllegalArgumentException::class.java) {caps.validate(SerialConfig(stopBits=StopBits.ONE_POINT_FIVE))}
    }
    @Test fun cp2105SecondPortDisablesParametersIgnoredByTheRestrictedChannel() {
        val ports=Cp21xxSerialDriver(device(2)).ports
        val first=UsbDriverAdapter(ports[0]).capabilities
        val second=UsbDriverAdapter(ports[1]).capabilities
        assertEquals((5..8).toSet(),first.dataBits)
        assertEquals(setOf(8),second.dataBits)
        assertEquals(setOf(StopBits.ONE),second.stopBits)
        assertEquals(setOf(Parity.NONE,Parity.ODD,Parity.EVEN),second.parities)
        assertThrows(IllegalArgumentException::class.java) {second.validate(SerialConfig(parity=Parity.MARK))}
    }
    @Test fun cp21xxAndCh34xRejectUnsupportedStopBitsBeforeAnyUsbWrite() {
        for(driver in listOf(Cp21xxSerialDriver(device()),Ch34xSerialDriver(device()))) {
            val adapter=UsbDriverAdapter(driver.ports.first())
            assertFalse(StopBits.ONE_POINT_FIVE in adapter.capabilities.stopBits)
            assertThrows(IllegalArgumentException::class.java) {adapter.apply(SerialConfig(stopBits=StopBits.ONE_POINT_FIVE))}
        }
    }
    @Test fun cdcAndProlificKeepAllUpstreamLineCodingCandidates() {
        for(driver in listOf(CdcAcmSerialDriver(device(0)),ProlificSerialDriver(device()))) {
            val caps=UsbDriverAdapter(driver.ports.first()).capabilities
            assertEquals((5..8).toSet(),caps.dataBits)
            assertEquals(StopBits.entries.toSet(),caps.stopBits)
            assertEquals(CapabilityState.UNKNOWN,caps.parameterKnowledge.state)
        }
    }
    @Test fun byteStreamDriversReportParameterAndBreakImplementationLimits() {
        for(driver in listOf(GsmModemSerialDriver(device()),ChromeCcdSerialDriver(device(3)))) {
            val adapter=UsbDriverAdapter(driver.ports.first());val caps=adapter.capabilities
            assertEquals(CapabilityState.DRIVER_UNIMPLEMENTED,caps.parameterKnowledge.state)
            assertEquals(CapabilityState.DRIVER_UNIMPLEMENTED,caps.breakControl.state)
            assertTrue(caps.controlLines.isEmpty())
            assertTrue(adapter.readControlLines().isEmpty())
        }
    }
    @Test fun byteStreamSessionOpensWithoutInventingAnAppliedUartConfiguration()=runBlocking {
        val source=UsbDriverAdapter(GsmModemSerialDriver(device()).ports.first())
        val applied=AtomicBoolean();val closed=AtomicBoolean()
        val adapter=object : SerialDriverAdapter {
            override val driverName=source.driverName
            override val capabilities get()=source.capabilities
            override fun apply(config: SerialConfig): BaudRateReport {applied.set(true);throw UnsupportedOperationException("UART parameters unavailable")}
            override fun read(buffer: ByteArray): Int {Thread.sleep(10);return 0}
            override fun write(bytes: ByteArray,timeoutMs: Int)=WriteResult(bytes.size)
            override fun control(name: String,enabled: Boolean) {throw UnsupportedOperationException()}
            override fun readControlLines()=source.readControlLines()
            override fun close() {closed.set(true)}
        }
        val session=SerialSession(RuntimeEnvironment.getApplication(),"stream-session","stream-device",0,adapter,AppSettings()) {_,_ -> }
        sessions+=session;session.start(SerialConfig())
        assertFalse(applied.get());assertEquals(PortState.OPEN,session.state.value.state)
        assertNull(session.state.value.appliedConfig);assertNull(session.state.value.lastConfirmedConfig);assertNull(session.state.value.baudReport)
        assertEquals(4,session.send("DATA".toByteArray()).confirmed)
        assertNotNull(runCatching {session.apply(SerialConfig())}.exceptionOrNull())
        assertFalse(applied.get());assertEquals(PortState.OPEN,session.state.value.state)
        session.close();assertTrue(closed.get())
    }
}
