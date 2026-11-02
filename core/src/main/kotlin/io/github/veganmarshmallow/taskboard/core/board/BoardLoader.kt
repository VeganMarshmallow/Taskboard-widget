package io.github.veganmarshmallow.taskboard.core.board

import io.github.veganmarshmallow.taskboard.core.error.BoardError
import io.github.veganmarshmallow.taskboard.core.github.GitHubApi
import io.github.veganmarshmallow.taskboard.core.http.Outcome
import io.github.veganmarshmallow.taskboard.core.model.Issue
import io.github.veganmarshmallow.taskboard.core.snapshot.WidgetState
import io.github.veganmarshmallow.taskboard.core.work.crashError
import java.time.Clock

/** One refresh: fetch, build, and fold the result into the previous [WidgetState]. */
class BoardLoader(
    private val clock: Clock,
    private val builder: BoardBuilder = BoardBuilder(),
    /** Ranks the PRs waiting for a merge (#45); null skips that step. */
    private val mergeLanes: MergeLaneSource? = null,
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
            Outcome.Failure(crashError(e))
        }
        return when (result) {
            is Outcome.Success -> previous.withBoard(withLanes(builder.build(result.value, now), result.value, api))
            is Outcome.Failure -> previous.withError(result.error, now)
        }
    }

    private fun withLanes(board: Board, issues: List<Issue>, api: GitHubApi): Board {
        val source = mergeLanes ?: return board
        if (board.sections.none { s -> s.items.any { it.canMerge } }) return board
        return board.withMergeLanes(source.lanes(issues, api))
    }
}
