package me.ik.argon

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import java.io.File
import me.ik.argon.ui.ArgonTabStrip
import me.ik.argon.ui.TabStripData
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import me.ik.argon.ui.ArgonCommandBar
import org.junit.Rule
import org.junit.Test

/**
 * Local host-rendered screenshots of the redesign v2 chrome (Paparazzi,
 * JVM layoutlib - no device or emulator). Design-verification evidence only;
 * not a runtime behavioral test.
 */
class ChromeScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_6)


    @Test
    fun commandDeckIdle() {
        paparazzi.snapshot {
            ArgonCommandBar(
                urlState = TextFieldState("servo.org/docs"),
                currentDisplayUrl = "servo.org",
                canGoBack = true,
                canGoForward = true,
                isLoading = false,
                focused = false,
                onFocusChange = {},
                onSubmit = {},
                onBack = {},
                onForward = {},
                onStopOrReload = {},
                onMenu = {},
                loadingArc = {},
            )
        }
    }

    @Test
    fun commandDeckFocused() {
        paparazzi.snapshot {
            ArgonCommandBar(
                urlState = TextFieldState("design tokens"),
                currentDisplayUrl = "servo.org",
                canGoBack = true,
                canGoForward = false,
                isLoading = false,
                focused = true,
                onFocusChange = {},
                onSubmit = {},
                onBack = {},
                onForward = {},
                onStopOrReload = {},
                onMenu = {},
                loadingArc = {},
            )
        }
    }

    @Test
    fun tabStrip() {
        paparazzi.snapshot {
            ArgonTabStrip(
                data =
                    TabStripData(
                        chips =
                            listOf(
                                "1" to "github.com",
                                "2" to "wikipedia.org",
                                "3" to "servo.org",
                            ),
                        activeId = "1",
                    ),
                onSelect = {},
                onClose = {},
                onNew = {},
                onLongPress = {},
            )
        }
    }

    /** Combined scaffold: strip above the deck pinned to the bottom of a
     * full-height dark surface - checks placement, overlap and insets as far
     * as the harness supports (no real nav-bar/IME). */
    @Test
    fun scaffoldNarrow() {
        paparazzi.snapshot {
            Box(Modifier.fillMaxSize().background(ArgonColors.Bg)) {
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                    ArgonCommandBar(
                        urlState = TextFieldState("servo.org/docs"),
                        currentDisplayUrl = "servo.org",
                        canGoBack = true,
                        canGoForward = true,
                        isLoading = true,
                        focused = false,
                        onFocusChange = {},
                        onSubmit = {},
                        onBack = {},
                        onForward = {},
                        onStopOrReload = {},
                        onMenu = {},
                        loadingArc = {},
                    )
                }
            }
        }
    }

    @Test
    fun tabStripCompactMonograms() {
        paparazzi.snapshot {
            ArgonTabStrip(
                data =
                    TabStripData(
                        chips =
                            listOf(
                                "1" to "github.com",
                                "2" to "wikipedia.org",
                                "3" to "news.ycombinator.com",
                                "4" to "servo.org",
                                "5" to "developer.mozilla.org",
                                "6" to "reddit.com",
                            ),
                        activeId = "2",
                    ),
                onSelect = {},
                onClose = {},
                onNew = {},
                onLongPress = {},
                compact = true,
            )
        }
    }

    @Test
    fun tabStripLongTitles() {
        paparazzi.snapshot {
            ArgonTabStrip(
                data =
                    TabStripData(
                        chips =
                            listOf(
                                "1" to "A very long page title that must ellipsize cleanly in the chip",
                                "2" to "another quite lengthy document title for width pressure",
                                "3" to "servo.org",
                            ),
                        activeId = "1",
                    ),
                onSelect = {},
                onClose = {},
                onNew = {},
                onLongPress = {},
            )
        }
    }

    @Test
    fun settingsScreen() {
        paparazzi.snapshot {
            SettingsScreen(
                adblock = true,
                onAdblock = {},
                searchEngine = SearchEngine.entries.first(),
                onSearchEngine = {},
                experimental = false,
                onExperimental = {},
                httpsOnly = true,
                onHttpsOnly = {},
                clearOnExit = false,
                onClearOnExit = {},
                omniboxShortcut = OmniboxShortcut.NONE,
                onOmniboxShortcut = {},
                compactTabs = false,
                onCompactTabs = {},
                closeButtonsBackgroundTabs = true,
                onCloseButtonsBackgroundTabs = {},
                onExportBookmarks = {},
                onImportBookmarks = {},
                onClearHistory = {},
                onBack = {},
            )
        }
    }

    @Test
    fun commandDeckNarrowLargeFont() {
        // Small-phone width (~291dp at 2.75 density) with 1.3x font scale.
        paparazzi.unsafeUpdateConfig(
            deviceConfig = DeviceConfig.PIXEL_6.copy(screenWidth = 800, fontScale = 1.3f),
        )
        paparazzi.snapshot {
            ArgonCommandBar(
                urlState = TextFieldState("servo.org/docs"),
                currentDisplayUrl = "servo.org",
                canGoBack = true,
                canGoForward = true,
                isLoading = false,
                focused = false,
                onFocusChange = {},
                onSubmit = {},
                onBack = {},
                onForward = {},
                onStopOrReload = {},
                onMenu = {},
                loadingArc = {},
            )
        }
    }

    private fun tempStoreFile(name: String) =
        File(System.getProperty("java.io.tmpdir"), "argon-$name-${System.nanoTime()}.json")

    @Test
    fun historyPage() {
        val history = HistoryStore(tempStoreFile("history"))
        history.add("https://servo.org/docs", "Servo documentation")
        history.add("https://github.com/servo/servo", "servo/servo: the Servo browser engine")
        history.add("https://developer.mozilla.org/en-US/docs/Web", "MDN Web Docs")
        val closed = RecentlyClosedStore(tempStoreFile("closed"))
        closed.add("https://news.ycombinator.com", "Hacker News")
        paparazzi.snapshot {
            HistoryPage(
                history = history,
                onOpen = {},
                onBack = {},
                recentlyClosed = closed,
                onReopenClosed = {},
            )
        }
    }

    @Test
    fun bookmarksPage() {
        val bookmarks = BookmarkStore(tempStoreFile("bookmarks"))
        bookmarks.add("Servo", "https://servo.org")
        bookmarks.add("GitHub", "https://github.com")
        bookmarks.add("MDN Web Docs", "https://developer.mozilla.org")
        bookmarks.add("A bookmark with a particularly long title to check row ellipsis behavior", "https://example.com/long")
        paparazzi.snapshot {
            BookmarksPage(bookmarks = bookmarks, onOpen = {}, onBack = {})
        }
    }

    @Test
    fun libraryPage() {
        val bookmarks = BookmarkStore(tempStoreFile("lib-bm"))
        bookmarks.add("Servo", "https://servo.org")
        val history = HistoryStore(tempStoreFile("lib-hist"))
        history.add("https://servo.org/docs", "Servo documentation")
        paparazzi.snapshot {
            LibraryPage(bookmarks = bookmarks, history = history, onOpen = {}, onBack = {})
        }
    }

    /** Sheet content pixels only: modal lifecycle and sheet insets are not
     *  covered by this harness (ModalBottomSheet hangs layoutlib). */
    @Test
    fun menuSheetContent() {
        paparazzi.snapshot {
            Box(Modifier.background(ArgonColors.Bg)) {
                MenuSheetContent(
                    canGoForward = true,
                    shareEnabled = true,
                    onShare = {},
                    onFind = {},
                    onCopyLink = {},
                    onOpenIn = {},
                    onBookmarks = {},
                    currentHost = "servo.org",
                    onForward = {},
                    startPaged = false,
                    startToggleEnabled = true,
                    onAddToStart = {},
                    onHistory = {},
                    onSettings = {},
                    onWax = {},
                    onAbout = {},
                    onExit = {},
                )
            }
        }
    }

    @Test
    fun onboardingWelcome() {
        val owner =
            object : OnBackPressedDispatcherOwner, LifecycleOwner {
                private val registry = LifecycleRegistry(this)
                override val onBackPressedDispatcher = OnBackPressedDispatcher()
                override val lifecycle: Lifecycle
                    get() = registry
            }
        paparazzi.snapshot {
            CompositionLocalProvider(LocalOnBackPressedDispatcherOwner provides owner) {
                OnboardingScreen(
                    adblock = true,
                    searchEngine = SearchEngine.entries.first(),
                    onAdblockChange = {},
                    onEngineChange = {},
                    onDone = {},
                )
            }
        }
    }

    @Test
    fun onboardingPrivacyPage() {
        paparazzi.snapshot {
            Box(Modifier.fillMaxSize().background(ArgonColors.Bg)) {
                PrivacyPage(adblock = true, onAdblockChange = {})
            }
        }
    }

    @Test
    fun onboardingEnginePage() {
        paparazzi.snapshot {
            Box(Modifier.fillMaxSize().background(ArgonColors.Bg)) {
                EnginePage(searchEngine = SearchEngine.entries.first(), onEngineChange = {})
            }
        }
    }

    /** Narrow (~291dp) + 1.3x font: the sheet body scrolls (verticalScroll);
     *  a static render can only show the top of the scroll region. */
    @Test
    fun menuSheetContentNarrow() {
        paparazzi.unsafeUpdateConfig(
            deviceConfig = DeviceConfig.PIXEL_6.copy(screenWidth = 800, fontScale = 1.3f),
        )
        paparazzi.snapshot {
            Box(Modifier.background(ArgonColors.Bg)) {
                MenuSheetContent(
                    canGoForward = true,
                    shareEnabled = true,
                    onShare = {},
                    onFind = {},
                    onCopyLink = {},
                    onOpenIn = {},
                    onBookmarks = {},
                    currentHost = "servo.org",
                    onForward = {},
                    startPaged = false,
                    startToggleEnabled = true,
                    onAddToStart = {},
                    onHistory = {},
                    onSettings = {},
                    onWax = {},
                    onAbout = {},
                    onExit = {},
                )
            }
        }
    }
}
