package io.github.veganmarshmallow.taskboard

import io.github.veganmarshmallow.taskboard.core.board.Board
import io.github.veganmarshmallow.taskboard.core.board.BoardBuilder
import io.github.veganmarshmallow.taskboard.core.model.Issue
import io.github.veganmarshmallow.taskboard.core.model.Labels
import java.time.Instant

/** Realistic fake boards for instrumented tests and screenshots. */
object FakeBoards {
    val NOW: Instant = Instant.parse("2026-10-22T14:05:00Z")

    const val HEBREW_TITLE =
        "לבנות דף נחיתה בעברית עם טופס הרשמה, תמיכה מלאה בכתיבה מימין לשמאל ובדיקות קצה לכל השדות"

    private fun issue(n: Int, title: String, vararg labels: String, open: Boolean = true, closedAt: Instant? = null) =
        Issue(n, title, labels.map(Labels::normalize).toSet(), open, closedAt)

    fun realistic(): Board = BoardBuilder().build(
        listOf(
            issue(1, "📋 Board", Labels.TODO), // the summary issue: never shown
            issue(12, "Which charting library for the dashboard?", Labels.BLOCKED, Labels.NEEDS_OWNER, Labels.PRIORITY_HIGH),
            issue(14, "Add dark mode to the portfolio site", Labels.IN_REVIEW),
            issue(6, "Android board widget", Labels.IN_PROGRESS, Labels.READY_FOR_CLAUDE),
            issue(15, HEBREW_TITLE, Labels.IN_PROGRESS, Labels.READY_FOR_CLAUDE),
            issue(9, "Deploy preview builds to Vercel", Labels.BLOCKED),
            issue(17, "Weekly digest email of finished tasks ✉️", Labels.TODO),
            issue(18, "Refactor the settings screen", Labels.TODO, Labels.READY_FOR_CLAUDE, Labels.PRIORITY_LOW),
            issue(4, "Set up CI", Labels.DONE, open = false, closedAt = NOW.minusSeconds(86_400)),
            issue(5, "Write the README", Labels.DONE, open = false, closedAt = NOW.minusSeconds(3 * 86_400)),
            issue(7, "Fix login redirect", Labels.DONE, open = false, closedAt = NOW.minusSeconds(5 * 86_400)),
        ),
        NOW,
    )

    /** Counts past the tab cap: Needs you 1500 (1000 PRs + 500 questions), Todo 1200, In progress 999, nothing blocked or done. */
    fun busy(): Board = BoardBuilder().build(
        (100 until 1100).map { issue(it, "Review PR $it", Labels.IN_REVIEW) } +
            (1100 until 1600).map { issue(it, "Question $it", Labels.TODO, Labels.NEEDS_OWNER) } +
            (1600 until 2800).map { issue(it, "Task $it", Labels.TODO) } +
            (2800 until 3799).map { issue(it, "Working on $it", Labels.IN_PROGRESS) },
        NOW,
    )

    fun emptyWithDone(): Board = BoardBuilder().build(
        listOf(issue(4, "Set up CI", Labels.DONE, open = false, closedAt = NOW.minusSeconds(3_600))),
        NOW,
    )
}
