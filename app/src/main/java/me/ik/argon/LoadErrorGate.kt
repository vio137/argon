package me.ik.argon

/**
 * Identity gate for engine load-error events (engine patch 020 v3).
 *
 * The engine stamps every top-level load start with a (webviewId, pipelineId,
 * generation) triple delivered via onTopLevelLoad, and attaches the failing
 * load's triple to each error. The error card shows only when the triple
 * matches the current request exactly. URL is display payload only: redirects
 * change it mid-load, so it must never be part of the match. Generation 0
 * marks loads the engine does not track and never matches.
 *
 * Ordering: engine signals arrive in FIFO order on the main dispatcher, so a
 * load's start always lands before its failure. App-initiated navigation
 * (omnibox submit, back/forward/reload/stop) invalidates immediately, before
 * the engine's start callback, so an old error arriving in between is dropped.
 */
class LoadErrorGate {
    data class Identity(val webviewId: String, val pipelineId: String, val generation: Long)

    var current: Identity? = null
        private set

    /** Engine: a top-level load started; adopt its identity. */
    fun onTopLevelLoad(webviewId: String, pipelineId: String, generation: Long) {
        if (generation == 0L) return
        current = Identity(webviewId, pipelineId, generation)
    }

    /** App: the user just initiated any navigation; drop the old identity now. */
    fun onNavigationInitiated() {
        current = null
    }

    /** True when the error belongs to the current request. */
    fun shouldShowError(webviewId: String, pipelineId: String, generation: Long): Boolean {
        val c = current ?: return false
        if (generation == 0L) return false
        return c.webviewId == webviewId && c.pipelineId == pipelineId && c.generation == generation
    }
}
