package com.lyufeng.phoneserialport.serial

import com.hoho.android.usbserial.driver.SerialTimeoutException
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.lyufeng.phoneserialport.core.*
import java.io.IOException

class UsbDriverAdapter(private val port: UsbSerialPort, private val ch34xBaud: Ch34xBaudControl? = null) : SerialDriverAdapter {
    override val driverName = port.driver.javaClass.simpleName
    private val ch34x = driverName == "Ch34xSerialDriver"
    private val byteStream = driverName in setOf("GsmModemSerialDriver","ChromeCcdSerialDriver")
    private val restrictedCp21xx: Boolean get() = driverName == "Cp21xxSerialDriver" && port.device.interfaceCount == 2 && port.portNumber == 1
    override val capabilities: SerialCapabilities get() = SerialCapabilities(
        dataBits = when {byteStream -> emptySet();ch34xBaud?.legacyLineControl == true -> setOf(8);driverName == "FtdiSerialDriver" -> setOf(7,8);restrictedCp21xx -> setOf(8);else -> (5..8).toSet()},
        parities = when {byteStream -> emptySet();ch34xBaud?.legacyLineControl == true -> setOf(Parity.NONE);restrictedCp21xx -> setOf(Parity.NONE,Parity.ODD,Parity.EVEN);else -> Parity.entries.toSet()},
        stopBits = when {byteStream -> emptySet();restrictedCp21xx || ch34xBaud?.legacyLineControl == true -> setOf(StopBits.ONE);ch34x || driverName in setOf("FtdiSerialDriver","Cp21xxSerialDriver") -> setOf(StopBits.ONE,StopBits.TWO);else -> StopBits.entries.toSet()},
        flowControls = port.supportedFlowControl.mapNotNull { when(it) {
            UsbSerialPort.FlowControl.NONE -> FlowControl.NONE
            UsbSerialPort.FlowControl.RTS_CTS -> FlowControl.RTS_CTS
            UsbSerialPort.FlowControl.DTR_DSR -> FlowControl.DTR_DSR
            UsbSerialPort.FlowControl.XON_XOFF,UsbSerialPort.FlowControl.XON_XOFF_INLINE -> FlowControl.XON_XOFF
        } }.toSet(),
        controlLines = port.supportedControlLines.map { it.name }.toSet(),
        breakControl = if(ch34x) ch34xBaud?.breakCapability ?: Capability(CapabilityState.UNKNOWN) else Capability(when {byteStream -> CapabilityState.DRIVER_UNIMPLEMENTED;driverName in listOf("FtdiSerialDriver","Cp21xxSerialDriver","ProlificSerialDriver","CdcAcmSerialDriver") -> CapabilityState.SUPPORTED;else -> CapabilityState.UNKNOWN}),
        parameterKnowledge = when {
            byteStream -> Capability(CapabilityState.DRIVER_UNIMPLEMENTED,"usb-serial-for-android 3.11.0 supports byte-stream I/O but does not implement UART parameters for this driver")
            ch34x || driverName in setOf("FtdiSerialDriver","Cp21xxSerialDriver") -> Capability(CapabilityState.SUPPORTED,"usb-serial-for-android 3.11.0 parameter restrictions; hardware verification pending")
            else -> Capability(CapabilityState.UNKNOWN,"Line-coding candidates are passed to the device; hardware support is not confirmed")
        },
        gpio = Capability(CapabilityState.DRIVER_UNIMPLEMENTED, "Select WchOfficial before opening to query vendor GPIO capabilities"),
        minimumBaud = if(ch34x) 46 else null,
        maximumBaud = if(ch34x) 3000000 else null,
    )
    override fun apply(config: SerialConfig): BaudRateReport {
        capabilities.validate(config)
        val stop = when(config.stopBits) { StopBits.ONE -> UsbSerialPort.STOPBITS_1; StopBits.ONE_POINT_FIVE -> UsbSerialPort.STOPBITS_1_5; StopBits.TWO -> UsbSerialPort.STOPBITS_2 }
        val flow = when(config.flowControl) {
            FlowControl.NONE -> UsbSerialPort.FlowControl.NONE
            FlowControl.RTS_CTS -> UsbSerialPort.FlowControl.RTS_CTS
            FlowControl.DTR_DSR -> UsbSerialPort.FlowControl.DTR_DSR
            FlowControl.XON_XOFF -> if(UsbSerialPort.FlowControl.XON_XOFF in port.supportedFlowControl) UsbSerialPort.FlowControl.XON_XOFF else UsbSerialPort.FlowControl.XON_XOFF_INLINE
        }
        if(ch34x) {
            val report=requireNotNull(ch34xBaud) {"CH34x baud controller is missing"}.apply(config)
            port.setFlowControl(flow)
            return report
        }
        port.setParameters(config.baudRate,config.dataBits,stop,config.parity.ordinal)
        port.setFlowControl(flow)
        // No public baud-rate readback exists in this library. Never report the request as a readback.
        return BaudRateReport(config.baudRate)
    }
    override fun read(buffer: ByteArray): Int = port.read(buffer,0)
    override fun write(bytes: ByteArray, timeoutMs: Int): WriteResult = try {
        port.write(bytes,timeoutMs); WriteResult(bytes.size)
    } catch(e: SerialTimeoutException) {
        WriteResult(e.bytesTransferred.coerceIn(0,bytes.size),e)
    } catch(e: IOException) {
        WriteResult(0,e,true)
    }
    override fun control(name: String, enabled: Boolean) { when(name) {
        "DTR" -> port.setDTR(enabled)
        "RTS" -> port.setRTS(enabled)
        "BREAK" -> {check(capabilities.breakControl.state == CapabilityState.SUPPORTED) {"BREAK support has not been confirmed"};port.setBreak(enabled)}
        else -> error("Unknown control line: $name")
    } }
    override fun readControlLines(): Map<String,Boolean> {
        if(port.supportedControlLines.isEmpty()) return emptyMap()
        val active = port.controlLines.map { it.name }.toSet()
        return port.supportedControlLines.associate { it.name to (it.name in active) }
    }
    override fun close() { port.close() }
}

object Ch341Divisor {
    fun androidEstimate(requested: Int): Double? {
        if(requested <= 0) return null
        var factor: Long
        var divisor: Int
        if(requested == 921600) { factor=0xf300; divisor=7 }
        else {
            factor=1532620800L/requested; divisor=3
            while(factor > 0xfff0 && divisor > 0) { factor=factor shr 3; divisor-- }
            if(factor > 0xfff0) return null
            factor=0x10000-factor
        }
        val div=256-((factor.toInt() shr 8) and 255)
        val ps=divisor and 3; val fact=(divisor shr 2) and 1
        return 48000000.0/((1 shl (12-3*ps-fact))*div)
    }
    /** Reference prediction from Linux ch341.c, not the Android driver's register readback. */
    fun linuxEstimate(requested: Int): Double? {
        if(requested !in 46..3000000) return null
        return Ch34xBaudPlan.closest(requested).estimated
    }
}
