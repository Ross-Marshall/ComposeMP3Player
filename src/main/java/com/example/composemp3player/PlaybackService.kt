package com.example.composemp3player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibrarySession
import androidx.media3.session.SessionResult

class PlaybackService : MediaLibraryService() {

    private lateinit var player: ExoPlayer
    private var session: MediaLibrarySession? = null

    override fun onCreate() {
        super.onCreate()

        player = ExoPlayer.Builder(this).build().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true
            )
        }

        val callback = object : MediaLibrarySession.Callback {
            override fun onGetLibraryRoot(
                session: MediaLibrarySession,
                browser: MediaLibrarySession.ControllerInfo,
                params: MediaLibraryService.LibraryParams?
            ): MediaItem {
                // Root is browsable
                return MediaItem.Builder()
                    .setMediaId("root")
                    .setMediaMetadata(
                        MediaItem.MediaMetadata.Builder()
                            .setIsBrowsable(true)
                            .setTitle("Library")
                            .build()
                    )
                    .build()
            }

            override fun onGetChildren(
                session: MediaLibrarySession,
                browser: MediaLibrarySession.ControllerInfo,
                parentId: String,
                page: Int,
                pageSize: Int,
                params: MediaLibraryService.LibraryParams?
            ): List<MediaItem> {
                // Minimal example: expose “Songs” as a folder and the individual tracks
                return when (parentId) {
                    "root" -> listOf(
                        MediaItem.Builder()
                            .setMediaId("songs")
                            .setMediaMetadata(
                                MediaItem.MediaMetadata.Builder()
                                    .setTitle("Songs")
                                    .setIsBrowsable(true)
                                    .build()
                            )
                            .build()
                    )
                    "songs" -> {
                        // TODO: load your real songs; make each item playable
                        val songs = InMemoryLibrary.songs(this@PlaybackService)
                        songs.map { song ->
                            MediaItem.Builder()
                                .setMediaId("song:${song.id}")
                                .setUri(song.uri)
                                .setMediaMetadata(
                                    MediaItem.MediaMetadata.Builder()
                                        .setTitle(song.title)
                                        .setArtist(song.artist)
                                        .setIsPlayable(true)
                                        .build()
                                )
                                .build()
                        }
                    }
                    else -> emptyList()
                }
            }

            override fun onGetItem(
                session: MediaLibrarySession,
                browser: MediaLibrarySession.ControllerInfo,
                mediaId: String
            ): MediaItem? {
                return null
            }

            override fun onSetMediaItems(
                session: MediaLibrarySession,
                controller: MediaLibrarySession.ControllerInfo,
                mediaItems: MutableList<MediaItem>
            ): Int {
                // Allow AA to hand us a list to play
                return SessionResult.RESULT_SUCCESS
            }
        }

        session = MediaLibrarySession.Builder(this, player, callback).build()

        // Foreground service with a small notification (required on Android 14+)
        startForeground(1, ensureNotification())
    }

    override fun onGetSession(sessionId: String): MediaLibrarySession? = session

    override fun onDestroy() {
        session?.release()
        player.release()
        super.onDestroy()
    }

    private fun ensureNotification(): Notification {
        val channelId = "playback"
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(
                    channelId,
                    "Playback",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
        return NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_stat_name) // add a 24x24 icon in res/drawable
            .setContentTitle("ComposeMP3Player")
            .setContentText("Playing media")
            .setOngoing(true)
            .build()
    }
}

/**
 * Replace this with your real loaders. This is just to keep the service self-contained.
 */
object InMemoryLibrary {
    fun songs(ctx: android.content.Context): List<AudioFile> {
        // Ideally call your existing loadAudioFiles(ctx)
        return emptyList()
    }
}
