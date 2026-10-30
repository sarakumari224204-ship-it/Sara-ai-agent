package com.saraai.offline.model

import android.app.DownloadManager
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

object ModelDownloader {

    data class Progress(
        val modelId: String = "",
        val status: Status = Status.Idle,
        val downloaded: Long = 0,
        val total: Long = 0,
        val speedBps: Long = 0,
        val message: String = "",
        val error: String = "",
        val reason: Int = 0,
    ) {
        val percent: Int
            get() = if (total > 0) ((downloaded * 100) / total).toInt().coerceIn(0, 100) else 0
        val etaSec: Long
            get() = if (speedBps > 0 && total > downloaded) (total - downloaded) / speedBps else -1
        val done: Boolean get() = status == Status.Success
        val failed: Boolean get() = status == Status.Failed
        val running: Boolean get() = status == Status.Running || status == Status.Paused
    }

    enum class Status { Idle, Running, Paused, Success, Failed, Cancelled }

    private val cancelFlags = ConcurrentHashMap<String, AtomicBoolean>()
    private val pauseFlags = ConcurrentHashMap<String, AtomicBoolean>()
    private val dmIds = ConcurrentHashMap<String, Long>()

    private val _progress = MutableStateFlow(Progress())
    val progress: StateFlow<Progress> = _progress.asStateFlow()

    fun modelsDir(context: Context): File =
        File(context.filesDir, "models").also { it.mkdirs() }

    fun localPath(context: Context, spec: ModelSpec): File =
        File(modelsDir(context), spec.fileName)

    fun isDownloaded(context: Context, spec: ModelSpec): Boolean {
        return try {
            val f = localPath(context, spec)
            f.exists() && f.length() > 1024 &&
                GgufValidator.validate(f, expectedSha256 = spec.sha256).ok
        } catch (_: Throwable) {
            false
        }
    }

    /** Compat: safe DM into externalFilesDir (never private file://). */
    fun start(context: Context, spec: ModelSpec): Long = startDownloadManager(context, spec)

    fun queryProgress(context: Context, id: Long): Progress? = queryDmProgress(context, id)

    fun availableBytes(context: Context): Long = try {
        modelsDir(context).usableSpace
    } catch (_: Throwable) { 0L }

    fun diskUsageBytes(context: Context): Long = try {
        modelsDir(context).listFiles()?.sumOf { it.length() } ?: 0L
    } catch (_: Throwable) { 0L }

