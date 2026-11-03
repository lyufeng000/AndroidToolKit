package com.lyufeng.phoneserialport

import android.net.Uri
import android.os.Looper
import androidx.lifecycle.viewModelScope
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.lyufeng.phoneserialport.core.SerialConfig
import com.lyufeng.phoneserialport.data.*
import com.lyufeng.phoneserialport.ui.SerialViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[37],application=SerialApplication::class)
class ConfigurationStorageTest {
    private val app get()=RuntimeEnvironment.getApplication() as SerialApplication
    private val storage get()=app.container
    private val repository get()=storage.configuration
    private val drivers=listOf("Ch34xSerialDriver","WchOfficial")
    private val models=mutableListOf<SerialViewModel>()
    private fun value(name: String,baud: Int)=ImportedConfiguration(AppSettings(config=SerialConfig(baudRate=baud)),
        listOf(MacroEntity(name,name,"AT+$name")),listOf(DeviceMappingEntity("1:2",1,2,"Ch34xSerialDriver")))
    private fun bytes(value: ImportedConfiguration)=ConfigurationArchive.encode(value).toString().toByteArray()
    private suspend fun seed(): ImportedConfiguration = value("original",1152000).also {repository.importArchive(bytes(it),drivers)}
    private fun repositoryWithWriter(write: suspend (AppSettings)->Unit)=ConfigurationRepository(storage.database,{storage.settings.settings.first()},write)
    private fun model()=SerialViewModel(app).also {models+=it}
    private suspend fun await(condition: ()->Boolean) = withTimeout(5000) {
        while(!condition()) {shadowOf(Looper.getMainLooper()).idle();delay(5)}
    }
    @After fun cleanup() {
        models.forEach {it.viewModelScope.cancel()}
        ReflectionHelpers.getField<CoroutineScope>(app.devices,"scope").cancel()
        storage.database.close()
    }
    @Test fun archiveRoundTripUsesRoomAndDataStoreWithoutReplacingHistoryOrDeviceMemory() = runBlocking {
        val original=seed()
        storage.dao.addHistory(HistoryEntity(content="UNREPLAYED",hex=false,charset="UTF-8"))
        storage.dao.remember(DeviceMemoryEntity("memory",1,2,null,0,"Ch34xSerialDriver"))
        val exported=repository.exportArchive()
        repository.importArchive(bytes(value("different",57600)),drivers)
        repository.importArchive(exported,drivers)
        assertEquals(original,repository.snapshot())
        assertEquals(original.settings,storage.settings.settings.first())
        assertEquals("UNREPLAYED",storage.dao.history().first().single().content)
        assertEquals("memory",storage.dao.memories().single().identity)
        assertTrue(app.devices.sessions.value.isEmpty())
    }
    @Test fun settingsWriteFailureRestoresBothRoomCollections() = runBlocking {
        val original=seed()
        val failing=repositoryWithWriter {throw IOException("settings unavailable")}
        assertNotNull(runCatching {failing.importArchive(bytes(value("new",57600)),drivers)}.exceptionOrNull())
        assertEquals(original,repository.snapshot())
    }
    @Test fun actualDataStoreFileFailurePreservesTheOldFileAndRestoresRoomRecords() = runBlocking {
        val storeScope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val file=File(app.filesDir,"datastore/file-failure.preferences_pb")
        val settings=SettingsRepository(app,PreferenceDataStoreFactory.create(scope=storeScope) {file})
        val owned=ConfigurationRepository(storage.database,{settings.settings.first()},{settings.save(it)})
        try {
            val original=value("original",1152000)
            owned.importArchive(bytes(original),drivers)
            assertTrue(file.isFile)
            val saved=file.readBytes()
            val blocked=File(file.parentFile,file.name+".tmp")
            assertTrue(blocked.mkdir())
            File(blocked,"occupied").writeText("block the atomic write scratch path")
            val error=runCatching {owned.importArchive(bytes(value("new",57600)),drivers)}.exceptionOrNull()
            assertNotNull(error)
            assertArrayEquals(saved,file.readBytes())
            assertEquals(original,owned.snapshot())
        } finally {storeScope.coroutineContext[Job]!!.cancelAndJoin()}
    }
    @Test fun failedImportCannotRollBackALaterSuccessfulImport() = runBlocking {
        seed()
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val ordered=repositoryWithWriter {settings ->
            if(settings.config.baudRate == 57600) {entered.complete(Unit);release.await();throw IOException("first write failed")}
            storage.settings.save(settings)
        }
        val first=async(Dispatchers.IO) {runCatching {ordered.importArchive(bytes(value("first",57600)),drivers)}}
        entered.await()
        val second=async(Dispatchers.IO) {ordered.importArchive(bytes(value("second",230400)),drivers)}
        try {withTimeoutOrNull(500) {second.await()}} finally {release.complete(Unit)}
        assertNotNull(first.await().exceptionOrNull());second.await()
        assertEquals(value("second",230400),repository.snapshot())
    }
    @Test fun exportWaitsForTheCompleteImportInsteadOfMixingSettingsAndRecords() = runBlocking {
        seed()
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val ordered=repositoryWithWriter {settings ->entered.complete(Unit);release.await();storage.settings.save(settings)}
        val importing=async(Dispatchers.IO) {ordered.importArchive(bytes(value("new",57600)),drivers)}
        entered.await()
        val exporting=async(Dispatchers.IO) {ordered.exportArchive()}
        val premature=try {withTimeoutOrNull(500) {exporting.await()}} finally {release.complete(Unit)}
        importing.await()
        assertNull(premature)
        assertEquals(value("new",57600),ConfigurationArchive.decode(exporting.await(),drivers))
    }
    @Test fun cancelledImportFinishesTheStartedWriteWithoutLeavingMixedState() = runBlocking {
        seed()
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val ordered=repositoryWithWriter {settings ->entered.complete(Unit);release.await();storage.settings.save(settings)}
        val importing=launch(Dispatchers.IO) {ordered.importArchive(bytes(value("new",57600)),drivers)}
        entered.await();importing.cancel();release.complete(Unit);importing.join()
        assertEquals(value("new",57600),repository.snapshot())
    }
    @Test fun editingAMacroDuringFailedImportIsNotLostByRollback() = runBlocking {
        val original=seed()
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val ordered=repositoryWithWriter {entered.complete(Unit);release.await();throw IOException("settings unavailable")}
        val importing=async(Dispatchers.IO) {runCatching {ordered.importArchive(bytes(value("new",57600)),drivers)}}
        entered.await()
        val edited=MacroEntity("user-edit","User edit","AT+USER")
        val editing=async(Dispatchers.IO) {ordered.editRecords {it.saveMacro(edited)}}
        try {withTimeoutOrNull(500) {editing.await()}} finally {release.complete(Unit)}
        assertNotNull(importing.await().exceptionOrNull());editing.await()
        assertEquals((original.macros+edited).toSet(),repository.snapshot().macros.toSet())
    }
    @Test fun viewModelRejectsInvalidUtf8BeforeChangingPersistentRecords() = runBlocking {
        val original=seed();val vm=model()
        val uri=Uri.parse("content://configuration/bad-encoding")
        val payload=("{\"schemaVersion\":1,\"macros\":[{\"name\":\"bad\",\"content\":\"".toByteArray()
            +byteArrayOf(0xc3.toByte(),0x28)+"\"}]}".toByteArray())
        shadowOf(app.contentResolver).registerInputStream(uri,payload.inputStream())
        vm.importConfig(uri);await {vm.message.value != null}
        assertNotEquals("Configuration imported",vm.message.value)
        assertEquals(original,repository.snapshot())
    }
    @Test fun viewModelImportsUtf8BomAndExportsTheCommittedSnapshot() = runBlocking {
        seed();val vm=model();val target=value("中文宏",57600)
        val input=Uri.parse("content://configuration/input");val output=Uri.parse("content://configuration/output")
        shadowOf(app.contentResolver).registerInputStream(input,(byteArrayOf(0xef.toByte(),0xbb.toByte(),0xbf.toByte())+bytes(target)).inputStream())
        val exported=ByteArrayOutputStream()
        shadowOf(app.contentResolver).registerOutputStream(output,exported)
        vm.importConfig(input);await {vm.message.value == "Configuration imported"}
        vm.message.value=null;vm.exportConfig(output);await {vm.message.value == "Configuration exported"}
        assertEquals(target,ConfigurationArchive.decode(exported.toByteArray(),drivers))
    }
    @Test fun exportStorageFailureIsReportedWithoutChangingConfiguration() = runBlocking {
        val original=seed();val vm=model();val uri=Uri.parse("content://configuration/write-failure")
        var closed=false
        shadowOf(app.contentResolver).registerOutputStream(uri,object : OutputStream() {
            override fun write(value: Int) {throw IOException("document write failed")}
            override fun close() {closed=true}
        })
        vm.exportConfig(uri);await {vm.message.value?.contains("document write failed") == true}
        assertTrue(closed);assertEquals(original,repository.snapshot())
    }
    @Test fun oversizedExportDoesNotOverwriteTheSelectedDocument() = runBlocking {
        seed();val vm=model();val uri=Uri.parse("content://configuration/size-limit")
        repository.editRecords {dao -> repeat(2) {dao.saveMacro(MacroEntity("large-$it","Large $it","A".repeat(ConfigurationArchive.MAX_BYTES/2)))}}
        val output=ByteArrayOutputStream().apply {write("ORIGINAL DOCUMENT".toByteArray())}
        shadowOf(app.contentResolver).registerOutputStream(uri,output)
        vm.exportConfig(uri);await {vm.message.value != null}
        assertTrue(vm.message.value!!.contains("2 MiB"))
        assertEquals("ORIGINAL DOCUMENT",output.toString("UTF-8"))
    }
    @Test fun failedSettingsReadIsNotExportedAsFabricatedDefaults() = runBlocking {
        seed()
        val unreadable=SettingsRepository(app,object : DataStore<Preferences> {
            override val data=flow<Preferences> {throw IOException("settings read failed")}
            override suspend fun updateData(transform: suspend (Preferences)->Preferences): Preferences = throw IOException("settings unavailable")
        })
        val strict=ConfigurationRepository(storage.database,{unreadable.settings.first()},{unreadable.save(it)})
        val error=runCatching {strict.exportArchive()}.exceptionOrNull()
        assertTrue(error is IOException)
        assertEquals("settings read failed",error!!.message)
    }
    @Test fun vofaImportAppendsOnceAndKeepsExistingConfiguration()=runBlocking {
        val original=seed();val vm=model()
        val data="""{"type":"cmds","vnumber":100,"ctx":{"name":"root","is_group":true,"subCmds":[{"name":"SC","cmd_hex":"53 43 0A","hex_on":false}]}}""".toByteArray()
        val uri=Uri.parse("content://configuration/vofa")
        shadowOf(app.contentResolver).registerInputStream(uri,data.inputStream())
        vm.importVofa(uri);await {vm.message.value?.startsWith("已导入 1") == true}
        val first=repository.snapshot()
        assertEquals(original.settings,first.settings);assertEquals(original.mappings,first.mappings)
        assertTrue(first.macros.containsAll(original.macros));assertEquals(2,first.macros.size)
        vm.message.value=null
        shadowOf(app.contentResolver).registerInputStream(uri,data.inputStream())
        vm.importVofa(uri);await {vm.message.value?.startsWith("已导入 0") == true}
        assertEquals(first,repository.snapshot())
    }
    @Test fun invalidVofaImportDoesNotPartiallyModifyMacros()=runBlocking {
        val original=seed();val vm=model()
        val uri=Uri.parse("content://configuration/invalid-vofa")
        val data="""{"type":"cmds","vnumber":100,"ctx":{"name":"root","is_group":true,"subCmds":[{"name":"good","cmd_hex":"AA"},{"name":"bad","cmd_hex":"GG"}]}}""".toByteArray()
        shadowOf(app.contentResolver).registerInputStream(uri,data.inputStream())
        vm.importVofa(uri);await {vm.message.value != null}
        assertEquals(original,repository.snapshot())
    }
}
