package com.saraai.offline.model

import android.app.DownloadManager
import android.content.Context
import android.database.Cursor
import android.net.Uri
import java.io.File

object ModelDownloader {
    fun start(context: Context, spec: ModelSpec): Long {
        val dir = File(context.filesDir, "models").apply { mkdirs() }
        val dest = File(dir, spec.fileName)
        if (dest.exists() && dest.length() > 1024) {
            return -1L
        }
        val req = DownloadManager.Request(Uri.parse(spec.url))
            .setTitle(spec.name)
            .setDescription("Sara AI model download")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)
            .setDestinationUri(Uri.fromFile(dest))
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        return dm.enqueue(req)
    }

    data class Progress(val status: Int, val downloaded: Long, val total: Long, val reason: Int = 0) {
        val percent: Int
            get() = if (total > 0) ((downloaded * 100) / total).toInt().coerceIn(0, 100) else 0
        val done: Boolean get() = status == DownloadManager.STATUS_SUCCESSFUL
        val failed: Boolean get() = status == DownloadManager.STATUS_FAILED
        val running: Boolean get() = status == DownloadManager.STATUS_RUNNING || status == DownloadManager.STATUS_PENDING
    }

    fun queryProgress(context: Context, id: Long): Progress? {
        if (id < 0) return null
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val q = DownloadManager.Query().setFilterById(id)
        val c: Cursor = dm.query(q) ?: return null
        c.use {
            if (!it.moveToFirst()) return null
            val status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val downloaded = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            val reason = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
            return Progress(status, downloaded, total, reason)
        }
    }

    fun localPath(context: Context, spec: ModelSpec): File =
        File(File(context.filesDir, "models"), spec.fileName)

    fun isDownloaded(context: Context, spec: ModelSpec): Boolean {
        val f = localPath(context, spec)
        return f.exists() && f.length() > 1024
    }
}
