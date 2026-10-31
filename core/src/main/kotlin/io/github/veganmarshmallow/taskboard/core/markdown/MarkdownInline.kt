package io.github.veganmarshmallow.taskboard.core.markdown

/**
 * Strips inline Markdown from one block's text, keeping what a reader would see: link and image
 * text without URLs, code spans without backticks, no emphasis markers, no HTML tags, decoded
 * entities and backslash escapes. Code span contents are kept verbatim (an `*` in code stays).
 * Underscores inside words (snake_case) are not emphasis. Never throws.
 *
 * The visible text is cut at [maxLength] characters (default [MAX_VISIBLE]) once all markup is gone,
 * so a long URL never pushes the words after it past the cut, and the cut never strands half a marker. Code spans ([CodeSpans])
 * and emphasis ([Emphasis]) are found in linear passes, so the cut is a display limit, not a guard:
 * a caller may pass a larger [maxLength] without hitting a slow path.
 */
object MarkdownInline {
    /*
     * Link text may hold balanced brackets (`[a [b] c](url)`) and the target balanced parentheses
     * (`(https://e.x/a_(b))`), one level deep and at most 8 times. Written as "unrolled loops" with a
     * bounded repeat: a repeated alternation recurses once per character in Java's regex engine and
     * overflowed the stack on a link a few thousand characters long.
     */
    private const val LINK_TEXT = """\[([^\[\]]*(?:\[[^\[\]]*][^\[\]]*){0,8})]"""
    private const val LINK_TARGET = """\([^()]*(?:\([^()]*\)[^()]*){0,8}\)"""
    private val IMAGE = Regex("!$LINK_TEXT$LINK_TARGET")
    private val LINK = Regex("$LINK_TEXT$LINK_TARGET")
    private val REFERENCE_LINK = Regex("""\[([^\[\]]+)]\[[^\[\]]*]""")
    private val AUTOLINK = Regex("""<((?:https?|mailto):[^<>\s]+)>""", RegexOption.IGNORE_CASE)
    private val BREAK_TAG = Regex("""</?(?:br|p|div|li|tr|td|th|ul|ol|hr)\b[^<>]*>""", RegexOption.IGNORE_CASE)
    private val HTML_TAG = Regex("""</?[A-Za-z][A-Za-z0-9-]*(?:\s[^<>]*)?/?>""")
    private val ESCAPE = Regex("""\\([!-/:-@\[-`{-~])""")
    private val NUMERIC_ENTITY = Regex("""&#(?:[xX]([0-9A-Fa-f]{1,6})|([0-9]{1,7}));""")
    private val NAMED_ENTITIES = mapOf(
        "&amp;" to "&", "&lt;" to "<", "&gt;" to ">", "&quot;" to "\"", "&apos;" to "'", "&nbsp;" to " ",
    )
    private val WHITESPACE = Regex("""\s+""")

    /**
     * Private-use characters stand in for escaped punctuation (U+E000–U+E07F) and code spans
     * (U+E080–U+E0FF) while the rest is stripped. Real ones in the input are removed first, and an
     * entity never decodes to one, so the stand-ins cannot be forged.
     */
    private const val ESCAPE_BASE = 0xE000
    private val ESCAPE_RANGE = ESCAPE_BASE..(ESCAPE_BASE + 0x7F)
    private const val CODE_TOKEN_START = '\uE080'
    private const val TOKEN_DIGIT_BASE = 0xE081
    private const val TOKEN_DIGITS = 0x7F
    private val CODE_TOKEN = Regex("\uE080([\uE081-\uE0FF]+)")
    private val RESERVED_RANGE = ESCAPE_BASE..0xE0FF

    /** Longest text [strip] returns by default: every caller shows less (the full-comment view caps each block here). */
    const val MAX_VISIBLE = 2_000

    fun strip(text: String, maxLength: Int = MAX_VISIBLE): String {
        val clean = text.filterNot { it.code in RESERVED_RANGE }
        val spans = CodeSpans.find(clean)
        val outside = StringBuilder(clean.length)
        var last = 0
        spans.forEachIndexed { index, span ->
            outside.append(clean, last, span.start).append(codeToken(index))
            last = span.end
        }
        outside.append(clean, last, clean.length)
        val visible = CODE_TOKEN.replace(stripMarkup(outside.toString())) { m ->
            spans.getOrNull(tokenIndex(m.groupValues[1]))?.content?.trim().orEmpty()
        }
        return clip(WHITESPACE.replace(visible, " ").trim(), maxLength)
    }

    /**
     * Everything but code spans, which arrive as tokens: a code span is one opaque piece of the text,
     * so links and emphasis may wrap it (`**`Name`**`, `[`f`](u)`) but never reach inside it.
     */
    private fun stripMarkup(text: String): String {
        var s = ESCAPE.replace(text) { (ESCAPE_BASE + it.groupValues[1][0].code).toChar().toString() }
        s = IMAGE.replace(s, "$1")
        s = LINK.replace(s, "$1")
        s = REFERENCE_LINK.replace(s, "$1")
        s = AUTOLINK.replace(s, "$1")
        s = BREAK_TAG.replace(s, " ")
        s = HTML_TAG.replace(s, "")
        s = decodeEntities(Emphasis.strip(s))
        return s.map { if (it.code in ESCAPE_RANGE) (it.code - ESCAPE_BASE).toChar() else it }.joinToString("")
    }

    /** [CODE_TOKEN_START] and the span's index in base [TOKEN_DIGITS], one private-use character per digit. */
    private fun codeToken(index: Int): String {
        val token = StringBuilder()
        var rest = index
        do {
            token.append((TOKEN_DIGIT_BASE + rest % TOKEN_DIGITS).toChar())
            rest /= TOKEN_DIGITS
        } while (rest > 0)
        return token.append(CODE_TOKEN_START).reverse().toString()
    }

    private fun tokenIndex(digits: String): Int = digits.fold(0) { n, c -> n * TOKEN_DIGITS + (c.code - TOKEN_DIGIT_BASE) }

    /** At most [max] characters, never ending in half a surrogate pair. */
    private fun clip(s: String, max: Int): String {
        if (s.length <= max) return s
        if (max <= 0) return ""
        return s.substring(0, if (Character.isHighSurrogate(s[max - 1])) max - 1 else max)
    }

    private fun decodeEntities(s: String): String {
        if ('&' !in s) return s
        var out = NUMERIC_ENTITY.replace(s) { m ->
            val code = m.groupValues[1].takeIf { it.isNotEmpty() }?.toIntOrNull(16) ?: m.groupValues[2].toIntOrNull()
            if (code == null || code == 0 || code > 0x10FFFF || code in 0xD800..0xDFFF || code in RESERVED_RANGE) "\uFFFD"
            else String(Character.toChars(code))
        }
        NAMED_ENTITIES.forEach { (entity, value) -> out = out.replace(entity, value, ignoreCase = true) }
        return out
    }
}
