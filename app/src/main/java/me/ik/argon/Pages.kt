package me.ik.argon

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import java.util.Calendar
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
private val dayFormat = SimpleDateFormat("EEE, MMM d", Locale.getDefault())
private val dayYearFormat = SimpleDateFormat("EEE, MMM d, yyyy", Locale.getDefault())

/** Start of the local day containing [time], used as the grouping key. */
private fun dayStartMs(time: Long): Long {
    val cal = Calendar.getInstance()
    cal.timeInMillis = time
    cal.set(Calendar.HOUR_OF_DAY, 0)
    cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    return cal.timeInMillis
}

private fun dayLabel(dayStart: Long, now: Long = System.currentTimeMillis()): String {
    val today = dayStartMs(now)
    return when (dayStart) {
        today -> "Today"
        dayStartMs(now - 86_400_000L) -> "Yesterday"
        else -> {
            val thisYear = Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.YEAR)
            val thatYear = Calendar.getInstance().apply { timeInMillis = dayStart }.get(Calendar.YEAR)
            (if (thatYear == thisYear) dayFormat else dayYearFormat).format(Date(dayStart))
        }
    }
}

@Composable
private fun PageTopBar(
    title: String,
    onBack: () -> Unit,
    actions: (@Composable () -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 8.dp),
    ) {
        IconButton(onClick = onBack) {
            Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Back", tint = ArgonColors.Text)
        }
        Text(title, color = ArgonColors.Text, style = ArgonType.Title)
        Spacer(Modifier.weight(1f))
        actions?.invoke()
    }
}

@Composable
fun HistoryPage(
    history: HistoryStore,
    onOpen: (String) -> Unit,
    onBack: () -> Unit,
    onCleared: () -> Unit = {},
    recentlyClosed: RecentlyClosedStore? = null,
    onReopenClosed: (HistoryEntry) -> Unit = {},
) {
    var confirmClear by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf("") }
    val shown =
        if (filter.isBlank()) history.entries
        else history.entries.filter {
            it.title.contains(filter, ignoreCase = true) || hostOf(it.url).contains(filter, ignoreCase = true)
        }
    Surface(Modifier.fillMaxSize(), color = ArgonColors.Bg) {
        Column(Modifier.fillMaxSize()) {
            PageTopBar(
                "History",
                onBack,
                actions = {
                    if (history.entries.isNotEmpty()) {
                        IconButton(onClick = { confirmClear = true }) {
                            Icon(painterResource(R.drawable.ic_delete), contentDescription = "Clear history", tint = ArgonColors.Text2)
                        }
                    }
                },
            )
            if (history.entries.isNotEmpty()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier =
                        Modifier.fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 4.dp)
                            .clip(RoundedCornerShape(ArgonRadii.Control))
                            .background(ArgonColors.Surface)
                            .padding(horizontal = 14.dp),
                ) {
                    Icon(painterResource(R.drawable.ic_search), contentDescription = null, tint = ArgonColors.Text3, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(10.dp))
                    androidx.compose.foundation.text.BasicTextField(
                        value = filter,
                        onValueChange = { filter = it },
                        singleLine = true,
                        textStyle = ArgonType.BodySmall.copy(color = ArgonColors.Text),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                    ) { inner ->
                        Box {
                            if (filter.isEmpty()) Text("Filter history", color = ArgonColors.Text3, style = ArgonType.BodySmall)
                            inner()
                        }
                    }
                }
            }
            val closedShown = recentlyClosed?.entries.orEmpty()
            if (closedShown.isNotEmpty()) {
                Text(
                    "Recently closed tabs",
                    color = ArgonColors.Text3,
                    style = ArgonType.Caption,
                    modifier = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 2.dp),
                )
                closedShown.take(10).forEach { entry ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier =
                            Modifier.fillMaxWidth()
                                .clickable { onReopenClosed(entry) }
                                .padding(horizontal = 20.dp, vertical = 10.dp),
                    ) {
                        HostGlyph(hostOf(entry.url))
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(entry.title, color = ArgonColors.Text, style = ArgonType.BodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                hostOf(entry.url),
                                color = ArgonColors.Text3,
                                style = ArgonType.Caption,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
            if (history.entries.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Pages you visit will appear here.", color = ArgonColors.Text3, style = ArgonType.BodySmall)
                }
            } else if (shown.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Nothing matches.", color = ArgonColors.Text3, style = ArgonType.BodySmall)
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    shown.groupBy { dayStartMs(it.time) }.forEach { (dayStart, dayEntries) ->
                        item(key = "day-$dayStart") {
                            Text(
                                dayLabel(dayStart),
                                color = ArgonColors.Text3,
                                style = ArgonType.Caption,
                                modifier = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 2.dp),
                            )
                        }
                        items(dayEntries, key = { it.url + it.time }) { entry ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier =
                                Modifier.fillMaxWidth()
                                    .clickable { onOpen(entry.url) }
                                    .padding(horizontal = 20.dp, vertical = 10.dp),
                        ) {
                            HostGlyph(hostOf(entry.url))
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(entry.title, color = ArgonColors.Text, style = ArgonType.BodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    "${hostOf(entry.url)} · ${timeFormat.format(Date(entry.time))}",
                                    color = ArgonColors.Text3,
                                    style = ArgonType.Caption,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            IconButton(onClick = {
                                history.remove(entry)
                                onCleared() // same refresh path as clear: start page Recent must not keep it
                            }) {
                                Icon(painterResource(R.drawable.ic_close), contentDescription = "Remove", tint = ArgonColors.Text3, modifier = Modifier.size(16.dp))
                            }
                        }
                        }
                    }
                }
            }
        }
    }
    if (confirmClear) {
        ClearHistoryDialog(
            onDismiss = { confirmClear = false },
            onClear = { since ->
                if (since == null) history.clear() else history.clearSince(since)
                confirmClear = false
                onCleared()
            },
        )
    }
}

