package com.lyufeng.phoneserialport.data

import com.lyufeng.phoneserialport.core.SerialPresets
import com.lyufeng.phoneserialport.terminal.HexCodec
import org.json.JSONArray
import org.json.JSONObject

data class ImportedConfiguration(val settings: AppSettings,val macros: List<MacroEntity>,val mappings: List<DeviceMappingEntity>)

/** Decode and validate the complete archive before any persistent state is changed. */
object ConfigurationArchive {
    const val MAX_BYTES=2*1024*1024
    fun decode(data: ByteArray,drivers: Collection<String>): ImportedConfiguration {
        require(data.size <= MAX_BYTES) {"Configuration exceeds 2 MiB"}
        val text=Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(data)).toString().removePrefix("\uFEFF")
        return decode(JSONObject(text),drivers)
    }
    fun encode(value: ImportedConfiguration): JSONObject = JSONObject().put("schemaVersion",1)
        .put("settings",SettingsCodec.encode(value.settings))
        .put("macros",JSONArray().apply {value.macros.forEach {m -> put(JSONObject().put("id",m.id).put("name",m.name)
            .put("content",m.content).put("hex",m.hex).put("insertOnly",m.insertOnly).put("row",m.row).put("delayMs",m.delayMs))}})
        .put("mappings",JSONArray().apply {value.mappings.forEach {m -> put(JSONObject().put("vendorId",m.vendorId)
            .put("productId",m.productId).put("driver",m.driver))}})
    private fun JSONObject.integer(key: String,default: Int? = null): Int {
        if(!has(key) && default != null) return default
        val value=get(key)
        require(value is Number) {"Invalid integer field: $key"}
        return try {java.math.BigDecimal(value.toString()).intValueExact()}
            catch(_: ArithmeticException) {throw IllegalArgumentException("Integer is fractional or out of range: $key")}
    }
    private fun JSONObject.text(key: String,default: String? = null): String {
        if(!has(key) && default != null) return default
        return (get(key) as? String) ?: throw IllegalArgumentException("Invalid string field: $key")
    }
    private fun JSONObject.flag(key: String,default: Boolean): Boolean {
        if(!has(key)) return default
        return (get(key) as? Boolean) ?: throw IllegalArgumentException("Invalid boolean field: $key")
    }
    private fun JSONObject.array(key: String): JSONArray {
        if(!has(key)) return JSONArray()
        return (get(key) as? JSONArray) ?: throw IllegalArgumentException("Invalid array field: $key")
    }
    fun decode(root: JSONObject,drivers: Collection<String>): ImportedConfiguration {
        require(root.integer("schemaVersion") == 1) {"Unsupported configuration version"}
        val settings=if(root.has("settings")) (root.get("settings") as? JSONObject
            ?: throw IllegalArgumentException("Invalid settings object")) else JSONObject()
        val options=SettingsCodec.decode(settings)
        val macros=root.array("macros");val mappings=root.array("mappings")
        require(macros.length() <= 10000 && mappings.length() <= 10000) {"Too many configuration records"}
        val parsedMacros=(0 until macros.length()).map {index ->
            val m=macros.getJSONObject(index)
            MacroEntity(m.text("id",SerialPresets.newId()),m.text("name"),m.text("content"),m.flag("hex",false),m.flag("insertOnly",false),m.integer("row",0),m.integer("delayMs",0)).also {
                require(it.id.isNotBlank() && it.name.isNotBlank() && it.row in 0..4 && it.delayMs in 0..9999) {"Invalid macro"}
                if(it.hex) HexCodec.decode(it.content)
                if(VofaCommands.isSequence(it.content)) VofaCommands.command(it.content)
            }
        }
        require(parsedMacros.map {it.id}.distinct().size == parsedMacros.size) {"Duplicate macro IDs"}
        val parsedMappings=(0 until mappings.length()).map {index ->
            val m=mappings.getJSONObject(index);val v=m.integer("vendorId");val p=m.integer("productId");val driver=m.text("driver")
            require(v in 0..65535 && p in 0..65535 && driver in drivers) {"Invalid device mapping"}
            DeviceMappingEntity("$v:$p",v,p,driver)
        }
        require(parsedMappings.map {it.key}.distinct().size == parsedMappings.size) {"Duplicate device mappings"}
        return ImportedConfiguration(options,parsedMacros,parsedMappings)
    }
}
