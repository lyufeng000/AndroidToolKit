package com.lyufeng.phoneserialport

import com.lyufeng.phoneserialport.core.*
import com.lyufeng.phoneserialport.serial.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import kotlin.math.abs

class Ch34xBaudControlTest {
    private class Device(val version: Int = 0x30, val limited: Boolean = false) {
        val writes = mutableListOf<Pair<Int, Int>>()
        var failAddress: Int? = null
        var readable = true
        var mismatch = false
        var versionLength = 2
        val control = Ch34xBaudControl { request, address, value, input ->
            when {
                request == 0x5f -> { input!![0] = version.toByte(); versionLength }
                request == 0x95 && address == 5 -> if(limited) -1 else 2
                request == 0x9a -> {
                    writes += address to value
                    if(address == failAddress) -1 else 0
                }
                request == 0x95 && address == 0x1312 -> {
                    val register = writes.last {it.first == 0x1312}.second xor if(mismatch) 0x0100 else 0
                    input!![0] = register.toByte(); input[1] = (register shr 8).toByte()
                    if(readable) 2 else -1
                }
                else -> error("Unexpected control transfer")
            }
        }
    }

    @Test fun screenshotRateWritesTheCorrectedDivisorAndReportsRemainingError() {
        val device = Device()
        val report = device.control.apply(SerialConfig(baudRate = 1152000))
        assertEquals(listOf(0x1312 to 0xfb83, 0x2518 to 0xc3, 0x0f2c to 7), device.writes)
        assertEquals(1_200_000.0, report.divisorEstimated!!, 0.001)
        assertEquals(4.16666667, report.estimatedDeviationPercent!!, 0.000001)
        assertTrue(report.estimatedOutsideTolerance)
        assertNull(report.driverReported); assertNull(report.externallyMeasured); assertFalse(report.measuredPass)
        assertTrue(report.estimateSource!!.contains("readback=matches write"))
        assertTrue(abs(report.estimatedDeviationPercent!!) < abs((1_000_000.0-1152000)/1152000*100))
    }

    @Test fun exactRatesAndThe921600SpecialCaseRemainAccurate() {
        for(rate in listOf(1_000_000, 1_500_000, 2_000_000, 3_000_000)) {
            val report = Device().control.apply(SerialConfig(baudRate = rate))
            assertEquals(rate.toDouble(), report.divisorEstimated!!, 0.0001)
            assertFalse(report.estimatedOutsideTolerance)
        }
        val device = Device()
        val report = device.control.apply(SerialConfig(baudRate = 921600))
        assertEquals(0xf387, device.writes.first().second)
        assertTrue(abs(report.estimatedDeviationPercent!!) < 0.2)
    }

    @Test fun oldVersionKeepsInitialized8N1AndDoesNotForceTheInvertedLatencyBit() {
        val device = Device(version = 0x27)
        device.control.apply(SerialConfig(baudRate = 1152000))
        assertEquals(listOf(0x1312 to 0xfb03, 0x0f2c to 7), device.writes)
        device.writes.clear()
        assertThrows(IllegalArgumentException::class.java) {device.control.apply(SerialConfig(dataBits = 7))}
        assertTrue(device.writes.isEmpty())
    }

    @Test fun lineFormatAndBreakProbeAreNotSilentlyIgnored() {
        val device=Device()
        device.control.apply(SerialConfig(dataBits = 7, parity = Parity.EVEN, stopBits = StopBits.TWO))
        assertEquals(0xde, device.writes.first {it.first == 0x2518}.second)
        assertEquals(CapabilityState.SUPPORTED, device.control.breakCapability.state)
        val limited=Device(limited = true)
        limited.control.apply(SerialConfig())
        assertEquals(CapabilityState.UNKNOWN, limited.control.breakCapability.state)
    }

    @Test fun limitedDevicesNeverUseUnsupportedPrescalerCombinations() {
        for(rate in listOf(46, 50, 110, 300, 1200, 9600, 19200, 38400, 57600, 115200, 230400, 921600, 1152000, 3000000)) {
            val plan = Ch34xBaudPlan.closest(rate, limitedPrescaler = true)
            assertTrue(plan.factor == 0 || plan.prescaler == 3)
            val legacy = Ch341Divisor.androidEstimate(rate)!!
            assertTrue("Rate $rate reg=${plan.register}", abs(plan.estimated-rate) <= abs(legacy-rate) + 0.000001)
        }
    }

    @Test fun unavailableReadbackNeverBecomesAMeasurementOrAFalseConfirmation() {
        val device = Device().also {it.readable = false}
        val report = device.control.apply(SerialConfig())
        assertTrue(report.estimateSource!!.contains("readback=unavailable"))
        assertNull(report.driverReported); assertNull(report.externallyMeasured)
    }

    @Test fun rejectedWritesFailAndUnverifiedReadbackMismatchesProduceADiagnostic() {
        for(address in listOf(0x1312, 0x2518, 0x0f2c)) {
            val device = Device().also {it.failAddress = address}
            assertThrows(IOException::class.java) {device.control.apply(SerialConfig())}
        }
        val mismatch=Device().also {it.mismatch = true}.control.apply(SerialConfig())
        assertNotNull(mismatch.configurationWarning)
        assertTrue(mismatch.estimateSource!!.contains("mismatch"))
        assertNull(mismatch.driverReported); assertNull(mismatch.externallyMeasured)
        val short = Device().also {it.versionLength = 1}
        assertThrows(IOException::class.java) {short.control.apply(SerialConfig())}
        assertTrue(short.writes.isEmpty())
    }

    @Test fun unsupportedParametersFailBeforeAnyDeviceWrite() {
        for(config in listOf(SerialConfig(baudRate = 45), SerialConfig(baudRate = 3_000_001),
            SerialConfig(stopBits = StopBits.ONE_POINT_FIVE), SerialConfig(flowControl = FlowControl.RTS_CTS))) {
            val device = Device()
            assertThrows(IllegalArgumentException::class.java) {device.control.apply(config)}
            assertTrue(device.writes.isEmpty())
        }
    }
}
