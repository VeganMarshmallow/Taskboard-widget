package io.github.veganmarshmallow.taskboard.core

import io.github.veganmarshmallow.taskboard.core.json.JsonParseException
import io.github.veganmarshmallow.taskboard.core.json.JsonParser
import io.github.veganmarshmallow.taskboard.core.json.JsonWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class JsonParserTest {
    @Test fun parsesScalars() {
        assertEquals(true, JsonParser.parse("true"))
        assertEquals(false, JsonParser.parse(" false "))
        assertNull(JsonParser.parse("null"))
        assertEquals(42L, JsonParser.parse("42"))
        assertEquals(-7L, JsonParser.parse("-7"))
        assertEquals(1.5, JsonParser.parse("1.5"))
        assertEquals(1.0e3, JsonParser.parse("1e3"))
        assertEquals("hi", JsonParser.parse("\"hi\""))
    }

    @Test fun parsesNestedStructures() {
        val v = JsonParser.parse("""{"a":[1,{"b":null}],"c":{}, "d":[]}""") as Map<*, *>
        assertEquals(listOf(1L, mapOf("b" to null)), v["a"])
        assertEquals(emptyMap<String, Any>(), v["c"])
        assertEquals(emptyList<Any>(), v["d"])
    }

    @Test fun decodesEscapesAndUnicode() {
        assertEquals("a\"b\\c/\n\t\r\b\u000C", JsonParser.parse(""""a\"b\\c\/\n\t\r\b\f""""))
        assertEquals("é", JsonParser.parse("\"\\u00e9\""))
        // surrogate pair for 🚀 via escapes, and raw emoji
        assertEquals("🚀", JsonParser.parse("\"\\ud83d\\ude80\""))
        assertEquals("📋 Board", JsonParser.parse("\"📋 Board\""))
    }

    @Test fun hugeIntegersFallBackToDouble() {
        assertEquals(1.0e20, JsonParser.parse("100000000000000000000"))
    }

    @Test fun rejectsMalformedInput() {
        listOf(
            "", "   ", "{", "[1,", "[1 2]", "{\"a\" 1}", "{a:1}", "\"unterminated", "tru", "nul",
            "01", "1.", "-", "1e", "[1,]", "{\"a\":1,}", "\"bad \\x escape\"", "\"\\u12\"", "{} extra",
            "\"line\nbreak\"", "<html>Bad gateway</html>",
        ).forEach { bad ->
            assertThrows("should reject: $bad", JsonParseException::class.java) { JsonParser.parse(bad) }
        }
    }

    @Test fun rejectsExcessiveNesting() {
        assertThrows(JsonParseException::class.java) { JsonParser.parse("[".repeat(500) + "]".repeat(500)) }
    }

    @Test fun writerRoundTrips() {
        val value = mapOf(
            "s" to "q\"uote\\ \n 🚀 \u0001", "n" to 5L, "d" to 1.5, "b" to true, "z" to null,
            "l" to listOf(1L, "x", mapOf("k" to false)),
        )
        assertEquals(value, JsonParser.parse(JsonWriter.write(value)))
    }

    @Test fun writerRejectsNonFiniteAndUnknownTypes() {
        assertThrows(IllegalArgumentException::class.java) { JsonWriter.write(Double.NaN) }
        assertThrows(IllegalArgumentException::class.java) { JsonWriter.write(Any()) }
    }
}
