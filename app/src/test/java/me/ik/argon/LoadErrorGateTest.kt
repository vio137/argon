package me.ik.argon

/** Standalone state-machine tests for LoadErrorGate (kotlinc-run, no device).
 * The gate is keyed per webview since engine patch 030 (tabs). */
private fun check(cond: Boolean, name: String) {
    if (!cond) throw AssertionError("FAIL: $name") else println("PASS: $name")
}

fun main() {
    // 1. stale error arriving after app-initiated submit, before new start
    run {
        val g = LoadErrorGate()
        g.onTopLevelLoad("wv1", "p1", 1)
        check(g.shouldShowError("wv1", "p1", 1), "current error shows")
        g.onNavigationInitiated("wv1")
        check(!g.shouldShowError("wv1", "p1", 1), "stale error after submit dropped")
    }
    // 2. stale error after new start (old tuple vs new tuple)
    run {
        val g = LoadErrorGate()
        g.onTopLevelLoad("wv1", "p1", 1)
        g.onNavigationInitiated("wv1")
        g.onTopLevelLoad("wv1", "p2", 2)
        check(!g.shouldShowError("wv1", "p1", 1), "old load error dropped after new start")
        check(g.shouldShowError("wv1", "p2", 2), "new load error shows")
    }
    // 3. repeated same URL: same webview, new pipeline/generation
    run {
        val g = LoadErrorGate()
        g.onTopLevelLoad("wv1", "p1", 1)
        g.onNavigationInitiated("wv1")
        g.onTopLevelLoad("wv1", "p1", 2) // same-URL reload may reuse nothing
        check(!g.shouldShowError("wv1", "p1", 1), "same-URL older generation dropped")
        check(g.shouldShowError("wv1", "p1", 2), "same-URL newer generation shows")
    }
    // 4. redirect: error URL differs from start URL; URL is not in the match
    //    key by construction, so identity alone decides (documented here).
    run {
        val g = LoadErrorGate()
        g.onTopLevelLoad("wv1", "p1", 5)
        check(g.shouldShowError("wv1", "p1", 5), "redirected load failure shows (url ignored)")
    }
    // 5. generation 0 (untracked, fail-closed overflow policy) never matches
    run {
        val g = LoadErrorGate()
        g.onTopLevelLoad("wv1", "p1", 0)
        check(!g.shouldShowError("wv1", "p1", 0), "gen0 start clears; gen0 error never shows")
        check(g.currentFor("wv1") == null, "gen0 clears the webview identity")
    }
    // 6. untracked webview error never shows
    run {
        val g = LoadErrorGate()
        check(!g.shouldShowError("wvX", "p9", 9), "untracked webview error dropped")
    }
    // 7. per-webview isolation: navigation on wv1 does not invalidate wv2
    run {
        val g = LoadErrorGate()
        g.onTopLevelLoad("wv1", "p1", 1)
        g.onTopLevelLoad("wv2", "p2", 1)
        g.onNavigationInitiated("wv1")
        check(!g.shouldShowError("wv1", "p1", 1), "navigated webview identity cleared")
        check(g.shouldShowError("wv2", "p2", 1), "other tab identity untouched")
    }
    // 8. per-webview isolation: an error keyed to wv2 never matches wv1's tuple
    run {
        val g = LoadErrorGate()
        g.onTopLevelLoad("wv1", "p1", 3)
        check(!g.shouldShowError("wv2", "p1", 3), "cross-webview tuple never matches")
    }
    // 9. a background tab starting a new load does not disturb the active tab
    run {
        val g = LoadErrorGate()
        g.onTopLevelLoad("wvActive", "pa", 1)
        g.onTopLevelLoad("wvBg", "pb", 7)
        check(g.shouldShowError("wvActive", "pa", 1), "active identity survives background start")
        check(g.shouldShowError("wvBg", "pb", 7), "background tab tracks its own identity")
    }
    // 10. monotonic generations: replayed old generation after newer start drops
    run {
        val g = LoadErrorGate()
        g.onTopLevelLoad("wv1", "p1", 10)
        g.onTopLevelLoad("wv1", "p2", 11)
        check(!g.shouldShowError("wv1", "p1", 10), "older generation dropped")
        check(g.shouldShowError("wv1", "p2", 11), "newest generation shows")
    }
    println("ALL LOADERRORGATE TESTS PASSED")
}
