package me.ik.argon.wax

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        override fun request(
            method: String,
            url: String,
            bearerToken: String?,
            jsonBody: String?,
        ): WaxRegHttpResponse = throw java.net.SocketTimeoutException("simulated")
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

    private val persisted = mutableListOf<WaxAccount>()
    private val persist: (WaxAccount) -> Boolean = { persisted.add(it); true }

    @Test
    fun freshRegistrationSendsTosConsentAndBuildsProfile() {
        val transport = FakeTransport(200 to regResponse())
        val outcome = WaxWarpRegistration(transport).provision(null, persist)

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

        // Account persisted (with the registered private key), and no second call was made.
        assertEquals(1, persisted.size)
        val account = persisted[0]
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
    fun declinedByProviderMeansNoPersistAndNoRetry() {
        val transport = FakeTransport(403 to "error 1020")
        val outcome = WaxWarpRegistration(transport).provision(null, persist)
        assertEquals(WaxRegOutcome.DeclinedByProvider, outcome)
        assertEquals(1, transport.calls.size)
        assertTrue(persisted.isEmpty())
    }

    @Test
    fun unauthorizedMapsToDeclined() {
        val transport = FakeTransport(401 to "")
        assertEquals(WaxRegOutcome.DeclinedByProvider, WaxWarpRegistration(transport).provision(null, persist))
        assertTrue(persisted.isEmpty())
    }

    @Test
    fun serverErrorMapsToRejected() {
        val transport = FakeTransport(500 to "")
        assertEquals(WaxRegOutcome.ProviderRejected, WaxWarpRegistration(transport).provision(null, persist))
        assertTrue(persisted.isEmpty())
    }

    @Test
    fun transportFailureMapsToUnreachable() {
        assertEquals(WaxRegOutcome.Unreachable, WaxWarpRegistration(ThrowingTransport()).provision(null, persist))
        assertTrue(persisted.isEmpty())
    }

    @Test
    fun malformedSuccessIsBadResponse() {
        val transport = FakeTransport(200 to "{\"unexpected\":true}")
        assertEquals(WaxRegOutcome.BadResponse, WaxWarpRegistration(transport).provision(null, persist))
        assertTrue(persisted.isEmpty())
    }

    @Test
    fun storageFailureStopsBeforeProfile() {
        val transport = FakeTransport(200 to regResponse())
        val outcome = WaxWarpRegistration(transport).provision(null) { false }
        assertEquals(WaxRegOutcome.StorageFailed, outcome)
    }

    @Test
    fun existingAccountResumesWithoutDuplicatePost() {
        val existing = WaxAccount.of(accountId, token, license, "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")!!
        val transport = FakeTransport(200 to deviceResponse())
        val outcome = WaxWarpRegistration(transport).provision(existing, persist)

        assertTrue(outcome is WaxRegOutcome.ProfileReady)
        assertEquals(1, transport.calls.size)
        val call = transport.calls[0]
        assertEquals("GET", call.method)
        assertEquals("https://api.cloudflareclient.com/v0a5641/reg/$accountId", call.url)
        assertEquals(token, call.token)
        assertTrue(persisted.isEmpty()) // already stored, not re-persisted
        val profile = (outcome as WaxRegOutcome.ProfileReady).profileText
        assertTrue(profile.contains("PrivateKey = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="))
        assertTrue(profile.contains("Endpoint = 162.159.192.7:2408"))
    }

    @Test
    fun resumeGetDeclinedStopsHonestly() {
        val existing = WaxAccount.of(accountId, token, license, "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")!!
        val transport = FakeTransport(403 to "")
        assertEquals(WaxRegOutcome.DeclinedByProvider, WaxWarpRegistration(transport).provision(existing, persist))
    }

    @Test
    fun endpointWithoutPortUsesDefault() {
        val response =
            "{\"id\":\"$accountId\",\"token\":\"$token\",\"account\":{\"license\":\"$license\"}," +
                "\"config\":{\"interface\":{\"addresses\":{\"v4\":\"172.16.0.2/32\",\"v6\":\"2606:4700:110::1/128\"}}," +
                "\"peers\":[{\"public_key\":\"$peerKey\",\"endpoint\":{\"v4\":\"162.159.193.10\"}}]}}"
        val outcome = WaxWarpRegistration(FakeTransport(200 to response)).provision(null, persist)
        assertTrue(outcome is WaxRegOutcome.ProfileReady)
        assertTrue((outcome as WaxRegOutcome.ProfileReady).profileText.contains("Endpoint = 162.159.193.10:2408"))
    }

    @Test
    fun nonLiteralEndpointIsRejected() {
        val response =
            "{\"id\":\"$accountId\",\"token\":\"$token\",\"account\":{\"license\":\"$license\"}," +
                "\"config\":{\"interface\":{\"addresses\":{\"v4\":\"172.16.0.2\",\"v6\":\"2606:4700:110::1\"}}," +
                "\"peers\":[{\"public_key\":\"$peerKey\",\"endpoint\":{\"v4\":\"engage.cloudflareclient.com\"}}]}}"
        assertEquals(
            WaxRegOutcome.BadResponse,
            WaxWarpRegistration(FakeTransport(200 to response)).provision(null, persist),
        )
    }

    @Test
    fun outcomesNeverLeakSecrets() {
        val transport = FakeTransport(200 to regResponse())
        val outcome = WaxWarpRegistration(transport).provision(null, persist)
        val text = outcome.toString()
        assertFalse(text.contains(token))
        assertFalse(text.contains(license))
        assertFalse(text.contains("PrivateKey"))
    }
}
