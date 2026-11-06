/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.servo.servoview

import android.util.Log
import android.content.Context
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
import java.util.concurrent.Executors
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
                    focusRequester.requestFocus()

                    val action = motionEvent.actionMasked
                    val pointerIndex = motionEvent.actionIndex
                    val pointerId = motionEvent.getPointerId(pointerIndex)
                    val x = motionEvent.getX(pointerIndex)
                    val y = motionEvent.getY(pointerIndex)

                    when (action) {
                        MotionEvent.ACTION_DOWN,
                        MotionEvent.ACTION_POINTER_DOWN -> servo.touchDown(x, y, pointerId)
                        MotionEvent.ACTION_MOVE -> servo.touchMove(x, y, pointerId)
                        MotionEvent.ACTION_UP,
                        MotionEvent.ACTION_POINTER_UP -> servo.touchUp(x, y, pointerId)
                        MotionEvent.ACTION_CANCEL -> servo.touchCancel(x, y, pointerId)
                    }

                    true
                }
    ) {
        onSurface { surface, width, height ->
            val size = Size(width, height)

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

    fun touchDown(x: Float, y: Float, pointerId: Int) {
        scope.launch(glDispatcher) { jni.touchDown(x, y, pointerId) }
    }

    fun touchMove(x: Float, y: Float, pointerId: Int) {
        scope.launch(glDispatcher) { jni.touchMove(x, y, pointerId) }
    }

    fun touchUp(x: Float, y: Float, pointerId: Int) {
        scope.launch(glDispatcher) { jni.touchUp(x, y, pointerId) }
    }

    fun touchCancel(x: Float, y: Float, pointerId: Int) {
        scope.launch(glDispatcher) { jni.touchCancel(x, y, pointerId) }
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

    fun onDoFrame(frameTs: Long = 0L) {
        scope.launch(glDispatcher) {
            if (paintingActive.get()) {
                val t0 = System.nanoTime()
                jni.doFrame()
                me.ik.argon.PerfStats.recordFrame(frameTs, System.nanoTime() - t0)
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

        override fun onHistoryChanged(canGoBack: Boolean, canGoForward: Boolean) {
            navigator.canGoBack = canGoBack
            navigator.canGoForward = canGoForward
        }
    }
}
