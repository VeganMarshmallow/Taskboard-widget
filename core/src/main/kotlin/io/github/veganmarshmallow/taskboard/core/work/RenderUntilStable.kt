package io.github.veganmarshmallow.taskboard.core.work

/**
 * Draws what [load] returns, then loads again and redraws if the value changed while drawing.
 *
 * A tab tap redraws the widget from the cache while a refresh may be saving a newer board on
 * another thread. The refresh always saves before it draws, so checking once more after our own
 * draw means the last thing on screen is never older than the last thing saved: either we saw the
 * new value and drew it, or the refresh's own draw comes after ours.
 *
 * Stops after [maxRounds] draws, so a writer that never stops cannot keep the caller spinning.
 * Returns how many draws were made.
 */
object RenderUntilStable {
    const val DEFAULT_MAX_ROUNDS = 3

    fun <T> run(load: () -> T, draw: (T) -> Unit, maxRounds: Int = DEFAULT_MAX_ROUNDS): Int {
        require(maxRounds >= 1) { "maxRounds must be at least 1" }
        var current = load()
        var rounds = 0
        while (true) {
            draw(current)
            rounds++
            if (rounds >= maxRounds) return rounds
            val latest = load()
            if (latest == current) return rounds
            current = latest
        }
    }
}
