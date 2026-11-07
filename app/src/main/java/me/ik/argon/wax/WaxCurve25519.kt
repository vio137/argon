package me.ik.argon.wax

import java.math.BigInteger
import java.security.SecureRandom

/**
 * X25519 (RFC 7748) for WARP device key generation.
 *
 * Montgomery ladder over BigInteger: the ladder operation SEQUENCE is fixed, but
 * BigInteger operand timing is data-dependent, so this is NOT constant-time. It is
 * used exactly once per account (keypair generation on the user's own device), never
 * per packet. Verified on the host against the RFC 7748 section 5.2 test vectors
 * (including the 1- and 1000-iteration base-point checks).
 *
 * Private keys are stored in the standard WireGuard form: 32 random bytes, clamped
 * at use time inside [x25519], matching wireguard-go/wgcf.
 */
object WaxCurve25519 {
    private val P = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))
    private val A24 = BigInteger.valueOf(121665)
    private val BASE_U = ByteArray(32).apply { this[0] = 9 }

    private fun decodeScalar(b: ByteArray): BigInteger {
        require(b.size == 32)
        val k = b.copyOf()
        k[0] = (k[0].toInt() and 248).toByte()
        k[31] = ((k[31].toInt() and 127) or 64).toByte()
        k.reverse()
        return BigInteger(1, k)
    }

    private fun decodeU(b: ByteArray): BigInteger {
        require(b.size == 32)
        val u = b.copyOf()
        u[31] = (u[31].toInt() and 127).toByte()
        u.reverse()
        return BigInteger(1, u)
    }

    private fun encodeU(x: BigInteger): ByteArray {
        val be = x.mod(P).toByteArray() // big-endian, possibly with sign byte
        val out = ByteArray(32)
        for (i in be.indices) {
            val target = be.size - 1 - i
            if (target < 32) out[target] = be[i]
        }
        return out
    }

    fun x25519(scalar: ByteArray, u: ByteArray): ByteArray {
        val k = decodeScalar(scalar)
        val x1 = decodeU(u)
        var x2 = BigInteger.ONE
        var z2 = BigInteger.ZERO
        var x3 = x1
        var z3 = BigInteger.ONE
        var swap = 0
        for (t in 254 downTo 0) {
            val kt = if (k.testBit(t)) 1 else 0
            swap = swap xor kt
            if (swap == 1) {
                val tx = x2; x2 = x3; x3 = tx
                val tz = z2; z2 = z3; z3 = tz
            }
            swap = kt
            val a = x2.add(z2).mod(P)
            val aa = a.multiply(a).mod(P)
            val b = x2.subtract(z2).mod(P)
            val bb = b.multiply(b).mod(P)
            val e = aa.subtract(bb).mod(P)
            val c = x3.add(z3).mod(P)
            val d = x3.subtract(z3).mod(P)
            val da = d.multiply(a).mod(P)
            val cb = c.multiply(b).mod(P)
            val daPlusCb = da.add(cb).mod(P)
            val daMinusCb = da.subtract(cb).mod(P)
            x3 = daPlusCb.multiply(daPlusCb).mod(P)
            z3 = x1.multiply(daMinusCb.multiply(daMinusCb).mod(P)).mod(P)
            x2 = aa.multiply(bb).mod(P)
            z2 = e.multiply(aa.add(A24.multiply(e)).mod(P)).mod(P)
        }
        if (swap == 1) {
            val tx = x2; x2 = x3; x3 = tx
            val tz = z2; z2 = z3; z3 = tz
        }
        return encodeU(x2.multiply(z2.modInverse(P)).mod(P))
    }

    /** 32 random bytes (unclamped, WireGuard convention). Caller must wipe copies. */
    fun privateKey(random: SecureRandom = SecureRandom()): ByteArray {
        val k = ByteArray(32)
        random.nextBytes(k)
        return k
    }

    fun publicFrom(privateKey: ByteArray): ByteArray = x25519(privateKey, BASE_U)
}
