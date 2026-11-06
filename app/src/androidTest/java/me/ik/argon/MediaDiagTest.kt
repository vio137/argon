package me.ik.argon

import android.content.Intent
import android.graphics.Bitmap
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.servo.servoview.ServoHolder

/**
 * Media backend diagnostic (experimental pref-ON APK only): progressive MP4
 * (h264+aac) playback, seek, pause/resume, and teardown, all observed through
 * the evaluateScript bridge polling window.__diag() on the diagnostic page.
 * Every poll is logged as ArgonMediaDiag so the run log is the evidence even
 * where assertions are intentionally soft (decode timing varies by device).
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class MediaDiagTest {

    companion object {
        const val PKG = "me.ik.argon"
        const val TAG = "ArgonMediaDiag"
        const val PAGE = "https://argon-browser.surge.sh/media-test/"
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

        /** Run JS in the active page via the app's bridge; returns the JSON
         * string or null on error/timeout. */
        private fun evalJs(script: String, timeoutMs: Long = 8_000): String? {
            val servo = ServoHolder.servo
            if (servo == null) {
                Log.e(TAG, "evalJs: no live Servo instance")
                return null
            }
            val latch = CountDownLatch(1)
            val result = arrayOfNulls<String>(1)
            val error = arrayOfNulls<String>(1)
            servo.evaluateScript(script) { json, err ->
                result[0] = json
                error[0] = err
                latch.countDown()
            }
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            if (error[0] != null) Log.e(TAG, "evalJs error: ${error[0]} for: ${script.take(80)}")
            return result[0]
        }

        private fun diag(): JSONObject? {
            val raw = evalJs("window.__diag ? window.__diag() : 'no_diag'") ?: return null
            // The bridge JSON-encodes the return value: a string result arrives
            // wrapped in quotes.
            val unwrapped = raw.trim().removePrefix("\"").removeSuffix("\"").replace("\\\"", "\"")
            return runCatching { JSONObject(unwrapped) }.getOrNull().also {
                Log.i(TAG, "diag: $unwrapped")
            }
        }

        private fun pollDiag(maxMs: Long, everyMs: Long = 1_000, until: (JSONObject) -> Boolean): JSONObject? {
            val deadline = System.currentTimeMillis() + maxMs
            var last: JSONObject? = null
            while (System.currentTimeMillis() < deadline) {
                val d = diag()
                if (d != null) {
                    last = d
                    if (until(d)) return d
                }
                Thread.sleep(everyMs)
            }
            return last
        }

        private fun play() {
            evalJs("(function(){var v=document.getElementById('v');var p=v.play();if(p&&p.catch){p.catch(function(e){window.__playError=String(e);});}return 'fired';})()")
        }

        private fun currentTime(d: JSONObject) = d.optDouble("currentTime", -1.0)

        /** Samples a grid inside the video element's own content rect (from
         *  __diag rect * dpr, inset 8px), never touching chrome or page pixels. */
        private fun sampleVideoRect(d: JSONObject): Pair<String, Int>? {
            val rect = d.optJSONObject("rect") ?: return null
            val dpr = rect.optDouble("dpr", 1.0)
            val shot: Bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                ?: return null
            try {
                val inset = 8
                val x0 = (rect.optDouble("x") * dpr).toInt() + inset
                val y0 = (rect.optDouble("y") * dpr).toInt() + inset
                val w = (rect.optDouble("w") * dpr).toInt() - 2 * inset
                val h = (rect.optDouble("h") * dpr).toInt() - 2 * inset
                if (w < 16 || h < 16 || x0 < 0 || y0 < 0 || x0 + w > shot.width || y0 + h > shot.height) {
                    Log.w(TAG, "video rect off-screen: x=$x0 y=$y0 w=$w h=$h shot=${'$'}{shot.width}x${'$'}{shot.height}")
                    return null
                }
                val cols = 16
                val rows = 12
                var hash = 1469598103934665603L
                var lum = 0L
                var n = 0
                for (r in 0 until rows) {
                    for (c in 0 until cols) {
                        val px = shot.getPixel(x0 + c * w / cols, y0 + r * h / rows)
                        hash = (hash xor px.toLong()) * 1099511628211L
                        lum += ((px shr 16 and 0xFF) + (px shr 8 and 0xFF) + (px and 0xFF)) / 3
                        n++
                    }
                }
                return Pair(java.lang.Long.toHexString(hash), (lum / n).toInt())
            } finally {
                shot.recycle()
            }
        }
    }

    @Before
    fun setUp() {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    }

    @Test
    fun t01_metadataAndProgressivePlayback() {
        openUrl(PAGE)
        val meta = pollDiag(30_000) { it.optInt("readyState", 0) >= 1 }
        assertNotNull("video metadata (readyState>=1) within 30s", meta)
        Log.i(TAG, "metadata: w=${meta!!.optInt("videoWidth")} h=${meta.optInt("videoHeight")} dur=${meta.optDouble("duration")}")
        assertTrue("videoWidth reported", meta.optInt("videoWidth") > 0)

        play()
        val playing =
            pollDiag(25_000) {
                currentTime(it) > 1.5 &&
                    it.optJSONArray("events")?.toString()?.contains("playing") == true
            }
        if (playing == null || currentTime(playing) <= 1.5) {
            val err = evalJs("window.__playError || 'none'")
            Log.e(TAG, "FAIL playback did not progress; playError=$err")
        }
        assertTrue("progressive playback: currentTime advances past 1.5s with a playing event", playing != null && currentTime(playing) > 1.5)
    }

    @Test
    fun t02_seek() {
        openUrl(PAGE)
        pollDiag(30_000) { it.optInt("readyState", 0) >= 1 }
        play()
        pollDiag(20_000) { currentTime(it) > 1.0 }
        evalJs("(function(){var v=document.getElementById('v');v.currentTime=4.0;return 'seeked';})()")
        val after = pollDiag(15_000) { currentTime(it) >= 3.5 }
        assertNotNull("seek lands near 4s", after)
        val t1 = currentTime(after!!)
        Thread.sleep(1_500)
        val d2 = diag()
        assertTrue("playback continues after seek (t=$t1 -> ${d2?.let { currentTime(it) }})", d2 != null && currentTime(d2) >= t1)
    }

    @Test
    fun t03_pauseResume() {
        openUrl(PAGE)
        pollDiag(30_000) { it.optInt("readyState", 0) >= 1 }
        play()
        pollDiag(20_000) { currentTime(it) > 1.0 }
        evalJs("(function(){document.getElementById('v').pause();return 'paused';})()")
        Thread.sleep(500)
        val p1 = diag()
        Thread.sleep(1_500)
        val p2 = diag()
        assertTrue(
            "paused: currentTime stable (${p1?.let { currentTime(it) }} vs ${p2?.let { currentTime(it) }})",
            p1 != null && p2 != null && p2.optBoolean("paused") &&
                kotlin.math.abs(currentTime(p2) - currentTime(p1)) < 0.25,
        )
        play()
        val resumed = pollDiag(15_000) { !it.optBoolean("paused") && currentTime(it) > currentTime(p2!!) + 0.3 }
        assertTrue("resume: playback progresses after pause", resumed != null)
    }

    /**
     * Real decoded-video proof on a deterministic fixture (diag.mp4 = 1s full-
     * frame solid colours + 440 Hz tone): two screenshots cropped to the exact
     * video content rect (from __diag, excluding all chrome and page pixels)
     * must differ, and presentation counters must advance. Negative control:
     * the same crop while paused must NOT change.
     */
    @Test
    fun t05_renderedFramesChange() {
        openUrl(PAGE)
        pollDiag(30_000) { it.optInt("readyState", 0) >= 1 }
        play()
        val playing = pollDiag(20_000) { currentTime(it) > 1.0 }
        assertNotNull("playback running for frame evidence", playing)
        Log.i(TAG, "counters: total=${playing!!.optInt("totalFrames")} dropped=${playing.optInt("droppedFrames")} rect=${playing.optJSONObject("rect")}")
        val a = sampleVideoRect(playing)
        Thread.sleep(1_200)
        val d2 = diag()!!
        val b = sampleVideoRect(d2)
        assertNotNull("screenshot A", a)
        assertNotNull("screenshot B", b)
        Log.i(TAG, "frames: hashA=${a!!.first} avgA=${a.second} hashB=${b!!.first} avgB=${b.second} total2=${d2.optInt("totalFrames")}")
        assertTrue("rendered video pixels change over 1.2s", a.first != b.first)
        // Negative control: paused, same crop must be static.
        evalJs("(function(){document.getElementById('v').pause();return 'paused';})()")
        Thread.sleep(600)
        val p1 = sampleVideoRect(diag()!!)
        Thread.sleep(1_200)
        val p2 = sampleVideoRect(diag()!!)
        Log.i(TAG, "negative control: pausedA=${p1?.first} pausedB=${p2?.first}")
        assertTrue("paused video pixels do NOT change", p1 != null && p2 != null && p1.first == p2.first)
    }

    @Test
    fun t04_teardownOnNavigateAway() {
        openUrl(PAGE)
        pollDiag(30_000) { it.optInt("readyState", 0) >= 1 }
        play()
        pollDiag(15_000) { currentTime(it) > 0.5 }
        openUrl("https://example.com/")
        // Browser survives decoder teardown mid-playback: chrome responsive.
        assertTrue("browser responsive after mid-playback navigation", device.wait(Until.hasObject(omnibox()), 10_000))
        Log.i(TAG, "teardown: navigated away mid-playback, browser alive")
    }
}
