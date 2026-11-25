package com.sfidm.downloader.model

enum class DownloadStatus {
    QUEUED,
    SCHEDULED,
    DOWNLOADING,
    PAUSED,
    COMPLETED,
    FAILED
}

enum class DownloadMode {
    MEDIA_VIDEO,   // YouTube, Facebook, TikTok, Instagram, Pinterest, HLS/M3U8
    DIRECT_FILE    // Direct HTTP multi-thread downloader (4x/8x/16x)
}

enum class FileCategory(
    val folderName: String,
    val labelEn: String,
    val labelBn: String
) {
    VIDEOS("Videos", "Videos", "ভিডিও"),
    AUDIO("Audio", "Audio", "অডিও"),
    IMAGES("Images", "Images", "ছবি"),
    DOCUMENTS("Documents", "Docs", "ডকুমেন্ট"),
    ARCHIVES("Archives", "Archives", "জিপ/আর্কাইভ"),
    APPS("Apps", "Apps", "অ্যাপস"),
    OTHERS("Others", "Others", "অন্যান্য");

    companion object {
        fun detect(
            fileName: String,
            mode: DownloadMode = DownloadMode.DIRECT_FILE,
            quality: MediaQuality = MediaQuality.BEST
        ): FileCategory {
            val lower = fileName.lowercase()
            when {
                lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") ||
                    lower.endsWith(".webp") || lower.endsWith(".gif") || lower.endsWith(".bmp") ||
                    lower.endsWith(".svg") -> return IMAGES

                lower.endsWith(".mp3") || lower.endsWith(".m4a") || lower.endsWith(".wav") ||
                    lower.endsWith(".aac") || lower.endsWith(".ogg") || lower.endsWith(".flac") ||
                    lower.endsWith(".opus") -> return AUDIO

                lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".webm") ||
                    lower.endsWith(".avi") || lower.endsWith(".mov") || lower.endsWith(".flv") ||
                    lower.endsWith(".3gp") -> return VIDEOS

                lower.endsWith(".pdf") || lower.endsWith(".doc") || lower.endsWith(".docx") ||
                    lower.endsWith(".xls") || lower.endsWith(".xlsx") || lower.endsWith(".ppt") ||
                    lower.endsWith(".pptx") || lower.endsWith(".txt") || lower.endsWith(".csv") ||
                    lower.endsWith(".epub") -> return DOCUMENTS

                lower.endsWith(".zip") || lower.endsWith(".rar") || lower.endsWith(".7z") ||
                    lower.endsWith(".tar") || lower.endsWith(".gz") || lower.endsWith(".iso") -> return ARCHIVES

                lower.endsWith(".apk") || lower.endsWith(".xapk") || lower.endsWith(".apks") -> return APPS
            }

            if (mode == DownloadMode.MEDIA_VIDEO) {
                return if (quality == MediaQuality.AUDIO_MP3) AUDIO else VIDEOS
            }
            return OTHERS
        }

        fun fromFolder(folder: String?): FileCategory =
            entries.find { it.folderName.equals(folder, ignoreCase = true) } ?: OTHERS
    }
}

enum class MediaQuality(val id: String, val labelEn: String, val labelBn: String) {
    BEST("best", "Best MP4", "সর্বোচ্চ MP4"),
    HD_1080("1080p", "1080p HD", "1080p HD"),
    HD_720("720p", "720p HD", "720p HD"),
    SD_480("480p", "480p SD", "480p SD"),
    AUDIO_MP3("mp3", "Audio MP3", "অডিও MP3");

    companion object {
        fun fromId(id: String?): MediaQuality = entries.find { it.id == id } ?: BEST
    }
}

enum class SpeedLimitOption(
    val bytesPerSec: Long,
    val labelEn: String,
    val labelBn: String
) {
    UNLIMITED(0L, "Unlimited", "আনলিমিটেড"),
    KB_512(512L * 1024L, "512 KB/s", "৫১২ KB/s"),
    MB_1(1024L * 1024L, "1 MB/s", "১ MB/s"),
    MB_2(2L * 1024L * 1024L, "2 MB/s", "২ MB/s"),
    MB_5(5L * 1024L * 1024L, "5 MB/s", "৫ MB/s");

    companion object {
        fun fromBytes(bytes: Long): SpeedLimitOption =
            entries.find { it.bytesPerSec == bytes } ?: UNLIMITED
    }
}

data class DownloadItem(
    val id: String,
    val fileName: String,
    val url: String,
    val mode: DownloadMode = DownloadMode.DIRECT_FILE,
    val category: FileCategory = FileCategory.OTHERS,
    val quality: MediaQuality = MediaQuality.BEST,
    val totalBytes: Long = 0L,
    val downloadedBytes: Long = 0L,
    val progressPercent: Float = 0f,
    val speedBytesPerSec: Long = 0L,
    val statusDetail: String = "",
    val threads: Int = 8,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val scheduledTimeMillis: Long = 0L,
    val isVault: Boolean = false,
    val filePath: String = "",
    val errorMessage: String = ""
) {
    val progress: Float
        get() = when {
            status == DownloadStatus.COMPLETED -> 1f
            mode == DownloadMode.MEDIA_VIDEO -> (progressPercent / 100f).coerceIn(0f, 1f)
            totalBytes > 0L -> (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
            else -> (progressPercent / 100f).coerceIn(0f, 1f)
        }

    val etaSeconds: Long
        get() = if (speedBytesPerSec > 0L && totalBytes > downloadedBytes) {
            (totalBytes - downloadedBytes) / speedBytesPerSec
        } else {
            -1L
        }
}
