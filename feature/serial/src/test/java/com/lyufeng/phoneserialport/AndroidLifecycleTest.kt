package com.lyufeng.phoneserialport

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.Context
import android.content.pm.PermissionInfo
import android.content.pm.PackageManager
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbConfiguration
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import com.lyufeng.phoneserialport.core.*
import com.lyufeng.phoneserialport.data.AppSettings
import com.lyufeng.phoneserialport.data.DeviceMemoryEntity
import com.lyufeng.phoneserialport.serial.*
import com.lyufeng.phoneserialport.ui.SerialViewModel
import com.lyufeng.phoneserialport.ui.ReturnHomeState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.shadows.ShadowContextImpl
import org.robolectric.shadows.ShadowPowerManager
import org.robolectric.shadows.ShadowUsbManager
import org.robolectric.util.ReflectionHelpers
import java.util.Collections

/** Exercises production Activity, Service and permission receiver without a USB connection. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[24,37],application=SerialApplication::class,shadows=[AndroidLifecycleTest.PermissionUsbShadow::class,AndroidLifecycleTest.DeclaredReceiverPermissionShadow::class])
class AndroidLifecycleTest {
    private val app get()=RuntimeEnvironment.getApplication() as SerialApplication
    private val manager get()=app.devices
    private val usb get()=app.getSystemService(UsbManager::class.java)
    private val activities=mutableListOf<ActivityController<MainActivity>>()
    private val services=mutableListOf<ServiceController<SerialService>>()
    private val sessions=mutableListOf<SerialSession>()

    @Before fun prepare() {
        manager.enterTool()
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(app.packageName)
        val resolve = ResolveInfo().apply { activityInfo = ActivityInfo().apply {
            packageName = app.packageName
            name = MainActivity::class.java.name
            exported = true
        } }
        shadowOf(app.packageManager).addResolveInfoForIntent(launcher, resolve)
        PermissionUsbShadow.requests.clear()
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_USB_HOST,true)
        shadowOf(app).clearStartedServices()
    }
    private suspend fun await(condition: ()->Boolean) = withTimeout(5000) {
        while(!condition()) {shadowOf(Looper.getMainLooper()).idle();delay(5)}
    }
    @After fun cleanup() = runBlocking {
        activities.forEach {runCatching {it.pause().stop().destroy()}}
        services.forEach {runCatching {it.destroy()}}
        sessions.forEach {it.close();it.store.close()}
        ReflectionHelpers.getField<CoroutineScope>(manager,"scope").cancel()
        app.container.database.close()
    }
    private fun attach(permission: Boolean=false): UsbDevice {
        val device=ReflectionHelpers.newInstance(UsbDevice::class.java)
        ReflectionHelpers.setField(device,"mName","/dev/bus/usb/001/002")
        ReflectionHelpers.setField(device,"mVendorId",0x1234)
        ReflectionHelpers.setField(device,"mProductId",0x5678)
        ReflectionHelpers.setField(device,"mProductName","Simulated serial device")
        ReflectionHelpers.setField(device,"mVersion","1.0")
        ReflectionHelpers.setField(device,"mConfigurations",emptyArray<UsbConfiguration>())
        shadowOf(usb).addOrUpdateUsbDevice(device,permission)
        manager.scan()
        return device
    }
    private suspend fun request(device: UsbDevice): PendingIntent {
        manager.requestOpen(device.deviceName,0,null,SerialConfig())
        await {PermissionUsbShadow.requests.isNotEmpty()}
        return PermissionUsbShadow.requests.last()
    }
    private fun result(callback: PendingIntent,device: UsbDevice,claimedGranted: Boolean) {
        callback.send(app,0,Intent().putExtra(UsbManager.EXTRA_DEVICE,device)
            .putExtra(UsbManager.EXTRA_PERMISSION_GRANTED,claimedGranted))
        shadowOf(Looper.getMainLooper()).idle()
    }
    @Test fun deniedPermissionCannotBeOverriddenByTheBroadcastSuccessFlag() = runBlocking {
        val device=attach();val callback=request(device)
        assertEquals(UsbConnectionPhase.PERMISSION_PENDING,manager.connectionPhases.value[device.deviceName])
        assertFalse(shadowOf(callback).isImmutable)
        assertEquals(UsbPermissionReceiver::class.java.name,shadowOf(callback).savedIntent.component!!.className)
        result(callback,device,true)
        await {manager.connectionPhases.value[device.deviceName] == UsbConnectionPhase.ERROR}
        assertTrue(manager.sessions.value.isEmpty())
        assertTrue(shadowOf(app).allStartedServices.isEmpty())
    }
    @Test fun removingDeviceDuringPermissionRequestNeverStartsTheService() = runBlocking {
        val device=attach();val callback=request(device)
        shadowOf(usb).removeUsbDevice(device)
        result(callback,device,true)
        await {manager.connectionPhases.value[device.deviceName] == UsbConnectionPhase.DISCONNECTED}
        assertTrue(manager.sessions.value.isEmpty())
        assertTrue(shadowOf(app).allStartedServices.isEmpty())
    }
    @Test fun actualPermissionStartsOneForegroundOpenAndDuplicateResultIsIgnored() = runBlocking {
        val device=attach();val callback=request(device)
        shadowOf(usb).addOrUpdateUsbDevice(device,true)
        result(callback,device,false)
        await {shadowOf(app).allStartedServices.size == 1}
        val command=shadowOf(app).allStartedServices.single()
        assertEquals(SerialService.ACTION_OPEN,command.action)
        assertEquals(device.deviceName,command.getStringExtra("device"))
        result(callback,device,true)
        assertEquals(1,shadowOf(app).allStartedServices.size)
        assertTrue(manager.sessions.value.isEmpty())
    }
    @Test fun deniedAutomaticReconnectDoesNotImmediatelyAskAgain() = runBlocking {
        val device=attach();manager.appVisible=true
        app.container.settings.save(AppSettings(autoReconnect=true))
        app.container.dao.remember(DeviceMemoryEntity("test-memory",device.vendorId,device.productId,null,0,"Ch34xSerialDriver"))
        await {ReflectionHelpers.getField<AppSettings>(manager,"settings").autoReconnect}
        val callback=request(device)
        result(callback,device,false)
        await {manager.connectionPhases.value[device.deviceName] == UsbConnectionPhase.ERROR}
        manager.scan(true)
        await {manager.devices.value.any {it.key == device.deviceName}}
        delay(50)
        assertEquals(1,PermissionUsbShadow.requests.size)
        assertTrue(shadowOf(app).allStartedServices.isEmpty())
    }
    private suspend fun openSession(): Pair<SerialSession,MemoryDriver> {
        val driver=MemoryDriver()
        val session=SerialSession(app,"test-session","test-device",0,driver,AppSettings()) {_,_ -> }
        session.start(SerialConfig());sessions+=session
        ReflectionHelpers.getField<MutableStateFlow<Map<String,SerialSession>>>(manager,"mutableSessions").value=mapOf(session.id to session)
        return session to driver
    }
    private fun service(): SerialService = Robolectric.buildService(SerialService::class.java).create().also {services+=it}.get()
    private fun openCommand()=Intent(app,SerialService::class.java).setAction(SerialService.ACTION_OPEN)
        .putExtra("device","unavailable-new-device").putExtra("config","{}")

    @Test fun notificationDisconnectClosesSessionsAndForgetsReconnectState() = runBlocking {
        val (session,driver)=openSession()
        app.container.dao.remember(DeviceMemoryEntity("connected-memory",1,2,null,0,"Test"))
        val service=service()
        assertEquals(Service.START_NOT_STICKY,service.onStartCommand(openCommand(),0,1))
        val notification=shadowOf(service).lastForegroundNotification
        assertNotNull(notification)
        if(Build.VERSION.SDK_INT >= 29) assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,service.foregroundServiceType)
        assertTrue(ShadowPowerManager.getLatestWakeLock().isHeld)
        val disconnect=notification!!.actions.single().actionIntent
        assertTrue(shadowOf(disconnect).isImmutable)
        assertEquals(SerialService.ACTION_DISCONNECT,shadowOf(disconnect).savedIntent.action)
        service.onStartCommand(shadowOf(disconnect).savedIntent,0,2)
        await {session.state.value.state == PortState.CLOSED && shadowOf(service).isForegroundStopped}
        assertTrue(driver.closed)
        assertFalse(app.container.dao.memories().single().wasConnected)
        assertTrue(shadowOf(service).isStoppedBySelf)
        services.single().destroy();services.clear()
        assertFalse(ShadowPowerManager.getLatestWakeLock().isHeld)
    }
    @Test fun foregroundStartFailureIsReportedAndDestroyReleasesCpuLock() = runBlocking {
        val service=service()
        shadowOf(service).setThrowInStartForeground(SecurityException("connectedDevice permission missing"))
        service.onStartCommand(openCommand(),0,1)
        assertTrue(manager.message.value!!.contains("Foreground connection failed"))
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertTrue(manager.sessions.value.isEmpty())
        services.single().destroy();services.clear()
        assertFalse(manager.serviceRunning)
        assertFalse(ShadowPowerManager.getLatestWakeLock().isHeld)
    }
    @Test fun restartedServiceDoesNotRestoreAConnectedSessionOrCommands() {
        val service=service()
        assertEquals(Service.START_NOT_STICKY,service.onStartCommand(null,0,1))
        assertTrue(manager.sessions.value.isEmpty())
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertTrue(shadowOf(app).allStartedServices.isEmpty())
    }
    @Test fun returningHomeFinishesAfterActivityRecreationDuringDisconnect() = runBlocking {
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup().also {activities+=it}
        val before=ViewModelProvider(controller.get())[SerialViewModel::class.java]
        val lock=ReflectionHelpers.getField<Mutex>(manager,"mutex")
        lock.lock()
        try {
            before.requestReturnHome()
            assertEquals(ReturnHomeState.CLOSING,before.returnHome.value)
            controller.recreate()
            assertSame(before,ViewModelProvider(controller.get())[SerialViewModel::class.java])
        } finally { lock.unlock() }
        await {controller.get().isFinishing}
        assertEquals(ReturnHomeState.IDLE,before.returnHome.value)
    }
    @Test fun activityRecreationKeepsTheViewModelAndNeverSendsDraftText() = runBlocking {
        val (session,driver)=openSession()
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup().also {activities+=it}
        val before=ViewModelProvider(controller.get())[SerialViewModel::class.java]
        before.input.value="UNSENT DRAFT";before.select(session.id)
        var callbacks=0
        before.afterNotificationPermission={callbacks++}
        controller.recreate()
        val after=ViewModelProvider(controller.get())[SerialViewModel::class.java]
        assertSame(before,after)
        assertEquals("UNSENT DRAFT",after.input.value)
        assertEquals(session.id,after.activeId.value)
        assertEquals(PortState.OPEN,session.state.value.state)
        assertFalse(driver.closed);assertEquals(0,driver.writes)
        after.notificationPermissionFinished();after.notificationPermissionFinished()
        assertEquals(1,callbacks)
    }
    @Implements(UsbManager::class)
    class PermissionUsbShadow : ShadowUsbManager() {
        companion object {val requests=Collections.synchronizedList(mutableListOf<PendingIntent>())}
        @Implementation protected fun requestPermission(device: UsbDevice,callback: PendingIntent) {requests+=callback}
    }
    /** Robolectric does not grant the app's declared signature permission when simulating API 24. */
    @Implements(className="android.app.ContextImpl",isInAndroidSdk=false)
    class DeclaredReceiverPermissionShadow : ShadowContextImpl() {
        @RealObject private lateinit var context: Context
        @Implementation override fun checkPermission(permission: String,pid: Int,uid: Int): Int {
            val own=context.packageName+".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
            if(permission == own && uid == android.os.Process.myUid()) {
                val info=context.packageManager.getPackageInfo(context.packageName,PackageManager.GET_PERMISSIONS)
                val declared=info.requestedPermissions?.contains(own) == true
                val signature=context.packageManager.getPermissionInfo(own,0).protectionLevel == PermissionInfo.PROTECTION_SIGNATURE
                if(declared && signature) return PackageManager.PERMISSION_GRANTED
            }
            return super.checkPermission(permission,pid,uid)
        }
    }
    private class MemoryDriver : SerialDriverAdapter {
        override val driverName="MemoryDriver"
        override val capabilities=SerialCapabilities()
        @Volatile var closed=false
        @Volatile var writes=0
        override fun apply(config: SerialConfig)=BaudRateReport(config.baudRate)
        override fun read(buffer: ByteArray): Int {Thread.sleep(10);return 0}
        override fun write(bytes: ByteArray,timeoutMs: Int): WriteResult {writes++;return WriteResult(bytes.size)}
        override fun control(name: String,enabled: Boolean) {}
        override fun readControlLines(): Map<String,Boolean> = emptyMap()
        override fun close() {closed=true}
    }
}
