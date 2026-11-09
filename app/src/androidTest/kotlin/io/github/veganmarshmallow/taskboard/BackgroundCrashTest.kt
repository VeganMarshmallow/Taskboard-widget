package io.github.veganmarshmallow.taskboard

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.veganmarshmallow.taskboard.core.error.BoardError
import io.github.veganmarshmallow.taskboard.core.filter.BoardFilter
import io.github.veganmarshmallow.taskboard.core.github.GitHubApi
import io.github.veganmarshmallow.taskboard.core.http.HttpClient
import io.github.veganmarshmallow.taskboard.core.http.HttpResponse
import io.github.veganmarshmallow.taskboard.core.http.Outcome
import io.github.veganmarshmallow.taskboard.filter.LiveFilter
import io.github.veganmarshmallow.taskboard.setup.ConnectionTester
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.ZoneOffset
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * Task #63: an [Error] thrown on the "Test connection" thread or on the live filter's save thread
 * no longer escapes it and crashes the app; and that thread still runs the next request.
 */
@RunWith(AndroidJUnit4::class)
class BackgroundCrashTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun api(http: HttpClient) = GitHubApi(http, "github_pat_X", Clock.fixed(FakeBoards.NOW, ZoneOffset.UTC))

    private val ok = HttpClient { HttpResponse(200, body = "[]") }

    /** Runs [count] connection tests one after another on [tester]'s real thread; returns what reached the screen. */
    private fun runTests(tester: ConnectionTester, count: Int): List<Outcome<Unit>> {
        val results = Collections.synchronizedList(mutableListOf<Outcome<Unit>>())
        try {
            repeat(count) { i ->
                tester.test(context, "github_pat_X") { results.add(it) }
                waitUntil("connection test ${i + 1} reported") { results.size == i + 1 }
            }
        } finally {
            tester.shutdown()
        }
        return results.toList()
    }

    @Test fun anErrorFromTheHttpStackIsAFailedTestAndTheNextTestStillRuns() {
        val calls = AtomicInteger()
        val http = HttpClient {
            if (calls.incrementAndGet() == 1) throw NoSuchMethodError("x")
            HttpResponse(200, body = "[]")
        }
        val tester = ConnectionTester { _, _ -> api(http) }
        val results = runTests(tester, 2)
        assertEquals(Outcome.Failure(BoardError.Internal("NoSuchMethodError")), results[0])
        assertEquals("the next test ran on the same thread", Outcome.Success(Unit), results[1])
    }

    @Test fun anErrorBuildingTheApiIsAFailedTestAndTheNextTestStillRuns() {
        val calls = AtomicInteger()
        val tester = ConnectionTester { _, _ ->
            if (calls.incrementAndGet() == 1) throw NoSuchMethodError("api")
            api(ok)
        }
        val results = runTests(tester, 2)
        assertEquals(Outcome.Failure(BoardError.Internal("NoSuchMethodError")), results[0])
        assertEquals(Outcome.Success(Unit), results[1])
        assertEquals(2, calls.get())
    }

    @Test fun aRuntimeExceptionBuildingTheApiIsAFailedTest() {
        val tester = ConnectionTester { _, _ -> throw IllegalStateException("token github_pat_X") }
        assertEquals(listOf(Outcome.Failure(BoardError.Internal("IllegalStateException"))), runTests(tester, 1))
    }

    /** Flushes [filters] through [live] one at a time, on the main thread as the sheet does. */
    private fun flushAll(live: LiveFilter, filters: List<BoardFilter>) {
        filters.forEach { filter ->
            instrumentation.runOnMainSync {
                live.update(filter)
                live.flush()
            }
        }
    }

    @Test fun anErrorWhileSavingTheFilterIsDroppedAndTheNextSaveStillRuns() {
        val calls = AtomicInteger()
        val saved = Collections.synchronizedList(mutableListOf<BoardFilter>())
        val live = LiveFilter(context) { _: Context, filter: BoardFilter ->
            if (calls.incrementAndGet() == 1) throw NoSuchMethodError("save")
            saved.add(filter)
        }
        val first = BoardFilter(text = "first")
        val second = BoardFilter(text = "second")
        flushAll(live, listOf(first, second))
        waitUntil("second save ran") { saved.isNotEmpty() }
        assertEquals("the save thread survived and ran the next save", listOf(second), saved.toList())
        assertEquals(2, calls.get())
    }

    @Test fun aRuntimeExceptionWhileSavingTheFilterIsDroppedToo() {
        val calls = AtomicInteger()
        val saved = Collections.synchronizedList(mutableListOf<BoardFilter>())
        val live = LiveFilter(context) { _: Context, filter: BoardFilter ->
            if (calls.incrementAndGet() == 1) throw IllegalStateException("redraw")
            saved.add(filter)
        }
        val second = BoardFilter(text = "second")
        flushAll(live, listOf(BoardFilter(text = "first"), second))
        waitUntil("second save ran") { saved.isNotEmpty() }
        assertEquals(listOf(second), saved.toList())
    }
}
