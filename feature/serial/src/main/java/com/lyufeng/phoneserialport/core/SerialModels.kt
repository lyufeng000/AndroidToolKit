package com.lyufeng.phoneserialport.core

import java.util.UUID

enum class Parity { NONE, ODD, EVEN, MARK, SPACE }
enum class StopBits { ONE, ONE_POINT_FIVE, TWO }
enum class FlowControl { NONE, RTS_CTS, DTR_DSR, XON_XOFF }
enum class CapabilityState { SUPPORTED, HARDWARE_UNSUPPORTED, DRIVER_UNIMPLEMENTED, UNKNOWN, READ_FAILED }
data class Capability(val state: CapabilityState, val detail: String = "")
data class SerialConfig(
    val baudRate: Int = 115200,
    val startBits: Int = 1,
    val dataBits: Int = 8,
    val parity: Parity = Parity.NONE,
    val stopBits: StopBits = StopBits.ONE,
    val flowControl: FlowControl = FlowControl.NONE,
) {
    fun validate() {
        require(baudRate > 0) { "Baud rate must be positive" }
        require(startBits == 1) { "This driver supports one start bit" }
        require(dataBits in 5..8) { "Data bits must be 5..8" }
    }
    fun summary() = "$baudRate / $dataBits${parity.name.first()}${when(stopBits) { StopBits.ONE -> "1"; StopBits.ONE_POINT_FIVE -> "1.5"; StopBits.TWO -> "2" }} / ${flowControl.name}"
}

data class SerialCapabilities(
    val startBits: Capability = Capability(CapabilityState.HARDWARE_UNSUPPORTED, "Fixed: 1"),
    val dataBits: Set<Int> = (5..8).toSet(),
    val parities: Set<Parity> = Parity.entries.toSet(),
    val stopBits: Set<StopBits> = setOf(StopBits.ONE, StopBits.TWO),
    val flowControls: Set<FlowControl> = setOf(FlowControl.NONE),
    val controlLines: Set<String> = emptySet(),
    val breakControl: Capability = Capability(CapabilityState.UNKNOWN),
    val baudRead: Capability = Capability(CapabilityState.DRIVER_UNIMPLEMENTED),
    val clockRead: Capability = Capability(CapabilityState.DRIVER_UNIMPLEMENTED),
    val uartErrors: Capability = Capability(CapabilityState.DRIVER_UNIMPLEMENTED),
    val gpio: Capability = Capability(CapabilityState.UNKNOWN),
    val reset: Capability = Capability(CapabilityState.DRIVER_UNIMPLEMENTED),
    val parameterKnowledge: Capability = Capability(CapabilityState.UNKNOWN),
    val minimumBaud: Int? = null,
    val maximumBaud: Int? = null,
) {
    val configurationAvailable: Boolean get() = parameterKnowledge.state !in setOf(CapabilityState.DRIVER_UNIMPLEMENTED,CapabilityState.HARDWARE_UNSUPPORTED)
    fun validate(config: SerialConfig) {
        require(configurationAvailable) {"UART parameter configuration is unavailable: ${parameterKnowledge.detail}"}
        config.validate()
        minimumBaud?.let { require(config.baudRate >= it) { "Baud rate is below the driver range" } }
        maximumBaud?.let { require(config.baudRate <= it) { "Baud rate is above the driver range" } }
        require(config.dataBits in dataBits && config.parity in parities && config.stopBits in stopBits) { "Unsupported frame format" }
        require(config.flowControl in flowControls) { "Flow control is not available in this driver" }
    }
}

data class BaudRateReport(
    val requested: Int,
    val driverReported: Int? = null,
    val divisorEstimated: Double? = null,
    val externallyMeasured: Double? = null,
    val estimateSource: String? = null,
    val measuredAt: Long? = null,
    val driverReportSource: String? = null,
    val configurationWarning: String? = null,
) {
    val estimatedDeviationPercent: Double? get() = divisorEstimated?.takeIf {requested > 0 && it.isFinite() && it > 0}?.let { (it - requested) / requested * 100 }
    val estimatedOutsideTolerance: Boolean get() = estimatedDeviationPercent?.let { kotlin.math.abs(it) > 1.0 } == true
    val measuredDeviationPercent: Double? get() = externallyMeasured?.let { (it - requested) / requested * 100 }
    val measuredPass: Boolean get() = measuredDeviationPercent?.let { kotlin.math.abs(it) <= 1.0 } == true
}

