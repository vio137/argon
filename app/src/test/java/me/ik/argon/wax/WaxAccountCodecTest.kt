package me.ik.argon.wax

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class WaxAccountCodecTest {
    private val account =
        WaxAccount.of(
            "11111111-2222-3333-4444-555555555555",
            "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
            "12abCD34-56efGH78-90ijKL12",
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        )!!

    @Test
    fun roundTrip() {
        val decoded = WaxAccountCodec.decode(WaxAccountCodec.encode(account))
        assertNotNull(decoded)
        assertEquals(account.id, decoded!!.id)
        assertEquals(account.token, decoded.token)
        assertEquals(account.license, decoded.license)
        assertEquals(account.privateKey, decoded.privateKey)
    }

    @Test
    fun rejectsUnknownMissingAndMalformed() {
        assertNull(WaxAccountCodec.decode("{}".toByteArray()))
        assertNull(WaxAccountCodec.decode("not json".toByteArray()))
        assertNull(WaxAccountCodec.decode(ByteArray(0)))
        // Unknown extra field: strict codec rejects.
        val withExtra =
            "{\"id\":\"11111111-2222-3333-4444-555555555555\",\"token\":\"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee\"," +
                "\"license\":\"12abCD34-56efGH78-90ijKL12\",\"privateKey\":\"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=\",\"extra\":1}"
        assertNull(WaxAccountCodec.decode(withExtra.toByteArray()))
        // Malformed id shape.
        assertNull(
            WaxAccount.of(
                "not-a-uuid",
                "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
                "12abCD34-56efGH78-90ijKL12",
                "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
            ),
        )
    }

    @Test
    fun toStringRedacts() {
        val text = account.toString()
        assertFalse(text.contains(account.token))
        assertFalse(text.contains(account.license))
        assertFalse(text.contains(account.privateKey))
    }
}
