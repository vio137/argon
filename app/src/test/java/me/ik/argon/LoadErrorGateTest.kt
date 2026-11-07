package me.ik.argon

/** Standalone state-machine tests for LoadErrorGate (kotlinc-run, no device). */
private fun check(cond: Boolean, name: String) {
    if (!cond) throw AssertionError("FAIL: $name") else println("PASS: $name")
}

fun main() {
    // 1. stale error arriving after app-initiated submit, before new start
    run {
        val g = LoadErrorGate()
        g.onTopLevelLoad("wv1", "p1", 1)
        check(g.shouldShowError("wv1", "p1", 1), "current error shows")
        g.onNavigationInitiated()
        check(!g.shouldShowError("wv1", "p1", 1), "stale error after submit dropped")
    }
    // 2. stale error after new start (old tuple vs new tuple)
    run {
        val g = LoadErrorGate()
        g.onTopLevelLoad("wv1", "p1", 1)
        g.onNavigationInitiated()
        g.onTopLevelLoad("wv1", "p2", 2)
        check(!g.shouldShowError("wv1", "p1", 1), "old load error dropped after new start")
        check(g.shouldShowError("wv1", "p2", 2), "new load error shows")
    }
    // 3. repeated same URL: same webview, new pipeline/generation
    run {
        val g = LoadErrorGate()
        g.onTopLevelLoad("wv1", "p1", 1)
        g.onNavigationInitiated()
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
    // 5. failed pending load: A starts, B starts before A fails
    run {
        val g = LoadErrorGate()
        g.onTopLevelLoad("wv1", "pA", 1)
        g.onTopLevelLoad("wv1", "pB", 2)
        check(!g.shouldShowError("wv1", "pA", 1), "superseded pending load error dropped")
        check(g.shouldShowError("wv1", "pB", 2), "current pending load error shows")
    }
    // 6. out of order: start A, start B, error B, error A
    run {
        val g = LoadErrorGate()
        g.onTopLevelLoad("wv1", "pA", 1)
        g.onTopLevelLoad("wv1", "pB", 2)
        check(g.shouldShowError("wv1", "pB", 2), "later load error shows first")
        check(!g.shouldShowError("wv1", "pA", 1), "earlier load error after still dropped")
    }
    // 7. untracked generation (0) never matches, even against nothing
    run {
        val g = LoadErrorGate()
        check(!g.shouldShowError("wv1", "pX", 0), "gen0 with no identity dropped")
        check(!g.shouldShowError("wv1", "pX", 0), "gen0 error dropped")
    }
    // 7b. gen0 start (fail-closed overflow) clears a previous identity
    run {
        val g = LoadErrorGate()
        g.onTopLevelLoad("wv1", "p1", 1)
        g.onTopLevelLoad("wv1", "p2", 0)
        check(g.current == null, "gen0 start clears identity")
        check(!g.shouldShowError("wv1", "p1", 1), "pre-overflow error dropped after gen0 start")
        check(!g.shouldShowError("wv1", "p2", 0), "overflow load error never shows")
    }
    // 8. lifecycle: invalidation then fresh start
    run {
        val g = LoadErrorGate()
        g.onTopLevelLoad("wv1", "p1", 1)
        g.onNavigationInitiated()
        check(g.current == null, "invalidation clears identity")
        g.onTopLevelLoad("wv1", "p3", 3)
        check(g.shouldShowError("wv1", "p3", 3), "fresh start after invalidation works")
    }
    println("ALL LOADERRORGATE TESTS PASS")
}
