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

    /** Engine-reported active tab id; empty until the first webview reports in. */
    var activeId: String = ""
        private set

    /** Route an engine event to its source tab, adopting unknown ids (the
     * engine only reports ids of real webviews). An empty id is the legacy
     * single-tab replay path and attributes to the active tab. Returns null
     * when there is nothing to attribute to yet. */
    fun route(webviewId: String): TabState? {
        val id = if (webviewId.isEmpty()) activeId else webviewId
        if (id.isEmpty()) return null
        return adopt(id)
    }

    private fun adopt(id: String): TabState {
        if (id !in states) {
            order.add(id)
            states[id] = TabState()
            if (activeId.isEmpty()) activeId = id
        }
        return states.getValue(id)
    }

    /** True when [webviewId] is the active tab (or the legacy empty-id path,
     * which predates id-tagging and always meant the only tab). */
    fun isActive(webviewId: String): Boolean = webviewId.isEmpty() || webviewId == activeId

    fun markActive(webviewId: String) {
        activeId = webviewId
    }

    /** User-facing cap on open tabs; engine-adopted ids are always tracked. */
    fun canOpenNew(): Boolean = order.size < maxTabs

    /** Remove a closed tab. Returns true when the closed tab was active; the
     * engine has already activated the next-newest tab, so the caller then
     * re-reads activeTabId() and markActive()s it. */
    fun onClosed(webviewId: String): Boolean {
        order.remove(webviewId)
        states.remove(webviewId)
        return activeId == webviewId
    }

    companion object {
        const val DEFAULT_MAX_TABS = 12
    }
}
