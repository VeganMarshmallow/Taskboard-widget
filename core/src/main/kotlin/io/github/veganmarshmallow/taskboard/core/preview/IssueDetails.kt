package io.github.veganmarshmallow.taskboard.core.preview

import io.github.veganmarshmallow.taskboard.core.model.Issue

/**
 * One issue as `GET /repos/{owner}/{repo}/issues/{n}` returns it, reduced to what the preview shows.
 * [labelColors] maps each normalized label name to its GitHub hex colour (null when not sent).
 */
data class IssueDetails(val issue: Issue, val body: String, val labelColors: Map<String, String?> = emptyMap())
