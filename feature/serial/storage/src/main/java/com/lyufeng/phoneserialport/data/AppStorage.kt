package com.lyufeng.phoneserialport.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.*
import com.lyufeng.phoneserialport.core.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import org.json.JSONObject

private val Context.preferences by preferencesDataStore("serial_settings")

object SettingsCodec {
    fun encode(s: AppSettings): JSONObject = JSONObject().apply {
        put("version", 1)
        put("config", JSONObject().apply {
            put("baudRate",s.config.baudRate); put("startBits",s.config.startBits); put("dataBits",s.config.dataBits)
            put("parity",s.config.parity.name); put("stopBits",s.config.stopBits.name); put("flowControl",s.config.flowControl.name)
        })
        put("charset",s.charset); put("sendHex",s.sendHex); put("displayMode",s.displayMode)
        put("ending",s.ending); put("receiveEnding",s.receiveEnding); put("echo",s.echo)
        put("clearAfterSend",s.clearAfterSend); put("timestamps",s.timestamps); put("datePrefix",s.datePrefix)
        put("showStatus",s.showStatus); put("controlCharacters",s.controlCharacters); put("bufferBytes",s.bufferBytes)
        put("fontSize",s.fontSize); put("monospace",s.monospace); put("macroRows",s.macroRows)
        put("lineDelayMs",s.lineDelayMs); put("charDelayMs",s.charDelayMs); put("delayByCharacter",s.delayByCharacter)
        put("repeatCount",s.repeatCount); put("repeatIntervalMs",s.repeatIntervalMs); put("keepScreenOn",s.keepScreenOn)
        put("autoReconnect",s.autoReconnect); put("logEnabled",s.logEnabled); put("logHex",s.logHex)
        put("directoryUri",s.directoryUri); put("language",s.language); put("contact",s.contact); put("hintsSeen",s.hintsSeen)
        put("terminalColumns",s.terminalColumns); put("terminalRows",s.terminalRows); put("refreshMs",s.refreshMs)
        put("countInKiB",s.countInKiB); put("clearResetsCounters",s.clearResetsCounters); put("showControlLines",s.showControlLines)
        put("enterToSend",s.enterToSend)
    }
    fun decode(o: JSONObject): AppSettings {
        require(o.optInt("version", 1) == 1) { "Unsupported settings version" }
        val defaults = encode(AppSettings())
        for (key in o.keys()) {
            if (!defaults.has(key)) continue
            val expected = defaults.get(key)
            val actual = o.get(key)
            val valid=when(expected) {
                is Int -> actual is Number && runCatching {java.math.BigDecimal(actual.toString()).intValueExact()}.isSuccess
                is Long -> actual is Number && runCatching {java.math.BigDecimal(actual.toString()).longValueExact()}.isSuccess
                else -> expected.javaClass.isInstance(actual)
            }
            require(valid) { "Invalid setting type or integer range: $key" }
        }
        val c = o.optJSONObject("config") ?: JSONObject()
        val configDefaults=defaults.getJSONObject("config")
        for(key in c.keys()) {
            require(configDefaults.has(key)) { "Unknown serial setting: $key" }
            val expected=configDefaults.get(key); val actual=c.get(key)
            require((expected is Int && actual is Int) || (expected is String && actual is String)) { "Invalid serial setting type: $key" }
        }
        val cfg = SerialConfig(c.optInt("baudRate",SerialConfig().baudRate),c.optInt("startBits",1),c.optInt("dataBits",8),
            Parity.valueOf(c.optString("parity","NONE")),StopBits.valueOf(c.optString("stopBits","ONE")),FlowControl.valueOf(c.optString("flowControl","NONE")))
        cfg.validate()
        fun b(k: String) = o.optBoolean(k, defaults.getBoolean(k))
        fun i(k: String) = o.optInt(k, defaults.getInt(k))
        fun l(k: String) = o.optLong(k, defaults.getLong(k))
        fun t(k: String) = o.optString(k, defaults.getString(k))
        require(java.nio.charset.Charset.isSupported(t("charset"))) { "Unsupported charset" }
        require(t("displayMode") in listOf("TEXT","HEX","SIMPLE","VT100")) { "Invalid display mode" }
        require(t("ending") in listOf("AUTO","CRLF","CR","LF","STX_ETX","NUL","NONE")) { "Invalid ending" }
        require(t("receiveEnding") in listOf("CRLF","CR","LF","STX_ETX","NUL","NONE")) { "Invalid receive ending" }
        require(i("bufferBytes") in listOf(0,2048,10240,51200,204800)) { "Invalid display buffer" }
        require(i("fontSize") in 6..30 && i("macroRows") in 0..5) { "Invalid layout setting" }
        require(i("lineDelayMs") in 0..9999 && i("charDelayMs") in 0..9999) { "Invalid delay" }
        require(i("repeatCount") >= 0 && l("repeatIntervalMs") >= 10) { "Invalid repeat setting" }
        require(i("terminalColumns") in 20..240 && i("terminalRows") in 5..120 && l("refreshMs") in 16..1000) { "Invalid screen size or refresh interval" }
        require(t("language") in listOf("zh","en")) { "Invalid language" }
        return AppSettings(cfg,t("charset"),b("sendHex"),t("displayMode"),t("ending"),t("receiveEnding"),b("echo"),
            b("clearAfterSend"),b("timestamps"),b("datePrefix"),b("showStatus"),b("controlCharacters"),i("bufferBytes"),i("fontSize"),
            b("monospace"),i("macroRows"),i("lineDelayMs"),i("charDelayMs"),b("delayByCharacter"),i("repeatCount"),l("repeatIntervalMs"),
            b("keepScreenOn"),b("autoReconnect"),b("logEnabled"),b("logHex"),t("directoryUri"),t("language"),t("contact"),b("hintsSeen"),
            i("terminalColumns"),i("terminalRows"),l("refreshMs"),b("countInKiB"),b("clearResetsCounters"),b("showControlLines"),b("enterToSend"))
    }
}

