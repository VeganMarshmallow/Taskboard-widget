package io.github.veganmarshmallow.taskboard.core.filter

import io.github.veganmarshmallow.taskboard.core.board.BoardItem
import java.text.Normalizer
import java.util.Locale

/**
 * The filter's search text (#59). Split into words; a task matches when every word is found in its
 * title, its repo, or its number (`12` or `#12`). Matching ignores case and diacritics (`cafe`
 * finds "Café", Hebrew points are ignored too). Blank text matches everything.
 */
class TextQuery(text: String) {
    private val words: List<String> = fold(text.take(MAX_LENGTH)).split(WHITESPACE).filter { it.isNotEmpty() }

    val isEmpty: Boolean get() = words.isEmpty()

    fun matches(item: BoardItem): Boolean {
        if (words.isEmpty()) return true
        val haystack = fold(item.title + " " + item.repo.orEmpty())
        val number = item.number.toString()
        return words.all { word -> word in haystack || word.removePrefix("#") == number }
    }

    companion object {
        /** Longer text is cut: nobody types more, and it keeps a pasted novel from slowing every render. */
        const val MAX_LENGTH = 200
        private val WHITESPACE = Regex("\\s+")
        private val MARKS = Regex("\\p{M}+")

        /** Lowercase, without diacritics or other combining marks. */
        fun fold(s: String): String = MARKS.replace(Normalizer.normalize(s, Normalizer.Form.NFD), "").lowercase(Locale.ROOT)
    }
}
