package me.ik.argon

/**
 * Tab state routing for multi-webview browsing (engine patch 030).
 *
 * Pure state machine with no Android/Compose deps so it is unit-testable.
 * Every engine callback is routed by its SOURCE webview id, never by the
 * assumed-current tab: an event queued before a tab switch, or from a
 * background tab, lands on its own tab's state and is published to the UI
 * fields only when its source is the active tab.
 */
class TabManager(private val maxTabs: Int = DEFAULT_MAX_TABS) {
    class TabState(
        var title: String = "",
        var url: String = "",
        var canGoBack: Boolean = false,
        var canGoForward: Boolean = false,
        var loading: Boolean = false,
    )

    /** Open-tab order, oldest first. */
    val order = mutableListOf<String>()
    val states = mutableMapOf<String, TabState>()

    /** Ids of closed tabs, kept forever. WebViewIds are never reused
     * engine-side (monotonic, opaque), so an unknown id that is NOT
     * tombstoned can only belong to a live engine webview (initial webview,
     * or an auxiliary window.open the engine created and activated). A
     * tombstoned id can only be a stale event from a dead tab and is never
     * re-adopted - closed tabs cannot be resurrected by racing callbacks. */
    private val tombstones = mutableSetOf<String>()

    /** Engine-reported active tab id; empty until the first webview reports in. */
    var activeId: String = ""
        private set

    /** Route an engine event to its source tab. Adopts unknown ids that are
     * not tombstoned (they can only be live engine webviews - see tombstones).
     * Tombstoned ids and empty ids route nowhere: the event is dropped.
     * Returns null when the event must be dropped. */
    fun route(webviewId: String): TabState? {
        if (webviewId.isEmpty()) return null
        if (webviewId in tombstones) return null
        return adopt(webviewId)
    }

    /** True when [webviewId] is a closed-tab id (dropped on arrival). */
    fun isTombstoned(webviewId: String): Boolean = webviewId in tombstones

    private fun adopt(id: String): TabState {
        if (id !in states) {
            order.add(id)
            states[id] = TabState()
            if (activeId.isEmpty()) activeId = id
        }
        return states.getValue(id)
    }

    /** True when [webviewId] is the active tab. Empty ids are never active:
     * every engine callback carries its source id (patch 030 v2). */
    fun isActive(webviewId: String): Boolean = webviewId.isNotEmpty() && webviewId == activeId

    fun markActive(webviewId: String) {
        activeId = webviewId
    }

    /** User-facing cap on open tabs. The SAME bound is enforced engine-side
     * (patch 030 v3) on every creation path including window.open, so this
     * count is an honest memory bound, not a UI courtesy. */
    fun canOpenNew(): Boolean = order.size < maxTabs

    /** Remove a closed tab and tombstone its id so no later event can
     * resurrect it. Returns true when the closed tab was active; the engine
     * has already activated the next-newest tab, so the caller then re-reads
     * activeTabId() and markActive()s it. */
    fun onClosed(webviewId: String): Boolean {
        order.remove(webviewId)
        states.remove(webviewId)
        tombstones.add(webviewId)
        return activeId == webviewId
    }

    companion object {
        const val DEFAULT_MAX_TABS = 12
    }
}
