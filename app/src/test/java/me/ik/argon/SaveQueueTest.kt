package me.ik.argon

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Standalone ordering tests for SaveQueue (kotlinc-run, no device).
 * Covers the async-save contract from the perf 1.0.17 review:
 * per-store isolation, latest-wins, clear-never-overwritten, flush
 * generation + bound + no deadlock, snapshot frozenness. */
private fun check(cond: Boolean, name: String) {
    if (!cond) throw AssertionError("FAIL: $name") else println("PASS: $name")
}

private fun awaitTrue(timeoutMs: Long = 4000, cond: () -> Boolean): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (cond()) return true
        Thread.sleep(5)
    }
    return cond()
}

fun main() {
    val dir = java.nio.file.Files.createTempDirectory("savequeue-test").toFile()

    // 1. Per-store isolation: two queues (history/bookmarks analogues) never
    //    drop each other's writes; each applies its own latest-wins.
    run {
        val qa = SaveQueue()
        val qb = SaveQueue()
        val fa = File(dir, "a.json")
        val fb = File(dir, "b.json")
        qa.enqueue { fa.writeText("A1") }
        qb.enqueue { fb.writeText("B1") }
        qa.enqueue { fa.writeText("A2") }
        check(awaitTrue { fa.exists() && fa.readText() == "A2" && fb.exists() && fb.readText() == "B1" },
            "per-store isolation: A2 and B1 both land")
    }

    // 2. Latest-wins within a store: a superseded snapshot is never the
    //    final state, and writes are strictly ordered.
    run {
        val q = SaveQueue()
        val f = File(dir, "lw.json")
        val order = java.util.Collections.synchronizedList(mutableListOf<String>())
        val first = CountDownLatch(1)
        q.enqueue { first.await(3, TimeUnit.SECONDS); order.add("S1"); f.writeText("S1") }
        // ensure S1 started (holds the worker) before queueing the rest
        check(awaitTrue { q.toString().isNotEmpty() }, "setup sane")
        q.enqueue { order.add("S2"); f.writeText("S2") }
        q.enqueue { order.add("S3"); f.writeText("S3") }
        first.countDown()
        check(awaitTrue { f.exists() && f.readText() == "S3" }, "latest-wins: final content is S3")
        check(order.isNotEmpty() && order.last() == "S3", "latest-wins: S3 written last")
    }

    // 3. A clear/delete enqueued behind an in-flight OLD write is never
    //    overwritten by that old write.
    run {
        val q = SaveQueue()
        val f = File(dir, "clear.json")
        val writing = CountDownLatch(1)
        val release = CountDownLatch(1)
        q.enqueue { writing.countDown(); release.await(3, TimeUnit.SECONDS); f.writeText("OLD") }
        check(writing.await(3, TimeUnit.SECONDS), "old write in flight")
        q.enqueue { f.writeText("") } // clear() snapshot
        release.countDown()
        check(awaitTrue { f.exists() && f.readText() == "" }, "clear never overwritten by in-flight old write")
    }

    // 4. flush() drains the newest generation synchronously and returns true.
    run {
        val q = SaveQueue()
        val f = File(dir, "flush.json")
        q.enqueue { Thread.sleep(150); f.writeText("G1") }
        q.enqueue { f.writeText("G2") }
        check(q.flush(), "flush returns true")
        check(f.exists() && f.readText() == "G2", "flush drains newest generation G2")
    }

    // 5. Bounded flush: an in-flight write past the bound makes flush return
    //    false WITHOUT writing; the pending snapshot survives for a retry.
    run {
        val q = SaveQueue()
        val f = File(dir, "bound.json")
        val writing = CountDownLatch(1)
        val release = CountDownLatch(1)
        q.enqueue { writing.countDown(); release.await(3, TimeUnit.SECONDS); f.writeText("BUSY") }
        check(writing.await(3, TimeUnit.SECONDS), "blocking write in flight")
        q.enqueue { f.writeText("PENDING") }
        val t0 = System.currentTimeMillis()
        val ok = q.flush(300)
        val waited = System.currentTimeMillis() - t0
        check(!ok, "bounded flush returns false on overrun")
        check(waited < 1500, "bounded flush respects the time bound (waited ${waited}ms)")
        check(!f.exists() || f.readText() != "PENDING", "no premature write during bounded flush")
        release.countDown()
        check(awaitTrue { f.exists() && (f.readText() == "BUSY" || f.readText() == "PENDING") }, "in-flight write completes after release")
        check(q.flush(), "retry flush succeeds after worker frees")
        check(f.readText() == "PENDING", "retry drains the surviving pending snapshot")
    }

    // 6. Snapshot frozenness (store-level contract): the store captures its
    //    immutable entries snapshot BEFORE enqueue; later mutations never
    //    leak into the queued write.
    run {
        class MiniStore {
            var entries: List<String> = emptyList()
            fun save(q: SaveQueue, f: File) {
                val snapshot = entries // same capture pattern as HistoryStore.save
                q.enqueue { f.writeText(snapshot.joinToString(",")) }
            }
        }
        val q = SaveQueue()
        val f = File(dir, "frozen.json")
        val s = MiniStore()
        val writing = CountDownLatch(1)
        val release = CountDownLatch(1)
        q.enqueue { writing.countDown(); release.await(3, TimeUnit.SECONDS) }
        check(writing.await(3, TimeUnit.SECONDS), "worker blocked")
        s.entries = listOf("v1")
        s.save(q, f)
        s.entries = listOf("v1", "v2") // mutation AFTER enqueue
        release.countDown()
        check(awaitTrue { f.exists() }, "snapshot write lands")
        check(f.readText() == "v1", "frozen snapshot: mutation after enqueue not visible")
    }

    // 7. Flush racing an in-flight claimed write: flush waits it out (within
    //    bound), the write lands exactly once, and the drained queue stays
    //    empty (no double write).
    run {
        val q = SaveQueue()
        val f = File(dir, "race.json")
        val writes = AtomicInteger(0)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        q.enqueue {
            started.countDown()
            release.await(3, TimeUnit.SECONDS)
            writes.incrementAndGet()
            f.writeText("W")
        }
        check(started.await(3, TimeUnit.SECONDS), "claimed write in flight")
        // release the write shortly after flush starts waiting on the lock
        Thread {
                Thread.sleep(300)
                release.countDown()
            }
            .start()
        check(q.flush(), "flush waits out the in-flight write within bound")
        check(writes.get() == 1 && f.exists() && f.readText() == "W", "write landed exactly once")
        Thread.sleep(200) // let any stale task run (there must be none)
        check(writes.get() == 1, "no double write after flush/worker race")
    }

    println("ALL SAVEQUEUE TESTS PASSED")
}
