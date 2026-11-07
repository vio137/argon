/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.servo.servoview

import android.util.Log
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Size
import android.view.KeyEvent
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.Surface
import android.view.inputmethod.BaseInputConnection
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.AndroidExternalSurface
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.Interaction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.PlatformTextInputModifierNode
import androidx.compose.ui.platform.establishTextInputSession
import androidx.lifecycle.compose.LifecycleResumeEffect
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

/**
 * Engine patch 052 clock basis: the engine gets ONE monotonic clock basis -
 * the SystemClock.uptimeMillis() base at nanosecond precision - for both
 * touch events and frame times, and only ever computes deltas.
 *
 * MotionEvent.eventTimeNanos (API 34+) is already in that base; on API 29-33
 * eventTime (uptimeMillis) is scaled. Reference: developer.android.com
 * MotionEvent#getEventTimeNanos.
 */
private fun MotionEvent.eventTimeUptimeNanos(): Long =
    if (Build.VERSION.SDK_INT >= 34) eventTimeNanos else eventTime * 1_000_000L

/**
 * Choreographer/withFrameNanos frame times are System.nanoTime()-based
 * (AOSP Choreographer computes latency against System.nanoTime()), which can
 * diverge from uptime across suspend. Normalize into the uptime basis with an
 * offset resampled per call, so the conversion stays drift-safe.
 */
private fun frameTsToUptimeNanos(frameTs: Long): Long {
    if (frameTs == 0L) return 0L
    val nanoToUptimeOffset = System.nanoTime() - SystemClock.uptimeMillis() * 1_000_000L
    return frameTs - nanoToUptimeOffset
}

