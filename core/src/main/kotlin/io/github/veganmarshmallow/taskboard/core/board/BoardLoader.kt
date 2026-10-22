package io.github.veganmarshmallow.taskboard.core.board

import io.github.veganmarshmallow.taskboard.core.error.BoardError
import io.github.veganmarshmallow.taskboard.core.github.GitHubApi
import io.github.veganmarshmallow.taskboard.core.http.Outcome
import io.github.veganmarshmallow.taskboard.core.snapshot.WidgetState
import java.time.Clock

/** One refresh: fetch, build, and fold the result into the previous [WidgetState]. */
class BoardLoader(
    private val clock: Clock,
    private val builder: BoardBuilder = BoardBuilder(),
) {
    /** [api] is null when no token is saved. */
    fun refresh(previous: WidgetState, api: GitHubApi?): WidgetState {
        val now = clock.instant()
        if (api == null) return WidgetState(null, BoardError.NoToken, now)
        val result = try {
            api.fetchIssues()
        } catch (e: RuntimeException) {
            // A bug or an odd platform exception must not crash the widget's process or leave it
            // stuck on "updating…"; report it like any other failed refresh.
            Outcome.Failure(BoardError.Unexpected("internal error: ${e.javaClass.simpleName}"))
        }
        return when (result) {
            is Outcome.Success -> previous.withBoard(builder.build(result.value, now))
            is Outcome.Failure -> previous.withError(result.error, now)
        }
    }
}
