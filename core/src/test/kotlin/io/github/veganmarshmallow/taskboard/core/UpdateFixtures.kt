package io.github.veganmarshmallow.taskboard.core

import io.github.veganmarshmallow.taskboard.core.http.HttpRequest
import io.github.veganmarshmallow.taskboard.core.http.StreamingHttpClient
import io.github.veganmarshmallow.taskboard.core.http.StreamingResponse
import io.github.veganmarshmallow.taskboard.core.json.JsonWriter
import io.github.veganmarshmallow.taskboard.core.update.DownloadTarget
import io.github.veganmarshmallow.taskboard.core.update.PackageCheck
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

const val APP_API = "https://api.github.com/repos/VeganMarshmallow/Taskboard-widget"
const val LATEST_URL = "$APP_API/releases/tags/latest"
const val PROBE_URL = "$APP_API/releases?per_page=1"
const val APK_ID = 111L
const val VERSION_ID = 222L
const val STORAGE = "https://objects.githubusercontent.com/github-production-release-asset/1?sig=abc"

fun assetUrl(id: Long) = "$APP_API/releases/assets/$id"

/** A package check that accepts every APK (signature checks have their own tests). */
val TRUSTED = PackageCheck { null }

/** A fake APK: ZIP magic plus [size] - 4 filler bytes. */
fun fakeApk(size: Int = 5000): ByteArray = ByteArray(size) { i ->
    when (i) { 0 -> 0x50; 1 -> 0x4B; 2 -> 0x03; 3 -> 0x04; else -> (i % 251).toByte().toInt() }.toByte()
}

fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xFF) }

fun assetJson(id: Long, name: String, size: Long, state: String = "uploaded"): Map<String, Any?> = mapOf(
    "id" to id, "name" to name, "size" to size, "state" to state,
    "url" to assetUrl(id),
    "browser_download_url" to "https://github.com/VeganMarshmallow/Taskboard-widget/releases/download/latest/$name",
    "content_type" to "application/octet-stream",
)

fun releaseJson(vararg assets: Map<String, Any?>, notes: String? = "Built from abc1234 on main (build 9, versionCode 9)."): String =
    JsonWriter.write(mapOf("tag_name" to "latest", "body" to notes, "assets" to assets.toList(), "prerelease" to false))

fun versionJson(code: Long, apk: ByteArray, name: String = "0.1.$code"): String = JsonWriter.write(
    mapOf(
        "versionCode" to code,
        "versionName" to name,
        "sha" to "776dc5f6623ab408acd13439f7f081a22c508fd4",
        "apkSha256" to sha256(apk),
        "apkSize" to apk.size.toLong(),
    ),
)

fun streamed(status: Int, body: ByteArray = ByteArray(0), vararg headers: Pair<String, String>, onClose: () -> Unit = {}) =
    StreamingResponse(status, headers.associate { it.first to listOf(it.second) }, ByteArrayInputStream(body), onClose)

fun streamed(status: Int, body: String, vararg headers: Pair<String, String>) =
    streamed(status, body.toByteArray(Charsets.UTF_8), *headers)

/** Scripted [StreamingHttpClient]: routes by exact URL, records requests, counts closes. */
class FakeStreamingHttp : StreamingHttpClient {
    val requests = mutableListOf<HttpRequest>()
    var opened = 0
        private set
    var closed = 0
        private set
    private val routes = mutableMapOf<String, (HttpRequest) -> StreamingResponse>()

    fun on(url: String, respond: (HttpRequest) -> StreamingResponse) = apply { routes[url] = respond }

    override fun open(request: HttpRequest): StreamingResponse {
        requests += request
        val response = (routes[request.url] ?: throw IOException("no route for ${request.url}"))(request)
        opened++
        return StreamingResponse(status = response.status, headers = headersOf(response), body = response.body) {
            closed++
            response.close()
        }
    }

    private fun headersOf(r: StreamingResponse): Map<String, List<String>> =
        listOf("location", "content-length", "x-ratelimit-remaining", "x-ratelimit-reset", "retry-after")
            .mapNotNull { name -> r.header(name)?.let { name to listOf(it) } }.toMap()

    /** The standard happy path: a release with an APK and version.json, redirected to storage. */
    fun standard(apk: ByteArray, code: Long = 42, version: String? = versionJson(code, apk)) = apply {
        val assets = buildList {
            add(assetJson(APK_ID, "taskboard-widget.apk", apk.size.toLong()))
            if (version != null) add(assetJson(VERSION_ID, "version.json", version.length.toLong()))
            add(assetJson(5, "1-setup.png", 100))
        }
        on(LATEST_URL) { streamed(200, releaseJson(*assets.toTypedArray())) }
        on(assetUrl(APK_ID)) { streamed(302, ByteArray(0), "Location" to STORAGE) }
        on(STORAGE) { streamed(200, apk) }
        if (version != null) {
            on(assetUrl(VERSION_ID)) { streamed(302, ByteArray(0), "Location" to "$STORAGE-v") }
            on("$STORAGE-v") { streamed(200, version) }
        }
    }
}

/** In-memory [DownloadTarget]. */
class MemoryTarget(
    var available: Long = 1L shl 40,
    private val failOpen: Boolean = false,
    private val failWriteAfter: Long = Long.MAX_VALUE,
    private val writeFailure: String = "write failed",
    private val failCommit: Boolean = false,
) : DownloadTarget {
    var partial: ByteArrayOutputStream? = null
    var committed: ByteArray? = null
    var discards = 0
    var streamClosed = false

    override fun availableBytes() = available

    override fun open(): OutputStream {
        if (failOpen) throw IOException("cannot create file")
        val buffer = ByteArrayOutputStream()
        partial = buffer
        return object : OutputStream() {
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
            override fun write(b: ByteArray, off: Int, len: Int) {
                if (buffer.size() + len > failWriteAfter) throw IOException(writeFailure)
                buffer.write(b, off, len)
            }
            override fun close() { streamClosed = true }
        }
    }

    override fun commit() {
        if (failCommit) throw IOException("rename failed")
        committed = partial!!.toByteArray()
    }

    override fun discard() {
        discards++
        partial = null
    }
}

/** An input stream that delivers [prefix] and then fails, like a dropped connection. */
class DroppingStream(private val prefix: ByteArray) : InputStream() {
    private var pos = 0
    override fun read(): Int = if (pos < prefix.size) prefix[pos++].toInt() and 0xFF else throw IOException("connection reset")
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (pos >= prefix.size) throw IOException("connection reset")
        val n = minOf(len, prefix.size - pos)
        System.arraycopy(prefix, pos, b, off, n)
        pos += n
        return n
    }
}
