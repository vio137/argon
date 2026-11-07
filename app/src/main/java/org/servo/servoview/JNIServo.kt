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

    external fun touchDown(x: Float, y: Float, pointer_id: Int)

    external fun touchMove(x: Float, y: Float, pointer_id: Int)

    external fun touchUp(x: Float, y: Float, pointer_id: Int)

    external fun touchCancel(x: Float, y: Float, pointer_id: Int)

    external fun pausePainting()

    external fun resumePainting(surface: Surface, size: Size)

    external fun setExperimentalMode(enable: Boolean)

    /** Argon: evaluate an app-bundled script; result arrives via Callbacks.onJsResult. */
    external fun evaluateScript(requestId: Int, script: String)

    external fun doFrame()

    interface Callbacks {
        fun wakeup()

        fun onAlert(message: String)

        fun onLoadStarted()

        fun onLoadEnded()

        fun onTitleChanged(title: String)

        fun onUrlChanged(url: String)

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

        fun onHistoryChanged(canGoBack: Boolean, canGoForward: Boolean)

        fun onImeShow()

        fun onImeHide()

        /** Argon: result of evaluateScript; exactly one of json/error is non-null. */
        fun onJsResult(requestId: Int, json: String?, error: String?)
    }
}
