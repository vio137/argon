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
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import org.servo.servoview.Servo
import org.servo.servoview.ServoNavigator
import org.servo.servoview.ServoHolder

enum class Screen {
    Browser,
    History,
    Bookmarks,
    Settings,
}

class MainActivity : ComponentActivity(), Servo.Client {

    private val urlState = TextFieldState()
    private val servoFocusRequester = FocusRequester()
    private var isLoading by mutableStateOf(false)
    private var currentUrl by mutableStateOf("")
    private var currentTitle by mutableStateOf("")
    private var alertMessage by mutableStateOf<String?>(null)
    private var screen by mutableStateOf(Screen.Browser)
    private var showMenu by mutableStateOf(false)
    private var showAbout by mutableStateOf(false)
    private var adblock by mutableStateOf(true)
    private var showOnboarding by mutableStateOf(false)
    private var httpsOnly by mutableStateOf(false)
    private var clearOnExit by mutableStateOf(false)

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
    private var experimental by mutableStateOf(false)

    companion object {
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

    private lateinit var prefs: SharedPreferences
    private lateinit var history: HistoryStore
    private lateinit var bookmarks: BookmarkStore
    private val navigator get() = ServoHolder.navigator

    /** Rewrite the local start page and, when it is the page on screen,
     * reload so cleared/removed entries never linger in the visible page or
     * reappear through the back stack's current entry. */
    private fun refreshStartPage() {
        StartPage.write(this, bookmarks.entries, history.entries, searchEngine)
        if (currentUrl.startsWith("file://") && currentUrl.endsWith("startpage.html")) navigator.reload()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MediaFocus.start(this)
        prefs = PreferenceManager.getDefaultSharedPreferences(applicationContext)
        adblock = prefs.getBoolean("adblock", true)
        showOnboarding = !prefs.getBoolean("onboarded", false)
        httpsOnly = prefs.getBoolean("https_only", false)
        clearOnExit = prefs.getBoolean("clear_on_exit", false)
        experimental = prefs.getBoolean("experimental", false)
        searchEngine = SearchEngine.fromPref(prefs.getString("engine", null))
        Blocklist.apply(this, adblock)
        history = HistoryStore(this)
        bookmarks = BookmarkStore(this)
        val startPageUrl = StartPage.write(this, bookmarks.entries, history.entries, searchEngine)
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
        val engineArgs =
            org.json.JSONArray().apply {
                put("--user-agent"); put(ENGINE_UA)
                putAll(ENGINE_PREFS)
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

        val intentUrl = if (intent?.action == Intent.ACTION_VIEW) intent.data?.toString() else null

        setContent {
            ArgonTheme {
                val focusManager = LocalFocusManager.current
                Box(Modifier.fillMaxSize().background(ArgonColors.Bg)) {
                    Servo(
                        experimentalModeEnabled = experimental,
                        // Engine init args (JSON array, parsed by servoshell's CLI).
                        // UA: Chrome's reduced mobile string (frozen Android 10 / K
                        // model + major version), so sites serve their standard
                        // mobile experience. Verified website-visible in tests.
                        args = engineArgs,
                        initialUrl = intentUrl ?: startPageUrl,
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
                    LaunchedEffect(Unit) { servoFocusRequester.requestFocus() }
                    me.ik.argon.ui.ArgonCommandBar(
                        urlState = urlState,
                        omniboxText = urlState.text.toString(),
                        currentDisplayUrl = hostOf(displayableUrl(currentUrl)),
                        canGoBack = navigator.canGoBack,
                        isLoading = isLoading,
                        focused = omniboxFocused,
                        secure = currentUrl.startsWith("https://"),
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
                        onStopOrReload = { if (isLoading) navigator.stop() else navigator.reload() },
                        onMenu = { showMenu = true },
                        loadingArc = { LoadingArc() },
                        onEscape = { focusManager.clearFocus() },
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                    val omniboxQuery = urlState.text.toString()
                    if (omniboxFocused && omniboxQuery.isNotBlank()) {
                        OmniboxSuggestions(
                            query = omniboxQuery,
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
                        // Back closes the suggestions first; the focus-loss
                        // handler restores the current host in the field.
                        BackHandler { focusManager.clearFocus() }
                    }
                    when (screen) {
                        Screen.History -> {
                            HistoryPage(
                                history = history,
                                onOpen = {
                                    navigate(it)
                                    screen = Screen.Browser
                                },
                                onBack = { screen = Screen.Browser },
                                onCleared = { refreshStartPage() },
                            )
                            BackHandler { screen = Screen.Browser }
                        }
                        Screen.Bookmarks -> {
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
                            BackHandler {
                                refreshStartPage()
                                screen = Screen.Browser
                            }
                        }
                        Screen.Settings -> SettingsPage()
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
                                    .putExtra(Intent.EXTRA_TEXT, currentUrl)
                                startActivity(Intent.createChooser(send, null))
                            },
                            onBookmarks = {
                                showMenu = false
                                screen = Screen.Bookmarks
                            },
                            currentHost = hostOf(displayableUrl(currentUrl)),
                            onDismiss = { showMenu = false },
                            onForward = {
                                showMenu = false
                                navigator.forward()
                            },
                            onAddToStart = {
                                showMenu = false
                                bookmarks.add(currentTitle, currentUrl)
                                refreshStartPage()
                            },
                            onHistory = {
                                showMenu = false
                                screen = Screen.History
                            },
                            onSettings = {
                                showMenu = false
                                screen = Screen.Settings
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
                    if (showAbout) {
                        AboutDialog(onDismiss = { showAbout = false })
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
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == Intent.ACTION_VIEW) {
            intent.data?.toString()?.let { navigate(it) }
        }
    }

    private fun navigate(target: String) {
        val dest = locationFor(target, searchEngine, httpsOnly)
        Log.i("ArgonNav", "submit target='$target' dest='$dest'")
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
            onExportBookmarks = { exportBookmarksLauncher.launch("argon-bookmarks.html") },
            onImportBookmarks = { importBookmarksLauncher.launch(arrayOf("text/html", "text/*", "application/octet-stream")) },
            onClearHistory = { history.clear() },
            onBack = { screen = Screen.Browser },
        )
        BackHandler { screen = Screen.Browser }
    }

    // Servo.Client
    override fun onAlert(message: String) {
        alertMessage = message
    }

    override fun onLoadStarted() {
        isLoading = true
    }

    override fun onLoadEnded() {
        if (currentUrl.isNotEmpty() && !currentUrl.startsWith("file://")) {
            history.add(currentUrl, currentTitle)
        }
        isLoading = false
    }

    override fun onTitleChanged(title: String) {
        currentTitle = title
        lastTitleForTest = title
    }

    override fun onUrlChanged(url: String) {
        currentUrl = url
        if (!omniboxFocused) {
            urlState.edit { replace(0, length, hostOf(displayableUrl(url))) }
        }
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
