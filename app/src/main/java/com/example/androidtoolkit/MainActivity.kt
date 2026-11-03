package com.example.androidtoolkit

import android.content.Intent
import android.hardware.usb.UsbManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
                ToolkitHome(onOpenSerial = { serialPage = true }, onOpenFlash = { flashPage = true })
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

@Composable
private fun ToolkitHome(onOpenSerial: () -> Unit, onOpenFlash: () -> Unit) {
    val colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
    MaterialTheme(colorScheme = colors) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(40.dp))
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(26.dp))
                HorizontalDivider(thickness = 3.dp, color = Color(0xFFE53935))
                Spacer(Modifier.height(24.dp))
                Card(
                    modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onOpenSerial),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(stringResource(R.string.serial_tool), style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold)
                            Text(stringResource(R.string.serial_tool_description),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("›", style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(16.dp))
                Card(
                    modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onOpenFlash),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("代码烧入", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text("ST-Link / J-Link / DAPLink · 源码编译与固件烧录", style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("›", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}
