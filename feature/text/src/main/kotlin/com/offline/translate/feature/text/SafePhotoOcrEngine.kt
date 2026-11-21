package com.offline.translate.feature.text

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Crash-safe, memory-bounded, multi-script Photo OCR engine:
 * 1. Downsamples large smartphone camera photos (up to 108 MP on Xiaomi Redmi) safely to <= 1600px
 *    with EXIF orientation correction so OutOfMemoryError and MIUI URI failures never happen.
 * 2. Runs Google ML Kit Latin OCR (fast, offline, ideal for English, Turkish, numbers, DN-50/PLC codes).
 * 3. Runs Tesseract4Android (`rus.traineddata` fast model cached in `filesDir/tessdata/`) whenever
 *    Russian Cyrillic OCR is selected or needed, working 100% offline once cached.
 */
@Singleton
class SafePhotoOcrEngine @Inject constructor() {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun recognizeTextFromUri(
        context: Context,
        imageUri: Uri,
        sourceLangHint: String
    ): Result<String> = withContext(Dispatchers.IO) {
        var bitmap: Bitmap? = null
        try {
            bitmap = decodeDownsampledBitmapWithExif(context, imageUri, maxDimension = 1600)
                ?: return@withContext Result.failure(
                    IllegalArgumentException("Не удалось прочитать изображение из выбранного файла.")
                )

            // If user explicitly chose Russian ("ru"), try Tesseract Cyrillic first (with ML Kit fallback)
            if (sourceLangHint.equals("ru", ignoreCase = true)) {
                val tessText = runTesseractCyrillicOcr(context, bitmap)
                if (!tessText.isNullOrBlank()) {
                    return@withContext Result.success(tessText)
                }
            }

            // Primary fast offline OCR for Latin / English / Turkish / Codes via Google ML Kit
            val mlKitText = runMlKitLatinOcr(bitmap)
            if (!mlKitText.isNullOrBlank()) {
                // If auto mode detected Latin gibberish on a Cyrillic photo and Russian tessdata is available, compare
                if (sourceLangHint == "auto" && looksLikeGarbledCyrillic(mlKitText)) {
                    val tessText = runTesseractCyrillicOcr(context, bitmap)
                    if (!tessText.isNullOrBlank()) {
                        return@withContext Result.success(tessText)
                    }
                }
                return@withContext Result.success(mlKitText)
            }

            // Last fallback: try Tesseract Cyrillic/English if ML Kit found nothing
            val tessFallback = runTesseractCyrillicOcr(context, bitmap)
            if (!tessFallback.isNullOrBlank()) {
                return@withContext Result.success(tessFallback)
            }

            Result.failure(IllegalStateException("На фото не обнаружено читаемого текста. Попробуйте сделать снимок чётче и ближе."))
        } catch (e: Throwable) {
            Result.failure(IllegalStateException("Ошибка OCR: ${e.localizedMessage ?: e.javaClass.simpleName}", e))
        } finally {
            runCatching { bitmap?.recycle() }
        }
    }

    private suspend fun runMlKitLatinOcr(bitmap: Bitmap): String? {
        return runCatching {
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            try {
                val inputImage = InputImage.fromBitmap(bitmap, 0)
                val result = recognizer.process(inputImage).await()
                result.text.trim().takeIf { it.isNotEmpty() }
            } finally {
                runCatching { recognizer.close() }
            }
        }.getOrNull()
    }

    private fun runTesseractCyrillicOcr(context: Context, bitmap: Bitmap): String? {
        val dataRoot = File(context.filesDir, "tesseract").apply { mkdirs() }
        val tessdataDir = File(dataRoot, "tessdata").apply { mkdirs() }
        val rusFile = File(tessdataDir, "rus.traineddata")

        if (!rusFile.exists() || rusFile.length() < 100_000L) {
            // Download lightweight fast Russian Cyrillic OCR model (~4 MB) once and cache permanently offline
            val downloaded = downloadFile(
                url = "https://github.com/tesseract-ocr/tessdata_fast/raw/main/rus.traineddata",
                target = rusFile
            )
            if (!downloaded) return null
        }

        val tess = TessBaseAPI()
        return try {
            val ok = tess.init(dataRoot.absolutePath, "rus")
            if (!ok) return null
            tess.setImage(bitmap)
            tess.utF8Text?.trim()?.takeIf { it.isNotEmpty() }
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { tess.recycle() }
        }
    }

    private fun downloadFile(url: String, target: File): Boolean {
        val tmp = File(target.parentFile, "${target.name}.tmp")
        return runCatching {
            val req = Request.Builder().url(url).get().build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return false
                val body = resp.body ?: return false
                tmp.outputStream().buffered().use { out ->
                    body.byteStream().copyTo(out)
                }
            }
            if (target.exists()) target.delete()
            tmp.renameTo(target)
        }.onFailure {
            tmp.delete()
        }.getOrDefault(false)
    }

    private fun looksLikeGarbledCyrillic(text: String): Boolean {
        val weirdSymbols = text.count { it in setOf('|', '\\', '/', '_', '~', '^') }
        return text.length > 8 && weirdSymbols.toFloat() / text.length > 0.18f
    }

    private fun decodeDownsampledBitmapWithExif(
        context: Context,
        uri: Uri,
        maxDimension: Int
    ): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, bounds)
        } ?: return null

        val rawW = bounds.outWidth
        val rawH = bounds.outHeight
        if (rawW <= 0 || rawH <= 0) return null

        var sampleSize = 1
        while ((rawW / sampleSize) > maxDimension || (rawH / sampleSize) > maxDimension) {
            sampleSize *= 2
        }

        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }

        val rawBitmap = context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, decodeOpts)
        } ?: return null

        val rotationDegrees = runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val exif = ExifInterface(stream)
                when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
        }.getOrDefault(0f)

        if (rotationDegrees == 0f) return rawBitmap

        val matrix = Matrix().apply { postRotate(rotationDegrees) }
        val rotated = Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true)
        if (rotated != rawBitmap) {
            rawBitmap.recycle()
        }
        return rotated
    }
}
