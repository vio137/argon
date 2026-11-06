package me.ik.argon

import android.content.Intent
import android.content.SharedPreferences
import android.graphics.PathMeasure
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
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
import androidx.compose.runtime.rememberCoroutineScope
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
    private var readerLoading by mutableStateOf(false)
    private var readerArticle by mutableStateOf<ReaderArticle?>(null)
    private var readerError by mutableStateOf<String?>(null)
    private var readerRequestGen = 0
    private var showAbout by mutableStateOf(false)
    private var adblock by mutableStateOf(true)
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MediaFocus.start(this)
        prefs = PreferenceManager.getDefaultSharedPreferences(applicationContext)
        adblock = prefs.getBoolean("adblock", true)
        experimental = prefs.getBoolean("experimental", false)
        searchEngine = SearchEngine.fromPref(prefs.getString("engine", null))
        Blocklist.apply(this, adblock)
        history = HistoryStore(this)
        bookmarks = BookmarkStore(this)
        val startPageUrl = StartPage.write(this, bookmarks.entries, searchEngine)
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
        android.util.Log.i("ArgonNav", "ENGINE_ARGS_EFFECTIVE $engineArgs")
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
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                    when (screen) {
                        Screen.History ->
                            HistoryPage(
                                history = history,
                                onOpen = {
                                    navigate(it)
                                    screen = Screen.Browser
                                },
                                onBack = { screen = Screen.Browser },
                            )
                        Screen.Settings -> SettingsPage()
                        Screen.Browser -> {}
                    }
                    val readerScope = rememberCoroutineScope()
                    if (showMenu) {
                        MenuSheet(
                            canGoForward = navigator.canGoForward,
                            readerEnabled = ReaderMode.bridgeAvailable.value,
                            currentHost = hostOf(displayableUrl(currentUrl)),
                            onDismiss = { showMenu = false },
                            onForward = {
                                showMenu = false
                                navigator.forward()
                            },
                            onReader = {
                                showMenu = false
                                readerLoading = true
                                val gen = ++readerRequestGen
                                ReaderMode.evaluate(this@MainActivity, readerScope) { article, error ->
                                    if (gen != readerRequestGen) return@evaluate
                                    readerLoading = false
                                    when {
                                        article != null -> readerArticle = article
                                        error == ReaderMode.STALE -> Unit // navigated away; discard
                                        else -> readerError = error ?: "Couldn't extract the article."
                                    }
                                }
                            },
                            onAddToStart = {
                                showMenu = false
                                bookmarks.add(currentTitle, currentUrl)
                                StartPage.write(this@MainActivity, bookmarks.entries, searchEngine)
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
                        )
                    }
                    if (showAbout) {
                        AboutDialog(onDismiss = { showAbout = false })
                    }
                    if (readerLoading) {
                        // Non-blocking pill: no touch interception, page stays usable
                        // while extraction runs; results from a navigated-away page
                        // are discarded by the request's URL binding.
                        Row(
                            Modifier.align(Alignment.TopCenter)
                                .statusBarsPadding()
                                .padding(top = 8.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(ArgonColors.SurfaceHigh)
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                        ) {
                            Text("Preparing reader view...", color = ArgonColors.Text, fontSize = 14.sp)
                        }
                    }
                    readerArticle?.let { article ->
                        ReaderOverlay(article = article, onClose = { readerArticle = null })
                    }
                    readerError?.let { message ->
                        AlertDialog(
                            onDismissRequest = { readerError = null },
                            confirmButton = {
                                TextButton(onClick = { readerError = null }) { Text("OK") }
                            },
                            title = { Text("Reader view") },
                            text = { Text(message) },
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
        val dest = locationFor(target, searchEngine)
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
    private fun BrowserChrome(modifier: Modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier =
                modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            if (!omniboxFocused) {
                IconButton(onClick = { navigator.back() }, enabled = navigator.canGoBack) {
                    Icon(
                        painterResource(R.drawable.ic_arrow_back),
                        contentDescription = "Back",
                        tint = if (navigator.canGoBack) ArgonColors.Text else ArgonColors.Text3,
                    )
                }
            }
            OmniboxPill(Modifier.weight(1f))
            if (omniboxFocused) {
                IconButton(onClick = { submitOmnibox() }) {
                    Icon(
                        painterResource(R.drawable.ic_arrow_forward),
                        contentDescription = "Go",
                        tint = ArgonColors.Accent,
                    )
                }
            } else {
                IconButton(onClick = { if (isLoading) navigator.stop() else navigator.reload() }) {
                    Icon(
                        painterResource(if (isLoading) R.drawable.ic_close else R.drawable.ic_refresh),
                        contentDescription = if (isLoading) "Stop" else "Reload",
                        tint = ArgonColors.Text,
                    )
                }
                IconButton(onClick = { showMenu = true }) {
                    Icon(
                        painterResource(R.drawable.ic_menu),
                        contentDescription = "Menu",
                        tint = ArgonColors.Text,
                    )
                }
            }
        }
    }

    @Composable
    private fun OmniboxPill(modifier: Modifier) {
        val focusManager = LocalFocusManager.current
        val shape = RoundedCornerShape(26.dp)
        Box(modifier = modifier.height(52.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier.fillMaxSize()
                        .clip(shape)
                        .background(ArgonColors.Surface)
                        .padding(horizontal = 16.dp),
            ) {
                Icon(
                    painterResource(
                        if (currentUrl.startsWith("https://")) R.drawable.ic_lock
                        else R.drawable.ic_search
                    ),
                    contentDescription = null,
                    tint = ArgonColors.Text3,
                    modifier = Modifier.size(18.dp),
                )
                Box(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                    if (urlState.text.isEmpty()) {
                        Text(
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
                        keyboardOptions =
                            KeyboardOptions(
                                keyboardType = KeyboardType.Uri,
                                imeAction = ImeAction.Go,
                            ),
                        onKeyboardAction = { submitOmnibox() },
                        modifier =
                            Modifier.fillMaxWidth().onPreviewKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown &&
                                    (event.key == Key.Enter || event.key == Key.NumPadEnter)
                                ) {
                                    submitOmnibox()
                                    true
                                } else {
                                    false
                                }
                            }.onFocusChanged { focusState ->
                                if (focusState.isFocused && !omniboxFocused) {
                                    omniboxFocused = true
                                    urlState.edit {
                                        val shown = displayableUrl(currentUrl)
                                        if (shown.isNotEmpty()) {
                                            replace(0, length, shown)
                                            selectAll()
                                        }
                                    }
                                } else if (!focusState.isFocused && omniboxFocused) {
                                    omniboxFocused = false
                                    urlState.edit { replace(0, length, hostOf(displayableUrl(currentUrl))) }
                                }
                            },
                    )
                }
                if (urlState.text.isNotEmpty() && omniboxFocused) {
                    Icon(
                        painterResource(R.drawable.ic_close),
                        contentDescription = "Clear",
                        tint = ArgonColors.Text3,
                        modifier =
                            Modifier.size(18.dp).clickable { urlState.edit { replace(0, length, "") } },
                    )
                }
            }
            AnimatedVisibility(
                visible = isLoading,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                LoadingArc()
            }
        }
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
                StartPage.write(this@MainActivity, bookmarks.entries, it)
            },
            experimental = experimental,
            onExperimental = {
                experimental = it
                prefs.edit().putBoolean("experimental", it).apply()
            },
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
        super.onDestroy()
    }
}
