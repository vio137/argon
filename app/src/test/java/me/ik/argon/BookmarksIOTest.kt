package me.ik.argon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BookmarksIOTest {

    @Test
    fun roundTripPreservesSpecialCharacters() {
        val entries = listOf(
            Bookmark(title = "Tom & Jerry <best> \"cartoon\"", url = "https://example.com/?a=1&b=2"),
            Bookmark(title = "Plain", url = "https://example.org/path"),
            Bookmark(title = "Literal entity &lt;not-a-tag&gt;", url = "https://example.net/"),
        )
        val imported = BookmarksIO.import(BookmarksIO.export(entries))
        assertEquals(entries, imported)
    }

    @Test
    fun importParsesChromeStyleExport() {
        val chrome = """
            <!DOCTYPE NETSCAPE-Bookmark-file-1>
            <META HTTP-EQUIV="Content-Type" CONTENT="text/html; charset=UTF-8">
            <TITLE>Bookmarks</TITLE>
            <H1>Bookmarks</H1>
            <DL><p>
                <DT><H3 ADD_DATE="1700000000" LAST_MODIFIED="1700000001">Folder</H3>
                <DL><p>
                    <DT><A HREF="https://a.example/" ADD_DATE="1700000002" ICON="data:image/png;base64,xx">Alpha</A>
                    <DT><A HREF="https://b.example/" ADD_DATE="1700000003">Beta &amp; Co</A>
                </DL><p>
            </DL><p>
        """.trimIndent()
        val imported = BookmarksIO.import(chrome)
        assertEquals(
            listOf(
                Bookmark(title = "Alpha", url = "https://a.example/"),
                Bookmark(title = "Beta & Co", url = "https://b.example/"),
            ),
            imported,
        )
    }

    @Test
    fun importSkipsEntriesWithoutUrl() {
        val html = "<DT><A HREF=\"\">empty</A><DT><A HREF=\"https://ok.example/\">ok</A>"
        assertEquals(listOf(Bookmark(title = "ok", url = "https://ok.example/")), BookmarksIO.import(html))
    }

    @Test
    fun exportEscapesAttributeQuotes() {
        val out = BookmarksIO.export(listOf(Bookmark(title = "t", url = "https://e.example/?q=\"quoted\"")))
        assertTrue(out.contains("HREF=\"https://e.example/?q=&quot;quoted&quot;\""))
        assertTrue(out.startsWith("<!DOCTYPE NETSCAPE-Bookmark-file-1>"))
    }

    @Test
    fun exportHasNoRawAmpersandInBody() {
        val out = BookmarksIO.export(listOf(Bookmark(title = "A & B", url = "https://e.example/")))
        val bodyLines = out.lines().filter { it.contains("<DT>") }
        assertTrue(bodyLines.all { !it.contains(Regex("&(?!amp;|lt;|gt;|quot;)")) })
    }

    @Test
    fun importIsCaseInsensitiveAndMultiline() {
        val html = "<dt><a href=\"https://x.example/\">\nmultiline title\n</a>"
        assertEquals(listOf(Bookmark(title = "multiline title", url = "https://x.example/")), BookmarksIO.import(html))
    }
    @Test fun importAllowsWhitespaceAttributeOrderAndSingleQuotes() {
        val html = "<DT>\n <A ADD_DATE='1' HREF = 'https://example.org/?x=1&amp;y=2'>A &#38; B &#x1F600;</A>"
        assertEquals(listOf(Bookmark(title = "A & B 😀", url = "https://example.org/?x=1&y=2")), BookmarksIO.import(html))
    }
    @Test fun numericEntitiesAreSinglePassAndInvalidCodePointsStayLiteral() {
        val html = "<DT><A HREF='https://e.example/'>&#38;lt; &#0; &#xD800; &#x110000; &apos;</A>"
        assertEquals("&lt; &#0; &#xD800; &#x110000; '", BookmarksIO.import(html).single().title)
    }
    @Test(expected = IllegalArgumentException::class) fun oversizedImportIsRejected() {
        BookmarksIO.import("x".repeat(BookmarksIO.MAX_IMPORT_BYTES + 1))
    }
}
