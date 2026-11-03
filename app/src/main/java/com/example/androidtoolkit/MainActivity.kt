package com.example.androidtoolkit

import android.content.Intent
import android.hardware.usb.UsbManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.lyufeng.androidtoolkit.home.ToolkitHome
import com.lyufeng.androidtoolkit.update.UpdateControls
import com.lyufeng.phoneserialport.SerialApplication
import com.lyufeng.phoneserialport.ui.SerialToolScreen
import com.lyufeng.flash.FlashScreen

private const val OPEN_SERIAL = "com.lyufeng.androidtoolkit.OPEN_SERIAL"
private const val SERIAL_PAGE = "serial_page"

class MainActivity : ComponentActivity() {
    private var serialPage by mutableStateOf(false)
    private var flashPage by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        serialPage = savedInstanceState?.getBoolean(SERIAL_PAGE) ?: false
        flashPage = savedInstanceState?.getBoolean("flash_page") ?: false
        if (savedInstanceState == null) handleIntent(intent)
        enableEdgeToEdge()
        setContent {
            if (flashPage) {
                FlashScreen(onReturnHome = { flashPage = false })
            } else if (serialPage) {
                SerialToolScreen(onReturnHome = { serialPage = false })
            } else {
                ToolkitHome(
                    appName = R.string.app_name,
                    onOpenSerial = { serialPage = true },
                    onOpenFlash = { flashPage = true },
                    updateContent = { UpdateControls() },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            OPEN_SERIAL -> { flashPage = false; serialPage = true }
            "com.lyufeng.androidtoolkit.OPEN_FLASH" -> { flashPage = true; serialPage = false }
            UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                @Suppress("DEPRECATION")
                val device = intent.getParcelableExtra<android.hardware.usb.UsbDevice>(UsbManager.EXTRA_DEVICE)
                val isProbe = device?.vendorId == 0x1366 || (device?.vendorId == 0x0483 && device.productId in
                    setOf(0x3744, 0x3748, 0x374b, 0x374d, 0x374e, 0x374f, 0x3752, 0x3753, 0x3754))
                if (isProbe) { flashPage = true; serialPage = false }
                else { flashPage = false; serialPage = true; (application as SerialApplication).devices.scan(true) }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(SERIAL_PAGE, serialPage)
        outState.putBoolean("flash_page", flashPage)
        super.onSaveInstanceState(outState)
    }
}
