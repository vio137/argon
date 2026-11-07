/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.servo.servoview

import android.content.Context
import android.util.Size
import android.view.Surface

/** Maps /ports/servoshell API */
internal class JNIServo {
    init {
        System.loadLibrary("c++_shared")
        System.loadLibrary("servoshell")
    }

    external fun init(
        context: Context,
        args: String?,
        url: String?,
        logStr: String?,
        experimentalMode: Boolean,
        callbacks: Callbacks,
    )

    external fun addPlatformWindow(
        size: Size,
        density: Float,
        surface: Surface,
    )

    external fun performUpdates()

    external fun resize(size: Size)

    external fun reload()

    external fun stop()

    external fun goBack()

    external fun goForward()

    external fun loadUri(uri: String)

    /** Argon (patch 030): open a new tab on [url], activate it, return its
     * opaque id (empty on failure). */
    external fun newTab(url: String): String

    /** Argon (patch 030): activate the tab with opaque [id]; true if found. */
    external fun activateTab(id: String): Boolean

    /** Argon (patch 030): close the tab with opaque [id]; true if found. */
    external fun closeTab(id: String): Boolean

    /** Argon (patch 030): active tab's opaque id (empty when none). */
    external fun activeTabId(): String

    external fun keydown(keycode: Int, unicode: Int)

    external fun keyup(keycode: Int, unicode: Int)

    /** [eventTimeNanos]: event time in the SystemClock.uptimeMillis() clock
     * base with nanosecond precision (see Servo.eventTimeUptimeNanos);
     * 0 = unknown. Engine patch 052: feeds the time-normalized fling estimator. */
    external fun touchDown(x: Float, y: Float, pointer_id: Int, eventTimeNanos: Long)

    external fun touchMove(x: Float, y: Float, pointer_id: Int, eventTimeNanos: Long)

    external fun touchUp(x: Float, y: Float, pointer_id: Int, eventTimeNanos: Long)

    external fun touchCancel(x: Float, y: Float, pointer_id: Int, eventTimeNanos: Long)

    external fun pausePainting()

    external fun resumePainting(surface: Surface, size: Size)

    external fun setExperimentalMode(enable: Boolean)

    /** Argon: evaluate an app-bundled script; result arrives via Callbacks.onJsResult. */
    external fun evaluateScript(requestId: Int, script: String)

    /** [frameTimeNanos]: frame time in the SystemClock.uptimeMillis() clock
     * base with nanosecond precision (see Servo.frameTsToUptimeNanos);
     * 0 = unknown. Engine patch 052: drives refresh-rate-independent fling decay. */
    external fun doFrame(frameTimeNanos: Long)

    /** Argon (engine patch 054): trigger the engine context-menu path at
     * viewport coordinates (secondary-button down/up; page can cancel via the
     * contextmenu event). */
    external fun contextMenuAt(x: Float, y: Float)

    /** Argon (engine patch 054): reply to onContextMenu. itemIndex >= 0
     * selects that item; -1 dismisses. Unknown/stale handles are ignored. */
    external fun contextMenuResult(handle: Int, itemIndex: Int)

    interface Callbacks {
        fun wakeup()

        /** Argon (engine patch 030 v2): all webview-state callbacks carry the
         * SOURCE webview id as first parameter, so events queued before a tab
         * switch or from a background tab can be routed by source, never by
         * assumed-current tab. */
        fun onAlert(webviewId: String, message: String)

        fun onLoadStarted(webviewId: String)

        fun onLoadEnded(webviewId: String)

        fun onTitleChanged(webviewId: String, title: String)

        fun onUrlChanged(webviewId: String, url: String)

        /** Argon (engine patch 020 v3): a top-level page load failed. kind is
         * "network" | "tls" | "crash"; reason is a fixed ASCII category token.
         * (webviewId, pipelineId, generation) identify the failing load; show
         * only when they match the current request identity. Iframe and
         * subresource failures never fire this. */
        fun onLoadError(
            webviewId: String,
            pipelineId: String,
            url: String,
            kind: String,
            reason: String,
            generation: Long,
        )

        /** Argon (engine patch 020 v3): a top-level load started; carries its
         * request identity. Always precedes any error of the same load. */
        fun onTopLevelLoad(webviewId: String, pipelineId: String, url: String, generation: Long)

        fun onHistoryChanged(webviewId: String, canGoBack: Boolean, canGoForward: Boolean)

        /** Argon (engine patch 030 v4): a webview was closed by the engine
         * (window.close, teardown) - covers closes that never passed through
         * the app's own closeTab, so the app can tombstone the id. */
        fun onTabClosed(webviewId: String)

        fun onImeShow()

        fun onImeHide()

        /** Argon: result of evaluateScript; exactly one of json/error is non-null. */
        fun onJsResult(requestId: Int, json: String?, error: String?)

        /** Argon (engine patch 054): web-content context menu requested.
         * labelsJoined: items separated by a newline (empty label = separator;
         * engine labels are fixed literals, never contain newlines).
         * enabledJoined: parallel '1'/'0' per item. linkUrl/imageUrl empty
         * when absent. x/y/width/height bound the hit element in viewport px. */
        fun onContextMenu(
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
        )
    }
}
