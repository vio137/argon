package me.ik.argon

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.servo.servoview.Servo
import org.servo.servoview.ServoHolder

/**
 * View DOM: renders the live DOM (document.documentElement.outerHTML) of the
 * current page as inert, selectable monospace text. This is the page's current
 * DOM tree, NOT the original wire source - labeled accordingly in the menu.
 * Same bridge discipline as ReaderMode: app-bundled script only, explicit user
 * tap, generation-bound result, bounded size, result never leaves the app.
 */
object ViewDom {
    private const val MAX_DOM_CHARS = 200_000
    private const val TIMEOUT_MS = 10_000L

    private const val SCRIPT =
        "(function(){try{return {dom:String(document.documentElement.outerHTML)};}catch(e){return {error:String(e&&e.message?e.message:e)};}})();"

    fun fetch(scope: CoroutineScope, onResult: (String?, String?) -> Unit) {
        if (!ReaderMode.bridgeAvailable.value) {
            onResult(null, "View DOM isn't available in this build.")
            return
        }
        val servo = ServoHolder.servo
        if (servo == null) {
            onResult(null, "Engine is not ready yet.")
            return
        }
        val generationAtRequest = Servo.navigationGeneration.get()
        var completed = false
        fun finish(dom: String?, error: String?) {
            if (!completed) {
                completed = true
                onResult(dom, error)
            }
        }
        val requestId =
            servo.evaluateScript(SCRIPT) { json, error ->
                if (Servo.navigationGeneration.get() != generationAtRequest) {
                    finish(null, ReaderMode.STALE)
                    return@evaluateScript
                }
                if (error != null) {
                    finish(null, "Couldn't read this page's DOM ($error).")
                    return@evaluateScript
                }
                try {
                    val dom = JSONObject(json).optString("dom")
                    if (dom.isEmpty()) {
                        finish(null, "Couldn't read this page's DOM.")
                    } else {
                        finish(if (dom.length > MAX_DOM_CHARS) dom.substring(0, MAX_DOM_CHARS) else dom, null)
                    }
                } catch (e: Exception) {
                    finish(null, "Couldn't read this page's DOM.")
                }
            }
        if (requestId < 0) {
            onResult(null, "Engine is not ready yet.")
            return
        }
        scope.launch {
            delay(TIMEOUT_MS)
            if (Servo.navigationGeneration.get() == generationAtRequest) {
                finish(null, "Timed out reading the DOM.")
            }
        }
    }
}

@Composable
fun DomPage(dom: String, onBack: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(ArgonColors.Bg)
            .statusBarsPadding(),
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text(
                "View DOM",
                color = ArgonColors.Text,
                fontSize = 17.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
            )
            Text(
                "Current DOM of the page, not the original source",
                color = ArgonColors.Text3,
                fontSize = 12.sp,
            )
        }
        SelectionContainer {
            LazyColumn(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .horizontalScroll(androidx.compose.foundation.rememberScrollState())
                    .padding(horizontal = 20.dp),
            ) {
                item {
                    Text(
                        dom,
                        color = ArgonColors.Text2,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 15.sp,
                    )
                }
            }
        }
        androidx.compose.material3.TextButton(onClick = onBack, modifier = Modifier.padding(12.dp)) {
            Text("Back", color = ArgonColors.Accent)
        }
    }
}
