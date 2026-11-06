/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.servo.servoview

import android.util.Log
import android.content.Context
import android.os.Build
import android.util.Size
import android.view.KeyEvent
import android.view.MotionEvent
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
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

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

                    val action = motionEvent.actionMasked

                    when (action) {
                        MotionEvent.ACTION_DOWN,
                        MotionEvent.ACTION_POINTER_DOWN -> {
                            val i = motionEvent.actionIndex
                            servo.touchDown(motionEvent.getX(i), motionEvent.getY(i), motionEvent.getPointerId(i))
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
                                )
                            }
                        }
                        MotionEvent.ACTION_UP,
                        MotionEvent.ACTION_POINTER_UP -> {
                            val i = motionEvent.actionIndex
                            servo.touchUp(motionEvent.getX(i), motionEvent.getY(i), motionEvent.getPointerId(i))
                        }
                        MotionEvent.ACTION_CANCEL -> {
                            val i = motionEvent.actionIndex.coerceAtMost(motionEvent.pointerCount - 1)
                            servo.touchCancel(motionEvent.getX(i), motionEvent.getY(i), motionEvent.getPointerId(i))
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

    var canGoBack by mutableStateOf(false)
        internal set

    var canGoForward by mutableStateOf(false)
        internal set

    fun navigate(uri: String) {
        coroutineScope.launch { navigationEvents.emit(NavigationEvent.Navigate(uri)) }
    }

    fun back() {
        coroutineScope.launch { navigationEvents.emit(NavigationEvent.Back) }
    }

    fun forward() {
        coroutineScope.launch { navigationEvents.emit(NavigationEvent.Forward) }
    }

    fun reload() {
        coroutineScope.launch { navigationEvents.emit(NavigationEvent.Reload) }
    }

    fun stop() {
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
        ServoHolder.lastUrl?.let { url -> scope.launch { client.onUrlChanged(url) } }
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
    private class MoveEvent(val x: Float, val y: Float, val pointerId: Int, val postNs: Long)
    private val pendingMoves = java.util.concurrent.ConcurrentHashMap<Int, MoveEvent>()

    private fun flushPendingMoves() {
        if (pendingMoves.isEmpty()) return
        val snapshot = pendingMoves.values.toList()
        pendingMoves.clear()
        for (move in snapshot) {
            me.ik.argon.PerfStats.recordInput(move.postNs)
            runCatching { jni.touchMove(move.x, move.y, move.pointerId) }
        }
    }

    fun touchDown(x: Float, y: Float, pointerId: Int) {
        scope.launch(glDispatcher) {
            flushPendingMoves()
            jni.touchDown(x, y, pointerId)
        }
    }

    fun touchMove(x: Float, y: Float, pointerId: Int) {
        me.ik.argon.PerfStats.recordInputPosted()
        pendingMoves[pointerId] = MoveEvent(x, y, pointerId, System.nanoTime())
        scope.launch(glDispatcher) {
            val move = pendingMoves.remove(pointerId)
            if (move == null) {
                me.ik.argon.PerfStats.recordInputSuperseded()
                return@launch // superseded by a newer move
            }
            me.ik.argon.PerfStats.recordInput(move.postNs)
            runCatching { jni.touchMove(move.x, move.y, move.pointerId) }
        }
    }

    fun touchUp(x: Float, y: Float, pointerId: Int) {
        scope.launch(glDispatcher) {
            flushPendingMoves()
            jni.touchUp(x, y, pointerId)
        }
    }

    fun touchCancel(x: Float, y: Float, pointerId: Int) {
        scope.launch(glDispatcher) {
            flushPendingMoves()
            jni.touchCancel(x, y, pointerId)
        }
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
                jni.doFrame()
                me.ik.argon.PerfStats.recordFrame(ts, t0, System.nanoTime() - t0)
            }
        }
    }

    interface Client {
        fun onAlert(message: String)

        fun onLoadStarted()

        fun onLoadEnded()

        fun onTitleChanged(title: String)

        fun onUrlChanged(url: String)
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

        override fun onAlert(message: String) {
            scope.launch { client.onAlert(message) }
        }

        override fun onImeShow() {
            scope.launch { onImeShow.invoke() }
        }

        override fun onImeHide() {
            scope.launch { onImeHide.invoke() }
        }

        override fun onLoadStarted() {
            Log.i("ArgonNav", "loadStarted")
            Servo.navigationGeneration.incrementAndGet()
            scope.launch { client.onLoadStarted() }
        }

        override fun onLoadEnded() {
            Log.i("ArgonNav", "loadEnded")
            scope.launch { client.onLoadEnded() }
        }

        override fun onTitleChanged(title: String) {
            scope.launch { client.onTitleChanged(title) }
        }

        override fun onUrlChanged(url: String) {
            Log.i("ArgonNav", "urlChanged $url")
            ServoHolder.lastUrl = url
            scope.launch { client.onUrlChanged(url) }
        }

        override fun onJsResult(requestId: Int, json: String?, error: String?) {
            val callback = Servo.pendingJsCallbacks.remove(requestId) ?: return
            scope.launch { callback(json, error) }
        }

        override fun onHistoryChanged(canGoBack: Boolean, canGoForward: Boolean) {
            navigator.canGoBack = canGoBack
            navigator.canGoForward = canGoForward
        }
    }
}
