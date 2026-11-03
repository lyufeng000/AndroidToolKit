package com.example.androidtoolkit

import android.content.Intent
import android.content.Context
import android.hardware.usb.UsbManager
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.lyufeng.phoneserialport.SerialApplication
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = SerialApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ToolkitNavigationRobolectricTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    @Test
    fun homeHasTwoToolsAndBackReturnsFromSerial() {
        ActivityScenario.launch<MainActivity>(launchIntent()).use { scenario ->
            compose.onNodeWithText("峰的工具箱").assertIsDisplayed()
            compose.onAllNodesWithText("串口调试").assertCountEquals(1)
            compose.onAllNodesWithText("代码烧入").assertCountEquals(1)
            compose.onNodeWithText("串口调试").performClick()
            assertSerialOpen()
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            compose.waitUntil(5000) { compose.onAllNodesWithText("峰的工具箱").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("峰的工具箱").assertIsDisplayed()
        }
    }

    @Test
    fun flashEntryOpensAndSurvivesRecreation() {
        ActivityScenario.launch<MainActivity>(launchIntent()).use { scenario ->
            compose.onNodeWithText("代码烧入").performClick()
            compose.onNodeWithText("1  选择烧录路线").assertIsDisplayed()
            scenario.recreate()
            compose.onNodeWithText("1  选择烧录路线").assertIsDisplayed()
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            compose.onNodeWithText("峰的工具箱").assertIsDisplayed()
        }
    }

    @Test
    fun notificationActionOpensSerial() {
        ActivityScenario.launch<MainActivity>(launchIntent("com.lyufeng.androidtoolkit.OPEN_SERIAL")).use {
            assertSerialOpen()
        }
    }

    @Test
    fun usbAttachActionOpensSerial() {
        ActivityScenario.launch<MainActivity>(launchIntent(UsbManager.ACTION_USB_DEVICE_ATTACHED)).use {
            assertSerialOpen()
        }
    }

    private fun launchIntent(action: String? = null): Intent =
        Intent(ApplicationProvider.getApplicationContext<Context>(), MainActivity::class.java).setAction(action)

    private fun assertSerialOpen() {
        compose.onNodeWithText("峰的工具箱").assertDoesNotExist()
        compose.onNodeWithText("串口调试").assertIsDisplayed()
    }
}
