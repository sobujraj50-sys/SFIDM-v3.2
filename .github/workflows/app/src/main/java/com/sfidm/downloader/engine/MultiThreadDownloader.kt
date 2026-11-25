package com.sfidm.downloader.engine

import android.content.Context
import com.sfidm.downloader.model.DownloadMode
import com.sfidm.downloader.model.FileCategory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class MultiThreadDownloader(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    data class ProbeResult(
        val contentLength: Long,
        val supportsRange: Boolean,
        val detectedFileName: String?
    )

    data class DownloadOutcome(
        val filePath: String,
        val resolvedName: String,
        val category: FileCategory,
        val fileSize: Long
    )

    suspend fun download(
        url: String,
        requestedFileName: String,
        threads: Int,
        isVault: Boolean,
        speedLimitProvider: () -> Long,
        onProgress: (downloadedBytes: Long, totalBytes: Long, speedBytesPerSec: Long, resolvedName: String, category: FileCategory) -> Unit
    ): DownloadOutcome = withContext(Dispatchers.IO) {
        val probe = probeUrl(url)
        val safeName = FileExportHelper.sanitizeFileName(
            requestedFileName.ifBlank { probe.detectedFileName ?: guessFileNameFromUrl(url) }
        )
        val category = FileCategory.detect(safeName, DownloadMode.DIRECT_FILE)

        val targetDir = if (isVault) {
            FileExportHelper.getVaultDir(context)
        } else {
            FileExportHelper.getCategoryWorkingDir(context, category)
        }
        val targetFile = File(targetDir, safeName)

        val totalBytes = probe.contentLength
        val effectiveThreads = if (probe.supportsRange && totalBytes > 512 * 1024) {
            threads.coerceIn(1, 16)
        } else {
            1
        }

        if (effectiveThreads > 1) {
            downloadMultiThread(url, targetFile, totalBytes, effectiveThreads, safeName, category, speedLimitProvider, onProgress)
        } else {
            downloadSingleThread(url, targetFile, totalBytes, safeName, category, speedLimitProvider, onProgress)
        }

        if (!isVault) {
            FileExportHelper.exportToPublicCategoryFolder(context, targetFile, category, safeName)
        }

        DownloadOutcome(
            filePath = targetFile.absolutePath,
            resolvedName = safeName,
            category = category,
            fileSize = targetFile.length()
        )
    }

    private fun probeUrl(url: String): ProbeResult {
        try {
            val headReq = Request.Builder()
                .url(url)
                .head()
                .header("User-Agent", "Mozilla/5.0 (Android 14; Mobile; SFIDM/3.2)")
                .build()
            client.newCall(headReq).execute().use { resp ->
                if (resp.isSuccessful) {
                    val length = resp.header("Content-Length")?.toLongOrNull() ?: -1L
                    val acceptRanges = resp.header("Accept-Ranges")?.equals("bytes", ignoreCase = true) == true
                    val name = parseFileNameFromDisposition(resp.header("Content-Disposition"))
                    if (length > 0L) {
                        return ProbeResult(length, acceptRanges, name)
                    }
                }
            }
        } catch (_: Exception) {
            // Fall through
        }

        return try {
            val rangeReq = Request.Builder()
                .url(url)
                .header("Range", "bytes=0-0")
                .header("User-Agent", "Mozilla/5.0 (Android 14; Mobile; SFIDM/3.2)")
                .build()
            client.newCall(rangeReq).execute().use { resp ->
                val contentRange = resp.header("Content-Range")
                val total = contentRange?.substringAfter("/")?.toLongOrNull()
                    ?: resp.header("Content-Length")?.toLongOrNull()
                    ?: -1L
                val supportsRange = resp.code == 206
                val name = parseFileNameFromDisposition(resp.header("Content-Disposition"))
                ProbeResult(total, supportsRange, name)
            }
        } catch (_: Exception) {
            ProbeResult(-1L, false, null)
        }
    }

    private suspend fun downloadMultiThread(
        url: String,
        targetFile: File,
        totalBytes: Long,
        threadCount: Int,
        fileName: String,
        category: FileCategory,
        speedLimitProvider: () -> Long,
        onProgress: (Long, Long, Long, String, FileCategory) -> Unit
    ) = coroutineScope {
        RandomAccessFile(targetFile, "rw").use { raf ->
            raf.setLength(totalBytes)
        }

        val downloadedAtomic = AtomicLong(0L)
        val chunkSize = totalBytes / threadCount
        val windowStart = AtomicLong(System.currentTimeMillis())
        val windowBytes = AtomicLong(0L)

        val monitorJob = launch(Dispatchers.IO) {
            var lastBytes = 0L
            var lastTime = System.currentTimeMillis()
            while (isActive) {
                delay(350)
                val now = System.currentTimeMillis()
                val current = downloadedAtomic.get()
                val elapsedMs = (now - lastTime).coerceAtLeast(1L)
                val speed = ((current - lastBytes) * 1000L) / elapsedMs
                lastBytes = current
                lastTime = now
                onProgress(current, totalBytes, speed.coerceAtLeast(0L), fileName, category)
            }
        }

        try {
            val jobs = (0 until threadCount).map { partIndex ->
                val start = partIndex * chunkSize
                val end = if (partIndex == threadCount - 1) totalBytes - 1 else (start + chunkSize - 1)

                async(Dispatchers.IO) {
                    val req = Request.Builder()
                        .url(url)
                        .header("Range", "bytes=$start-$end")
                        .header("User-Agent", "Mozilla/5.0 (Android 14; Mobile; SFIDM/3.2)")
                        .build()

                    client.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful && resp.code != 206) {
                            throw IllegalStateException("HTTP ${resp.code}")
                        }
                        val body = resp.body ?: throw IllegalStateException("Empty response body")
                        val buffer = ByteArray(16 * 1024)
                        body.byteStream().use { input ->
                            RandomAccessFile(targetFile, "rw").use { raf ->
                                raf.seek(start)
                                while (isActive) {
                                    val read = input.read(buffer)
                                    if (read == -1) break
                                    raf.write(buffer, 0, read)
                                    downloadedAtomic.addAndGet(read.toLong())
                                    throttleIfNeeded(read.toLong(), speedLimitProvider(), windowStart, windowBytes)
                                }
                            }
                        }
                    }
                }
            }
            jobs.awaitAll()
            onProgress(totalBytes, totalBytes, 0L, fileName, category)
        } catch (e: CancellationException) {
            throw e
        } finally {
            monitorJob.cancel()
        }
    }

    private suspend fun downloadSingleThread(
        url: String,
        targetFile: File,
        knownTotal: Long,
        fileName: String,
        category: FileCategory,
        speedLimitProvider: () -> Long,
        onProgress: (Long, Long, Long, String, FileCategory) -> Unit
    ) = coroutineScope {
        val existingBytes = if (targetFile.exists()) targetFile.length() else 0L
        val reqBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Android 14; Mobile; SFIDM/3.2)")

        if (existingBytes > 0L && (knownTotal <= 0L || existingBytes < knownTotal)) {
            reqBuilder.header("Range", "bytes=$existingBytes-")
        }

        val windowStart = AtomicLong(System.currentTimeMillis())
        val windowBytes = AtomicLong(0L)

        client.newCall(reqBuilder.build()).execute().use { resp ->
            if (!resp.isSuccessful && resp.code != 206) {
                throw IllegalStateException("HTTP ${resp.code}")
            }
            val isResuming = resp.code == 206 && existingBytes > 0L
            val body = resp.body ?: throw IllegalStateException("Empty response body")
            val contentLen = body.contentLength()
            val totalBytes = when {
                isResuming && contentLen > 0L -> existingBytes + contentLen
                contentLen > 0L -> contentLen
                knownTotal > 0L -> knownTotal
                else -> 0L
            }

            var downloaded = if (isResuming) existingBytes else 0L
            var lastBytes = downloaded
            var lastTime = System.currentTimeMillis()
            val buffer = ByteArray(16 * 1024)

            body.byteStream().use { input ->
                FileOutputStream(targetFile, isResuming).use { output ->
                    while (isActive) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        throttleIfNeeded(read.toLong(), speedLimitProvider(), windowStart, windowBytes)

                        val now = System.currentTimeMillis()
                        if (now - lastTime >= 350L) {
                            val speed = ((downloaded - lastBytes) * 1000L) / (now - lastTime).coerceAtLeast(1L)
                            lastBytes = downloaded
                            lastTime = now
                            onProgress(downloaded, totalBytes, speed.coerceAtLeast(0L), fileName, category)
                        }
                    }
                }
            }
            val finalTotal = if (totalBytes > 0L) totalBytes else downloaded
            onProgress(downloaded, finalTotal, 0L, fileName, category)
        }
    }

    private suspend fun throttleIfNeeded(
        bytesRead: Long,
        limitBytesPerSec: Long,
        windowStart: AtomicLong,
        windowBytes: AtomicLong
    ) {
        if (limitBytesPerSec <= 0L) return
        val totalInWindow = windowBytes.addAndGet(bytesRead)
        val now = System.currentTimeMillis()
        val elapsed = (now - windowStart.get()).coerceAtLeast(1L)
        if (elapsed >= 1000L) {
            windowStart.set(now)
            windowBytes.set(0L)
            return
        }
        val expectedTimeMs = (totalInWindow * 1000L) / limitBytesPerSec
        if (expectedTimeMs > elapsed) {
            delay((expectedTimeMs - elapsed).coerceAtMost(900L))
        }
    }

    private fun parseFileNameFromDisposition(disposition: String?): String? {
        if (disposition.isNullOrBlank()) return null
        val part = disposition.substringAfter("filename*=", "")
            .ifBlank { disposition.substringAfter("filename=", "") }
        if (part.isBlank()) return null
        return part.substringAfter("UTF-8''")
            .substringBefore(";")
            .trim()
            .trim('"')
            .ifBlank { null }
    }

    private fun guessFileNameFromUrl(url: String): String {
        val cleaned = url.substringBefore("?").substringBefore("#")
        val lastSegment = cleaned.substringAfterLast("/", "")
        return if (lastSegment.isNotBlank() && lastSegment.contains(".")) {
            lastSegment
        } else {
            "SFIDM_${System.currentTimeMillis()}.bin"
        }
    }
}
