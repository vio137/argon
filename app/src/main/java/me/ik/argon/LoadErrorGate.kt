package me.ik.argon

/**
 * Identity gate for engine load-error events (engine patch 020 v3), keyed per
 * webview since engine patch 030 (tabs).
 *
 * The engine stamps every top-level load start with a (webviewId, pipelineId,
 * generation) triple delivered via onTopLevelLoad, and attaches the failing
 * load's triple to each error. The error card shows only when the triple
 * matches the current request of ITS OWN webview exactly - an error from a
 * background tab never displaces or matches the active tab's identity. URL is
 * display payload only: redirects change it mid-load, so it must never be part
 * of the match. Generation 0 marks loads the engine does not track and never
 * matches.
 *
 * Ordering: engine signals arrive in FIFO order on the main dispatcher, so a
 * load's start always lands before its failure. App-initiated navigation
 * (omnibox submit, back/forward/reload/stop) acts on the active webview and
 * invalidates that webview's identity immediately, before the engine's start
 * callback, so an old error arriving in between is dropped.
 */
class LoadErrorGate {
    data class Identity(val webviewId: String, val pipelineId: String, val generation: Long)

    private val currentByWebview = mutableMapOf<String, Identity>()

    /** Current request identity of one webview (test introspection). */
    fun currentFor(webviewId: String): Identity? = currentByWebview[webviewId]

    /** Engine: a top-level load started; adopt its identity for its webview.
     * Generation 0 marks an untracked load (fail-closed overflow policy); it
     * clears that webview's identity rather than adopting one, so no error can
     * match. */
    fun onTopLevelLoad(webviewId: String, pipelineId: String, generation: Long) {
        if (generation == 0L) currentByWebview.remove(webviewId)
        else currentByWebview[webviewId] = Identity(webviewId, pipelineId, generation)
    }

    /** App: the user just initiated a navigation on [webviewId] (the active
     * tab); drop that webview's old identity now. */
    fun onNavigationInitiated(webviewId: String) {
        currentByWebview.remove(webviewId)
    }

    /** True when the error belongs to the current request of its own webview. */
    fun shouldShowError(webviewId: String, pipelineId: String, generation: Long): Boolean {
        if (generation == 0L) return false
        val c = currentByWebview[webviewId] ?: return false
        return c.pipelineId == pipelineId && c.generation == generation
    }
}
