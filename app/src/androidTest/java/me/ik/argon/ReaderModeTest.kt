package me.ik.argon

import android.content.Intent
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * Reader mode end-to-end: bundled Readability runs in the page through the
 * evaluateScript bridge; results render as inert text in the overlay. Covers
 * a known article, a known non-article, and the navigation race (result from
 * a page the user already left must be discarded). Mirrors InputFlowsTest's
 * self-launching harness (test-start ActivityFinisher kills earlier launches).
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class ReaderModeTest {

    companion object {
        const val PKG = "me.ik.argon"
        const val TAG = "ArgonReaderTest"
        private lateinit var device: UiDevice

        private fun omnibox() = By.clazz("android.widget.EditText")

        private fun ensureBrowser(): UiObject2 {
            Thread.sleep(1_500)
            if (!device.wait(Until.hasObject(omnibox()), 3_000)) {
                Log.i(TAG, "ensureBrowser: no live browser, launching")
                val ctx = InstrumentationRegistry.getInstrumentation().context
                val intent = ctx.packageManager.getLaunchIntentForPackage(PKG)!!
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
                InstrumentationRegistry.getInstrumentation().startActivitySync(intent)
                assertTrue("omnibox appears", device.wait(Until.hasObject(omnibox()), 30_000))
                Thread.sleep(2_000)
            }
            return device.findObject(omnibox())
        }

        private fun openUrl(url: String) {
            val om = ensureBrowser()
            om.click()
            Thread.sleep(500)
            om.setText(url)
            device.pressEnter()
            assertTrue(
                "page load completes for $url",
                device.wait(Until.hasObject(By.desc("Reload")), 60_000),
            )
        }

        private fun openReaderFromMenu() {
            device.findObject(By.desc("Menu")).click()
            assertTrue("menu sheet opens", device.wait(Until.hasObject(By.text("Reader view")), 5_000))
            device.findObject(By.text("Reader view")).click()
        }
    }

    @Before
    fun setUp() {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    }

    @Test
    fun t01_article_showsOverlayAndCloses() {
        openUrl("https://en.wikipedia.org/wiki/Android_(operating_system)")
        openReaderFromMenu()
        assertTrue(
            "reader overlay appears for a known article",
            device.wait(Until.hasObject(By.desc("Close reader view")), 45_000),
        )
        assertTrue("article title rendered", device.hasObject(By.textContains("Android")))
        device.findObject(By.desc("Close reader view")).click()
        assertTrue(
            "overlay closes",
            device.wait(Until.gone(By.desc("Close reader view")), 5_000),
        )
        assertTrue("browser chrome still present", device.hasObject(omnibox()))
    }

    @Test
    fun t02_nonArticle_showsExplicitFailure() {
        openUrl("https://example.com")
        openReaderFromMenu()
        assertTrue(
            "non-article page yields explicit failure",
            device.wait(Until.hasObject(By.textContains("doesn't look like an article")), 45_000),
        )
        device.findObject(By.text("OK")).click()
        assertTrue(
            "failure dialog dismisses",
            device.wait(Until.gone(By.textContains("doesn't look like an article")), 5_000),
        )
    }

    @Test
    fun t03_navigationRace_staleResultDiscarded() {
        openUrl("https://en.wikipedia.org/wiki/Android_(operating_system)")
        openReaderFromMenu()
        // Navigate away before extraction returns; the result belongs to the
        // previous document and must be dropped without UI.
        openUrl("https://example.com")
        Thread.sleep(8_000)
        assertTrue(
            "no overlay from the stale page",
            !device.hasObject(By.desc("Close reader view")),
        )
        assertTrue(
            "no error dialog from the stale page",
            !device.hasObject(By.textContains("doesn't look like an article")),
        )
        // The reader still works on the new page afterwards (requests are not
        // wedged by the discarded one).
        openReaderFromMenu()
        assertTrue(
            "reader still answers after a discarded request",
            device.wait(Until.hasObject(By.textContains("doesn't look like an article")), 45_000),
        )
        device.findObject(By.text("OK")).click()
    }
}
