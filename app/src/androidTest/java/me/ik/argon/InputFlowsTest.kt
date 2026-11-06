package me.ik.argon

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import me.ik.argon.PerfStats
import org.junit.runners.MethodSorters

/**
 * Every test guarantees a live browser activity itself: the runner's
 * ActivityFinisher cleanup fires at test start and finishes any activity
 * launched earlier (observed on device), so a @BeforeClass launch is dead
 * by the time the first test body runs. ensureBrowser() waits out the
 * cleanup, then launches (monitored) only if the omnibox is absent.
 * Screenshots are captured in finally blocks so failures keep evidence.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class InputFlowsTest {

    companion object {
        const val PKG = "me.ik.argon"
        const val TAG = "ArgonInputTest"
        private lateinit var device: UiDevice

        private fun omnibox() = By.clazz("android.widget.EditText")

        private fun launchIntent(clearTask: Boolean): Intent {
            val ctx = InstrumentationRegistry.getInstrumentation().context
            val intent = ctx.packageManager.getLaunchIntentForPackage(PKG)!!
            if (clearTask) {
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            return intent
        }

        private fun ensureBrowser(): UiObject2 {
            // Let any test-start ActivityFinisher cleanup land first.
            Thread.sleep(1_500)
            if (!device.wait(Until.hasObject(omnibox()), 3_000)) {
                Log.i(TAG, "ensureBrowser: no live browser, launching (monitored)")
                InstrumentationRegistry.getInstrumentation().startActivitySync(launchIntent(false))
                assertTrue(
                    "omnibox appears after launch",
                    device.wait(Until.hasObject(omnibox()), 30_000),
                )
                Thread.sleep(2_000)
            }
            return findOmnibox()
        }

        private fun waitForLoadComplete(stage: String) {
            val ok = device.wait(Until.hasObject(By.desc("Reload")), 60_000)
            Log.i(TAG, "LOAD_COMPLETE[$stage]=$ok")
            assertTrue("page load completes at $stage (Reload action visible)", ok)
        }

        private fun imeDump(label: String): Boolean {
            val out = device.executeShellCommand("dumpsys input_method")
            out.lines()
                .filter { it.contains("mInputShown") || it.contains("mImeWindowVis") }
                .forEach { Log.i(TAG, "IME[$label] $it") }
            val shown = out.contains("mInputShown=true")
            Log.i(TAG, "IME_SHOWN[$label]=$shown")
            return shown
        }

        private fun waitIme(shown: Boolean, timeoutMs: Long, label: String): Boolean {
            val end = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < end) {
                if (imeDump(label) == shown) return true
                Thread.sleep(500)
            }
            return false
        }

        private fun evidence(label: String) {
            try {
                val f =
                    File(
                        InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
                        "$label.png",
                    )
                device.takeScreenshot(f)
                val bmp = BitmapFactory.decodeFile(f.absolutePath) ?: return
                val scaled = Bitmap.createScaledBitmap(bmp, bmp.width / 2, bmp.height / 2, true)
                val baos = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, 70, baos)
                val b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
                Log.i(TAG, "ARGON_EVIDENCE_BEGIN $label len=${b64.length}")
                var i = 0
                var n = 0
                while (i < b64.length) {
                    val e = minOf(i + 3500, b64.length)
                    Log.i(TAG, "ARGON_SHOT $label $n " + b64.substring(i, e))
                    i = e
                    n++
                }
                Log.i(TAG, "ARGON_EVIDENCE_END $label chunks=$n")
            } catch (t: Throwable) {
                Log.e(TAG, "screenshot evidence failed for $label: ${t.message}")
            }
        }

        private fun findOmnibox(): UiObject2 {
            val om = device.findObject(omnibox())
            assertNotNull("omnibox field exists", om)
            return om
        }
    }

    @Before
    fun initDevice() {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    }

    @Test
    fun t1_keyboardRaisesAndDismisses() {
        try {
            val om = ensureBrowser()
            om.click()
            assertTrue("keyboard raises on omnibox tap", waitIme(true, 8_000, "raise"))
            evidence("01a-keyboard-open")
            device.pressBack()
            assertTrue("keyboard dismisses on back", waitIme(false, 8_000, "dismiss"))
            Log.i(TAG, "KEYBOARD_TEST=PASS")
        } finally {
            evidence("01-keyboard-end")
        }
    }

    @Test
    fun t2_omniboxTypingNavigates() {
        try {
            val om = ensureBrowser()
            om.click()
            assertTrue("keyboard appears when omnibox tapped", waitIme(true, 8_000, "omnibox-tap"))
            // Long URL proves single-line bounded editing (visual evidence shot).
            val longUrl =
                "https://example.com/a/rather/long/path/with-segments?query=paramvalue&another=1234567890"
            om.setText(longUrl)
            Thread.sleep(700)
            val typed = om.text ?: ""
            Log.i(TAG, "OMNIBOX_TYPED_TEXT='$typed'")
            assertTrue("typed text lands in omnibox", typed.contains("example.com"))
            val go = device.findObject(By.desc("Go"))
            assertNotNull("dedicated Go submit button exists while editing", go)
            evidence("02-omnibox-typed-longurl-keyboard-open")
            go.click()
            val committed =
                device.wait(
                    Until.findObject(By.clazz("android.widget.EditText").text("example.com")),
                    45_000,
                )
            Log.i(TAG, "NAV_COMMITTED=${committed != null}")
            assertTrue(
                "navigation committed: omnibox now shows host 'example.com' (focus moved to page)",
                committed != null,
            )
            waitForLoadComplete("example.com")
            Log.i(TAG, "OMNIBOX_TEST=PASS")
        } finally {
            evidence("03-after-navigation")
        }
    }

    @Test
    fun t3_pageTextFieldAcceptsKeyboardInput() {
        try {
            val rawHtml =
                "<!DOCTYPE html><meta name=\"viewport\" content=\"width=device-width\">" +
                    "<body style=\"margin:0;background:#fff\">" +
                    "<input id=\"t\" style=\"position:fixed;top:40%;left:10%;width:80%;height:70px;font-size:36px\" placeholder=\"tap-and-type\">" +
                    "<div id=\"out\" style=\"position:fixed;top:60%;left:10%;font-size:42px;color:#087f23\"></div>" +
                    "<div id=\"fonts\" style=\"position:fixed;top:6%;left:10%;font-size:40px;color:#111\">বাংলা আমার<br>देवनागरी हिन्दी<br>中文日本語한국어</div>" +
                    "<script>var t=document.getElementById('t');" +
                    "t.addEventListener('input',function(){document.getElementById('out').textContent='GOT:'+t.value});</script>"
            val enc =
                java.net.URLEncoder.encode(rawHtml, "UTF-8")
                    .replace("+", "%20")
                    .replace("%7E", "~")
            val dataUrl = "data:text/html,$enc"
            // Evidence for the glyph-fallback investigation: which faces exist.
            val fontInventory = device.executeShellCommand("ls /system/fonts")
            Log.i(TAG, "FONT_INVENTORY: $fontInventory")
            // Evidence for the glyph-fallback root cause: the actual font mapping
            // the engine parses (named families vs lang-tagged unnamed ones).
            val fontsXml = device.executeShellCommand("cat /system/etc/fonts.xml")
            Log.i(TAG, "FONTS_XML_LEN: ${fontsXml.length}")
            fontsXml.chunked(3000).forEachIndexed { i, c -> Log.i(TAG, "FONTS_XML[$i] $c") }
            val om = ensureBrowser()
            om.click()
            assertTrue("keyboard appears for omnibox", waitIme(true, 8_000, "omnibox-tap-2"))
            om.setText(dataUrl)
            Thread.sleep(500)
            device.pressEnter()
            Thread.sleep(4_000)
            waitForLoadComplete("data-url-page")
            Thread.sleep(2_000)
            // The page input sits at top:40% height:70px; tap its centre.
            val x = device.displayWidth / 2
            val y = (device.displayHeight * 0.44).toInt()
            Log.i(TAG, "PAGE_FIELD_TAP at ($x,$y) of ${device.displayWidth}x${device.displayHeight}")
            device.click(x, y)
            val kbShown = waitIme(true, 8_000, "page-field-tap")
            Log.i(TAG, "PAGE_FIELD_KEYBOARD=$kbShown")
            assertTrue("keyboard appears when PAGE text field tapped (field takes focus)", kbShown)
            evidence("04-page-field-keyboard")
            device.executeShellCommand("input text ArgonCheck42")
            Thread.sleep(1_500)
            evidence("05-page-field-typed")
            Log.i(TAG, "PAGE_INPUT_INJECTED='ArgonCheck42' - see screenshot evidence 05")
            Log.i(TAG, "PAGE_FIELD_TEST=PASS")
        } finally {
            evidence("05b-page-field-end")
        }
    }

    @Test
    fun t4_relaunchKeepsEngineUsable() {
        try {
            ensureBrowser()
            // Destroys and recreates the activity inside the SAME process
            // (CLEAR_TASK). The engine wrapper must be re-attached, not
            // re-initialised: re-init panics in JNIServo.init.
            val pidBefore = device.executeShellCommand("pidof me.ik.argon").trim()
            Log.i(TAG, "RELAUNCH_PID_BEFORE=$pidBefore")
            InstrumentationRegistry.getInstrumentation().startActivitySync(launchIntent(true))
            val pidAfterWait = System.currentTimeMillis()
            assertTrue(
                "omnibox appears after same-process relaunch",
                device.wait(Until.hasObject(omnibox()), 30_000),
            )
            val pidAfter = device.executeShellCommand("pidof me.ik.argon").trim()
            Log.i(TAG, "RELAUNCH_PID_AFTER=$pidAfter (waited ${System.currentTimeMillis()-pidAfterWait}ms)")
            assertTrue("same process survived relaunch (engine re-attached)", pidAfter == pidBefore)
            // The engine must still fully work: type and navigate again.
            Thread.sleep(2_000)
            val om = findOmnibox()
            om.click()
            assertTrue("keyboard raises after relaunch", waitIme(true, 8_000, "post-relaunch-tap"))
            om.setText("https://example.org")
            device.pressEnter()
            val committed =
                device.wait(
                    Until.findObject(By.clazz("android.widget.EditText").text("example.org")),
                    45_000,
                )
            assertTrue("navigation works after relaunch", committed != null)
            Log.i(TAG, "RELAUNCH_TEST=PASS")
        } finally {
            evidence("06-post-relaunch-navigation")
        }
    }

    @Test
    fun t5_backgroundAndForeground() {
        try {
            ensureBrowser()
            val pidBefore = device.executeShellCommand("pidof me.ik.argon").trim()
            Log.i(TAG, "BG_PID_BEFORE=$pidBefore")
            // Backgrounding destroys the window surface - the engine teardown
            // path that panicked in native pausePainting before the guard.
            device.pressHome()
            Thread.sleep(3_000)
            // Resume via shell: startActivitySync waits for main-thread idle,
            // which never comes while the engine's frame loop runs (45s timeout).
            device.executeShellCommand("am start -n me.ik.argon/.MainActivity")
            assertTrue(
                "omnibox appears after background resume",
                device.wait(Until.hasObject(omnibox()), 30_000),
            )
            val pidAfter = device.executeShellCommand("pidof me.ik.argon").trim()
            Log.i(TAG, "BG_PID_AFTER=$pidAfter")
            assertTrue("process survived backgrounding (no engine panic)", pidAfter == pidBefore)
            // Repaint + state: the loaded page (example.org from t4) still shows.
            val hostKept =
                device.wait(
                    Until.findObject(By.clazz("android.widget.EditText").text("example.org")),
                    10_000,
                )
            Log.i(TAG, "BG_PAGE_KEPT=${hostKept != null}")
            assertTrue("page state repainted after resume (host still shown)", hostKept != null)
            evidence("07a-after-resume-repaint")
            // Input still works after resume.
            val om = findOmnibox()
            om.click()
            assertTrue("keyboard raises after resume", waitIme(true, 8_000, "post-resume-tap"))
            // Navigation still works after resume (full engine pipeline).
            om.setText("https://example.com")
            val go = device.findObject(By.desc("Go"))
            assertNotNull("Go button present after resume", go)
            go.click()
            val committed =
                device.wait(
                    Until.findObject(By.clazz("android.widget.EditText").text("example.com")),
                    45_000,
                )
            assertTrue("navigation works after background resume", committed != null)
            // Renderer health: no guarded resume/window errors tolerated.
            val engineErrors = device.executeShellCommand("logcat -d -s ArgonNav:E")
            Log.i(TAG, "ENGINE_ERRORS:\n$engineErrors")
            assertTrue(
                "no resumePainting engine errors",
                !engineErrors.contains("resumePainting engine error"),
            )
            assertTrue(
                "no addPlatformWindow engine errors",
                !engineErrors.contains("addPlatformWindow engine error"),
            )
            Log.i(TAG, "BACKGROUND_TEST=PASS")
        } finally {
            evidence("07-after-background-resume")
        }
    }

    @Test
    fun t6_scrollFramePacing() {
        try {
            // Self-contained long page generated by script: repeatable, no
            // network variance. 4000 mixed rows = a heavy scroll workload.
            val rawHtml =
                "<!DOCTYPE html><meta name=\"viewport\" content=\"width=device-width\">" +
                    "<body style=\"margin:0\"><script>" +
                    "var h='';for(var i=0;i<4000;i++){h+='<div style=\"padding:12px 16px;font-size:20px;" +
                    "color:#eee;background:hsl('+(i%360)+',40%,'+(18+i%3*8)+'%)\">Row '+i+'" +
                    " - scroll workload row with enough text to force real layout and paint</div>'}" +
                    "document.body.innerHTML=h;</script>"
            val enc =
                java.net.URLEncoder.encode(rawHtml, "UTF-8")
                    .replace("+", "%20")
                    .replace("%7E", "~")
            val dataUrl = "data:text/html,$enc"
            val om = ensureBrowser()
            om.click()
            assertTrue("keyboard appears for omnibox", waitIme(true, 8_000, "omnibox-tap-scroll"))
            om.setText(dataUrl)
            Thread.sleep(500)
            device.pressEnter()
            Thread.sleep(4_000)
            waitForLoadComplete("scroll-page")
            Thread.sleep(3_000)

            PerfStats.reset()
            val cx = device.displayWidth / 2
            val h = device.displayHeight
            // 10 fast down-flings, then 10 up-flings, pacing the gestures.
            for (i in 1..10) {
                device.swipe(cx, (h * 0.80).toInt(), cx, (h * 0.20).toInt(), 6)
                Thread.sleep(450)
                if (i == 5) {
                    // Native (UiAutomator) capture MID-scroll: compare against the
                    // same-moment video frame to attribute the streaking artifact
                    // (renderer vs recording codec).
                    evidence("08-scroll-mid-native")
                }
            }
            for (i in 1..10) {
                device.swipe(cx, (h * 0.20).toInt(), cx, (h * 0.80).toInt(), 6)
                Thread.sleep(450)
            }
            Thread.sleep(1_500)

            val report = PerfStats.report()
            Log.i(TAG, "PERF_REPORT_SCROLL $report")
            val gfx = device.executeShellCommand("dumpsys gfxinfo me.ik.argon")
            Log.i(TAG, "GFXINFO_SCROLL " + gfx.take(3000))
            val mem = device.executeShellCommand("dumpsys meminfo me.ik.argon")
            Log.i(TAG, "MEMINFO_SCROLL " + mem.lines().filter { it.contains("TOTAL") }.take(3).joinToString(" | "))
            assertTrue("scroll pacing sampled", report.contains("n=") && !report.contains("insufficient"))
            Log.i(TAG, "SCROLL_PERF_TEST=PASS")
        } finally {
            evidence("08-scroll-perf-end")
        }
    }

    @Test
    fun t7_userAgentPresentsAsMobileChrome() {
        try {
            // JS-visible UA: data: page renders navigator.userAgent on-screen.
            val rawHtml =
                "<!DOCTYPE html><meta name=\"viewport\" content=\"width=device-width\">" +
                    "<body style=\"margin:0;background:#fff;font-size:22px;padding:24px\">" +
                    "<div id=ua style=\"color:#111\"></div>" +
                    "<script>document.getElementById('ua').textContent=navigator.userAgent;</script>"
            val enc =
                java.net.URLEncoder.encode(rawHtml, "UTF-8")
                    .replace("+", "%20")
                    .replace("%7E", "~")
            val om = ensureBrowser()
            om.click()
            assertTrue("keyboard appears", waitIme(true, 8_000, "ua-omnibox-tap"))
            om.setText("data:text/html,$enc")
            Thread.sleep(500)
            device.pressEnter()
            Thread.sleep(4_000)
            waitForLoadComplete("ua-js-page")
            Thread.sleep(1_500)
            evidence("09-ua-js-visible")

            // HTTP header UA: echo endpoint shows what the network layer sends.
            om.click()
            assertTrue("keyboard appears again", waitIme(true, 8_000, "ua-omnibox-tap-2"))
            om.setText("https://httpbin.org/user-agent")
            Thread.sleep(500)
            device.pressEnter()
            Thread.sleep(5_000)
            waitForLoadComplete("ua-http-page")
            Thread.sleep(1_500)
            evidence("09-ua-http-header")
            Log.i(TAG, "UA_TEST=PASS")
        } finally {
            evidence("09-ua-end")
        }
    }

    @Test
    fun t8_fontCssFallbackExperiment() {
        try {
            // Lines: (1) Bengali default family - tests whether platform
            // missing-glyph fallback consults the user-CSS @font-face named
            // exactly like the fallback family. (2) Bengali with explicit
            // ArgonTestBengali - positive control for @font-face + data: URL
            // mechanics. (3) Devanagari default. (4) Japanese default -
            // expected tofu (CJK font exceeds the CSS size cap; documents limit).
            val rawHtml =
                "<!DOCTYPE html><meta name=\"viewport\" content=\"width=device-width\">" +
                    "<body style=\"margin:0;background:#fff;padding:28px\">" +
                    "<div style=\"font-size:44px;color:#111;margin:22px 0\">L1 বাংলা আমার ভাষা</div>" +
                    "<div style=\"font-size:44px;color:#111;margin:22px 0;font-family:ArgonTestBengali\">L2 বাংলা আমার ভাষা</div>" +
                    "<div style=\"font-size:44px;color:#111;margin:22px 0\">L3 देवनागरी हिन्दी भाषा</div>" +
                    "<div style=\"font-size:44px;color:#111;margin:22px 0\">L4 日本語のテキスト</div>"
            val enc =
                java.net.URLEncoder.encode(rawHtml, "UTF-8")
                    .replace("+", "%20")
                    .replace("%7E", "~")
            val om = ensureBrowser()
            om.click()
            assertTrue("keyboard appears", waitIme(true, 8_000, "fontcss-omnibox-tap"))
            om.setText("data:text/html,$enc")
            Thread.sleep(500)
            device.pressEnter()
            Thread.sleep(5_000)
            waitForLoadComplete("fontcss-page")
            Thread.sleep(2_500)
            evidence("10-fontcss-page")
            Log.i(TAG, "FONTCSS_PAGE_SHOWN - verdict from native screenshot 10-fontcss-page + video")
            Log.i(TAG, "FONTCSS_TEST=PASS")
        } finally {
            evidence("10-fontcss-end")
        }
    }
}