@Composable
fun Servo(
    experimentalModeEnabled: Boolean,
    args: String?,
    initialUrl: String?,
    logStr: String?,
    client: Servo.Client,
    navigator: ServoNavigator,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var softKeyboardVisible by remember { mutableStateOf(false) }
    val servo = remember {
        ServoHolder.servo?.also { existing ->
            existing.reattach(
                scope = scope,
                client = client,
                navigator = ServoHolder.navigator,
                onImeShow = { softKeyboardVisible = true },
                onImeHide = { softKeyboardVisible = false },
            )
        }
            ?: Servo(
                args = args,
                url = initialUrl,
                logStr = logStr,
                experimentalMode = experimentalModeEnabled,
                scope = scope,
                client = client,
                context = context,
                navigator = ServoHolder.navigator,
                onImeShow = { softKeyboardVisible = true },
                onImeHide = { softKeyboardVisible = false },
            ).also { ServoHolder.servo = it }
    }

    LifecycleResumeEffect(servo) {
        servo.suspend(false)
        onPauseOrDispose { servo.suspend(true) }
    }
    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { frameTs -> servo.onDoFrame(frameTs) }
        }
    }
    LaunchedEffect(servo, navigator) { navigator.consumeNavigationEvents(servo) }
    BackHandler(enabled = navigator.canGoBack) { navigator.back() }
    LaunchedEffect(experimentalModeEnabled) { servo.setExperimentalMode(experimentalModeEnabled) }

    val resources = LocalResources.current
    val appContext = LocalContext.current
    val focusRequester = remember { FocusRequester() }
    // Argon (engine patch 054): long-press fires the engine context menu.
    // Handler-timed; canceled by a slop-exceeding move, up, cancel, or an
    // additional pointer. After it fires, the gesture's UP is delivered as a
    // cancel so the engine never sees a stray click on the long-pressed link.
    val longPressHandler = remember { Handler(Looper.getMainLooper()) }
    val longPressState = remember { LongPressState() }
    AndroidExternalSurface(
        modifier =
            modifier
                .focusRequester(focusRequester)
                .focusable()
                .softKeyboardVisible(softKeyboardVisible)
                .onKeyEvent { keyEvent ->
                    when (keyEvent.type) {
                        KeyEventType.KeyDown if keyEvent.key != Key.Back -> {
                            servo.onKeyDown(
                                keyEvent.key.nativeKeyCode,
                                keyEvent.nativeKeyEvent,
                            )
                            true
                        }

                        KeyEventType.KeyUp if keyEvent.key != Key.Back -> {
                            servo.onKeyUp(
                                keyEvent.key.nativeKeyCode,
                                keyEvent.nativeKeyEvent,
                            )
                            true
                        }

                        else -> false
                    }
                }
                .pointerInteropFilter(null) { motionEvent ->
                    // Focus churn is main-thread work; request once per gesture,
                    // not on every batched MOVE (up to ~120/s while scrolling).
                    if (motionEvent.actionMasked == MotionEvent.ACTION_DOWN) {
                        focusRequester.requestFocus()
                    }
                    longPressState.onEvent(
                        motionEvent,
                        longPressHandler,
                        ViewConfiguration.get(appContext).scaledTouchSlop,
                    ) { x, y -> servo.contextMenuAt(x, y) }

                    val action = motionEvent.actionMasked
                    // Engine patch 052: the event's own timestamp (uptime
                    // clock base, nanos), captured once per MotionEvent and
                    // carried with each pointer dispatch, including through
                    // move coalescing (the delivered move keeps its own ts).
                    val eventTs = motionEvent.eventTimeUptimeNanos()

                    when (action) {
                        MotionEvent.ACTION_DOWN,
                        MotionEvent.ACTION_POINTER_DOWN -> {
                            val i = motionEvent.actionIndex
                            servo.touchDown(motionEvent.getX(i), motionEvent.getY(i), motionEvent.getPointerId(i), eventTs)
                        }
                        // MOVE carries EVERY active pointer (actionIndex is
                        // meaningless here); deliver each pointer's latest
                        // position. Values are copied out - the MotionEvent is
                        // recycled by the framework after dispatch.
                        MotionEvent.ACTION_MOVE -> {
                            for (i in 0 until motionEvent.pointerCount) {
                                servo.touchMove(
                                    motionEvent.getX(i),
                                    motionEvent.getY(i),
                                    motionEvent.getPointerId(i),
                                    eventTs,
                                )
                            }
                        }
                        MotionEvent.ACTION_UP,
                        MotionEvent.ACTION_POINTER_UP -> {
                            val i = motionEvent.actionIndex
                            if (action == MotionEvent.ACTION_UP && longPressState.consumeFired()) {
                                servo.touchCancel(motionEvent.getX(i), motionEvent.getY(i), motionEvent.getPointerId(i), eventTs)
                            } else {
                                servo.touchUp(motionEvent.getX(i), motionEvent.getY(i), motionEvent.getPointerId(i), eventTs)
                            }
                        }
                        MotionEvent.ACTION_CANCEL -> {
                            val i = motionEvent.actionIndex.coerceAtMost(motionEvent.pointerCount - 1)
                            servo.touchCancel(motionEvent.getX(i), motionEvent.getY(i), motionEvent.getPointerId(i), eventTs)
                        }
                    }

                    true
                }
    ) {
        onSurface { surface, width, height ->
            val size = Size(width, height)

            // High refresh rate (1.0.14): let this surface run at the panel's
            // peak rate while content is active. COMPATIBILITY_DEFAULT lets
            // the system drop the rate for static content, so idle power is
            // preserved. Actual rendered fps still depends on page work per
            // frame; this only removes the 60 Hz cap. No game classification,
            // no thermal/battery bypass.
            if (Build.VERSION.SDK_INT >= 30) {
                val maxHz = appContext.display?.supportedModes?.maxOfOrNull { it.refreshRate } ?: 60f
                runCatching { surface.setFrameRate(maxHz, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT) }
            }

            if (servo.paintingPaused) {
                servo.paintingPaused = false
                servo.resumePainting(surface, size)
            } else {
                servo.addPlatformWindow(
                    size,
                    resources.displayMetrics.density,
                    surface,
                )
            }

            surface.onChanged { width, height -> servo.resize(Size(width, height)) }

            surface.onDestroyed {
                servo.paintingPaused = true
                servo.pausePainting()
            }
        }
    }
}

/** Process-wide holder: the native engine allows exactly one instance per
 * process, so the Servo object (and the navigator whose state it updates)
 * outlive any single Activity. Recreation re-attaches instead of re-init. */
object ServoHolder {
    val navigator: ServoNavigator by lazy { ServoNavigator() }

    @Volatile var servo: Servo? = null

    @Volatile var lastUrl: String? = null

