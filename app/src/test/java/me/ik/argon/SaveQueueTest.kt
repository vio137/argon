package me.ik.argon

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Standalone ordering tests for SaveQueue (kotlinc-run, no device).
 * Covers the async-save contract: per-store isolation, latest-wins,
 * clear-never-overwritten, flush generation/failure/timeout/supersede
 * semantics, worker-owned I/O, snapshot frozenness, no double writes. */
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

private fun writeTo(f: File, content: String): () -> Boolean = {
    f.writeText(content)
    true
}

fun main() {
    val dir = java.nio.file.Files.createTempDirectory("savequeue-test").toFile()

    // 1. Per-store isolation: two queues never drop each other's writes;
    //    each applies its own latest-wins.
    run {
        val qa = SaveQueue()
        val qb = SaveQueue()
        val fa = File(dir, "a.json")
        val fb = File(dir, "b.json")
        qa.enqueue(writeTo(fa, "A1"))
        qb.enqueue(writeTo(fb, "B1"))
        qa.enqueue(writeTo(fa, "A2"))
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
        q.enqueue { first.await(3, TimeUnit.SECONDS); order.add("S1"); f.writeText("S1"); true }
        q.enqueue { order.add("S2"); f.writeText("S2"); true }
        q.enqueue { order.add("S3"); f.writeText("S3"); true }
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
        q.enqueue { writing.countDown(); release.await(3, TimeUnit.SECONDS); f.writeText("OLD"); true }
        check(writing.await(3, TimeUnit.SECONDS), "old write in flight")
        q.enqueue(writeTo(f, "")) // clear() snapshot
        release.countDown()
        check(awaitTrue { f.exists() && f.readText() == "" }, "clear never overwritten by in-flight old write")
    }

    // 4. flush() awaits the newest generation and reports WRITTEN only after
    //    the write completed successfully.
    run {
        val q = SaveQueue()
        val f = File(dir, "flush.json")
        q.enqueue { Thread.sleep(150); f.writeText("G1"); true }
        q.enqueue(writeTo(f, "G2"))
        check(q.flush() == SaveQueue.FlushResult.WRITTEN, "flush reports WRITTEN for completed newest generation")
        check(f.exists() && f.readText() == "G2", "flush: newest generation G2 on disk at return")
    }

    // 5. TIMEOUT: an in-flight blocked write past the bound makes flush
    //    return TIMEOUT without writing; the pending snapshot survives.
    run {
        val q = SaveQueue()
        val f = File(dir, "bound.json")
        val writing = CountDownLatch(1)
        val release = CountDownLatch(1)
        q.enqueue { writing.countDown(); release.await(3, TimeUnit.SECONDS); f.writeText("BUSY"); true }
        check(writing.await(3, TimeUnit.SECONDS), "blocking write in flight")
        q.enqueue(writeTo(f, "PENDING"))
        val t0 = System.currentTimeMillis()
        val r = q.flush(300)
        val waited = System.currentTimeMillis() - t0
        check(r == SaveQueue.FlushResult.TIMEOUT, "flush returns TIMEOUT on overrun")
        check(waited < 1500, "flush respects the time bound (waited ${waited}ms)")
        check(!f.exists() || f.readText() != "PENDING", "no premature write during bounded flush")
        release.countDown()
        check(awaitTrue { f.exists() }, "in-flight write completes after release")
        check(q.flush() == SaveQueue.FlushResult.WRITTEN, "retry flush WRITTEN after worker frees")
        check(f.readText() == "PENDING", "retry: surviving pending snapshot is on disk")
    }

    // 6. Snapshot frozenness (store-level contract): the store captures its
    //    immutable entries snapshot BEFORE enqueue; later mutations never
    //    leak into the queued write.
    run {
        class MiniStore {
            var entries: List<String> = emptyList()
            fun save(q: SaveQueue, f: File) {
                val snapshot = entries // same capture pattern as HistoryStore.save
                q.enqueue { f.writeText(snapshot.joinToString(",")); true }
            }
        }
        val q = SaveQueue()
        val f = File(dir, "frozen.json")
        val s = MiniStore()
        val writing = CountDownLatch(1)
        val release = CountDownLatch(1)
        q.enqueue { writing.countDown(); release.await(3, TimeUnit.SECONDS); true }
        check(writing.await(3, TimeUnit.SECONDS), "worker blocked")
        s.entries = listOf("v1")
        s.save(q, f)
        s.entries = listOf("v1", "v2") // mutation AFTER enqueue
        release.countDown()
        check(awaitTrue { f.exists() }, "snapshot write lands")
        check(f.readText() == "v1", "frozen snapshot: mutation after enqueue not visible")
    }

    // 7. Flush racing an in-flight claimed write: flush waits it out (within
    //    bound), the write lands exactly once, no double write.
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
            true
        }
        check(started.await(3, TimeUnit.SECONDS), "claimed write in flight")
        Thread {
                Thread.sleep(300)
                release.countDown()
            }
            .start()
        check(q.flush() == SaveQueue.FlushResult.WRITTEN, "flush waits out in-flight write within bound")
        check(writes.get() == 1 && f.exists() && f.readText() == "W", "write landed exactly once")
        Thread.sleep(200)
        check(writes.get() == 1, "no double write after flush/worker race")
    }

    // 8. Write FAILURE propagates: a write returning false makes flush
    //    report FAILED (not WRITTEN).
    run {
        val q = SaveQueue()
        q.enqueue { false }
        check(q.flush() == SaveQueue.FlushResult.FAILED, "write failure propagates as FAILED")
    }

    // 8b. A write that throws is also FAILED, never a worker crash.
    run {
        val q = SaveQueue()
        val f = File(dir, "after-throw.json")
        q.enqueue { throw RuntimeException("boom") }
        check(q.flush() == SaveQueue.FlushResult.FAILED, "throwing write reports FAILED")
        q.enqueue(writeTo(f, "OK"))
        check(q.flush() == SaveQueue.FlushResult.WRITTEN && f.readText() == "OK",
            "worker survives a throwing write and processes later saves")
    }

    // 9. Worker-owned I/O: write bytes are produced on the worker thread,
    //    never on the flush caller's thread.
    run {
        val q = SaveQueue()
        val writerThread = AtomicReference<String>()
        val callerThread = Thread.currentThread().name
        q.enqueue {
            writerThread.set(Thread.currentThread().name)
            true
        }
        check(q.flush() == SaveQueue.FlushResult.WRITTEN, "flush completes")
        check(writerThread.get() == "argon-store-save", "write executed on worker thread")
        check(writerThread.get() != callerThread, "flush caller never performs I/O")
    }

    // 10. SUPERSEDED: a save enqueued after the flush started supersedes the
    //     flush's target; flush reports SUPERSEDED, the covering save then
    //     completes as WRITTEN.
    run {
        val q = SaveQueue()
        val f = File(dir, "superseded.json")
        val writing = CountDownLatch(1)
        val release = CountDownLatch(1)
        q.enqueue { writing.countDown(); release.await(3, TimeUnit.SECONDS); true }
        check(writing.await(3, TimeUnit.SECONDS), "worker blocked")
        q.enqueue(writeTo(f, "S1"))
        val flushResult = AtomicReference<SaveQueue.FlushResult>()
        val flusher = Thread { flushResult.set(q.flush(4000)) }
        flusher.start()
        Thread.sleep(100) // flush is now awaiting S1
        q.enqueue(writeTo(f, "S2")) // supersedes S1 while flush awaits it
        release.countDown()
        flusher.join(4000)
        check(flushResult.get() == SaveQueue.FlushResult.SUPERSEDED, "flush reports SUPERSEDED for covered target")
        check(q.flush() == SaveQueue.FlushResult.WRITTEN, "covering save completes WRITTEN")
        check(f.readText() == "S2", "covering save's content is on disk")
    }

    println("ALL SAVEQUEUE TESTS PASSED")
}
