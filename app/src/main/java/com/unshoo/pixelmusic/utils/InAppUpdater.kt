package com.unshoo.pixelmusic.utils

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

data class GithubRelease(
    @SerializedName("tag_name") val tagName: String,
    @SerializedName("assets") val assets: List<GithubAsset>
)

data class GithubAsset(
    @SerializedName("browser_download_url") val downloadUrl: String,
    @SerializedName("name") val name: String
)

sealed class UpdateState {
    object Checking : UpdateState()
    object UpToDate : UpdateState()
    data class Available(val versionName: String, val downloadUrl: String) : UpdateState()
    data class Downloading(val progress: Float) : UpdateState()
}

object InAppUpdater {
    private val client = OkHttpClient()
    private val gson = Gson()
    private const val REPO_URL = "https://api.github.com/repos/Saurav-02/PixelMusic/releases/latest"

    suspend fun checkForUpdate(currentVersion: String): UpdateState = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(REPO_URL).build()
            val response = client.newCall(request).execute()
            
            if (response.isSuccessful) {
                val body = response.body?.string()
                val release = gson.fromJson(body, GithubRelease::class.java)
                
                // Compare tags (e.g., "v2.1" vs "2.0.0")
                val cleanLatest = release.tagName.replace(Regex("[^0-9.]"), "")
                val cleanCurrent = currentVersion.replace(Regex("[^0-9.]"), "")
                
                if (cleanLatest != cleanCurrent && release.assets.isNotEmpty()) {
                    val apkAsset = release.assets.firstOrNull { it.name.endsWith(".apk") }
                    if (apkAsset != null) {
                        return@withContext UpdateState.Available(release.tagName, apkAsset.downloadUrl)
                    }
                }
            }
            return@withContext UpdateState.UpToDate
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext UpdateState.UpToDate
        }
    }

    fun downloadAndTrackProgress(context: Context, url: String, fileName: String): Flow<Float> = flow {
        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val uri = Uri.parse(url)
        
        // Clean up old update files
        val oldFile = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName)
        if (oldFile.exists()) oldFile.delete()

        val request = DownloadManager.Request(uri)
            .setTitle("PixelMusic Update")
            .setDescription("Downloading latest version")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, fileName)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)

        val downloadId = downloadManager.enqueue(request)
        var isDownloading = true

        while (isDownloading) {
            val query = DownloadManager.Query().setFilterById(downloadId)
            val cursor = downloadManager.query(query)
            
            if (cursor != null && cursor.moveToFirst()) {
                val bytesDownloadedIndex = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                val bytesTotalIndex = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)

                if (bytesDownloadedIndex != -1 && bytesTotalIndex != -1 && statusIndex != -1) {
                    val bytesDownloaded = cursor.getInt(bytesDownloadedIndex)
                    val bytesTotal = cursor.getInt(bytesTotalIndex)
                    val status = cursor.getInt(statusIndex)

                    if (status == DownloadManager.STATUS_SUCCESSFUL) {
                        emit(1f)
                        isDownloading = false
                        promptInstall(context, fileName)
                    } else if (status == DownloadManager.STATUS_FAILED) {
                        isDownloading = false
                    } else if (bytesTotal > 0) {
                        emit(bytesDownloaded.toFloat() / bytesTotal.toFloat())
                    }
                }
            }
            cursor?.close()
            delay(500) // Poll twice a second
        }
    }.flowOn(Dispatchers.IO)

    private fun promptInstall(context: Context, fileName: String) {
        val file = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName)
        if (file.exists()) {
            val authority = "${context.packageName}.provider"
            val apkUri = FileProvider.getUriForFile(context, authority, file)
            
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            context.startActivity(intent)
        }
    }
}

