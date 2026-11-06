package me.ik.argon.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.ik.argon.ArgonColors
import me.ik.argon.R

/**
 * Argon command bar - rebuilt chrome (milestone 1).
 * One floating pill: back (contextual), omnibox, trailing action slot.
 * Focus expands the field and crossfades the trailing slot to an accent Go
 * button; loading state is drawn as a travelling accent arc on the border.
 */
@Composable
fun ArgonCommandBar(
    urlState: TextFieldState,
    omniboxText: String,
    currentDisplayUrl: String,
    canGoBack: Boolean,
    isLoading: Boolean,
    focused: Boolean,
    secure: Boolean,
    onFocusChange: (Boolean) -> Unit,
    onSubmit: () -> Unit,
    onBack: () -> Unit,
    onStopOrReload: () -> Unit,
    onMenu: () -> Unit,
    loadingArc: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier =
            modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        AnimatedVisibility(
            visible = canGoBack && !focused,
            enter = slideInHorizontally(spring()) { -it } + fadeIn(),
            exit = slideOutHorizontally(spring()) { -it } + fadeOut(),
        ) {
            ChromeIconButton(onClick = onBack, description = "Back", enabled = true) {
                ChromeGlyph(R.drawable.ic_arrow_back, "Back", ArgonColors.Text)
            }
        }

        // The pill itself: elevation + hairline, accent border when focused.
        val borderAlpha by animateFloatAsState(if (focused) 0.55f else 0f, label = "focusBorder")
        Box(
            modifier =
                Modifier.weight(1f)
                    .height(54.dp)
                    .shadow(14.dp, RoundedCornerShape(27.dp), ambientColor = Color.Black, spotColor = Color.Black)
                    .clip(RoundedCornerShape(27.dp))
                    .background(ArgonColors.SurfaceHigh)
                    .border(
                        1.dp,
                        if (borderAlpha > 0.01f) ArgonColors.Accent.copy(alpha = borderAlpha)
                        else ArgonColors.Hairline,
                        RoundedCornerShape(27.dp),
                    ),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            ) {
                ChromeGlyph(
                    if (secure) R.drawable.ic_lock else R.drawable.ic_search,
                    null,
                    if (secure) ArgonColors.Accent else ArgonColors.Text3,
                )
                Box(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                    if (omniboxText.isEmpty()) {
                        androidx.compose.material3.Text(
                            "Search or enter address",
                            color = ArgonColors.Text3,
                            fontSize = 17.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    BasicTextField(
                        state = urlState,
                        lineLimits = TextFieldLineLimits.SingleLine,
                        textStyle =
                            TextStyle(
                                color = ArgonColors.Text,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Normal,
                            ),
                        cursorBrush = SolidColor(ArgonColors.Accent),
                        keyboardOptions =
                            KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                        onKeyboardAction = { onSubmit() },
                        modifier =
                            Modifier.fillMaxWidth()
                                .onPreviewKeyEvent { event ->
                                    if (event.type == KeyEventType.KeyDown &&
                                        (event.key == Key.Enter || event.key == Key.NumPadEnter)
                                    ) {
                                        onSubmit()
                                        true
                                    } else false
                                }
                                .onFocusChanged { onFocusChange(it.isFocused) },
                    )
                }
                AnimatedVisibility(visible = omniboxText.isNotEmpty() && focused) {
                    ChromeIconButton(
                        onClick = { urlState.edit { replace(0, length, "") } },
                        description = "Clear",
                        enabled = true,
                    ) {
                        ChromeGlyph(R.drawable.ic_close, "Clear", ArgonColors.Text3)
                    }
                }
            }
            androidx.compose.animation.AnimatedVisibility(
                visible = isLoading,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                loadingArc()
            }
        }

        // Trailing slot: Go (focused) vs stop/reload + menu (idle).
        AnimatedContent(
            targetState = focused,
            transitionSpec = {
                (slideInHorizontally(spring()) { it / 2 } + fadeIn()) togetherWith
                    (slideOutHorizontally(spring()) { it / 2 } + fadeOut())
            },
            label = "trailingSlot",
        ) { isFocused ->
            if (isFocused) {
                // Accent-filled Go button - the one action that matters while typing.
                ChromeIconButton(onClick = onSubmit, description = "Go", enabled = true, filled = true) {
                    ChromeGlyph(R.drawable.ic_arrow_forward, "Go", Color(0xFF0B0B10))
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    ChromeIconButton(onClick = onStopOrReload, description = if (isLoading) "Stop" else "Reload", enabled = true) {
                        ChromeGlyph(
                            if (isLoading) R.drawable.ic_close else R.drawable.ic_refresh,
                            if (isLoading) "Stop" else "Reload",
                            ArgonColors.Text,
                        )
                    }
                    ChromeIconButton(onClick = onMenu, description = "Menu", enabled = true) {
                        ChromeGlyph(R.drawable.ic_menu, "Menu", ArgonColors.Text)
                    }
                }
            }
        }
    }
}

@Composable
private fun ChromeIconButton(
    onClick: () -> Unit,
    description: String,
    enabled: Boolean,
    filled: Boolean = false,
    content: @Composable () -> Unit,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier.size(44.dp)
                .clip(CircleShape)
                .background(if (filled) ArgonColors.Accent else Color.Transparent)
                .clickable(enabled = enabled, onClickLabel = description, role = Role.Button, onClick = onClick),
    ) {
        content()
    }
}

@Composable
private fun ChromeGlyph(res: Int, description: String?, tint: Color) {
    androidx.compose.material3.Icon(
        painterResource(res),
        contentDescription = description,
        tint = tint,
        modifier = Modifier.size(20.dp),
    )
}
