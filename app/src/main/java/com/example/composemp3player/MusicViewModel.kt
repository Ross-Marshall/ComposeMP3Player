package com.example.composemp3player

import android.app.Application
import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

class MusicViewModel(application: Application) : AndroidViewModel(application) {

    // 1. ALL STATE VARIABLES (Declared at the top to prevent "unresolved" errors)
    var songs by mutableStateOf(listOf<AudioFile>())
    var isPlaying by mutableStateOf(false)
    var currentSong by mutableStateOf<AudioFile?>(null)
    var searchQuery by mutableStateOf("")
    var playlistSearchQuery by mutableStateOf("")

    var selectedTab by mutableIntStateOf(0) // 0=Songs, 1=Albums, 2=Playlists
    var selectedAlbum by mutableStateOf<Album?>(null)
    var selectedPlaylist by mutableStateOf<Playlist?>(null)
    var playlistTracks by mutableStateOf<List<AudioFile>>(emptyList())

    var isShuffleOn by mutableStateOf(false)
    var repeatMode by mutableIntStateOf(Player.REPEAT_MODE_OFF)
    var currentPosition by mutableLongStateOf(0L)
    var totalDuration by mutableLongStateOf(0L)

    var playlists by mutableStateOf<List<Playlist>>(emptyList())

    // 2. THE PLAYER & SESSION
    val player: Player = ExoPlayer.Builder(application).build()
    private var mediaSession: MediaSession? = null

    // 3. DERIVED STATES (These rely on the variables above)
    val filteredSongs by derivedStateOf {
        if (searchQuery.isEmpty()) {
            songs
        } else {
            songs.filter {
                it.title.contains(searchQuery, ignoreCase = true) ||
                        (it.artist?.contains(searchQuery, ignoreCase = true) == true)
            }
        }
    }

    val filteredAlbums by derivedStateOf {
        val allAlbums = songs.groupBy { it.albumId }.map { (id, trackList) ->
            val representative = trackList.first()
            Album(
                id = id,
                title = representative.album ?: "Unknown Album",
                artist = representative.artist ?: "Unknown Artist",
                tracks = trackList.sortedBy { it.trackNumber }
            )
        }
        if (searchQuery.isEmpty()) {
            allAlbums.sortedBy { it.title }
        } else {
            allAlbums.filter {
                it.title.contains(searchQuery, ignoreCase = true) ||
                        it.artist.contains(searchQuery, ignoreCase = true)
            }.sortedBy { it.title }
        }
    }

    val filteredPlaylists: List<Playlist>
        get() = if (playlistSearchQuery.isEmpty()) {
            playlists
        } else {
            playlists.filter { playlist ->
                val nameMatch = playlist.name.contains(playlistSearchQuery, ignoreCase = true)
                val songMatch = playlist.songTitles.any { it.contains(playlistSearchQuery, ignoreCase = true) }
                nameMatch || songMatch
            }
        }

