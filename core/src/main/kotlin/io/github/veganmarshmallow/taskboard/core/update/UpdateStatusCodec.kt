package io.github.veganmarshmallow.taskboard.core.update

import io.github.veganmarshmallow.taskboard.core.github.GitHubRequests
import io.github.veganmarshmallow.taskboard.core.json.JsonParser
import io.github.veganmarshmallow.taskboard.core.json.JsonWriter
import io.github.veganmarshmallow.taskboard.core.json.long
import io.github.veganmarshmallow.taskboard.core.json.string
import java.time.Instant

/** Stores [UpdateStatus] as JSON. Decoding never throws: anything unreadable is [UpdateStatus.NEVER]. */
object UpdateStatusCodec {
    private const val VERSION = 1L

    fun encode(status: UpdateStatus): String = JsonWriter.write(
        mapOf(
            "v" to VERSION,
            "checkedAt" to status.checkedAt?.toEpochMilli(),
            "available" to status.available?.let(::updateToMap),
            "error" to status.error?.let(::errorToMap),
        ),
    )

    fun decode(text: String?): UpdateStatus {
        if (text.isNullOrBlank()) return UpdateStatus.NEVER
        return try {
            val root = JsonParser.parse(text) as Map<*, *>
            if (root.long("v") != VERSION) return UpdateStatus.NEVER
            UpdateStatus(
                checkedAt = root.long("checkedAt")?.let(Instant::ofEpochMilli),
                available = (root["available"] as? Map<*, *>)?.let(::updateFromMap),
                error = (root["error"] as? Map<*, *>)?.let(::errorFromMap),
            )
        } catch (e: Exception) {
            UpdateStatus.NEVER
        }
    }

    private fun updateToMap(u: AvailableUpdate) = mapOf(
        "code" to u.version.versionCode,
        "name" to u.version.versionName,
        "sha" to u.version.commitSha,
        "apkSha256" to u.version.apkSha256,
        "apkSize" to u.version.apkSize,
        "assetId" to u.apk.id,
        "assetName" to u.apk.name,
        "assetSize" to u.apk.size,
        "assetUrl" to u.apk.apiUrl,
    )

    private fun updateFromMap(m: Map<*, *>): AvailableUpdate {
        val url = m.string("assetUrl")!!
        require(GitHubRequests.isApiUrl(url))
        return AvailableUpdate(
            VersionInfo(m.long("code")!!, m.string("name")!!, m.string("sha"), m.string("apkSha256"), m.long("apkSize")),
            ReleaseAsset(m.long("assetId")!!, m.string("assetName")!!, m.long("assetSize")!!, url),
        )
    }

    private fun errorToMap(e: UpdateError): Map<String, Any?> = when (e) {
        UpdateError.NoToken -> mapOf("k" to "NoToken")
        UpdateError.Network -> mapOf("k" to "Network")
        UpdateError.NoRepoAccess -> mapOf("k" to "NoRepoAccess")
        UpdateError.BadToken -> mapOf("k" to "BadToken")
        UpdateError.NoRelease -> mapOf("k" to "NoRelease")
        UpdateError.ReleaseChanged -> mapOf("k" to "ReleaseChanged")
        UpdateError.UpToDate -> mapOf("k" to "UpToDate")
        is UpdateError.RateLimited -> mapOf("k" to "RateLimited", "retryAt" to e.retryAt?.toEpochMilli())
        is UpdateError.Server -> mapOf("k" to "Server", "status" to e.status.toLong())
        is UpdateError.Malformed -> mapOf("k" to "Malformed", "d" to e.detail)
        is UpdateError.Corrupt -> mapOf("k" to "Corrupt", "d" to e.detail)
        is UpdateError.Untrusted -> mapOf("k" to "Untrusted", "d" to e.detail)
        UpdateError.InstallNotAllowed -> mapOf("k" to "InstallNotAllowed")
        is UpdateError.LowStorage -> mapOf("k" to "LowStorage", "need" to e.neededBytes, "free" to e.availableBytes)
        is UpdateError.Unexpected -> mapOf("k" to "Unexpected", "d" to e.detail)
    }

    private fun errorFromMap(m: Map<*, *>): UpdateError? = when (m.string("k")) {
        "NoToken" -> UpdateError.NoToken
        "Network" -> UpdateError.Network
        "NoRepoAccess" -> UpdateError.NoRepoAccess
        "BadToken" -> UpdateError.BadToken
        "NoRelease" -> UpdateError.NoRelease
        "ReleaseChanged" -> UpdateError.ReleaseChanged
        "UpToDate" -> UpdateError.UpToDate
        "RateLimited" -> UpdateError.RateLimited(m.long("retryAt")?.let(Instant::ofEpochMilli))
        "Server" -> UpdateError.Server(m.long("status")!!.toInt())
        "Malformed" -> UpdateError.Malformed(m.string("d").orEmpty())
        "Corrupt" -> UpdateError.Corrupt(m.string("d").orEmpty())
        "Untrusted" -> UpdateError.Untrusted(m.string("d").orEmpty())
        "InstallNotAllowed" -> UpdateError.InstallNotAllowed
        "LowStorage" -> UpdateError.LowStorage(m.long("need")!!, m.long("free")!!)
        "Unexpected" -> UpdateError.Unexpected(m.string("d").orEmpty())
        else -> null
    }
}
