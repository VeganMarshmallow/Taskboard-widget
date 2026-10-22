package io.github.veganmarshmallow.taskboard.core.update

import io.github.veganmarshmallow.taskboard.core.http.StreamingResponse
import io.github.veganmarshmallow.taskboard.core.update.UpdateErrorMapper.Step
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Downloads the APK of an [AvailableUpdate] into a [DownloadTarget]: checks free space first,
 * streams with progress, verifies size / SHA-256 / APK signature, and only then commits. Any
 * failure discards the partial file. Never throws.
 */
class ApkDownloader(private val fetcher: AssetFetcher, private val requests: UpdateRequests) {

    fun download(
        update: AvailableUpdate,
        target: DownloadTarget,
        onProgress: (DownloadProgress) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): UpdateResult<Unit> {
        val expectedSize = update.version.apkSize ?: update.apk.size
        if (expectedSize != update.apk.size) return UpdateResult.Err(UpdateError.ReleaseChanged)
        StoragePolicy.check(expectedSize, safeAvailable(target))?.let { return UpdateResult.Err(it) }

        val result = fetcher.fetch(requests.asset(update.apk), Step.ASSET).flatMap { response ->
            response.use { copy(it, target, DownloadVerifier(expectedSize, update.version.apkSha256), onProgress, isCancelled) }
        }
        if (result is UpdateResult.Err) target.discard()
        return result
    }

    private fun copy(
        response: StreamingResponse,
        target: DownloadTarget,
        verifier: DownloadVerifier,
        onProgress: (DownloadProgress) -> Unit,
        isCancelled: () -> Boolean,
    ): UpdateResult<Unit> {
        val input = response.body ?: return UpdateResult.Err(UpdateError.Corrupt("empty response"))
        val output = try {
            target.open()
        } catch (e: IOException) {
            return UpdateResult.Err(writeError(e, target, verifier))
        }
        val copied = output.useSafely { pump(input, it, target, verifier, onProgress, isCancelled) }
        copied?.let { return UpdateResult.Err(it) }
        verifier.problem()?.let { return UpdateResult.Err(UpdateError.Corrupt(it)) }
        return try {
            target.commit()
            UpdateResult.Ok(Unit)
        } catch (e: IOException) {
            UpdateResult.Err(writeError(e, target, verifier))
        }
    }

    /** Returns null when the body was read to the end (or past the expected size), else the error. */
    private fun pump(
        input: InputStream,
        output: OutputStream,
        target: DownloadTarget,
        verifier: DownloadVerifier,
        onProgress: (DownloadProgress) -> Unit,
        isCancelled: () -> Boolean,
    ): UpdateError? {
        val buffer = ByteArray(BUFFER)
        val total = verifier.expectedSize
        while (true) {
            if (isCancelled()) return UpdateError.Unexpected("cancelled")
            val n = try {
                input.read(buffer)
            } catch (e: IOException) {
                return UpdateError.Network
            }
            if (n < 0) return null
            if (n == 0) continue
            verifier.update(buffer, n)
            // Stop reading a body that is already longer than promised; the verifier reports it.
            if (verifier.bytes > total) return null
            try {
                output.write(buffer, 0, n)
            } catch (e: IOException) {
                return writeError(e, target, verifier)
            }
            onProgress(DownloadProgress(verifier.bytes, total))
        }
    }

    private fun writeError(e: IOException, target: DownloadTarget, verifier: DownloadVerifier): UpdateError {
        val available = safeAvailable(target)
        val noSpace = e.message?.contains("ENOSPC") == true || e.message?.contains("No space", ignoreCase = true) == true
        return if (noSpace || (available in 0L until BUFFER.toLong())) {
            UpdateError.LowStorage(StoragePolicy.requiredBytes(verifier.bytes), available)
        } else {
            UpdateError.Unexpected("could not save the download")
        }
    }

    private fun safeAvailable(target: DownloadTarget): Long = try {
        target.availableBytes()
    } catch (e: RuntimeException) {
        -1
    }

    private inline fun OutputStream.useSafely(block: (OutputStream) -> UpdateError?): UpdateError? {
        val error = try {
            block(this)
        } catch (e: RuntimeException) {
            UpdateError.Unexpected("download failed: ${e.javaClass.simpleName}")
        }
        val closeError = try {
            close()
            null
        } catch (e: IOException) {
            UpdateError.Unexpected("could not save the download")
        }
        return error ?: closeError
    }

    private companion object {
        const val BUFFER = 64 * 1024
    }
}
