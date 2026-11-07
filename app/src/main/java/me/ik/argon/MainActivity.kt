package me.ik.argon

import android.content.Intent
import android.content.SharedPreferences
import android.graphics.PathMeasure
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.animation.AnimatedVisibility
import kotlinx.coroutines.launch
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.text.input.selectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import android.util.Log
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.preference.PreferenceManager
import me.ik.argon.R
import me.ik.argon.wax.FreshWaxImport
import me.ik.argon.wax.FreshWaxIntent
import me.ik.argon.wax.FreshWaxManagedStore
import me.ik.argon.wax.FreshWaxManagementDialog
import me.ik.argon.wax.FreshWaxSession
import me.ik.argon.wax.WaxConnection
import me.ik.argon.wax.WaxEffect
import me.ik.argon.wax.WaxIntentReader
import me.ik.argon.wax.WaxManagement
import me.ik.argon.wax.WaxManagementState
import me.ik.argon.wax.WaxSessionFactory
import me.ik.argon.wax.WaxStartup
import me.ik.argon.wax.WaxStartupPlan
import me.ik.argon.wax.WaxStoredIntent
import org.servo.servoview.Servo
import org.servo.servoview.ServoNavigator
import org.servo.servoview.ServoHolder

enum class Screen {
    Browser,
    History,
    Bookmarks,
    Library,
    Settings,
}

class MainActivity : ComponentActivity(), Servo.Client {

    private val urlState = TextFieldState()
    private val servoFocusRequester = FocusRequester()
    private var isLoading by mutableStateOf(false)
    private var currentUrl by mutableStateOf("")
    private var currentTitle by mutableStateOf("")
    private var alertMessage by mutableStateOf<String?>(null)

    /** Argon (engine patch 054): pending web-content context menu. Bound to
     * its source webview id + engine handle; replied exactly once via
     * Servo.contextMenuResult and cleared on selection, dismissal,
     * navigation of the same webview, or tab close/switch. */
    private class PendingContextMenu(
        val webviewId: String,
        val handle: Int,
        val linkUrl: String?,
        val imageUrl: String?,
        val items: List<org.servo.servoview.ContextMenuEntry>,
    )

    private var pendingContextMenu by mutableStateOf<PendingContextMenu?>(null)

    /** Tab strip long-press menu target (webview id). */
    private var tabStripMenuId by mutableStateOf<String?>(null)

    // Top-level load failure reported by the engine (patch 020 v3); shown
    // only when its (webviewId, pipelineId, generation) triple matches the
    // current request identity held by loadErrorGate. URL is display payload
    // only, never part of the match (redirects change it mid-load).
    private data class LoadError(val url: String, val kind: String, val reason: String)
    private var loadError by mutableStateOf<LoadError?>(null)
    private val loadErrorGate = LoadErrorGate()

    // Multi-tab state (engine patch 030): every engine event is routed by its
    // SOURCE webview id through tabManager, never by assumed-current tab, so a
    // queued pre-switch or background event can never land on the active tab's
    // state. tabStripVersion bumps on any tab change so the strip recomposes.
    private val tabManager = TabManager()
    private var tabStripVersion by mutableStateOf(0)
    private var uiScope: kotlinx.coroutines.CoroutineScope? = null
    private var startPageUrl = ""
    private var lastRequestedUrl = ""
    private var screen by mutableStateOf(Screen.Browser)
    private var showMenu by mutableStateOf(false)
    private var showAbout by mutableStateOf(false)
    private var findVisible by mutableStateOf(false)
    private var findQuery by mutableStateOf("")
    private var findCount by mutableStateOf(0)
    private var findIndex by mutableStateOf(0)
    private var adblock by mutableStateOf(true)
    private var showOnboarding by mutableStateOf(false)
    // Arm timestamp for press-back-again-to-exit at the root of history.
    private var exitArmedAt = 0L
    // External (share/view) navigation queued while onboarding is showing, so
    // a cold-start share never loads a page behind or instead of onboarding.
    private var pendingExternalUrl: String? = null
    // Last handled ACTION_SEND payload, for dropping duplicate deliveries of
    // the same share (re-created activity, rapid re-send of one intent).
    private var lastShareText: String? = null
    private var lastShareAt: Long = 0L
    private var httpsOnly by mutableStateOf(false)
    private var clearOnExit by mutableStateOf(false)
    private var compactTabs by mutableStateOf(false)
    private var tabCloseButtons by mutableStateOf(true)

