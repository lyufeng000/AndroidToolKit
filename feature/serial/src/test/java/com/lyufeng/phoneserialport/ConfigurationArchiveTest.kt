package com.lyufeng.phoneserialport

import com.lyufeng.phoneserialport.data.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ConfigurationArchiveTest {
    private val drivers=listOf("Ch34xSerialDriver","WchOfficial")
    private fun archive()=JSONObject().put("schemaVersion",1)
    private fun macro(id: String="macro-1")=JSONObject().put("id",id).put("name","Status").put("content","AT")
    private fun mapping()=JSONObject().put("vendorId",6790).put("productId",29987).put("driver","WchOfficial")

    @Test fun minimalVersionOneArchiveFillsMissingFieldsWithoutFabricatingConnectionState() {
        val root=archive().put("macros",JSONArray().put(macro())).put("mappings",JSONArray().put(mapping()))
        val imported=ConfigurationArchive.decode(root,drivers)
        assertEquals(115200,imported.settings.config.baudRate)
        assertEquals(0,imported.macros.single().row);assertFalse(imported.macros.single().hex)
        assertEquals(0,imported.macros.single().delayMs);assertEquals("WchOfficial",imported.mappings.single().driver)
    }
    @Test fun malformedCollectionsAreRejectedInsteadOfBecomingEmptyCollections() {
        assertThrows(IllegalArgumentException::class.java) {ConfigurationArchive.decode(archive().put("macros","invalid"),drivers)}
        assertThrows(IllegalArgumentException::class.java) {ConfigurationArchive.decode(archive().put("mappings",JSONObject()),drivers)}
        assertThrows(IllegalArgumentException::class.java) {ConfigurationArchive.decode(archive().put("settings","invalid"),drivers)}
    }
    @Test fun stringBooleansAndFractionalMacroRowsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {ConfigurationArchive.decode(archive().put("macros",JSONArray().put(macro().put("hex","false"))),drivers)}
        assertThrows(IllegalArgumentException::class.java) {ConfigurationArchive.decode(archive().put("macros",JSONArray().put(macro().put("row",1.5))),drivers)}
    }
    @Test fun duplicateRecordsCannotSilentlyOverwriteImportedMacrosOrMappings() {
        assertThrows(IllegalArgumentException::class.java) {ConfigurationArchive.decode(archive().put("macros",JSONArray().put(macro()).put(macro())),drivers)}
        assertThrows(IllegalArgumentException::class.java) {ConfigurationArchive.decode(archive().put("mappings",JSONArray().put(mapping()).put(mapping())),drivers)}
    }
    @Test fun unknownVersionsAndDriversRemainErrors() {
        assertThrows(IllegalArgumentException::class.java) {ConfigurationArchive.decode(archive().put("schemaVersion",2),drivers)}
        assertThrows(IllegalArgumentException::class.java) {ConfigurationArchive.decode(archive().put("schemaVersion","1"),drivers)}
        assertThrows(IllegalArgumentException::class.java) {ConfigurationArchive.decode(archive().put("mappings",JSONArray().put(mapping().put("driver","unknown"))),drivers)}
    }
    @Test fun mappingNumbersCannotBeCoercedFromStringsOrTruncated() {
        assertThrows(IllegalArgumentException::class.java) {ConfigurationArchive.decode(archive().put("mappings",JSONArray().put(mapping().put("vendorId","6790"))),drivers)}
        assertThrows(IllegalArgumentException::class.java) {ConfigurationArchive.decode(archive().put("mappings",JSONArray().put(mapping().put("vendorId",6790.5))),drivers)}
    }
    @Test fun settingsRejectFractionalAndOverflowingIntegersBeforeTheyCanWrap() {
        assertThrows(IllegalArgumentException::class.java) {SettingsCodec.decode(JSONObject().put("macroRows",1.5))}
        assertThrows(IllegalArgumentException::class.java) {SettingsCodec.decode(JSONObject().put("macroRows",4294967296L))}
        assertThrows(IllegalArgumentException::class.java) {SettingsCodec.decode(JSONObject().put("refreshMs",50.5))}
        assertThrows(IllegalArgumentException::class.java) {SettingsCodec.decode(JSONObject().put("repeatIntervalMs",java.math.BigInteger("9223372036854775808")))}
    }
}
