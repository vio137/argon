package me.ik.argon.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.ik.argon.ArgonColors
import me.ik.argon.ArgonType

/**
 * Native load-failure card shown over the content area when the engine
 * reports a top-level load error (engine patch 020). The engine still
 * renders its own inline error page underneath; this card only describes
 * the failure and offers navigation actions. It does not replace the
 * underlying document and does not change any security state.
 *
 * TLS failures deliberately offer no reload action: re-attempting a
 * certificate failure from the error UI would read as a bypass affordance.
 */
@Composable
fun LoadErrorCard(
    kind: String,
    reason: String,
    onGoBack: () -> Unit,
    onReload: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val (title, body) =
        when (kind) {
            "tls" ->
                "Secure connection failed" to
                    "The connection failed its security checks, so the page was not shown."
            "crash" -> "This page crashed" to "The page stopped while loading."
            else -> "This page couldn't be loaded" to reason.ifBlank { "The connection failed." }
        }
    Box(
        Modifier.fillMaxSize()
            .background(ArgonColors.Bg.copy(alpha = 0.72f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.padding(24.dp)
                .fillMaxWidth()
                .background(ArgonColors.Surface, RoundedCornerShape(20.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                )
                .padding(horizontal = 24.dp, vertical = 22.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, style = ArgonType.Heading, color = ArgonColors.Text)
            Text(body, style = ArgonType.Body, color = ArgonColors.Text2)
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
            ) {
                LoadErrorAction("Go back", onGoBack, accent = false)
                if (onReload != null) {
                    LoadErrorAction(if (kind == "crash") "Reload" else "Retry", onReload, accent = true)
                }
            }
        }
    }
}

@Composable
private fun LoadErrorAction(label: String, onClick: () -> Unit, accent: Boolean) {
    Text(
        label,
        style = ArgonType.Body,
        color = if (accent) ArgonColors.Accent else ArgonColors.Text2,
        modifier =
            Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick,
                )
                .padding(horizontal = 12.dp, vertical = 10.dp),
    )
}
