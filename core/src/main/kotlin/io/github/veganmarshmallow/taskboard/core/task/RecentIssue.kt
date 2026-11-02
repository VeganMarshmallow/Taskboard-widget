package io.github.veganmarshmallow.taskboard.core.task

import java.time.Instant

/** One entry of an issues listing, as much as [SubmitRecovery] needs. [createdAt] is null when unreadable. */
data class RecentIssue(val number: Int, val title: String, val createdAt: Instant?, val isPullRequest: Boolean = false)
