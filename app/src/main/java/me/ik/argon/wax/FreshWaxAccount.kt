package me.ik.argon.wax

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * WARP account credentials created by an explicit in-app user action. Confidential:
 * never logged, never shown in UI, stored only through the Keystore-encrypted store
 * (same WaxSecureProfile mechanism as the profile, separate blob + AAD).
 *
 * [privateKey] is the base64 WireGuard device key registered with this account. It is
 * kept with the account so an interrupted setup can resume WITHOUT creating a duplicate
 * account server-side; it equals the key inside the stored profile once setup completes.
 */
@Serializable
class WaxAccount private constructor(
    val id: String,
    val token: String,
    val license: String,
    val privateKey: String,
) {
    init {
        require(UUID_RE.matches(id)) { "account id shape" }
        require(UUID_RE.matches(token)) { "token shape" }
        require(license.length in 8..64 && license.all { it.isLetterOrDigit() || it == '-' }) { "license shape" }
        require(B64KEY_RE.matches(privateKey)) { "key shape" }
    }

    override fun toString() = "WaxAccount(redacted)"

    companion object {
        private val UUID_RE =
            Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
        private val B64KEY_RE = Regex("[A-Za-z0-9+/]{43}=")

        /** Validated construction; null when the provider payload is malformed. */
        fun of(id: String, token: String, license: String, privateKey: String): WaxAccount? =
            runCatching { WaxAccount(id, token, license, privateKey) }.getOrNull()
    }
}

object WaxAccountCodec {
    private val json = Json { ignoreUnknownKeys = false }

    fun encode(account: WaxAccount): ByteArray =
        json.encodeToString(WaxAccount.serializer(), account).toByteArray(Charsets.UTF_8)

    /** Strict decode: unknown, missing or malformed fields reject. null = absent/invalid. */
    fun decode(bytes: ByteArray): WaxAccount? = runCatching {
        require(bytes.size in 2..4096)
        json.decodeFromString(WaxAccount.serializer(), bytes.toString(Charsets.UTF_8))
    }.getOrNull()
}
