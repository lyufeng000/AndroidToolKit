package com.example.androidtoolkit

import android.content.Intent
import android.hardware.usb.UsbManager
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import android.os.ParcelFileDescriptor
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.platform.app.InstrumentationRegistry

@RunWith(AndroidJUnit4::class)
class ToolkitNavigationTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Before fun launchHome() {
        // Launch the real activity without ActivityScenario's cross-package
        // helper activity, which MIUI intercepts during test teardown.
        val test = InstrumentationRegistry.getInstrumentation()
        val component = "${test.targetContext.packageName}/com.example.androidtoolkit.MainActivity"
        ParcelFileDescriptor.AutoCloseInputStream(test.uiAutomation.executeShellCommand(
            "am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -f 0x10008000 -n $component"
        )).use { it.readBytes() }
        compose.waitUntil(5000) { compose.onAllNodesWithText("峰的工具箱").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun homeOpensSerialAndBackReturnsHome() {
        compose.onNodeWithText("峰的工具箱").assertIsDisplayed()
        compose.onAllNodesWithText("串口调试").assertCountEquals(1)
        compose.onNodeWithText("串口调试").performClick()
        assertSerialOpen()
        withActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntil(5000) { compose.onAllNodesWithText("峰的工具箱").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("峰的工具箱").assertIsDisplayed()
    }

    @Test
    fun notificationOpensSerial() {
        deliverAction("com.lyufeng.androidtoolkit.OPEN_SERIAL")
        assertSerialOpen()
    }

    @Test
    fun usbAttachOpensSerial() {
        deliverAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        assertSerialOpen()
    }

    private fun deliverAction(action: String) {
        withActivity { activity ->
            activity.startActivity(Intent(activity, MainActivity::class.java).apply {
                this.action = action
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            })
        }
        compose.waitForIdle()
    }

    private fun withActivity(action: (MainActivity) -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<MainActivity>().single()
            action(activity)
        }
    }

    private fun assertSerialOpen() {
        compose.onNodeWithText("峰的工具箱").assertDoesNotExist()
        compose.onNodeWithText("串口调试").assertIsDisplayed()
    }
}
