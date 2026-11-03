package com.lyufeng.phoneserialport.data

import com.lyufeng.phoneserialport.terminal.HexCodec
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

data class VofaCommand(
    val name: String,
    val hex: String = "",
    val group: Boolean = false,
    val children: List<VofaCommand> = emptyList(),
    val included: Boolean = true,
    val delayMs: Long = 100,
    val repeats: Int = 1,
    val hexView: Boolean = false,
    val note: String = "",
)

/** Store typed command trees in the existing macro payload without changing the database schema. */
object VofaCommands {
    private const val PREFIX = "\u001eVOFA1\n"
    const val MAX_BYTES = 2 * 1024 * 1024
    fun isSequence(content: String) = content.startsWith(PREFIX)
    fun content(command: VofaCommand): String {
        validate(command)
        return PREFIX + json(command).toString()
    }
    fun command(content: String): VofaCommand {
        require(isSequence(content)) {"Not a VOFA macro"}
        require(content.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) {"VOFA macro exceeds 2 MiB"}
        return parse(JSONObject(content.removePrefix(PREFIX)), 0, intArrayOf(0)).also(::validate)
    }
    fun decodeExport(data: ByteArray): List<VofaCommand> {
        require(data.size <= MAX_BYTES) {"VOFA file exceeds 2 MiB"}
        val text=Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data)).toString().removePrefix("\uFEFF")
        val root=JSONObject(text)
        require(root.getString("type") == "cmds") {"Not a VOFA command export"}
        require(integer(root,"vnumber",100) in setOf(100L,140L)) {"Unsupported VOFA command version"}
        val tree=parse(root.getJSONObject("ctx"),0,intArrayOf(0)).also(::validate)
        return if(tree.group) tree.children else listOf(tree)
    }
    fun validate(root: VofaCommand) {
        var count=0
        fun check(node: VofaCommand,depth: Int) {
            require(depth <= 16 && ++count <= 2048) {"VOFA command tree is too large"}
            require(node.name.isNotBlank() && node.name.length <= 256) {"Command name is empty or too long"}
            require(node.delayMs in 0..86_400_000 && (node.repeats == -1 || node.repeats in 1..1_000_000)) {"Invalid command delay or repeat count"}
            if(node.group) {
                require(node.children.isNotEmpty()) {"Command group is empty"}
                require(node.children.any {it.included}) {"Command group has no enabled steps"}
                node.children.forEach {check(it,depth+1)}
            } else {
                val bytes=HexCodec.decode(node.hex)
                require(bytes.isNotEmpty() && bytes.size <= 1024*1024 && node.children.isEmpty()) {"Invalid command bytes"}
            }
        }
        check(root,0)
    }
    private fun integer(o: JSONObject,key: String,default: Long): Long {
        if(!o.has(key)) return default
        val value=o.get(key)
        require(value is Number) {"Invalid integer: $key"}
        return java.math.BigDecimal(value.toString()).longValueExact()
    }
    private fun flag(o: JSONObject,key: String,default: Boolean): Boolean =
        if(!o.has(key)) default else o.get(key) as? Boolean ?: throw IllegalArgumentException("Invalid flag: $key")
    private fun parse(o: JSONObject,depth: Int,count: IntArray): VofaCommand {
        require(depth <= 16 && ++count[0] <= 2048) {"VOFA command tree is too large"}
        val children=o.optJSONArray("subCmds") ?: JSONArray()
        val repeats=integer(o,"loop_count",1)
        require(repeats == -1L || repeats in 1..1_000_000) {"Invalid repeat count"}
        return VofaCommand(o.getString("name"),o.optString("cmd_hex",""),flag(o,"is_group",false),
            (0 until children.length()).map {parse(children.getJSONObject(it),depth+1,count)},
            flag(o,"loop_on",true),integer(o,"loop_ms",100),repeats.toInt(),flag(o,"hex_on",false),o.optString("intro",""))
    }
    private fun json(node: VofaCommand): JSONObject = JSONObject().put("name",node.name).put("cmd_hex",node.hex)
        .put("is_group",node.group).put("loop_on",node.included).put("loop_ms",node.delayMs)
        .put("loop_count",node.repeats).put("hex_on",node.hexView).put("intro",node.note)
        .put("subCmds",JSONArray().apply {node.children.forEach {put(json(it))}})

    /** loop_on controls inclusion in a group. A directly selected command still runs. */
    suspend fun run(root: VofaCommand,write: suspend (ByteArray)->Unit,wait: suspend (Long)->Unit = {delay(it)}) {
        validate(root)
        suspend fun execute(node: VofaCommand) {
            var iteration=0
            while(node.repeats == -1 || iteration < node.repeats) {
                currentCoroutineContext().ensureActive()
                if(node.group) node.children.filter {it.included}.forEach {execute(it)}
                else write(HexCodec.decode(node.hex))
                iteration++
                // Cancellation remains responsive even for zero-delay infinite loops.
                wait(if(node.repeats == -1) node.delayMs.coerceAtLeast(1) else node.delayMs)
            }
        }
        execute(root)
    }
}
