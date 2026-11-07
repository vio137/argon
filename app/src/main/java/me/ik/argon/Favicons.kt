package me.ik.argon

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.nio.ByteBuffer

/** Argon (engine patch 055): favicon bitmaps pushed by the engine, kept in a
 * bounded per-webview LRU. Every frame is validated before decode: dimensions
 * must be positive and within [MAX_DIM], and the payload must be exactly
 * width*height*4 bytes (RGBA8); anything else is dropped before allocation.
 * Callers drop frames for tombstoned or never-adopted webviews before they
 * reach this store. Total memory is capped by entry count AND a byte budget
 * so a flood of large icons cannot grow the cache unboundedly. Writes are
 * thread-safe; callbacks may arrive on an engine thread. */
object FaviconStore {
    private const val MAX_DIM = 1024
    private const val MAX_ENTRIES = 64
    private const val MAX_BYTES = 8 * 1024 * 1024

    private val lock = Any()
    private var bytes = 0
    private val entries = LinkedHashMap<String, Bitmap>(16, 0.75f, true)

    /** Bumped on any store change; reading it in a composition subscribes the
     * tab strip to favicon updates. */
    var version by mutableStateOf(0)
        private set

    fun put(webviewId: String, rgba: ByteArray, width: Int, height: Int): Boolean {
        if (webviewId.isEmpty()) return false
        if (width <= 0 || height <= 0 || width > MAX_DIM || height > MAX_DIM) return false
        if (rgba.size.toLong() != width.toLong() * height.toLong() * 4L) return false
        val bmp =
            try {
                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                    // ARGB_8888 is little-endian B,G,R,A in memory, so the
                    // engine's RGBA8 frame needs R<->B swapped per pixel
                    // (Config.RGBA_8888 is gone from the compileSdk stubs;
                    // swap verified by golden pixel inspection).
                    val swizzled = ByteArray(rgba.size)
                    var i = 0
                    while (i < rgba.size) {
                        swizzled[i] = rgba[i + 2]
                        swizzled[i + 1] = rgba[i + 1]
                        swizzled[i + 2] = rgba[i]
                        swizzled[i + 3] = rgba[i + 3]
                        i += 4
                    }
                    it.copyPixelsFromBuffer(ByteBuffer.wrap(swizzled))
                }
            } catch (_: RuntimeException) {
                return false
            }
        synchronized(lock) {
            entries.remove(webviewId)?.let { bytes -= it.byteCount }
            entries[webviewId] = bmp
            bytes += bmp.byteCount
            while (entries.size > MAX_ENTRIES || bytes > MAX_BYTES) {
                val eldest = entries.entries.firstOrNull() ?: break
                bytes -= eldest.value.byteCount
                entries.remove(eldest.key)
            }
        }
        version++
        return true
    }

    fun get(webviewId: String): Bitmap? = synchronized(lock) { entries[webviewId] }

    fun evict(webviewId: String) {
        val removed = synchronized(lock) { entries.remove(webviewId)?.also { bytes -= it.byteCount } }
        if (removed != null) version++
    }
}