    /** Source webview of [lastUrl], captured at the same event, so the
     * reattach replay is routed by identity, not by assumed-current tab. */
    @Volatile var lastWebviewId: String = ""
}

/** Argon (engine patch 054): tracks one long-press gesture. [onEvent] arms a
 * handler timer on DOWN and cancels it on slop-exceeding move, UP, CANCEL or
 * an extra pointer; on timeout it invokes the callback with the down
 * coordinates. [consumeFired] reports (once) that the current gesture already
 * opened the menu, so its UP becomes a cancel rather than a click. */
/** Argon (engine patch 054): one context-menu entry; null label = separator. */
class ContextMenuEntry(val label: String?, val enabled: Boolean)

private class LongPressState {
    private var armed = false
    private var fired = false
    private var downX = 0f
    private var downY = 0f
    private var slopSq = 0f
    private var pending: Runnable? = null

    fun onEvent(
        ev: MotionEvent,
        handler: Handler,
        slop: Int,
        onLongPress: (Float, Float) -> Unit,
    ) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelPending(handler)
                armed = true
                fired = false
                downX = ev.x
                downY = ev.y
                slopSq = (slop * slop).toFloat()
                val r = Runnable {
                    if (armed) {
                        fired = true
                        armed = false
                        onLongPress(downX, downY)
                    }
                }
                pending = r
                handler.postDelayed(r, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> {
                if (armed) {
                    val dx = ev.x - downX
                    val dy = ev.y - downY
                    if (dx * dx + dy * dy > slopSq) {
                        armed = false
                        cancelPending(handler)
                    }
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                armed = false
                cancelPending(handler)
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                armed = false
                cancelPending(handler)
            }
        }
    }

    private fun cancelPending(handler: Handler) {
        pending?.let { handler.removeCallbacks(it) }
        pending = null
    }

    /** True exactly once after the menu fired for the current gesture. */
    fun consumeFired(): Boolean {
        val f = fired
        fired = false
        return f
    }
}

@Stable
class ServoNavigator {
    private sealed interface NavigationEvent : Interaction {
        data class Navigate(val uri: String) : NavigationEvent

        data object Back : NavigationEvent

        data object Forward : NavigationEvent

        data object Reload : NavigationEvent

        data object Stop : NavigationEvent
    }

    private val coroutineScope = CoroutineScope(EmptyCoroutineContext)
    private val navigationEvents = MutableSharedFlow<NavigationEvent>()

    internal suspend fun consumeNavigationEvents(servo: Servo) {
        navigationEvents.collect { navigationEvent ->
            when (navigationEvent) {
                is NavigationEvent.Navigate -> {
                    Log.i("ArgonNav", "engine loadUri '${navigationEvent.uri}'")
                    servo.loadUri(navigationEvent.uri)
                }
                NavigationEvent.Back -> servo.goBack()
                NavigationEvent.Forward -> servo.goForward()
                NavigationEvent.Reload -> servo.reload()
                NavigationEvent.Stop -> servo.stop()
            }
        }
    }

    /** Argon: invoked synchronously when the user initiates any navigation,
     * before the engine's load-start callback, so stale error events from the
     * previous request are dropped at display. Set by MainActivity. */
    var onNavigationInitiated: (() -> Unit)? = null

    var canGoBack by mutableStateOf(false)
        internal set

    var canGoForward by mutableStateOf(false)
        internal set

    fun navigate(uri: String) {
        onNavigationInitiated?.invoke()
        coroutineScope.launch { navigationEvents.emit(NavigationEvent.Navigate(uri)) }
    }

    fun back() {
        onNavigationInitiated?.invoke()
        coroutineScope.launch { navigationEvents.emit(NavigationEvent.Back) }
    }

    fun forward() {
        onNavigationInitiated?.invoke()
        coroutineScope.launch { navigationEvents.emit(NavigationEvent.Forward) }
    }

    fun reload() {
        onNavigationInitiated?.invoke()
        coroutineScope.launch { navigationEvents.emit(NavigationEvent.Reload) }
    }

    fun stop() {
        onNavigationInitiated?.invoke()
        coroutineScope.launch { navigationEvents.emit(NavigationEvent.Stop) }
    }
}

private fun Modifier.softKeyboardVisible(visible: Boolean) = this then SoftKeyboardVisible(visible)

