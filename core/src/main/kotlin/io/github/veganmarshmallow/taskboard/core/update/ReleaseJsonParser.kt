package io.github.veganmarshmallow.taskboard.core.update

import io.github.veganmarshmallow.taskboard.core.github.GitHubRequests
import io.github.veganmarshmallow.taskboard.core.json.JsonParseException
import io.github.veganmarshmallow.taskboard.core.json.JsonParser
import io.github.veganmarshmallow.taskboard.core.json.list
import io.github.veganmarshmallow.taskboard.core.json.long
import io.github.veganmarshmallow.taskboard.core.json.string

/** Parses `GET /repos/{owner}/{repo}/releases/tags/{tag}`. Assets with unusable fields are skipped. */
object ReleaseJsonParser {
    fun parse(text: String): Release {
        val root = try {
            JsonParser.parse(text.removePrefix("﻿"))
        } catch (e: JsonParseException) {
            throw MalformedVersionException("release is not JSON")
        } as? Map<*, *> ?: throw MalformedVersionException("release is not an object")
        val assets = root.list("assets") ?: throw MalformedVersionException("release has no assets list")
        return Release(
            tag = root.string("tag_name").orEmpty(),
            notes = root.string("body"),
            assets = assets.mapNotNull { (it as? Map<*, *>)?.let(::asset) },
            htmlUrl = root.string("html_url"),
        )
    }

    private fun asset(m: Map<*, *>): ReleaseAsset? {
        val id = m.long("id")?.takeIf { it > 0 } ?: return null
        val name = m.string("name")?.takeIf { it.isNotEmpty() } ?: return null
        val size = m.long("size")?.takeIf { it >= 0 } ?: return null
        // Only API asset URLs are used, so the token is only ever sent to api.github.com.
        val url = m.string("url")?.takeIf(GitHubRequests::isApiUrl) ?: return null
        return ReleaseAsset(id, name, size, url, uploaded = m.string("state")?.let { it == "uploaded" } ?: true)
    }
}
