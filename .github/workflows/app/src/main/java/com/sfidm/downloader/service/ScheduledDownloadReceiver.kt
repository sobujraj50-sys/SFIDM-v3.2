package com.sfidm.downloader.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.sfidm.downloader.engine.DownloadManager

class ScheduledDownloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        DownloadManager.init(context.applicationContext)
        val downloadId = intent.getStringExtra("download_id")
        if (!downloadId.isNullOrBlank()) {
            DownloadManager.startDownload(downloadId)
        } else {
            DownloadManager.checkDueSchedules()
        }
    }
}
