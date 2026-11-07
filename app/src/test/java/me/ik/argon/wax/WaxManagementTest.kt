package me.ik.argon.wax

import org.junit.Assert.assertEquals
import org.junit.Test

class WaxManagementTest {
    @Test
    fun createWarpAccountRequiresDisclosure() {
        val state = WaxManagementState()
        assertEquals(
            WaxEffect.SHOW_REGISTRATION_DISCLOSURE,
            WaxManagement.effect(state, WaxAction.CREATE_WARP_ACCOUNT),
        )
    }

    @Test
    fun createWarpAccountWithStoredProfileIsNoOp() {
        val state = WaxManagementState(hasProfile = true, hasAccount = true)
        assertEquals(WaxEffect.NO_OP, WaxManagement.effect(state, WaxAction.CREATE_WARP_ACCOUNT))
    }

    @Test
    fun storedAccountWithoutProfileStillOffersSetup() {
        val state = WaxManagementState(hasAccount = true)
        assertEquals(
            WaxEffect.SHOW_REGISTRATION_DISCLOSURE,
            WaxManagement.effect(state, WaxAction.CREATE_WARP_ACCOUNT),
        )
    }

    @Test
    fun existingGatesUnchanged() {
        // Enabling still requires consent and a profile; connect still requires an
        // enabled process. Registration must not have loosened routing gates.
        val bare = WaxManagementState()
        assertEquals(WaxEffect.SHOW_NOTICE, WaxManagement.effect(bare, WaxAction.REQUEST_ENABLE))
        assertEquals(
            WaxEffect.OPEN_DOCUMENT_PICKER,
            WaxManagement.effect(bare.copy(consentAccepted = true), WaxAction.REQUEST_ENABLE),
        )
        assertEquals(WaxEffect.NO_OP, WaxManagement.effect(bare, WaxAction.CONNECT))
        assertEquals("Off - ordinary browsing", WaxManagement.status(bare))
    }
}
