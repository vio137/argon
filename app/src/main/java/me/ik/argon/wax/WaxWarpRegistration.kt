package me.ik.argon.wax

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64

/** Transport seam: the Android impl uses plain platform HTTPS with honest headers. */
interface WaxRegTransport {
    /**
     * Performs one HTTPS request and returns the provider's status + body. Implementations
     * throw ONLY for transport-level failure (DNS/TLS/timeout), never for HTTP status,
     * and must never log request or response bodies (they contain keys and tokens).
     */
    fun request(method: String, url: String, bearerToken: String?, jsonBody: String?): WaxRegHttpResponse
}

class WaxRegHttpResponse(val status: Int, val body: String)

/** Credential persistence seam (the Keystore-encrypted store in production). */
interface WaxRegStore {
    fun loadAccount(): WaxAccount?
    fun loadPending(): PendingWaxRegistration?

    /** Durable or false. */
    fun savePending(pending: PendingWaxRegistration): Boolean

    /** Durable or false. */
    fun saveAccount(account: WaxAccount): Boolean
    fun clearPending()
}

sealed interface WaxRegOutcome {
    /**
     * Account durably persisted and profile text built. Caller must still validate the
     * profile with the native validator before storing. The account is NOT asserted
     * connect-ready: provider-side activation state is unverified (see class docs).
     */
    class ProfileReady(val profileText: String) : WaxRegOutcome {
        override fun toString() = "ProfileReady(redacted)"
    }

    /** Provider declined this client (HTTP 401/403). Honest stop; no workaround is attempted. */
    data object DeclinedByProvider : WaxRegOutcome

    /** Any other definite HTTP rejection (the server answered; nothing was created). */
    data object ProviderRejected : WaxRegOutcome

    /**
     * The outcome of the registration request is UNKNOWN: the connection failed after
     * the request may have reached the provider, or a success payload was unreadable.
     * An account may exist server-side that cannot be recovered. A pending marker is
     * retained and no retry is made automatically; only the user's explicit fresh-setup
     * choice (which may create a duplicate, disclosed in the UI) clears it.
     */
    data object RegistrationUnknown : WaxRegOutcome

    /** DNS/TLS/timeout before the request could be sent (pending marker predates it). */
    data object Unreachable : WaxRegOutcome

    /**
     * Account IS durably stored but the profile could not be built/fetched from the
     * provider payload. A later setup resumes the same account (no duplicate).
     */
    data object Incomplete : WaxRegOutcome

    /** Private credential storage failed; nothing usable was kept. */
    data object StorageFailed : WaxRegOutcome
}

/**
 * User-driven WARP account provisioning (the "interactive wgcf" surface).
 *
 * Authority model: a network call happens ONLY inside [provision], which the UI invokes
 * exclusively from the registration disclosure dialog's explicit "Agree" action. The
 * registration request itself carries the terms-of-service acceptance timestamp, so that
 * single user tap IS the consent - there is no code path that registers without it, and
 * the agent never runs this code (mocked transports only in tests).
 *
 * Wire shape mirrors the public wgcf/OpenAPI contract
 * (github.com/ViRb3/wgcf master openapi-spec.yml + cloudflare/api.go, retrieved
 * 2026-11-07): POST {base}/{version}/reg -> id/token/account.license/config, and
 * GET {base}/{version}/reg/{id} (Bearer) -> config for resuming an interrupted setup.
 *
 * Duplicate-safety: a pending marker (the generated key, encrypted) is persisted BEFORE
 * the POST; the account is persisted before any later step. An ambiguous POST outcome
 * (throw mid-flight, unreadable 200) returns [WaxRegOutcome.RegistrationUnknown] and
 * retains the marker, and a stored marker blocks any automatic re-POST - the user
 * explicitly chooses a fresh setup, accepting the disclosed possible duplicate.
 *
 * Provider-side activation: the registration response carries a warp_enabled flag and
 * the official client may update it via PATCH; whether a fresh account routes traffic
 * without that step is UNVERIFIED (no live registration is ever performed during
 * development). Outcomes therefore never claim connect-readiness.
 *
 * Fail-closed policy: no TLS or header fingerprint mimicry, no endpoint or API-version
 * cycling to evade a refusal. 401/403 maps to [WaxRegOutcome.DeclinedByProvider].
 */
