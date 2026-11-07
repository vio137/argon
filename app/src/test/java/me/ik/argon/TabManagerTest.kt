package me.ik.argon

/** Standalone state-machine tests for TabManager (kotlinc-run, no device). */
private fun check(cond: Boolean, name: String) {
    if (!cond) throw AssertionError("FAIL: $name") else println("PASS: $name")
}

fun main() {
    // 1. first event adopts the first tab and makes it active
    run {
        val tm = TabManager()
        val t = tm.route("wv1")!!
        check(tm.activeId == "wv1", "first webview becomes active")
        t.url = "https://a.example"
        check(tm.states["wv1"]?.url == "https://a.example", "routed state persists")
    }
    // 2. stale background event: pre-switch queued event lands on its own tab
    run {
        val tm = TabManager()
        tm.route("wv1")
        tm.route("wv2")
        tm.markActive("wv2") // user switched before wv1's event arrived
        val t = tm.route("wv1")!! // stale queued event from the old tab
        t.title = "old title"
        check(tm.states["wv1"]?.title == "old title", "stale event updates its source tab")
        check(tm.states["wv2"]?.title == "", "active tab state untouched by stale event")
        check(!tm.isActive("wv1"), "stale source is not active; UI must not publish it")
    }
    // 3. NO RESURRECTION: an event from a closed tab is dropped, never re-adopted
    run {
        val tm = TabManager()
        tm.route("wv1")
        tm.route("wv2")
        tm.markActive("wv2")
        tm.onClosed("wv1")
        check(tm.isTombstoned("wv1"), "closed tab is tombstoned")
        check(tm.route("wv1") == null, "racing event from closed tab dropped")
        check("wv1" !in tm.states, "closed tab not resurrected")
        check(tm.activeId == "wv2", "active tab unchanged by dead-tab event")
        check(tm.route("wv1") == null, "tombstone is permanent (second event too)")
    }
    // 4. close active: engine activates next-newest; manager drops state and
    //    reports the closure so the caller re-reads activeTabId()
    run {
        val tm = TabManager()
        tm.route("wv1")
        tm.route("wv2")
        tm.markActive("wv2")
        check(tm.onClosed("wv2"), "closing active tab reported")
        check("wv2" !in tm.states, "closed tab state dropped")
        tm.markActive("wv1") // engine-reported next active
        check(tm.activeId == "wv1", "engine-chosen next tab becomes active")
    }
    // 5. last-tab close is refused by the UI rule (MainActivity recycles it to
    //    the start page); the manager itself only tracks order
    run {
        val tm = TabManager()
        tm.route("wv1")
        check(tm.order.size == 1, "single tab: UI must recycle, not close")
    }
    // 6. tab cap: user-initiated opens stop at the cap. The SAME bound is
    //    enforced engine-side (patch 030 v3) on window.open too, so auxiliary
    //    ids past the cap cannot exist; adoption only ever sees live ids.
    run {
        val tm = TabManager(maxTabs = 3)
        tm.route("wv1")
        tm.route("wv2")
        tm.route("wv3")
        check(!tm.canOpenNew(), "cap reached: no user-initiated new tab")
        // An auxiliary id arriving under the cap (window.open) is adopted.
        val tm2 = TabManager(maxTabs = 3)
        tm2.route("wv1")
        tm2.route("wvAux") // engine-created auxiliary, under cap
        check(tm2.states.containsKey("wvAux"), "auxiliary window.open tab adopted under cap")
    }
    // 7. per-tab independence of nav state
    run {
        val tm = TabManager()
        val a = tm.route("wv1")!!
        val b = tm.route("wv2")!!
        a.canGoBack = true
        b.canGoForward = true
        check(tm.states["wv1"]?.canGoBack == true && tm.states["wv1"]?.canGoForward == false,
            "tab1 nav state independent")
        check(tm.states["wv2"]?.canGoForward == true && tm.states["wv2"]?.canGoBack == false,
            "tab2 nav state independent")
    }
    // 8. reattach replay carries snapshot source identity: it lands on its own
    //    tab even when the active tab changed since the snapshot
    run {
        val tm = TabManager()
        tm.route("wv1")
        tm.route("wv2")
        // snapshot taken while wv1 active: (wv1, url). Then user switches.
        tm.markActive("wv2")
        val t = tm.route("wv1")!! // replay routed by captured id
        t.url = "https://replay.example"
        check(tm.states["wv1"]?.url == "https://replay.example", "replay lands on snapshot's tab")
        check(tm.states["wv2"]?.url == "", "active tab not polluted by replay")
    }
    // 9. replay of a tab closed since the snapshot is discarded (tombstoned)
    run {
        val tm = TabManager()
        tm.route("wv1")
        tm.route("wv2")
        tm.onClosed("wv1")
        check(tm.route("wv1") == null, "replay for closed tab discarded, not mis-attributed")
    }
    // 10. empty id never routes (all engine callbacks carry source ids)
    run {
        val tm = TabManager()
        check(tm.route("") == null, "empty id with no tabs routes nowhere")
        tm.route("wv1")
        check(tm.route("") == null, "empty id never attributes to active tab")
        check(!tm.isActive(""), "empty id is never active")
    }
    // 11. closing a background tab leaves the active tab intact
    run {
        val tm = TabManager()
        tm.route("wv1")
        tm.route("wv2")
        tm.markActive("wv1")
        check(!tm.onClosed("wv2"), "background close not reported as active-close")
        check(tm.activeId == "wv1", "active tab unchanged by background close")
        check(tm.order == listOf("wv1"), "order shrinks to remaining tab")
    }
    // 12. alert-routing predicate: only the active tab's alert is displayable
    run {
        val tm = TabManager()
        tm.route("wv1")
        tm.route("wv2")
        tm.markActive("wv1")
        check(tm.isActive("wv1"), "active tab alert displayable")
        check(!tm.isActive("wv2"), "background tab alert suppressed")
    }
    println("ALL TABMANAGER TESTS PASSED")
}
