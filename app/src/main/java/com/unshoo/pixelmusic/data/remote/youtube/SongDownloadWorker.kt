package com.unshoo.pixelmusic.data.remote.youtube

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.unshoo.pixelmusic.data.database.youtube.AppDatabase
import com.unshoo.pixelmusic.data.model.youtube.Song
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.absoluteValue

class SongDownloadWorker(
    private val appContext: Context,
    private val params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface WorkerEntryPoint {
        fun musicDao(): com.unshoo.pixelmusic.data.database.MusicDao
    }

    private val playlistRepository = AppDatabase.getInstance(appContext).playlistRepository()
    private val localSongRepository = AppDatabase.getInstance(appContext).songRepository()
    private val songRepository = SongRepository()
    private val musicDao = EntryPointAccessors.fromApplication(
        appContext,
        WorkerEntryPoint::class.java
    ).musicDao()

    @OptIn(UnstableApi::class)
    override suspend fun doWork(): Result {
        return withContext(Dispatchers.IO) {
            val playlistId = params.inputData.getString(PLAYLIST_KEY)
            val songId = params.inputData.getString(SONG_KEY)
                ?: return@withContext Result.failure()
                
            val persistPublicly = params.inputData.getBoolean("persist_publicly", true)

            var song = localSongRepository.getSong(songId)
            if (song == null) {
                var fetchedSong: Song? = null
                songRepository.getSongInfo(songId).collect { apiResult ->
                    if (apiResult is ApiResult.Success) {
                        fetchedSong = apiResult.data
                    }
                }
                song = fetchedSong ?: return@withContext Result.failure()
                localSongRepository.create(song)
            }

            if (playlistId != null) {
                val playlist = playlistRepository.getPlaylistById(playlistId)
                if (playlist != null) {
                    val playlistImage =
                        DownloadHelper.downloadImage(
                            appContext,
                            playlist.info.coverHref,
                            playlist.info.id
                        )
                    playlistRepository.insertPlaylist(
                        playlist.info.copy(
                            coverPath = playlistImage?.path
                        )
                    )
                }
            }

            try {
                var fullSong: Song? = null
                songRepository.getSongInfo(song.youtubeId)
                    .collect { apiResult ->
                        when (apiResult) {
                            is ApiResult.Success -> {
                                fullSong = apiResult.data
                            }
                            else -> {}
                        }
                    }

                val audioPath =
                    DownloadHelper.downloadAudio(
                        appContext, song, connections = 8, persistPublicly = persistPublicly
                    )
                val thumbnailPath =
                    DownloadHelper.downloadImage(
                        appContext,
                        fullSong?.thumbnailHref ?: song.thumbnailHref,
                        song.youtubeId
                    )

                val updatedSong = song.copy(
                    thumbnailPath = thumbnailPath?.path,
                    audioFilePath = audioPath,
                )
                localSongRepository.create(updatedSong)

                ensureYoutubeSongInLibrary(updatedSong)

                if (audioPath != null) {
                    val mainId = -(15_000_000_000_000L + song.youtubeId.hashCode().toLong().absoluteValue)
                    
                    // Safely handle MediaStore URIs so the database doesn't crash
                    val parentDir = if (audioPath.startsWith("content://")) {
                        "Music/PixelMusic"
                    } else {
                        File(audioPath).parentFile?.absolutePath ?: ""
                    }
                    
                    musicDao.updateSongFilePathAndParent(mainId, audioPath, parentDir)
                }

                if (persistPublicly) {
                    UmihiNotificationManager.showSongDownloadSuccess(appContext, song)
                }
                Result.success()
            } catch (_: CancellationException) {
                UmihiHelper.printd("Song download canceled ${song.title}")
                Result.failure()
            } catch (e: Exception) {
                val errorMessage = e.message ?: e.javaClass.simpleName
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(
                        appContext, 
                        "CRASH REASON: $errorMessage", 
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
                
                if (persistPublicly) {
                    UmihiNotificationManager.showSongDownloadFailed(
                        appContext,
                        song
                    )
                }
                
                UmihiHelper.printe(
                    message = "Error downloading song: ${song.youtubeId}",
                    exception = e
                )
                Result.failure()
            }
        }
    }
