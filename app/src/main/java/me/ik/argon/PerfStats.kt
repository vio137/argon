package me.ik.argon

import java.util.Locale

/** Frame-pacing measurement for the engine render path. The instrumentation
 * test process shares the app process, so tests can reset/read this directly.
 * Records vsync-frame intervals and native doFrame() durations (GL thread). */
object PerfStats {
    private const val CAP = 8192

    private val intervalNs = LongArray(CAP)
    private val durationNs = LongArray(CAP)

    @Volatile private var count = 0
    @Volatile private var lastFrameTs = 0L

    @Synchronized
    fun recordFrame(frameTs: Long, durationNsValue: Long) {
        if (count >= CAP) return
        intervalNs[count] = if (lastFrameTs == 0L) 0L else frameTs - lastFrameTs
        durationNs[count] = durationNsValue
        count++
        lastFrameTs = frameTs
    }

    @Synchronized
    fun reset() {
        count = 0
        lastFrameTs = 0L
    }

    private fun pct(sorted: LongArray, n: Int, p: Double): Double {
        if (n == 0) return 0.0
        val idx = ((n - 1) * p).toInt()
        return sorted[idx] / 1_000_000.0
    }

    /** One-line summary: frame count, interval p50/p90/p99 ms, doFrame p50/p90/p99 ms,
     * share of intervals over 20ms (rough jank proxy at 60hz) and over 34ms. */
    @Synchronized
    fun report(): String {
        val n = count
        if (n < 2) return "PERF n=$n (insufficient samples)"
        val iv = intervalNs.copyOf(n).also { it.sort() }
        val du = durationNs.copyOf(n).also { it.sort() }
        var over20 = 0
        var over34 = 0
        for (i in 1 until n) {
            if (intervalNs[i] > 20_000_000) over20++
            if (intervalNs[i] > 34_000_000) over34++
        }
        return String.format(
            Locale.US,
            "PERF n=%d ivl_ms[p50=%.2f p90=%.2f p99=%.2f] doFrame_ms[p50=%.2f p90=%.2f p99=%.2f max=%.2f] over20ms=%.1f%% over34ms=%.1f%%",
            n,
            pct(iv, n, 0.50), pct(iv, n, 0.90), pct(iv, n, 0.99),
            pct(du, n, 0.50), pct(du, n, 0.90), pct(du, n, 0.99), du[n - 1] / 1_000_000.0,
            over20 * 100.0 / (n - 1), over34 * 100.0 / (n - 1),
        )
    }
}
