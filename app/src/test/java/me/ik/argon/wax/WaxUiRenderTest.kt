package me.ik.argon.wax

import android.widget.LinearLayout
import android.widget.TextView
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import org.junit.Rule
import org.junit.Test

/**
 * Host-rendered snapshots (Paparazzi/JVM layoutlib - no device) of the actual waX
 * management body and registration disclosure copy, for visual copy/layout inspection.
 * Design evidence only; not a runtime behavioral test.
 */
class WaxUiRenderTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_6)

    private fun disclosureView(text: String): TextView =
        TextView(paparazzi.context).apply {
            this.text = text
            textSize = 14f
            setPadding(48, 24, 48, 24)
        }

    @Test
    fun managementBodyDefaultOff() {
        val body: LinearLayout =
            FreshWaxManagementDialog.buildBody(paparazzi.context, WaxManagementState()) {}
        paparazzi.snapshot(body, name = "wax_management_off")
    }

    @Test
    fun managementBodyRegisteredUnknown() {
        val body =
            FreshWaxManagementDialog.buildBody(
                paparazzi.context,
                WaxManagementState(hasAccount = false, setupUnknown = true),
            ) {}
        paparazzi.snapshot(body, name = "wax_management_setup_unknown")
    }

    @Test
    fun managementBodyProfileStored() {
        val body =
            FreshWaxManagementDialog.buildBody(
                paparazzi.context,
                WaxManagementState(
                    consentAccepted = true,
                    hasProfile = true,
                    hasAccount = true,
                ),
            ) {}
        paparazzi.snapshot(body, name = "wax_management_profile_stored")
    }

    @Test
    fun disclosureFresh() {
        paparazzi.snapshot(
            disclosureView(FreshWaxManagementDialog.registrationDisclosureText(false, false)),
            name = "wax_registration_disclosure_fresh",
        )
    }

    @Test
    fun disclosureResume() {
        paparazzi.snapshot(
            disclosureView(FreshWaxManagementDialog.registrationDisclosureText(true, false)),
            name = "wax_registration_disclosure_resume",
        )
    }

    @Test
    fun disclosureUnknownOutcome() {
        paparazzi.snapshot(
            disclosureView(FreshWaxManagementDialog.registrationDisclosureText(false, true)),
            name = "wax_registration_disclosure_unknown",
        )
    }
}
