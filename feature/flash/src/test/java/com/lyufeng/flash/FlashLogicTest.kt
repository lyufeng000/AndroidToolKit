package com.lyufeng.flash

import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class FlashLogicTest {
    @get:Rule val temp = TemporaryFolder(File("D:/Data/.temp"))
    private fun zip(vararg entries: Pair<String, String>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { out -> entries.forEach { (name, content) ->
            out.putNextEntry(ZipEntry(name)); out.write(content.toByteArray()); out.closeEntry()
        } }
        return bytes.toByteArray()
    }
    @Test fun archiveImportsNestedSourcesWithoutEscapingRoot() {
        val root = temp.newFolder()
        ArchiveImport.unzip(ByteArrayInputStream(zip("board/main.c" to "int main(){}", "board/build/app.bin" to "firmware")), root)
        assertEquals("int main(){}", File(root, "board/main.c").readText())
        assertEquals(2, FirmwareFiles.scan(root).size)
        for (bad in listOf("../outside.c", "/outside.c", "C:/outside.c", "a/../../outside.c", "a\\outside.c", "a/evil{.c")) {
            assertThrows(IllegalArgumentException::class.java) { ArchiveImport.unzip(ByteArrayInputStream(zip(bad to "bad")), root) }
        }
    }
    @Test fun archiveRejectsCaseCollisions() {
        assertThrows(IllegalArgumentException::class.java) {
            ArchiveImport.unzip(ByteArrayInputStream(zip("Main.c" to "a", "main.c" to "b")), temp.newFolder())
        }
    }
    @Test fun manifestRejectsExternalDependenciesAndCompilerPlugins() {
        val root = temp.newFolder()
        File(root, "main.c").writeText("int main(){}")
        File(root, "mem.ld").writeText("ENTRY(main)")
        val file = File(root, "toolkit-build.json")
        fun config(source: String, flag: String) = """{"version":1,"cpu":"cortex-m3","sources":["$source"],"linkerScript":"mem.ld","flags":["$flag"]}"""
        file.writeText(config("main.c", "-Os"))
        assertEquals("cortex-m3", SourceProjects.manifest(file, root).cpu)
        file.writeText(config("../main.c", "-Os"))
        assertThrows(IllegalArgumentException::class.java) { SourceProjects.manifest(file, root) }
        file.writeText(config("main.c", "-fplugin=bad.so"))
        assertThrows(IllegalArgumentException::class.java) { SourceProjects.manifest(file, root) }
    }
    @Test fun binUsesAddressButHexKeepsEmbeddedAddressesAndAlwaysVerifies() {
        val root = temp.newFolder()
        File(root, "target").mkdirs(); File(root, "target/stm32f1x.cfg").writeText("# target")
        val bin = File(root, "firmware.bin").apply { writeBytes(byteArrayOf(1,2,3)) }
        val hex = File(root, "firmware.hex").apply { writeText(":00000001FF") }
        val probe = Probe("device", "ST-Link", ProbeKind.ST_LINK, 0x3748)
        val target = TargetProfile("stm32f1x", "STM32F1", setOf(Wire.SWD, Wire.JTAG))
        val options = FlashOptions(probe, Wire.SWD, target, 1000, "0x08004000", false, true)
        val binScript = FlashCommand.script(options, bin, root)
        assertTrue(binScript.contains("dapdirect_swd"))
        assertTrue(binScript.contains("0x8004000 verify reset exit"))
        val hexScript = FlashCommand.script(options, hex, root)
        assertFalse(hexScript.contains("0x8004000"))
        assertTrue(hexScript.contains("verify reset exit"))
        assertThrows(IllegalArgumentException::class.java) { FlashCommand.script(options.copy(address = "0x0;shutdown"), bin, root) }
    }
    @Test fun incompatibleProbeAndInterfaceAreRejectedBeforeExecution() {
        val probe = Probe("device", "ST-Link", ProbeKind.ST_LINK, 0x3748)
        val target = TargetProfile("nrf52", "nRF52", setOf(Wire.SWD))
        val options = FlashOptions(probe, Wire.SWD, target, 1000, "0x00000000", false, true)
        assertThrows(IllegalArgumentException::class.java) { FlashValidation.check(options, null) }
        assertThrows(IllegalArgumentException::class.java) { FlashValidation.check(options.copy(probe = probe.copy(kind = ProbeKind.J_LINK), wire = Wire.JTAG), null) }
    }
    @Test fun dapLinkUsesSelectedBulkInterfaceForCompiledElf() {
        val root = temp.newFolder()
        File(root, "target").mkdirs()
        File(root, "target/nrf52.cfg").writeText("# target")
        val elf = File(root, "app.elf").apply { writeBytes(byteArrayOf(0x7f, 0x45, 0x4c, 0x46)) }
        val probe = Probe("device", "DAPLink", ProbeKind.DAP_LINK, 0x0204, 0x1209, 3)
        val target = TargetProfile("nrf52", "nRF52", setOf(Wire.SWD))
        val options = FlashOptions(probe, Wire.SWD, target, 1000, "", false, true)
        val script = FlashCommand.script(options, elf, root)
        assertTrue("source [find interface/cmsis-dap.cfg]" in script)
        assertTrue("cmsis-dap backend usb_bulk" in script)
        assertTrue("cmsis-dap vid_pid 4617 516" in script)
        assertTrue("cmsis-dap usb interface 3" in script)
        assertTrue("transport select swd" in script)
        assertTrue("program ${FlashValidation.tclPath(elf)} verify reset exit" in script)
    }
}
