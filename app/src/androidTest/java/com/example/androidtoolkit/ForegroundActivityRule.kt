package com.example.androidtoolkit

import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/** Bring the app forward before ActivityScenario. MIUI otherwise denies background launches.
 * The test changes no device permissions or security settings.
 */
class ForegroundActivityRule : TestRule {
    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            val test = InstrumentationRegistry.getInstrumentation()
            val component = "${test.targetContext.packageName}/com.example.androidtoolkit.MainActivity"
            ParcelFileDescriptor.AutoCloseInputStream(test.uiAutomation.executeShellCommand(
                "am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n $component"
            )).use { it.readBytes() }
            base.evaluate()
        }
    }
}
