package io.github.veganmarshmallow.taskboard

import io.github.veganmarshmallow.taskboard.core.task.NewIssue
import io.github.veganmarshmallow.taskboard.core.task.NewTaskSession
import io.github.veganmarshmallow.taskboard.core.task.TaskDraft

/** Starts a valid "A task" draft in [session] and returns the issue it is ready to send. */
fun sendableIssue(session: NewTaskSession): NewIssue =
    (session.start(TaskDraft(title = "A task", repo = "Taskboard-widget")) as NewTaskSession.Start.Send).issue
