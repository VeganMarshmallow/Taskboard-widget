package io.github.veganmarshmallow.taskboard.core

import io.github.veganmarshmallow.taskboard.core.error.BoardError
import io.github.veganmarshmallow.taskboard.core.error.ErrorClassifier
import io.github.veganmarshmallow.taskboard.core.error.ErrorMessages
import io.github.veganmarshmallow.taskboard.core.error.NetworkProbe
import io.github.veganmarshmallow.taskboard.core.github.GitHubApi
import io.github.veganmarshmallow.taskboard.core.http.Outcome
import io.github.veganmarshmallow.taskboard.core.preview.PreviewApi
import io.github.veganmarshmallow.taskboard.core.task.CreateErrors
import io.github.veganmarshmallow.taskboard.core.task.NewIssue
import io.github.veganmarshmallow.taskboard.core.update.UpdateError
import io.github.veganmarshmallow.taskboard.core.update.UpdateMessages
import io.github.veganmarshmallow.taskboard.core.update.UpdateResult
import io.github.veganmarshmallow.taskboard.core.update.Updater
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

/** #35: every request's connection failure is worded from the device's network, not only the widget's. */
class NetworkProbeTest {
    private val online = NetworkProbe { true }
    private val offline = NetworkProbe { false }
    private val messages = ErrorMessages { "14:05" }

    /** Counts how often it is asked, to prove it is consulted only after a connection failure. */
    private class CountingProbe(private val answer: Boolean) : NetworkProbe {
        var asked = 0
        override fun deviceOnline(): Boolean { asked++; return answer }
    }

    private val connectionFailures: List<IOException> = listOf(
        UnknownHostException("api.github.com"), // DNS: no network, or a VPN whose DNS is broken
        ConnectException("refused"),
        NoRouteToHostException("no route"),
        SocketException("reset"),
        SSLHandshakeException("captive portal"),
        IOException("unexpected end of stream"),
    )

    private fun downApi(probe: NetworkProbe, e: Exception = UnknownHostException("api.github.com")) =
        GitHubApi(FakeHttp().on({ true }) { throw e }, TOKEN, FIXED_CLOCK, probe = probe)

    private fun Outcome<*>.error() = (this as Outcome.Failure).error

    // --- the classifier ---

    @Test fun connectionFailuresSayNoNetworkOnlyWhenTheDeviceIsOffline() {
        connectionFailures.forEach { e ->
            assertEquals("$e offline", BoardError.Network, ErrorClassifier.classifyFailure(e, offline))
            assertEquals("$e online", BoardError.Unreachable, ErrorClassifier.classifyFailure(e, online))
        }
    }

    @Test fun aTimeoutStaysATimeoutUnlessTheNetworkWentAway() {
        val e = SocketTimeoutException("read timed out")
        assertEquals(BoardError.Timeout, ErrorClassifier.classifyFailure(e, online))
        assertEquals(BoardError.Network, ErrorClassifier.classifyFailure(e, offline))
    }

    @Test fun nonNetworkExceptionsAreUnexpectedWhateverTheNetworkAndNeverAskTheProbe() {
        listOf(SecurityException("Bearer $TOKEN"), IllegalStateException("bug"), IllegalArgumentException()).forEach { e ->
            listOf(true, false).forEach { answer ->
                val probe = CountingProbe(answer)
                val error = ErrorClassifier.classifyFailure(e, probe) as BoardError.Unexpected
                assertEquals("request failed: ${e.javaClass.simpleName}", error.detail)
                assertFalse("no token in the text", error.detail.contains(TOKEN))
                assertEquals(0, probe.asked)
            }
        }
    }

    @Test fun theProbeIsAskedOncePerFailureAndNeverWhenGitHubAnswers() {
        val probe = CountingProbe(true)
        downApi(probe).approve(4)
        assertEquals(1, probe.asked)

        val answering = CountingProbe(true)
        val api = GitHubApi(FakeHttp().on({ true }) { status(500) }, TOKEN, FIXED_CLOCK, probe = answering)
        assertEquals(BoardError.Server(500), api.fetchIssues().error())
        assertEquals(0, answering.asked)
    }

    @Test fun withoutAProbeTheOldWordingIsKept() {
        assertEquals(BoardError.Network, ErrorClassifier.classifyFailure(UnknownHostException("x"), null))
        assertEquals(BoardError.Timeout, ErrorClassifier.classifyFailure(SocketTimeoutException(), null))
        val api = GitHubApi(FakeHttp().on({ true }) { throw UnknownHostException("x") }, TOKEN, FIXED_CLOCK)
        assertEquals(BoardError.Network, api.fetchIssues().error())
    }

