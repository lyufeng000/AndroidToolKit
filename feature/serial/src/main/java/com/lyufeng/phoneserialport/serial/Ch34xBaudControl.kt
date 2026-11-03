package com.lyufeng.phoneserialport.serial

import com.lyufeng.phoneserialport.core.*
import java.io.IOException
import kotlin.math.abs

/** Register model: https://github.com/torvalds/linux/blob/master/drivers/usb/serial/ch341.c */
data class Ch34xBaudPlan(val prescaler: Int, val factor: Int, val divisor: Int) {
    val estimated: Double get() = 48_000_000.0 / ((1 shl (12 - 3 * prescaler - factor)) * divisor)
    val register: Int get() = ((256 - divisor) shl 8) or (factor shl 2) or prescaler

    companion object {
        fun closest(requested: Int, limitedPrescaler: Boolean = false): Ch34xBaudPlan {
            require(requested in 46..3_000_000) { "CH34x baud rate must be 46..3000000" }
            var best: Ch34xBaudPlan? = null
            // Prefer factor=0 on equal errors. This gives the receiver more clock samples.
            for (factor in 0..1) for (ps in 3 downTo 0) {
                if (limitedPrescaler && factor == 1 && ps < 3) continue
                for (div in (if (factor == 0) 2 else 9)..256) {
                    val candidate = Ch34xBaudPlan(ps, factor, div)
                    if (best == null || abs(candidate.estimated - requested) < abs(best.estimated - requested)) best = candidate
                }
            }
            return requireNotNull(best)
        }
    }
}

/** Uses the port's existing USB handle. It never opens a competing connection. */
class Ch34xBaudControl(private val transfer: (request: Int, value: Int, index: Int, input: ByteArray?) -> Int) {
    private var version: Int? = null
    private var limitedPrescaler = true
    val legacyLineControl: Boolean get() = version?.let {it < 0x30} == true
    var breakCapability: Capability = Capability(CapabilityState.UNKNOWN, "CH34x BREAK register has not been probed")
        private set

    fun apply(config: SerialConfig): BaudRateReport {
        // Validate everything before the first device write.
        SerialCapabilities(minimumBaud = 46, maximumBaud = 3_000_000).validate(config)
        if (version == null) {
            val bytes = ByteArray(2)
            if (transfer(0x5f, 0, 0, bytes) != 2) throw IOException("CH34x version read failed")
            version = bytes[0].toInt() and 255
            // A failed BREAK register read identifies restricted devices in the reference driver.
            // A short read is also treated conservatively, without assuming hardware support.
            val probe = transfer(0x95, 0x0005, 0, ByteArray(2))
            limitedPrescaler = probe != 2
            breakCapability = Capability(if(probe == 2) CapabilityState.SUPPORTED else CapabilityState.UNKNOWN,
                if(probe == 2) "CH34x BREAK register read succeeded; hardware verification pending" else "CH34x BREAK register read failed; conservative prescaler settings are used")
        }
        require(!legacyLineControl || config.dataBits == 8 && config.parity == Parity.NONE && config.stopBits == StopBits.ONE) {
            "CH34x versions below 0x30 use legacy line control; only the initialized 8N1 format is available"
        }
        val plan = Ch34xBaudPlan.closest(config.baudRate, limitedPrescaler)
        val register = plan.register or if (requireNotNull(version) > 0x27) 0x80 else 0
        write(0x1312, register)
        val parity = when (config.parity) {
            Parity.NONE -> 0
            Parity.ODD -> 0x08
            Parity.EVEN -> 0x18
            Parity.MARK -> 0x28
            Parity.SPACE -> 0x38
        }
        val line = 0xc0 or (config.dataBits - 5) or parity or if (config.stopBits == StopBits.TWO) 4 else 0
        if(!legacyLineControl) write(0x2518, line)
        // WCH's driver uses 0x0f2c for USB packet upload timeout, not fractional baud.
        // Do not leave the old library's 9600-baud timeout active at high speed.
        val uploadTimeout = (76_800.0 / plan.estimated).toInt().coerceIn(7, 255)
        write(0x0f2c, uploadTimeout)
        // Some chips cannot read these registers. A successful write is not a measurement.
        val readback = ByteArray(2)
        val count = transfer(0x95, 0x1312, 0, readback)
        val matched = if (count == 2) {
            val actual = (readback[0].toInt() and 255) or ((readback[1].toInt() and 255) shl 8)
            (actual and 0xff07) == (register and 0xff07)
        } else false
        val readbackStatus = if(matched) "matches write (support unverified)" else if(count == 2) "mismatch (support unverified)" else "unavailable"
        return BaudRateReport(config.baudRate, divisorEstimated = plan.estimated,
            estimateSource = "CH34x nearest divisor; 48 MHz nominal clock; version=0x${requireNotNull(version).toString(16)}; " +
                "0x1312=0x${register.toString(16)}; limitedPrescaler=$limitedPrescaler; " +
                "register readback=$readbackStatus; not measured",
            configurationWarning = if(count == 2 && !matched) "CH34x divisor readback differs from the write. Readback support is unverified. The baud estimate is not confirmed." else null)
    }

    private fun write(address: Int, value: Int) {
        if (transfer(0x9a, address, value, null) != 0) throw IOException("CH34x register write failed: 0x${address.toString(16)}")
    }
}
