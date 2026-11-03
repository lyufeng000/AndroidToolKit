package com.lyufeng.phoneserialport.serial

import android.app.Application
import android.hardware.usb.UsbDevice
import cn.wch.uartlib.WCHUARTManager
import cn.wch.uartlib.base.error.SerialErrorType
import cn.wch.uartlib.base.gpio.GPIO_DIR
import cn.wch.uartlib.base.gpio.GPIO_VALUE
import cn.wch.uartlib.callback.IDataCallback
import cn.wch.uartlib.callback.IModemStatus
import cn.wch.uartlib.chip.type.ChipType2
import com.lyufeng.phoneserialport.core.*
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** The SDK owns one connection per device. The hub owns its lifetime across all ports. */
class WchDeviceHub(private val application: Application) {
    val sdk: WCHUARTManager by lazy {
        WCHUARTManager.getInstance().also {it.init(application);WCHUARTManager.setDebug(false)}
    }
    private class DeviceLease(val device: UsbDevice) {
        val ports=ConcurrentHashMap<Int,WchDriverAdapter>()
        val controlLock=Any()
    }
    private val leases=mutableMapOf<String,DeviceLease>()
    internal fun <T> control(device: UsbDevice,block: ()->T): T {
        val lease=synchronized(this) {leases[device.deviceName] ?: throw IOException("WCH device is closed")}
        return synchronized(lease.controlLock) {block()}
    }
    fun supportedDevices(): Set<String> = sdk.enumDevice().map {it.deviceName}.toSet()
    @Synchronized fun portCount(device: UsbDevice): Int? = leases[device.deviceName]?.let {runCatching {sdk.getSerialCount(it.device)}.getOrNull()}
    @Synchronized fun open(device: UsbDevice, port: Int): WchDriverAdapter {
        var lease=leases[device.deviceName]
        if(lease == null) {
            check(sdk.openDevice(device)) {"WCH SDK could not open the USB device"}
            lease=DeviceLease(device)
            leases[device.deviceName]=lease
            val attached=lease
            try {
                sdk.registerDataCallback(device,IDataCallback {index,bytes,length -> attached.ports[index]?.receive(bytes,length)})
                sdk.registerModemStatusCallback(device,object : IModemStatus {
                    // SDK 2.3 demo order: DCD, DSR, CTS, RING.
                    override fun onStatusChanged(index: Int,dcd: Boolean,dsr: Boolean,cts: Boolean,ring: Boolean) {
                        attached.ports[index]?.modem(cts,dsr,ring,dcd)
                    }
                    // diagnose() reads the SDK's cumulative error counters on demand.
                    override fun onOverrunError(index: Int) {}
                    override fun onParityError(index: Int) {}
                    override fun onFrameError(index: Int) {}
                })
            } catch(e: Exception) {leases.remove(device.deviceName);sdk.disconnect(device);throw e}
        }
        try {
            require(port in 0 until sdk.getSerialCount(device)) {"Invalid WCH port index"}
            check(!lease.ports.containsKey(port)) {"WCH port is already open"}
            val adapter=WchDriverAdapter(this,device,port)
            lease.ports[port]=adapter
            if(!sdk.enableSerial(device,port,true)) {lease.ports.remove(port);error("WCH SDK could not enable the port")}
            return adapter
        } catch(e: Exception) {
            if(lease.ports.isEmpty()) {leases.remove(device.deviceName);sdk.disconnect(device)}
            throw e
        }
    }
    @Synchronized internal fun release(device: UsbDevice, port: Int) {
        val lease=leases[device.deviceName] ?: return
        lease.ports.remove(port)
        synchronized(lease.controlLock) {
            runCatching {sdk.enableSerial(device,port,false)}
            if(lease.ports.isEmpty()) {leases.remove(device.deviceName);sdk.disconnect(device)}
        }
    }
    companion object {
        const val DRIVER="WchOfficial"
        const val PACKAGE_VERSION="2.3"
    }
}

