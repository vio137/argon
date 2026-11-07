package me.ik.argon

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Omnibox suggestions - LOCAL ONLY. Matches come from the device's own
 * history and bookmark lists; no keystroke ever leaves the device to a
 * suggestion service. Ranking: bookmarks before history, host-prefix matches
 * before plain substring, most recent first within a tier, duplicates removed
 * (a bookmarked URL shows once, as a bookmark).
 */
private data class Match(val title: String, val url: String, val bookmark: Boolean, val hostPrefix: Boolean)

private fun suggest(query: String, history: List<HistoryEntry>, bookmarks: List<Bookmark>, limit: Int = 5): List<Match> {
    val q = query.trim().lowercase()
    if (q.length < 2) return emptyList()
    val seen = HashSet<String>()
    val matches = ArrayList<Match>()
    // Only web pages are suggested: file:// (the start page), data:, about:
    // and every non-web scheme stay out of the omnibox.
    val webHistory = history.filter { it.url.startsWith("https://") || it.url.startsWith("http://") }
    val webBookmarks = bookmarks.filter { it.url.startsWith("https://") || it.url.startsWith("http://") }
    for (b in webBookmarks.asReversed()) {
        val host = hostOf(b.url).lowercase()
        val title = b.title.lowercase()
        if ((host.contains(q) || title.contains(q)) && seen.add(b.url)) {
            matches.add(Match(b.title.ifBlank { hostOf(b.url) }, b.url, bookmark = true, hostPrefix = host.startsWith(q)))
        }
    }
    // History is stored newest-first; keep that order so the stable sort
    // leaves the most recent visit first inside a tier. (asReversed() here
    // used to invert the tier into oldest-first.)
    for (h in webHistory) {
        val host = hostOf(h.url).lowercase()
        val title = h.title.lowercase()
        if ((host.contains(q) || title.contains(q)) && seen.add(h.url)) {
            matches.add(Match(h.title.ifBlank { hostOf(h.url) }, h.url, bookmark = false, hostPrefix = host.startsWith(q)))
        }
    }
    return matches.sortedWith(compareByDescending<Match> { it.bookmark }.thenByDescending { it.hostPrefix }).take(limit)
}

@Composable
fun OmniboxSuggestions(
    query: String,
    engine: SearchEngine,
    history: List<HistoryEntry>,
    bookmarks: List<Bookmark>,
    onSearch: () -> Unit,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Recompute only when the query or a store snapshot changes; other
    // recompositions (row ripple, parent pass) reuse the last result.
    // (Perf audit 1.0.17.)
    val matches = androidx.compose.runtime.remember(query, history, bookmarks) { suggest(query, history, bookmarks) }
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(ArgonRadii.Panel))
                .background(ArgonColors.Surface)
                .border(1.dp, ArgonColors.Hairline, RoundedCornerShape(ArgonRadii.Panel))
                .padding(vertical = 4.dp),
    ) {
        SuggestionRow(
            icon = R.drawable.ic_search,
            title = "Search ${engine.label}",
            subtitle = query.trim(),
            onClick = onSearch,
        )
        matches.forEach { m ->
            Box(Modifier.padding(start = 52.dp).fillMaxWidth().height(1.dp).background(ArgonColors.Hairline))
            SuggestionRow(
                icon = if (m.bookmark) R.drawable.ic_star else R.drawable.ic_history,
                title = m.title,
                subtitle = hostOf(m.url),
                onClick = { onPick(m.url) },
            )
        }
    }
}

@Composable
private fun SuggestionRow(icon: Int, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier.fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = ArgonColors.Text3, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = ArgonColors.Text, style = ArgonType.BodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = ArgonColors.Text3, style = ArgonType.Caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
