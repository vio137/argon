package me.ik.argon.wax

import java.security.SecureRandom
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters

/**
 * X25519 (RFC 7748) for WARP device key generation, backed by Bouncy Castle's vetted,
 * maintained low-level implementation (bcprov). Platform JCA XDH would need API 33 and
 * minSdk is 29; a handwritten BigInteger ladder was rejected as not side-channel-vetted.
 * No JCA provider is registered, so platform crypto is untouched.
 *
 * Private keys use the WireGuard convention: 32 random bytes, clamped at use time by
 * the implementation. Host-verified against the RFC 7748 section 5.2 vectors
 * (including the 1- and 1000-iteration base-point checks) as conformance evidence.
 */
object WaxCurve25519 {
    /** 32 random bytes (unclamped, WireGuard convention). Caller must wipe copies. */
    fun privateKey(random: SecureRandom = SecureRandom()): ByteArray =
        X25519PrivateKeyParameters(random).encoded

    fun publicFrom(privateKey: ByteArray): ByteArray =
        X25519PrivateKeyParameters(privateKey, 0).generatePublicKey().encoded

    fun x25519(scalar: ByteArray, u: ByteArray): ByteArray {
        require(scalar.size == 32 && u.size == 32)
        val out = ByteArray(32)
        X25519PrivateKeyParameters(scalar, 0).generateSecret(X25519PublicKeyParameters(u, 0), out, 0)
        return out
    }
}
