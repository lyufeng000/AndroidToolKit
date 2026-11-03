package com.lyufeng.phoneserialport.data

import com.lyufeng.phoneserialport.terminal.HexCodec
import com.lyufeng.phoneserialport.terminal.encodeCommand

/** Copy actual bytes, without retaining a live reference to a macro or global send options. */
object MacroCommands {
    fun copyStep(macro: MacroEntity,settings: AppSettings): VofaCommand {
        if(VofaCommands.isSequence(macro.content)) {
            return VofaCommands.command(macro.content).copy(included=true)
        }
        val bytes=encodeCommand(macro.content,macro.hex,settings.charset,settings.ending,settings.receiveEnding)
        return VofaCommand(macro.name,hex=HexCodec.encode(bytes),hexView=macro.hex).also(VofaCommands::validate)
    }
}
