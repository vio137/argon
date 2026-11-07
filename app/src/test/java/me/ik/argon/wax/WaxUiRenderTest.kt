package me.ik.argon.wax

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import me.ik.argon.ArgonTheme
import me.ik.argon.ArgonColors
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.background
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import org.junit.Rule
import org.junit.Test

/** Host design evidence of the same complete themed content used in production dialogs. */
class WaxUiRenderTest {
    @get:Rule val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_6)
    @Composable private fun Host(content: @Composable () -> Unit) {
        ArgonTheme {
            Box(Modifier.fillMaxSize().background(ArgonColors.Bg), contentAlignment = Alignment.Center) {
                Box(Modifier.fillMaxWidth(.92f)) { content() }
            }
        }
    }
    @Test fun managementBodyDefaultOff() { paparazzi.snapshot { Host { FreshWaxManagementDialog.ManagementContent(WaxManagementState(), {}, {}) } } }
    @Test fun managementBodyRegisteredUnknown() { paparazzi.snapshot { Host { FreshWaxManagementDialog.ManagementContent(WaxManagementState(setupUnknown = true), {}, {}) } } }
    @Test fun managementBodyProfileStored() { paparazzi.snapshot { Host { FreshWaxManagementDialog.ManagementContent(WaxManagementState(consentAccepted = true, hasProfile = true, hasAccount = true), {}, {}) } } }
    @Test fun disclosureFresh() { paparazzi.snapshot { Host { FreshWaxManagementDialog.RegistrationContent(false, false, {}, {}, {}) } } }
    @Test fun disclosureResume() { paparazzi.snapshot { Host { FreshWaxManagementDialog.RegistrationContent(true, false, {}, {}, {}) } } }
    @Test fun disclosureUnknownOutcome() { paparazzi.snapshot { Host { FreshWaxManagementDialog.RegistrationContent(false, true, {}, {}, {}) } } }
}
