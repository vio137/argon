package me.ik.argon

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import org.servo.servoview.ServoHolder

data class ReaderArticle(
    val title: String,
    val byline: String,
    val paragraphs: List<String>,
)

/**
 * Reader mode: Mozilla Readability (bundled, Apache-2.0) runs inside the page
 * via the engine's evaluate_javascript bridge and returns title/byline/plain
 * text as JSON. Only app-bundled source is evaluated, only on an explicit user
 * tap, and the result never leaves the app.
 */
object ReaderMode {
    @Volatile private var cachedSource: String? = null

    private fun source(context: Context): String =
        cachedSource
            ?: run {
                val lib =
                    context.assets.open("readability.js").bufferedReader().use { it.readText() }
                val wrapped =
                    lib +
                        """
                        ;
                        (function() {
                          try {
                            if (typeof Readability !== "function") return {"error":"library"};
                            var doc = document.cloneNode(true);
                            var article = new Readability(doc).parse();
                            if (!article || !article.textContent) return {"error":"no_article"};
                            return {"title": String(article.title || document.title || ""),
                                    "byline": String(article.byline || ""),
                                    "text": String(article.textContent).replace(/\r/g, "")};
                          } catch (e) {
                            return {"error": "exception: " + (e && e.message ? e.message : String(e))};
                          }
                        })();
                        """.trimIndent()
                cachedSource = wrapped
                wrapped
            }

    fun evaluate(context: Context, onResult: (ReaderArticle?, String?) -> Unit) {
        val servo = ServoHolder.servo
        if (servo == null) {
            onResult(null, "Engine is not ready yet.")
            return
        }
        servo.evaluateScript(source(context)) { json, error ->
            if (error != null) {
                onResult(null, "Couldn't read this page ($error).")
                return@evaluateScript
            }
            if (json == null) {
                onResult(null, "Couldn't read this page.")
                return@evaluateScript
            }
            try {
                val obj = JSONObject(json)
                if (obj.has("error")) {
                    onResult(
                        null,
                        if (obj.optString("error") == "no_article") {
                            "This page doesn't look like an article."
                        } else {
                            "Reader mode failed on this page."
                        },
                    )
                    return@evaluateScript
                }
                val paragraphs =
                    obj.optString("text")
                        .split('\n')
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                if (paragraphs.size < 2) {
                    onResult(null, "This page doesn't look like an article.")
                    return@evaluateScript
                }
                onResult(
                    ReaderArticle(
                        title = obj.optString("title").trim().ifEmpty { "Reader view" },
                        byline = obj.optString("byline").trim(),
                        paragraphs = paragraphs,
                    ),
                    null,
                )
            } catch (e: Exception) {
                onResult(null, "Couldn't parse the article.")
            }
        }
    }
}

@Composable
fun ReaderOverlay(article: ReaderArticle, onClose: () -> Unit) {
    Column(
        Modifier.fillMaxSize()
            .background(ArgonColors.Surface)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        ) {
            Text(
                "Reader view",
                color = ArgonColors.Text3,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onClose) {
                Icon(
                    painterResource(R.drawable.ic_close),
                    contentDescription = "Close reader view",
                    tint = ArgonColors.Text,
                )
            }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                Column {
                    Text(
                        article.title,
                        color = ArgonColors.Text,
                        fontSize = 25.sp,
                        fontWeight = FontWeight.SemiBold,
                        lineHeight = 31.sp,
                    )
                    if (article.byline.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text(article.byline, color = ArgonColors.Text3, fontSize = 14.sp)
                    }
                    Spacer(Modifier.height(10.dp))
                    Box(Modifier.fillMaxWidth().height(1.dp).background(ArgonColors.Hairline))
                    Spacer(Modifier.height(6.dp))
                }
            }
            items(article.paragraphs) { paragraph ->
                Text(
                    paragraph,
                    color = ArgonColors.Text,
                    fontSize = 17.sp,
                    lineHeight = 27.sp,
                )
            }
            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}
