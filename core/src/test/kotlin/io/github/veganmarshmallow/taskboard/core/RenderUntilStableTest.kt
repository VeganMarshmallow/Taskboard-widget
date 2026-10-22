package io.github.veganmarshmallow.taskboard.core

import io.github.veganmarshmallow.taskboard.core.work.RenderUntilStable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/** A tab tap racing a refresh: the widget must never end up showing the older board. */
class RenderUntilStableTest {
    @Test fun stableValueIsDrawnOnce() {
        val drawn = mutableListOf<String>()
        assertEquals(1, RenderUntilStable.run({ "a" }, { drawn += it }))
        assertEquals(listOf("a"), drawn)
    }

    @Test fun aValueSavedDuringTheDrawIsDrawnNext() {
        val values = ArrayDeque(listOf("old", "new", "new"))
        val drawn = mutableListOf<String>()
        assertEquals(2, RenderUntilStable.run({ values.removeFirst() }, { drawn += it }))
        assertEquals(listOf("old", "new"), drawn)
    }

    @Test fun aWriterThatNeverStopsIsCappedAtMaxRounds() {
        var n = 0
        val drawn = mutableListOf<Int>()
        assertEquals(3, RenderUntilStable.run({ n++ }, { drawn += it }))
        assertEquals(listOf(0, 1, 2), drawn)
        assertEquals(1, RenderUntilStable.run({ n++ }, { }, maxRounds = 1))
    }

    @Test fun nullsAndEqualButDistinctValuesCountAsStable() {
        assertEquals(1, RenderUntilStable.run({ null }, { }))
        assertEquals(1, RenderUntilStable.run({ listOf(1, 2) }, { })) // a fresh but equal list each load
    }

    @Test fun invalidRoundCountIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { RenderUntilStable.run({ 1 }, { }, maxRounds = 0) }
        assertThrows(IllegalArgumentException::class.java) { RenderUntilStable.run({ 1 }, { }, maxRounds = -1) }
    }

    @Test fun failuresPropagateAndStopDrawing() {
        var draws = 0
        assertThrows(IllegalStateException::class.java) {
            RenderUntilStable.run({ throw IllegalStateException("cache") }, { draws++ })
        }
        assertEquals(0, draws)
        assertThrows(IllegalStateException::class.java) {
            RenderUntilStable.run({ 1 }, { throw IllegalStateException("host") })
        }
    }

    @Test fun racingARefreshThatSavesThenDrawsAlwaysEndsOnTheNewestValue() {
        repeat(200) {
            val saved = AtomicInteger(0)
            val screen = AtomicInteger(-1)
            val start = CountDownLatch(1)
            val refresh = Thread {
                start.await()
                for (v in 1..20) { saved.set(v); screen.set(v) } // save, then draw
            }
            val tap = Thread {
                start.await()
                RenderUntilStable.run({ saved.get() }, { screen.set(it) }, maxRounds = 1_000)
            }
            refresh.start(); tap.start(); start.countDown()
            refresh.join(); tap.join()
            assertEquals(20, screen.get())
        }
    }
}
