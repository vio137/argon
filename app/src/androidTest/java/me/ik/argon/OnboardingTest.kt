package me.ik.argon

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * First-run onboarding flow: appears on a fresh install, three pages, engine
 * choice persists, and dismissal lands on the browser chrome.
 */
@RunWith(AndroidJUnit4::class)
class OnboardingTest {

    private lateinit var device: UiDevice

    @Before
    fun setUp() {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    }

    @Test
    fun freshInstallShowsOnboardingAndCompletes() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(ctx)
            .edit().clear().commit()
        val launch = ctx.packageManager.getLaunchIntentForPackage("me.ik.argon")!!
        launch.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
        InstrumentationRegistry.getInstrumentation().startActivitySync(launch)

        assertTrue("welcome page shows", device.wait(Until.hasObject(By.textContains("quieter")), 15_000))
        device.findObject(By.text("Continue")).click()
        assertTrue("privacy page shows", device.wait(Until.hasObject(By.textContains("Private by default")), 5_000))
        device.findObject(By.text("Continue")).click()
        assertTrue("engine page shows", device.wait(Until.hasObject(By.textContains("Pick your search")), 5_000))
        device.findObject(By.text("DuckDuckGo")).click()
        device.findObject(By.text("Start browsing")).click()
        assertTrue(
            "browser chrome appears after onboarding",
            device.wait(Until.hasObject(By.clazz("android.widget.EditText")), 10_000),
        )
        val saved = androidx.preference.PreferenceManager.getDefaultSharedPreferences(ctx)
        assertTrue("onboarded flag persisted", saved.getBoolean("onboarded", false))
        assertTrue("engine choice persisted", saved.getString("engine", null) == "duckduckgo")
        // Second launch must not show onboarding again.
        InstrumentationRegistry.getInstrumentation().startActivitySync(launch)
        Thread.sleep(2_000)
        assertTrue(
            "no onboarding on relaunch",
            device.wait(Until.hasObject(By.clazz("android.widget.EditText")), 10_000) &&
                !device.hasObject(By.textContains("quieter")),
        )
    }
}
