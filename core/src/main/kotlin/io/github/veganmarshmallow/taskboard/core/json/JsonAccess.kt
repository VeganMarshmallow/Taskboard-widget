package io.github.veganmarshmallow.taskboard.core.json

/** Typed accessors over parsed JSON objects. They return null instead of throwing on a type mismatch. */
internal fun Map<*, *>.string(key: String): String? = this[key] as? String

internal fun Map<*, *>.long(key: String): Long? = when (val v = this[key]) {
    is Long -> v
    is Double -> if (v % 1.0 == 0.0) v.toLong() else null
    else -> null
}

internal fun Map<*, *>.list(key: String): List<*>? = this[key] as? List<*>
