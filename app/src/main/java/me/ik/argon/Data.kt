package me.ik.argon

import android.content.Context
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable data class HistoryEntry(val url: String, val title: String, val time: Long)

@Serializable data class Bookmark(val title: String, val url: String)

private val storeJson = Json { ignoreUnknownKeys = true }

/**
 * Set when any store write fails, so the UI can tell the user a change may
 * not have been saved instead of silently reporting success. Compose-visible.
 */
object StoreHealth {
    val writeError = androidx.compose.runtime.mutableStateOf(false)
}

/** Atomic store write: serialize to a sibling temp file, then rename over
 * the target, so a crash mid-write can never leave a truncated store.
 * Returns success; failures are surfaced via [StoreHealth] and never
 * swallowed silently. */
private fun writeStore(file: File, payload: String): Boolean {
    val tmp = File(file.parentFile, file.name + ".tmp")
    return runCatching {
            tmp.writeText(payload)
            if (!tmp.renameTo(file)) {
                // Some filesystems refuse rename-over-existing; fall back to
                // delete+rename, still never writing the target in place.
                file.delete()
                check(tmp.renameTo(file))
            }
        }
        .onFailure { StoreHealth.writeError.value = true }
        .isSuccess
}

/**
 * Load a JSON store. A present-but-undecodable file is RENAMED ASIDE
 * (preserved as <name>.corrupt) instead of being left in place where the
 * next save would silently overwrite potentially recoverable data.
 */
private inline fun <reified T> loadStore(file: File, fallback: List<T>): List<T> {
    val decoded = runCatching { storeJson.decodeFromString<List<T>>(file.readText()) }
    return decoded.getOrElse {
        if (file.exists() && file.length() > 0) {
            runCatching { file.renameTo(File(file.parentFile, file.name + ".corrupt")) }
        }
        fallback
    }
}

class HistoryStore(context: Context) {
    private val file = File(context.filesDir, "history.json")

    var entries: List<HistoryEntry> = emptyList()
        private set

    init {
        entries = loadStore(file, emptyList())
    }

    @Synchronized
    fun add(url: String, title: String) {
        if (url.isBlank() || url == "about:blank") return
        if (entries.firstOrNull()?.url == url) return
        entries = (listOf(HistoryEntry(url, title.ifBlank { url }, System.currentTimeMillis())) + entries).take(500)
        save()
    }

    @Synchronized
    fun remove(entry: HistoryEntry) {
        entries = entries - entry
        save()
    }

    @Synchronized
    fun clear() {
        entries = emptyList()
        save()
    }

    /** Removes every entry visited at or after [sinceMs]; older entries stay. */
    @Synchronized
    fun clearSince(sinceMs: Long) {
        entries = entries.filter { it.time < sinceMs }
        save()
    }

    private fun save() {
        writeStore(file, storeJson.encodeToString(entries))
    }
}

class BookmarkStore(context: Context) {
    private val file = File(context.filesDir, "bookmarks.json")

    var entries: List<Bookmark> = emptyList()
        private set

    init {
        // No shipped bookmarks: the start page must only ever show pages
        // the user actually starred (same rule as history).
        entries = loadStore(file, emptyList())
    }

    @Synchronized
    fun add(title: String, url: String) {
        if (url.isBlank() || entries.any { it.url == url }) return
        entries = entries + Bookmark(title.ifBlank { url }, url)
        save()
    }

    @Synchronized
    fun remove(bookmark: Bookmark) {
        entries = entries - bookmark
        save()
    }

    /** Renames [bookmark]; a blank title keeps the current one. */
    @Synchronized
    fun rename(bookmark: Bookmark, newTitle: String) {
        val title = newTitle.trim()
        if (title.isEmpty()) return
        entries = entries.map { if (it == bookmark) it.copy(title = title) else it }
        save()
    }

    private fun save() {
        writeStore(file, storeJson.encodeToString(entries))
    }
}
