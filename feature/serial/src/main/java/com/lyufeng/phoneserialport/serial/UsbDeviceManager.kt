package com.lyufeng.phoneserialport.serial

import android.app.PendingIntent
import android.content.*
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.hoho.android.usbserial.driver.*
import com.lyufeng.phoneserialport.SerialApplication
import com.lyufeng.phoneserialport.core.*
import com.lyufeng.phoneserialport.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class UsbDeviceEntry(val key: String, val vendorId: Int, val productId: Int, val product: String,
    val driver: String?, val portCount: Int, val permission: Boolean, val serial: String?, val officialPortCount: Int? = null)
enum class UsbConnectionPhase { DISCOVERED, PERMISSION_PENDING, OPENING, CONNECTED, DISCONNECTED, ERROR }
private data class OpenRequest(val key: String, val port: Int, val driver: String?, val config: SerialConfig, val generation: Long, val token: String = java.util.UUID.randomUUID().toString())

class UsbDeviceManager(private val context: Context, private val storage: AppContainer) {
    private val usb=context.getSystemService(UsbManager::class.java)
    private val wch=WchDeviceHub(context.applicationContext as android.app.Application)
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val mutex=Mutex()
    private val mutableDevices=MutableStateFlow<List<UsbDeviceEntry>>(emptyList())
    val devices: StateFlow<List<UsbDeviceEntry>> = mutableDevices
    private val sessionGenerations = java.util.concurrent.ConcurrentHashMap<String, Long>()
    fun sessionsOwnedBy(generations: Set<Long>): List<SerialSession> = sessions.value.values.filter { sessionGenerations[it.id] in generations }
    private val mutableSessions=MutableStateFlow<Map<String,SerialSession>>(emptyMap())
    val sessions: StateFlow<Map<String,SerialSession>> = mutableSessions
    val message=MutableStateFlow<String?>(null)
    val connectionPhases=MutableStateFlow<Map<String,UsbConnectionPhase>>(emptyMap())
    private fun phase(key: String, value: UsbConnectionPhase) {connectionPhases.update {it+(key to value)}}
    fun reportConnectionFailure(key: String?, detail: String) {if(key != null) phase(key,UsbConnectionPhase.ERROR);message.value=detail}
    private val gate = Any()
    @Volatile private var generation = 0L
    @Volatile private var toolEnabled = false
    private var leavingTool = 0
    private var reenterAfterLeave = false
    private val pending=mutableMapOf<String,OpenRequest>()
    private val openingPorts=java.util.concurrent.ConcurrentHashMap<Pair<String,Int>,Long>()
    fun enterTool() = synchronized(gate) {
        if (leavingTool > 0) reenterAfterLeave = true else toolEnabled = true
    }
    fun isCurrentGeneration(value: Long): Boolean = toolEnabled && generation == value
    fun currentGeneration(): Long = generation
    private val deniedAutoDevices=java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    fun completeServiceOpen(key: String,port: Int, value: Long) {openingPorts.remove(key to port, value)}
    fun serviceStopped() {serviceRunning=false}
    private var settings=AppSettings()
    @Volatile var serviceRunning=false
    @Volatile var appVisible=false
    val driverNames=listOf("Ch34xSerialDriver","FtdiSerialDriver","Cp21xxSerialDriver","ProlificSerialDriver","CdcAcmSerialDriver","GsmModemSerialDriver","ChromeCcdSerialDriver",WchDeviceHub.DRIVER)
    init {
        ContextCompat.registerReceiver(context,object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                when(intent.action) {
                    UsbManager.ACTION_USB_DEVICE_ATTACHED -> scan(true)
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> { usbDevice(intent)?.let { detached(it.deviceName) }; scan() }
                }
            }
        },IntentFilter().apply { addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED); addAction(UsbManager.ACTION_USB_DEVICE_DETACHED) },ContextCompat.RECEIVER_NOT_EXPORTED)
        scope.launch { storage.settings.settings.catch { message.value="Settings read failed: ${it.message}" }.collect { value -> settings=value; sessions.value.values.forEach { it.updateSettings(value) } } }
        scan()
    }
    private fun probe(device: UsbDevice, mapping: List<DeviceMappingEntity>, explicit: String? = null): UsbSerialDriver? {
        val chosen=explicit ?: mapping.firstOrNull { it.vendorId == device.vendorId && it.productId == device.productId }?.driver
        if(chosen == WchDeviceHub.DRIVER) return null
        if(chosen != null) {
            require(chosen in driverNames) { "Unknown driver" }
            @Suppress("UNCHECKED_CAST") val klass=Class.forName("com.hoho.android.usbserial.driver.$chosen") as Class<out UsbSerialDriver>
            return UsbSerialProber(ProbeTable().addProduct(device.vendorId,device.productId,klass)).probeDevice(device)
        }
        return UsbSerialProber.getDefaultProber().probeDevice(device)
    }
    fun scan(reconnect: Boolean = false) {
        val scanGeneration = generation
        scope.launch {
            try {
                if(!context.packageManager.hasSystemFeature("android.hardware.usb.host")) {
                    mutableDevices.value=emptyList();message.value="USB Host is not available";return@launch
                }
                val mappings=storage.dao.mappings()
                val officialDevices=runCatching {wch.supportedDevices()}.onFailure {message.value="WCH SDK discovery failed: ${it.message}"}.getOrDefault(emptySet())
                mutableDevices.value=usb.deviceList.values.map { d ->
                    val driver=probe(d,mappings); val allowed=usb.hasPermission(d)
                    val mapped=mappings.firstOrNull {it.vendorId == d.vendorId && it.productId == d.productId}?.driver
                    val useOfficial=mapped == WchDeviceHub.DRIVER || (driver == null && d.deviceName in officialDevices)
                    UsbDeviceEntry(d.deviceName,d.vendorId,d.productId,runCatching { d.productName }.getOrNull() ?: "USB",if(useOfficial) WchDeviceHub.DRIVER else driver?.javaClass?.simpleName,
                        if(useOfficial) wch.portCount(d) ?: 1 else driver?.ports?.size ?: 0,allowed,if(allowed) runCatching { d.serialNumber }.getOrNull() else null,wch.portCount(d))
                }.sortedBy { it.key }
                if(reconnect && settings.autoReconnect && isCurrentGeneration(scanGeneration)) restoreAttached(scanGeneration)
            } catch(e: Exception) { message.value="USB scan failed: ${e.message}" }
        }
    }
    private suspend fun restoreAttached(scanGeneration: Long) {
        for(memory in storage.dao.memories().filter { it.wasConnected }) {
            if (!isCurrentGeneration(scanGeneration)) return
            val matching=devices.value.filter { it.vendorId == memory.vendorId && it.productId == memory.productId && (memory.serial == null || !it.permission || it.serial == memory.serial) }
            if(matching.size != 1) { if(matching.size > 1) message.value="Multiple matching devices. Select a device before reconnecting."; continue }
            val d=matching.single()
            if(d.key in deniedAutoDevices || openingPorts.containsKey(d.key to memory.port) || synchronized(pending) {pending.containsKey(d.key)}) continue
            if(sessions.value.values.any { it.deviceKey == d.key && it.portIndex == memory.port && it.state.value.state == PortState.OPEN }) continue
            if(!d.permission) {
                if(appVisible) requestOpenForGeneration(d.key,memory.port,memory.driver,settings.config,scanGeneration)
                else message.value="Reconnect needs USB permission. Open the app and connect."
                continue
            }
            if(!serviceRunning) {
                if(appVisible) launchService(OpenRequest(d.key,memory.port,memory.driver,settings.config,scanGeneration))
                else message.value="Device found. Open the app to resume the foreground connection."
                continue
            }
            try {launchService(OpenRequest(d.key,memory.port,memory.driver,settings.config,scanGeneration))}
            catch(e: Exception) {reportConnectionFailure(d.key,"Reconnect requires returning to the app: ${e.message}")}
        }
    }
    fun requestOpen(key: String, port: Int, driver: String?, config: SerialConfig) {
        requestOpenForGeneration(key, port, driver, config, generation)
    }
    private fun requestOpenForGeneration(key: String, port: Int, driver: String?, config: SerialConfig, requestGeneration: Long) {
        synchronized(gate) {
            if (!isCurrentGeneration(requestGeneration)) return
            phase(key, UsbConnectionPhase.OPENING)
        }
        scope.launch {
            try {
                config.validate()
                synchronized(gate) {
                    if (!isCurrentGeneration(requestGeneration)) return@launch
                    deniedAutoDevices.remove(key)
                    val device=usb.deviceList[key] ?: error("Device was unplugged")
                    val request=OpenRequest(key,port,driver,config,requestGeneration)
                    if(!usb.hasPermission(device)) {
                        phase(key,UsbConnectionPhase.PERMISSION_PENDING)
                        synchronized(pending) { pending[key]=request }
                        val intent=Intent(context,UsbPermissionReceiver::class.java).setAction(ACTION_PERMISSION)
                            .setData(android.net.Uri.parse("serial-permission://request/${request.token}"))
                        val flags=PendingIntent.FLAG_UPDATE_CURRENT or if(Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
                        usb.requestPermission(device,PendingIntent.getBroadcast(context,0,intent,flags))
                    } else launchService(request)
                }
            } catch(e: Exception) { if(isCurrentGeneration(requestGeneration)) reportConnectionFailure(key,"Connection failed: ${e.message}") }
        }
    }
    fun permissionResult(key: String?, token: String?) {
        if(key == null || token == null) return
        synchronized(gate) {
            val request=synchronized(pending) {
                val found=pending[key]
                if(found?.token == token) pending.remove(key) else null
            } ?: return
            if (!isCurrentGeneration(request.generation)) return
            val actual=usb.deviceList[key]
            if(actual == null) {phase(key,UsbConnectionPhase.DISCONNECTED);message.value="Device removed during USB permission request"}
            else if(!usb.hasPermission(actual)) {deniedAutoDevices.add(key);reportConnectionFailure(key,"USB permission was denied")}
            else try { launchService(request) } catch(e: Exception) { reportConnectionFailure(key,"Open the app to connect: ${e.message}") }
            scan(actual != null && usb.hasPermission(actual))
        }
    }
    private fun launchService(request: OpenRequest) = synchronized(gate) {
        if(!isCurrentGeneration(request.generation)) return@synchronized
        if(openingPorts.putIfAbsent(request.key to request.port, request.generation) != null) return@synchronized
        phase(request.key,UsbConnectionPhase.OPENING)
        val intent=Intent(context,SerialService::class.java).setAction(SerialService.ACTION_OPEN)
            .putExtra("device",request.key).putExtra("port",request.port).putExtra("driver",request.driver)
            .putExtra("generation",request.generation)
            .putExtra("config",SettingsCodec.encode(settings.copy(config=request.config)).toString())
        try {
            if(serviceRunning) context.startService(intent) else ContextCompat.startForegroundService(context,intent)
        } catch(e: Exception) {openingPorts.remove(request.key to request.port, request.generation);throw e}
    }
    suspend fun open(key: String, portIndex: Int, explicitDriver: String?, config: SerialConfig, requestGeneration: Long = generation) = mutex.withLock {
        if (!isCurrentGeneration(requestGeneration)) return@withLock
        var adapter: SerialDriverAdapter?=null
        var opening: SerialSession?=null
        var published=false
        try {
            phase(key,UsbConnectionPhase.OPENING)
            val device=usb.deviceList[key] ?: error("Device is no longer attached")
            check(usb.hasPermission(device)) { "USB permission is missing" }
            val mappings=storage.dao.mappings()
            val driver=probe(device,mappings,explicitDriver)
            val selected=explicitDriver ?: mappings.firstOrNull {it.vendorId == device.vendorId && it.productId == device.productId}?.driver
            val useOfficial=selected == WchDeviceHub.DRIVER || (selected == null && driver == null && key in wch.supportedDevices())
            val driverName=if(useOfficial) WchDeviceHub.DRIVER else driver?.javaClass?.simpleName ?: error("No driver. Add a manual mapping.")
            val active=sessions.value.values.filter { it.deviceKey == key && it.state.value.state in listOf(PortState.OPEN,PortState.OPENING,PortState.CLOSING) }
            check(active.none { it.portIndex == portIndex }) { "Port is already open" }
            check(active.all { it.adapter.driverName == driverName }) { "Another driver already owns this USB device" }
            adapter=if(useOfficial) {
                if(selected == WchDeviceHub.DRIVER && key !in wch.supportedDevices()) cn.wch.uartlib.WCHUARTManager.addNewHardware(device.vendorId,device.productId)
                wch.open(device,portIndex)
            } else {
                val port=requireNotNull(driver).ports.getOrNull(portIndex) ?: error("Invalid port index")
                // Upstream close() closes this handle. Never share it between ports.
                val connection=usb.openDevice(device) ?: error("USB open failed. Device may be busy.")
                port.setReadQueue(8,4096)
                try {port.open(connection)} catch(e: Exception) {connection.close();throw e}
                val baudControl=if(driver.javaClass.simpleName == "Ch34xSerialDriver") Ch34xBaudControl {request,value,index,input ->
                    connection.controlTransfer(if(input == null) 0x40 else 0xc0,request,value,index,input,input?.size ?: 0,2000)
                } else null
                UsbDriverAdapter(port,baudControl)
            }
            if (!isCurrentGeneration(requestGeneration)) throw CancellationException("Connection request expired")
            val id=SerialPresets.newId()
            val session=SerialSession(context,id,key,portIndex,adapter,settings) { failed,error -> scope.launch {
                failed.close(true,error.message);reportConnectionFailure(failed.deviceKey,"Serial I/O failed: ${error.message}")
            } }
            opening=session
            try {session.start(config)} catch(e: Exception) {session.close(true,e.message);session.store.close();throw e}
            currentCoroutineContext().ensureActive()
            val obsolete=sessions.value.values.filter { it.deviceKey == key && it.portIndex == portIndex && it.state.value.state !in listOf(PortState.OPEN,PortState.OPENING) }
            obsolete.forEach { runCatching {it.store.close()} }
            synchronized(gate) {
                if (!isCurrentGeneration(requestGeneration)) throw CancellationException("Connection request expired")
                obsolete.forEach { sessionGenerations.remove(it.id) }
                sessionGenerations[id] = requestGeneration
                mutableSessions.update { old -> old.filterKeys { oldId -> oldId !in obsolete.map { it.id } } + (id to session) }
                published=true
            }
            val serial=runCatching { device.serialNumber }.getOrNull()
            val memoryFailure=try {
                storage.dao.remember(DeviceMemoryEntity(identity(device,serial,portIndex),device.vendorId,device.productId,serial,portIndex,driverName,listPosition=devices.value.indexOfFirst {it.key == key}.coerceAtLeast(0)));null
            } catch(e: CancellationException) {throw e} catch(e: Exception) {e}
            currentCoroutineContext().ensureActive()
            phase(key,UsbConnectionPhase.CONNECTED)
            message.value=memoryFailure?.let {"Connected, but device memory could not be saved: ${it.message}"}
            scan()
        } catch(e: Exception) {
            withContext(NonCancellable) {
                if(opening != null) {opening.close(true,e.message);if(!published) runCatching {opening.store.close()}}
                else runCatching {adapter?.close()}
            }
            if(isCurrentGeneration(requestGeneration)) reportConnectionFailure(key,"Open failed: ${e.message}")
            if(e is CancellationException) throw e
        }
    }
    private fun identity(d: UsbDevice, serial: String?, port: Int) = "${d.vendorId}:${d.productId}:${serial ?: "unknown"}:$port"
    fun closePort(id: String, rememberDisconnected: Boolean = true) {
        scope.launch { mutex.withLock {
            val session=sessions.value[id] ?: return@withLock
            session.close()
            phase(session.deviceKey,if(sessions.value.values.any {it.deviceKey == session.deviceKey && it.state.value.state == PortState.OPEN}) UsbConnectionPhase.CONNECTED else UsbConnectionPhase.DISCOVERED)
            if(rememberDisconnected) usb.deviceList[session.deviceKey]?.let { d -> storage.dao.forgetConnection(identity(d,runCatching { d.serialNumber }.getOrNull(),session.portIndex)) }
        } }
    }
    fun disconnectDevice(key: String) { sessions.value.values.filter { it.deviceKey == key }.forEach { closePort(it.id) } }
    private fun detached(key: String) {
        phase(key,UsbConnectionPhase.DISCONNECTED)
        deniedAutoDevices.remove(key)
        synchronized(pending) { pending.remove(key) }
        scope.launch { mutex.withLock { sessions.value.values.filter { it.deviceKey == key }.forEach { it.close(true,"USB detached") } } }
    }
    suspend fun disconnectForHome() {
        synchronized(gate) {
            toolEnabled=false
            leavingTool++
            reenterAfterLeave=false
            generation++
            synchronized(pending) { pending.clear() }
            openingPorts.clear()
        }
        // Finish closing handles even if a configuration change cancels the UI coroutine.
        withContext(NonCancellable) {
            try {
                mutex.withLock {
                    sessions.value.values.forEach { it.close() }
                    connectionPhases.update { all -> all.mapValues { (_,value) -> if(value == UsbConnectionPhase.DISCONNECTED) value else UsbConnectionPhase.DISCOVERED } }
                    try { storage.dao.memories().forEach { storage.dao.forgetConnection(it.identity) } }
                    catch(e: Exception) { message.value="Could not clear connection history: ${e.message}" }
                }
            } finally {
                val reentered=synchronized(gate) {
                    leavingTool--
                    if (leavingTool == 0) {
                        toolEnabled=reenterAfterLeave
                        reenterAfterLeave=false
                        toolEnabled
                    } else false
                }
                if(reentered) scan(true)
            }
        }
    }
    suspend fun disconnectAll() = mutex.withLock {
        sessions.value.values.forEach { it.close() }
        connectionPhases.update {all -> all.mapValues {(_,value) -> if(value == UsbConnectionPhase.DISCONNECTED) value else UsbConnectionPhase.DISCOVERED}}
        storage.dao.memories().forEach { storage.dao.forgetConnection(it.identity) }
    }
    companion object { const val ACTION_PERMISSION="com.lyufeng.phoneserialport.USB_PERMISSION" }
}

@Suppress("DEPRECATION")
private fun usbDevice(intent: Intent): UsbDevice? = if(Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE,UsbDevice::class.java) else intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)

class UsbPermissionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if(intent.action == UsbDeviceManager.ACTION_PERMISSION) (context.applicationContext as SerialApplication).devices.permissionResult(usbDevice(intent)?.deviceName,intent.data?.lastPathSegment)
    }
}
