package me.ik.argon

/**
 * Bookmarks import/export in the cross-browser NETSCAPE-Bookmark-file-1
 * format (the HTML format Chrome/Firefox/Edge/Safari exchange). Export is a
 * plain write of a document the user picks; import parses the same format.
 * Imports bookmark anchors after DT, allowing common attribute and whitespace
 * variants; unrelated HTML is ignored. Duplicate URLs are skipped by BookmarkStore.add.
 */
object BookmarksIO {

    fun export(entries: List<Bookmark>): String {
        val out = StringBuilder()
        out.append("<!DOCTYPE NETSCAPE-Bookmark-file-1>\n")
        out.append("<META HTTP-EQUIV=\"Content-Type\" CONTENT=\"text/html; charset=UTF-8\">\n")
        out.append("<TITLE>Bookmarks</TITLE>\n<H1>Bookmarks</H1>\n<DL><p>\n")
        for (b in entries) {
            out.append("    <DT><A HREF=\"").append(escapeAttr(b.url)).append("\">")
                .append(escapeText(b.title)).append("</A>\n")
        }
        out.append("</DL><p>\n")
        return out.toString()
    }

    const val MAX_IMPORT_BYTES = 4 * 1024 * 1024

    fun import(html: String): List<Bookmark> {
        require(html.toByteArray(Charsets.UTF_8).size <= MAX_IMPORT_BYTES) { "Bookmark file is too large" }
        val anchors = Regex("""<DT\s*>\s*<A\b([^>]*)>(.*?)</A\s*>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val href = Regex("""(?:^|\s)HREF\s*=\s*(?:"([^"]*)"|'([^']*)')""", RegexOption.IGNORE_CASE)
        return anchors.findAll(html).mapNotNull { anchor ->
            val match = href.find(anchor.groupValues[1]) ?: return@mapNotNull null
            val url = unescape(match.groupValues[1].ifEmpty { match.groupValues[2] }.trim())
            if (url.isBlank()) null else Bookmark(title = unescape(anchor.groupValues[2].trim()), url = url)
        }.toList()
    }

    private fun escapeText(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun escapeAttr(s: String): String = escapeText(s).replace("\"", "&quot;")

    private fun unescape(s: String): String =
        Regex("""&(#x[0-9a-f]+|#[0-9]+|lt|gt|quot|apos|amp);""", RegexOption.IGNORE_CASE).replace(s) { match ->
            val entity = match.groupValues[1]
            when (entity.lowercase()) {
                "lt" -> "<"
                "gt" -> ">"
                "quot" -> "\""
                "apos" -> "'"
                "amp" -> "&"
                else -> {
                    val code = if (entity.startsWith("#x", true)) entity.drop(2).toIntOrNull(16)
                        else entity.drop(1).toIntOrNull()
                    if (code != null && code in 1..0x10ffff && code !in 0xd800..0xdfff)
                        String(Character.toChars(code)) else match.value
                }
            }
        }
}
