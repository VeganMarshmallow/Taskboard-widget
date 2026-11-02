package io.github.veganmarshmallow.taskboard.core.board

/**
 * The count a tab shows, kept short so the tabs fit a narrow widget: [Exact] up to [MAX_SHOWN],
 * [Capped] past it (the app shows it as e.g. "999+"). Negative counts show as 0. Wording is the app's.
 */
sealed interface CountBadge {
    data class Exact(val count: Int) : CountBadge
    data class Capped(val max: Int) : CountBadge

    companion object {
        const val MAX_SHOWN = 999

        fun of(count: Int): CountBadge = when {
            count < 0 -> Exact(0)
            count > MAX_SHOWN -> Capped(MAX_SHOWN)
            else -> Exact(count)
        }

        fun of(board: Board, section: Section): CountBadge = of(board.count(section))
    }
}
