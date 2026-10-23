package io.github.veganmarshmallow.taskboard.core.update

/** Picks the APK and its `version.json` out of a release's assets. */
object ReleaseAssets {
    const val APK_NAME = "taskboard-widget.apk"
    const val VERSION_NAME = "version.json"

    /** Larger than any plausible build of this app; refuses to download something unexpected. */
    const val MAX_APK_BYTES = 200L * 1024 * 1024

    data class Selection(val apk: ReleaseAsset?, val versionJson: ReleaseAsset?)

    fun select(release: Release): Selection = Selection(
        apk = find(release, APK_NAME)?.takeIf { it.size in 1..MAX_APK_BYTES },
        versionJson = find(release, VERSION_NAME)?.takeIf { it.size in 1..64 * 1024 },
    )

    /** Exact name first; then a case-insensitive match. Assets still uploading are ignored. */
    private fun find(release: Release, name: String): ReleaseAsset? {
        val ready = release.assets.filter { it.uploaded }
        return ready.firstOrNull { it.name == name } ?: ready.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }
}
