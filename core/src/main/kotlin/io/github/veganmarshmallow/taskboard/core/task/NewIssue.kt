package io.github.veganmarshmallow.taskboard.core.task

/** A validated issue, ready to send to `POST /repos/{owner}/{repo}/issues`. */
data class NewIssue(val title: String, val body: String, val labels: List<String>)

/**
 * The issue GitHub created. [number] is null if the 2xx response could not be read (the issue
 * still exists). [missingLabels] lists requested labels GitHub silently left off, which it does
 * when the token may not set labels; empty when unknown.
 */
data class CreatedIssue(val number: Int?, val missingLabels: List<String> = emptyList())