    @Test fun aProbeThatThrowsKeepsTheRawWordingAndNeverCrashesTheCaller() {
        val broken = NetworkProbe { throw SecurityException("no ACCESS_NETWORK_STATE") }
        assertEquals(BoardError.Network, ErrorClassifier.classifyFailure(UnknownHostException("x"), broken))
        assertEquals(BoardError.Timeout, ErrorClassifier.classifyFailure(SocketTimeoutException(), broken))
        assertEquals(BoardError.Network, downApi(broken).approve(4).error())
        assertEquals(UpdateError.Network, updateError(broken))
    }

    @Test fun anHttpErrorOrAGarbledResponseNeverAsksTheProbe() {
        listOf(401, 403, 404, 422, 502).forEach { code ->
            val probe = CountingProbe(false)
            val api = GitHubApi(FakeHttp().on({ true }) { status(code) }, TOKEN, FIXED_CLOCK, probe = probe)
            assertFalse("$code is not a network error", api.fetchIssues().error() == BoardError.Network)
            assertEquals(0, probe.asked)
        }
    }

    @Test fun aRuntimeFailureInTheUpdateCheckIsUnexpectedAndNeverAsksTheProbe() {
        val probe = CountingProbe(true)
        val http = FakeStreamingHttp().on(LATEST_URL) { throw IllegalStateException("Bearer $TOKEN") }
        val error = updateError(probe, http) as UpdateError.Unexpected
        assertFalse(error.detail.contains(TOKEN))
        assertEquals(0, probe.asked)
    }

    // --- every caller of GitHubApi gets it ---

    @Test fun boardLoadAndApproveAndTestConnection() {
        assertEquals(BoardError.Unreachable, downApi(online).fetchIssues().error())
        assertEquals(BoardError.Network, downApi(offline).fetchIssues().error())
        assertEquals(BoardError.Unreachable, downApi(online).approve(4).error())
        assertEquals(BoardError.Unreachable, downApi(online).testConnection().error())
        assertEquals(BoardError.Timeout, downApi(online, SocketTimeoutException()).approve(4).error())
    }

    @Test fun previewTextWithTheDeviceOnline() {
        val preview = PreviewApi(downApi(online))
        listOf(preview.fetchIssue(7).error(), preview.fetchRecentComments(7).error()).forEach { error ->
            assertEquals(BoardError.Unreachable, error)
            assertEquals("Can't reach GitHub (VPN or DNS problem?)", messages.message(error))
        }
        assertTrue(messages.message(PreviewApi(downApi(offline)).fetchIssue(7).error()).startsWith("No network"))
    }

    @Test fun createTaskTextWithTheDeviceOnline() {
        val issue = NewIssue("Task", "body", listOf("status: todo"))
        val error = downApi(online).createIssue(issue).error()
        assertEquals(BoardError.Unreachable, error)
        assertEquals("Can't reach GitHub (VPN or DNS problem?)", messages.message(error))
        assertTrue("the form still warns the task may exist", CreateErrors.mayAlreadyExist(error))
        assertEquals(BoardError.Unreachable, downApi(online).repoSuggestions().error())
        assertEquals(BoardError.Unreachable, downApi(online).issuesUpdatedSince(NOW).error())
    }

    // --- the update check ---

    private fun updateError(probe: NetworkProbe, http: FakeStreamingHttp = FakeStreamingHttp()) =
        (Updater(http, TOKEN, FIXED_CLOCK, probe).check() as UpdateResult.Err).error

    @Test fun updateCheckSaysNoNetworkOnlyWhenTheDeviceIsOffline() {
        assertEquals(UpdateError.Network, updateError(offline)) // no route -> IOException
        assertEquals(UpdateError.Unreachable, updateError(online))
        val timedOut = FakeStreamingHttp().on(LATEST_URL) { throw SocketTimeoutException() }
        assertEquals(UpdateError.Unreachable, updateError(online, timedOut))
        assertEquals(UpdateError.Network, updateError(offline, timedOut))
        assertEquals(UpdateError.Network, (Updater(FakeStreamingHttp(), TOKEN, FIXED_CLOCK).check() as UpdateResult.Err).error)
        assertEquals(UpdateError.Network, (Updater(timedOut, TOKEN, FIXED_CLOCK).check() as UpdateResult.Err).error)
        val text = UpdateMessages { "14:05" }.message(UpdateError.Unreachable)
        assertEquals("Can't reach GitHub (VPN or DNS problem?)", text)
    }
}