/** Start of today in the device's local zone, for "clear today" ranges. */
private fun startOfTodayMs(): Long {
    val cal = Calendar.getInstance()
    cal.set(Calendar.HOUR_OF_DAY, 0)
    cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    return cal.timeInMillis
}

/**
 * Range picker for clearing history. [onClear] receives the cutoff: entries
 * at or after it are deleted, or null for "delete everything".
 */
@Composable
private fun ClearHistoryDialog(onDismiss: () -> Unit, onClear: (Long?) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Clear history") },
        text = {
            Column {
                listOf(
                    Triple("Last hour", System.currentTimeMillis() - 3_600_000L, false),
                    Triple("Today", startOfTodayMs(), false),
                    Triple("Everything", null, true),
                ).forEach { (label, cutoff, danger) ->
                    Text(
                        label,
                        color = if (danger) ArgonColors.Danger else ArgonColors.Text,
                        style = ArgonType.Body,
                        modifier =
                            Modifier.fillMaxWidth()
                                .clickable { onClear(cutoff) }
                                .padding(vertical = 12.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun BookmarksPage(bookmarks: BookmarkStore, onOpen: (String) -> Unit, onBack: () -> Unit) {
    var renaming by remember { mutableStateOf<Bookmark?>(null) }
    var renameText by remember { mutableStateOf("") }
    Surface(Modifier.fillMaxSize(), color = ArgonColors.Bg) {
        Column(Modifier.fillMaxSize()) {
            PageTopBar("Bookmarks", onBack)
            if (bookmarks.entries.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Star a page from the menu and it lands here.", color = ArgonColors.Text3, style = ArgonType.BodySmall)
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(bookmarks.entries, key = { it.url }) { entry ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier =
                                Modifier.fillMaxWidth()
                                    .clickable { onOpen(entry.url) }
                                    .padding(horizontal = 20.dp, vertical = 10.dp),
                        ) {
                            HostGlyph(hostOf(entry.url))
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(entry.title, color = ArgonColors.Text, style = ArgonType.BodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    hostOf(entry.url),
                                    color = ArgonColors.Text3,
                                    style = ArgonType.Caption,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            IconButton(onClick = {
                                renaming = entry
                                renameText = entry.title
                            }) {
                                Icon(painterResource(R.drawable.ic_edit), contentDescription = "Rename", tint = ArgonColors.Text3, modifier = Modifier.size(16.dp))
                            }
                            IconButton(onClick = { bookmarks.remove(entry) }) {
                                Icon(painterResource(R.drawable.ic_close), contentDescription = "Remove", tint = ArgonColors.Text3, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
        }
    }
    renaming?.let { target ->
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename bookmark") },
            text = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier =
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(ArgonRadii.Control))
                            .background(ArgonColors.Surface)
                            .padding(horizontal = 14.dp),
                ) {
                    androidx.compose.foundation.text.BasicTextField(
                        value = renameText,
                        onValueChange = { renameText = it },
                        singleLine = true,
                        textStyle = ArgonType.Body.copy(color = ArgonColors.Text),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        bookmarks.rename(target, renameText)
                        renaming = null
                    }
                ) {
                    Text("Save", color = ArgonColors.Accent)
                }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }
}

@Composable
fun SettingsScreen(
    adblock: Boolean,
    onAdblock: (Boolean) -> Unit,
    searchEngine: SearchEngine,
    onSearchEngine: (SearchEngine) -> Unit,
    experimental: Boolean,
    onExperimental: (Boolean) -> Unit,
    httpsOnly: Boolean,
    onHttpsOnly: (Boolean) -> Unit,
    clearOnExit: Boolean,
    onClearOnExit: (Boolean) -> Unit,
    compactTabs: Boolean,
    onCompactTabs: (Boolean) -> Unit,
    closeButtonsBackgroundTabs: Boolean,
    onCloseButtonsBackgroundTabs: (Boolean) -> Unit,
    onExportBookmarks: () -> Unit,
    onImportBookmarks: () -> Unit,
    onClearHistory: (Long?) -> Unit,
    onBack: () -> Unit,
) {
    var engineDialog by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    Surface(Modifier.fillMaxSize(), color = ArgonColors.Bg) {
        Column(Modifier.fillMaxSize()) {
            PageTopBar("Settings", onBack)
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                SettingsLabel("Privacy")
                SettingSwitch(
                    title = "Ad blocking",
                    subtitle = "${String.format("%,d", Blocklist.DOMAIN_COUNT)} ad and tracker domains blocked at the engine level",
                    checked = adblock,
                    onCheckedChange = onAdblock,
                )
                SettingsLabel("General")
                SettingClick(
                    title = "Search engine",
                    subtitle = searchEngine.label,
                    onClick = { engineDialog = true },
                )
                SettingSwitch(
                    title = "HTTPS-only",
                    subtitle = "Upgrades addresses you type or open to https://. Resources a page loads itself are not affected",
                    checked = httpsOnly,
                    onCheckedChange = onHttpsOnly,
                )
                SettingSwitch(
                    title = "Clear history on exit",
                    subtitle = "Clears Argon's history list when the app is closed. Engine cookies and cache are not cleared",
                    checked = clearOnExit,
                    onCheckedChange = onClearOnExit,
                )
                SettingSwitch(
                    title = "Experimental web features",
                    subtitle = "In-development Servo capabilities",
                    checked = experimental,
                    onCheckedChange = onExperimental,
                )
                SettingsLabel("Tabs")
                SettingSwitch(
                    title = "Compact tabs",
                    subtitle = "Smaller tab chips showing the site initial; close button only on the active tab",
                    checked = compactTabs,
                    onCheckedChange = onCompactTabs,
                )
                SettingSwitch(
                    title = "Close button on background tabs",
                    subtitle = "Shows the close control on every tab chip, not only the active one",
                    checked = closeButtonsBackgroundTabs,
                    onCheckedChange = onCloseButtonsBackgroundTabs,
                )
                SettingsLabel("Data")
                SettingClick(
                    title = "Export bookmarks",
                    subtitle = "Save as browser-compatible HTML",
                    onClick = onExportBookmarks,
                )
                SettingClick(
                    title = "Import bookmarks",
                    subtitle = "From a Chrome, Firefox, Edge or Safari HTML file; existing entries are kept",
                    onClick = onImportBookmarks,
                )
                SettingClick(title = "Clear history", subtitle = null, danger = true, onClick = { confirmClear = true })
                Spacer(Modifier.height(32.dp))
                Text(
                    "Argon 1.0 · Servo engine b6c9df7 · Block list StevenBlack/hosts (Nov 2026)",
                    color = ArgonColors.Text3,
                    style = ArgonType.Caption,
                )
            }
        }
    }
    if (engineDialog) {
        AlertDialog(
            onDismissRequest = { engineDialog = false },
            title = { Text("Search engine") },
            text = {
                Column {
                    SearchEngine.entries.forEach { engine ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier =
                                Modifier.fillMaxWidth()
                                    .clickable {
                                        onSearchEngine(engine)
                                        engineDialog = false
                                    }
                                    .padding(vertical = 4.dp),
                        ) {
                            RadioButton(
                                selected = engine == searchEngine,
                                onClick = null,
                                colors = RadioButtonDefaults.colors(selectedColor = ArgonColors.Accent),
                            )
                            Text(engine.label, color = ArgonColors.Text, style = ArgonType.Body)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { engineDialog = false }) { Text("Cancel") } },
        )
    }
    if (confirmClear) {
        ClearHistoryDialog(
            onDismiss = { confirmClear = false },
            onClear = { since ->
                onClearHistory(since)
                confirmClear = false
            },
        )
    }
}

@Composable
private fun SettingsLabel(label: String) {
    Text(
        label,
        color = ArgonColors.Text3,
        style = ArgonType.Caption,
        modifier = Modifier.padding(top = 20.dp, bottom = 2.dp),
    )
}

@Composable
private fun SettingSwitch(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
    ) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(title, color = ArgonColors.Text, style = ArgonType.Body)
            Text(subtitle, color = ArgonColors.Text3, style = ArgonType.Caption)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors =
                SwitchDefaults.colors(
                    checkedTrackColor = ArgonColors.Accent,
                    checkedThumbColor = ArgonColors.Bg,
                    uncheckedTrackColor = ArgonColors.SurfaceHigh,
                    uncheckedThumbColor = ArgonColors.Text2,
                    uncheckedBorderColor = ArgonColors.SurfaceHigh,
                ),
        )
    }
}

@Composable
private fun SettingClick(title: String, subtitle: String?, danger: Boolean = false, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
    ) {
        Column {
            Text(title, color = if (danger) ArgonColors.Danger else ArgonColors.Text, style = ArgonType.Body)
            if (subtitle != null) {
                Text(subtitle, color = ArgonColors.Text3, style = ArgonType.Caption)
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun MenuSheet(
    canGoForward: Boolean,
    shareEnabled: Boolean,
    onShare: () -> Unit,
    onFind: () -> Unit,
    onCopyLink: () -> Unit,
    onOpenIn: () -> Unit,
    onBookmarks: () -> Unit,
    currentHost: String,
    onDismiss: () -> Unit,
    onForward: () -> Unit,
    startPaged: Boolean,
    startToggleEnabled: Boolean,
    onAddToStart: () -> Unit,
    onHistory: () -> Unit,
    onSettings: () -> Unit,
    onWax: () -> Unit,
    onAbout: () -> Unit,
    onExit: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = ArgonColors.Surface) {
        Column(Modifier.padding(bottom = 40.dp)) {
            // Context header: what page these actions apply to.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
            ) {
                HostGlyph(currentHost)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        currentHost.ifEmpty { "Argon" },
                        color = ArgonColors.Text,
                        style = ArgonType.Heading,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text("Page actions", color = ArgonColors.Text3, style = ArgonType.Caption)
                }
            }
            SheetLabel("This page")
            SheetRow(R.drawable.ic_search, "Find in page", shareEnabled, onFind)
            SheetRow(R.drawable.ic_share, "Share page", shareEnabled, onShare)
            SheetRow(R.drawable.ic_copy, "Copy link", shareEnabled, onCopyLink)
            SheetRow(R.drawable.ic_open_in, "Open in another app", shareEnabled, onOpenIn)
            SheetRow(R.drawable.ic_arrow_forward, "Forward", canGoForward, onForward)
            SheetRow(
                R.drawable.ic_star,
                if (startPaged) "Remove from start page" else "Add to start page",
                startToggleEnabled,
                onAddToStart,
            )
            SheetLabel("App")
            SheetRow(R.drawable.ic_star, "Bookmarks", true, onBookmarks)
            SheetRow(R.drawable.ic_history, "History", true, onHistory)
            SheetRow(R.drawable.ic_settings, "Settings", true, onSettings)
            SheetRow(R.drawable.ic_shield, "waX", true, onWax)
            SheetRow(R.drawable.ic_info, "About Argon", true, onAbout)
            SheetRow(R.drawable.ic_exit, "Quit Argon", true, onExit)
        }
    }
}

@Composable
private fun HostGlyph(host: String) {
    val letter = host.firstOrNull()?.uppercaseChar()?.toString() ?: "•"
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier.size(38.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(ArgonColors.AccentSoft),
    ) {
        Text(letter, color = ArgonColors.Accent, style = ArgonType.Heading)
    }
}

@Composable
private fun SheetLabel(label: String) {
    Text(
        label,
        color = ArgonColors.Text3,
        style = ArgonType.Caption,
        modifier = Modifier.padding(start = 24.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun SheetRow(icon: Int, label: String, enabled: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier.fillMaxWidth()
                .alpha(if (enabled) 1f else 0.38f)
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 24.dp, vertical = 12.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier.size(38.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(ArgonColors.SurfaceHigh),
        ) {
            Icon(painterResource(icon), contentDescription = null, tint = ArgonColors.Text, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(16.dp))
        Text(label, color = ArgonColors.Text, style = ArgonType.Body)
    }
}

@Composable
fun AboutDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Argon") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · package me.ik.argon", style = ArgonType.BodySmall)
                Text("Web engine: Servo nightly 2026-11-05 (servo/servo@b6c9df76), MPL-2.0 · servo.org", style = ArgonType.BodySmall)
                Text("Ad and tracker list: StevenBlack/hosts, MIT license", style = ArgonType.BodySmall)
                Text("Interface direction informed by unslop by Damir Sokolovsky, CC BY 4.0", style = ArgonType.BodySmall)
                Text("Reviewed against Apple's Human Interface Guidelines (apple-design-skill) and mobile-app-ui-design", style = ArgonType.BodySmall)
                Text("Argon is an independent shell and is not affiliated with the Servo project.", style = ArgonType.Caption, color = ArgonColors.Text2)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}
