package me.ik.argon.wax

import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Host-side coverage of the AES/GCM packet format with an in-memory blob and fixed key. */
class WaxSecureProfileTest {
    private class MemBlob : WaxPrivateBlob {
        var data: ByteArray? = null

        override fun read(limit: Int): ByteArray? = data?.let {
            require(it.size <= limit)
            it.copyOf()
        }

        override fun replace(bytes: ByteArray) {
            data = bytes.copyOf()
        }

        override fun delete() {
            data = null
        }
    }

    private val key = WaxSecretKey { SecretKeySpec(ByteArray(32) { 7 }, "AES") }

    @Test
    fun roundTripAndAbsent() {
        val blob = MemBlob()
        val store = WaxSecureProfile(key, blob)
        assertNull(store.load())
        val content = "profile bytes \u0000 with nul".toByteArray()
        store.save(content)
        assertArrayEquals(content, store.load())
        store.delete()
        assertNull(store.load())
    }

    @Test
    fun labelsAreSeparated() {
        val blob = MemBlob()
        val profileStore = WaxSecureProfile(key, blob) // default profile label
        val accountStore = WaxSecureProfile(key, blob, "Argon.waX.fresh-account.v1")
        profileStore.save("secret".toByteArray())
        // Same blob and key but a different purpose label must NOT decrypt.
        var failed = false
        try {
            accountStore.load()
        } catch (_: Exception) {
            failed = true
        }
        assertTrue(failed)
    }

    @Test
    fun tamperIsDetected() {
        val blob = MemBlob()
        val store = WaxSecureProfile(key, blob)
        store.save("integrity".toByteArray())
        blob.data!!.let { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        var failed = false
        try {
            store.load()
        } catch (_: Exception) {
            failed = true
        }
        assertTrue(failed)
    }
}
