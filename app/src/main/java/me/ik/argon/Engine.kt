package me.ik.argon

enum class SearchEngine(val label: String, val template: String, val searchUrlPrefix: String, val queryParam: String) {
    GOOGLE("Google", "https://www.google.com/search?q=%s", "https://www.google.com/search", "q"),
    DUCKDUCKGO("DuckDuckGo", "https://duckduckgo.com/html/?q=%s", "https://duckduckgo.com/html/", "q"),
    BRAVE("Brave Search", "https://search.brave.com/search?q=%s", "https://search.brave.com/search", "q"),
    BING("Bing", "https://www.bing.com/search?q=%s", "https://www.bing.com/search", "q");

    fun urlFor(query: String): String =
        template.replace("%s", java.net.URLEncoder.encode(query, "UTF-8"))

    companion object {
        fun fromPref(value: String?): SearchEngine = entries.firstOrNull { it.name.lowercase() == value } ?: GOOGLE
    }
}

fun locationFor(input: String, engine: SearchEngine): String {
    val trimmed = input.trim()
    val looksLikeUrl =
        trimmed.matches(Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://.*")) ||
            (!trimmed.contains(' ') && trimmed.contains('.') && trimmed.length > 3)
    val isHttp = trimmed.matches(Regex("^(?i)https?://.+"))
    // Only data: and about: pass through without //; file:, intent:,
    // javascript:, content: and every other scheme are NOT navigable from
    // the omnibox (typed input falls through to search instead).
    val isAllowedPlainScheme = trimmed.matches(Regex("^(?i)(data|about):.+"))
    return when {
        isHttp || isAllowedPlainScheme -> trimmed
        trimmed.matches(Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:.*")) -> engine.urlFor(trimmed)
        looksLikeUrl -> "https://$trimmed"
        else -> engine.urlFor(trimmed)
    }
}

// URLs the omnibox may display. The internal start page is an app-private
// file: document - never show its path; an empty field shows the placeholder.
fun displayableUrl(url: String): String = if (url.startsWith("file://")) "" else url

fun hostOf(url: String): String =
    runCatching { java.net.URI(url).host ?: url }.getOrDefault(url).removePrefix("www.")
