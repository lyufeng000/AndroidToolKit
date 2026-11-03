package com.lyufeng.phoneserialport

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbInterface
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.viewModelScope
import com.hoho.android.usbserial.driver.FtdiSerialDriver
import com.hoho.android.usbserial.driver.Ch34xSerialDriver
import com.hoho.android.usbserial.driver.GsmModemSerialDriver
import com.lyufeng.phoneserialport.core.*
import com.lyufeng.phoneserialport.data.AppSettings
import com.lyufeng.phoneserialport.serial.SerialSession
import com.lyufeng.phoneserialport.serial.UsbDriverAdapter
import com.lyufeng.phoneserialport.ui.SerialApp
import com.lyufeng.phoneserialport.ui.SerialViewModel
import com.lyufeng.phoneserialport.ui.theme.PhoneSerialPortTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[37],application=SerialApplication::class)
class ParameterUiTest {
    @get:Rule val compose=createComposeRule()
    private val app get()=RuntimeEnvironment.getApplication() as SerialApplication
    private var model: SerialViewModel?=null
    private var session: SerialSession?=null
    private fun device(): UsbDevice = ReflectionHelpers.newInstance(UsbDevice::class.java).also {
        ReflectionHelpers.setField(it,"mName","/dev/bus/usb/001/002")
        ReflectionHelpers.setField(it,"mInterfaces",arrayOf(ReflectionHelpers.newInstance(UsbInterface::class.java)))
    }
    private fun show(source: UsbDriverAdapter, config: SerialConfig = SerialConfig(), estimate: Double? = null) {
        runBlocking {
            app.container.configuration.saveSettings(AppSettings(hintsSeen=true))
            val adapter=object : SerialDriverAdapter {
                override val driverName=source.driverName
                override val capabilities=source.capabilities
                override fun apply(config: SerialConfig): BaudRateReport {capabilities.validate(config);return BaudRateReport(config.baudRate,divisorEstimated=estimate)}
                override fun read(buffer: ByteArray): Int {Thread.sleep(10);return 0}
                override fun write(bytes: ByteArray,timeoutMs: Int)=WriteResult(bytes.size)
                override fun control(name: String,enabled: Boolean) {}
                override fun readControlLines(): Map<String,Boolean> = emptyMap()
                override fun close() {}
            }
            val opened=SerialSession(app,"parameter-ui","ui-device",0,adapter,AppSettings()) {_,_ -> }
            session=opened;opened.start(config)
            ReflectionHelpers.getField<MutableStateFlow<Map<String,SerialSession>>>(app.devices,"mutableSessions").value=mapOf(opened.id to opened)
        }
        val vm=SerialViewModel(app);model=vm
        compose.setContent {PhoneSerialPortTheme {SerialApp(vm) {}}}
        compose.waitUntil(5000) {vm.active.value != null}
        compose.onNodeWithText("波特率未实测",substring=true).assertDoesNotExist()
        compose.onNodeWithText("波特率推算偏差",substring=true).assertDoesNotExist()
        compose.onNodeWithText("菜单").performClick()
        compose.onNodeWithText("调试选项").performClick()
        compose.onNodeWithText("参数与诊断").performClick()
    }
    @After fun cleanup()=runBlocking {
        model?.viewModelScope?.cancel()
        session?.let {it.close();it.store.close()}
        ReflectionHelpers.getField<CoroutineScope>(app.devices,"scope").cancel()
        app.container.database.close()
    }
    @Test fun ftdiMenusDisableUnsupportedDataAndStopBits() {
        show(UsbDriverAdapter(FtdiSerialDriver(device()).ports.first()))
        compose.onNodeWithText("数据位: 8 ▾").performScrollTo().performClick()
        compose.onNodeWithText("5").assertIsNotEnabled()
        compose.onNodeWithText("6").assertIsNotEnabled()
        compose.onNodeWithText("7").assertIsEnabled()
        compose.onNodeWithText("8").performClick()
        compose.onNodeWithText("停止位: ONE ▾").performScrollTo().performClick()
        compose.onNodeWithText("ONE_POINT_FIVE").assertIsNotEnabled()
        compose.onNodeWithText("TWO").assertIsEnabled()
    }
    @Test fun byteStreamPortDisablesUartParameterControlsAndApply() {
        show(UsbDriverAdapter(GsmModemSerialDriver(device()).ports.first()))
        compose.onNodeWithText("自定义波特率").assertIsNotEnabled()
        compose.onNodeWithText("数据位: 8 ▾").assertIsNotEnabled()
        compose.onNodeWithText("校验位: NONE ▾").assertIsNotEnabled()
        compose.onNodeWithText("停止位: ONE ▾").assertIsNotEnabled()
        compose.onNodeWithText("应用参数").assertIsNotEnabled()
    }
    @Test fun detailedBaudDataRemainsInTheSecondaryDialogWithoutAdvisoryText() {
        show(UsbDriverAdapter(Ch34xSerialDriver(device()).ports.first()),SerialConfig(baudRate=1152000),1_200_000.0)
        compose.onNodeWithText("推算偏差: +4.1667%").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("当前 CH34x 分频模型无法使此速率达到 ±1%。请核对设备速率，或使用支持此速率的适配器。")
            .assertDoesNotExist()
    }
}
