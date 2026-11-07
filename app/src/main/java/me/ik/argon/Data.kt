package me.ik.argon

import android.content.Context
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable data class HistoryEntry(val url: String, val title: String, val time: Long)

@Serializable data class Bookmark(val title: String, val url: String)

private val storeJson = Json { ignoreUnknownKeys = true }

class HistoryStore(context: Context) {
    private val file = File(context.filesDir, "history.json")

    var entries: List<HistoryEntry> = emptyList()
        private set

    init {
        entries =
            runCatching {
                    storeJson.decodeFromString<List<HistoryEntry>>(file.readText())
                }
                .getOrDefault(emptyList())
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
        runCatching { file.writeText(storeJson.encodeToString(entries)) }
    }
}

class BookmarkStore(context: Context) {
    private val file = File(context.filesDir, "bookmarks.json")

    var entries: List<Bookmark> = emptyList()
        private set

    init {
        entries =
            runCatching {
                    storeJson.decodeFromString<List<Bookmark>>(file.readText())
                }
                .getOrDefault(
                    listOf(
                        Bookmark("YouTube", "https://www.youtube.com"),
                        Bookmark("Wikipedia", "https://www.wikipedia.org"),
                        Bookmark("GitHub", "https://github.com"),
                        Bookmark("Reddit", "https://www.reddit.com"),
                    )
                )
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

    private fun save() {
        runCatching { file.writeText(storeJson.encodeToString(entries)) }
    }
}
