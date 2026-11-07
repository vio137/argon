package me.ik.argon

import java.io.File
import org.junit.Test

/** Emits the real shipped start-page markup (StartPage.html) with sample
 *  data so a browser can screenshot the actual template output. */
class StartPageHtmlTest {
    @Test
    fun emitSampleHtml() {
        val html =
            StartPage.html(
                bookmarks =
                    listOf(
                        Bookmark("Servo", "https://servo.org"),
                        Bookmark("GitHub", "https://github.com"),
                        Bookmark("MDN Web Docs", "https://developer.mozilla.org"),
                    ),
                history =
                    listOf(
                        HistoryEntry("https://servo.org/docs", "Servo documentation", 1L),
                        HistoryEntry("https://news.ycombinator.com", "Hacker News", 1L),
                    ),
                engine = SearchEngine.GOOGLE,
            )
        File("build/outputs/startpage-sample.html").apply {
            parentFile.mkdirs()
            writeText(html)
        }
    }
}
