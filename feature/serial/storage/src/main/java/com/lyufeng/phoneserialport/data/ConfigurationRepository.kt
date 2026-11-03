package com.lyufeng.phoneserialport.data

import androidx.room.withTransaction
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Coordinates the settings file and the Room records included in configuration archives. */
class ConfigurationRepository(
    private val database: SerialDatabase,
    private val readSettings: suspend ()->AppSettings,
    private val writeSettings: suspend (AppSettings)->Unit,
) {
    private val mutex=Mutex()
    private val dao get()=database.dao()
    suspend fun saveSettings(value: AppSettings) = mutex.withLock {
        SettingsCodec.decode(SettingsCodec.encode(value))
        writeSettings(value)
    }
    suspend fun editRecords(edit: suspend (SerialDao)->Unit) = mutex.withLock {database.withTransaction {edit(dao)}}
    suspend fun snapshot(): ImportedConfiguration = mutex.withLock {
        val settings=readSettings()
        database.withTransaction {ImportedConfiguration(settings,dao.allMacros(),dao.mappings())}
    }
    suspend fun exportArchive(): ByteArray {
        val data=ConfigurationArchive.encode(snapshot()).toString(2).toByteArray(Charsets.UTF_8)
        require(data.size <= ConfigurationArchive.MAX_BYTES) {"Configuration exceeds 2 MiB"}
        return data
    }
    suspend fun importArchive(data: ByteArray,drivers: Collection<String>) {
        val imported=ConfigurationArchive.decode(data,drivers)
        mutex.withLock {
            val old=database.withTransaction {dao.allMacros() to dao.mappings()}
            withContext(NonCancellable) {
                replaceRecords(imported.macros,imported.mappings)
                try {writeSettings(imported.settings)} catch(writeFailure: Exception) {
                    try {replaceRecords(old.first,old.second)} catch(rollbackFailure: Exception) {
                        throw IllegalStateException("Configuration write failed and rollback failed. Configuration state is unknown.",writeFailure)
                            .also {it.addSuppressed(rollbackFailure)}
                    }
                    throw writeFailure
                }
            }
        }
    }
    private suspend fun replaceRecords(macros: List<MacroEntity>,mappings: List<DeviceMappingEntity>) = database.withTransaction {
        dao.clearMacros();dao.clearMappings()
        macros.forEach {dao.saveMacro(it)};mappings.forEach {dao.saveMapping(it)}
    }
}
