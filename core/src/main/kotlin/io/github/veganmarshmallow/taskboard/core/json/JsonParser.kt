package io.github.veganmarshmallow.taskboard.core.json

/**
 * Minimal, dependency-free JSON parser.
 *
 * Produces plain Kotlin values: [Map] (String keys, insertion ordered), [List], [String],
 * [Long] (integral numbers that fit), [Double] (other numbers), [Boolean] and `null`.
 */
object JsonParser {
    private const val MAX_DEPTH = 200
    private const val HEX_DIGITS = "0123456789abcdef"

    fun parse(text: String): Any? {
        val reader = Reader(text)
        reader.skipWhitespace()
        val value = reader.readValue(0)
        reader.skipWhitespace()
        if (!reader.atEnd()) reader.fail("Unexpected trailing content")
        return value
    }

    private class Reader(private val text: String) {
        private var pos = 0

        fun atEnd() = pos >= text.length

        fun fail(message: String): Nothing = throw JsonParseException(message, pos)

        fun skipWhitespace() {
            while (pos < text.length && text[pos].let { it == ' ' || it == '\n' || it == '\r' || it == '\t' }) pos++
        }

        fun readValue(depth: Int): Any? {
            if (depth > MAX_DEPTH) fail("Nesting too deep")
            if (atEnd()) fail("Unexpected end of input")
            return when (val c = text[pos]) {
                '{' -> readObject(depth)
                '[' -> readArray(depth)
                '"' -> readString()
                't' -> readLiteral("true", true)
                'f' -> readLiteral("false", false)
                'n' -> readLiteral("null", null)
                else -> if (c == '-' || c in '0'..'9') readNumber() else fail("Unexpected character '$c'")
            }
        }

        private fun readLiteral(word: String, value: Any?): Any? {
            if (!text.startsWith(word, pos)) fail("Invalid literal")
            pos += word.length
            return value
        }

        private fun readObject(depth: Int): Map<String, Any?> {
            val result = LinkedHashMap<String, Any?>()
            pos++ // {
            skipWhitespace()
            if (peek() == '}') { pos++; return result }
            while (true) {
                skipWhitespace()
                if (peek() != '"') fail("Expected object key")
                val key = readString()
                skipWhitespace()
                expect(':')
                skipWhitespace()
                result[key] = readValue(depth + 1)
                skipWhitespace()
                when (peek()) {
                    ',' -> pos++
                    '}' -> { pos++; return result }
                    else -> fail("Expected ',' or '}'")
                }
            }
        }

        private fun readArray(depth: Int): List<Any?> {
            val result = ArrayList<Any?>()
            pos++ // [
            skipWhitespace()
            if (peek() == ']') { pos++; return result }
            while (true) {
                skipWhitespace()
                result.add(readValue(depth + 1))
                skipWhitespace()
                when (peek()) {
                    ',' -> pos++
                    ']' -> { pos++; return result }
                    else -> fail("Expected ',' or ']'")
                }
            }
        }

        private fun readString(): String {
            pos++ // opening quote
            val sb = StringBuilder()
            while (true) {
                if (atEnd()) fail("Unterminated string")
                val c = text[pos++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> sb.append(readEscape())
                    c < ' ' -> { pos--; fail("Control character in string") }
                    else -> sb.append(c)
                }
            }
        }

        private fun readEscape(): Char {
            if (atEnd()) fail("Unterminated escape")
            return when (val c = text[pos++]) {
                '"' -> '"'
                '\\' -> '\\'
                '/' -> '/'
                'b' -> '\b'
                'f' -> '\u000C'
                'n' -> '\n'
                'r' -> '\r'
                't' -> '\t'
                'u' -> readHex4()
                else -> { pos--; fail("Invalid escape '\\$c'") }
            }
        }

        private fun readHex4(): Char {
            if (pos + 4 > text.length) fail("Truncated unicode escape")
            var code = 0
            repeat(4) {
                // Char.digitToIntOrNull would accept non-ASCII digits; toIntOrNull(16) would accept a sign.
                val digit = HEX_DIGITS.indexOf(text[pos + it].lowercaseChar())
                if (digit < 0) fail("Invalid unicode escape")
                code = code * 16 + digit
            }
            pos += 4
            return code.toChar()
        }

        private fun readNumber(): Any {
            val start = pos
            if (peek() == '-') pos++
            if (atEnd() || text[pos] !in '0'..'9') fail("Invalid number")
            if (text[pos] == '0') pos++ else skipDigits()
            var integral = true
            if (peek() == '.') {
                integral = false
                pos++
                if (atEnd() || text[pos] !in '0'..'9') fail("Invalid fraction")
                skipDigits()
            }
            if (peek() == 'e' || peek() == 'E') {
                integral = false
                pos++
                if (peek() == '+' || peek() == '-') pos++
                if (atEnd() || text[pos] !in '0'..'9') fail("Invalid exponent")
                skipDigits()
            }
            val raw = text.substring(start, pos)
            return if (integral) raw.toLongOrNull() ?: raw.toDouble() else raw.toDouble()
        }

        private fun skipDigits() {
            while (pos < text.length && text[pos] in '0'..'9') pos++
        }

        private fun peek(): Char? = if (atEnd()) null else text[pos]

        private fun expect(c: Char) {
            if (peek() != c) fail("Expected '$c'")
            pos++
        }
    }
}
