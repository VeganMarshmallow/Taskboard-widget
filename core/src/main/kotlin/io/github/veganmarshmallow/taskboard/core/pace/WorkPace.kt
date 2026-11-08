package io.github.veganmarshmallow.taskboard.core.pace

/**
 * The "Work pace" setting (#83), stored in the board repo's `.board/settings.json` as `"pace"`.
 * [SAVE] (the default): Claude works one task at a time, the update workflow re-tests only the next
 * PR after a merge, and auto merge / Merge all wait for one PR's tests at a time. [FAST]: several
 * tasks at once, every open PR is updated after a merge, and PRs that were already green merge back
 * to back. A missing file, a missing key or an unknown value is [SAVE].
 */
enum class WorkPace(val value: String) {
    SAVE("save"),
    FAST("fast"),
    ;

    /**
     * Whether auto merge and Merge all may merge a PR that fell behind its default branch only because
     * they just merged another PR, without updating and re-testing it first (its own CI is still green).
     */
    val mergesBackToBack: Boolean get() = this == FAST

    /**
     * The merge commit trailer the update-prs workflow reads (#83: its token can't read the board repo,
     * so the pace travels with each merge the app makes). Null for [SAVE]: no trailer means Save usage.
     */
    val mergeTrailer: String? get() = if (this == FAST) "$TRAILER_KEY: $value" else null

    companion object {
        val DEFAULT = SAVE
        const val TRAILER_KEY = "Work-Pace"

        /** The pace a stored value names (case and surrounding spaces ignored), or null when it names none. */
        fun ofOrNull(value: String?): WorkPace? {
            val clean = value?.trim()?.lowercase() ?: return null
            return entries.firstOrNull { it.value == clean }
        }

        /** Like [ofOrNull], with [DEFAULT] for a missing or unknown value. */
        fun of(value: String?): WorkPace = ofOrNull(value) ?: DEFAULT
    }
}
