package me.ik.argon

import androidx.compose.foundation.text.input.TextFieldState
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import me.ik.argon.ui.ArgonCommandBar
import org.junit.Rule
import org.junit.Test

/**
 * Local host-rendered screenshots of the redesign v2 chrome (Paparazzi,
 * JVM layoutlib - no device or emulator). Design-verification evidence only;
 * not a runtime behavioral test.
 */
class ChromeScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_6)

    @Test
    fun commandDeckIdle() {
        paparazzi.snapshot {
            ArgonCommandBar(
                urlState = TextFieldState("servo.org/docs"),
                currentDisplayUrl = "servo.org",
                canGoBack = true,
                canGoForward = true,
                isLoading = false,
                focused = false,
                onFocusChange = {},
                onSubmit = {},
                onBack = {},
                onForward = {},
                onStopOrReload = {},
                onMenu = {},
                loadingArc = {},
            )
        }
    }

    @Test
    fun commandDeckFocused() {
        paparazzi.snapshot {
            ArgonCommandBar(
                urlState = TextFieldState("design tokens"),
                currentDisplayUrl = "servo.org",
                canGoBack = true,
                canGoForward = false,
                isLoading = false,
                focused = true,
                onFocusChange = {},
                onSubmit = {},
                onBack = {},
                onForward = {},
                onStopOrReload = {},
                onMenu = {},
                loadingArc = {},
            )
        }
    }
}
