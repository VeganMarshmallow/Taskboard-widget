package io.github.veganmarshmallow.taskboard.core

import io.github.veganmarshmallow.taskboard.core.mergeall.LockedMergeAllStore
import io.github.veganmarshmallow.taskboard.core.mergeall.MergeAllState
import io.github.veganmarshmallow.taskboard.core.mergeall.MergeAllStateCodec
import io.github.veganmarshmallow.taskboard.core.mergeall.MergeAllStop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.Collections
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit

/** Starting a Merge all run is a compare-and-set (#60): never over an unfinished run, and one winner of two at once. */
class MergeAllStartTest {
    private val now = Instant.parse("2026-11-08T10:00:00Z")

    private val records = MemoryMergeAllRecords()
    private val store = LockedMergeAllStore(records, Any())

    private fun run(id: String) = MergeAllState.start(id, planned(31, 32), now)

    @Test fun startsWhenNoRunIsSaved() {
        assertTrue(store.start(run("a")))
        assertEquals("a", store.load()?.runId)
    }

    @Test fun refusedWhileARunGoesOnAndTheRunIsLeftAlone() {
        val going = run("a").copy(index = 1)
        store.save(going)
        store.requestCancel("a")
        assertFalse(store.start(run("b")))
        assertEquals(going, store.load())
        assertTrue("its cancel is kept", store.cancelRequested("a"))
    }

    @Test fun allowedOnceTheRunHasEnded() {
        val ends = listOf(
            run("a").mergedCurrent("claude/31", now).mergedCurrent("claude/32", now),
            run("a").cancelled(now),
            run("a").stopped(MergeAllStop(MergeAllStop.Kind.CONFLICT), now),
        )
        for (ended in ends) {
            store.save(ended)
            assertFalse(store.load()!!.running)
            assertTrue("after ${ended.end}", store.start(run("b")))
            assertEquals("b", store.load()?.runId)
            assertTrue(store.load()!!.running)
            store.clear()
        }
    }

    @Test fun allowedAfterAReset() {
        assertTrue(store.start(run("a")))
        store.clear()
        assertNull(store.load())
        assertTrue(store.start(run("b")))
        assertEquals("b", store.load()?.runId)
    }

    @Test fun anUnreadableSavedRunDoesNotBlockAStart() {
        records.state = "not a run"
        assertTrue(store.start(run("b")))
        assertEquals("b", store.load()?.runId)
    }

    @Test fun aCancelForAnEarlierRunDoesNotCarryOver() {
        store.save(run("a").cancelled(now))
        store.requestCancel("a")
        assertTrue(store.start(run("b")))
        assertFalse(store.cancelRequested("b"))
    }

    /** Two widgets tap at once, each with its own store instance over the same records and the process's lock. */
    private fun race(records: MemoryMergeAllRecords, lock: () -> Any): Pair<List<String>, String?> {
        val winners = Collections.synchronizedList(mutableListOf<String>())
        val barrier = CyclicBarrier(2)
        val threads = listOf("a", "b").map { id ->
            Thread {
                val mine = LockedMergeAllStore(records, lock())
                barrier.await(5, TimeUnit.SECONDS)
                if (mine.start(run(id))) winners += id
            }
        }
        threads.forEach(Thread::start)
        threads.forEach { it.join(10_000) }
        return winners.toList() to records.state?.let { MergeAllStateCodec.decode(it)?.runId }
    }

    @Test fun twoStartsAtOnceExactlyOneWins() {
        repeat(20) {
            val (winners, saved) = race(MemoryMergeAllRecords(onRead = { Thread.sleep(20) }), lock = { PROCESS_LOCK })
            assertEquals("exactly one start wins", 1, winners.size)
            assertEquals("the saved run is the winner's", winners.single(), saved)
        }
    }

    @Test fun withoutTheSharedLockBothWouldStart() {
        // Each read waits until both starts have read: unlocked, both see "no run" and both save (the bug #60 fixes).
        val bothRead = CyclicBarrier(2)
        val records = MemoryMergeAllRecords(onRead = { bothRead.await(5, TimeUnit.SECONDS) })
        val (winners, _) = race(records, lock = { Any() })
        assertEquals(2, winners.size)
    }

    private companion object {
        val PROCESS_LOCK = Any()
    }
}
