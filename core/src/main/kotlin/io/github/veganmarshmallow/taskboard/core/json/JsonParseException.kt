package io.github.veganmarshmallow.taskboard.core.json

/** Thrown when text is not valid JSON. [position] is the character offset of the problem. */
class JsonParseException(message: String, val position: Int) : Exception("$message at position $position")
