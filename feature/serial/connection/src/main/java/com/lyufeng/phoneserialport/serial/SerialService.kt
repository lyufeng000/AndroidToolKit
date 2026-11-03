package com.lyufeng.phoneserialport.serial

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.lyufeng.phoneserialport.SerialApplication
import com.lyufeng.phoneserialport.connection.R
import com.lyufeng.phoneserialport.core.PortState
import com.lyufeng.phoneserialport.data.SettingsCodec
import kotlinx.coroutines.*
import org.json.JSONObject

class SerialService : Service() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val manager get() = (application as SerialApplication).devices
    private var foreground=false
    private var watch: Job?=null
    private val ownedGenerations = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()
    private val pendingOpens=java.util.concurrent.atomic.AtomicInteger()
    private val latestStart=java.util.concurrent.atomic.AtomicInteger()
    private lateinit var wakeLock: PowerManager.WakeLock
    private var wakeRenewAt=0L
    @Volatile private var stopping=false
    override fun onCreate() {
        super.onCreate(); manager.serviceRunning=true
        wakeLock=getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"PhoneSerialPort:usb-session").apply {setReferenceCounted(false)}
        if(Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL,getString(R.string.connection_channel),NotificationManager.IMPORTANCE_LOW))
    }
    @Synchronized private fun keepCpuAwake(needed: Boolean) {
        if(needed && !stopping) {
            val now=SystemClock.elapsedRealtime()
            if(!wakeLock.isHeld || now >= wakeRenewAt) {
                wakeLock.acquire(30*60*1000L)
                wakeRenewAt=now+15*60*1000L
            }
        } else if(wakeLock.isHeld) {wakeLock.release();wakeRenewAt=0}
    }
    private fun notification(): Notification {
        val launch = requireNotNull(packageManager.getLaunchIntentForPackage(packageName))
            .setAction("com.lyufeng.androidtoolkit.OPEN_SERIAL")
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val open=PendingIntent.getActivity(this,0,launch,PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val disconnect=PendingIntent.getService(this,1,Intent(this,SerialService::class.java).setAction(ACTION_DISCONNECT),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val count=manager.sessions.value.values.count { it.state.value.state == PortState.OPEN }
        return NotificationCompat.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_serial_notification)
            .setContentTitle(getString(R.string.serial_tool_name)).setContentText(getString(R.string.connection_count,count))
            .setContentIntent(open).setOngoing(true).addAction(0,getString(R.string.disconnect),disconnect).build()
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if(intent == null) { stopSelf(); return START_NOT_STICKY }
        latestStart.set(startId)
        if(intent.action == ACTION_DISCONNECT) {
            scope.launch {
                manager.disconnectAll()
                if(stopSelfResult(startId)) {stopForeground(STOP_FOREGROUND_REMOVE);foreground=false}
            }
            return START_NOT_STICKY
        }
        if(intent.action != ACTION_OPEN) { stopSelf(); return START_NOT_STICKY }
        try {
            ServiceCompat.startForeground(this,100,notification(),if(Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0)
            foreground=true
            keepCpuAwake(true)
            val key=intent.getStringExtra("device") ?: error("Missing USB device")
            val port=intent.getIntExtra("port",0)
            val generation=intent.getLongExtra("generation", manager.currentGeneration())
            if (!manager.isCurrentGeneration(generation)) {
                manager.completeServiceOpen(key,port,generation)
                if(pendingOpens.get() == 0 && manager.sessions.value.values.none { it.state.value.state in listOf(PortState.OPEN,PortState.OPENING,PortState.CLOSING) }) {
                    if(stopSelfResult(startId)) {stopForeground(STOP_FOREGROUND_REMOVE);foreground=false}
                }
                return START_NOT_STICKY
            }
            ownedGenerations.add(generation)
            val config=SettingsCodec.decode(JSONObject(intent.getStringExtra("config") ?: "{}")).config
            pendingOpens.incrementAndGet()
            scope.launch {
                try {manager.open(key,port,intent.getStringExtra("driver"),config,generation)} catch (_: CancellationException) { } finally {pendingOpens.decrementAndGet();manager.completeServiceOpen(key,port,generation)}
                if(watch?.isActive != true) watch=scope.launch {
                    while(isActive && foreground) {
                        val observedStart=latestStart.get()
                        val active=pendingOpens.get() > 0 || manager.sessions.value.values.any {it.state.value.state in listOf(PortState.OPEN,PortState.OPENING,PortState.CLOSING)}
                        keepCpuAwake(active)
                        if(!active) {
                            if(stopSelfResult(observedStart)) {stopForeground(STOP_FOREGROUND_REMOVE);foreground=false;break}
                        }
                        if(Build.VERSION.SDK_INT < 33 || checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED)
                            getSystemService(NotificationManager::class.java).notify(100,notification())
                        delay(1000)
                    }
                }
            }
        } catch(e: Exception) {
            intent.getStringExtra("device")?.let {manager.completeServiceOpen(it,intent.getIntExtra("port",0),intent.getLongExtra("generation",manager.currentGeneration()))}
            manager.reportConnectionFailure(intent.getStringExtra("device"),"Foreground connection failed: ${e.message}"); stopSelf()
        }
        return START_NOT_STICKY
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        stopping=true
        keepCpuAwake(false)
        manager.serviceStopped(); scope.cancel()
        val owned=manager.sessionsOwnedBy(ownedGenerations.toSet())
        CoroutineScope(SupervisorJob()+Dispatchers.IO).launch { owned.forEach { it.close() } }
        super.onDestroy()
    }
    companion object {
        const val ACTION_OPEN="com.lyufeng.phoneserialport.OPEN"
        const val ACTION_DISCONNECT="com.lyufeng.phoneserialport.DISCONNECT"
        const val CHANNEL="serial_connection"
    }
}
