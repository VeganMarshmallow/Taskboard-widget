package io.github.veganmarshmallow.taskboard

import io.github.veganmarshmallow.taskboard.core.error.BoardError
import io.github.veganmarshmallow.taskboard.core.model.Issue
import io.github.veganmarshmallow.taskboard.core.model.Labels
import io.github.veganmarshmallow.taskboard.core.preview.IssueComment
import io.github.veganmarshmallow.taskboard.core.preview.IssueDetails
import io.github.veganmarshmallow.taskboard.core.preview.PreviewScreen
import io.github.veganmarshmallow.taskboard.core.preview.TaskPreview

/** Realistic preview states for instrumented tests and screenshots. */
object FakePreviews {
    private fun details(n: Int, title: String, body: String, vararg labels: String) =
        IssueDetails(Issue(n, title, labels.map(Labels::normalize).toSet(), isOpen = true), body)

    val TODO_BODY = """
        ### What should be done
        Tapping a task opens a preview inside the app instead of going straight to GitHub:
        - Title, and a very short summary of the task (2–3 lines)
        - An **Approve** button for unapproved Todo tasks, with the confirmation
        - At the bottom, a link [Open on GitHub](https://github.com)

        ### Repo
        VeganMarshmallow/Taskboard-widget
    """.trimIndent()

    fun todoLoaded(): PreviewScreen = PreviewScreen.initial(11, null)
        .issueLoaded(details(11, "Widget: task preview with summary and approve", TODO_BODY, Labels.TODO))

    fun blockedWithClaudeLine(): PreviewScreen = PreviewScreen.initial(12, null)
        .issueLoaded(details(12, "Which charting library for the dashboard?", "### What should be done\nPick a charting library for the **stats** dashboard and wire it up.", Labels.BLOCKED, Labels.NEEDS_OWNER))
        .commentsLoaded(
            listOf(
                IssueComment(1, "🤖 Started in run 2026-10-22 10:00. Plan: compare 3 libraries", null),
                IssueComment(2, "⛔ Blocked: Chart.js or ECharts? ECharts is heavier but has the heatmap you asked for.", null),
            ),
        )

    fun hebrewInProgress(): PreviewScreen = PreviewScreen.initial(15, null)
        .issueLoaded(
            details(
                15, FakeBoards.HEBREW_TITLE,
                "### What should be done\n- לבנות **דף נחיתה** בעברית עם טופס הרשמה\n- תמיכה מלאה ב־RTL ובדיקות קצה לכל השדות, כולל שדות ריקים וארוכים מאוד ושגיאות שרת",
                Labels.IN_PROGRESS, Labels.READY_FOR_CLAUDE,
            ),
        )
        .commentsLoaded(listOf(IssueComment(1, "🤖 התחלתי לעבוד על הטופס. תוכנית: רכיב טופס, ולידציה, בדיקות", null)))

    fun cachedWhileLoading(): PreviewScreen =
        PreviewScreen.initial(17, TaskPreview.fromBoard(FakeBoards.realistic(), 17))

    fun failedWithCache(): PreviewScreen = cachedWhileLoading().issueFailed(BoardError.Network)

    fun failedNoToken(): PreviewScreen = PreviewScreen.initial(17, null).issueFailed(BoardError.NoToken)

    fun noDescription(): PreviewScreen = PreviewScreen.initial(18, null)
        .issueLoaded(details(18, "Refactor the settings screen", "", Labels.TODO, Labels.READY_FOR_CLAUDE, Labels.PRIORITY_LOW))

    fun justApproved(): PreviewScreen = todoLoaded().approveStarted().approveSucceeded()
}
