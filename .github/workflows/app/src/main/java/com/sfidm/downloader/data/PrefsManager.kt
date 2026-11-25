package com.sfidm.downloader.data

import android.content.Context
import com.sfidm.downloader.model.DownloadItem
import com.sfidm.downloader.model.DownloadMode
import com.sfidm.downloader.model.DownloadStatus
import com.sfidm.downloader.model.FileCategory
import com.sfidm.downloader.model.MediaQuality
import com.sfidm.downloader.ui.strings.AppLanguage
import com.sfidm.downloader.ui.theme.AppTheme
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

class PrefsManager(context: Context) {
    private val prefs = context.getSharedPreferences("sfidm_prefs_v3", Context.MODE_PRIVATE)

    fun getTheme(): AppTheme = AppTheme.fromId(prefs.getString("theme_id", AppTheme.MIDNIGHT_CYBER.id))

    fun saveTheme(theme: AppTheme) {
        prefs.edit().putString("theme_id", theme.id).apply()
    }

    fun getLanguage(): AppLanguage = AppLanguage.fromCode(prefs.getString("lang_code", AppLanguage.BN.code))

    fun saveLanguage(lang: AppLanguage) {
        prefs.edit().putString("lang_code", lang.code).apply()
    }

    fun getSpeedLimitBytes(): Long = prefs.getLong("speed_limit_bytes", 0L)

    fun saveSpeedLimitBytes(bytes: Long) {
        prefs.edit().putLong("speed_limit_bytes", bytes).apply()
    }

    fun isWifiOnly(): Boolean = prefs.getBoolean("wifi_only", false)

    fun saveWifiOnly(enabled: Boolean) {
        prefs.edit().putBoolean("wifi_only", enabled).apply()
    }

    fun hasVaultPin(): Boolean = !prefs.getString("vault_pin_hash", null).isNullOrBlank()

    fun setVaultPin(pin: String) {
        prefs.edit().putString("vault_pin_hash", sha256(pin)).apply()
    }

    fun verifyVaultPin(pin: String): Boolean {
        val saved = prefs.getString("vault_pin_hash", null) ?: return false
        return saved == sha256(pin)
    }

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun saveDownloads(list: List<DownloadItem>) {
        val array = JSONArray()
        list.forEach { item ->
            val obj = JSONObject().apply {
                put("id", item.id)
                put("fileName", item.fileName)
                put("url", item.url)
                put("mode", item.mode.name)
                put("category", item.category.folderName)
                put("quality", item.quality.id)
                put("totalBytes", item.totalBytes)
                put("downloadedBytes", item.downloadedBytes)
                put("progressPercent", item.progressPercent.toDouble())
                put("threads", item.threads)
                val persistedStatus = when (item.status) {
                    DownloadStatus.DOWNLOADING, DownloadStatus.QUEUED -> DownloadStatus.PAUSED
                    else -> item.status
                }
                put("status", persistedStatus.name)
                put("scheduledTimeMillis", item.scheduledTimeMillis)
                put("isVault", item.isVault)
                put("filePath", item.filePath)
                put("errorMessage", item.errorMessage)
            }
            array.put(obj)
        }
        prefs.edit().putString("downloads_json", array.toString()).apply()
    }

    fun loadDownloads(): List<DownloadItem> {
        val raw = prefs.getString("downloads_json", null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val status = runCatching {
                        DownloadStatus.valueOf(obj.optString("status", DownloadStatus.PAUSED.name))
                    }.getOrDefault(DownloadStatus.PAUSED)
                    val mode = runCatching {
                        DownloadMode.valueOf(obj.optString("mode", DownloadMode.DIRECT_FILE.name))
                    }.getOrDefault(DownloadMode.DIRECT_FILE)
                    val fileName = obj.getString("fileName")
                    val quality = MediaQuality.fromId(obj.optString("quality", "best"))
                    val catStr = obj.optString("category", "")
                    val category = if (catStr.isNotBlank()) {
                        FileCategory.fromFolder(catStr)
                    } else {
                        FileCategory.detect(fileName, mode, quality)
                    }
                    add(
                        DownloadItem(
                            id = obj.getString("id"),
                            fileName = fileName,
                            url = obj.getString("url"),
                            mode = mode,
                            category = category,
                            quality = quality,
                            totalBytes = obj.optLong("totalBytes", 0L),
                            downloadedBytes = obj.optLong("downloadedBytes", 0L),
                            progressPercent = obj.optDouble("progressPercent", 0.0).toFloat(),
                            speedBytesPerSec = 0L,
                            threads = obj.optInt("threads", 8),
                            status = status,
                            scheduledTimeMillis = obj.optLong("scheduledTimeMillis", 0L),
                            isVault = obj.optBoolean("isVault", false),
                            filePath = obj.optString("filePath", ""),
                            errorMessage = obj.optString("errorMessage", "")
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