    init {
        player.repeatMode = Player.REPEAT_MODE_ALL
        mediaSession = MediaSession.Builder(application, player).build()

        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                val currentMediaItem = player.currentMediaItem
                if (currentMediaItem != null) {
                    val uriString = currentMediaItem.localConfiguration?.uri.toString()
                    val matchedSong = songs.find { it.uri.toString() == uriString }
                    currentSong = matchedSong
                    matchedSong?.let { updateMetadata(it) }
                }
            }
        })

        viewModelScope.launch {
            while(true) {
                currentPosition = player.currentPosition
                totalDuration = player.duration.coerceAtLeast(0L)
                delay(1000)
            }
        }
    }

    // --- METADATA UPDATE (For Bluetooth/Tesla) ---
    private fun updateMetadata(song: AudioFile) {
        val metadata = MediaMetadata.Builder()
            .setTitle(song.title)
            .setArtist(song.artist)
            .setAlbumTitle(song.album)
            .setArtworkUri(song.uri)
            .build()
        player.playlistMetadata = metadata
    }

    private val prefs = application.getSharedPreferences("playlist_art", Context.MODE_PRIVATE)

    // --- MEDIA CONTROL FUNCTIONS ---
    fun togglePlay() { if (player.isPlaying) player.pause() else player.play() }
    fun next() { player.seekToNext() }
    fun previous() { player.seekToPrevious() }
    fun seekTo(position: Long) { player.seekTo(position) }

    fun toggleShuffle() {
        isShuffleOn = !isShuffleOn
        player.shuffleModeEnabled = isShuffleOn
    }

    fun cycleRepeatMode() {
        repeatMode = when (repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ONE
            Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_ALL
            else -> Player.REPEAT_MODE_OFF
        }
        player.repeatMode = repeatMode
    }

    // In MusicViewModel.kt

    fun updatePlaylistCover(playlistId: Long, uri: String) {
        // 1. Save to persistent storage
        prefs.edit().putString("custom_art_$playlistId", uri).apply()

        // 2. Update the in-memory state so the UI refreshes immediately
        playlists = playlists.map {
            if (it.id == playlistId) it.copy(customCoverUri = uri) else it
        }
    }

    // --- PLAYBACK LOGIC ---
    fun playSongFromList(targetList: List<AudioFile>, index: Int) {
        if (index < 0 || index >= targetList.size) return
        val mediaItems = targetList.map { androidx.media3.common.MediaItem.fromUri(it.uri) }
        player.setMediaItems(mediaItems, index, 0L)
        player.prepare()
        player.play()
        currentSong = targetList[index]
    }

    // --- PLAYLIST & LIBRARY LOADING ---
    fun loadLibrary(context: Context) {
        val songList = mutableListOf<AudioFile>()
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val projection = arrayOf(
            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.TRACK, MediaStore.Audio.Media.DATA
        )

        context.contentResolver.query(collection, projection, selection, null, "${MediaStore.Audio.Media.TITLE} ASC")?.use { cursor ->
            val cols = object {
                val id = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val title = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artist = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val album = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val albumId = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                val duration = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val track = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
                val data = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            }

            while (cursor.moveToNext()) {
                val trackRaw = cursor.getInt(cols.track)
                songList.add(AudioFile(
                    id = cursor.getLong(cols.id),
                    title = cursor.getString(cols.title) ?: "Unknown",
                    artist = cursor.getString(cols.artist) ?: "Unknown",
                    album = cursor.getString(cols.album) ?: "Unknown",
                    uri = ContentUris.withAppendedId(collection, cursor.getLong(cols.id)),
                    albumId = cursor.getLong(cols.albumId),
                    durationMs = cursor.getLong(cols.duration),
                    trackNumber = if (trackRaw >= 1000) trackRaw % 1000 else trackRaw,
                    path = cursor.getString(cols.data) ?: ""
                ))
            }
        }
        songs = songList
        loadPlaylists(context)
    }

    fun loadPlaylists(context: Context) {
        val playlistList = mutableListOf<Playlist>()
        val uri = MediaStore.Audio.Playlists.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Audio.Playlists._ID, MediaStore.Audio.Playlists.NAME, MediaStore.Audio.Playlists.DATA)
        val normalizedLib = songs.associateBy { normalizeForComparison(it.path?.substringAfterLast("/") ?: "") }

        context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Playlists._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Playlists.NAME)
            val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Playlists.DATA)

            while (cursor.moveToNext()) {

                val id = cursor.getLong(idCol)
                val name = cursor.getString(nameCol)
                val path = cursor.getString(dataCol) ?: ""

                // 🐧 THE SD CARD FILTER: Only process files that are NOT on the internal storage
                // or specifically on your SD card path (/storage/3930-6433/)
                /*if (path.startsWith("/storage/emulated/0/")) {
                    continue // Skip the internal storage "mirror" or "ghost" records
                }*/

                //val file = File(cursor.getString(dataCol) ?: "")
                val file = java.io.File(path)

                // Only admit the playlist if the .m3u file actually exists on the disk
                if (file.exists()) {
                    val savedUri = prefs.getString("custom_art_$id", null)
                    val artIds = mutableListOf<Long>()
                    val titlesInPlaylist = mutableListOf<String>()

                    try {
                        file.useLines(Charsets.ISO_8859_1) { lines ->
                            lines.filter { it.isNotBlank() && !it.startsWith("#") }.forEach { line ->
                                val fileName = line.substringAfterLast("/").trim()
                                val match = normalizedLib[normalizeForComparison(fileName)]
                                match?.let {
                                    titlesInPlaylist.add(it.title)
                                    if (artIds.size < 4) artIds.add(it.albumId)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        println("DEBUG: Error reading $path: ${e.message}")
                    }

                    playlistList.add(Playlist(id, name, titlesInPlaylist.size, artIds, titlesInPlaylist, savedUri))
                } else {
                    // This is where the duplicates/ghosts are being filtered out
                    println("DEBUG: Skipping ghost playlist at $path")
                }
            }
        }
        playlists = playlistList
    }

    fun openPlaylist(context: Context, playlist: Playlist) {
        selectedPlaylist = playlist
        val tracks = mutableListOf<AudioFile>()
        val normalizedLibrary = songs.associateBy { normalizeForComparison(it.path?.substringAfterLast("/") ?: "") }

        // Get path from MediaStore
        var filePath: String? = null
        context.contentResolver.query(MediaStore.Audio.Playlists.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Audio.Playlists.DATA), "${MediaStore.Audio.Playlists._ID} = ?", arrayOf(playlist.id.toString()), null)?.use {
            if (it.moveToFirst()) filePath = it.getString(0)
        }

        filePath?.let { path ->
            val file = File(path)
            if (file.exists()) {
                file.useLines(Charsets.ISO_8859_1) { lines ->
                    lines.filter { it.isNotBlank() && !it.startsWith("#") }.forEach { line ->
                        normalizedLibrary[normalizeForComparison(line.substringAfterLast("/").trim())]?.let { tracks.add(it) }
                    }
                }
            }
        }
        playlistTracks = tracks
    }

    fun closePlaylist() {
        selectedPlaylist = null
        playlistTracks = emptyList()
    }

    fun closeAlbum() { selectedAlbum = null }

    fun normalizeForComparison(name: String): String = name.replace(Regex("[^a-zA-Z0-9]"), "").lowercase()

    fun formatDuration(ms: Long): String {
        val s = ms / 1000; val m = s / 60; val h = m / 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m % 60, s % 60) else String.format("%02d:%02d", m % 60, s % 60)
    }

    override fun onCleared() {
        super.onCleared()
        mediaSession?.release()
        player.release()
    }
}