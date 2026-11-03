package com.lyufeng.phoneserialport

import com.lyufeng.phoneserialport.data.*
import com.lyufeng.phoneserialport.terminal.HexCodec
import org.junit.Assert.*
import org.junit.Test

class MacroCommandsTest {
    @Test fun copyingTextFreezesTheCurrentCharsetAndEnding() {
        val source=MacroEntity(name="温度",content="温度",insertOnly=true,delayMs=9999)
        val step=MacroCommands.copyStep(source,AppSettings(charset="GBK",ending="AUTO",receiveEnding="LF"))
        assertArrayEquals("温度\n".toByteArray(charset("GBK")),HexCodec.decode(step.hex))
        assertEquals(100L,step.delayMs)
        assertEquals(1,step.repeats)
        assertTrue(step.included)
        val changed=source.copy(content="其他")
        assertNotEquals(step.hex,MacroCommands.copyStep(changed,AppSettings()).hex)
        assertArrayEquals("温度\n".toByteArray(charset("GBK")),HexCodec.decode(step.hex))
    }

    @Test fun copyingHexUsesTheCurrentEndingAndRetainsHexEditing() {
        val step=MacroCommands.copyStep(MacroEntity(name="raw",content="FF 00",hex=true),AppSettings(ending="CRLF"))
        assertEquals("FF 00 0D 0A",step.hex)
        assertTrue(step.hexView)
    }

    @Test fun copyingASequencePreservesItsBytesTimingAndNestedSteps() {
        val root=VofaCommand("existing",group=true,included=false,delayMs=87,repeats=3,children=listOf(
            VofaCommand("nested",group=true,delayMs=12,children=listOf(VofaCommand("SC","53 43 0A",delayMs=30000)))
        ))
        val copied=MacroCommands.copyStep(MacroEntity(name="existing",content=VofaCommands.content(root)),AppSettings(ending="CRLF"))
        assertEquals(root.copy(included=true),copied)
        assertNotSame(root.children,copied.children)
        assertNotSame(root.children.single().children,copied.children.single().children)
        assertEquals("53 43 0A",copied.children.single().children.single().hex)
    }

    @Test fun emptyOrInvalidMacrosCannotBecomeSteps() {
        assertThrows(IllegalArgumentException::class.java) {
            MacroCommands.copyStep(MacroEntity(name="empty",content=""),AppSettings())
        }
        assertThrows(IllegalArgumentException::class.java) {
            MacroCommands.copyStep(MacroEntity(name="bad",content="GG",hex=true),AppSettings())
        }
        assertThrows(IllegalArgumentException::class.java) {
            MacroCommands.copyStep(MacroEntity(name="too big",content="A".repeat(1024*1024+1)),AppSettings(ending="NONE"))
        }
    }
}
