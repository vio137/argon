package me.ik.argon

/**
 * Bookmarks import/export in the cross-browser NETSCAPE-Bookmark-file-1
 * format (the HTML format Chrome/Firefox/Edge/Safari exchange). Export is a
 * plain write of a document the user picks; import parses the same format.
 * Parsing is deliberately narrow: <DT><A HREF="...">title</A> entries only,
 * everything else ignored. Duplicate URLs are skipped by BookmarkStore.add.
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

    fun import(html: String): List<Bookmark> {
        val re = Regex(
            "<DT><A\\s+HREF=\"([^\"]*)\"[^>]*>(.*?)</A>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        return re.findAll(html).map { m ->
            Bookmark(title = unescape(m.groupValues[2].trim()), url = unescape(m.groupValues[1].trim()))
        }.filter { it.url.isNotBlank() }.toList()
    }

    private fun escapeText(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun escapeAttr(s: String): String = escapeText(s).replace("\"", "&quot;")

    private fun unescape(s: String): String =
        s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&amp;", "&")
}
