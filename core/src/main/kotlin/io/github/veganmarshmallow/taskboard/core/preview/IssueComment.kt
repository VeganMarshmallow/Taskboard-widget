package io.github.veganmarshmallow.taskboard.core.preview

import java.time.Instant

/** One issue comment. [createdAt] is null when GitHub sent none (it always should). */
data class IssueComment(val id: Long, val body: String, val createdAt: Instant?)
