package com.lyufeng.phoneserialport.transfer

import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.InputStream

enum class FileMode { RAW, LINES, XMODEM, XMODEM_CRC, XMODEM_1K }

object XmodemPacket {
    const val SOH = 1; const val STX = 2; const val EOT = 4; const val ACK = 6; const val NAK = 21; const val CAN = 24
    fun crc(data: ByteArray): Int {
        var crc = 0
        for(byte in data) {
            crc = crc xor ((byte.toInt() and 255) shl 8)
            repeat(8) { crc = if(crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1 }
            crc = crc and 65535
        }
        return crc
    }
    fun build(number: Int, payload: ByteArray, crc: Boolean): ByteArray {
        require(payload.size == 128 || payload.size == 1024)
        val checksum = if(crc) crc(payload).let { byteArrayOf((it shr 8).toByte(),it.toByte()) }
            else byteArrayOf(payload.sumOf { it.toInt() and 255 }.toByte())
        return byteArrayOf(if(payload.size == 1024) STX.toByte() else SOH.toByte(),number.toByte(),(255-(number and 255)).toByte()) + payload + checksum
    }
}

class XmodemSender(
    private val responses: ReceiveChannel<Int>,
    private val write: suspend (ByteArray) -> Unit,
    private val progress: (Long) -> Unit,
    private val timeoutMs: Long = 10000,
    private val retries: Int = 3,
) {
    private suspend fun response(expected: Set<Int>): Int {
        var previousCancel = false
        while(true) {
            val value = responses.receive()
            if(value == XmodemPacket.CAN && previousCancel) error("Receiver cancelled transfer")
            previousCancel = value == XmodemPacket.CAN
            if(value in expected) return value
        }
    }
    suspend fun send(input: InputStream, mode: FileMode) {
        val useCrc = mode != FileMode.XMODEM
        val handshake = if(useCrc) 'C'.code else XmodemPacket.NAK
        var ready = false
        for(attempt in 0..retries) {
            if(withTimeoutOrNull(timeoutMs) { response(setOf(handshake)) } == handshake) { ready = true; break }
        }
        check(ready) { "Xmodem handshake timed out" }
        val size = if(mode == FileMode.XMODEM_1K) 1024 else 128
        var number = 1
        var confirmed = 0L
        while(true) {
            currentCoroutineContext().ensureActive()
            val payload = ByteArray(size) { 0x1a }
            var count = 0
            while(count < size) { val n = input.read(payload,count,size-count); if(n < 0) break; if(n == 0) continue; count += n }
            if(count == 0) break
            val packet = XmodemPacket.build(number,payload,useCrc)
            var ack = false
            for(attempt in 0..retries) {
                write(packet)
                if(withTimeoutOrNull(timeoutMs) { response(setOf(XmodemPacket.ACK,XmodemPacket.NAK)) } == XmodemPacket.ACK) { ack = true; break }
            }
            check(ack) { "Xmodem block $number failed after ${retries+1} attempts" }
            confirmed += count; progress(confirmed); number = (number+1) and 255
        }
        for(attempt in 0..retries) {
            write(byteArrayOf(XmodemPacket.EOT.toByte()))
            if(withTimeoutOrNull(timeoutMs) { response(setOf(XmodemPacket.ACK,XmodemPacket.NAK)) } == XmodemPacket.ACK) return
        }
        error("Xmodem end handshake timed out")
    }
}
