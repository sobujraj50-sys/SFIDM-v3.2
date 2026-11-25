package com.sfidm.downloader.engine

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.sfidm.downloader.data.PrefsManager
import com.sfidm.downloader.model.DownloadItem
import com.sfidm.downloader.model.DownloadMode
import com.sfidm.downloader.model.DownloadStatus
import com.sfidm.downloader.model.FileCategory
import com.sfidm.downloader.model.MediaQuality
import com.sfidm.downloader.service.DownloadService
import com.sfidm.downloader.service.ScheduledDownloadReceiver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object DownloadManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeJobs = ConcurrentHashMap<String, Job>()

    private val _downloads = MutableStateFlow<List<DownloadItem>>(emptyList())
    val downloads: StateFlow<List<DownloadItem>> = _downloads.asStateFlow()

    private val _speedLimitBytes = MutableStateFlow(0L)
    val speedLimitBytes: StateFlow<Long> = _speedLimitBytes.asStateFlow()

    private val _wifiOnly = MutableStateFlow(false)
    val wifiOnly: StateFlow<Boolean> = _wifiOnly.asStateFlow()

    private var initialized = false
    private lateinit var appContext: Context
    private lateinit var prefs: PrefsManager
    private lateinit var directDownloader: MultiThreadDownloader
    private lateinit var mediaDownloader: MediaVideoDownloader
    private var lastNotifUpdate = 0L

    fun init(context: Context) {
        if (initialized) return
        appContext = context.applicationContext
        prefs = PrefsManager(appContext)
        directDownloader = MultiThreadDownloader(appContext)
        mediaDownloader = MediaVideoDownloader(appContext, directDownloader)
        _downloads.value = prefs.loadDownloads()
        _speedLimitBytes.value = prefs.getSpeedLimitBytes()
        _wifiOnly.value = prefs.isWifiOnly()
        initialized = true

        registerWifiMonitor()
        startSchedulerTicker()

        scope.launch {
            runCatching { mediaDownloader.ensureInitialized() }
        }
    }

    fun setSpeedLimit(bytesPerSec: Long) {
        _speedLimitBytes.value = bytesPerSec
        prefs.saveSpeedLimitBytes(bytesPerSec)
    }

    fun setWifiOnly(enabled: Boolean) {
        _wifiOnly.value = enabled
        prefs.saveWifiOnly(enabled)
        if (enabled && !isOnWifi()) {
            val activeIds = _downloads.value
                .filter { it.status == DownloadStatus.DOWNLOADING }
                .map { it.id }
            activeIds.forEach { id ->
                pauseDownloadInternal(id, waitingWifi = true)
            }
        } else if (!enabled || isOnWifi()) {
            val waitingIds = _downloads.value
                .filter { it.status == DownloadStatus.PAUSED && it.statusDetail == "WIFI_WAIT" }
                .map { it.id }
            waitingIds.forEach { startDownload(it) }
        }
    }

    fun isOnWifi(): Boolean {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    private fun registerWifiMonitor() {
        try {
            val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            val req = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            cm.registerNetworkCallback(req, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (_wifiOnly.value && isOnWifi()) {
                        val waitingIds = _downloads.value
                            .filter { it.status == DownloadStatus.PAUSED && it.statusDetail == "WIFI_WAIT" }
                            .map { it.id }
                        waitingIds.forEach { startDownload(it) }
                    }
                }

                override fun onLost(network: Network) {
                    if (_wifiOnly.value && !isOnWifi()) {
                        val activeIds = _downloads.value
                            .filter { it.status == DownloadStatus.DOWNLOADING }
                            .map { it.id }
                        activeIds.forEach { pauseDownloadInternal(it, waitingWifi = true) }
                    }
                }
            })
        } catch (_: Exception) {
            // Ignore network callback registration issues
        }
    }

    private fun startSchedulerTicker() {
        scope.launch {
            while (isActive) {
                checkDueSchedules()
                delay(1500L)
            }
        }
    }

    fun checkDueSchedules() {
        val now = System.currentTimeMillis()
        val dueItems = _downloads.value.filter {
            it.status == DownloadStatus.SCHEDULED && it.scheduledTimeMillis in 1..now
        }
        dueItems.forEach { item ->
            startDownload(item.id)
        }
    }

    suspend fun updateVideoEngine(): MediaVideoDownloader.EngineUpdateResult {
        return mediaDownloader.updateEngine()
    }

    fun addDownload(
        url: String,
        customName: String,
        mode: DownloadMode,
        quality: MediaQuality,
        threads: Int,
        isVault: Boolean = false,
        scheduledTimeMillis: Long = 0L
    ) {
        val cleanUrl = url.trim()
        val displayTitle = customName.ifBlank {
            if (mode == DownloadMode.MEDIA_VIDEO) {
                guessHostLabel(cleanUrl)
            } else {
                cleanUrl.substringBefore("?").substringAfterLast("/").ifBlank { "SFIDM_File" }
            }
        }
        val detectedCategory = FileCategory.detect(displayTitle, mode, quality)
        val isScheduled = scheduledTimeMillis > System.currentTimeMillis()

        val item = DownloadItem(
            id = UUID.randomUUID().toString(),
            fileName = displayTitle,
            url = cleanUrl,
            mode = mode,
            category = detectedCategory,
            quality = quality,
            threads = threads.coerceIn(1, 16),
            status = if (isScheduled) DownloadStatus.SCHEDULED else DownloadStatus.QUEUED,
            scheduledTimeMillis = if (isScheduled) scheduledTimeMillis else 0L,
            isVault = isVault
        )
        _downloads.update { listOf(item) + it }
        prefs.saveDownloads(_downloads.value)

        if (isScheduled) {
            registerSystemAlarm(item.id, scheduledTimeMillis)
        } else {
            startDownload(item.id)
        }
    }

    fun scheduleExistingDownload(id: String, triggerAtMillis: Long) {
        activeJobs[id]?.cancel()
        activeJobs.remove(id)
        updateItem(id) {
            it.copy(
                status = DownloadStatus.SCHEDULED,
                scheduledTimeMillis = triggerAtMillis,
                speedBytesPerSec = 0L,
                errorMessage = ""
            )
        }
        prefs.saveDownloads(_downloads.value)
        registerSystemAlarm(id, triggerAtMillis)
        updateForegroundNotification(force = true)
    }

    private fun registerSystemAlarm(downloadId: String, triggerAtMillis: Long) {
        try {
            val am = appContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val intent = Intent(appContext, ScheduledDownloadReceiver::class.java).apply {
                putExtra("download_id", downloadId)
            }
            val pi = PendingIntent.getBroadcast(
                appContext,
                downloadId.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
        } catch (_: Exception) {
            // In-app ticker coroutine also checks due schedules every 1.5s
        }
    }

    fun startDownload(id: String) {
        val item = _downloads.value.find { it.id == id } ?: return

        if (_wifiOnly.value && !isOnWifi()) {
            updateItem(id) {
                it.copy(
                    status = DownloadStatus.PAUSED,
                    statusDetail = "WIFI_WAIT",
                    speedBytesPerSec = 0L,
                    scheduledTimeMillis = 0L
                )
            }
            prefs.saveDownloads(_downloads.value)
            return
        }

        activeJobs[id]?.cancel()

        updateItem(id) {
            it.copy(
                status = DownloadStatus.DOWNLOADING,
                errorMessage = "",
                speedBytesPerSec = 0L,
                scheduledTimeMillis = 0L,
                statusDetail = if (it.mode == DownloadMode.MEDIA_VIDEO) "Analyzing..." else ""
            )
        }
        updateForegroundNotification(force = true)

        val job = scope.launch {
            try {
                if (item.mode == DownloadMode.MEDIA_VIDEO) {
                    val isAutoLabel = item.fileName.startsWith("Media (") || item.fileName.startsWith("Video (")
                    val outcome = mediaDownloader.downloadMedia(
                        processId = id,
                        url = item.url,
                        customName = if (isAutoLabel) "" else item.fileName,
                        quality = item.quality,
                        isVault = item.isVault,
                        speedLimitProvider = { _speedLimitBytes.value }
                    ) { percent, downloaded, total, speed, detail, resolvedTitle ->
                        updateItem(id) { current ->
                            val newName = resolvedTitle ?: current.fileName
                            current.copy(
                                fileName = newName,
                                category = FileCategory.detect(newName, DownloadMode.MEDIA_VIDEO, current.quality),
                                downloadedBytes = if (downloaded > 0L) downloaded else current.downloadedBytes,
                                totalBytes = if (total > 0L) total else current.totalBytes,
                                speedBytesPerSec = speed,
                                progressPercent = percent,
                                statusDetail = detail,
                                status = DownloadStatus.DOWNLOADING
                            )
                        }
                        updateForegroundNotification(force = false)
                    }

                    updateItem(id) {
                        it.copy(
                            fileName = outcome.resolvedName,
                            category = outcome.category,
                            downloadedBytes = outcome.fileSize,
                            totalBytes = outcome.fileSize,
                            progressPercent = 100f,
                            speedBytesPerSec = 0L,
                            statusDetail = "",
                            status = DownloadStatus.COMPLETED,
                            filePath = outcome.filePath,
                            errorMessage = ""
                        )
                    }
                } else {
                    val outcome = directDownloader.download(
                        url = item.url,
                        requestedFileName = item.fileName,
                        threads = item.threads,
                        isVault = item.isVault,
                        speedLimitProvider = { _speedLimitBytes.value }
                    ) { downloaded, total, speed, resolvedName, detectedCat ->
                        val pct = if (total > 0L) (downloaded.toFloat() / total.toFloat()) * 100f else 0f
                        updateItem(id) {
                            it.copy(
                                fileName = resolvedName,
                                category = detectedCat,
                                downloadedBytes = downloaded,
                                totalBytes = total,
                                progressPercent = pct,
                                speedBytesPerSec = speed,
                                statusDetail = "",
                                status = DownloadStatus.DOWNLOADING
                            )
                        }
                        updateForegroundNotification(force = false)
                    }

                    updateItem(id) {
                        val finalTotal = if (it.totalBytes > 0L) it.totalBytes else it.downloadedBytes
                        it.copy(
                            fileName = outcome.resolvedName,
                            category = outcome.category,
                            downloadedBytes = finalTotal,
                            totalBytes = finalTotal,
                            progressPercent = 100f,
                            speedBytesPerSec = 0L,
                            statusDetail = "",
                            status = DownloadStatus.COMPLETED,
                            filePath = outcome.filePath,
                            errorMessage = ""
                        )
                    }
                }
            } catch (_: CancellationException) {
                updateItem(id) {
                    if (it.status == DownloadStatus.DOWNLOADING) {
                        it.copy(status = DownloadStatus.PAUSED, speedBytesPerSec = 0L)
                    } else {
                        it
                    }
                }
            } catch (e: Exception) {
                val formattedErr = formatDiagnosticError(e.message ?: "Download error")
                updateItem(id) {
                    it.copy(
                        status = DownloadStatus.FAILED,
                        speedBytesPerSec = 0L,
                        statusDetail = "",
                        errorMessage = formattedErr
                    )
                }
            } finally {
                activeJobs.remove(id)
                prefs.saveDownloads(_downloads.value)
                updateForegroundNotification(force = true)
            }
        }
        activeJobs[id] = job
    }

    private fun formatDiagnosticError(raw: String): String {
        val lines = raw.lines().map { it.trim() }.filter { it.isNotBlank() }
        if (lines.isEmpty()) return "Download error"
        val errorLine = lines.lastOrNull { it.startsWith("ERROR:", ignoreCase = true) }
        return if (errorLine != null && lines.size > 1) {
            val rest = lines.filterNot { it == errorLine }.joinToString("\n")
            "$errorLine\n\n$rest".take(1200)
        } else {
            lines.joinToString("\n").take(1200)
        }
    }

    fun pauseDownload(id: String) {
        pauseDownloadInternal(id, waitingWifi = false)
    }

    private fun pauseDownloadInternal(id: String, waitingWifi: Boolean) {
        val item = _downloads.value.find { it.id == id }
        if (item?.mode == DownloadMode.MEDIA_VIDEO) {
            mediaDownloader.cancelProcess(id)
        }
        activeJobs[id]?.cancel()
        activeJobs.remove(id)
        updateItem(id) {
            it.copy(
                status = DownloadStatus.PAUSED,
                speedBytesPerSec = 0L,
                statusDetail = if (waitingWifi) "WIFI_WAIT" else ""
            )
        }
        prefs.saveDownloads(_downloads.value)
        updateForegroundNotification(force = true)
    }

    fun moveItemToVault(id: String): Boolean {
        val item = _downloads.value.find { it.id == id } ?: return false
        if (item.status != DownloadStatus.COMPLETED || item.isVault) return false
        val newPath = FileExportHelper.moveFileToVault(appContext, item.filePath, item.fileName) ?: return false
        updateItem(id) { it.copy(isVault = true, filePath = newPath) }
        prefs.saveDownloads(_downloads.value)
        return true
    }

    fun exportItemFromVault(id: String): Boolean {
        val item = _downloads.value.find { it.id == id } ?: return false
        if (item.status != DownloadStatus.COMPLETED || !item.isVault) return false
        val newPath = FileExportHelper.exportFileFromVault(appContext, item.filePath, item.category, item.fileName) ?: return false
        updateItem(id) { it.copy(isVault = false, filePath = newPath) }
        prefs.saveDownloads(_downloads.value)
        return true
    }

    fun deleteDownload(id: String) {
        val item = _downloads.value.find { it.id == id }
        if (item?.mode == DownloadMode.MEDIA_VIDEO) {
            mediaDownloader.cancelProcess(id)
        }
        activeJobs[id]?.cancel()
        activeJobs.remove(id)
        if (item != null && item.filePath.isNotBlank()) {
            runCatching { File(item.filePath).delete() }
        }
        _downloads.update { list -> list.filterNot { it.id == id } }
        prefs.saveDownloads(_downloads.value)
        updateForegroundNotification(force = true)
    }

    private fun updateItem(id: String, transform: (DownloadItem) -> DownloadItem) {
        _downloads.update { list ->
            list.map { if (it.id == id) transform(it) else it }
        }
    }

    private fun updateForegroundNotification(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastNotifUpdate < 700L) return
        lastNotifUpdate = now

        val active = _downloads.value.filter { it.status == DownloadStatus.DOWNLOADING }
        if (active.isEmpty()) {
            DownloadService.stop(appContext)
            return
        }
        val totalSpeed = active.sumOf { it.speedBytesPerSec }
        val avgProgress = (active.map { it.progress }.average() * 100).toInt()
        val first = active.first()
        val title = when {
            active.size > 1 -> "SFIDM (${active.size} active)"
            first.isVault -> "SFIDM Vault • ${first.progress.times(100).toInt()}%"
            else -> first.fileName
        }
        val text = if (totalSpeed > 0L) "${formatSpeed(totalSpeed)} • $avgProgress%" else "$avgProgress%"
        DownloadService.startOrUpdate(appContext, title, text, avgProgress)
    }

    private fun guessHostLabel(url: String): String {
        val host = url.substringAfter("://").substringBefore("/").removePrefix("www.").removePrefix("m.")
        return "Media (${host.ifBlank { "Link" }})"
    }

    fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        val kb = 1024.0
        val mb = kb * 1024.0
        val gb = mb * 1024.0
        return when {
            bytes >= gb -> String.format("%.2f GB", bytes / gb)
            bytes >= mb -> String.format("%.1f MB", bytes / mb)
            bytes >= kb -> String.format("%.0f KB", bytes / kb)
            else -> "$bytes B"
        }
    }

    fun formatSpeed(bytesPerSec: Long): String {
        if (bytesPerSec <= 0L) return "0 KB/s"
        val kb = 1024.0
        val mb = kb * 1024.0
        return when {
            bytesPerSec >= mb -> String.format("%.2f MB/s", bytesPerSec / mb)
            else -> String.format("%.0f KB/s", bytesPerSec / kb)
        }
    }
}
