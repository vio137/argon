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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.ik.argon.ArgonColors
import me.ik.argon.ArgonRadii
import me.ik.argon.ArgonType
import me.ik.argon.R

/**
 * Argon command deck - redesign v2 ("Argon discharge").
 * One unified bottom deck: nav buttons, the omnibox pill and the action slot
 * live inside a single continuous glass surface. Idle: back (long-press
 * trail) + forward, omnibox pill (site/search glyph, address, clear),
 * stop-or-reload, menu. Focused: nav buttons collapse, the pill takes the
 * space and the trailing slot crossfades to an accent Go button. The loading
 * state is a thin accent track along the deck's bottom edge.
 *
 * Compose cannot backdrop-blur the webview behind the deck, so the glass is
 * a high-alpha raised surface over the page; the hairline border carries the
 * edge definition. The leading glyph is a neutral site icon: the engine
 * does not expose verified transport state to the embedder, so the UI never
 * implies certificate validation; it becomes a search glyph while editing.
 * Touch targets stay >=44dp; every action keeps its content-description for
 * tests and accessibility.
 */
@Composable
fun ArgonCommandBar(
    urlState: TextFieldState,
    currentDisplayUrl: String,
    canGoBack: Boolean,
    canGoForward: Boolean,
    isLoading: Boolean,
    focused: Boolean,
    onFocusChange: (Boolean) -> Unit,
    onSubmit: () -> Unit,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onStopOrReload: () -> Unit,
    onMenu: () -> Unit,
    onLeadingShortcut: (() -> Unit)? = null,
    onEscape: () -> Unit = {},
    recentHistory: List<Pair<String, String>> = emptyList(),
    onPickHistory: (String) -> Unit = {},
    loadingArc: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier =
            modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .height(56.dp)
                .clip(RoundedCornerShape(ArgonRadii.Panel))
                .background(ArgonColors.BgRaise.copy(alpha = 0.94f))
                .border(1.dp, ArgonColors.HairlineStrong, RoundedCornerShape(ArgonRadii.Panel)),
    ) {
        // Adaptive chrome: below a comfortable width the forward button folds
        // away so the omnibox pill keeps meaningful address text (review:
        // at small-phone width with 1.3x font, five controls leave the pill
        // only a couple of characters).
        val roomy = maxWidth >= 380.dp
        val showForward = canGoForward && roomy
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxSize().padding(horizontal = 6.dp),
        ) {
            // Nav buttons: hidden on the start surface (nothing to navigate)
            // and collapsed while editing so the pill gets the width.
            AnimatedVisibility(
                visible = (canGoBack || showForward) && !focused,
                enter = slideInHorizontally(spring()) { -it } + fadeIn(),
                exit = slideOutHorizontally(spring()) { -it } + fadeOut(),
            ) {
                Row {
                    Box {
                        var showRecent by remember { mutableStateOf(false) }
                        ChromeIconButton(
                            onClick = onBack,
                            onLongClick =
                                if (recentHistory.isEmpty()) null
                                else ({ showRecent = true }),
                            description = "Back",
                            enabled = canGoBack,
                        ) {
                            ChromeGlyph(
                                R.drawable.ic_arrow_back,
                                "Back",
                                if (canGoBack) ArgonColors.Text else ArgonColors.Text3,
                            )
                        }
                        DropdownMenu(
                            expanded = showRecent,
                            onDismissRequest = { showRecent = false },
                            containerColor = ArgonColors.SurfaceHigh,
                        ) {
                            recentHistory.forEach { (title, url) ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            title,
                                            color = ArgonColors.Text,
                                            style = ArgonType.BodySmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    },
                                    onClick = {
                                        showRecent = false
                                        onPickHistory(url)
                                    },
                                )
                            }
                        }
                    }
                    if (showForward) {
                        ChromeIconButton(
                            onClick = onForward,
                            description = "Forward",
                            enabled = true,
                        ) {
                            ChromeGlyph(R.drawable.ic_arrow_forward, "Forward", ArgonColors.Text)
                        }
                    }
                }
            }

            // Omnibox pill inside the deck.
            val pillBorderAlpha by animateFloatAsState(if (focused) 0.55f else 0f, label = "focusBorder")
            Box(
                Modifier.weight(1f)
                    .height(44.dp)
                    .clip(RoundedCornerShape(ArgonRadii.Control))
                    .background(ArgonColors.SurfaceHigh)
                    .border(
                        1.dp,
                        if (pillBorderAlpha > 0.01f) ArgonColors.Accent.copy(alpha = pillBorderAlpha)
                        else ArgonColors.Hairline,
                        RoundedCornerShape(ArgonRadii.Control),
                    ),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxSize().padding(start = 12.dp, end = 4.dp),
                ) {
                    if (onLeadingShortcut != null) {
                        ChromeIconButton(
                            onClick = onLeadingShortcut,
                            description = "Shortcut",
                            enabled = true,
                            size = 32.dp,
                            glyph = 18.dp,
                        ) {
                            ChromeGlyph(leadingGlyphRes(focused), "Shortcut", ArgonColors.Text3)
                        }
                    } else {
                        ChromeGlyph(leadingGlyphRes(focused), null, ArgonColors.Text3)
                    }
                    Box(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                        if (urlState.text.isEmpty()) {
                            androidx.compose.material3.Text(
                                "Search or enter address",
                                color = ArgonColors.Text3,
                                style = ArgonType.BodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        BasicTextField(
                            state = urlState,
                            lineLimits = TextFieldLineLimits.SingleLine,
                            textStyle = ArgonType.BodySmall.copy(color = ArgonColors.Text),
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
                                        } else if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                                            onEscape()
                                            true
                                        } else false
                                    }
                                    .onFocusChanged { onFocusChange(it.isFocused) },
                        )
                    }
                    AnimatedVisibility(visible = urlState.text.isNotEmpty() && focused) {
                        ChromeIconButton(
                            onClick = { urlState.edit { replace(0, length, "") } },
                            description = "Clear",
                            enabled = true,
                            size = 32.dp,
                            glyph = 18.dp,
                        ) {
                            ChromeGlyph(R.drawable.ic_close, "Clear", ArgonColors.Text3)
                        }
                    }
                }
            }

            // Trailing slot, inside the deck: Go (editing) vs
            // stop/reload + menu (idle).
            AnimatedContent(
                targetState = focused,
                transitionSpec = {
                    (slideInHorizontally(spring()) { it / 2 } + fadeIn()) togetherWith
                        (slideOutHorizontally(spring()) { it / 2 } + fadeOut())
                },
                label = "trailingSlot",
            ) { isFocused ->
                if (isFocused) {
                    ChromeIconButton(onClick = onSubmit, description = "Go", enabled = true, filled = true) {
                        ChromeGlyph(R.drawable.ic_arrow_forward, "Go", ArgonColors.OnAccent)
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
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
        // Loading: thin accent track along the deck's bottom edge.
        androidx.compose.animation.AnimatedVisibility(
            visible = isLoading,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            loadingArc()
        }
    }
}

private fun leadingGlyphRes(focused: Boolean): Int =
    if (focused) R.drawable.ic_search else R.drawable.ic_globe

@Composable
private fun ChromeIconButton(
    onClick: () -> Unit,
    description: String,
    enabled: Boolean,
    filled: Boolean = false,
    size: androidx.compose.ui.unit.Dp = 48.dp,
    glyph: androidx.compose.ui.unit.Dp = 20.dp,
    onLongClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier.size(size)
                .clip(CircleShape)
                .background(if (filled) ArgonColors.Accent else Color.Transparent)
                .then(
                    if (onLongClick != null) {
                        @OptIn(ExperimentalFoundationApi::class)
                        Modifier.combinedClickable(
                            enabled = enabled,
                            onClickLabel = description,
                            role = Role.Button,
                            onClick = onClick,
                            onLongClick = onLongClick,
                        )
                    } else {
                        Modifier.clickable(enabled = enabled, onClickLabel = description, role = Role.Button, onClick = onClick)
                    }
                ),
    ) {
        Box(Modifier.size(glyph), contentAlignment = Alignment.Center) { content() }
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