enum class Direction { RX, TX, STATUS, ERROR }
data class SerialEvent(
    val sessionId: String,
    val sequence: Long,
    val timestamp: Long = System.currentTimeMillis(),
    val direction: Direction,
    val data: ByteArray = byteArrayOf(),
    val message: String? = null,
    val streamReset: Boolean = false,
)
enum class PortState { CLOSED, OPENING, OPEN, CLOSING, DISCONNECTED, ERROR }
data class SerialCounters(
    val received: Long = 0,
    val sent: Long = 0,
    val rxOverflow: Long = 0,
    val txRejected: Long = 0,
    val displayTrimmed: Long = 0,
    val communicationLoss: Long? = null,
)
data class TransferProgress(val name: String, val mode: String, val completed: Long, val total: Long, val status: String)
data class CapabilityValue<T>(val capability: Capability, val value: T? = null)
data class ClockReport(val frequency: Int, val switchEnabled: Boolean, val status: Int)
data class GpioPin(val index: Int, val enabled: Boolean, val output: Boolean, val high: Boolean)
data class DriverDiagnostics(
    val chip: String? = null,
    val sdkVersion: String? = null,
    val baud: CapabilityValue<Int> = CapabilityValue(Capability(CapabilityState.DRIVER_UNIMPLEMENTED)),
    val clock: CapabilityValue<ClockReport> = CapabilityValue(Capability(CapabilityState.DRIVER_UNIMPLEMENTED)),
    val errors: Map<String,CapabilityValue<Int>> = emptyMap(),
    val gpio: CapabilityValue<List<GpioPin>> = CapabilityValue(Capability(CapabilityState.DRIVER_UNIMPLEMENTED)),
)
data class SessionSnapshot(
    val id: String,
    val deviceName: String,
    val portIndex: Int,
    val driver: String,
    val state: PortState = PortState.CLOSED,
    val appliedConfig: SerialConfig? = null,
    val lastConfirmedConfig: SerialConfig? = null,
    val capabilities: SerialCapabilities = SerialCapabilities(),
    val baudReport: BaudRateReport? = null,
    val counters: SerialCounters = SerialCounters(),
    val error: String? = null,
    val transfer: TransferProgress? = null,
    val lines: Map<String, Boolean> = emptyMap(),
    val repeating: Boolean = false,
    val commandGroupName: String? = null,
    val transmitting: Boolean = false,
    val flowBlocked: Boolean = false,
    val diagnostics: DriverDiagnostics? = null,
)
data class WriteResult(val confirmed: Int, val error: Throwable? = null, val completionUnknown: Boolean = false)
interface SerialDriverAdapter {
    val driverName: String
    val capabilities: SerialCapabilities
    fun apply(config: SerialConfig): BaudRateReport
    fun read(buffer: ByteArray): Int
    fun write(bytes: ByteArray, timeoutMs: Int): WriteResult
    fun control(name: String, enabled: Boolean)
    fun readControlLines(): Map<String, Boolean>
    fun takeReceiveOverflow(): Long = 0
    fun takeReceiveDiscontinuity(): Boolean = false
    fun diagnose(): DriverDiagnostics = DriverDiagnostics(
        baud=CapabilityValue(capabilities.baudRead),clock=CapabilityValue(capabilities.clockRead),
        errors=listOf("OVERRUN","PARITY","FRAME").associateWith {CapabilityValue<Int>(capabilities.uartErrors)},
        gpio=CapabilityValue(capabilities.gpio))
    fun configureGpio(index: Int, enabled: Boolean, output: Boolean): Unit = throw UnsupportedOperationException("GPIO configuration is not implemented by this driver")
    fun writeGpio(index: Int, high: Boolean): Unit = throw UnsupportedOperationException("GPIO output is not implemented by this driver")
    fun close()
}

object SerialPresets {
    val baudRates = listOf(50,75,100,110,150,300,600,900,1200,1800,2400,3600,4800,9600,14400,19200,
        28800,33600,38400,56000,57600,76800,115200,128000,153600,230400,256000,307200,460800,
        921600,1000000,1152000,1500000,2000000,3000000,4000000,6000000,9000000)
    val atCommands = listOf("ATE1","ATI","AT+CIMI","AT+CGSN","AT+CPIN?","AT+CSCA?","AT+CPMS?","AT+CGPADDR=1","AT+CCLK?","AT+CGATT?","AT+CSQ")
    fun newId(): String = UUID.randomUUID().toString()
}