class WchDriverAdapter internal constructor(private val hub: WchDeviceHub,private val device: UsbDevice,private val port: Int) : SerialDriverAdapter {
    private val sdk get()=hub.sdk
    override val driverName=WchDeviceHub.DRIVER
    private val closed=AtomicBoolean(false)
    private val incoming=BoundedReceiveBuffer(128)
    private val lines=ConcurrentHashMap<String,Boolean>()
    private var detected: DriverDiagnostics?=null
    private val chip get()=sdk.getChipType(device)
    override val capabilities: SerialCapabilities get() {
        val family=chip == ChipType2.CHIP_CH341
        val diagnostics=detected
        return SerialCapabilities(
            flowControls=if(family) setOf(FlowControl.NONE) else setOf(FlowControl.NONE,FlowControl.RTS_CTS),
            controlLines=setOf("DTR","RTS","CTS","DSR","CD","RI"),
            breakControl=Capability(CapabilityState.SUPPORTED,"WCH SDK API; hardware verification pending"),
            baudRead=diagnostics?.baud?.capability ?: Capability(CapabilityState.UNKNOWN,"Query SDK diagnostics"),
            clockRead=diagnostics?.clock?.capability ?: Capability(CapabilityState.UNKNOWN,"Query SDK diagnostics"),
            uartErrors=diagnostics?.errors?.values?.let {values -> values.firstOrNull {it.capability.state != CapabilityState.SUPPORTED}?.capability ?: values.firstOrNull()?.capability}
                ?: Capability(CapabilityState.UNKNOWN,"SDK cumulative counters; query required"),
            gpio=diagnostics?.gpio?.capability ?: Capability(CapabilityState.UNKNOWN,"Probe the identified chip"),
            reset=Capability(CapabilityState.DRIVER_UNIMPLEMENTED,"WCH SDK package 2.3 exposes no reset API"),
            parameterKnowledge=Capability(CapabilityState.UNKNOWN,"SDK validates the identified chip and parameter combination"))
    }
    internal fun receive(data: ByteArray,length: Int) {
        if(closed.get()) return
        incoming.offer(data,length)
    }
    internal fun modem(cts: Boolean,dsr: Boolean,ring: Boolean,dcd: Boolean) {
        lines["CTS"]=cts;lines["DSR"]=dsr;lines["RI"]=ring;lines["CD"]=dcd
    }
    override fun takeReceiveOverflow(): Long = incoming.takeDropped()
    override fun takeReceiveDiscontinuity(): Boolean = incoming.takeDiscontinuity()
    override fun apply(config: SerialConfig): BaudRateReport = hub.control(device) {
        check(!closed.get()) {"WCH port closed"}
        capabilities.validate(config)
        check(sdk.setSerialParameter(device,port,config.baudRate,config.dataBits,if(config.stopBits == StopBits.TWO) 2 else 1,
            config.parity.ordinal,config.flowControl == FlowControl.RTS_CTS)) {"WCH SDK rejected the serial parameters"}
        val readback=reading {sdk.getSerialBaud(device,port).also {require(it > 0) {"Invalid SDK baud report"}}}
        detected=(detected ?: DriverDiagnostics()).copy(baud=readback)
        BaudRateReport(config.baudRate,driverReported=readback.value,
            driverReportSource="WCH SDK package 2.3 getSerialBaud; not an external UART measurement")
    }
    override fun read(buffer: ByteArray): Int {
        if(closed.get()) throw IOException("WCH port closed")
        val count=incoming.read(buffer)
        if(count == 0 && !sdk.isConnected(device)) throw IOException("WCH device disconnected")
        return count
    }
    override fun write(bytes: ByteArray,timeoutMs: Int): WriteResult = try {
        check(!closed.get()) {"WCH port closed"}
        val count=sdk.syncWriteData(device,port,bytes,bytes.size,timeoutMs)
        when {
            count < 0 -> WriteResult(0,IOException("WCH write failed: $count"),true)
            count > bytes.size -> WriteResult(0,IOException("Invalid WCH write result: $count"),true)
            count < bytes.size -> WriteResult(count,IOException("WCH partial write: $count/${bytes.size}"))
            else -> WriteResult(count)
        }
    } catch(e: Exception) {WriteResult(0,e,true)}
    override fun control(name: String,enabled: Boolean): Unit = hub.control(device) {
        check(!closed.get()) {"WCH port closed"}
        val success=when(name) {"DTR" -> sdk.setDTR(device,port,enabled);"RTS" -> sdk.setRTS(device,port,enabled);"BREAK" -> sdk.setBreak(device,port,enabled);else -> error("Unknown control line")}
        check(success) {"WCH control failed: $name"}
        lines[name]=enabled
    }
    override fun readControlLines(): Map<String,Boolean> = lines.toMap()
    private fun <T> reading(block: ()->T): CapabilityValue<T> = try {
        CapabilityValue(Capability(CapabilityState.SUPPORTED,"WCH SDK package 2.3"),block())
    } catch(e: Exception) {
        val unavailable=e.message.orEmpty().contains("not support",ignoreCase=true)
        CapabilityValue(Capability(if(unavailable) CapabilityState.DRIVER_UNIMPLEMENTED else CapabilityState.READ_FAILED,e.message.orEmpty()))
    }
    override fun diagnose(): DriverDiagnostics = hub.control(device) {
        check(!closed.get()) {"WCH port closed"}
        val type=chip
        val gpio=reading {
            if(!sdk.isSupportGPIOFeature(device)) throw UnsupportedOperationException("SDK does not support GPIO for this chip")
            val expected=sdk.queryGPIOCount(device)
            sdk.queryAllGPIOStatus(device).map {
                val direction=requireNotNull(it.dir) {"SDK GPIO direction is unavailable: ${it.gpioIndex}"}
                val level=requireNotNull(it.value) {"SDK GPIO level is unavailable: ${it.gpioIndex}"}
                GpioPin(it.gpioIndex,it.isEnabled,direction == GPIO_DIR.OUT,level == GPIO_VALUE.HIGH)
            }.also {
                require(expected == it.size) {"GPIO count mismatch: $expected/${it.size}"}
            }
        }
        val result=DriverDiagnostics(
            chip=if(type == ChipType2.CHIP_CH341) "CH340/CH341 family (suffix unknown)" else type?.description,
            sdkVersion=WchDeviceHub.PACKAGE_VERSION,
            baud=reading {sdk.getSerialBaud(device,port).also {require(it > 0) {"Invalid SDK baud report"}}},
            clock=reading {sdk.getChipMasterFrequency(device).let {
                require(it.frequency > 0) {"SDK clock report is unavailable: ${it.frequency}"}
                ClockReport(it.frequency,it.isSwitchEnable,it.coStatus)
            }},
            errors=SerialErrorType.values().associate {kind -> kind.name to reading {
                sdk.querySerialErrorCount(device,port,kind).also {require(it >= 0) {"SDK error count is unavailable: $it"}}
            }},gpio=gpio)
        detected=result
        result
    }
    override fun configureGpio(index: Int,enabled: Boolean,output: Boolean): Unit = hub.control(device) {
        check(!closed.get()) {"WCH port closed"}
        check(sdk.enableGPIO(device,index,enabled,if(output) GPIO_DIR.OUT else GPIO_DIR.IN)) {"WCH GPIO configuration failed"}
    }
    override fun writeGpio(index: Int,high: Boolean): Unit = hub.control(device) {
        check(!closed.get()) {"WCH port closed"}
        val current=sdk.queryGPIOStatus(device,index)
        check(current.isEnabled && current.dir == GPIO_DIR.OUT) {"GPIO is not enabled as an output"}
        check(sdk.setGPIOVal(device,index,if(high) GPIO_VALUE.HIGH else GPIO_VALUE.LOW)) {"WCH GPIO output failed"}
    }
    override fun close() {if(closed.compareAndSet(false,true)) {incoming.close();hub.release(device,port)}}
}
