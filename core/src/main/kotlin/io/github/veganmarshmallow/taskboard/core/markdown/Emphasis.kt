package io.github.veganmarshmallow.taskboard.core.markdown

/**
 * Removes emphasis markers (`*`, `**`, `_`, `__`, `~~`) in one linear pass over delimiter runs: a
 * simplified CommonMark "delimiter stack". [DelimiterRuns] decides which runs can open or close; a
 * closer then matches the nearest open run of the same character, using two markers when both
 * sides have two (`**`) and one otherwise, so `***both***` loses all six. Openers of other
 * characters between a matched pair are dropped, as in CommonMark, which keeps every run pushed and
 * popped at most once: O(n) for any input. Unmatched markers stay as text.
 */
internal object Emphasis {
    fun strip(text: String): String {
        val runs = DelimiterRuns.scan(text)
        if (runs.isEmpty()) return text
        val openers = OpenerStacks()
        for (run in runs) {
            if (run.canClose) close(run, openers)
            if (run.canOpen && run.remaining > 0) openers.push(run)
        }
        return render(text, runs)
    }

    private fun close(closer: DelimiterRun, openers: OpenerStacks) {
        while (closer.remaining > 0) {
            val opener = openers.top(closer.char) ?: return
            val used = if (opener.remaining >= 2 && closer.remaining >= 2) 2 else 1
            opener.removedAtEnd += used
            closer.removedAtStart += used
            openers.dropOthersAfter(opener)
            if (opener.remaining == 0) openers.pop(opener.char)
        }
    }

    private fun render(text: String, runs: List<DelimiterRun>): String {
        val out = StringBuilder(text.length)
        var pos = 0
        for (run in runs) {
            out.append(text, pos, run.start)
            out.append(text, run.start + run.removedAtStart, run.end - run.removedAtEnd)
            pos = run.end
        }
        return out.append(text, pos, text.length).toString()
    }

    /** One stack of still-open runs per marker character. */
    private class OpenerStacks {
        private val stacks = DelimiterRuns.MARKERS.associateWith { ArrayList<DelimiterRun>() }

        fun push(run: DelimiterRun) {
            stacks.getValue(run.char).add(run)
        }

        fun top(char: Char): DelimiterRun? = stacks.getValue(char).lastOrNull()

        fun pop(char: Char) {
            val stack = stacks.getValue(char)
            stack.removeAt(stack.lastIndex)
        }

        /** Openers of other characters inside a matched pair can no longer be closed. */
        fun dropOthersAfter(opener: DelimiterRun) {
            for ((char, stack) in stacks) {
                if (char == opener.char) continue
                while (stack.isNotEmpty() && stack[stack.lastIndex].start > opener.start) stack.removeAt(stack.lastIndex)
            }
        }
    }
}
