package io.github.veganmarshmallow.taskboard.core.model

/**
 * Workflow status from the `status:` labels.
 *
 * Declared in precedence order: when an issue carries several status labels (it should not, but
 * it happens mid-edit), the first one in this list wins. The order favours what needs attention
 * soonest: a PR waiting for review beats a block, which beats active work, which beats the backlog.
 */
enum class Status(val label: String) {
    IN_REVIEW(Labels.IN_REVIEW),
    BLOCKED(Labels.BLOCKED),
    IN_PROGRESS(Labels.IN_PROGRESS),
    TODO(Labels.TODO),
    DONE(Labels.DONE);

    companion object {
        /** The winning status among [labels] (already normalized), or null if none is present. */
        fun from(labels: Set<String>): Status? = entries.firstOrNull { it.label in labels }
    }
}
