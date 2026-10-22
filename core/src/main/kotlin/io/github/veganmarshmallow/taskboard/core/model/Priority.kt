package io.github.veganmarshmallow.taskboard.core.model

/** Sort priority. Declared in display order. If both priority labels are present, HIGH wins. */
enum class Priority {
    HIGH, NORMAL, LOW;

    companion object {
        fun from(labels: Set<String>): Priority = when {
            Labels.PRIORITY_HIGH in labels -> HIGH
            Labels.PRIORITY_LOW in labels -> LOW
            else -> NORMAL
        }
    }
}
