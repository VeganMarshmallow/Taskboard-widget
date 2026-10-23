package io.github.veganmarshmallow.taskboard.core

import io.github.veganmarshmallow.taskboard.core.work.SerialRender
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Task #33: a background redraw must never land after a tap's draw with the tab it read before the tap. */
class SerialRenderTest {
    @Test fun drawsWhatItReads() {
        val drawn = mutableListOf<String>()
        SerialRender().run({ "todo" }, { drawn += it })
        assertEquals(listOf("todo"), drawn)
    }

    @Test fun aRedrawThatReadTheOldTabCannotLandAfterTheTap() {
        // Thread A (a background redraw) reads the old tab and is about to push it. The user taps:
        // the pick is saved and the tap draws. Without serialising, A's push lands last.
        val gate = SerialRender()
        val tab = AtomicReference("NEEDS_YOU")
        val screen = AtomicReference<String>()
        val aHasRead = CountDownLatch(1)
        val tapSaved = CountDownLatch(1)
        val redraw = Thread {
            gate.run({ tab.get().also { aHasRead.countDown() } }, {
                tapSaved.await(2, TimeUnit.SECONDS) // the tap happens while A holds its stale value
                screen.set(it)
            })
        }
        redraw.start()
        aHasRead.await()
        tab.set("BLOCKED")
        tapSaved.countDown()
        gate.run({ tab.get() }, { screen.set(it) }) // the tap's draw waits for A, then reads the new pick
        redraw.join()
        assertEquals("BLOCKED", screen.get())
    }

    @Test fun manyWritersAndRedrawsAlwaysEndOnTheLastWrite() {
        repeat(200) {
            val gate = SerialRender()
            val saved = AtomicInteger(0)
            val screen = AtomicInteger(-1)
            val start = CountDownLatch(1)
            val redraws = (1..3).map {
                Thread { start.await(); repeat(50) { gate.run({ saved.get() }, { v -> screen.set(v) }) } }
            }
            val taps = Thread {
                start.await()
                for (v in 1..30) { saved.set(v); gate.run({ saved.get() }, { screen.set(it) }) } // save, then draw
            }
            (redraws + taps).forEach(Thread::start)
            start.countDown()
            (redraws + taps).forEach(Thread::join)
            assertEquals(30, screen.get())
        }
    }

    @Test fun stepsNeverOverlap() {
        val gate = SerialRender()
        val inside = AtomicInteger(0)
        val overlap = AtomicInteger(0)
        val threads = (1..8).map {
            Thread {
                repeat(500) {
                    gate.run({ if (inside.incrementAndGet() > 1) overlap.incrementAndGet() }, { inside.decrementAndGet() })
                }
            }
        }
        threads.forEach(Thread::start)
        threads.forEach(Thread::join)
        assertEquals(0, overlap.get())
    }

    @Test fun separateGatesDoNotWaitForEachOther() {
        val first = SerialRender()
        val second = SerialRender()
        val ran = AtomicInteger(0)
        first.run({ Unit }, {
            val t = Thread { second.run({ Unit }, { ran.incrementAndGet() }) }
            t.start()
            t.join(2_000)
            assertFalse(t.isAlive)
        })
        assertEquals(1, ran.get())
    }

    @Test fun aFailureReleasesTheGate() {
        val gate = SerialRender()
        assertThrows(IllegalStateException::class.java) { gate.run({ throw IllegalStateException("cache") }, { }) }
        assertThrows(IllegalStateException::class.java) { gate.run({ 1 }, { throw IllegalStateException("host") }) }
        var drew = false
        val t = Thread { gate.run({ 1 }, { drew = true }) }
        t.start()
        t.join(2_000)
        assertTrue(drew)
    }

    @Test fun aNestedRunOnTheSameThreadDoesNotDeadlock() {
        // A draw that itself redraws (e.g. a renderer calling another render helper) must not hang.
        val gate = SerialRender()
        val drawn = mutableListOf<Int>()
        gate.run({ 1 }, { v -> drawn += v; gate.run({ 2 }, { drawn += it }) })
        assertEquals(listOf(1, 2), drawn)
    }
}
