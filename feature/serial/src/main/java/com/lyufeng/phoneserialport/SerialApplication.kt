package com.lyufeng.phoneserialport

import android.app.Application
import com.lyufeng.phoneserialport.data.AppContainer
import com.lyufeng.phoneserialport.serial.UsbDeviceManager

class SerialApplication : Application() {
    lateinit var container: AppContainer; private set
    lateinit var devices: UsbDeviceManager; private set
    override fun onCreate() {
        super.onCreate()
        container=AppContainer(this)
        devices=UsbDeviceManager(this,container)
    }
}
