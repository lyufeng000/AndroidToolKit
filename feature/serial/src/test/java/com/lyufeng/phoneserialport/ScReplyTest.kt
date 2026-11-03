package com.lyufeng.phoneserialport

import com.lyufeng.phoneserialport.core.Direction
import com.lyufeng.phoneserialport.core.SerialEvent
import com.lyufeng.phoneserialport.data.AppSettings
import com.lyufeng.phoneserialport.terminal.EventTextRenderer
import com.lyufeng.phoneserialport.terminal.TextRenderContext
import org.junit.Assert.*
import org.junit.Test

class ScReplyTest {
    // Transcribed from the user's known-good reply. These values are data, not commands.
    private val reply = listOf(
        "MotorID:2", "PolePairs:13", "Direction:CW", "Udc:24.502658", "Temp:29.838855",
        "MotorMode:NULL", "Resistance:1.551977", "Inductance:0.000566", "ReductionRatio:36",
        "MaxCurrent:10.000000", "MaxSpeed:200.000000", "IsOpenAntiCoggingFlag:YES",
        "AnticoggingCalibrated:NO", "EncoderOffset:1236", "CurrentKp:0.084944", "CurrentKi:0.020554",
        "SpeedKp:0.150000", "SpeedKi:0.000000", "PositionKp:3000.000000", "PositionKi:0.000000",
        "HeartbeatFlag:YES", "HeartbeatCycle:10", "WarningInfo:Null", "ErrorInfo:Null",
        "ENCoffset1:0.656927", "ENCoffset2:4.697049", "ENC1Live:2.709393", "ENC2Live:2.577854",
        "GearboxAbsAccAngleDirect:0.000000", "AbsAccAngle36Direct:0.000000",
    ).joinToString("\r\n", postfix = "\r\n")

    @Test fun knownGoodMotorReplyKeepsEveryDigitAcrossUsbPacketSizes() {
        val bytes = reply.toByteArray(Charsets.UTF_8)
        assertTrue(bytes.all {it.toInt() in 0..127})
        for(size in listOf(1, 2, 7, 32, 64, 256, 4096)) {
            val events = bytes.toList().chunked(size).mapIndexed {index, chunk ->
                SerialEvent("SC", index.toLong()+2, direction = Direction.RX, data = chunk.toByteArray())
            }
            val sent = SerialEvent("SC", 1, direction = Direction.TX, data = "SC\r\n".toByteArray())
            val rows = EventTextRenderer.formatEvents(listOf(sent)+events, AppSettings(timestamps = false),
                TextRenderContext(endOfInput = true))
            assertEquals("Packet size $size", reply.replace("\r\n", "\n"),
                rows.filter {it.direction == Direction.RX}.joinToString("") {it.text})
            assertTrue(rows.all {it.issues.isEmpty()})
            assertEquals("SC\r\n", rows.single {it.direction == Direction.TX}.text)
        }
    }

    @Test fun hexViewKeepsTheExactReceivedBytesForDiagnosis() {
        val data = byteArrayOf(0xff.toByte(), 0x80.toByte(), 0x37, 0, 0x41)
        val rows = EventTextRenderer.formatEvents(listOf(SerialEvent("SC", 1, direction = Direction.RX, data = data)),
            AppSettings(displayMode = "HEX"))
        assertEquals("FF 80 37 00 41", rows.single().text)
        assertEquals("FF 80 37 00 41", rows.single().rawHex)
    }
}
