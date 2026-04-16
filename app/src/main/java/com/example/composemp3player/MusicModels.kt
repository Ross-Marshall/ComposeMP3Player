package com.example.composemp3player

import android.net.Uri

data class AudioFile(
    val id: Long,
    val title: String,
    val artist: String?,
    val album: String?,
    val uri: android.net.Uri,
    val albumId: Long,
    val durationMs: Long,
    val trackNumber: Int,
    val path: String? // <--- Change this to String? (Nullable)
)

data class Album(
    val id: Long,
    val title: String,
    val artist: String,
    val tracks: List<AudioFile>
)

data class Playlist(
    val id: Long,
    val name: String,
    val count: Int,
    val artIds: List<Long> = emptyList(),
    val songTitles: List<String> = emptyList(),
    var customCoverUri: String? = null // New field
)

data class AlbumItem(val id: Long, val title: String, val artist: String, val songCount: Int)
data class PlaylistItem(val id: Long, val name: String, val trackCount: Int)