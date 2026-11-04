package io.github.veganmarshmallow.taskboard.core.model

import java.time.Instant

/** A GitHub issue (or pull request) as returned by the issues endpoint, reduced to what the board needs. */
data class Issue(
    val number: Int,
    val title: String,
    val labels: Set<String>,
    val isOpen: Boolean,
    val closedAt: Instant? = null,
    val isPullRequest: Boolean = false,
    /** The task's project repo from its body's Repo field (#45 looks up its PR; #59 filters by it, Done tasks too). */
    val repo: Repo? = null,
)
