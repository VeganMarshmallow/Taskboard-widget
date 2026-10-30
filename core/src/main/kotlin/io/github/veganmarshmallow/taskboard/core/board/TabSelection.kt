package io.github.veganmarshmallow.taskboard.core.board

/**
 * Which tab a widget shows. The user's pick, once made, is remembered (per widget, by the app) and
 * always wins. Until then: Needs you when it has items; otherwise Blocked when a blocked task waits
 * on the owner (#34 moved those out of Needs you, but they still come first); otherwise Todo.
 */
object TabSelection {
    fun default(board: Board?): Section = when {
        board == null -> Section.TODO
        board.count(Section.NEEDS_YOU) > 0 -> Section.NEEDS_YOU
        board.section(Section.BLOCKED).items.any { it.needsYouReason != null } -> Section.BLOCKED
        else -> Section.TODO
    }

    /** [savedKey] is what [key] stored for this widget, or null when the user never picked a tab. */
    fun resolve(savedKey: String?, board: Board?): Section = parse(savedKey) ?: default(board)

    /** Stable storage key for a tab. Enum names are used so a reorder never changes stored picks. */
    fun key(section: Section): String = section.name

    /** Null for null, unknown or malformed keys (e.g. a tab removed in a later version). */
    fun parse(key: String?): Section? = key?.let { k -> Section.entries.firstOrNull { it.name == k.trim() } }
}
