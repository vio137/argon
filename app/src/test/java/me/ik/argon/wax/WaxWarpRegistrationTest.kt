package me.ik.argon.wax

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WaxWarpRegistrationTest {
    private class Recorded(val method: String, val url: String, val token: String?, val body: String?)

    private class FakeTransport(vararg val steps: Pair<Int, String>) : WaxRegTransport {
        val calls = mutableListOf<Recorded>()
        var index = 0

        override fun request(
            method: String,
            url: String,
            bearerToken: String?,
            jsonBody: String?,
        ): WaxRegHttpResponse {
            calls.add(Recorded(method, url, bearerToken, jsonBody))
            val step = steps.getOrNull(index++) ?: throw AssertionError("unexpected extra call")
            return WaxRegHttpResponse(step.first, step.second)
        }
    }

    private class ThrowingTransport : WaxRegTransport {
        var calls = 0

        override fun request(
            method: String,
            url: String,
            bearerToken: String?,
            jsonBody: String?,
        ): WaxRegHttpResponse {
            calls++
            throw java.net.SocketTimeoutException("simulated")
        }
    }

    private class MemStore : WaxRegStore {
        var account: WaxAccount? = null
        var pending: PendingWaxRegistration? = null
        var failSavePending = false
        var failSaveAccount = false
        var clearCount = 0

        override fun loadAccount() = account
        override fun loadPending() = pending
        override fun savePending(pending: PendingWaxRegistration): Boolean {
            if (failSavePending) return false
            this.pending = pending
            return true
        }
        override fun saveAccount(account: WaxAccount): Boolean {
            if (failSaveAccount) return false
            this.account = account
            return true
        }
        override fun clearPending() {
            clearCount++
            pending = null
        }
    }

    private val accountId = "11111111-2222-3333-4444-555555555555"
    private val token = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
    private val license = "12abCD34-56efGH78-90ijKL12"
    private val peerKey = "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo="

    private fun configJson() =
        "\"config\":{\"interface\":{\"addresses\":{\"v4\":\"172.16.0.2\",\"v6\":\"2606:4700:110:8949:fed8:2642:a640:c8e1/128\"}}," +
            "\"peers\":[{\"public_key\":\"$peerKey\",\"endpoint\":{\"host\":\"engage.cloudflareclient.com\",\"v4\":\"162.159.192.7:0\",\"v6\":\"[2606:4700:d0::a29f:c007]:0\",\"ports\":[2408,500]}}]}"

    private fun regResponse() =
        "{\"id\":\"$accountId\",\"token\":\"$token\",\"account\":{\"license\":\"$license\",\"account_type\":\"free\"},${configJson()}}"

    private fun deviceResponse() = "{${configJson()}}"

    @Test
    fun freshRegistrationSendsTosConsentAndBuildsProfile() {
        val transport = FakeTransport(200 to regResponse())
        val store = MemStore()
        val outcome = WaxWarpRegistration(transport).provision(store)

        assertTrue(outcome is WaxRegOutcome.ProfileReady)
        assertEquals(1, transport.calls.size)
        val call = transport.calls[0]
        assertEquals("POST", call.method)
        assertEquals("https://api.cloudflareclient.com/v0a5641/reg", call.url)
        assertNull(call.token)
        val body = call.body!!
        assertTrue(body.contains("\"tos\":\"20")) // consent timestamp present
        assertTrue(body.contains("\"type\":\"Android\""))
        assertTrue(body.contains("\"model\":\"Argon\""))
        assertTrue(Regex("\"key\":\"[A-Za-z0-9+/]{43}=\"").containsMatchIn(body))

        // Pending marker was cleared, account persisted, no further call was made.
        assertNull(store.pending)
        assertEquals(1, store.clearCount)
        val account = store.account!!
        assertEquals(accountId, account.id)
        assertEquals(token, account.token)
        assertEquals(license, account.license)

        val profile = (outcome as WaxRegOutcome.ProfileReady).profileText
        val expected =
            "[Interface]\n" +
                "PrivateKey = ${account.privateKey}\n" +
                "Address = 172.16.0.2/32, 2606:4700:110:8949:fed8:2642:a640:c8e1/128\n" +
                "DNS = 1.1.1.1, 1.0.0.1\n" +
                "MTU = 1280\n" +
                "\n" +
                "[Peer]\n" +
                "PublicKey = $peerKey\n" +
                "AllowedIPs = 0.0.0.0/0, ::/0\n" +
                "Endpoint = 162.159.192.7:2408\n" +
                "PersistentKeepalive = 25\n"
        assertEquals(expected, profile)
        // The registered public key must match the stored private key.
        val bodyKey = Regex("\"key\":\"([^\"]+)\"").find(body)!!.groupValues[1]
        val derived =
            java.util.Base64.getEncoder()
                .encodeToString(
                    WaxCurve25519.publicFrom(java.util.Base64.getDecoder().decode(account.privateKey)),
                )
        assertEquals(derived, bodyKey)
    }

    @Test
    fun declinedByProviderClearsPendingAndStops() {
        val transport = FakeTransport(403 to "error 1020")
        val store = MemStore()
        assertEquals(WaxRegOutcome.DeclinedByProvider, WaxWarpRegistration(transport).provision(store))
        assertEquals(1, transport.calls.size)
        assertNull(store.account)
        assertNull(store.pending) // definite refusal: marker cleared
    }

    @Test
    fun serverErrorClearsPending() {
        val transport = FakeTransport(500 to "")
        val store = MemStore()
        assertEquals(WaxRegOutcome.ProviderRejected, WaxWarpRegistration(transport).provision(store))
        assertNull(store.pending)
        assertNull(store.account)
    }

    @Test
    fun ambiguousTransportFailureRetainsPendingAndNeverAutoRetries() {
        val transport = ThrowingTransport()
        val store = MemStore()
        assertEquals(WaxRegOutcome.RegistrationUnknown, WaxWarpRegistration(transport).provision(store))
        assertNotNull(store.pending) // unknown outcome: marker retained
        assertNull(store.account)

        // A later provision with a stored marker MUST NOT send any request.
        val transport2 = ThrowingTransport()
        assertEquals(WaxRegOutcome.RegistrationUnknown, WaxWarpRegistration(transport2).provision(store))
        assertEquals(0, transport2.calls)
    }

    @Test
    fun unreadableSuccessIsUnknownNotSilentDuplicate() {
        val transport = FakeTransport(200 to "{\"unexpected\":true}")
        val store = MemStore()
        assertEquals(WaxRegOutcome.RegistrationUnknown, WaxWarpRegistration(transport).provision(store))
        assertNotNull(store.pending)
        assertNull(store.account)
        // And a subsequent attempt still refuses to auto-POST.
        val transport2 = FakeTransport(200 to regResponse())
        assertEquals(WaxRegOutcome.RegistrationUnknown, WaxWarpRegistration(transport2).provision(store))
        assertEquals(0, transport2.calls.size)
    }

    @Test
    fun savePendingFailureIsStorageFailedWithoutNetwork() {
        val transport = ThrowingTransport()
        val store = MemStore().apply { failSavePending = true }
        assertEquals(WaxRegOutcome.StorageFailed, WaxWarpRegistration(transport).provision(store))
        assertEquals(0, transport.calls)
    }

    @Test
    fun saveAccountFailureRetainsPendingForRecovery() {
        val transport = FakeTransport(200 to regResponse())
        val store = MemStore().apply { failSaveAccount = true }
        assertEquals(WaxRegOutcome.StorageFailed, WaxWarpRegistration(transport).provision(store))
        assertNotNull(store.pending)
        assertNull(store.account)
    }

    @Test
    fun existingAccountResumesWithoutDuplicatePost() {
        val existing = WaxAccount.of(accountId, token, license, "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")!!
        val transport = FakeTransport(200 to deviceResponse())
        val store = MemStore().apply { account = existing }
        val outcome = WaxWarpRegistration(transport).provision(store)

        assertTrue(outcome is WaxRegOutcome.ProfileReady)
        assertEquals(1, transport.calls.size)
        val call = transport.calls[0]
        assertEquals("GET", call.method)
        assertEquals("https://api.cloudflareclient.com/v0a5641/reg/$accountId", call.url)
        assertEquals(token, call.token)
        val profile = (outcome as WaxRegOutcome.ProfileReady).profileText
        assertTrue(profile.contains("PrivateKey = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="))
        assertTrue(profile.contains("Endpoint = 162.159.192.7:2408"))
    }

    @Test
    fun resumeTransportFailureIsSafelyRetryable() {
        val existing = WaxAccount.of(accountId, token, license, "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")!!
        val store = MemStore().apply { account = existing }
        // GET failure does not create accounts, so this is plain Unreachable (resume safe).
        assertEquals(WaxRegOutcome.Unreachable, WaxWarpRegistration(ThrowingTransport()).provision(store))
        assertEquals(existing, store.account)
    }

    @Test
    fun resumeGetDeclinedStopsHonestly() {
        val existing = WaxAccount.of(accountId, token, license, "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")!!
        val store = MemStore().apply { account = existing }
        val transport = FakeTransport(403 to "")
        assertEquals(WaxRegOutcome.DeclinedByProvider, WaxWarpRegistration(transport).provision(store))
    }

    @Test
    fun endpointWithoutPortUsesDefault() {
        val response =
            "{\"id\":\"$accountId\",\"token\":\"$token\",\"account\":{\"license\":\"$license\"}," +
                "\"config\":{\"interface\":{\"addresses\":{\"v4\":\"172.16.0.2/32\",\"v6\":\"2606:4700:110::1/128\"}}," +
                "\"peers\":[{\"public_key\":\"$peerKey\",\"endpoint\":{\"v4\":\"162.159.193.10\"}}]}}"
        val store = MemStore()
        val outcome = WaxWarpRegistration(FakeTransport(200 to response)).provision(store)
        assertTrue(outcome is WaxRegOutcome.ProfileReady)
        assertTrue((outcome as WaxRegOutcome.ProfileReady).profileText.contains("Endpoint = 162.159.193.10:2408"))
    }

    @Test
    fun nonLiteralEndpointLeavesStoredAccountForResume() {
        val response =
            "{\"id\":\"$accountId\",\"token\":\"$token\",\"account\":{\"license\":\"$license\"}," +
                "\"config\":{\"interface\":{\"addresses\":{\"v4\":\"172.16.0.2\",\"v6\":\"2606:4700:110::1\"}}," +
                "\"peers\":[{\"public_key\":\"$peerKey\",\"endpoint\":{\"v4\":\"engage.cloudflareclient.com\"}}]}}"
        val store = MemStore()
        assertEquals(
            WaxRegOutcome.Incomplete,
            WaxWarpRegistration(FakeTransport(200 to response)).provision(store),
        )
        assertNotNull(store.account) // account kept; a later resume retries the config fetch
    }

    @Test
    fun outcomesNeverLeakSecrets() {
        val store = MemStore()
        val outcome = WaxWarpRegistration(FakeTransport(200 to regResponse())).provision(store)
        val text = outcome.toString()
        assertFalse(text.contains(token))
        assertFalse(text.contains(license))
        assertFalse(text.contains("PrivateKey"))
    }
}