class WaxWarpRegistration(
    private val transport: WaxRegTransport,
    private val random: SecureRandom = SecureRandom(),
    private val now: () -> Instant = { Instant.now() },
) {
    companion object {
        const val API_BASE = "https://api.cloudflareclient.com"

        /** Single pinned version as observed in wgcf master; never rotated to evade a refusal. */
        const val API_VERSION = "v0a5641"
        private const val DEFAULT_ENDPOINT_PORT = 2408
        private val B64KEY_RE = Regex("[A-Za-z0-9+/]{43}=")
        private val IPV4_RE = Regex("((25[0-5]|2[0-4][0-9]|1?[0-9]?[0-9])\\.){3}(25[0-5]|2[0-4][0-9]|1?[0-9]?[0-9])")
        private val CIDR4_RE = Regex("$IPV4_RE/([0-9]|[12][0-9]|3[0-2])")
        private val CIDR6_RE = Regex("[0-9a-fA-F:]+/([0-9]|[1-9][0-9]|1[0-2][0-8])")
        private val JSON = Json { ignoreUnknownKeys = true }
    }

    fun provision(store: WaxRegStore): WaxRegOutcome {
        store.loadAccount()?.let { account ->
            // Resume an interrupted setup with the SAME account; never a second POST.
            store.clearPending()
            return finishWithExisting(account)
        }
        if (store.loadPending() != null) {
            // An earlier attempt's outcome is unknown; never silently re-POST.
            return WaxRegOutcome.RegistrationUnknown
        }
        return registerFresh(store)
    }

    private fun registerFresh(store: WaxRegStore): WaxRegOutcome {
        val privateKey = WaxCurve25519.privateKey(random)
        try {
            val b64 = Base64.getEncoder()
            val privateB64 = b64.encodeToString(privateKey)
            val pending = PendingWaxRegistration.of(privateB64, now().toString())
                ?: return WaxRegOutcome.StorageFailed
            // Marker must be durable BEFORE the request can have an unknown outcome.
            if (!store.savePending(pending)) return WaxRegOutcome.StorageFailed

            val publicKey = b64.encodeToString(WaxCurve25519.publicFrom(privateKey))
            val serial = ByteArray(6).also { random.nextBytes(it) }
                .joinToString("") { "%02x".format(it) }
            val requestBody = "{" +
                "\"install_id\":\"\"," +
                "\"fcm_token\":\"\"," +
                "\"key\":\"$publicKey\"," +
                "\"type\":\"Android\"," +
                "\"model\":\"Argon\"," +
                "\"locale\":\"en_US\"," +
                "\"serial_number\":\"$serial\"," +
                "\"tos\":\"${now()}\"" +
                "}"
            val response = try {
                transport.request("POST", "$API_BASE/$API_VERSION/reg", null, requestBody)
            } catch (_: Exception) {
                // May or may not have reached the provider; marker retained.
                return WaxRegOutcome.RegistrationUnknown
            }
            when (response.status) {
                200 -> {}
                401, 403 -> {
                    store.clearPending() // definite refusal: nothing was created
                    return WaxRegOutcome.DeclinedByProvider
                }
                else -> {
                    store.clearPending() // definite rejection: nothing was created
                    return WaxRegOutcome.ProviderRejected
                }
            }
            val registered = parseRegistration(response.body)
                ?: return WaxRegOutcome.RegistrationUnknown // success claimed, unreadable
            val created = WaxAccount.of(registered.id, registered.token, registered.license, privateB64)
                ?: return WaxRegOutcome.RegistrationUnknown
            if (!store.saveAccount(created)) return WaxRegOutcome.StorageFailed
            store.clearPending()
            val config = registered.config ?: return finishWithExisting(created)
            val profile = buildProfile(created, config) ?: return WaxRegOutcome.Incomplete
            return WaxRegOutcome.ProfileReady(profile)
        } finally {
            privateKey.fill(0)
        }
    }

    private fun finishWithExisting(account: WaxAccount): WaxRegOutcome {
        val response = try {
            transport.request("GET", "$API_BASE/$API_VERSION/reg/${account.id}", account.token, null)
        } catch (_: Exception) {
            return WaxRegOutcome.Unreachable // account is stored; a later resume is safe
        }
        val config = when (response.status) {
            200 ->
                runCatching {
                    JSON.parseToJsonElement(response.body).asObject()?.get("config")?.asObject()
                }.getOrNull()
                    ?.let { parseConfig(it) }
                    ?: return WaxRegOutcome.Incomplete
            401, 403 -> return WaxRegOutcome.DeclinedByProvider
            else -> return WaxRegOutcome.ProviderRejected
        }
        val profile = buildProfile(account, config) ?: return WaxRegOutcome.Incomplete
        return WaxRegOutcome.ProfileReady(profile)
    }

    private class Registered(val id: String, val token: String, val license: String, val config: JsonObject?)

    private fun parseRegistration(body: String): Registered? = runCatching {
        val root = JSON.parseToJsonElement(body).asObject() ?: return null
        val id = root["id"]?.jsonPrimitive?.content ?: return null
        val token = root["token"]?.jsonPrimitive?.content ?: return null
        val license = root["account"]?.asObject()?.get("license")?.jsonPrimitive?.content ?: return null
        val config = root["config"]?.asObject()?.let { parseConfig(it) }
        Registered(id, token, license, config)
    }.getOrNull()

    /** Keeps only the fields the single-peer profile needs; unknown fields are ignored. */
    private fun parseConfig(config: JsonObject): JsonObject? = runCatching {
        val addresses = config["interface"]?.asObject()?.get("addresses")?.asObject() ?: return null
        val v4 = addresses["v4"]?.jsonPrimitive?.content ?: return null
        val v6 = addresses["v6"]?.jsonPrimitive?.content ?: return null
        val peers = config["peers"]?.jsonArray ?: return null
        val peer = peers.firstOrNull()?.asObject() ?: return null
        val publicKey = peer["public_key"]?.jsonPrimitive?.content ?: return null
        val endpoint = peer["endpoint"]?.asObject() ?: return null
        val endpointV4 = endpoint["v4"]?.jsonPrimitive?.content ?: return null
        val ports = endpoint["ports"]?.jsonArray?.mapNotNull { it.jsonPrimitive.intOrNull }
        kotlinx.serialization.json.buildJsonObject {
            put("v4", kotlinx.serialization.json.JsonPrimitive(v4))
            put("v6", kotlinx.serialization.json.JsonPrimitive(v6))
            put("peer_key", kotlinx.serialization.json.JsonPrimitive(publicKey))
            put("endpoint_v4", kotlinx.serialization.json.JsonPrimitive(endpointV4))
            put("port", kotlinx.serialization.json.JsonPrimitive(ports?.firstOrNull() ?: DEFAULT_ENDPOINT_PORT))
        }
    }.getOrNull()

    private fun kotlinx.serialization.json.JsonElement.asObject(): JsonObject? =
        runCatching { jsonObject }.getOrNull()

    private fun buildProfile(account: WaxAccount, config: JsonObject): String? {
        val v4raw = config["v4"]?.jsonPrimitive?.content ?: return null
        val v6raw = config["v6"]?.jsonPrimitive?.content ?: return null
        val peerKey = config["peer_key"]?.jsonPrimitive?.content ?: return null
        val endpointRaw = config["endpoint_v4"]?.jsonPrimitive?.content ?: return null
        val port = config["port"]?.jsonPrimitive?.intOrNull ?: DEFAULT_ENDPOINT_PORT
        if (port !in 1..65535) return null
        if (!B64KEY_RE.matches(peerKey)) return null
        // Endpoint arrives as "host" or "ip:port"; the native profile requires an IP literal.
        val endpointHost = endpointRaw.substringBefore(':')
        if (!IPV4_RE.matches(endpointHost)) return null
        val address4 = if ('/' in v4raw) v4raw else "$v4raw/32"
        val address6 = if ('/' in v6raw) v6raw else "$v6raw/128"
        if (!CIDR4_RE.matches(address4) || !CIDR6_RE.matches(address6)) return null
        // Confirm the stored private key decodes before embedding it.
        runCatching { Base64.getDecoder().decode(account.privateKey) }.getOrNull() ?: return null
        return buildString {
            append("[Interface]\n")
            append("PrivateKey = ").append(account.privateKey).append('\n')
            append("Address = ").append(address4).append(", ").append(address6).append('\n')
            append("DNS = 1.1.1.1, 1.0.0.1\n")
            append("MTU = 1280\n")
            append('\n')
            append("[Peer]\n")
            append("PublicKey = ").append(peerKey).append('\n')
            append("AllowedIPs = 0.0.0.0/0, ::/0\n")
            append("Endpoint = ").append(endpointHost).append(':').append(port).append('\n')
            append("PersistentKeepalive = 25\n")
        }
    }
}