private data class SoftKeyboardVisible(val visible: Boolean) :
    ModifierNodeElement<SoftKeyboardVisibleNode>() {
    override fun create() = SoftKeyboardVisibleNode()

    override fun update(node: SoftKeyboardVisibleNode) {
        node.update(visible)
    }
}

private class SoftKeyboardVisibleNode : Modifier.Node(), PlatformTextInputModifierNode {
    private var textInputSessionJob: Job? = null

    fun update(visible: Boolean) {
        textInputSessionJob?.cancel()
        if (visible) {
            textInputSessionJob = coroutineScope.launch {
                establishTextInputSession {
                    startInputMethod { _ -> BaseInputConnection(view, true) }
                }
            }
        }
    }
}

class Servo
internal constructor(
    args: String?,
    url: String?,
    logStr: String?,
    experimentalMode: Boolean,
    scope: CoroutineScope,
    client: Client,
    context: Context,
    navigator: ServoNavigator,
    onImeShow: () -> Unit,
    onImeHide: () -> Unit,
) {
    @Volatile private var scope: CoroutineScope = scope
    @Volatile internal var paintingPaused: Boolean = false

    /** Serialized on glDispatcher: true while the engine has a live window.
     * Guards native painting calls against double-pause / use-after-destroy
     * (the engine panics on unwrap when painting state is absent). */
    internal val paintingActive = java.util.concurrent.atomic.AtomicBoolean(false)
    private val jni = JNIServo()
    internal val glDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val servoCallbacks =
        Callbacks(
            client,
            jni,
            scope,
            glDispatcher,
            navigator,
            onImeShow,
            onImeHide,
        )

    init {
        scope.launch(glDispatcher) {
            jni.init(
                context,
                args,
                url,
                logStr,
                experimentalMode,
                servoCallbacks,
            )
        }
    }

    /** Re-attach this long-lived engine wrapper to a freshly created
     * Activity/Composition after recreation. */
    fun reattach(
        scope: CoroutineScope,
        client: Client,
        navigator: ServoNavigator,
        onImeShow: () -> Unit,
        onImeHide: () -> Unit,
    ) {
        Log.i("ArgonNav", "reattach: swapping scope/client/IME callbacks, lastUrl=${ServoHolder.lastUrl}")
        this.scope = scope
        servoCallbacks.reattach(scope, client, navigator, onImeShow, onImeHide)
        // Replay carries the snapshot's source webview id; if that tab was
        // closed meanwhile the client drops it (tombstoned), never
        // mis-attributes it to whatever tab is active now.
        ServoHolder.lastUrl?.let { url -> scope.launch { client.onUrlChanged(ServoHolder.lastWebviewId, url) } }
    }

    /** Argon: evaluate an app-bundled script in the active page. The callback
     * runs on the activity scope with (json, error); exactly one is non-null.
     * Not exposed to web content - callers pass bundled source only. */
    fun evaluateScript(script: String, onResult: (String?, String?) -> Unit): Int {
        val id = nextJsRequestId.incrementAndGet()
        pendingJsCallbacks[id] = onResult
        scope.launch(glDispatcher) {
            runCatching { jni.evaluateScript(id, script) }
                .onFailure {
                    pendingJsCallbacks.remove(id)
                    Log.e("ArgonJs", "evaluateScript engine error: $it")
                    // A 19-export engine .so (pre-bridge) throws UnsatisfiedLinkError;
                    // surface it distinctly so callers can disable the feature instead
                    // of crashing or retrying.
                    val message =
                        if (it is UnsatisfiedLinkError) "bridge_missing"
                        else "engine error: ${it.message}"
                    scope.launch { onResult(null, message) }
                }
        }
        return id
    }

    internal companion object {
        private val nextJsRequestId = AtomicInteger(0)
        internal val pendingJsCallbacks = ConcurrentHashMap<Int, (String?, String?) -> Unit>()

        /** Advanced on every top-level load start (navigation AND reload), so an
         * in-flight evaluateScript result can be bound to the exact document it
         * was issued on; URL equality alone misses reload and away-and-back. */
        internal val navigationGeneration = AtomicInteger(0)
    }

    fun addPlatformWindow(
        size: Size,
        density: Float,
        surface: Surface,
    ) {
        scope.launch(glDispatcher) {
            paintingActive.set(true)
            runCatching { jni.addPlatformWindow(size, density, surface) }
                .onFailure { Log.e("ArgonNav", "addPlatformWindow engine error: $it") }
        }
    }

    fun resize(size: Size) {
        scope.launch(glDispatcher) { jni.resize(size) }
    }

    fun reload() {
        scope.launch(glDispatcher) { jni.reload() }
    }

    fun stop() {
        scope.launch(glDispatcher) { jni.stop() }
    }

    fun goBack() {
        scope.launch(glDispatcher) { jni.goBack() }
    }

    fun goForward() {
        scope.launch(glDispatcher) { jni.goForward() }
    }

    fun loadUri(uri: String) {
        scope.launch(glDispatcher) { jni.loadUri(uri) }
    }

    /** Argon (patch 030): tab management; suspend on the GL executor so tab
     * ops stay ordered with navigation commands. */
    suspend fun newTab(url: String): String = withContext(glDispatcher) { jni.newTab(url) }

    suspend fun activateTab(id: String): Boolean = withContext(glDispatcher) { jni.activateTab(id) }

    suspend fun closeTab(id: String): Boolean = withContext(glDispatcher) { jni.closeTab(id) }

    suspend fun activeTabId(): String = withContext(glDispatcher) { jni.activeTabId() }

    fun onKeyDown(keyCode: Int, event: KeyEvent) {
        scope.launch(glDispatcher) { jni.keydown(keyCode, event.unicodeChar) }
    }

    fun onKeyUp(keyCode: Int, event: KeyEvent) {
        scope.launch(glDispatcher) { jni.keyup(keyCode, event.unicodeChar) }
    }

    // Touch coalescing: ACTION_MOVE streams (up to ~120/s during scroll) share
    // the single GL executor with frame rendering; uncoalesced, queued moves
    // serialize behind doFrame calls and scroll input lags whole frames.
    // Per-pointer latest-wins: tasks remove their pointer's newest pending move
    // and exit when superseded. Down/up/cancel flush pending moves first, so
    // ordering against gesture state transitions is preserved (pinch keeps each
    // pointer's latest position).
    private class MoveEvent(
        val x: Float,
        val y: Float,
        val pointerId: Int,
        val postNs: Long,
        /** The MotionEvent's own timestamp (uptime nanos); survives coalescing
         * because latest-wins keeps the newest move WITH its timestamp. */
        val eventTs: Long,
    )
    private val pendingMoves = java.util.concurrent.ConcurrentHashMap<Int, MoveEvent>()

    private fun flushPendingMoves() {
        if (pendingMoves.isEmpty()) return
        val snapshot = pendingMoves.values.toList()
        pendingMoves.clear()
        for (move in snapshot) {
            me.ik.argon.PerfStats.recordInput(move.postNs)
            runCatching { jni.touchMove(move.x, move.y, move.pointerId, move.eventTs) }
        }
    }

    fun touchDown(x: Float, y: Float, pointerId: Int, eventTimeNanos: Long = 0L) {
        scope.launch(glDispatcher) {
            flushPendingMoves()
            jni.touchDown(x, y, pointerId, eventTimeNanos)
        }
    }

    fun touchMove(x: Float, y: Float, pointerId: Int, eventTimeNanos: Long = 0L) {
        me.ik.argon.PerfStats.recordInputPosted()
        pendingMoves[pointerId] = MoveEvent(x, y, pointerId, System.nanoTime(), eventTimeNanos)
        scope.launch(glDispatcher) {
            val move = pendingMoves.remove(pointerId)
            if (move == null) {
                me.ik.argon.PerfStats.recordInputSuperseded()
                return@launch // superseded by a newer move
            }
            me.ik.argon.PerfStats.recordInput(move.postNs)
            runCatching { jni.touchMove(move.x, move.y, move.pointerId, move.eventTs) }
        }
    }

    fun touchUp(x: Float, y: Float, pointerId: Int, eventTimeNanos: Long = 0L) {
        scope.launch(glDispatcher) {
            flushPendingMoves()
            jni.touchUp(x, y, pointerId, eventTimeNanos)
        }
    }

    fun touchCancel(x: Float, y: Float, pointerId: Int, eventTimeNanos: Long = 0L) {
        scope.launch(glDispatcher) {
            flushPendingMoves()
            jni.touchCancel(x, y, pointerId, eventTimeNanos)
        }
    }
    /** Argon (engine patch 054): long-press context menu trigger. */
    fun contextMenuAt(x: Float, y: Float) {
        scope.launch(glDispatcher) { jni.contextMenuAt(x, y) }
    }

    /** Argon (engine patch 054): reply to a context menu. */
    fun contextMenuResult(handle: Int, itemIndex: Int) {
        scope.launch(glDispatcher) { jni.contextMenuResult(handle, itemIndex) }
    }


    fun pausePainting() {
        scope.launch(glDispatcher) {
            if (paintingActive.compareAndSet(true, false)) {
                runCatching { jni.pausePainting() }
                    .onFailure { Log.e("ArgonNav", "pausePainting engine error (tolerated on teardown): $it") }
            }
        }
    }

    fun resumePainting(surface: Surface, size: Size) {
        scope.launch(glDispatcher) {
            paintingActive.set(true)
            runCatching { jni.resumePainting(surface, size) }
                .onFailure { Log.e("ArgonNav", "resumePainting engine error: $it") }
        }
    }

    fun suspend(suspended: Boolean) {
        servoCallbacks.suspended = suspended
    }

    fun setExperimentalMode(enable: Boolean) {
        scope.launch(glDispatcher) { jni.setExperimentalMode(enable) }
    }

    // Latest-vsync-wins coalescing: every vsync posts a task, but a queued
    // task whose frame was superseded exits immediately. This bounds the GL
    // queue to one pending frame and always renders the newest vsync -
    // run#13 measured a 1.5-4.6s queue backlog without it.
    private val pendingFrameTs = java.util.concurrent.atomic.AtomicLong(-1L)

    fun onDoFrame(frameTs: Long = 0L) {
        pendingFrameTs.set(frameTs)
        scope.launch(glDispatcher) {
            val ts = pendingFrameTs.getAndSet(-1L)
            if (ts == -1L) return@launch // superseded; a newer task renders it
            if (paintingActive.get()) {
                val t0 = System.nanoTime()
                jni.doFrame(frameTsToUptimeNanos(ts))
                me.ik.argon.PerfStats.recordFrame(ts, t0, System.nanoTime() - t0)
            }
        }
    }

    interface Client {
            /** All webview-state callbacks carry the source webview id (engine
         * patch 030 v2); clients route by source, never by assumed-current. */
        fun onAlert(webviewId: String, message: String)

        fun onLoadStarted(webviewId: String)

        fun onLoadEnded(webviewId: String)

        fun onTitleChanged(webviewId: String, title: String)

        fun onUrlChanged(webviewId: String, url: String)

        fun onHistoryChanged(webviewId: String, canGoBack: Boolean, canGoForward: Boolean)

        /** Engine-initiated webview close (engine patch 030 v4). */
        fun onTabClosed(webviewId: String)

        fun onLoadError(
            webviewId: String,
            pipelineId: String,
            url: String,
            kind: String,
            reason: String,
            generation: Long,
        )

        fun onTopLevelLoad(webviewId: String, pipelineId: String, url: String, generation: Long)

        /** Argon (engine patch 054): web-content context menu. items are
         * (label, enabled); null label = separator. linkUrl/imageUrl null
         * when absent. Reply with Servo.contextMenuResult(handle, index|-1). */
        fun onContextMenu(
            webviewId: String,
            handle: Int,
            linkUrl: String?,
            imageUrl: String?,
            items: List<ContextMenuEntry>,
        )
    }

    private class Callbacks(
        client: Client,
        private val jni: JNIServo,
        scope: CoroutineScope,
        private val glDispatcher: CoroutineDispatcher,
        navigator: ServoNavigator,
        onImeShow: () -> Unit,
        onImeHide: () -> Unit,
    ) : JNIServo.Callbacks, Client {
        @Volatile private var client: Client = client
        @Volatile private var scope: CoroutineScope = scope
        @Volatile private var navigator: ServoNavigator = navigator
        @Volatile private var onImeShow: () -> Unit = onImeShow
        @Volatile private var onImeHide: () -> Unit = onImeHide
        var suspended: Boolean = false

        fun reattach(
            scope: CoroutineScope,
            client: Client,
            navigator: ServoNavigator,
            onImeShow: () -> Unit,
            onImeHide: () -> Unit,
        ) {
            this.scope = scope
            this.client = client
            this.navigator = navigator
            this.onImeShow = onImeShow
            this.onImeHide = onImeHide
        }

        override fun wakeup() {
            if (!suspended) {
                scope.launch(glDispatcher) { jni.performUpdates() }
            }
        }

        // NOTE: performUpdates is a no-op tick; left unguarded (no unwrap risk
        // observed). pausePainting/doFrame carry the unwrap-on-None risk and
        // are guarded at the Servo level via paintingActive.

        override fun onAlert(webviewId: String, message: String) {
            scope.launch { client.onAlert(webviewId, message) }
        }

        override fun onContextMenu(
            webviewId: String,
            handle: Int,
            flags: Int,
            linkUrl: String,
            imageUrl: String,
            x: Int,
            y: Int,
            width: Int,
            height: Int,
            labelsJoined: String,
            enabledJoined: String,
        ) {
            val labels = labelsJoined.split('\n')
            val items =
                labels.mapIndexed { i, label ->
                    val enabled = enabledJoined.getOrNull(i) == '1'
                    if (label.isEmpty()) ContextMenuEntry(null, false)
                    else ContextMenuEntry(label, enabled)
                }
            scope.launch {
                client.onContextMenu(
                    webviewId,
                    handle,
                    linkUrl.ifEmpty { null },
                    imageUrl.ifEmpty { null },
                    items,
                )
            }
        }

        // Client-side half (Callbacks implements both JNIServo.Callbacks and
        // Client): delegate to the current client, same as the other relays.
        override fun onContextMenu(
            webviewId: String,
            handle: Int,
            linkUrl: String?,
            imageUrl: String?,
            items: List<ContextMenuEntry>,
        ) {
            scope.launch { client.onContextMenu(webviewId, handle, linkUrl, imageUrl, items) }
        }

        override fun onImeShow() {
            scope.launch { onImeShow.invoke() }
        }

        override fun onImeHide() {
            scope.launch { onImeHide.invoke() }
        }

        override fun onLoadStarted(webviewId: String) {
            Log.i("ArgonNav", "loadStarted")
            Servo.navigationGeneration.incrementAndGet()
            scope.launch { client.onLoadStarted(webviewId) }
        }

        override fun onLoadEnded(webviewId: String) {
            Log.i("ArgonNav", "loadEnded")
            scope.launch { client.onLoadEnded(webviewId) }
        }

        override fun onTitleChanged(webviewId: String, title: String) {
            scope.launch { client.onTitleChanged(webviewId, title) }
        }

        override fun onUrlChanged(webviewId: String, url: String) {
            Log.i("ArgonNav", "urlChanged $url")
            ServoHolder.lastUrl = url
            ServoHolder.lastWebviewId = webviewId
            scope.launch { client.onUrlChanged(webviewId, url) }
        }

        override fun onLoadError(
            webviewId: String,
            pipelineId: String,
            url: String,
            kind: String,
            reason: String,
            generation: Long,
        ) {
            Log.i("ArgonNav", "loadError kind=$kind gen=$generation")
            scope.launch { client.onLoadError(webviewId, pipelineId, url, kind, reason, generation) }
        }

        override fun onTopLevelLoad(webviewId: String, pipelineId: String, url: String, generation: Long) {
            Log.i("ArgonNav", "topLevelLoad gen=$generation")
            scope.launch { client.onTopLevelLoad(webviewId, pipelineId, url, generation) }
        }

        override fun onJsResult(requestId: Int, json: String?, error: String?) {
            val callback = Servo.pendingJsCallbacks.remove(requestId) ?: return
            scope.launch { callback(json, error) }
        }

        // History state routes through the client so per-tab routing can
        // publish it to the navigator only when the source tab is active.
        override fun onHistoryChanged(webviewId: String, canGoBack: Boolean, canGoForward: Boolean) {
            scope.launch { client.onHistoryChanged(webviewId, canGoBack, canGoForward) }
        }

        override fun onTabClosed(webviewId: String) {
            Log.i("ArgonNav", "tabClosed")
            scope.launch { client.onTabClosed(webviewId) }
        }
    }
}
