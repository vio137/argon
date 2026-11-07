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

/** Shared background-save machinery for the JSON stores. All physical I/O is
 * worker-owned: no save bytes are ever serialized or written on the caller's
 * thread. Queueing is latest-wins over immutable List snapshots - a
 * superseded snapshot is dropped unwritten (SAFE only because each snapshot
 * is the full current state, so a newer snapshot always covers an older
 * one's changes).
 *
 * flush() never writes. It awaits completion of the newest generation
 * enqueued before the call, bounded by [maxWaitMs], and reports:
 *  - WRITTEN: that generation's write finished AND the store write reported
 *    success (AtomicFile completed without error; this is completion of a
 *    successful write call, not proof of physical media sync).
 *  - FAILED: the write completed but reported failure (store health flag
 *    already raised by the store's write path).
 *  - SUPERSEDED: a newer save enqueued after the flush started covers the
 *    target generation's state; the covering write may still be queued.
 *  - TIMEOUT: the target generation did not complete within the bound; it
 *    remains queued or in flight and a later flush can await it again.
 * Loss window, honestly: anything not yet WRITTEN when the process dies is
 * lost. flush at onPause narrows that window; it cannot close it.
 * (Perf audit 1.0.17: history.add runs on the main thread from onLoadEnded;
 * JSON-encoding up to 500 entries + file IO mid-navigation janked the
 * page-transition frame.) */
internal class SaveQueue {
    internal enum class FlushResult { WRITTEN, FAILED, SUPERSEDED, TIMEOUT, IDLE }

    internal class Save(val write: () -> Boolean) {
        val done = java.util.concurrent.CountDownLatch(1)
        @Volatile var result: FlushResult = FlushResult.IDLE
    }

    private val executor =
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "argon-store-save").apply { isDaemon = true }
        }
    private val pending = java.util.concurrent.atomic.AtomicReference<Save?>(null)
    private val newest = java.util.concurrent.atomic.AtomicReference<Save?>(null)

    fun enqueue(write: () -> Boolean) {
        val save = Save(write)
        pending.set(save)
        newest.set(save)
        executor.execute {
            // Single-thread executor serializes claims and writes; no lock
            // needed because flush() only ever awaits latches, never touches
            // the pending slot or the file.
            if (pending.get() !== save) {
                save.result = FlushResult.SUPERSEDED
                save.done.countDown()
                return@execute
            }
            pending.compareAndSet(save, null)
            val ok = runCatching { save.write() }.getOrDefault(false)
            save.result = if (ok) FlushResult.WRITTEN else FlushResult.FAILED
            save.done.countDown()
        }
    }

    /** Awaits the newest generation enqueued before this call, bounded.
     * Never performs I/O on the calling thread. */
    fun flush(maxWaitMs: Long = 750): FlushResult {
        val target = newest.get() ?: return FlushResult.IDLE
        val finished = target.done.await(maxWaitMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        return if (finished) target.result else FlushResult.TIMEOUT
    }
}


/** Durable store write via AtomicFile: the previous good bytes are kept in
 * AtomicFile's own backup until finishWrite syncs the replacement, so a
 * crash or failure mid-write leaves the last good store recoverable. The
 * target is never deleted to make room for a save. */
/** Compose-visible health flags are written from the main thread only: the
 * background save worker must never poke snapshot state off-main. */
internal fun setStoreHealthFlag(flag: androidx.compose.runtime.MutableState<Boolean>) {
    val main = android.os.Looper.getMainLooper()
    if (main != null && android.os.Looper.myLooper() != main) {
        android.os.Handler(main).post { flag.value = true }
    } else {
        flag.value = true
    }
}

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
        setStoreHealthFlag(StoreHealth.writeError)
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
            setStoreHealthFlag(StoreHealth.corruptPreserveError)
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
            setStoreHealthFlag(StoreHealth.corruptPreserveError)
            return
        }
        val snapshot = entries
        saveQueue.enqueue {
            // The serialization also runs on the worker: the snapshot is
            // captured here on the caller thread, everything after is I/O.
            writeStore(file, storeJson.encodeToString(snapshot))
        }
    }

    /** Best-effort bounded wait for the newest queued save; never writes on
     * the caller thread. See SaveQueue.flush for result semantics. */
    internal fun flushSaves(): SaveQueue.FlushResult = saveQueue.flush()
}

/** Tabs the user or engine closed most recently, for "reopen closed tab".
 * Same durability semantics as HistoryStore: corrupt originals are preserved,
 * never overwritten; in-memory session state keeps working on save failure. */
class RecentlyClosedStore(context: Context) {
    private val file = File(context.filesDir, "recently_closed.json")

    var entries: List<HistoryEntry> = emptyList()
        private set

    private var savesBlocked = false

    init {
        val loaded = loadStore<HistoryEntry>(file)
        entries = loaded.entries
        savesBlocked = loaded.quarantineFailed
    }

    @Synchronized
    fun add(url: String, title: String) {
        if (url.isBlank() || url == "about:blank") return
        entries =
            (listOf(HistoryEntry(url, title.ifBlank { url }, System.currentTimeMillis())) +
                entries.filter { it.url != url })
                .take(25)
        save()
    }

    @Synchronized
    fun remove(entry: HistoryEntry) {
        entries = entries - entry
        save()
    }

    private val saveQueue = SaveQueue()

    private fun save() {
        if (savesBlocked) {
            setStoreHealthFlag(StoreHealth.corruptPreserveError)
            return
        }
        val snapshot = entries
        saveQueue.enqueue { writeStore(file, storeJson.encodeToString(snapshot)) }
    }

    internal fun flushSaves(): SaveQueue.FlushResult = saveQueue.flush()
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
            setStoreHealthFlag(StoreHealth.corruptPreserveError)
            return
        }
        val snapshot = entries
        saveQueue.enqueue {
            // The serialization also runs on the worker: the snapshot is
            // captured here on the caller thread, everything after is I/O.
            writeStore(file, storeJson.encodeToString(snapshot))
        }
    }

    /** Best-effort bounded wait for the newest queued save; never writes on
     * the caller thread. See SaveQueue.flush for result semantics. */
    internal fun flushSaves(): SaveQueue.FlushResult = saveQueue.flush()
}
