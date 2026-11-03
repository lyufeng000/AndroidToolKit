package com.lyufeng.phoneserialport

import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbConfiguration
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Looper
import com.lyufeng.phoneserialport.core.*
import com.lyufeng.phoneserialport.data.AppSettings
import com.lyufeng.phoneserialport.data.DeviceMemoryEntity
import com.lyufeng.phoneserialport.serial.SerialSession
import com.lyufeng.phoneserialport.serial.SerialService
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowUsbManager
import org.robolectric.util.ReflectionHelpers
import java.util.Collections

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[37], application=SerialApplication::class, shadows=[ConnectionExitTest.PermissionShadow::class])
class ConnectionExitTest {
    private val app get() = RuntimeEnvironment.getApplication() as SerialApplication
    private val manager get() = app.devices
    private val usb get() = app.getSystemService(UsbManager::class.java)
    @Before fun setup() {
        PermissionShadow.requests.clear()
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_USB_HOST,true)
        shadowOf(app).clearStartedServices()
        manager.enterTool()
    }
    @After fun cleanup() {
        ReflectionHelpers.getField<CoroutineScope>(manager,"scope").cancel()
        app.container.database.close()
    }
    private suspend fun await(condition: () -> Boolean) = withTimeout(5000) {
        while(!condition()) { shadowOf(Looper.getMainLooper()).idle(); delay(5) }
    }
    private fun attach(): UsbDevice {
        val device=ReflectionHelpers.newInstance(UsbDevice::class.java)
        ReflectionHelpers.setField(device,"mName","/dev/bus/usb/001/002")
        ReflectionHelpers.setField(device,"mVendorId",0x1234)
        ReflectionHelpers.setField(device,"mProductId",0x5678)
        ReflectionHelpers.setField(device,"mProductName","Simulated device")
        ReflectionHelpers.setField(device,"mVersion","1.0")
        ReflectionHelpers.setField(device,"mConfigurations",emptyArray<UsbConfiguration>())
        shadowOf(usb).addOrUpdateUsbDevice(device,false)
        return device
    }
    private fun reply(callback: PendingIntent,device: UsbDevice) {
        callback.send(app,0,Intent().putExtra(UsbManager.EXTRA_DEVICE,device).putExtra(UsbManager.EXTRA_PERMISSION_GRANTED,true))
        shadowOf(Looper.getMainLooper()).idle()
    }
    @Test fun oldPermissionCannotOpenAfterReturn() = runBlocking {
        val device=attach()
        manager.requestOpen(device.deviceName,0,null,SerialConfig())
        await { PermissionShadow.requests.size == 1 }
        val old=PermissionShadow.requests.single()
        manager.disconnectForHome()
        shadowOf(usb).addOrUpdateUsbDevice(device,true)
        reply(old,device)
        delay(50)
        assertTrue(shadowOf(app).allStartedServices.isEmpty())
    }
    @Test fun oldPermissionCannotConsumeNewRequest() = runBlocking {
        val device=attach()
        manager.requestOpen(device.deviceName,0,null,SerialConfig())
        await { PermissionShadow.requests.size == 1 }
        val old=PermissionShadow.requests.single()
        manager.disconnectForHome()
        manager.enterTool()
        manager.requestOpen(device.deviceName,0,null,SerialConfig())
        await { PermissionShadow.requests.size == 2 }
        val fresh=PermissionShadow.requests.last()
        assertNotEquals(shadowOf(old).savedIntent.data,shadowOf(fresh).savedIntent.data)
        shadowOf(usb).addOrUpdateUsbDevice(device,true)
        reply(old,device)
        assertTrue(shadowOf(app).allStartedServices.isEmpty())
        reply(fresh,device)
        await { shadowOf(app).allStartedServices.isNotEmpty() }
        assertEquals(SerialService.ACTION_OPEN,shadowOf(app).allStartedServices.single().action)
    }
    @Test fun queuedOpenExpiresWhenReturningHome() = runBlocking {
        val lock = ReflectionHelpers.getField<Mutex>(manager,"mutex")
        lock.lock()
        val oldGeneration = manager.currentGeneration()
        val opening = async(start=CoroutineStart.UNDISPATCHED) {
            manager.open("missing-device",0,null,SerialConfig(),oldGeneration)
        }
        val exit = launch(start=CoroutineStart.UNDISPATCHED) { manager.disconnectForHome() }
        lock.unlock()
        opening.await()
        exit.join()
        assertTrue(manager.sessions.value.isEmpty())
        assertNull(manager.connectionPhases.value["missing-device"])
    }
    @Test fun cancelledExitStillFinishesBeforeReentry() = runBlocking {
        val lock = ReflectionHelpers.getField<Mutex>(manager,"mutex")
        lock.lock()
        val exit = launch(start=CoroutineStart.UNDISPATCHED) { manager.disconnectForHome() }
        exit.cancel()
        manager.enterTool()
        val generation = manager.currentGeneration()
        assertFalse(manager.isCurrentGeneration(generation))
        lock.unlock()
        exit.join()
        assertTrue(manager.isCurrentGeneration(generation))
    }
    @Test fun staleAutoReconnectCannotStartAfterReentry() = runBlocking {
        val device=attach()
        manager.appVisible=true
        manager.scan()
        await { manager.devices.value.any { it.key == device.deviceName } }
        val oldGeneration=manager.currentGeneration()
        manager.disconnectForHome()
        manager.enterTool()
        // Resume an old scan after the user has left and re-entered the tool.
        app.container.dao.remember(DeviceMemoryEntity("stale-memory",device.vendorId,device.productId,null,0,"Ch34xSerialDriver"))
        val restore=manager.javaClass.getDeclaredMethod("restoreAttached",Long::class.javaPrimitiveType,Continuation::class.java)
        restore.isAccessible=true
        suspendCoroutineUninterceptedOrReturn<Unit> { continuation ->
            restore.invoke(manager,oldGeneration,continuation)
        }
        delay(50)
        assertTrue(PermissionShadow.requests.isEmpty())
        assertTrue(shadowOf(app).allStartedServices.isEmpty())
    }
    @Test fun oldServiceDestroyDoesNotCloseNewGenerationSession() = runBlocking {
        val oldDriver=MemoryDriver()
        val newDriver=MemoryDriver()
        val oldSession=SerialSession(app,"old","old-device",0,oldDriver,AppSettings()) {_,_->}
        val newSession=SerialSession(app,"new","new-device",0,newDriver,AppSettings()) {_,_->}
        oldSession.start(SerialConfig())
        newSession.start(SerialConfig())
        try {
            val sessions=ReflectionHelpers.getField<MutableStateFlow<Map<String,SerialSession>>>(manager,"mutableSessions")
            sessions.value=mapOf(oldSession.id to oldSession,newSession.id to newSession)
            val generations=ReflectionHelpers.getField<MutableMap<String,Long>>(manager,"sessionGenerations")
            generations[oldSession.id]=10L
            generations[newSession.id]=11L
            val controller=Robolectric.buildService(SerialService::class.java).create()
            val owned=ReflectionHelpers.getField<MutableSet<Long>>(controller.get(),"ownedGenerations")
            owned.add(10L)
            controller.destroy()
            await { oldSession.state.value.state == PortState.CLOSED }
            assertTrue(oldDriver.closed)
            assertFalse(newDriver.closed)
            assertEquals(PortState.OPEN,newSession.state.value.state)
        } finally {
            oldSession.close(); newSession.close()
            oldSession.store.close(); newSession.store.close()
        }
    }
    private class MemoryDriver : SerialDriverAdapter {
        override val driverName="MemoryDriver"
        override val capabilities=SerialCapabilities()
        @Volatile var closed=false
        override fun apply(config: SerialConfig)=BaudRateReport(config.baudRate)
        override fun read(buffer: ByteArray): Int { Thread.sleep(10); return 0 }
        override fun write(bytes: ByteArray,timeoutMs: Int)=WriteResult(bytes.size)
        override fun control(name: String,enabled: Boolean) {}
        override fun readControlLines(): Map<String,Boolean> = emptyMap()
        override fun close() { closed=true }
    }
    @Implements(UsbManager::class)
    class PermissionShadow : ShadowUsbManager() {
        companion object { val requests=Collections.synchronizedList(mutableListOf<PendingIntent>()) }
        @Implementation protected fun requestPermission(device: UsbDevice, callback: PendingIntent) { requests.add(callback) }
    }
}
