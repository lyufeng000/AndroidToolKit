package com.lyufeng.phoneserialport.terminal

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

object HexCodec {
    fun decode(text: String): ByteArray {
        require(text.all { it.isWhitespace() || it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) { "Invalid HEX character" }
        val compact = text.filterNot(Char::isWhitespace)
        require(compact.length % 2 == 0) { "HEX requires two digits per byte" }
        return ByteArray(compact.length / 2) { compact.substring(it*2,it*2+2).toInt(16).toByte() }
    }
    fun encode(bytes: ByteArray) = bytes.joinToString(" ") { "%02X".format(it.toInt() and 255) }
}

enum class DecodeErrorKind { MALFORMED, UNMAPPABLE }

data class DecodeIssue(val offset: Long, val rawBytes: ByteArray, val kind: DecodeErrorKind) {
    val rawHex: String get() = HexCodec.encode(rawBytes)
}

data class DecodedText(val text: String, val issues: List<DecodeIssue>)

class StreamTextDecoder(charset: Charset) {
    private val decoder = charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
    private var remainder = byteArrayOf()
    private var consumed = 0L

    fun decode(bytes: ByteArray, end: Boolean = false): String = decodeDetailed(bytes, end).text

    /** Report decoder errors. A valid U+FFFD does not produce an issue. */
    fun decodeDetailed(bytes: ByteArray, end: Boolean = false): DecodedText {
        val input = ByteBuffer.wrap(remainder + bytes)
        val output = CharBuffer.allocate(input.remaining().coerceIn(32, 4096))
        val text = StringBuilder()
        val issues = mutableListOf<DecodeIssue>()
        fun drain() {
            output.flip()
            text.append(output)
            output.clear()
        }
        while (true) {
            val result = decoder.decode(input, output, end)
            drain()
            when {
                result.isOverflow -> continue
                result.isError -> {
                    val offset = consumed + input.position()
                    val raw = ByteArray(result.length()).also(input::get)
                    issues += DecodeIssue(offset, raw, if(result.isMalformed) DecodeErrorKind.MALFORMED else DecodeErrorKind.UNMAPPABLE)
                    text.append('\uFFFD')
                }
                else -> break
            }
        }
        consumed += input.position()
        remainder = ByteArray(input.remaining()).also(input::get)
        if (end) {
            do {
                val result = decoder.flush(output)
                drain()
            } while (result.isOverflow)
        }
        return DecodedText(text.toString(), issues)
    }

    fun reset() {
        decoder.reset()
        remainder = byteArrayOf()
        consumed = 0L
    }

    /** Finish a broken stream before accepting bytes from a new stream. */
    fun finishAndReset(): DecodedText = try {
        decodeDetailed(byteArrayOf(), true)
    } finally {
        reset()
    }
}

/** Find character boundaries without re-encoding or inserting extra byte-order marks. */
fun characterChunks(bytes: ByteArray, charset: Charset): List<ByteArray> {
    val decoder=StreamTextDecoder(charset)
    val chunks=mutableListOf<ByteArray>()
    var start=0
    for(index in bytes.indices) {
        if(decoder.decode(byteArrayOf(bytes[index])).isNotEmpty()) {
            chunks+=bytes.copyOfRange(start,index+1);start=index+1
        }
    }
    if(start < bytes.size) chunks+=bytes.copyOfRange(start,bytes.size)
    return chunks
}

class ReceiveFormatter(private val ending: String, private val visualize: Boolean) {
    private var pendingCR = false
    fun feed(text: String): String = buildString {
        for (c in text) {
            if (ending == "CRLF") {
                if (pendingCR) { append(if (c == '\n') '\n' else '\r'); pendingCR = false; if(c == '\n') continue }
                if (c == '\r') { pendingCR = true; continue }
            }
            val adjusted = when {
                ending == "CR" && c == '\r' -> '\n'
                ending == "LF" && c == '\n' -> '\n'
                ending == "NUL" && c == '\u0000' -> '\n'
                ending == "STX_ETX" && c == '\u0002' -> continue
                ending == "STX_ETX" && c == '\u0003' -> '\n'
                else -> c
            }
            if (visualize && adjusted.code < 32 && adjusted !in listOf('\n','\r','\t')) append('^').append((adjusted.code + 64).toChar())
            else if (visualize && adjusted.code == 127) append("^?") else append(adjusted)
        }
    }
    fun finish(): String = if(pendingCR) { pendingCR = false; "\r" } else ""
}

fun encodeCommand(text: String, hex: Boolean, charset: String, ending: String, receiveEnding: String): ByteArray {
    val data = if(hex) HexCodec.decode(text) else {
        val encoder = Charset.forName(charset).newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        val buffer = encoder.encode(CharBuffer.wrap(text))
        ByteArray(buffer.remaining()).also(buffer::get)
    }
    require(data.isNotEmpty()) { "Nothing to send" }
    return applyEnding(data, if(ending == "AUTO") receiveEnding else ending)
}

fun applyEnding(data: ByteArray, ending: String): ByteArray = when(ending) {
    "CRLF" -> data + byteArrayOf(13,10)
    "CR" -> data + byteArrayOf(13)
    "LF" -> data + byteArrayOf(10)
    "STX_ETX" -> byteArrayOf(2) + data + byteArrayOf(3)
    "NUL" -> data + byteArrayOf(0)
    else -> data
}