    suspend fun downloadHttp(context: Context, spec: ModelSpec): Progress = withContext(Dispatchers.IO) {
        val dest = localPath(context, spec)
        val partial = File(dest.parentFile, dest.name + ".part")
        if (dest.exists() && dest.length() > 1024) {
            val v = GgufValidator.validate(dest, expectedSha256 = spec.sha256)
            if (v.ok) {
                val p = Progress(spec.id, Status.Success, dest.length(), dest.length(), 0, "Already downloaded")
                _progress.value = p
                return@withContext p
            } else dest.delete()
        }
        val need = (spec.sizeMb.toLong() * 1024L * 1024L) + (50L * 1024L * 1024L)
        val avail = availableBytes(context)
        if (avail in 1 until need) {
            val p = Progress(spec.id, Status.Failed, error = "Not enough storage")
            _progress.value = p
            return@withContext p
        }
        val cancel = AtomicBoolean(false)
        val pause = AtomicBoolean(false)
        cancelFlags[spec.id] = cancel
        pauseFlags[spec.id] = pause
        try {
            val conn = (URL(spec.url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000; readTimeout = 60_000; instanceFollowRedirects = true
                requestMethod = "GET"; setRequestProperty("User-Agent", "SaraAI-Offline/2.1")
            }
            if (conn.responseCode !in 200..299) {
                val p = Progress(spec.id, Status.Failed, error = "HTTP ${conn.responseCode}")
                _progress.value = p; return@withContext p
            }
            val total = conn.contentLengthLong.coerceAtLeast(0)
            var downloaded = 0L; var lastTs = System.currentTimeMillis(); var lastBytes = 0L; var speed = 0L
            partial.parentFile?.mkdirs()
            conn.inputStream.use { input ->
                FileOutputStream(partial).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        if (cancel.get()) {
                            partial.delete()
                            val p = Progress(spec.id, Status.Cancelled, downloaded, total, speed, "Cancelled")
                            _progress.value = p; return@withContext p
                        }
                        while (pause.get() && !cancel.get()) {
                            _progress.value = Progress(spec.id, Status.Paused, downloaded, total, 0, "Paused")
                            Thread.sleep(200); currentCoroutineContext().ensureActive()
                        }
                        val n = input.read(buf); if (n <= 0) break
                        out.write(buf, 0, n); downloaded += n
                        val now = System.currentTimeMillis()
                        if (now - lastTs >= 400) {
                            val dt = (now - lastTs).coerceAtLeast(1)
                            speed = ((downloaded - lastBytes) * 1000) / dt
                            lastTs = now; lastBytes = downloaded
                            _progress.value = Progress(spec.id, Status.Running, downloaded, total, speed,
                                message = "${if (total > 0) ((downloaded * 100) / total).toInt() else 0}%")
                        }
                    }
                    out.flush()
                }
            }
            if (cancel.get()) {
                partial.delete()
                val p = Progress(spec.id, Status.Cancelled, downloaded, total, 0, "Cancelled")
                _progress.value = p; return@withContext p
            }
            if (dest.exists()) dest.delete()
            if (!partial.renameTo(dest)) { partial.copyTo(dest, overwrite = true); partial.delete() }
            val v = GgufValidator.validate(dest, expectedSha256 = spec.sha256)
            if (!v.ok) {
                dest.delete()
                val p = Progress(spec.id, Status.Failed, downloaded, total, 0, error = "Invalid GGUF: ${v.reason}")
                _progress.value = p; return@withContext p
            }
            val p = Progress(spec.id, Status.Success, dest.length(), dest.length(), 0, "Download complete")
            _progress.value = p; p
        } catch (t: Throwable) {
            try { partial.delete() } catch (_: Throwable) {}
            val p = Progress(spec.id, Status.Failed, error = t.message ?: "Download error")
            _progress.value = p; p
        } finally {
            cancelFlags.remove(spec.id); pauseFlags.remove(spec.id)
        }
    }

    fun cancel(modelId: String) { cancelFlags[modelId]?.set(true) }
    fun pause(modelId: String) { pauseFlags[modelId]?.set(true) }
    fun resume(modelId: String) { pauseFlags[modelId]?.set(false) }

    fun delete(context: Context, spec: ModelSpec): Boolean = try {
        val f = localPath(context, spec)
        File(f.parentFile, f.name + ".part").delete()
        if (f.exists()) f.delete() else true
    } catch (_: Throwable) { false }

    fun startDownloadManager(context: Context, spec: ModelSpec): Long {
        return try {
            val req = DownloadManager.Request(Uri.parse(spec.url))
                .setTitle(spec.name)
                .setDescription("Sara AI model")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)
                .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, spec.fileName)
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val id = dm.enqueue(req)
            dmIds[spec.id] = id
            id
        } catch (t: Throwable) {
            _progress.value = Progress(spec.id, Status.Failed, error = "DM start failed: ${t.message}")
            -2L
        }
    }

    fun queryDmProgress(context: Context, id: Long): Progress? {
        if (id < 0) return null
        return try {
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val c: Cursor = dm.query(DownloadManager.Query().setFilterById(id)) ?: return null
            c.use {
                if (!it.moveToFirst()) return null
                val status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val downloaded = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                val total = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                val reason = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                val st = when (status) {
                    DownloadManager.STATUS_SUCCESSFUL -> Status.Success
                    DownloadManager.STATUS_FAILED -> Status.Failed
                    DownloadManager.STATUS_PAUSED -> Status.Paused
                    DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PENDING -> Status.Running
                    else -> Status.Idle
                }
                Progress(status = st, downloaded = downloaded, total = total, reason = reason,
                    message = if (st == Status.Failed) "DM fail reason=$reason" else "",
                    error = if (st == Status.Failed) "reason=$reason" else "")
            }
        } catch (_: Throwable) { null }
    }
}
