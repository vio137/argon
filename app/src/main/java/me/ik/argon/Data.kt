package me.ik.argon

import android.content.Context
import android.util.AtomicFile
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
    /** A corrupt store could not even be moved aside safely; the original is
     * untouched and all saves for that store are blocked. */
    val corruptPreserveError = androidx.compose.runtime.mutableStateOf(false)
}

/** Result of loading a store: the decoded entries and whether a corrupt
 * original could NOT be quarantined (in which case saves must stay blocked
 * so the original is never overwritten). */
private class LoadedStore<T>(val entries: List<T>, val quarantineFailed: Boolean)

/** Shared background-save machinery for the JSON stores. save() callers hold
 * immutable List snapshots, so queueing is latest-wins: a superseded snapshot
 * is dropped unwritten, and flushSaves() drains the newest snapshot
 * synchronously (called on onPause) so backgrounding never loses queued
 * writes. The write lock also makes flush wait out an in-flight background
 * write. (Perf audit 1.0.17: history.add runs on the main thread from
 * onLoadEnded; JSON-encoding up to 500 entries + file IO mid-navigation
 * janked the page-transition frame.) */
private class SaveQueue {
    private val executor =
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "argon-store-save").apply { isDaemon = true }
        }
    private val writeLock = java.util.concurrent.locks.ReentrantLock()
    private val pending = java.util.concurrent.atomic.AtomicReference<(() -> Unit)?>(null)

    fun enqueue(write: () -> Unit) {
        pending.set(write)
        executor.execute {
            val task = pending.get()
            if (task !== write) return@execute // superseded by a newer snapshot
            pending.compareAndSet(write, null)
            writeLock.lock()
            try {
                write()
            } finally {
                writeLock.unlock()
            }
        }
    }

    fun flush() {
        val task = pending.getAndSet(null)
        writeLock.lock()
        try {
            task?.invoke()
        } finally {
            writeLock.unlock()
        }
    }
}


/** Durable store write via AtomicFile: the previous good bytes are kept in
 * AtomicFile's own backup until finishWrite syncs the replacement, so a
 * crash or failure mid-write leaves the last good store recoverable. The
 * target is never deleted to make room for a save. */
private fun writeStore(file: File, payload: String): Boolean {
    val af = AtomicFile(file)
    var out: java.io.FileOutputStream? = null
    return try {
        out = af.startWrite()
        out.write(payload.toByteArray(Charsets.UTF_8))
        out.flush()
        af.finishWrite(out)
        true
    } catch (t: Throwable) {
        af.failWrite(out)
        StoreHealth.writeError.value = true
        false
    }
}

private inline fun <reified T> loadStore(file: File): LoadedStore<T> {
    // No exists() gate here: AtomicFile.openRead recovers from its own .bak
    // when a crash mid-write left the base missing but the backup present,
    // and that path must not be skipped.
    val bytes = runCatching { AtomicFile(file).openRead().use { it.readBytes() } }.getOrNull()
    if (bytes == null) {
        // Neither base nor backup readable. If a file with content is still
        // sitting there unreadable, treat it as corrupt and quarantine below.
        if (!file.exists() || file.length() == 0L) return LoadedStore(emptyList(), quarantineFailed = false)
    } else if (bytes.isEmpty()) {
        return LoadedStore(emptyList(), quarantineFailed = false)
    }
    val decoded = bytes?.let { runCatching { storeJson.decodeFromString<List<T>>(String(it, Charsets.UTF_8)) }.getOrNull() }
    if (decoded != null) return LoadedStore(decoded, quarantineFailed = false)
    // Corrupt content: move it aside under a UNIQUE name so later saves can
    // never overwrite recoverable data. If quarantine itself fails, the
    // original stays put, saves are blocked, and the user is told.
    var attempt = 0
    while (true) {
        val quarantine =
            File(file.parentFile, "${file.name}.corrupt-${System.currentTimeMillis()}-${attempt}")
        if (quarantine.exists()) { attempt += 1; continue }
        val moved = runCatching { file.renameTo(quarantine) }.getOrDefault(false)
        return if (moved) {
            LoadedStore(emptyList(), quarantineFailed = false)
        } else {
            StoreHealth.corruptPreserveError.value = true
            LoadedStore(emptyList(), quarantineFailed = true)
        }
    }
}

class HistoryStore(context: Context) {
    private val file = File(context.filesDir, "history.json")

    var entries: List<HistoryEntry> = emptyList()
        private set

    /** When true, a corrupt original could not be quarantined; saving is
     * blocked so the original bytes are never overwritten. */
    private var savesBlocked = false

    init {
        val loaded = loadStore<HistoryEntry>(file)
        entries = loaded.entries
        savesBlocked = loaded.quarantineFailed
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

    private val saveQueue = SaveQueue()

    private fun save() {
        if (savesBlocked) {
            // Honest failure semantics: the in-memory change STAYS (the app
            // keeps working for the session), the disk bytes stay untouched,
            // and the error surfaces on every blocked save so the state is
            // never silently divergent.
            StoreHealth.corruptPreserveError.value = true
            return
        }
        val snapshot = entries
        saveQueue.enqueue { writeStore(file, storeJson.encodeToString(snapshot)) }
    }

    /** Drains any queued save synchronously (onPause durability point). */
    fun flushSaves() = saveQueue.flush()
}

class BookmarkStore(context: Context) {
    private val file = File(context.filesDir, "bookmarks.json")

    var entries: List<Bookmark> = emptyList()
        private set

    private var savesBlocked = false

    init {
        // No shipped bookmarks: the start page must only ever show pages
        // the user actually starred (same rule as history).
        val loaded = loadStore<Bookmark>(file)
        entries = loaded.entries
        savesBlocked = loaded.quarantineFailed
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

    private val saveQueue = SaveQueue()

    private fun save() {
        if (savesBlocked) {
            // Honest failure semantics: the in-memory change STAYS (the app
            // keeps working for the session), the disk bytes stay untouched,
            // and the error surfaces on every blocked save so the state is
            // never silently divergent.
            StoreHealth.corruptPreserveError.value = true
            return
        }
        val snapshot = entries
        saveQueue.enqueue { writeStore(file, storeJson.encodeToString(snapshot)) }
    }

    /** Drains any queued save synchronously (onPause durability point). */
    fun flushSaves() = saveQueue.flush()
}