class SettingsRepository(context: Context,private val store: DataStore<Preferences> = context.preferences) {
    private val key = stringPreferencesKey("json_v1")
    val settings: Flow<AppSettings> = store.data.map { p -> p[key]?.let { SettingsCodec.decode(JSONObject(it)) } ?: AppSettings() }
    suspend fun save(s: AppSettings) { store.edit { it[key] = SettingsCodec.encode(s).toString() } }
}

@Entity(tableName = "macros")
data class MacroEntity(@PrimaryKey val id: String = SerialPresets.newId(), val name: String, val content: String,
    val hex: Boolean = false, val insertOnly: Boolean = false, val row: Int = 0, val delayMs: Int = 0)
@Entity(tableName = "history")
data class HistoryEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val content: String, val hex: Boolean, val charset: String, val createdAt: Long = System.currentTimeMillis())
@Entity(tableName = "device_mappings")
data class DeviceMappingEntity(@PrimaryKey val key: String, val vendorId: Int, val productId: Int, val driver: String)
@Entity(tableName = "device_memory")
data class DeviceMemoryEntity(@PrimaryKey val identity: String, val vendorId: Int, val productId: Int, val serial: String?, val port: Int, val driver: String, val wasConnected: Boolean = true,
    val listPosition: Int = 0, val lastUsedAt: Long = System.currentTimeMillis())

@Dao
interface SerialDao {
    @Query("SELECT * FROM macros ORDER BY row, name") fun macros(): Flow<List<MacroEntity>>
    @Query("SELECT * FROM macros ORDER BY row, name") suspend fun allMacros(): List<MacroEntity>
    @Upsert suspend fun saveMacro(macro: MacroEntity)
    @Query("DELETE FROM macros WHERE id = :id") suspend fun deleteMacro(id: String)
    @Query("DELETE FROM macros") suspend fun clearMacros()
    @Query("SELECT * FROM history ORDER BY createdAt DESC, id DESC LIMIT 50") fun history(): Flow<List<HistoryEntity>>
    @Insert suspend fun insertHistory(history: HistoryEntity)
    @Query("DELETE FROM history WHERE id NOT IN (SELECT id FROM history ORDER BY createdAt DESC, id DESC LIMIT 50)") suspend fun trimHistory()
    @Transaction suspend fun addHistory(h: HistoryEntity) { insertHistory(h); trimHistory() }
    @Query("SELECT * FROM device_mappings") suspend fun mappings(): List<DeviceMappingEntity>
    @Query("SELECT * FROM device_mappings") fun observeMappings(): Flow<List<DeviceMappingEntity>>
    @Upsert suspend fun saveMapping(mapping: DeviceMappingEntity)
    @Query("DELETE FROM device_mappings") suspend fun clearMappings()
    @Upsert suspend fun remember(memory: DeviceMemoryEntity)
    @Query("SELECT * FROM device_memory ORDER BY lastUsedAt DESC") suspend fun memories(): List<DeviceMemoryEntity>
    @Query("SELECT * FROM device_memory ORDER BY lastUsedAt DESC") fun observeMemories(): Flow<List<DeviceMemoryEntity>>
    @Query("UPDATE device_memory SET wasConnected = 0 WHERE identity = :identity") suspend fun forgetConnection(identity: String)
}

@Database(entities = [MacroEntity::class, HistoryEntity::class, DeviceMappingEntity::class, DeviceMemoryEntity::class], version = 1, exportSchema = false)
abstract class SerialDatabase : RoomDatabase() {
    abstract fun dao(): SerialDao
}

class AppContainer(context: Context) {
    val settings = SettingsRepository(context)
    val database = Room.databaseBuilder(context, SerialDatabase::class.java, "serial.db").build()
    val dao = database.dao()
    val configuration=ConfigurationRepository(database,{settings.settings.first()},{settings.save(it)})
}
