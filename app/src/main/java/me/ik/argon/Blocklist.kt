package me.ik.argon

import android.content.Context
import android.system.Os
import java.io.File
import java.security.MessageDigest

object Blocklist {
    const val DOMAIN_COUNT = 77759
    private const val FILE_NAME = "android_hosts"
    private const val DIGEST_FILE = "$FILE_NAME.sha256"
    private const val LEGACY_MARKER = "$FILE_NAME.version"
    // SHA-256 of the hosts.txt shipped in every build so far (unchanged
    // since the initial commit). A legacy version marker alone cannot prove
    // the bytes on disk are ours - the user may have edited the file - so
    // only this content digest does.
    private const val SHIPPED_DIGEST =
        "9490e167cc4c8c224130bea890ded1040474ecc3814771a78bb1f2b2ac12bc5d"
    private const val EMPTY_FILE = "android_hosts_empty"

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /**
     * Point the engine at the block list. The list is refreshed by content
     * digest, not app version (interim builds share a versionCode), and a
     * file that does not match anything we wrote is treated as a user's own
     * custom list and never overwritten or deleted. Disabling points the
     * engine at an empty file instead of deleting anything.
     */
    fun apply(context: Context, enabled: Boolean) {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        val out = File(dir, FILE_NAME)
        val marker = File(dir, DIGEST_FILE)
        val legacy = File(dir, LEGACY_MARKER)
        if (enabled) {
            val asset = context.assets.open("hosts.txt").readBytes()
            val assetDigest = sha256(asset)
            val recorded = runCatching { marker.readText().trim() }.getOrNull()
            val fileDigest =
                if (out.exists() && out.length() > 0) runCatching { sha256(out.readBytes()) }.getOrNull()
                else null
            val isOurCopy =
                fileDigest != null && (fileDigest == recorded || fileDigest == SHIPPED_DIGEST)
            when {
                fileDigest == assetDigest -> Unit // already current
                fileDigest == null || isOurCopy ->
                    runCatching {
                        out.writeBytes(asset)
                        marker.writeText(assetDigest)
                    }
                else -> Unit // custom list of unknown origin: preserved
            }
            legacy.delete()
            Os.setenv("HOST_FILE", out.absolutePath, true)
        } else {
            // Either way the engine gets an empty list so blocking really
            // turns off; the file itself is only touched when provably ours.
            val recorded = runCatching { marker.readText().trim() }.getOrNull()
            val fileDigest =
                if (out.exists() && out.length() > 0) runCatching { sha256(out.readBytes()) }.getOrNull()
                else null
            // Only remove bytes we can prove are ours (our recorded write or
            // the shipped list). Missing/empty is safe to remove; custom,
            // unknown or UNREADABLE files are always left in place.
            val removable =
                !out.exists() ||
                    (out.length() == 0L && fileDigest == null) ||
                    (fileDigest != null && (fileDigest == recorded || fileDigest == SHIPPED_DIGEST))
            if (removable) {
                out.delete()
                marker.delete()
            }
            legacy.delete()
            val empty = File(dir, EMPTY_FILE)
            runCatching { if (!empty.exists()) empty.writeBytes(ByteArray(0)) }
            Os.setenv("HOST_FILE", empty.absolutePath, true)
        }
    }
}
