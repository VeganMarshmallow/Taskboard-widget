package io.github.veganmarshmallow.taskboard.core.markdown

/**
 * Strips inline Markdown from one block's text, keeping what a reader would see: link and image
 * text without URLs, code spans without backticks, no emphasis markers, no HTML tags, decoded
 * entities and backslash escapes. Code span contents are kept verbatim (an `*` in code stays).
 * Underscores inside words (snake_case) are not emphasis. Never throws.
 *
 * Emphasis matching is quadratic when there are thousands of unclosed `*` or `_`, so the visible
 * text is cut at [maxLength] characters (default [MAX_VISIBLE]) before it, after links, images and
 * tags are gone: a long URL never pushes the words after it past the cut. Every caller shows far
 * less than that.
 */
object MarkdownInline {
    private val CODE_SPAN = Regex("""(`+)(.+?)\1""")
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
    private val EMPHASIS = listOf(
        Regex("""\*\*(?!\s)(.+?)(?<!\s)\*\*"""),
        Regex("""(?<![\p{L}\p{N}_])__(?!\s)(.+?)(?<!\s)__(?![\p{L}\p{N}_])"""),
        Regex("""~~(?!\s)(.+?)(?<!\s)~~"""),
        Regex("""(?<![\p{L}\p{N}*\\])\*(?![\s*])(.+?)(?<![\s*\\])\*(?![\p{L}\p{N}*])"""),
        Regex("""(?<![\p{L}\p{N}_\\])_(?![\s_])(.+?)(?<![\s_\\])_(?![\p{L}\p{N}_])"""),
    )
    private val ESCAPE = Regex("""\\([!-/:-@\[-`{-~])""")
    private val NUMERIC_ENTITY = Regex("""&#(?:[xX]([0-9A-Fa-f]{1,6})|([0-9]{1,7}));""")
    private val NAMED_ENTITIES = mapOf(
        "&amp;" to "&", "&lt;" to "<", "&gt;" to ">", "&quot;" to "\"", "&apos;" to "'", "&nbsp;" to " ",
    )
    private val WHITESPACE = Regex("""\s+""")

    /** Private-use characters stand in for escaped punctuation while emphasis is matched. */
    private const val ESCAPE_BASE = 0xE000
    private val ESCAPE_RANGE = ESCAPE_BASE..(ESCAPE_BASE + 0x7F)

    const val MAX_VISIBLE = 2_000

    fun strip(text: String, maxLength: Int = MAX_VISIBLE): String {
        val clean = text.filterNot { it.code in ESCAPE_RANGE }
        val out = StringBuilder()
        var last = 0
        for (m in CODE_SPAN.findAll(clean)) {
            if (out.length >= maxLength) break
            out.append(stripOutsideCode(clean.substring(last, m.range.first), maxLength - out.length))
            out.append(m.groupValues[2].trim())
            last = m.range.last + 1
        }
        if (out.length < maxLength) out.append(stripOutsideCode(clean.substring(last), maxLength - out.length))
        return clip(WHITESPACE.replace(out, " ").trim(), maxLength)
    }

    private fun stripOutsideCode(segment: String, budget: Int): String {
        var s = ESCAPE.replace(segment) { (ESCAPE_BASE + it.groupValues[1][0].code).toChar().toString() }
        s = IMAGE.replace(s, "$1")
        s = LINK.replace(s, "$1")
        s = REFERENCE_LINK.replace(s, "$1")
        s = AUTOLINK.replace(s, "$1")
        s = BREAK_TAG.replace(s, " ")
        s = HTML_TAG.replace(s, "")
        s = clip(s, budget)
        // Nested emphasis (***both***, **_mixed_**) needs a few passes; stop once nothing changes.
        for (pass in 1..MAX_EMPHASIS_PASSES) {
            val before = s
            EMPHASIS.forEach { re -> s = re.replace(s, "$1") }
            if (s == before) break
        }
        s = decodeEntities(s)
        return s.map { if (it.code in ESCAPE_RANGE) (it.code - ESCAPE_BASE).toChar() else it }.joinToString("")
    }

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
            if (code == null || code == 0 || code > 0x10FFFF || code in 0xD800..0xDFFF) "\uFFFD"
            else String(Character.toChars(code))
        }
        NAMED_ENTITIES.forEach { (entity, value) -> out = out.replace(entity, value, ignoreCase = true) }
        return out
    }

    private const val MAX_EMPHASIS_PASSES = 4
}
