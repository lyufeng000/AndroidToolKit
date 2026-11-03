package com.lyufeng.phoneserialport

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.lyufeng.phoneserialport.ui.SerialApp
import com.lyufeng.phoneserialport.ui.SerialViewModel
import com.lyufeng.phoneserialport.ui.theme.PhoneSerialPortTheme

class MainActivity : ComponentActivity() {
    private val model: SerialViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if(intent?.action == android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED) model.manager.scan(true)
        setContent { PhoneSerialPortTheme { SerialApp(model) { finish() } } }
    }
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        if(intent.action == android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED) model.manager.scan(true)
    }
    override fun onResume() {
        super.onResume()
        model.manager.appVisible=true
        model.manager.scan(true)
    }
    override fun onPause() {
        model.manager.appVisible=false
        super.onPause()
    }
}
