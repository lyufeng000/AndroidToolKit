package com.example.androidtoolkit

import android.content.res.Configuration
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.RuleChain

@RunWith(AndroidJUnit4::class)
class ToolkitAppearanceTest {
    val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(ForegroundActivityRule()).around(compose)
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun shell(command: String): String = android.os.ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }

    @Test fun allThreePagesFollowSystemThemeAndHomeHasNoPlaceholderIcons() {
        val original = shell("cmd uimode night").substringAfter(": ").trim()
        try {
            checkTheme(false)
            compose.onNodeWithText("串").assertDoesNotExist()
            compose.onNodeWithText("烧").assertDoesNotExist()
            checkTheme(true)
            compose.onNodeWithText("代码烧入").performClick()
            compose.onNodeWithText("Android → USB OTG", substring = true).assertDoesNotExist()
            checkTheme(false)
            checkTheme(true)
            compose.onNodeWithText("返回").performClick()
            compose.onNodeWithText("串口调试").performClick()
            checkTheme(false)
            checkTheme(true)
        } finally { shell("cmd uimode night ${if (original in setOf("yes", "no", "auto", "custom")) original else "no"}") }
    }
    private fun checkTheme(dark: Boolean) {
        shell("cmd uimode night ${if (dark) "yes" else "no"}")
        compose.waitUntil(10000) {
            val mode = instrumentation.targetContext.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            (mode == Configuration.UI_MODE_NIGHT_YES) == dark
        }
        compose.waitForIdle()
        val pixels = compose.onRoot().captureToImage().toPixelMap()
        val brightness = pixels[2, pixels.height / 2].luminance()
        assertTrue("Expected dark=$dark, brightness=$brightness", if (dark) brightness < 0.2f else brightness > 0.7f)
    }
}
