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
        return when (val result = api.fetchIssues()) {
            is Outcome.Success -> previous.withBoard(builder.build(result.value, now))
            is Outcome.Failure -> previous.withError(result.error, now)
        }
    }
}
