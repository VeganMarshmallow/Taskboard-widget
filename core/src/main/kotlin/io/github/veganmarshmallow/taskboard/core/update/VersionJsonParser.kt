package io.github.veganmarshmallow.taskboard.core.update

import io.github.veganmarshmallow.taskboard.core.json.JsonParseException
import io.github.veganmarshmallow.taskboard.core.json.JsonParser
import io.github.veganmarshmallow.taskboard.core.json.long
import io.github.veganmarshmallow.taskboard.core.json.string

/**
 * Parses the `version.json` asset CI publishes next to the APK:
 * `{"versionCode":42,"versionName":"0.1.42","sha":"<commit>","apkSha256":"<hex>","apkSize":704244}`.
 * Only versionCode is required. Optional fields that are present but malformed are rejected,
 * because a wrong checksum or size would make every download look corrupt.
 */
object VersionJsonParser {
    private const val MAX_LENGTH = 64 * 1024
    private const val MAX_NAME = 64
    private val SHA256 = Regex("[0-9a-fA-F]{64}")
    private val COMMIT = Regex("[0-9a-fA-F]{7,64}")

    /** Throws [MalformedVersionException] for anything that is not a usable description. */
    fun parse(text: String): VersionInfo {
        if (text.length > MAX_LENGTH) throw MalformedVersionException("version.json too large")
        val root = try {
            JsonParser.parse(text.removePrefix("﻿"))
        } catch (e: JsonParseException) {
            throw MalformedVersionException("version.json is not JSON")
        } as? Map<*, *> ?: throw MalformedVersionException("version.json is not an object")

        val code = root.long("versionCode")?.takeIf { it in 1..Int.MAX_VALUE.toLong() }
            ?: throw MalformedVersionException("version.json has no valid versionCode")
        return VersionInfo(
            versionCode = code,
            versionName = name(root["versionName"]) ?: code.toString(),
            commitSha = optional(root, "sha", COMMIT)?.lowercase(),
            apkSha256 = optional(root, "apkSha256", SHA256)?.lowercase(),
            apkSize = apkSize(root),
        )
    }

    private fun name(raw: Any?): String? = (raw as? String)?.trim()
        ?.takeIf { it.isNotEmpty() && it.length <= MAX_NAME && it.none(::unsafeInName) }

    /** Control, whitespace and format characters (bidi overrides would reorder the "Update available" line). */
    private fun unsafeInName(c: Char) = c.isISOControl() || c.isWhitespace() || Character.getType(c) == Character.FORMAT.toInt()

    private fun optional(root: Map<*, *>, key: String, pattern: Regex): String? {
        if (root[key] == null) return null
        val value = root.string(key)?.trim()
        if (value == null || !pattern.matches(value)) throw MalformedVersionException("version.json has a malformed $key")
        return value
    }

    private fun apkSize(root: Map<*, *>): Long? {
        if (root["apkSize"] == null) return null
        return root.long("apkSize")?.takeIf { it > 0 } ?: throw MalformedVersionException("version.json has a malformed apkSize")
    }
}

class MalformedVersionException(message: String) : Exception(message)
