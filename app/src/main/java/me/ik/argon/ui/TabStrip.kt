package me.ik.argon.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.ik.argon.ArgonColors
import me.ik.argon.ArgonType

/** Snapshot of the strip's presentation data. @Immutable so ArgonTabStrip is
 * skippable: without a stable wrapper the List<Pair<..>> parameter makes every
 * keystroke-level parent recomposition re-run the whole strip (perf audit
 * 1.0.17). Build once per tab-state change with remember(version). */
@Immutable
data class TabStripData(val chips: List<Pair<String, String>>, val activeId: String)

/** Deterministic per-site tint for monogram chips (redesign v2): a small
 * muted palette keyed off the label so each site keeps a stable hue. Placeholder
 * until the favicon pipeline lands. */
private fun siteTint(label: String): Color {
    val palette =
        listOf(
            Color(0xFF9B8CFF), // argon violet
            Color(0xFF6FB8A5), // teal
            Color(0xFFD8A75B), // amber
            Color(0xFFD97B96), // rose
            Color(0xFF7FA8E0), // sky
            Color(0xFF8FBF6F), // moss
        )
    val h = label.fold(0) { acc, c -> (acc * 31 + c.code) and Int.MAX_VALUE }
    return palette[h % palette.size]
}

/** Horizontal tab strip: one chip per open tab (title, or host when untitled),
 * close affordance per chip, and a trailing new-tab button. Selection state
 * and per-tab data are owned by MainActivity's TabManager; this composable is
 * pure presentation. Redesign v2: hairline chips, active chip carries the
 * accent wash, compact mode shows a per-site tinted monogram. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ArgonTabStrip(
    data: TabStripData,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
    onNew: () -> Unit,
    onLongPress: (String) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    showCloseOnBackground: Boolean = true,
) {
    Row(
        modifier = modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        data.chips.forEach { (id, label) ->
            val selected = id == data.activeId
            Surface(
                color = if (selected) ArgonColors.AccentSoft else ArgonColors.BgRaise,
                shape = RoundedCornerShape(12.dp),
                modifier =
                    Modifier.border(
                            1.dp,
                            if (selected) ArgonColors.Accent.copy(alpha = 0.45f) else ArgonColors.Hairline,
                            RoundedCornerShape(12.dp),
                        )
                        .combinedClickable(
                            onClick = { onSelect(id) },
                            onLongClick = { onLongPress(id) },
                        ),
            ) {
                if (compact) {
                    // Compact density: fixed-size monogram chip, tinted per
                    // site (site monogram placeholder until a favicon pipeline
                    // lands); close only on the active chip so background chips
                    // stay tap targets.
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.height(48.dp)) {
                        Text(
                            label.firstOrNull()?.uppercase() ?: "?",
                            color = if (selected) ArgonColors.Accent else siteTint(label),
                            style = ArgonType.Caption,
                            modifier =
                                Modifier.padding(
                                    start = 16.dp,
                                    end = if (selected) 2.dp else 16.dp,
                                ),
                        )
                    }
                    if (selected) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.size(48.dp).clickable { onClose(id) },
                        ) {
                            Text("×", color = ArgonColors.Text3, fontSize = 15.sp)
                        }
                    }
                } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.height(48.dp).padding(start = 12.dp),
                ) {
                    Text(
                        label,
                        color = if (selected) ArgonColors.Text else ArgonColors.Text2,
                        style = ArgonType.Caption,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 120.dp),
                    )
                    if (selected || showCloseOnBackground) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(48.dp).clickable { onClose(id) },
                    ) {
                        Text("×", color = ArgonColors.Text3, fontSize = 15.sp)
                    }
                    }
                }
                }
            }
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(48.dp).clickable { onNew() },
        ) {
            Text("+", color = ArgonColors.Text2, style = ArgonType.Heading)
        }
    }
}
