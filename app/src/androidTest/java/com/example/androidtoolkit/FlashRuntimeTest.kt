package com.example.androidtoolkit

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lyufeng.flash.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class FlashRuntimeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun packagedOpenOcdStartsAndContainsProbeDrivers() {
        val process = ToolProcess(context) { android.util.Log.i("FlashRuntimeTest", it) }
        assertTrue(process.run("libopenocd.so", listOf("--version")).contains("0.12.0"))
        val adapters = process.run("libopenocd.so", listOf("-c", "echo [adapter list]; shutdown"))
        assertTrue(adapters.contains("jlink"))
        assertTrue(adapters.contains("st-link"))
        assertTrue(adapters.contains("cmsis-dap"))
        val targets = FlashAssets(context).targets()
        assertTrue(targets.size >= 100)
        val scripts = FlashAssets(context).prepare("openocd")
        assertTrue(targets.all { File(scripts, "target/${it.id}.cfg").isFile })
    }

    @Test fun compilesCortexMSourceToArmElfOnPhone() {
        val root = File(context.cacheDir, "runtime-test-source").apply { mkdirs() }
        val assets = instrumentation.context.assets
        for (name in assets.list("stm32f103").orEmpty()) assets.open("stm32f103/$name").use { input -> File(root, name).outputStream().use { input.copyTo(it) } }
        val plan = SourceProjects.manifest(File(root, "toolkit-build.json"), root)
        val runner = ToolProcess(context) { android.util.Log.i("FlashRuntimeTest", it) }
        val elf = SourceCompiler(context, runner) { android.util.Log.i("FlashRuntimeTest", it) }.compile(plan)
        val header = elf.inputStream().use { it.readNBytes(52) }
        assertArrayEquals(byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()), header.copyOfRange(0,4))
        assertEquals(40, header[18].toInt()) // ELF e_machine = EM_ARM.
        assertEquals(0x08, header[27].toInt()) // Entry point lies in internal flash 0x08000000.
    }

    @Test fun toolErrorsNeverBecomeSuccess() {
        val runner = ToolProcess(context) {}
        assertThrows(IllegalStateException::class.java) { runner.run("libopenocd.so", listOf("-c", "not_a_real_command; shutdown")) }
        runner.cancel()
        assertThrows(java.util.concurrent.CancellationException::class.java) { runner.run("libopenocd.so", listOf("--version")) }
    }

    @Test fun probeRoutesFailCleanlyWithoutAnAuthorizedUsbDescriptor() {
        val scripts = FlashAssets(context).prepare("openocd")
        val root = File(context.cacheDir, "runtime-no-probe").apply { mkdirs() }
        val image = File(root, "image.bin").apply { writeBytes(byteArrayOf(1,2,3,4)) }
        val target = FlashAssets(context).targets().first { it.id == "stm32f1x" }
        for (kind in ProbeKind.entries) {
            val options = FlashOptions(Probe("missing", kind.label, kind, if (kind == ProbeKind.ST_LINK) 0x3748 else 0x0101),
                Wire.SWD, target, 1000, "0x08000000", false, true)
            val config = File(root, "${kind.name}.cfg").apply { writeText(FlashCommand.script(options, image, scripts)) }
            val runner = ToolProcess(context) { android.util.Log.i("FlashRuntimeTest", it) }
            val failure = assertThrows(IllegalStateException::class.java) {
                runner.run("libopenocd.so", listOf("-s", scripts.absolutePath, "-f", config.absolutePath), timeoutSeconds = 10)
            }
            assertFalse(failure.message.orEmpty().contains("invalid command name"))
            assertFalse(failure.message.orEmpty().contains("Can't find"))
            assertFalse(failure.message.orEmpty().contains("error 139"))
        }
    }

    @Test fun canCancelARunningNativeProcess() {
        val started = java.util.concurrent.CountDownLatch(1)
        val stopped = java.util.concurrent.atomic.AtomicBoolean(false)
        val runner = ToolProcess(context) { if (it.contains("WAITING")) started.countDown() }
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val task = executor.submit {
                try { runner.run("libopenocd.so", listOf("-c", "echo WAITING; sleep 10000; shutdown")) }
                catch (expected: java.util.concurrent.CancellationException) { stopped.set(true) }
            }
            assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS))
            runner.cancel()
            task.get(5, java.util.concurrent.TimeUnit.SECONDS)
            assertTrue(stopped.get())
        } finally { runner.cancel(); executor.shutdownNow() }
    }

    @Test fun importsZipAndFirmwareThroughAndroidContentResolver() {
        val folder = File(context.filesDir, "test-import-input").apply { mkdirs() }
        val archive = File(folder, "source-project.zip")
        java.util.zip.ZipOutputStream(archive.outputStream()).use { zip ->
            for (name in instrumentation.context.assets.list("stm32f103").orEmpty()) {
                zip.putNextEntry(java.util.zip.ZipEntry("project/$name"))
                instrumentation.context.assets.open("stm32f103/$name").use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        val archiveUri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", archive)
        val imported = ProjectImporter(context).import(archiveUri, false)
        assertEquals(1, imported.buildPlans.size)
        assertEquals(2, imported.sources.size)
        assertTrue(imported.firmware.isEmpty())
        assertEquals("cortex-m3", imported.buildPlans.single().cpu)
        val bin = File(folder, "app.bin").apply { writeBytes(byteArrayOf(0x12,0x34,0x56,0x78)) }
        val binUri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", bin)
        val direct = ProjectImporter(context).import(binUri, false)
        assertEquals(4L, direct.firmware.single().length())
        assertTrue(direct.sources.isEmpty())
        imported.root.deleteRecursively(); direct.root.deleteRecursively(); folder.deleteRecursively()
    }
}
