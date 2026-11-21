package com.offline.translate.engine.packmanager

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.offline.translate.core.common.CryptoVerifier
import com.offline.translate.core.common.SafeLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream

/**
 * Background CoroutineWorker for downloading large offline language packs over HTTPS
 * with HTTP Range resumption, progress reporting, and SHA-256 integrity verification.
 *
 * Strictly isolated inside `:engine:packmanager` — no user audio or text ever passes through here.
 */
class LanguagePackDownloadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    private val httpClient = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val packId = inputData.getString(KEY_PACK_ID) ?: return@withContext Result.failure()
        val downloadUrl = inputData.getString(KEY_DOWNLOAD_URL) ?: return@withContext Result.failure()
        val expectedSha256 = inputData.getString(KEY_EXPECTED_SHA256) ?: return@withContext Result.failure()

        // Enforce HTTPS only
        if (!downloadUrl.startsWith("https://")) {
            SafeLogger.e("PackDownloadWorker", "Rejected non-HTTPS language pack URL")
            return@withContext Result.failure()
        }

        val downloadsDir = File(applicationContext.filesDir, "pack_downloads").apply { mkdirs() }
        val partialFile = File(downloadsDir, "$packId.tpk.part")
        val existingBytes = if (partialFile.exists()) partialFile.length() else 0L

        val requestBuilder = Request.Builder().url(downloadUrl)
        if (existingBytes > 0L) {
            requestBuilder.header("Range", "bytes=$existingBytes-")
        }

        runCatching {
            httpClient.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful && response.code != 206) {
                    return@withContext Result.retry()
                }
                val body = response.body ?: return@withContext Result.retry()
                val totalLength = (body.contentLength().takeIf { it > 0 } ?: 0L) + existingBytes
                val appendMode = (response.code == 206 && existingBytes > 0L)

                FileOutputStream(partialFile, appendMode).buffered().use { out ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var bytesWritten = if (appendMode) existingBytes else 0L
                    body.byteStream().use { input ->
                        var read = input.read(buffer)
                        while (read >= 0) {
                            out.write(buffer, 0, read)
                            bytesWritten += read
                            if (totalLength > 0L) {
                                val pct = ((bytesWritten * 100L) / totalLength).toInt().coerceIn(0, 100)
                                setProgressAsync(workDataOf(KEY_PROGRESS to pct))
                            }
                            read = input.read(buffer)
                        }
                    }
                }
            }

            if (!CryptoVerifier.verifySha256(partialFile, expectedSha256)) {
                partialFile.delete()
                SafeLogger.e("PackDownloadWorker", "SHA-256 verification failed for $packId")
                return@withContext Result.failure()
            }

            Result.success(workDataOf(KEY_ARCHIVE_PATH to partialFile.absolutePath))
        }.getOrElse {
            Result.retry()
        }
    }

    companion object {
        const val KEY_PACK_ID = "pack_id"
        const val KEY_DOWNLOAD_URL = "download_url"
        const val KEY_EXPECTED_SHA256 = "expected_sha256"
        const val KEY_PROGRESS = "progress_percent"
        const val KEY_ARCHIVE_PATH = "archive_path"
    }
}
