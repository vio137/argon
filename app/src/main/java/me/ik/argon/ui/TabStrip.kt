package me.ik.argon.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.ik.argon.ArgonColors

/** Horizontal tab strip: one chip per open tab (title, or host when untitled),
 * close affordance per chip, and a trailing new-tab button. Selection state
 * and per-tab data are owned by MainActivity's TabManager; this composable is
 * pure presentation. */
@Composable
fun ArgonTabStrip(
    tabs: List<Pair<String, String>>,
    activeId: String,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
    onNew: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEach { (id, label) ->
            val selected = id == activeId
            Surface(
                color = if (selected) ArgonColors.SurfaceHigh else ArgonColors.Surface,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.clickable { onSelect(id) },
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 10.dp, top = 6.dp, bottom = 6.dp, end = 2.dp),
                ) {
                    Text(
                        label,
                        color = if (selected) ArgonColors.Text else ArgonColors.Text2,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = 12.sp,
                        modifier = Modifier.widthIn(max = 120.dp),
                    )
                    Text(
                        "\u00d7",
                        color = ArgonColors.Text3,
                        fontSize = 14.sp,
                        modifier = Modifier.clickable { onClose(id) }.padding(horizontal = 8.dp),
                    )
                }
            }
        }
        Text(
            "+",
            color = ArgonColors.Text2,
            fontSize = 16.sp,
            modifier = Modifier.clickable { onNew() }.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}
