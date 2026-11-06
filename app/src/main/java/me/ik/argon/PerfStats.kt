package me.ik.argon

import java.util.Locale

/** Frame-pacing measurement for the engine render path. The instrumentation
 * test process shares the app process, so tests can reset/read this directly.
 *
 * Populations, kept strictly separate (do not compare across them):
 *  - vsync intervals (frameTs deltas): vsync DELIVERY spacing, always ~16.7ms
 *    regardless of render cost - NOT a jank measure, kept only as a sanity check.
 *  - exec gaps (start-to-start of GL doFrame tasks): real execution pacing.
 *  - queue lag (exec start - vsync timestamp): backlog between vsync and work.
 *  - work (jni doFrame duration on the GL thread): CPU submission cost.
 *  - input lag (GL-exec start - touch post time, per delivered move task):
 *    how long scroll input waited behind queued GL work. Superseded/coalesced
 *    moves never execute and are excluded by design (they carried no unique
 *    information to the engine).
 * Coverage: scheduling/queueing/CPU submission only. NOT SurfaceFlinger
 * presentation and NOT input-to-display latency. */
object PerfStats {
    private const val CAP = 8192

    private val intervalNs = LongArray(CAP)
    private val execGapNs = LongArray(CAP)
    private val queueLagNs = LongArray(CAP)
    private val durationNs = LongArray(CAP)
    private val inputLagNs = LongArray(CAP)

    private val inputPostNs = LongArray(CAP)

    @Volatile private var count = 0
    @Volatile private var inputCount = 0
    @Volatile private var lastFrameTs = 0L
    @Volatile private var lastExecStart = 0L

    @Synchronized
    fun recordFrame(frameTs: Long, execStartNs: Long, durationNsValue: Long) {
        if (count >= CAP) return
        intervalNs[count] = if (lastFrameTs == 0L) 0L else frameTs - lastFrameTs
        execGapNs[count] = if (lastExecStart == 0L) 0L else execStartNs - lastExecStart
        queueLagNs[count] = if (frameTs > 0L) execStartNs - frameTs else 0L
        durationNs[count] = durationNsValue
        count++
        lastFrameTs = frameTs
        lastExecStart = execStartNs
    }

    /** Records one touch-move task that REACHED the GL thread: posted at
     * postNs on the UI thread, starting execution now. */
    @Synchronized
    fun recordInput(postNs: Long) {
        if (inputCount >= CAP) return
        inputPostNs[inputCount] = postNs
        inputLagNs[inputCount] = System.nanoTime() - postNs
        inputCount++
    }

    @Synchronized
    fun reset() {
        count = 0
        inputCount = 0
        lastFrameTs = 0L
        lastExecStart = 0L
    }

    private fun pct(sorted: LongArray, n: Int, p: Double): Double {
        if (n == 0) return 0.0
        val idx = ((n - 1) * p).toInt()
        return sorted[idx] / 1_000_000.0
    }

    private fun over(sorted: LongArray, n: Int, thresholdNs: Long, skipFirst: Boolean): Double {
        if (n <= 1) return 0.0
        var c = 0
        var total = 0
        for (i in (if (skipFirst) 1 else 0) until n) {
            total++
            if (sorted[i] > thresholdNs) c++
        }
        return if (total == 0) 0.0 else c * 100.0 / total
    }

    @Synchronized
    fun report(): String {
        val n = count
        if (n < 2) return "PERF n=$n (insufficient samples)"
        val iv = intervalNs.copyOf(n).also { it.sort() }
        val eg = execGapNs.copyOf(n).also { it.sort() }
        val ql = queueLagNs.copyOf(n).also { it.sort() }
        val du = durationNs.copyOf(n).also { it.sort() }
        val ni = inputCount
        val il = inputLagNs.copyOf(ni).also { it.sort() }
        return String.format(
            Locale.US,
            "PERF n=%d " +
                "vsync_ivl_ms[p50=%.2f p99=%.2f] " +
                "execgap_ms[p50=%.2f p90=%.2f p99=%.2f max=%.2f over20ms=%.1f%% over34ms=%.1f%%] " +
                "queue_ms[p50=%.2f p90=%.2f p99=%.2f max=%.2f] " +
                "work_ms[p50=%.2f p90=%.2f p99=%.2f max=%.2f] " +
                "inputlag_ms[n=%d p50=%.2f p90=%.2f p99=%.2f max=%.2f]",
            n,
            pct(iv, n, 0.50), pct(iv, n, 0.99),
            pct(eg, n, 0.50), pct(eg, n, 0.90), pct(eg, n, 0.99), eg[n - 1] / 1_000_000.0,
            over(execGapNs.copyOf(n).also { it.sort() }, n, 20_000_000, true),
            over(eg, n, 34_000_000, true),
            pct(ql, n, 0.50), pct(ql, n, 0.90), pct(ql, n, 0.99), ql[n - 1] / 1_000_000.0,
            pct(du, n, 0.50), pct(du, n, 0.90), pct(du, n, 0.99), du[n - 1] / 1_000_000.0,
            inputCount,
            pct(il, ni, 0.50), pct(il, ni, 0.90), pct(il, ni, 0.99),
            if (ni > 0) il[ni - 1] / 1_000_000.0 else 0.0,
        )
    }
}
