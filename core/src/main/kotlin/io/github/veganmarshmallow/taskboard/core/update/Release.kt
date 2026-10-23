package io.github.veganmarshmallow.taskboard.core.update

/** A file attached to a release. [apiUrl] is `https://api.github.com/repos/.../releases/assets/<id>`. */
data class ReleaseAsset(val id: Long, val name: String, val size: Long, val apiUrl: String, val uploaded: Boolean = true)

/** The parts of a GitHub release the updater needs. */
data class Release(val tag: String, val notes: String?, val assets: List<ReleaseAsset>)
