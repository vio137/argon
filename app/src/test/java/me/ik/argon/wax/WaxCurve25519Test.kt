package me.ik.argon.wax

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * RFC 7748 section 5.2 vectors (rfc-editor.org/rfc/rfc7748.txt, retrieved 2026-11-07).
 */
class WaxCurve25519Test {
    private fun hex(s: String): ByteArray =
        ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    @Test
    fun rfcVector1() {
        val out =
            WaxCurve25519.x25519(
                hex("a546e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449ac4"),
                hex("e6db6867583030db3594c1a424b15f7c726624ec26b3353b10a903a6d0ab1c4c"),
            )
        assertEquals(
            "c3da55379de9c6908e94ea4df28d084f32eccf03491c71f754b4075577a28552",
            out.joinToString("") { "%02x".format(it) },
        )
    }

    @Test
    fun rfcVector2() {
        val out =
            WaxCurve25519.x25519(
                hex("4b66e9d4d1b4673c5ad22691957d6af5c11b6421e0ea01d42ca4169e7918ba0d"),
                hex("e5210f12786811d3f4b7959d0538ae2c31dbe7106fc03c3efc4cd549c715a493"),
            )
        assertEquals(
            "95cbde9476e8907d7aade45cb4b873f88b595a68799fa152e6f8f7647aac7957",
            out.joinToString("") { "%02x".format(it) },
        )
    }

    @Test
    fun rfcIteratedBasePoint() {
        var k = ByteArray(32).apply { this[0] = 9 }
        var u = ByteArray(32).apply { this[0] = 9 }
        repeat(1) {
            val t = k
            k = WaxCurve25519.x25519(k, u)
            u = t
        }
        assertEquals(
            "422c8e7a6227d7bca1350b3e2bb7279f7897b87bb6854b783c60e80311ae3079",
            k.joinToString("") { "%02x".format(it) },
        )
        repeat(999) {
            val t = k
            k = WaxCurve25519.x25519(k, u)
            u = t
        }
        assertEquals(
            "684cf59ba83309552800ef566f2f4d3c1c3887c49360e3875f2eb94d99532c51",
            k.joinToString("") { "%02x".format(it) },
        )
    }

    @Test
    fun keygenShapeAndConsistency() {
        val priv = WaxCurve25519.privateKey()
        assertEquals(32, priv.size)
        val pub1 = WaxCurve25519.publicFrom(priv)
        val pub2 = WaxCurve25519.publicFrom(priv)
        assertEquals(32, pub1.size)
        assertArrayEquals(pub1, pub2)
        assertNotEquals(pub1.joinToString(""), ByteArray(32).joinToString(""))
        // Distinct keys give distinct public keys.
        val other = WaxCurve25519.privateKey()
        assertNotEquals(
            WaxCurve25519.publicFrom(priv).joinToString(""),
            WaxCurve25519.publicFrom(other).joinToString(""),
        )
    }

    @Test
    fun ecdhAgreement() {
        val a = WaxCurve25519.privateKey()
        val b = WaxCurve25519.privateKey()
        val sharedA = WaxCurve25519.x25519(a, WaxCurve25519.publicFrom(b))
        val sharedB = WaxCurve25519.x25519(b, WaxCurve25519.publicFrom(a))
        assertArrayEquals(sharedA, sharedB)
    }
}
