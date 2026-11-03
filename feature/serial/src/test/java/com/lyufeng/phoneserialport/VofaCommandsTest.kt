package com.lyufeng.phoneserialport

import com.lyufeng.phoneserialport.data.*
import com.lyufeng.phoneserialport.terminal.HexCodec
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class VofaCommandsTest {
    @Test fun nestedGroupsKeepActualBytesEndingsOrderAndLongDelays()=runBlocking {
        val node=VofaCommand("测试组",group=true,delayMs=100,children=listOf(
            VofaCommand("SC","53 43 0A",delayMs=80),
            VofaCommand("CA","43 41 0A",delayMs=30000),
            VofaCommand("PT50","50 54 36 30 0A",delayMs=8000),
            VofaCommand("no ending","53 49 2D 30 2E 30 30 30 30 30 31",delayMs=0),
        ))
        val restored=VofaCommands.command(VofaCommands.content(node))
        assertEquals(node,restored)
        val sent=mutableListOf<String>();val waits=mutableListOf<Long>()
        VofaCommands.run(restored,{sent+=HexCodec.encode(it)},{waits+=it})
        assertEquals(listOf("53 43 0A","43 41 0A","50 54 36 30 0A","53 49 2D 30 2E 30 30 30 30 30 31"),sent)
        assertEquals(listOf(80L,30000L,8000L,0L,100L),waits)
    }
    @Test fun inclusionFlagsApplyToGroupsButDoNotDisableDirectCommands()=runBlocking {
        val excluded=VofaCommand("skip","FF",included=false,delayMs=0)
        val group=VofaCommand("group",group=true,repeats=2,delayMs=0,children=listOf(excluded,VofaCommand("send","00 0A",delayMs=0)))
        val writes=mutableListOf<String>()
        VofaCommands.run(group,{writes+=HexCodec.encode(it)},{})
        assertEquals(listOf("00 0A","00 0A"),writes)
        VofaCommands.run(excluded,{writes+=HexCodec.encode(it)},{})
        assertEquals("FF",writes.last())
    }
    @Test fun infiniteSequencesCanBeCancelledDuringTheirDelay()=runBlocking {
        var writes=0
        val task=launch {VofaCommands.run(VofaCommand("loop","AA",repeats=-1,delayMs=30000),{writes++})}
        withTimeout(1000) {while(writes == 0) yield()}
        task.cancelAndJoin()
        assertEquals(1,writes)
    }
    @Test fun invalidDataIsRejectedBeforeAnySequenceWrite()=runBlocking {
        var writes=0
        val invalid=VofaCommand("group",group=true,children=listOf(VofaCommand("valid","AA"),VofaCommand("invalid","GG")))
        assertNotNull(runCatching {VofaCommands.run(invalid,{writes++},{})}.exceptionOrNull())
        assertEquals(0,writes)
        for(node in listOf(VofaCommand("bad","A"),VofaCommand("bad","AA",repeats=0),VofaCommand("bad","AA",delayMs=-1))) {
            assertThrows(IllegalArgumentException::class.java) {VofaCommands.validate(node)}
        }
    }
    @Test fun exportedTreeAndOldSettingsRoundTripWithoutADataMigration() {
        val data="""{"type":"cmds","vnumber":100,"ctx":{"name":"root","is_group":true,"subCmds":[{"name":"SC","cmd_hex":"53 43 0A","hex_on":false}]}}""".toByteArray()
        val commands=VofaCommands.decodeExport(data)
        assertEquals(1,commands.size);assertEquals("53 43 0A",commands.single().hex)
        val options=SettingsCodec.decode(JSONObject())
        assertFalse(options.enterToSend)
        assertTrue(SettingsCodec.decode(SettingsCodec.encode(options.copy(enterToSend=true))).enterToSend)
        val macro=MacroEntity(name="SC",content=VofaCommands.content(commands.single()))
        val archive=ConfigurationArchive.encode(ImportedConfiguration(options,listOf(macro),emptyList()))
        assertEquals(macro,ConfigurationArchive.decode(archive,emptyList()).macros.single())
    }
    @Test fun suppliedMotorExportDecodesWithoutRunningMotorCommands()=runBlocking {
        val file=File("C:/Users/11910/Documents/xwechat_files/wxid_vu1s6hf3scfq22_1edd/msg/file/2026-10/电机测试自动命令vofa+.cmds.json")
        assumeTrue("User export is not present on this host",file.isFile)
        val commands=VofaCommands.decodeExport(file.readBytes())
        assertTrue(commands.size >= 30)
        val group=commands.single {it.name == "大电机开始测试"}
        val bytes=mutableListOf<String>();val waits=mutableListOf<Long>()
        VofaCommands.run(group,{bytes+=HexCodec.encode(it)},{waits+=it})
        assertEquals(listOf("53 43 0A","43 41 0A","55 50 0A","53 43 0A"),bytes)
        assertEquals(listOf(80L,30000L,800L,100L,0L),waits)
        assertEquals(-1,commands.single {it.name == "动态相应测试"}.repeats)
    }
}
