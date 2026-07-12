package com.druk.lmplayground.download

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.LiveData
import androidx.lifecycle.map
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.druk.lmplayground.models.ModelInfo
import com.druk.lmplayground.storage.DownloadProgress
import com.druk.lmplayground.storage.StoragePreferences
import java.util.concurrent.TimeUnit

class DownloadRepository(private val context: Context) {

    companion object {
        private const val TAG = "DownloadRepository"
    }

    private val workManager = WorkManager.getInstance(context)

    fun startDownload(model: ModelInfo, storageUri: Uri) {
        val remoteUri = model.remoteUri ?: return
        val workName = workNameFor(model)

        val inputData = Data.Builder()
            .putString(DownloadWorker.KEY_URL, remoteUri.toString())
            .putString(DownloadWorker.KEY_FILENAME, model.filename)
            .putString(DownloadWorker.KEY_MODEL_NAME, model.name)
            .putString(DownloadWorker.KEY_STORAGE_URI, storageUri.toString())
            .putString(DownloadWorker.KEY_WORK_NAME, workName)
            .build()

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(inputData)
            .setConstraints(constraints)
            .addTag(DownloadWorker.TAG_MODEL_DOWNLOAD)
            .addTag(model.name)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        workManager.enqueueUniqueWork(workName, ExistingWorkPolicy.KEEP, request)
    }

    fun cancelDownload(model: ModelInfo) {
        workManager.cancelUniqueWork(workNameFor(model))

        // Downloads now stream straight into "<filename>.part" inside the SAF
        // model folder (not app temp storage), so drop that partial here.
        // Prefix-match because createFile may have appended an extension.
        try {
            val storageUri = StoragePreferences(context).modelStorageUri
            if (storageUri != null) {
                val partPrefix = "${model.filename}.part"
                DocumentFile.fromTreeUri(context, storageUri)
                    ?.listFiles()
                    ?.firstOrNull { it.name?.startsWith(partPrefix) == true }
                    ?.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to delete partial on cancel: ${e.message}")
        }

        val notificationManager = DownloadNotificationManager(context)
        notificationManager.cancelNotification(notificationManager.getNotificationId(model.name))
    }

    fun observeDownloads(): LiveData<Map<String, DownloadProgress>> {
        return workManager
            .getWorkInfosByTagLiveData(DownloadWorker.TAG_MODEL_DOWNLOAD)
            .map { workInfoList -> mapWorkInfoToProgress(workInfoList) }
    }

    private fun mapWorkInfoToProgress(workInfoList: List<WorkInfo>): Map<String, DownloadProgress> {
        val result = mutableMapOf<String, DownloadProgress>()

        for (workInfo in workInfoList) {
            if (workInfo.state.isFinished) continue

            val progress = workInfo.progress
            val modelName = progress.getString(DownloadWorker.KEY_MODEL_NAME)

            if (modelName == null) {
                val tags = workInfo.tags
                val nameTag = tags.firstOrNull {
                    it != DownloadWorker.TAG_MODEL_DOWNLOAD && it != DownloadWorker::class.java.name
                }
                if (nameTag != null) {
                    val status = when (workInfo.state) {
                        WorkInfo.State.ENQUEUED -> "Waiting for network…"
                        WorkInfo.State.BLOCKED -> "Waiting…"
                        else -> "Starting download…"
                    }
                    result[nameTag] = DownloadProgress(
                        modelName = nameTag,
                        progress = -1f,
                        status = status
                    )
                }
                continue
            }

            val progressValue = progress.getFloat(DownloadWorker.KEY_PROGRESS, 0f)
            val status = progress.getString(DownloadWorker.KEY_STATUS) ?: "Downloading…"
            val bytesDownloaded = progress.getLong(DownloadWorker.KEY_BYTES_DOWNLOADED, 0L)
            val totalBytes = progress.getLong(DownloadWorker.KEY_TOTAL_BYTES, 0L)
            val speed = progress.getLong(DownloadWorker.KEY_SPEED_BYTES_PER_SEC, 0L)
            val eta = progress.getLong(DownloadWorker.KEY_ETA_SECONDS, -1L)

            result[modelName] = DownloadProgress(
                modelName = modelName,
                progress = progressValue,
                status = status,
                bytesDownloaded = bytesDownloaded,
                totalBytes = totalBytes,
                speedBytesPerSec = speed,
                etaSeconds = eta
            )
        }

        return result
    }

    private fun workNameFor(model: ModelInfo): String = "download_${model.filename}"
}