    private val exportBookmarksLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/html")) { uri ->
            if (uri != null) {
                val n =
                    runCatching {
                            contentResolver.openOutputStream(uri)?.use { out ->
                                out.write(BookmarksIO.export(bookmarks.entries).toByteArray(Charsets.UTF_8))
                            } ?: error("no stream")
                            bookmarks.entries.size
                        }
                        .getOrElse { -1 }
                alertMessage = if (n >= 0) "Exported $n bookmarks." else "Bookmark export failed."
            }
        }

    private val importBookmarksLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                val result =
                    runCatching {
                            val html =
                                contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                                    ?: error("no stream")
                            val parsed = BookmarksIO.import(html)
                            val before = bookmarks.entries.size
                            parsed.forEach { bookmarks.add(it.title, it.url) }
                            bookmarks.entries.size - before
                        }
                        .getOrElse { -1 }
                alertMessage =
                    if (result >= 0) "Imported $result new bookmarks."
                    else "Bookmark import failed - not a recognized bookmarks file."
            }
        }

    private val waxProfileLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                val ok =
                    runCatching {
                            val bytes = FreshWaxImport.read(this, uri)
                            try {
                                waxStore.saveProfile(bytes)
                            } finally {
                                bytes.fill(0)
                            }
                            true
                        }
                        .getOrElse { false }
                alertMessage = if (ok) "waX profile stored privately." else "waX profile import failed."
                refreshWaxState()
            }
        }
    private var experimental by mutableStateOf(false)

    companion object {
        // Shares from other apps: cap the accepted text and ignore a second
        // delivery of the identical payload inside this window.
        private const val MAX_SHARED_TEXT_LEN = 2048
        private const val EXIT_ARM_WINDOW_MS = 2500L
        private const val SHARE_DEDUPE_WINDOW_MS = 3000L

        private const val ENGINE_UA =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/158.0.0.0 Mobile Safari/537.36"

        /** Engine prefs shipped in the default build. Never populated from
         * intents or other runtime input.
         *
         * GC responsiveness (added 1.0.14, UNVERIFIED on Argon - labeled in the
         * release notes): the engine defaults js_mem_gc_incremental_enabled and
         * js_mem_gc_per_zone_enabled to false, so SpiderMonkey GC runs are
         * non-incremental stop-the-world pauses, which show up as scroll/page
         * jank on JS-heavy sites. Incremental + per-zone GC is the long-standing
         * Firefox configuration and slices GC work into ~10ms pieces. This is a
         * mechanism-based choice, not a measurement: revert to emptyList() if
         * device runs show regressions. layout_threads stays at the engine
         * default here; threaded bench variants remain explicit opt-in builds. */
        private val ENGINE_PREFS: List<String> =
            listOf(
                "--pref", "js_mem_gc_incremental_enabled=true",
                "--pref", "js_mem_gc_incremental_slice_ms=10",
                "--pref", "js_mem_gc_per_zone_enabled=true",
            )

        private fun org.json.JSONArray.putAll(items: List<String>) {
            for (item in items) put(item)
        }

        /** Latest page title, exposed for instrumentation assertions. */
        @Volatile
        var lastTitleForTest: String = ""
            private set
    }
    private var searchEngine by mutableStateOf(SearchEngine.GOOGLE)
    private var omniboxFocused by mutableStateOf(false)
    private var restartDialog by mutableStateOf(false)
    // waX integration (in progress - not a verified native feature). Startup gate
    // runs BEFORE engine init and only appends proxy prefs for a live blocked
    // session; Stop never falls back to direct networking. OFF is the default.
    private lateinit var waxStore: FreshWaxManagedStore
    private lateinit var waxIntent: FreshWaxIntent
    private var waxSession: FreshWaxSession? = null
    private var waxConnection = WaxConnection.OFF
    private var waxPlanPrefs: List<String> = emptyList()
    private var waxStopReason by mutableStateOf<WaxStartupPlan.Reason?>(null)

    private lateinit var prefs: SharedPreferences
    private lateinit var history: HistoryStore
    private lateinit var recentlyClosed: RecentlyClosedStore
    private var libraryInitialTab = 0
    private var omniboxShortcut by mutableStateOf(OmniboxShortcut.NONE)
    private lateinit var bookmarks: BookmarkStore
    private val navigator get() = ServoHolder.navigator

    /** Run a find-in-page script, binding the result to the document it was
     * issued on (generation advances on every load start, like find-in-page
     * binding did). Runtime behavior of the bridge is unverified on device;
     * the symbol is confirmed exported by the shipped v15 .so. */
    private fun runFindScript(script: String) {
        val servo = ServoHolder.servo ?: return
        val generationAtRequest = Servo.navigationGeneration.get()
        servo.evaluateScript(script) { json, error ->
            if (Servo.navigationGeneration.get() != generationAtRequest) return@evaluateScript
            if (error != null) return@evaluateScript
            FindInPage.parse(json)?.let {
                findCount = it.count
                findIndex = it.index
            }
        }
    }

    private fun closeFind() {
        if (findVisible) runFindScript(FindInPage.CLEAR_SCRIPT)
        findVisible = false
    }

    /** Rewrite the local start page and, when it is the page on screen,
     * reload so cleared/removed entries never linger in the visible page or
     * reappear through the back stack's current entry. */
    private fun refreshStartPage() {
        StartPage.write(this, bookmarks.entries, history.entries, searchEngine)
        if (currentUrl.startsWith("file://") && currentUrl.endsWith("startpage.html")) navigator.reload()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // App-initiated navigation invalidates the load-error identity
        // immediately, before the engine's load-start callback arrives.
        ServoHolder.navigator.onNavigationInitiated = {
            // Navigation commands act on the active webview, so invalidate
            // that webview's load-error identity only; other tabs keep theirs.
            loadErrorGate.onNavigationInitiated(tabManager.activeId)
            loadError = null
        }
        MediaFocus.start(this)
        prefs = PreferenceManager.getDefaultSharedPreferences(applicationContext)
        adblock = prefs.getBoolean("adblock", true)
        showOnboarding = !prefs.getBoolean("onboarded", false)
        httpsOnly = prefs.getBoolean("https_only", false)
        clearOnExit = prefs.getBoolean("clear_on_exit", false)
        compactTabs = prefs.getBoolean("compact_tabs", false)
        tabCloseButtons = prefs.getBoolean("tab_close_buttons", true)
        omniboxShortcut =
            runCatching {
                    OmniboxShortcut.valueOf(
                        prefs.getString("omnibox_shortcut", "NONE") ?: "NONE"
                    )
                }
                .getOrDefault(OmniboxShortcut.NONE)
        experimental = prefs.getBoolean("experimental", false)
        searchEngine = SearchEngine.fromPref(prefs.getString("engine", null))
        Blocklist.apply(this, adblock)
        history = HistoryStore(this)
        recentlyClosed = RecentlyClosedStore(this)
        bookmarks = BookmarkStore(this)
        startPageUrl = StartPage.write(this, bookmarks.entries, history.entries, searchEngine)
        // Experiment: user stylesheet declaring script-fallback family names
        // from the device's own font files (bounded size; no default-family
        // replacement). Null when no usable fonts found.
        // Engine args are FIXED at build time: MainActivity is exported
        // (launcher/VIEW), so any intent-extra arg hook would let other apps
        // inject engine flags. Pref A/B uses separate build variants instead.
        // Production keeps the ENGINE DEFAULT layout thread count. A threaded
        // bench variant (e.g. layout_threads=(cores/2).coerceIn(3,6)) must be an
        // explicit opt-in build via ENGINE_PREFS and win on measured scroll
        // metrics before it can ship - no unmeasured tuning in the default build.
        waxStore = FreshWaxManagedStore(this)
        waxIntent = FreshWaxIntent(this)
        val waxStartup =
            WaxStartup(
                WaxIntentReader { waxIntent.read() },
                WaxSessionFactory { FreshWaxSession.createBlocked().also { waxSession = it } },
            )
        when (val waxPlan = waxStartup.beforeEngineInit()) {
            is WaxStartupPlan.Stop -> waxStopReason = waxPlan.reason
            is WaxStartupPlan.Enabled -> {
                waxPlanPrefs = waxPlan.additionalPrefValues()
                waxConnection = WaxConnection.BLOCKED
            }
            WaxStartupPlan.Stock -> {}
        }
        val engineArgs =
            org.json.JSONArray().apply {
                put("--user-agent"); put(ENGINE_UA)
                putAll(ENGINE_PREFS)
                for (kv in waxPlanPrefs) {
                    put("--pref"); put(kv)
                }
            }.toString()
        // Redact credentials before logging: engine args may carry URIs
        // (e.g. proxy prefs) with embedded user:pass - never log secrets.
        android.util.Log.i("ArgonNav", "ENGINE_ARGS_EFFECTIVE ${redactUriCredentials(engineArgs)}")
        enableEdgeToEdge()

        // High refresh rate (1.0.14): request the panel's peak-refresh mode
        // at the current resolution so scrolling/animation can run at
        // 90/120Hz+ where supported. The mode switches back when Argon is not
        // in front. Logged for device verification (DISPLAY_MODE).
        runCatching {
            @Suppress("DEPRECATION") val d = windowManager.defaultDisplay
            @Suppress("DEPRECATION") val cur = d.mode
            @Suppress("DEPRECATION") val best =
                d.supportedModes
                    .filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
                    .maxByOrNull { it.refreshRate }
            if (best != null && best.modeId != cur.modeId) {
                val lp = window.attributes
                lp.preferredDisplayModeId = best.modeId
                window.attributes = lp
            }
            android.util.Log.i("ArgonNav", "DISPLAY_MODE peak=${best?.refreshRate} was=${cur.refreshRate}")
        }

        val intentUrl =
            when {
                // Cold VIEW intents go through the same strict external
                // resolver as shares so httpsOnly is honored on cold start
                // (the warm path already upgrades via navigate/locationFor).
                intent?.action == Intent.ACTION_VIEW ->
                    intent.data?.toString()?.let { locationForExternal(it, searchEngine, httpsOnly) }
                // Shared text goes through locationFor, exactly like typed
                // omnibox input: URLs open, anything else becomes a search.
                intent?.action == Intent.ACTION_SEND ->
                    handleSharedText(intent.getStringExtra(Intent.EXTRA_TEXT))
                else -> null
            }
        if (showOnboarding && intentUrl != null) {
            pendingExternalUrl = intentUrl
        }

        setContent {
            ArgonTheme {
                val focusManager = LocalFocusManager.current
                uiScope = rememberCoroutineScope()
                Box(Modifier.fillMaxSize().background(ArgonColors.Bg)) {
                    if (waxStopReason == null) {
                    Servo(
                        experimentalModeEnabled = experimental,
                        // Engine init args (JSON array, parsed by servoshell's CLI).
                        // UA: Chrome's reduced mobile string (frozen Android 10 / K
                        // model + major version), so sites serve their standard
                        // mobile experience. Verified website-visible in tests.
                        args = engineArgs,
                        initialUrl = if (showOnboarding) startPageUrl else intentUrl ?: startPageUrl,
                        logStr = null,
                        client = this@MainActivity,
                        navigator = navigator,
                        modifier =
                            Modifier.fillMaxSize()
                                // Keep page content below the phone status bar:
                                // the system bar stays visible and readable and
                                // web content never draws under it.
                                .statusBarsPadding()
                                // Reserve the bottom chrome zone so page content
                                // never renders under the floating bar.
                                .padding(bottom = 76.dp)
                                .imePadding()
                                .focusRequester(servoFocusRequester),
                    )
                    }
                    LaunchedEffect(Unit) { servoFocusRequester.requestFocus() }
                    loadError?.let { err ->
                        me.ik.argon.ui.LoadErrorCard(
                            kind = err.kind,
                            reason = err.reason,
                            onGoBack = {
                                loadError = null
                                navigator.back()
                            },
                            onReload =
                                if (err.kind == "tls") null
                                else {
                                    {
                                        loadError = null
                                        navigator.reload()
                                    }
                                },
                            onDismiss = { loadError = null },
                        )
                    }
                    TabStripHost()

                    me.ik.argon.ui.ArgonCommandBar(
                        urlState = urlState,
                        currentDisplayUrl = hostOf(displayableUrl(currentUrl)),
                        canGoBack = navigator.canGoBack,
                        canGoForward = navigator.canGoForward,
                        isLoading = isLoading,
                        focused = omniboxFocused,
                        onLeadingShortcut =
                            if (omniboxShortcut == OmniboxShortcut.NONE) null
                            else ({ runOmniboxShortcut() }),
                        onFocusChange = { nowFocused ->
                            if (nowFocused && !omniboxFocused) {
                                omniboxFocused = true
                                urlState.edit {
                                    val shown = displayableUrl(currentUrl)
                                    if (shown.isNotEmpty()) {
                                        replace(0, length, shown)
                                        selectAll()
                                    }
                                }
                            } else if (!nowFocused && omniboxFocused) {
                                omniboxFocused = false
                                urlState.edit { replace(0, length, hostOf(displayableUrl(currentUrl))) }
                            }
                        },
                        onSubmit = { submitOmnibox() },
                        onBack = { navigator.back() },
                        onForward = { navigator.forward() },
                        onStopOrReload = { if (isLoading) navigator.stop() else navigator.reload() },
                        onMenu = { showMenu = true },
                        loadingArc = { LoadingArc() },
                        onEscape = { focusManager.clearFocus() },
                        // Computed only when the URL or the store snapshot
                        // changes - not on every keystroke/parent recompose
                        // (perf audit 1.0.17).
                        // Per-tab trail for the back-button long-press sheet
                        // (Vivaldi B); label is the host since per-entry titles
                        // are not tracked. Recomputed on navigation only.
                        recentHistory =
                            remember(currentUrl, tabStripVersion) {
                                tabManager.states[tabManager.activeId]
                                    ?.trail
                                    ?.asReversed()
                                    ?.filter { it != currentUrl }
                                    ?.take(8)
                                    ?.map { hostOf(displayableUrl(it)) to it }
                                    ?: emptyList()
                            },
                        onPickHistory = { url ->
                            navigate(url)
                            servoFocusRequester.requestFocus()
                        },
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                    SuggestionsHost(focusManager)
                    when (screen) {
                        Screen.History -> {
                            PageEntrance {
                            HistoryPage(
                                history = history,
                                onOpen = {
                                    navigate(it)
                                    screen = Screen.Browser
                                },
                                onBack = { screen = Screen.Browser },
                                onCleared = { refreshStartPage() },
                                recentlyClosed = recentlyClosed,
                                onReopenClosed = { entry ->
                                    recentlyClosed.remove(entry)
                                    navigate(entry.url)
                                    screen = Screen.Browser
                                },
                            )
                            }
                            BackHandler { screen = Screen.Browser }
                        }
                        Screen.Library -> {
                            PageEntrance {
                            LibraryPage(
                                bookmarks = bookmarks,
                                history = history,
                                recentlyClosed = recentlyClosed,
                                initialTab = libraryInitialTab,
                                onOpen = {
                                    navigate(it)
                                    screen = Screen.Browser
                                },
                                onReopenClosed = { entry ->
                                    recentlyClosed.remove(entry)
                                    navigate(entry.url)
                                    screen = Screen.Browser
                                },
                                onBack = {
                                    refreshStartPage()
                                    screen = Screen.Browser
                                },
                                onCleared = { refreshStartPage() },
                            )
                            }
                            BackHandler { screen = Screen.Browser }
                        }
                        Screen.Bookmarks -> {
                            PageEntrance {
                            BookmarksPage(
                                bookmarks = bookmarks,
                                onOpen = {
                                    navigate(it)
                                    screen = Screen.Browser
                                },
                                onBack = {
                                    refreshStartPage()
                                    screen = Screen.Browser
                                },
                            )
                            }
                            BackHandler {
                                refreshStartPage()
                                screen = Screen.Browser
                            }
                        }
                        Screen.Settings -> PageEntrance { SettingsPage() }
                        Screen.Browser -> {}
                    }
                    if (showOnboarding) {
                        OnboardingScreen(
                            adblock = adblock,
                            searchEngine = searchEngine,
                            onAdblockChange = {
                                adblock = it
                                prefs.edit().putBoolean("adblock", it).apply()
                                Blocklist.apply(this@MainActivity, it)
                            },
                            onEngineChange = {
                                searchEngine = it
                                prefs.edit().putString("engine", it.name.lowercase()).apply()
                                refreshStartPage()
                            },
                            onDone = {
                                prefs.edit().putBoolean("onboarded", true).apply()
                                showOnboarding = false
                                pendingExternalUrl?.let {
                                    lastRequestedUrl = it
                                    loadError = null
                                    navigator.navigate(it)
                                }
                                pendingExternalUrl = null
                            },
                        )
                    }
                    if (showMenu) {
                        MenuSheet(
                            canGoForward = navigator.canGoForward,
                            shareEnabled = currentUrl.isNotEmpty() && !currentUrl.startsWith("file://"),
                            onShare = {
                                showMenu = false
                                val send = Intent(Intent.ACTION_SEND)
                                    .setType("text/plain")
                                    .putExtra(
                                        Intent.EXTRA_TEXT,
                                        if (currentTitle.isBlank()) currentUrl else "$currentTitle\n$currentUrl",
                                    )
                                startActivity(Intent.createChooser(send, null))
                            },
                            onFind = {
                                showMenu = false
                                findVisible = true
                            },
                            onCopyLink = {
                                showMenu = false
                                val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                cm.setPrimaryClip(android.content.ClipData.newPlainText("link", currentUrl))
                            },
                            onOpenIn = {
                                showMenu = false
                                startActivity(
                                    Intent(Intent.ACTION_VIEW, android.net.Uri.parse(currentUrl))
                                        .addCategory(Intent.CATEGORY_BROWSABLE)
                                )
                            },
                            onBookmarks = {
                                showMenu = false
                                libraryInitialTab = 0
                                screen = Screen.Library
                            },
                            currentHost = hostOf(displayableUrl(currentUrl)),
                            onDismiss = { showMenu = false },
                            onForward = {
                                showMenu = false
                                navigator.forward()
                            },
                            startPaged = bookmarks.entries.any { it.url == currentUrl },
                            startToggleEnabled =
                                currentUrl.startsWith("https://") || currentUrl.startsWith("http://"),
                            onAddToStart = {
                                showMenu = false
                                val existing = bookmarks.entries.firstOrNull { it.url == currentUrl }
                                if (existing != null) bookmarks.remove(existing)
                                else bookmarks.add(currentTitle, currentUrl)
                                refreshStartPage()
                            },
                            onHistory = {
                                showMenu = false
                                libraryInitialTab = 1
                                screen = Screen.Library
                            },
                            onSettings = {
                                showMenu = false
                                screen = Screen.Settings
                            },
                            onWax = {
                                showMenu = false
                                showWaxManager()
                            },
                            onAbout = {
                                showMenu = false
                                showAbout = true
                            },
                            onExit = {
                                showMenu = false
                                finishAndRemoveTask()
                            },
                        )
                    }
                    // System back on the browser screen walks page history
                    // first; at the root it asks for a second press to exit.
                    if (screen == Screen.Browser && !showOnboarding && !showMenu && !findVisible && !omniboxFocused) {
                        BackHandler {
                            when {
                                navigator.canGoBack -> navigator.back()
                                System.currentTimeMillis() - exitArmedAt < EXIT_ARM_WINDOW_MS ->
                                    finishAndRemoveTask()
                                else -> {
                                    exitArmedAt = System.currentTimeMillis()
                                    android.widget.Toast
                                        .makeText(this@MainActivity, "Press back again to exit", android.widget.Toast.LENGTH_SHORT)
                                        .show()
                                }
                            }
                        }
                    }
                    if (findVisible) {
                        BackHandler { closeFind() }
                        // Debounced highlight on query change / bar open.
                        LaunchedEffect(findQuery) {
                            kotlinx.coroutines.delay(300)
                            if (findQuery.isNotEmpty()) {
                                runFindScript(FindInPage.highlightScript(findQuery))
                            } else {
                                runFindScript(FindInPage.CLEAR_SCRIPT)
                                findCount = 0
                                findIndex = 0
                            }
                        }
                        // A navigation drops the old document's marks; re-run.
                        LaunchedEffect(currentUrl) {
                            if (findQuery.isNotEmpty()) runFindScript(FindInPage.highlightScript(findQuery))
                        }
                        FindBar(
                            query = findQuery,
                            count = findCount,
                            index = findIndex,
                            onQuery = { findQuery = it },
                            onNext = { runFindScript(FindInPage.NEXT_SCRIPT) },
                            onPrev = { runFindScript(FindInPage.PREV_SCRIPT) },
                            onClose = { closeFind() },
                            modifier =
                                Modifier.align(Alignment.BottomCenter)
                                    .navigationBarsPadding()
                                    .padding(start = 12.dp, end = 12.dp, bottom = 84.dp),
                        )
                    }
                    if (showAbout) {
                        AboutDialog(onDismiss = { showAbout = false })
                    }
                    if (StoreHealth.corruptPreserveError.value) {
                        AlertDialog(
                            onDismissRequest = { StoreHealth.corruptPreserveError.value = false },
                            title = { Text("Data file unreadable") },
                            text = {
                                Text(
                                    "A saved data file couldn't be read, and it couldn't be safely moved aside either. It has been left untouched, and saving is paused for that list so nothing is overwritten. Freeing storage space and restarting Argon may recover it."
                                )
                            },
                            confirmButton = {
                                TextButton(onClick = { StoreHealth.corruptPreserveError.value = false }) { Text("OK") }
                            },
                        )
                    }
                    if (StoreHealth.writeError.value) {
                        AlertDialog(
                            onDismissRequest = { StoreHealth.writeError.value = false },
                            title = { Text("Couldn't save") },
                            text = {
                                Text(
                                    "Writing to device storage failed, so the last change may not be kept. Free up storage space and try again."
                                )
                            },
                            confirmButton = {
                                TextButton(onClick = { StoreHealth.writeError.value = false }) { Text("OK") }
                            },
                        )
                    }
                    if (restartDialog) {
                        AlertDialog(
                            onDismissRequest = { restartDialog = false },
                            title = { Text("Restart to apply") },
                            text = { Text("Argon restarts to switch the block list at the engine level.") },
                            confirmButton = {
                                TextButton(
                                    onClick = {
                                        restartDialog = false
                                        restartApp()
                                    }
                                ) {
                                    Text("Restart")
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { restartDialog = false }) { Text("Later") }
                            },
                        )
                    }
                    pendingContextMenu?.let { menu ->
                        AlertDialog(
                            onDismissRequest = { dismissContextMenu(menu) },
                            title = {
                                Text(
                                    menu.linkUrl?.let { hostOf(it) }
                                        ?: menu.imageUrl?.let { hostOf(it) }
                                        ?: ""
                                )
                            },
                            text = {
                                Column {
                                    menu.items.forEachIndexed { index, entry ->
                                        if (entry.label == null) {
                                            HorizontalDivider()
                                        } else {
                                            TextButton(
                                                enabled = entry.enabled,
                                                onClick = { answerContextMenu(menu, index) },
                                                modifier = Modifier.fillMaxWidth(),
                                            ) {
                                            Text(entry.label)
                                            }
                                        }
                                    }
                                }
                            },
                            confirmButton = {
                                TextButton(onClick = { dismissContextMenu(menu) }) { Text("Cancel") }
                            },
                        )
                    }
                    tabStripMenuId?.let { menuId ->
                        AlertDialog(
                            onDismissRequest = { tabStripMenuId = null },
                            title = {
                                Text(
                                    tabManager.states[menuId]?.title?.ifBlank { null }
                                        ?: hostOf(displayableUrl(tabManager.states[menuId]?.url ?: ""))
                                )
                            },
                            text = {
                                Column {
                                    TextButton(
                                        onClick = {
                                            tabStripMenuId = null
                                            closeTab(menuId)
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                    ) { Text("Close tab") }
                                    if (tabManager.order.size > 1) {
                                        TextButton(
                                            onClick = {
                                                tabStripMenuId = null
                                                tabManager.order.filter { it != menuId }.forEach { closeTab(it) }
                                            },
                                            modifier = Modifier.fillMaxWidth(),
                                        ) { Text("Close other tabs") }
                                    }
                                    TextButton(
                                        onClick = {
                                            tabStripMenuId = null
                                            val url = tabManager.states[menuId]?.url ?: ""
                                            openNewTab(url.ifBlank { startPageUrl })
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                    ) { Text("Duplicate tab") }
                                }
                            },
                            confirmButton = {
                                TextButton(onClick = { tabStripMenuId = null }) { Text("Cancel") }
                            },
                        )
                    }
                    alertMessage?.let { message ->
                        AlertDialog(
                            onDismissRequest = { alertMessage = null },
                            confirmButton = {
                                TextButton(onClick = { alertMessage = null }) { Text("OK") }
                            },
                            text = { Text(message) },
                        )
                    }
                }
            }
        }
        waxStopReason?.let { showWaxStopDialog(it) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        when (intent.action) {
            Intent.ACTION_VIEW -> intent.data?.toString()?.let { navigate(it) }
            Intent.ACTION_SEND -> handleSharedText(intent.getStringExtra(Intent.EXTRA_TEXT))?.let { navigateDest(it) }
        }
    }

    // Shared text from other apps: trimmed, length-bounded, duplicate
    // deliveries dropped, then resolved with the strict external resolver so
    // only http(s) locations can come out of a share. Returns the destination
    // URL, or null when the share is empty, malformed or a duplicate.
    private fun handleSharedText(raw: String?): String? {
        val text = raw?.trim()?.take(MAX_SHARED_TEXT_LEN)?.takeIf { it.isNotEmpty() } ?: return null
        val now = System.currentTimeMillis()
        if (text == lastShareText && now - lastShareAt < SHARE_DEDUPE_WINDOW_MS) return null
        lastShareText = text
        lastShareAt = now
        return locationForExternal(text, searchEngine, httpsOnly)
    }

    private fun navigateDest(dest: String) {
        Log.i("ArgonNav", "share dest='$dest'")
        lastRequestedUrl = dest
        loadError = null
        navigator.navigate(dest)
    }

    private fun navigate(target: String) {
        val dest = locationFor(target, searchEngine, httpsOnly)
        Log.i("ArgonNav", "submit target='$target' dest='$dest'")
        lastRequestedUrl = dest
        loadError = null
        navigator.navigate(dest)
    }

    private fun submitOmnibox() {
        val input = urlState.text.toString()
        if (input.isNotBlank()) {
            navigate(input)
            servoFocusRequester.requestFocus()
        }
    }

    private fun restartApp() {
        val launch =
            packageManager.getLaunchIntentForPackage(packageName)!!
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(launch)
        Runtime.getRuntime().exit(0)
    }

    private fun refreshWaxState(): WaxManagementState {
        val wanted = waxIntent.read() == WaxStoredIntent.Enabled
        val processEnabled = waxSession != null
        return WaxManagementState(
            connection = waxConnection,
            consentAccepted = waxStore.consented(),
            hasProfile = waxStore.hasProfile(),
            processEnabled = processEnabled,
            wantedEnabled = wanted,
            restartRequired = wanted != processEnabled,
        )
    }

    private fun runOmniboxShortcut() {
        when (omniboxShortcut) {
            OmniboxShortcut.NONE -> {}
            OmniboxShortcut.ADBLOCK -> {
                adblock = !adblock
                prefs.edit().putBoolean("adblock", adblock).apply()
                Blocklist.apply(this, adblock)
                restartDialog = true
            }
            OmniboxShortcut.BOOKMARK -> {
                val existing = bookmarks.entries.firstOrNull { it.url == currentUrl }
                if (existing != null) bookmarks.remove(existing)
                else if (currentUrl.startsWith("https://") || currentUrl.startsWith("http://")) {
                    bookmarks.add(currentTitle, currentUrl)
                }
                refreshStartPage()
            }
            OmniboxShortcut.SHARE -> {
                if (currentUrl.isNotEmpty() && !currentUrl.startsWith("file://")) {
                    val send =
                        Intent(Intent.ACTION_SEND)
                            .setType("text/plain")
                            .putExtra(
                                Intent.EXTRA_TEXT,
                                if (currentTitle.isBlank()) currentUrl else "$currentTitle\n$currentUrl",
                            )
                    startActivity(Intent.createChooser(send, null))
                }
            }
            OmniboxShortcut.LIBRARY -> screen = Screen.Library
        }
    }

    private fun showWaxManager() {
        FreshWaxManagementDialog.show(this, refreshWaxState()) { action ->
            handleWaxEffect(WaxManagement.effect(refreshWaxState(), action))
        }
    }

    private fun handleWaxEffect(effect: WaxEffect) {
        when (effect) {
            WaxEffect.SHOW_NOTICE ->
                FreshWaxManagementDialog.showNotice(this) {
                    waxIntent.acceptNoticeOff()
                }
            WaxEffect.OPEN_DOCUMENT_PICKER -> waxProfileLauncher.launch(arrayOf("*/*"))
            WaxEffect.PERSIST_ENABLE_THEN_CLOSE -> {
                waxStore.saveMode(true)
                restartApp()
            }
            WaxEffect.PERSIST_DISABLE_THEN_CLOSE -> {
                waxStore.saveMode(false)
                restartApp()
            }
            WaxEffect.CONNECT_EXISTING_SESSION -> {
                val session = waxSession ?: return
                waxConnection = WaxConnection.CONNECTING
                val scope = uiScope ?: return
                scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    val profile = waxStore.load()
                    val code = if (profile != null) session.connect(profile) else -1
                    profile?.fill(0)
                    waxConnection =
                        if (code == 0) WaxConnection.PEER_CONNECTED else WaxConnection.BLOCKED
                }
            }
            WaxEffect.BLOCK_EXISTING_SESSION -> {
                waxSession?.block()
                waxConnection = if (waxSession != null) WaxConnection.BLOCKED else WaxConnection.OFF
            }
            WaxEffect.CONFIRM_DELETE_THEN_BLOCK_AND_DELETE -> {
                waxSession?.block()
                if (waxSession != null) waxConnection = WaxConnection.BLOCKED
                waxStore.deleteProfile()
            }
            WaxEffect.EXPLAIN_PROVISIONING_UNAVAILABLE ->
                FreshWaxManagementDialog.explainRegistration(this)
            WaxEffect.NO_OP -> {}
        }
    }

    private fun showWaxStopDialog(reason: WaxStartupPlan.Reason) {
        val detail =
            when (reason) {
                WaxStartupPlan.Reason.INVALID_INTENT -> "The stored waX preference is unreadable."
                WaxStartupPlan.Reason.INTENT_READ_FAILED ->
                    "The stored waX preference could not be read."
                WaxStartupPlan.Reason.NATIVE_UNAVAILABLE ->
                    "The waX native library is not available in this build."
                WaxStartupPlan.Reason.INVALID_LISTENER ->
                    "waX could not open a safe local listener."
                WaxStartupPlan.Reason.PROCESS_STATE_CHANGED ->
                    "waX state changed while Argon was running."
            }
        android.app.AlertDialog.Builder(this)
            .setTitle("waX can't start")
            .setMessage(
                "$detail Argon did not fall back to ordinary direct browsing. Turn waX off to continue."
            )
            .setCancelable(false)
            .setPositiveButton("Turn waX off and restart") { _, _ ->
                waxStore.saveMode(false)
                restartApp()
            }
            .setNegativeButton("Close Argon") { _, _ -> finishAndRemoveTask() }
            .show()
    }

    /** Reads tabStripVersion + tabManager inside its own composition scope so
     * tab-state writes recompose only the strip, not the whole browser tree.
     * The @Immutable TabStripData wrapper keeps ArgonTabStrip skippable (its
     * old List<Pair<..>> parameter was unstable, forcing recomposition on
     * every parent pass, including per-keystroke). Perf audit 1.0.17. */
    @Composable
    private fun BoxScope.TabStripHost() {
        val data =
            remember(tabStripVersion) {
                me.ik.argon.ui.TabStripData(
                    chips =
                        tabManager.order.map { id ->
                            val t = tabManager.states[id]
                            id to (
                                t?.title?.ifBlank { null }
                                    ?: hostOf(displayableUrl(t?.url ?: "")).ifBlank { "New tab" }
                                )
                        },
                    activeId = tabManager.activeId,
                )
            }
        if (data.chips.isNotEmpty()) {
            me.ik.argon.ui.ArgonTabStrip(
                data = data,
                onSelect = { switchToTab(it) },
                onClose = { closeTab(it) },
                onNew = { openNewTab(startPageUrl) },
                onLongPress = { tabStripMenuId = it },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 80.dp),
                compact = compactTabs,
                showCloseOnBackground = tabCloseButtons,
            )
        }
    }

    /** Owns the omnibox-query subscription: urlState.text changes on every
     * keystroke, so reading it here keeps keystrokes recomposing only this
     * subtree instead of the whole browser tree (the query used to be read in
     * the root Box scope). Perf audit 1.0.17. Behavior unchanged: Back backs
     * out of editing, suggestions only for a non-blank focused query, hidden
     * while the find bar owns the slot. */
    @Composable
    private fun BoxScope.SuggestionsHost(focusManager: androidx.compose.ui.focus.FocusManager) {
        val query = urlState.text.toString()
        if (omniboxFocused && query.isBlank()) {
            // Suggestions only exist for a non-blank query; the blank-query
            // case still backs out of editing first.
            BackHandler { focusManager.clearFocus() }
        }
        if (!(omniboxFocused && query.isNotBlank() && !findVisible)) return
        OmniboxSuggestions(
            query = query,
            engine = searchEngine,
            history = history.entries,
            bookmarks = bookmarks.entries,
            onSearch = {
                submitOmnibox()
                servoFocusRequester.requestFocus()
            },
            onPick = { url ->
                navigate(url)
                servoFocusRequester.requestFocus()
            },
            modifier =
                Modifier.align(Alignment.BottomCenter)
                    .padding(start = 12.dp, end = 12.dp, bottom = 84.dp),
        )
        // Back closes the suggestions first; the focus-loss handler restores
        // the current host in the field.
        BackHandler { focusManager.clearFocus() }
    }

    @Composable
    private fun LoadingArc() {
        // Bottom-edge loading track: a short accent segment travelling the
        // capsule's width (rendered inside the bar's bottom-aligned slot).
        val transition = rememberInfiniteTransition(label = "loadArc")
        val progress by
            transition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing)),
                label = "arcProgress",
            )
        Canvas(Modifier.fillMaxWidth().height(2.5.dp)) {
            val w = size.width
            val segLen = w * 0.28f
            val travel = w + segLen
            val x = progress * travel - segLen
            drawRoundRect(
                color = ArgonColors.Accent,
                topLeft = Offset(x, 0f),
                size = Size(segLen, size.height),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2, size.height / 2),
            )
        }
    }

    @Composable
    private fun SettingsPage() {
        SettingsScreen(
            adblock = adblock,
            onAdblock = {
                adblock = it
                prefs.edit().putBoolean("adblock", it).apply()
                Blocklist.apply(this@MainActivity, it)
                restartDialog = true
            },
            searchEngine = searchEngine,
            onSearchEngine = {
                searchEngine = it
                prefs.edit().putString("engine", it.name.lowercase()).apply()
                refreshStartPage()
            },
            experimental = experimental,
            onExperimental = {
                experimental = it
                prefs.edit().putBoolean("experimental", it).apply()
            },
            httpsOnly = httpsOnly,
            onHttpsOnly = {
                httpsOnly = it
                prefs.edit().putBoolean("https_only", it).apply()
            },
            clearOnExit = clearOnExit,
            onClearOnExit = {
                clearOnExit = it
                prefs.edit().putBoolean("clear_on_exit", it).apply()
            },
            omniboxShortcut = omniboxShortcut,
            onOmniboxShortcut = {
                omniboxShortcut = it
                prefs.edit().putString("omnibox_shortcut", it.name).apply()
            },
            compactTabs = compactTabs,
            onCompactTabs = {
                compactTabs = it
                prefs.edit().putBoolean("compact_tabs", it).apply()
            },
            closeButtonsBackgroundTabs = tabCloseButtons,
            onCloseButtonsBackgroundTabs = {
                tabCloseButtons = it
                prefs.edit().putBoolean("tab_close_buttons", it).apply()
            },
            onExportBookmarks = { exportBookmarksLauncher.launch("argon-bookmarks.html") },
            onImportBookmarks = { importBookmarksLauncher.launch(arrayOf("text/html", "text/*", "application/octet-stream")) },
            onClearHistory = { since ->
                if (since == null) history.clear() else history.clearSince(since)
                refreshStartPage()
            },
            onBack = { screen = Screen.Browser },
        )
        BackHandler { screen = Screen.Browser }
    }

    // Servo.Client. Every callback is routed by its SOURCE webview id
    // (engine patch 030 v2): events from background or just-closed tabs update
    // their own tab's state only and never touch the active-tab UI fields.
    override fun onAlert(webviewId: String, message: String) {
        if (tabManager.isActive(webviewId)) {
            alertMessage = message
        } else {
            // Only informational alerts reach here; Confirm/Prompt are denied
            // by default engine-side (Drop sends Cancel), never silently
            // consented. A background tab's alert has no user to answer it,
            // so it is logged, not queued.
            Log.i("ArgonNav", "alert dropped: background tab")
        }
    }

    override fun onLoadStarted(webviewId: String) {
        val tab = tabManager.route(webviewId) ?: return
        tab.loading = true
        tabStripVersion++
        if (tabManager.isActive(webviewId)) isLoading = true
    }

    override fun onLoadEnded(webviewId: String) {
        val tab = tabManager.route(webviewId) ?: return
        tab.loading = false
        tabStripVersion++
        // History records web pages only: file:// (start page) is not
        // browsing, and data:/about: entries can't be meaningfully
        // revisited from the list. Background tabs record theirs too.
        if (tab.url.startsWith("https://") || tab.url.startsWith("http://")) {
            history.add(tab.url, tab.title)
        }
        if (tabManager.isActive(webviewId)) isLoading = false
    }

    override fun onTitleChanged(webviewId: String, title: String) {
        val tab = tabManager.route(webviewId) ?: return
        tab.title = title
        tabStripVersion++
        if (tabManager.isActive(webviewId)) {
            currentTitle = title
            lastTitleForTest = title
        }
    }

    override fun onUrlChanged(webviewId: String, url: String) {
        // Argon (engine patch 054): a navigation invalidates the page's
        // pending context menu; reply dismiss and drop the UI.
        pendingContextMenu?.let { if (it.webviewId == webviewId) dismissContextMenu(it) }
        val tab = tabManager.route(webviewId) ?: return
        tab.url = url
        if (url.isNotBlank() && url != "about:blank" && tab.trail.lastOrNull() != url) {
            tab.trail.addLast(url)
            while (tab.trail.size > 50) tab.trail.removeFirst()
        }
        tabStripVersion++
        if (!tabManager.isActive(webviewId)) return
        currentUrl = url
        if (loadError != null && loadError?.url != url) loadError = null
        exitArmedAt = 0L
        if (!omniboxFocused) {
            urlState.edit { replace(0, length, hostOf(displayableUrl(url))) }
        }
    }

    override fun onHistoryChanged(webviewId: String, canGoBack: Boolean, canGoForward: Boolean) {
        val tab = tabManager.route(webviewId) ?: return
        tab.canGoBack = canGoBack
        tab.canGoForward = canGoForward
        if (tabManager.isActive(webviewId)) {
            navigator.canGoBack = canGoBack
            navigator.canGoForward = canGoForward
        }
    }

    /** Argon (engine patch 054): reply -1 (dismiss) if not already answered. */
    private fun dismissContextMenu(menu: PendingContextMenu) {
        if (pendingContextMenu === menu) pendingContextMenu = null
        ServoHolder.servo?.contextMenuResult(menu.handle, -1)
    }

    private fun answerContextMenu(menu: PendingContextMenu, index: Int) {
        if (pendingContextMenu === menu) pendingContextMenu = null
        ServoHolder.servo?.contextMenuResult(menu.handle, index)
    }

    override fun onContextMenu(
        webviewId: String,
        handle: Int,
        linkUrl: String?,
        imageUrl: String?,
        items: List<org.servo.servoview.ContextMenuEntry>,
    ) {
        // Only the active tab's menu is shown; a stale/background request is
        // dismissed immediately so the engine never waits on invisible UI.
        if (!tabManager.isActive(webviewId)) {
            ServoHolder.servo?.contextMenuResult(handle, -1)
            return
        }
        pendingContextMenu?.let { ServoHolder.servo?.contextMenuResult(it.handle, -1) }
        pendingContextMenu = PendingContextMenu(webviewId, handle, linkUrl, imageUrl, items)
    }

    // ---- tab operations (engine patch 030 JNI: newTab/activateTab/closeTab) ----

    private fun openNewTab(url: String) {
        if (!tabManager.canOpenNew()) {
            alertMessage = "Tab limit reached (${TabManager.DEFAULT_MAX_TABS})."
            return
        }
        val scope = uiScope ?: return
        val servo = ServoHolder.servo ?: return
        scope.launch {
            val id = servo.newTab(url)
            if (id.isEmpty()) return@launch
            val tab = tabManager.route(id) ?: return@launch
            tab.url = url
            tab.loading = true
            tabManager.markActive(id)
            tabStripVersion++
            loadError = null
            publishActiveTab()
        }
    }

    private fun switchToTab(id: String) {
        if (id == tabManager.activeId) return
        val scope = uiScope ?: return
        val servo = ServoHolder.servo ?: return
        scope.launch {
            pendingContextMenu?.let { dismissContextMenu(it) }
            if (!servo.activateTab(id)) return@launch
            tabManager.markActive(id)
            loadError = null
            publishActiveTab()
            tabStripVersion++
        }
    }

    private fun closeTab(id: String) {
        // Last-tab rule: never leave the browser with no webview; recycle the
        // only tab into the start page instead of closing it.
        if (tabManager.order.size <= 1) {
            navigate(startPageUrl)
            return
        }
        val scope = uiScope ?: return
        val servo = ServoHolder.servo ?: return
        scope.launch {
            if (!servo.closeTab(id)) return@launch
            tabManager.states[id]?.let { recentlyClosed.add(it.url, it.title) }
            val wasActive = tabManager.onClosed(id)
            tabStripVersion++
            if (wasActive) reconcileAfterActiveClosed()
        }
    }

    /** Engine-initiated close (window.close, teardown; engine patch 030 v4):
     * tombstone the id so no racing event can resurrect the tab, then
     * reconcile the active tab if the closed one was showing. Ids the app
     * never adopted are tombstoned too, closing the liveness gap for views
     * that lived and died entirely engine-side. */
    override fun onTabClosed(webviewId: String) {
        pendingContextMenu?.let { if (it.webviewId == webviewId) dismissContextMenu(it) }
        val known = tabManager.states.containsKey(webviewId)
        if (known) tabManager.states[webviewId]?.let { recentlyClosed.add(it.url, it.title) }
        val wasActive = tabManager.onClosed(webviewId)
        if (!known) return
        tabStripVersion++
        if (wasActive) reconcileAfterActiveClosed()
    }

    /** After the active tab closed (by app or engine), the engine has
     * activated the next-newest; adopt its reported id and publish it. */
    private fun reconcileAfterActiveClosed() {
        val scope = uiScope ?: return
        val servo = ServoHolder.servo ?: return
        scope.launch {
            val newActive = servo.activeTabId()
            if (newActive.isNotEmpty()) {
                tabManager.route(newActive)
                tabManager.markActive(newActive)
                loadError = null
                publishActiveTab()
                tabStripVersion++
            }
        }
    }

    /** Publish the active tab's state to the single-tab UI fields after a
     * switch. The engine re-reports title/url/history for the newly active
     * webview as well; publishing immediately keeps strip and omnibox
     * consistent in the meantime. */
    private fun publishActiveTab() {
        val tab = tabManager.states[tabManager.activeId] ?: return
        currentUrl = tab.url
        currentTitle = tab.title
        isLoading = tab.loading
        navigator.canGoBack = tab.canGoBack
        navigator.canGoForward = tab.canGoForward
        if (!omniboxFocused) {
            urlState.edit { replace(0, length, hostOf(displayableUrl(tab.url))) }
        }
    }

    override fun onLoadError(
        webviewId: String,
        pipelineId: String,
        url: String,
        kind: String,
        reason: String,
        generation: Long,
    ) {
        // Identity binding: only a failure whose (webview, pipeline,
        // generation) triple matches the current request may surface; stale,
        // superseded, untracked, and non-top-level events are dropped here
        // (the engine already gates them at the constellation).
        if (!loadErrorGate.shouldShowError(webviewId, pipelineId, generation)) {
            Log.i("ArgonNav", "loadError dropped: stale identity gen=$generation")
            return
        }
        if (!tabManager.isActive(webviewId)) {
            Log.i("ArgonNav", "loadError dropped: background tab")
            return
        }
        loadError = LoadError(url, kind, reason)
    }

    override fun onTopLevelLoad(webviewId: String, pipelineId: String, url: String, generation: Long) {
        // Adopt the first webview here (it is the engine's earliest signal)
        // so all later id-tagged events route to a known tab.
        tabManager.route(webviewId)
        // A new top-level request supersedes any error card still showing on
        // ITS OWN webview; other tabs' cards are unaffected.
        loadErrorGate.onTopLevelLoad(webviewId, pipelineId, generation)
        if (tabManager.isActive(webviewId)) loadError = null
    }

    override fun onDestroy() {
        MediaFocus.stop()
        // Scope is exactly the app's own history list; engine cookies/cache
        // are not covered (labeled as such in Settings).
        if (clearOnExit && ::history.isInitialized) history.clear()
        super.onDestroy()
    }
}

/** Replaces the userinfo part of any URI in the string with "***". */
internal fun redactUriCredentials(s: String): String =
    s.replace(Regex("""([a-zA-Z][a-zA-Z0-9+.-]*://)[^/@\s"\]]+@"""), "$1***@")
