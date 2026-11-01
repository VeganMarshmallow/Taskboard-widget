package io.github.veganmarshmallow.taskboard.core.update

import io.github.veganmarshmallow.taskboard.core.error.BoardError
import io.github.veganmarshmallow.taskboard.core.error.ErrorClassifier
import io.github.veganmarshmallow.taskboard.core.error.NetworkProbe
import io.github.veganmarshmallow.taskboard.core.http.HttpRequest
import io.github.veganmarshmallow.taskboard.core.http.Redirects
import io.github.veganmarshmallow.taskboard.core.http.StreamingHttpClient
import io.github.veganmarshmallow.taskboard.core.http.StreamingResponse
import io.github.veganmarshmallow.taskboard.core.update.UpdateErrorMapper.Step
import java.io.IOException
import java.time.Clock

/**
 * Sends updater requests, following an asset's redirect to the storage host via [Redirects]
 * (which strips the token for any host but api.github.com). Returns an open 2xx response that the
 * caller must close, or the mapped error. Never throws. Connection failures are worded by the
 * board's [ErrorClassifier.classifyFailure] with [probe], so the update check says the same as the board.
 */
class AssetFetcher(
    private val http: StreamingHttpClient,
    private val clock: Clock,
    private val probe: NetworkProbe = NetworkProbe.ASSUME_OFFLINE,
) {

    fun fetch(first: HttpRequest, firstStep: Step): UpdateResult<StreamingResponse> {
        var request = first
        var step = firstStep
        repeat(Redirects.MAX_HOPS + 1) {
            val response = when (val r = open(request)) {
                is UpdateResult.Err -> return r
                is UpdateResult.Ok -> r.value
            }
            if (!Redirects.isRedirect(response.status)) return finish(response, step)
            val location = response.header("location")
            response.close()
            request = Redirects.next(request, location)
                ?: return UpdateResult.Err(UpdateError.Unexpected("unsafe or missing redirect target"))
            if (!Redirects.isApiUrl(request.url)) step = Step.STORAGE
        }
        return UpdateResult.Err(UpdateError.Unexpected("too many redirects"))
    }

    private fun open(request: HttpRequest): UpdateResult<StreamingResponse> = try {
        UpdateResult.Ok(http.open(request))
    } catch (e: IOException) {
        UpdateResult.Err(connectionError(e))
    } catch (e: RuntimeException) {
        UpdateResult.Err(UpdateError.Unexpected("request failed: ${e.javaClass.simpleName}"))
    }

    /** A request or a body read that failed mid-way: "No network" only when the device has none. */
    fun connectionError(e: IOException): UpdateError =
        if (ErrorClassifier.classifyFailure(e, probe) == BoardError.Network) UpdateError.Network else UpdateError.Unreachable

    private fun finish(response: StreamingResponse, step: Step): UpdateResult<StreamingResponse> {
        if (response.isSuccessful) return UpdateResult.Ok(response)
        val body = try {
            response.readText(ERROR_BODY_LIMIT)
        } catch (e: IOException) {
            ""
        } finally {
            response.close()
        }
        val error = UpdateErrorMapper.map(response.toHttpResponse(body), step, clock.instant())
            ?: UpdateError.Unexpected("HTTP ${response.status}")
        return UpdateResult.Err(error)
    }

    private companion object {
        const val ERROR_BODY_LIMIT = 16 * 1024
    }
}
