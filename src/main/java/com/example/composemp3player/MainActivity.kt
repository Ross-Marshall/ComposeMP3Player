@file:Suppress("DEPRECATION")

package com.example.composemp3player

// --- Android / system ---
import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore

// --- Activity / Compose entrypoint ---
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

// --- Compose ---
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

import androidx.compose.foundation.background

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Slider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton

// --- Coroutines ---
import kotlinx.coroutines.launch

// --- Media3 ---
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.FilledTonalIconButton

import androidx.compose.material3.FilterChip

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.saveable.rememberSaveable

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp

import kotlin.math.max

import androidx.compose.material3.Text
import androidx.compose.material3.ListItem
import androidx.compose.material3.Divider

import androidx.compose.ui.platform.LocalDensity


import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.lazy.*
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt

import androidx.compose.ui.input.pointer.pointerInput

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size

import androidx.compose.material.icons.filled.ArrowBack

import androidx.core.content.contentValuesOf

import android.content.Intent

import androidx.compose.material.icons.filled.*

import androidx.media3.common.MediaMetadata

import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ArrowDownward

import androidx.compose.runtime.remember

import android.content.BroadcastReceiver

import android.content.IntentFilter
import android.media.AudioManager

import androidx.compose.runtime.DisposableEffect

import androidx.media3.common.AudioAttributes
import androidx.media3.common.C

import androidx.media3.session.MediaSession


// ---------------------- Activity ----------------------

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // Use your app theme if you have one; MaterialTheme works fine too
            MaterialTheme {
                MusicPlayerApp()
            }
        }
    }
}

// ---------------------- App code ----------------------

data class AudioFile(
    val id: Long,
    val title: String,
    val artist: String?,
    val uri: Uri,
    val albumId: Long? = null,
    val durationMs: Long = 0
)
data class AlbumItem(val id: Long, val title: String, val artist: String?)
data class ArtistItem(val id: Long, val name: String)
data class PlaylistItem(val id: Long, val name: String, val trackCount: Int)
data class GenreItem(val id: Long, val name: String)

enum class LibraryTab(val title: String) {
    Songs("Songs"),
    Albums("Albums"),
    Playlist("Playlist"),
    Genres("Genres")
}

data class ImportedEntry(val title: String?, val durationSec: Int?, val location: String)



private const val SAFE_MODE: Boolean = false




@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MusicPlayerApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // --- UI state ---
    var showNowPlayingSheet by remember { mutableStateOf(false) }
    var viewingAlbum by remember { mutableStateOf<AlbumItem?>(null) }
    var viewingPlaylist by remember { mutableStateOf<PlaylistItem?>(null) }
    var viewingGenre by remember { mutableStateOf<GenreItem?>(null) }

    // --- Data ---
    val songs = remember { mutableStateListOf<AudioFile>() }
    val albums = remember { mutableStateListOf<AlbumItem>() }
    val playlists = remember { mutableStateListOf<PlaylistItem>() }
    val genres = remember { mutableStateListOf<GenreItem>() }

    // The queue the player is currently using (songs / album / playlist / genre)
    var activeQueue by remember { mutableStateOf<List<AudioFile>>(emptyList()) }

    // --- Player + MediaSession ---
    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true
            )
        }
    }
    val mediaSession = remember { MediaSession.Builder(context, player).build() }

    // Release both on dispose
    DisposableEffect(player, mediaSession) {
        onDispose {
            runCatching { mediaSession.release() }
            runCatching { player.release() }
        }
    }

    // Pause when headphones unplug
    DisposableEffect(player) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                if (AudioManager.ACTION_AUDIO_BECOMING_NOISY == intent?.action && player.isPlaying) {
                    player.pause()
                }
            }
        }
        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        context.registerReceiver(receiver, filter)
        onDispose { context.unregisterReceiver(receiver) }
    }

    // --- Observe player state ---
    var currentIndex by remember { mutableStateOf(-1) }
    var isPlaying by remember { mutableStateOf(false) }
    var positionMs by remember { mutableStateOf(0L) }
    var durationMs by remember { mutableStateOf(0L) }
    var shuffleOn by remember { mutableStateOf(false) }
    var repeatMode by remember { mutableStateOf(androidx.media3.common.Player.REPEAT_MODE_OFF) }

    DisposableEffect(player) {
        val listener = object : androidx.media3.common.Player.Listener {
            override fun onEvents(p: androidx.media3.common.Player, events: androidx.media3.common.Player.Events) {
                currentIndex = p.currentMediaItemIndex
                isPlaying = p.isPlaying
                durationMs = if (p.duration > 0) p.duration else 0L
                positionMs = p.currentPosition
                shuffleOn = p.shuffleModeEnabled
                repeatMode = p.repeatMode
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    // Poll progress while playing
    LaunchedEffect(isPlaying, currentIndex) {
        while (isPlaying && currentIndex >= 0) {
            positionMs = player.currentPosition
            durationMs = if (player.duration > 0) player.duration else 0L
            kotlinx.coroutines.delay(500)
        }
    }

    // --- Permissions ---
    val neededPermission =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            Manifest.permission.READ_MEDIA_AUDIO
        else
            Manifest.permission.READ_EXTERNAL_STORAGE

    var permissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, neededPermission) == PackageManager.PERMISSION_GRANTED
        )
    }
    var requestedOnce by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        permissionGranted = granted
        if (granted) {
            loadAllMediaSafe(context, songs, albums, playlists, genres)
            player.setMediaItems(songs.map(::songToMediaItem))
            player.prepare()
            activeQueue = songs                  // ✅ keep UI in sync
        }
    }

    val onToggleShuffle = { player.shuffleModeEnabled = !player.shuffleModeEnabled }
    val onCycleRepeat = {
        player.repeatMode = when (player.repeatMode) {
            androidx.media3.common.Player.REPEAT_MODE_OFF -> androidx.media3.common.Player.REPEAT_MODE_ALL
            androidx.media3.common.Player.REPEAT_MODE_ALL -> androidx.media3.common.Player.REPEAT_MODE_ONE
            else -> androidx.media3.common.Player.REPEAT_MODE_OFF
        }
    }

    // First composition: load or request
    LaunchedEffect(Unit) {
        if (permissionGranted) {
            loadAllMediaSafe(context, songs, albums, playlists, genres)
            player.setMediaItems(songs.map(::songToMediaItem))
            player.prepare()
            activeQueue = songs                  // ✅ initial queue
        } else if (!requestedOnce) {
            requestedOnce = true
            permissionLauncher.launch(neededPermission)
        }
    }

    // --- UI ---
    val tabs = listOf(LibraryTab.Songs, LibraryTab.Albums, LibraryTab.Playlist, LibraryTab.Genres)
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { tabs.size })

    Scaffold(
        topBar = {
            Surface(shadowElevation = 4.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val showingDetail = (viewingPlaylist != null || viewingAlbum != null || viewingGenre != null)
                    if (showingDetail) {
                        IconButton(onClick = {
                            when {
                                viewingPlaylist != null -> viewingPlaylist = null
                                viewingAlbum != null    -> viewingAlbum = null
                                viewingGenre != null    -> viewingGenre = null
                            }
                        }) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") }

                        val title = when {
                            viewingPlaylist != null -> viewingPlaylist!!.name
                            viewingAlbum != null    -> viewingAlbum!!.title
                            viewingGenre != null    -> viewingGenre!!.name
                            else                    -> ""
                        }
                        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 4.dp))
                    } else {
                        Text(
                            text = "ComposeMP3Player",
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.padding(start = 12.dp)
                        )
                    }
                }
            }
        },
        bottomBar = {
            NowPlayingBar(
                current = activeQueue.getOrNull(currentIndex),   // ✅ from activeQueue
                isPlaying = isPlaying,
                positionMs = positionMs,
                durationMs = durationMs,
                onTogglePlay = { if (player.isPlaying) player.pause() else player.play() },
                onPrev = { player.seekToPreviousMediaItem() },
                onNext = { player.seekToNextMediaItem() },
                onSeek = { newPos -> player.seekTo(newPos) },
                onExpand = { showNowPlayingSheet = true }
            )
        }
    ) { inner ->

        when {
            // --- Album detail ---
            viewingAlbum != null -> {
                val album = viewingAlbum!!
                val albumSongs by produceState(initialValue = emptyList<AudioFile>(), album, context) {
                    value = runCatching { loadAlbumSongs(context, album.id) }.getOrDefault(emptyList())
                }

                AlbumSongsScreen(
                    album = album,
                    songs = albumSongs,
                    onBack = { viewingAlbum = null },
                    onPlaySong = { song ->
                        val idx = albumSongs.indexOfFirst { it.id == song.id }
                        if (idx >= 0) {
                            activeQueue = albumSongs
                            player.setMediaItems(albumSongs.map(::songToMediaItem), idx, 0)
                            player.prepare()
                            player.play()
                        }
                    },
                    contentPadding = inner
                )
            }

            // --- Playlist detail ---
            viewingPlaylist != null -> {
                val pl = viewingPlaylist!!
                var playlistRefresh by remember(pl) { mutableIntStateOf(0) }
                val playlistSongs by produceState(initialValue = emptyList<AudioFile>(), pl, context, playlistRefresh) {
                    value = runCatching { loadPlaylistSongs(context, pl.id) }.getOrDefault(emptyList())
                }

                PlaylistSongsScreen(
                    playlist = pl,
                    songs = playlistSongs,
                    allSongs = songs,
                    onBack = { viewingPlaylist = null },
                    onPlaySong = { song ->
                        val idx = playlistSongs.indexOfFirst { it.id == song.id }
                        if (idx >= 0) {
                            activeQueue = playlistSongs                           // ✅
                            player.setMediaItems(playlistSongs.map(::songToMediaItem), idx, 0)
                            player.prepare()
                            player.play()
                        }
                    },
                    onPlaylistChanged = { playlistRefresh++ },
                    contentPadding = inner
                )
            }

            // --- Genre detail ---
            viewingGenre != null -> {
                val g = viewingGenre!!
                val genreSongs by produceState(initialValue = emptyList<AudioFile>(), g, context) {
                    value = runCatching { loadGenreSongs(context, g.id) }.getOrDefault(emptyList())
                }

                GenreSongsScreen(
                    genre = g,
                    songs = genreSongs,
                    onBack = { viewingGenre = null },
                    onPlaySong = { song ->
                        val idx = genreSongs.indexOfFirst { it.id == song.id }
                        if (idx >= 0) {
                            activeQueue = genreSongs                               // ✅
                            player.setMediaItems(genreSongs.map(::songToMediaItem), idx, 0)
                            player.prepare()
                            player.play()
                        }
                    },
                    contentPadding = inner
                )
            }

            // --- Main tabs ---
            else -> {
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(inner)
                ) {
                    TabRow(selectedTabIndex = pagerState.currentPage) {
                        tabs.forEachIndexed { index, tab ->
                            Tab(
                                selected = pagerState.currentPage == index,
                                onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                                text = { Text(tab.title, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }

                    HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                        when (tabs[page]) {
                            LibraryTab.Songs -> SongsTab(
                                songs = songs,
                                onPlay = { song ->
                                    val idx = songs.indexOfFirst { it.id == song.id }
                                    if (idx >= 0) {
                                        activeQueue = songs                        // ✅
                                        player.setMediaItems(songs.map(::songToMediaItem), idx, 0)
                                        player.prepare()
                                        player.play()
                                    }
                                }
                            )
                            LibraryTab.Albums -> AlbumsTab(
                                albums = albums,
                                onOpenAlbum = { viewingAlbum = it }
                            )
                            LibraryTab.Playlist -> PlaylistTab(
                                playlists = playlists,
                                onOpenPlaylist = { viewingPlaylist = it }
                            )
                            LibraryTab.Genres -> GenresTab(
                                genres = genres,
                                onOpenGenre = { viewingGenre = it }
                            )
                        }
                    }
                }
            }
        }

        if (showNowPlayingSheet) {
            NowPlayingSheet(
                current = activeQueue.getOrNull(currentIndex),   // ✅
                isPlaying = isPlaying,
                positionMs = positionMs,
                durationMs = durationMs,
                onDismiss = { showNowPlayingSheet = false },
                onTogglePlay = { if (player.isPlaying) player.pause() else player.play() },
                onPrev = { player.seekToPreviousMediaItem() },
                onNext = { player.seekToNextMediaItem() },
                onSeek = { newPos -> player.seekTo(newPos) },
                queue = activeQueue,                              // ✅
                currentIndex = currentIndex,
                shuffleOn = shuffleOn,
                repeatMode = repeatMode,
                onToggleShuffle = onToggleShuffle,
                onCycleRepeat = onCycleRepeat,
                onJumpTo = { index ->
                    if (index in activeQueue.indices) {
                        player.seekTo(index, 0)
                        player.play()
                    }
                }
            )
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchSheet(
    onDismiss: () -> Unit,
    songs: List<AudioFile>,
    albums: List<AlbumItem>,
    playlists: List<PlaylistItem>,
    genres: List<GenreItem>,
    onPlaySong: (AudioFile) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        // Tiny custom handle
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .width(36.dp)
                    .height(4.dp)
                    .clip(MaterialTheme.shapes.extraLarge)
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f))
            )
        }

        // Reuse your global search UI
        GlobalSearchTab(
            songs = songs,
            albums = albums,
            playlists = playlists,
            genres = genres,
            onPlaySong = onPlaySong
        )
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun GenresTab(
    genres: List<GenreItem>,
    onOpenGenre: (GenreItem) -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    val focus = LocalFocusManager.current

    val filtered by remember(query, genres) {
        derivedStateOf {
            val q = query.trim().lowercase()
            if (q.isEmpty()) genres
            else genres.filter { it.name.lowercase().contains(q) }
        }
    }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            singleLine = true,
            label = { Text("Search Genres") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) {
                        Icon(Icons.Default.Close, contentDescription = "Clear")
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() })
        )

        if (filtered.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No genres match “$query”.")
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(filtered, key = { it.id }) { genre ->
                    ListItem(
                        headlineContent = { Text(genre.name, maxLines = 1) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenGenre(genre) }
                    )
                    Divider()
                }
            }
        }
    }
}


@Composable
private fun GlobalSearchTab(
    songs: List<AudioFile>,
    albums: List<AlbumItem>,
    playlists: List<PlaylistItem>,
    genres: List<GenreItem>,
    onPlaySong: (AudioFile) -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    val focus = LocalFocusManager.current

    val q = query.trim().lowercase()

    val matchedSongs by remember(q, songs) {
        derivedStateOf {
            if (q.isEmpty()) emptyList()
            else songs.filter { it.title.lowercase().contains(q)  }
        }
    }
    val matchedAlbums by remember(q, albums) {
        derivedStateOf {
            if (q.isEmpty()) emptyList()
            else albums.filter { it.title.lowercase().contains(q)  }
        }
    }

    val matchedPlaylists by remember(q, playlists) {
        derivedStateOf {
            if (q.isEmpty()) emptyList()
            else playlists.filter { it.name.lowercase().contains(q) }
        }
    }
    val matchedGenres by remember(q, genres) {
        derivedStateOf {
            if (q.isEmpty()) emptyList()
            else genres.filter { it.name.lowercase().contains(q) }
        }
    }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Search library") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) {
                        Icon(Icons.Default.Close, contentDescription = "Clear")
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() })
        )

        Spacer(Modifier.height(8.dp))

        LazyColumn(Modifier.fillMaxSize()) {
            if (matchedSongs.isNotEmpty()) {
                item { SectionHeader("Songs (${matchedSongs.size})") }
                items(matchedSongs, key = { it.id }) { s ->
                    ListItem(
                        headlineContent = { Text(s.title, maxLines = 1) },
                        supportingContent = { Text(s.artist ?: "Unknown Artist", maxLines = 1) },
                        modifier = Modifier.clickable { onPlaySong(s) }
                    )
                    Divider()
                }
            }
            if (matchedAlbums.isNotEmpty()) {
                item { SectionHeader("Albums (${matchedAlbums.size})") }
                items(matchedAlbums, key = { it.id }) { a ->
                    ListItem(
                        headlineContent = { Text(a.title, maxLines = 1) },
                        supportingContent = { Text(a.artist ?: "Various Artists", maxLines = 1) }
                    )
                    Divider()
                }
            }

            if (matchedPlaylists.isNotEmpty()) {
                item { SectionHeader("Playlists (${matchedPlaylists.size})") }
                items(matchedPlaylists, key = { it.id }) { pl ->
                    ListItem(
                        headlineContent = { Text(pl.name, maxLines = 1) },
                        supportingContent = { Text("${pl.trackCount} tracks", maxLines = 1) }
                    )
                    Divider()
                }
            }
            if (matchedGenres.isNotEmpty()) {
                item { SectionHeader("Genres (${matchedGenres.size})") }
                items(matchedGenres, key = { it.id }) { g ->
                    ListItem(headlineContent = { Text(g.name, maxLines = 1) })
                    Divider()
                }
            }

            if (q.isNotEmpty() &&
                matchedSongs.isEmpty() &&
                matchedAlbums.isEmpty() &&
                matchedPlaylists.isEmpty() &&
                matchedGenres.isEmpty()
            ) {
                item {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        Text("No results for “$query”", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun AlbumSongsScreen(
    album: AlbumItem,
    songs: List<AudioFile>,
    onBack: () -> Unit,
    onPlaySong: (AudioFile) -> Unit,
    contentPadding: PaddingValues = PaddingValues(0.dp)
) {
    Column(Modifier.fillMaxSize()) {

        // Header with Back + album title
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back")
            }
            Text(
                text = album.title,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 4.dp)
            )
        }

        if (songs.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No tracks found for this album.")
            }
            return
        }

        // (Optional) search-in-album
        var query by rememberSaveable { mutableStateOf("") }
        val focus = LocalFocusManager.current
        val filtered by remember(query, songs) {
            derivedStateOf {
                val q = query.trim().lowercase()
                if (q.isEmpty()) songs
                else songs.filter { s ->
                    s.title.lowercase().contains(q) ||
                            (s.artist ?: "").lowercase().contains(q)
                }
            }
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            singleLine = true,
            label = { Text("Search in album") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) {
                        Icon(Icons.Default.Close, contentDescription = "Clear")
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() })
        )

        // Track list
        LazyColumn(Modifier.fillMaxSize()) {
            items(filtered, key = { it.id }) { song ->
                ListItem(
                    leadingContent = { AlbumArtThumb(albumId = song.albumId, size = 52.dp) },
                    headlineContent = { Text(song.title, maxLines = 1) },
                    supportingContent = { Text(song.artist ?: "Unknown Artist", maxLines = 1) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPlaySong(song) }
                )
                Divider()
            }
        }
    }
}



@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 4.dp)
    )
}


/* ---- Safe loader wrapper (kept for later when SAFE_MODE = false) ---- */
private fun loadAllMediaSafe(
    context: Context,
    songs: MutableList<AudioFile>,
    albums: MutableList<AlbumItem>,
    playlists: MutableList<PlaylistItem>,
    genres: MutableList<GenreItem>
) {
    runCatching { songs.swapAll(loadAudioFiles(context)) }.onFailure { it.printStackTrace() }
    runCatching { albums.swapAll(loadAlbums(context)) }.onFailure { it.printStackTrace() }
    runCatching { playlists.swapAll(loadPlaylists(context)) }.onFailure { it.printStackTrace() } // <-- fixed
    runCatching { genres.swapAll(loadGenres(context)) }.onFailure { it.printStackTrace() }
}


@Composable
private fun SongsTab(
    songs: List<AudioFile>,
    onPlay: (AudioFile) -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    val focus = LocalFocusManager.current

    // Fast, case-insensitive filter
    val filtered by remember(query, songs) {
        derivedStateOf {
            val q = query.trim().lowercase()
            if (q.isEmpty()) songs
            else songs.filter { s ->
                s.title.lowercase().contains(q) ||
                        (s.artist ?: "").lowercase().contains(q)
            }
        }
    }

    val listState = rememberLazyListState()

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Search songs") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) {
                        Icon(Icons.Default.Close, contentDescription = "Clear")
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() })
        )

        Spacer(Modifier.height(8.dp))

        LazyColumnWithScrollbar(
            modifier = Modifier.fillMaxSize(),
            state = listState
        ) {
            items(filtered, key = { it.id }) { song ->
                ListItem(
                    leadingContent = { AlbumArtThumb(albumId = song.albumId, size = 52.dp) },
                    headlineContent = { Text(song.title, maxLines = 1) },
                    supportingContent = { Text(song.artist ?: "Unknown Artist", maxLines = 1) },
                    modifier = Modifier.clickable { onPlay(song) }
                )
                Divider()
            }
        }
    }
}

@Composable
private fun GenreSongsScreen(
    genre: GenreItem,
    songs: List<AudioFile>,
    onBack: () -> Unit,
    onPlaySong: (AudioFile) -> Unit,
    contentPadding: PaddingValues = PaddingValues(0.dp)
) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(contentPadding)
    ) {
        // Optional: small header
        // Text(genre.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(12.dp))

        // Search-in-genre
        var query by rememberSaveable { mutableStateOf("") }
        val focus = LocalFocusManager.current
        val filtered by remember(query, songs) {
            derivedStateOf {
                val q = query.trim().lowercase()
                if (q.isEmpty()) songs
                else songs.filter { s ->
                    s.title.lowercase().contains(q) || (s.artist ?: "").lowercase().contains(q)
                }
            }
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            singleLine = true,
            label = { Text("Search in genre") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) {
                        Icon(Icons.Default.Close, contentDescription = "Clear")
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() })
        )

        LazyColumn(Modifier.fillMaxSize()) {
            items(filtered, key = { it.id }) { song ->
                ListItem(
                    leadingContent = { AlbumArtThumb(albumId = song.albumId, size = 52.dp) },
                    headlineContent = { Text(song.title, maxLines = 1) },
                    supportingContent = { Text(song.artist ?: "Unknown Artist", maxLines = 1) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPlaySong(song) }
                )
                Divider()
            }
        }
    }
}


fun loadGenreSongs(context: Context, genreId: Long): List<AudioFile> {
    val out = mutableListOf<AudioFile>()
    val members = MediaStore.Audio.Genres.Members.getContentUri("external", genreId)
    val proj = arrayOf(
        MediaStore.Audio.Genres.Members.AUDIO_ID,   // song id
        MediaStore.Audio.Genres.Members.TITLE,
        MediaStore.Audio.Genres.Members.ARTIST,
        MediaStore.Audio.Genres.Members.ALBUM_ID
    )
    val sort = MediaStore.Audio.Genres.Members.TITLE + " COLLATE NOCASE ASC"
    val base = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

    context.contentResolver.query(members, proj, null, null, sort)?.use { c ->
        val idIx = c.getColumnIndexOrThrow(MediaStore.Audio.Genres.Members.AUDIO_ID)
        val titleIx = c.getColumnIndexOrThrow(MediaStore.Audio.Genres.Members.TITLE)
        val artistIx = c.getColumnIndexOrThrow(MediaStore.Audio.Genres.Members.ARTIST)
        val albumIdIx = c.getColumnIndexOrThrow(MediaStore.Audio.Genres.Members.ALBUM_ID)
        while (c.moveToNext()) {
            val id = c.getLong(idIx)
            val title = c.getString(titleIx) ?: "(Untitled)"
            val artist = c.getString(artistIx)
            val albumId = c.getLong(albumIdIx)
            val uri = ContentUris.withAppendedId(base, id)
            out.add(AudioFile(id = id, title = title, artist = artist, uri = uri, albumId = albumId))
        }
    }
    return out
}


@Composable
private fun AlbumsTab(
    albums: List<AlbumItem>,
    onOpenAlbum: (AlbumItem) -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    val focus = LocalFocusManager.current

    val filtered by remember(query, albums) {
        derivedStateOf {
            val q = query.trim().lowercase()
            if (q.isEmpty()) albums
            else albums.filter { it.title.lowercase().contains(q) }
        }
    }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            singleLine = true,
            label = { Text("Search for Album Name") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) {
                        Icon(Icons.Default.Close, contentDescription = "Clear")
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() })
        )

        if (filtered.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No albums match “$query”.")
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(filtered, key = { it.id }) { album ->
                    ListItem(
                        leadingContent = {
                            // Uses your helper; shows a blank box if none
                            AlbumArtThumb(albumId = album.id, size = 56.dp)
                        },
                        headlineContent = { Text(album.title, maxLines = 1) },
                        supportingContent = { Text(album.artist ?: "Various Artists", maxLines = 1) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenAlbum(album) }
                    )
                    Divider()
                }
            }
        }
    }
}

@Composable
fun AlbumArtCollage(
    albumIds: List<Long?>,
    size: Dp = 96.dp
) {
    val validIds = albumIds.filterNotNull().take(4)
    val cell = size / 2

    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(4.dp))
    ) {
        when (validIds.size) {
            0 -> Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )
            1 -> {
                // single cover fills the square
                AlbumArtThumb(albumId = validIds[0], size = size)
            }
            else -> {
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxWidth()) {
                        Box(Modifier.size(cell)) {
                            validIds.getOrNull(0)?.let { AlbumArtThumb(albumId = it, size = cell) }
                        }
                        Box(Modifier.size(cell)) {
                            validIds.getOrNull(1)?.let { AlbumArtThumb(albumId = it, size = cell) }
                        }
                    }
                    Row(Modifier.fillMaxWidth()) {
                        Box(Modifier.size(cell)) {
                            validIds.getOrNull(2)?.let { AlbumArtThumb(albumId = it, size = cell) }
                        }
                        Box(Modifier.size(cell)) {
                            validIds.getOrNull(3)?.let { AlbumArtThumb(albumId = it, size = cell) }
                        }
                    }
                }
            }
        }
    }
}


fun getAlbumArtUri(albumId: Long?): Uri? {
    if (albumId == null) return null
    return ContentUris.withAppendedId(
        Uri.parse("content://media/external/audio/albumart"),
        albumId
    )
}

/*
@Composable
fun AlbumArtThumb(albumId: Long?, size: Dp, modifier: Modifier = Modifier) {
    val uri = getAlbumArtUri(albumId)
    if (uri != null) {
        androidx.compose.foundation.Image(
            painter = coil.compose.rememberAsyncImagePainter(uri),
            contentDescription = null,
            modifier = modifier.size(size)
        )
    } else {
        Box(
            modifier = modifier
                .size(size)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        )
    }
}
*/

@Composable
fun AlbumArtThumb(
    albumId: Long?,
    size: Dp,
    corner: Dp = 6.dp,
    modifier: Modifier = Modifier
) {
    val uri = albumId?.let {
        ContentUris.withAppendedId(
            Uri.parse("content://media/external/audio/albumart"),
            it
        )
    }

    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(corner))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        if (uri != null) {
            androidx.compose.foundation.Image(
                painter = coil.compose.rememberAsyncImagePainter(uri),
                contentDescription = null,
                modifier = Modifier.matchParentSize()
            )
        }
    }
}

@Composable
private fun PlaylistTab(
    playlists: List<PlaylistItem>,
    onOpenPlaylist: (PlaylistItem) -> Unit
) {
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    val q = remember(query) { query.trim().lowercase() }

    // Cache playlistId -> songs (used for search + collage fallback)
    val cache = remember { mutableStateMapOf<Long, List<AudioFile>>() }

    // If searching, prefetch all so results can be computed
    LaunchedEffect(q, playlists) {
        if (q.isNotEmpty()) {
            playlists.forEach { pl ->
                if (pl.id !in cache) {
                    cache[pl.id] = runCatching { loadPlaylistSongs(context, pl.id) }.getOrDefault(emptyList())
                }
            }
        }
    }

    // Results when searching: playlists containing matches
    val results: List<Pair<PlaylistItem, Int>> = remember(q, playlists, cache) {
        if (q.isEmpty()) emptyList()
        else playlists.mapNotNull { pl ->
            val songs = cache[pl.id] ?: return@mapNotNull null
            val matches = songs.count { s ->
                s.title.contains(q, ignoreCase = true) ||
                        (s.artist ?: "").contains(q, ignoreCase = true)
            }
            if (matches > 0) pl to matches else null
        }
    }

    Column(Modifier.fillMaxSize()) {
        // Search bar
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            singleLine = true,
            label = { Text("Search playlist for songs") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) {
                        Icon(Icons.Default.Close, contentDescription = "Clear")
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { /* no-op */ })
        )

        if (q.isEmpty()) {
            // Default list
            LazyColumn(Modifier.fillMaxSize()) {
                items(playlists, key = { it.id }) { pl ->
                    // Lazy fill cache for this row (for collage fallback & future totals)
                    LaunchedEffect(pl.id) {
                        if (pl.id !in cache) {
                            cache[pl.id] = runCatching { loadPlaylistSongs(context, pl.id) }.getOrDefault(emptyList())
                        }
                    }
                    val songsInPl = cache[pl.id] ?: emptyList()

                    ListItem(
                        leadingContent = {
                            PlaylistCoverThumb(
                                playlistId = pl.id,
                                songsInPlaylist = songsInPl,
                                size = 56.dp
                            )
                        },
                        headlineContent = { Text(pl.name, maxLines = 1) },
                        supportingContent = { Text("${pl.trackCount} tracks", maxLines = 1) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenPlaylist(pl) }
                    )
                    Divider()
                }
            }
        } else {
            // Search results (playlists that contain matching songs)
            if (results.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text("No playlists contain “$query”.")
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(results, key = { it.first.id }) { (pl, matchCount) ->
                        val songsInPl = cache[pl.id] ?: emptyList()
                        ListItem(
                            leadingContent = {
                                PlaylistCoverThumb(
                                    playlistId = pl.id,
                                    songsInPlaylist = songsInPl,
                                    size = 56.dp
                                )
                            },
                            headlineContent = { Text(pl.name, maxLines = 1) },
                            supportingContent = {
                                Text(
                                    "$matchCount matching song${if (matchCount == 1) "" else "s"}",
                                    maxLines = 1
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenPlaylist(pl) }
                        )
                        Divider()
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaylistSongsScreen(
    playlist: PlaylistItem,
    songs: List<AudioFile>,
    allSongs: List<AudioFile>,
    onBack: () -> Unit,
    onPlaySong: (AudioFile) -> Unit,
    onPlaylistChanged: () -> Unit,
    contentPadding: PaddingValues = PaddingValues(0.dp)
) {
    val context = LocalContext.current

    // --- Edit mode + selection ---
    var editMode by rememberSaveable { mutableStateOf(false) }
    val selected = remember { mutableStateListOf<Long>() } // audioIds
    fun clearSelection() = selected.clear()

    // --- Cover pick + M3U export launchers ---
    val pickCoverLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            context.contentResolver.takePersistableUriPermission(
                it, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            savePlaylistCoverUri(context, playlist.id, it.toString())
            onPlaylistChanged()
        }
    }

    val createM3uLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("audio/x-mpegurl")
    ) { dest: Uri? ->
        if (dest != null) {
            runCatching { exportM3u(context, songs, dest) }
                .onSuccess { /* toast/snackbar if you like */ }
                .onFailure { it.printStackTrace() }
        }
    }

    // --- Add songs dialog state ---
    var showAddDialog by rememberSaveable { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .padding(contentPadding)
    ) {
        // Header collage/custom cover
        val headerAlbumIds = remember(songs) {
            songs.asSequence().mapNotNull { it.albumId }.distinct().take(4).toList()
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            // prefer custom cover, else collage
            val customCover = loadPlaylistCoverUri(context, playlist.id)
            if (!customCover.isNullOrBlank()) {
                androidx.compose.foundation.Image(
                    painter = coil.compose.rememberAsyncImagePainter(Uri.parse(customCover)),
                    contentDescription = null,
                    modifier = Modifier
                        .size(160.dp)
                        .clip(RoundedCornerShape(12.dp))
                )
            } else {
                AlbumArtCollage(albumIds = headerAlbumIds, size = 160.dp)
            }
        }

        // Top action row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                playlist.name,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f)
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                // Set Cover
                IconButton(onClick = { pickCoverLauncher.launch(arrayOf("image/*")) }) {
                    Icon(Icons.Default.Image, contentDescription = "Set Cover")
                }
                // Export M3U
                IconButton(onClick = {
                    val safeName = (playlist.name.ifBlank { "playlist" })
                        .replace(Regex("""[\\/:"*?<>|]+"""), "_")
                    createM3uLauncher.launch("$safeName.m3u")
                }) {
                    Icon(Icons.Default.FileDownload, contentDescription = "Export M3U")
                }
                // Toggle Edit
                FilterChip(
                    selected = editMode,
                    onClick = {
                        editMode = !editMode
                        if (!editMode) clearSelection()
                    },
                    label = { Text(if (editMode) "Done" else "Edit") }
                )
            }
        }

        // In edit mode: toolbar (Add / Remove)
        if (editMode) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilledTonalIconButton(onClick = { showAddDialog = true }) {
                    Icon(Icons.Default.Add, contentDescription = "Add songs")
                }
                val canRemove = selected.isNotEmpty()
                FilledTonalIconButton(
                    enabled = canRemove,
                    onClick = {
                        // Remove selected songs by AUDIO_ID
                        selected.toList().forEach { audioId ->
                            removeFromPlaylistByAudioId(context, playlist.id, audioId)
                        }
                        clearSelection()
                        onPlaylistChanged()
                    }
                ) {
                    Icon(Icons.Default.Delete, contentDescription = "Remove selected")
                }
                if (selected.isNotEmpty()) {
                    Text("${selected.size} selected", style = MaterialTheme.typography.bodyMedium)
                }
            }
            Spacer(Modifier.height(4.dp))
        }

        // List of songs
        LazyColumn(Modifier.fillMaxSize()) {
            items(songs, key = { it.id }) { song ->
                val checked = song.id in selected
                ListItem(
                    leadingContent = {
                        if (editMode) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { on ->
                                    if (on) selected.add(song.id) else selected.remove(song.id)
                                }
                            )
                        } else {
                            AlbumArtThumb(albumId = song.albumId, size = 52.dp)
                        }
                    },
                    headlineContent = { Text(song.title, maxLines = 1) },
                    supportingContent = {
                        Text(song.artist ?: "Unknown Artist", maxLines = 1)
                    },
                    trailingContent = {
                        if (editMode) {
                            Row {
                                IconButton(
                                    onClick = {
                                        // Move Up
                                        val idx = songs.indexOfFirst { it.id == song.id }
                                        if (idx > 0) {
                                            val newOrder = songs.map { it.id }.toMutableList().apply {
                                                val tmp = this[idx - 1]
                                                this[idx - 1] = this[idx]
                                                this[idx] = tmp
                                            }
                                            reorderPlaylist(context, playlist.id, newOrder)
                                            onPlaylistChanged()
                                        }
                                    }
                                ) { Icon(Icons.Default.ArrowUpward, contentDescription = "Up") }

                                IconButton(
                                    onClick = {
                                        // Move Down
                                        val idx = songs.indexOfFirst { it.id == song.id }
                                        if (idx >= 0 && idx < songs.lastIndex) {
                                            val newOrder = songs.map { it.id }.toMutableList().apply {
                                                val tmp = this[idx + 1]
                                                this[idx + 1] = this[idx]
                                                this[idx] = tmp
                                            }
                                            reorderPlaylist(context, playlist.id, newOrder)
                                            onPlaylistChanged()
                                        }
                                    }
                                ) { Icon(Icons.Default.ArrowDownward, contentDescription = "Down") }
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (editMode) {
                                if (checked) selected.remove(song.id) else selected.add(song.id)
                            } else {
                                onPlaySong(song)
                            }
                        }
                )
                Divider()
            }
        }
    }

    // --- Add Songs Dialog ---
    if (showAddDialog) {
        AddSongsToPlaylistDialog(
            allSongs = allSongs,
            existing = songs.map { it.id }.toSet(),
            onDismiss = { showAddDialog = false },
            onConfirm = { toAddIds ->
                if (toAddIds.isNotEmpty()) {
                    addToPlaylist(context, playlist.id, toAddIds)
                    onPlaylistChanged()
                }
                showAddDialog = false
            }
        )
    }
}

@Composable private fun GenresTab(genres: List<GenreItem>) {
    LazyColumn(Modifier.fillMaxSize()) {
        items(genres, key = { it.id }) { genre ->
            ListItem(headlineContent = { Text(genre.name) })
            Divider()
        }
    }
}

@Composable
private fun AddSongsToPlaylistDialog(
    allSongs: List<AudioFile>,
    existing: Set<Long>,
    onDismiss: () -> Unit,
    onConfirm: (List<Long>) -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    val focus = LocalFocusManager.current
    val selectable = remember(allSongs, existing) {
        allSongs.filter { it.id !in existing }
    }
    val filtered by remember(query, selectable) {
        derivedStateOf {
            val q = query.trim().lowercase()
            if (q.isEmpty()) selectable
            else selectable.filter {
                it.title.lowercase().contains(q) ||
                        (it.artist ?: "").lowercase().contains(q)
            }
        }
    }
    val chosen = remember { mutableStateListOf<Long>() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add songs to playlist") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Search songs") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(Icons.Default.Close, contentDescription = "Clear")
                            }
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() })
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    items(filtered, key = { it.id }) { s ->
                        val checked = s.id in chosen
                        ListItem(
                            leadingContent = {
                                Checkbox(checked = checked, onCheckedChange = { on ->
                                    if (on) chosen.add(s.id) else chosen.remove(s.id)
                                })
                            },
                            headlineContent = { Text(s.title, maxLines = 1) },
                            supportingContent = { Text(s.artist ?: "Unknown Artist", maxLines = 1) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (checked) chosen.remove(s.id) else chosen.add(s.id)
                                }
                        )
                        Divider()
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(chosen.toList()) }) {
                Text("Add (${chosen.size})")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

/* ---------- MediaStore loaders (for later) ---------- */

fun loadAudioFiles(context: Context): List<AudioFile> {
    val list = mutableListOf<AudioFile>()
    val base = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    val proj = arrayOf(
        MediaStore.Audio.Media._ID,
        MediaStore.Audio.Media.TITLE,
        MediaStore.Audio.Media.ARTIST,
        MediaStore.Audio.Media.ALBUM_ID,
        MediaStore.Audio.Media.IS_MUSIC
    )
    val sel = "${MediaStore.Audio.Media.IS_MUSIC}!=0"
    val sort = "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
    context.contentResolver.query(base, proj, sel, null, sort)?.use { c ->
        val idIx = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
        val titleIx = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
        val artistIx = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
        val albumIdIx = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
        while (c.moveToNext()) {
            val id = c.getLong(idIx)
            val title = c.getString(titleIx) ?: "(Untitled)"
            val artist = c.getString(artistIx)
            val albumId = c.getLong(albumIdIx)
            list.add(
                AudioFile(
                    id = id,
                    title = title,
                    artist = artist,
                    uri = ContentUris.withAppendedId(base, id),
                    albumId = albumId
                )
            )
        }
    }
    return list
}

@Composable
private fun NowPlayingBar(
    current: AudioFile?,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    onTogglePlay: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onExpand: () -> Unit         // ⬅ NEW
) {
    if (current == null) return

    Surface(tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onExpand() }                 // ⬅ tap mini bar to expand
            ) {
                val artModifier = Modifier.size(44.dp)
                if (current.albumId != null) {
                    val artUri = ContentUris.withAppendedId(
                        Uri.parse("content://media/external/audio/albumart"),
                        current.albumId
                    )
                    androidx.compose.foundation.Image(
                        painter = coil.compose.rememberAsyncImagePainter(artUri),
                        contentDescription = null,
                        modifier = artModifier
                    )
                } else {
                    Box(artModifier)
                }

                Spacer(Modifier.width(12.dp))

                Column(Modifier.weight(1f)) {
                    Text(current.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                    Text(current.artist ?: "Unknown Artist", style = MaterialTheme.typography.bodySmall, maxLines = 1)
                }

                IconButton(onClick = onPrev) { Icon(Icons.Default.SkipPrevious, null) }
                IconButton(onClick = onTogglePlay) {
                    if (isPlaying) Icon(Icons.Default.Pause, null) else Icon(Icons.Default.PlayArrow, null)
                }
                IconButton(onClick = onNext) { Icon(Icons.Default.SkipNext, null) }
            }

            if (durationMs > 0) {
                val pos = positionMs.coerceIn(0L, durationMs)
                Slider(
                    value = pos.toFloat(),
                    onValueChange = { onSeek(it.toLong()) },
                    valueRange = 0f..durationMs.toFloat(),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NowPlayingSheet(
    current: AudioFile?,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    onDismiss: () -> Unit,
    onTogglePlay: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    queue: List<AudioFile>,
    currentIndex: Int,
    shuffleOn: Boolean,
    repeatMode: Int,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onJumpTo: (Int) -> Unit
) {
    if (current == null) return
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val queueState = rememberLazyListState()

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        // --- custom drag handle (small pill) ---
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .width(36.dp)
                    .height(4.dp)
                    .clip(MaterialTheme.shapes.extraLarge)
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f))
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // === BIG ALBUM ART ===
            val artSize = 280.dp
            if (current.albumId != null) {
                val artUri = ContentUris.withAppendedId(
                    Uri.parse("content://media/external/audio/albumart"),
                    current.albumId
                )
                androidx.compose.foundation.Image(
                    painter = coil.compose.rememberAsyncImagePainter(artUri),
                    contentDescription = null,
                    modifier = Modifier.size(artSize)
                )
            } else {
                Surface(
                    modifier = Modifier.size(artSize),
                    tonalElevation = 2.dp,
                    shape = MaterialTheme.shapes.medium
                ) {}
            }

            Spacer(Modifier.height(16.dp))

            // === TITLE / ARTIST ===
            Text(current.title, style = MaterialTheme.typography.titleLarge, maxLines = 2)
            Text(current.artist ?: "Unknown Artist", style = MaterialTheme.typography.bodyMedium)

            Spacer(Modifier.height(8.dp))

            // === SEEK SLIDER + TIME ===
            if (durationMs > 0) {
                val pos = positionMs.coerceIn(0L, durationMs)
                Slider(
                    value = pos.toFloat(),
                    onValueChange = { onSeek(it.toLong()) },
                    valueRange = 0f..durationMs.toFloat(),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(formatTime(pos), style = MaterialTheme.typography.labelSmall)
                    Text(formatTime(durationMs), style = MaterialTheme.typography.labelSmall)
                }
            }

            Spacer(Modifier.height(8.dp))

            // === MAIN CONTROLS (Prev / Play-Pause / Next) ===
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                IconButton(onClick = onPrev) {
                    Icon(Icons.Default.SkipPrevious, contentDescription = "Previous", modifier = Modifier.size(40.dp))
                }
                Spacer(Modifier.width(8.dp))
                FilledTonalIconButton(onClick = onTogglePlay) {
                    if (isPlaying) {
                        Icon(Icons.Default.Pause, contentDescription = "Pause", modifier = Modifier.size(44.dp))
                    } else {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Play", modifier = Modifier.size(44.dp))
                    }
                }
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = onNext) {
                    Icon(Icons.Default.SkipNext, contentDescription = "Next", modifier = Modifier.size(40.dp))
                }
            }

            // --- Shuffle / Repeat row (slimmed down) ---
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterChip(
                    selected = shuffleOn,
                    onClick = onToggleShuffle,
                    label = { Text("Shuffle", style = MaterialTheme.typography.labelSmall) },
                )
                val repeatLabel = when (repeatMode) {
                    androidx.media3.common.Player.REPEAT_MODE_ONE -> "1"
                    androidx.media3.common.Player.REPEAT_MODE_ALL -> "All"
                    else -> "Off"
                }
                FilterChip(
                    selected = repeatMode != androidx.media3.common.Player.REPEAT_MODE_OFF,
                    onClick = onCycleRepeat,
                    label = { Text("Repeat $repeatLabel", style = MaterialTheme.typography.labelSmall) },
                )
            }

            // --- Queue (compact) ---
            Spacer(Modifier.height(8.dp))
            Text(
                "Up Next",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(4.dp))

            LazyColumnWithScrollbar(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 160.dp),
                state = queueState
            ) {
                items(queue.size) { idx ->
                    val item = queue[idx]
                    ListItem(
                        leadingContent = { AlbumArtThumb(albumId = item.albumId, size = 52.dp) },
                        headlineContent = { Text(item.title, maxLines = 1, style = MaterialTheme.typography.bodySmall) },
                        supportingContent = { Text(item.artist ?: "Unknown Artist", maxLines = 1, style = MaterialTheme.typography.labelSmall) },
                        trailingContent = {
                            if (idx == currentIndex) Text("• Now", style = MaterialTheme.typography.labelSmall)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onJumpTo(idx) }
                    )
                    Divider(thickness = 0.5.dp)
                }
            }


            Spacer(Modifier.height(12.dp))
        }
    }
}

fun songToMediaItem(song: AudioFile): MediaItem {
    val artUri = song.albumId?.let {
        ContentUris.withAppendedId(
            Uri.parse("content://media/external/audio/albumart"),
            it
        )
    }

    val metadata = MediaMetadata.Builder()
        .setTitle(song.title)
        .setArtist(song.artist ?: "Unknown Artist")
        .setArtworkUri(artUri)
        .build()

    return MediaItem.Builder()
        .setMediaId(song.id.toString())
        .setUri(song.uri)
        .setMediaMetadata(metadata)
        .build()
}

@Composable
private fun Scrollbar(
    state: LazyListState,
    modifier: Modifier = Modifier,
    thickness: Dp = 3.dp,
    minThumbHeight: Dp = 24.dp,
    color: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
) {
    // read values that change during scroll so we recompose
    val firstIndex by remember { derivedStateOf { state.firstVisibleItemIndex } }
    val firstOffset by remember { derivedStateOf { state.firstVisibleItemScrollOffset } }

    val info = state.layoutInfo
    val visible = info.visibleItemsInfo
    val totalItems = info.totalItemsCount
    val viewportHeight = (info.viewportEndOffset - info.viewportStartOffset).toFloat()

    if (totalItems == 0 || visible.isEmpty() || viewportHeight <= 0f) return

    // estimate content height
    val avgItemSize = visible.map { it.size }.average().toFloat().coerceAtLeast(1f)
    val totalContentHeight = max(avgItemSize * totalItems, viewportHeight)

    // how far we’ve scrolled in px
    val scrolledPx = firstIndex * avgItemSize + firstOffset

    val density = LocalDensity.current
    val rawThumbPx = (viewportHeight / totalContentHeight) * viewportHeight
    // manual dp→px conversion (no toPx())
    val minThumbPx = minThumbHeight.value * density.density
    val thumbHeightPx = max(minThumbPx, rawThumbPx)

    val trackPx = (viewportHeight - thumbHeightPx).coerceAtLeast(0f)
    val progress =
        if (totalContentHeight <= viewportHeight) 0f
        else (scrolledPx / (totalContentHeight - viewportHeight)).coerceIn(0f, 1f)
    val topPx = trackPx * progress

    Canvas(modifier = modifier.width(thickness).fillMaxHeight()) {
        drawRoundRect(
            color = color,
            topLeft = Offset(0f, topPx),
            size = Size(size.width, thumbHeightPx),
            cornerRadius = CornerRadius(size.width / 2f, size.width / 2f)
        )
    }
}

@Composable
private fun DraggableScrollbar(
    state: LazyListState,
    modifier: Modifier = Modifier,
    hitWidth: Dp = 28.dp,
    thickness: Dp = 6.dp,
    minThumbHeight: Dp = 28.dp,
    color: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
) {
    val coroutineScope = rememberCoroutineScope()

    // Listen for scroll position
    val firstIndex by remember { derivedStateOf { state.firstVisibleItemIndex } }
    val firstOffset by remember { derivedStateOf { state.firstVisibleItemScrollOffset } }

    val info = state.layoutInfo
    val visible = info.visibleItemsInfo
    val totalItems = info.totalItemsCount
    val viewportHeight = (info.viewportEndOffset - info.viewportStartOffset).toFloat()
    if (totalItems == 0 || visible.isEmpty() || viewportHeight <= 0f) return

    val avgItemPx = visible.map { it.size }.average().toFloat().coerceAtLeast(1f)
    val totalContentPx = max(avgItemPx * totalItems, viewportHeight)

    val scrolledPx = firstIndex * avgItemPx + firstOffset

    val density = LocalDensity.current
    val rawThumbPx = (viewportHeight / totalContentPx) * viewportHeight
    val minThumbPx = minThumbHeight.value * density.density
    val thumbPx = max(minThumbPx, rawThumbPx)

    val trackPx = (viewportHeight - thumbPx).coerceAtLeast(0f)
    val progress =
        if (totalContentPx <= viewportHeight) 0f
        else (scrolledPx / (totalContentPx - viewportHeight)).coerceIn(0f, 1f)
    val topPx = trackPx * progress

    suspend fun LazyListState.scrollToProgress(p: Float) {
        val layout = this.layoutInfo
        val vph = (layout.viewportEndOffset - layout.viewportStartOffset).toFloat().coerceAtLeast(1f)
        val vis = layout.visibleItemsInfo
        if (layout.totalItemsCount == 0 || vis.isEmpty()) return
        val avg = vis.map { it.size }.average().toFloat().coerceAtLeast(1f)
        val totalPx = max(avg * layout.totalItemsCount, vph)
        val targetPx = (totalPx - vph) * p.coerceIn(0f, 1f)
        val targetIndex = (targetPx / avg).roundToInt().coerceIn(0, layout.totalItemsCount - 1)
        val offsetPx = (targetPx - targetIndex * avg).roundToInt().coerceAtLeast(0)
        scrollToItem(targetIndex, offsetPx)
    }

    Box(
        modifier = modifier
            .zIndex(10f)
            .fillMaxHeight()
            .width(hitWidth)
            .pointerInput(totalItems) {
                detectTapGestures { offset ->
                    val h = size.height.coerceAtLeast(1)
                    val newProgress = (offset.y / h.toFloat()).coerceIn(0f, 1f)
                    coroutineScope.launch { state.scrollToProgress(newProgress) }
                }
            }
            .pointerInput(totalItems) {
                detectVerticalDragGestures { change, _ ->
                    val h = size.height.coerceAtLeast(1)
                    val newProgress = (change.position.y / h.toFloat()).coerceIn(0f, 1f)
                    coroutineScope.launch { state.scrollToProgress(newProgress) }
                }
            },
        contentAlignment = Alignment.TopEnd
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxHeight()
                .width(thickness)
        ) {
            drawRoundRect(
                color = color,
                topLeft = Offset(0f, topPx),
                size = Size(size.width, thumbPx),
                cornerRadius = CornerRadius(size.width / 2f, size.width / 2f)
            )
        }
    }
}
/*
private fun songToMediaItem(song: AudioFile): MediaItem {
    val artUri = song.albumId?.let {
        ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), it)
    }
    val md = MediaMetadata.Builder()
        .setTitle(song.title)
        .setArtist(song.artist ?: "Unknown Artist")
        .setArtworkUri(artUri)
        .build()
    return MediaItem.Builder().setUri(song.uri).setMediaMetadata(md).build()
}
*/

@Composable
private fun LazyColumnWithScrollbar(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    content: LazyListScope.() -> Unit
) {
    Box(modifier) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = state,
            content = content
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .padding(end = 2.dp),
            contentAlignment = Alignment.TopEnd
        ) {
            Scrollbar(state = state)
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSec = (ms / 1000).toInt()
    val m = totalSec / 60
    val s = totalSec % 60
    return "%d:%02d".format(m, s)
}

fun loadAlbumSongs(context: Context, albumId: Long): List<AudioFile> {
    val out = mutableListOf<AudioFile>()
    val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

    val projection = arrayOf(
        MediaStore.Audio.Media._ID,
        MediaStore.Audio.Media.TITLE,
        MediaStore.Audio.Media.ARTIST,
        MediaStore.Audio.Media.ALBUM_ID,
        MediaStore.Audio.Media.TRACK      // not stored, but used for ordering
    )

    val selection = "${MediaStore.Audio.Media.ALBUM_ID}=? AND ${MediaStore.Audio.Media.IS_MUSIC}!=0"
    val args = arrayOf(albumId.toString())

    // Order by TRACK so multi-track albums show in play order
    val sortOrder = "${MediaStore.Audio.Media.TRACK} ASC"

    context.contentResolver.query(uri, projection, selection, args, sortOrder)?.use { c ->
        val idIx = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
        val titleIx = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
        val artistIx = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
        val albumIdIx = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
        while (c.moveToNext()) {
            val id = c.getLong(idIx)
            val title = c.getString(titleIx) ?: "(Untitled)"
            val artist = c.getString(artistIx)
            val albId = c.getLong(albumIdIx)
            out.add(
                AudioFile(
                    id = id,
                    title = title,
                    artist = artist,
                    uri = ContentUris.withAppendedId(uri, id),
                    albumId = albId
                )
            )
        }
    }
    return out
}


fun loadAlbums(context: Context): List<AlbumItem> {
    val list = mutableListOf<AlbumItem>()
    val uri = MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI
    val proj = arrayOf(
        MediaStore.Audio.Albums._ID,
        MediaStore.Audio.Albums.ALBUM,
        MediaStore.Audio.Albums.ARTIST
    )
    val sort = "${MediaStore.Audio.Albums.ALBUM} COLLATE NOCASE ASC"
    context.contentResolver.query(uri, proj, null, null, sort)?.use { c ->
        val idIx = c.getColumnIndexOrThrow(MediaStore.Audio.Albums._ID)
        val albIx = c.getColumnIndexOrThrow(MediaStore.Audio.Albums.ALBUM)
        val artIx = c.getColumnIndexOrThrow(MediaStore.Audio.Albums.ARTIST)
        while (c.moveToNext()) {
            list.add(AlbumItem(c.getLong(idIx), c.getString(albIx) ?: "(Unknown Album)", c.getString(artIx)))
        }
    }
    return list
}



fun loadPlaylistSongs(context: Context, playlistId: Long): List<AudioFile> {
    val out = mutableListOf<AudioFile>()
    val membersUri = MediaStore.Audio.Playlists.Members.getContentUri("external", playlistId)
    val proj = arrayOf(
        MediaStore.Audio.Playlists.Members.AUDIO_ID,   // song id
        MediaStore.Audio.Playlists.Members.TITLE,
        MediaStore.Audio.Playlists.Members.ARTIST,
        MediaStore.Audio.Playlists.Members.ALBUM_ID
    )
    val sort = MediaStore.Audio.Playlists.Members.PLAY_ORDER + " ASC"

    context.contentResolver.query(membersUri, proj, null, null, sort)?.use { c ->
        val idIx = c.getColumnIndexOrThrow(MediaStore.Audio.Playlists.Members.AUDIO_ID)
        val titleIx = c.getColumnIndexOrThrow(MediaStore.Audio.Playlists.Members.TITLE)
        val artistIx = c.getColumnIndexOrThrow(MediaStore.Audio.Playlists.Members.ARTIST)
        val albumIdIx = c.getColumnIndexOrThrow(MediaStore.Audio.Playlists.Members.ALBUM_ID)
        val base = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

        while (c.moveToNext()) {
            val id = c.getLong(idIx)
            val title = c.getString(titleIx) ?: "(Untitled)"
            val artist = c.getString(artistIx)
            val albumId = c.getLong(albumIdIx)
            val uri = ContentUris.withAppendedId(base, id)
            out.add(AudioFile(id = id, title = title, artist = artist, uri = uri, albumId = albumId))
        }
    }
    return out
}


fun loadPlaylists(context: Context): List<PlaylistItem> {
    val resolver = context.contentResolver
    val uri = MediaStore.Audio.Playlists.EXTERNAL_CONTENT_URI
    val projection = arrayOf(
        MediaStore.Audio.Playlists._ID,
        MediaStore.Audio.Playlists.NAME,
        MediaStore.Audio.Playlists.DATE_MODIFIED
    )
    val sortOrder = "${MediaStore.Audio.Playlists.NAME} COLLATE NOCASE ASC"

    // nameKey -> best PlaylistItem we’ve seen
    val bestByName = LinkedHashMap<String, PlaylistItem>()
    // keep a parallel modified time to break ties
    val modifiedByName = HashMap<String, Long>()

    resolver.query(uri, projection, null, null, sortOrder)?.use { c ->
        val idIx = c.getColumnIndexOrThrow(MediaStore.Audio.Playlists._ID)
        val nameIx = c.getColumnIndexOrThrow(MediaStore.Audio.Playlists.NAME)
        val modIx = c.getColumnIndexOrThrow(MediaStore.Audio.Playlists.DATE_MODIFIED)

        while (c.moveToNext()) {
            val id = c.getLong(idIx)
            val name = (c.getString(nameIx) ?: "").ifBlank { "(Unnamed Playlist)" }
            val modified = runCatching { c.getLong(modIx) }.getOrElse { 0L }

            // Count tracks; stale/empty playlists will be 0
            val count = getPlaylistTrackCount(context, id)

            // Skip known junk: zero-track rows or hidden/system names (optional)
            if (count <= 0) continue
            if (name.startsWith(".")) continue

            val key = name.trim().lowercase()

            val existing = bestByName[key]
            if (existing == null) {
                bestByName[key] = PlaylistItem(id = id, name = name, trackCount = count)
                modifiedByName[key] = modified
            } else {
                // prefer the one with more tracks; if equal, prefer the newer one
                val existingModified = modifiedByName[key] ?: 0L
                if (count > existing.trackCount ||
                    (count == existing.trackCount && modified > existingModified)
                ) {
                    bestByName[key] = PlaylistItem(id = id, name = name, trackCount = count)
                    modifiedByName[key] = modified
                }
            }
        }
    }

    // Stable alphabetical order
    return bestByName.values.sortedBy { it.name.lowercase() }
}

private fun getPlaylistTrackCount(context: Context, playlistId: Long): Int {
    // NOTE: We keep using the classic "external" volume for Members, which matches EXTERNAL_CONTENT_URI.
    val membersUri = MediaStore.Audio.Playlists.Members.getContentUri("external", playlistId)
    context.contentResolver.query(membersUri,
        arrayOf(MediaStore.Audio.Playlists.Members._ID),
        null, null, null
    )?.use { c -> return c.count }
    return 0
}


fun loadGenres(context: Context): List<GenreItem> {
    val list = mutableListOf<GenreItem>()
    val uri = MediaStore.Audio.Genres.EXTERNAL_CONTENT_URI
    val proj = arrayOf(MediaStore.Audio.Genres._ID, MediaStore.Audio.Genres.NAME)
    val sort = "${MediaStore.Audio.Genres.NAME} COLLATE NOCASE ASC"
    context.contentResolver.query(uri, proj, null, null, sort)?.use { c ->
        val idIx = c.getColumnIndexOrThrow(MediaStore.Audio.Genres._ID)
        val nmIx = c.getColumnIndexOrThrow(MediaStore.Audio.Genres.NAME)
        while (c.moveToNext()) list.add(GenreItem(c.getLong(idIx), c.getString(nmIx) ?: "(Unknown Genre)"))
    }
    return list
}

fun createPlaylist(context: Context, name: String): Long {
    val values = contentValuesOf(
        MediaStore.Audio.Playlists.NAME to name
    )
    val uri = context.contentResolver.insert(
        MediaStore.Audio.Playlists.EXTERNAL_CONTENT_URI,
        values
    ) ?: throw IllegalStateException("Failed to create playlist")
    return ContentUris.parseId(uri)
}

fun addToPlaylist(context: Context, playlistId: Long, audioIds: List<Long>) {
    val members = MediaStore.Audio.Playlists.Members.getContentUri("external", playlistId)

    // Find current max PLAY_ORDER
    val startOrder = context.contentResolver.query(
        members,
        arrayOf(MediaStore.Audio.Playlists.Members.PLAY_ORDER),
        null, null,
        "${MediaStore.Audio.Playlists.Members.PLAY_ORDER} DESC"
    )?.use { c -> if (c.moveToFirst()) c.getInt(0) + 1 else 0 } ?: 0

    audioIds.forEachIndexed { i, audioId ->
        val values = contentValuesOf(
            MediaStore.Audio.Playlists.Members.PLAY_ORDER to (startOrder + i),
            MediaStore.Audio.Playlists.Members.AUDIO_ID to audioId
        )
        context.contentResolver.insert(members, values)
    }
}

fun removeFromPlaylistByAudioId(context: Context, playlistId: Long, audioId: Long) {
    val members = MediaStore.Audio.Playlists.Members.getContentUri("external", playlistId)
    context.contentResolver.delete(
        members,
        "${MediaStore.Audio.Playlists.Members.AUDIO_ID}=?",
        arrayOf(audioId.toString())
    )
}

fun removeFromPlaylistByMemberRow(context: Context, playlistId: Long, memberRowId: Long) {
    val members = MediaStore.Audio.Playlists.Members.getContentUri("external", playlistId)
    context.contentResolver.delete(
        members,
        "${MediaStore.Audio.Playlists.Members._ID}=?",
        arrayOf(memberRowId.toString())
    )
}

fun reorderPlaylist(context: Context, playlistId: Long, newOrderAudioIds: List<Long>) {
    val members = MediaStore.Audio.Playlists.Members.getContentUri("external", playlistId)
    newOrderAudioIds.forEachIndexed { index, audioId ->
        val values = contentValuesOf(
            MediaStore.Audio.Playlists.Members.PLAY_ORDER to index
        )
        context.contentResolver.update(
            members,
            values,
            "${MediaStore.Audio.Playlists.Members.AUDIO_ID}=?",
            arrayOf(audioId.toString())
        )
    }
}

fun totalDurationMs(songs: List<AudioFile>): Long =
    songs.sumOf { it.durationMs }

fun itDurationMs(context: Context, audioId: Long): Long {
    val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, audioId)
    context.contentResolver.query(uri, arrayOf(MediaStore.Audio.Media.DURATION), null, null, null)?.use { c ->
        return if (c.moveToFirst()) c.getLong(0) else 0L
    }
    return 0L
}

fun formatHms(ms: Long): String {
    var s = ms / 1000
    val h = s / 3600
    s %= 3600
    val m = s / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

fun exportM3u(context: Context, songs: List<AudioFile>, destUri: Uri) {
    context.contentResolver.openOutputStream(destUri, "w")!!.bufferedWriter(Charsets.UTF_8).use { out ->
        out.write("#EXTM3U\n")
        songs.forEach { s ->
            // If you cache duration in AudioFile, write it here; else 0
            val durSec = 0
            val artist = s.artist ?: ""
            out.write("#EXTINF:$durSec, $artist - ${s.title}\n")
            // Write a content URI; many players can handle these
            out.write(s.uri.toString())
            out.write("\n")
        }
    }
}

@Composable
private fun PlaylistCoverThumb(
    playlistId: Long,
    songsInPlaylist: List<AudioFile>,
    size: Dp = 56.dp
) {
    val context = LocalContext.current
    // Try custom cover first
    val customCover = remember(playlistId) { loadPlaylistCoverUri(context, playlistId) }

    val modifier = Modifier
        .size(size)
        .clip(RoundedCornerShape(6.dp))

    if (!customCover.isNullOrBlank()) {
        androidx.compose.foundation.Image(
            painter = coil.compose.rememberAsyncImagePainter(Uri.parse(customCover)),
            contentDescription = null,
            modifier = modifier
        )
    } else {
        val albumIds = remember(songsInPlaylist) {
            songsInPlaylist.asSequence().mapNotNull { it.albumId }.distinct().take(4).toList()
        }
        AlbumArtCollage(albumIds = albumIds, size = size)
    }
}

fun parseM3u(text: String): List<ImportedEntry> {
    val out = mutableListOf<ImportedEntry>()
    var pending: ImportedEntry? = null
    text.lineSequence().forEach { raw ->
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("#") && !line.startsWith("#EXTINF")) return@forEach
        if (line.startsWith("#EXTINF")) {
            val after = line.removePrefix("#EXTINF:") // format: duration, title
            val comma = after.indexOf(',')
            val dur = after.substring(0, maxOf(0, comma)).toIntOrNull()
            val title = if (comma >= 0) after.substring(comma + 1).trim().ifBlank { null } else null
            pending = ImportedEntry(title, dur, "")
        } else {
            val loc = line
            out.add((pending?.copy(location = loc) ?: ImportedEntry(null, null, loc)).also { pending = null })
        }
    }
    return out
}

private fun savePlaylistCoverUri(context: Context, playlistId: Long, uri: String) {
    val prefs = context.getSharedPreferences("playlist_covers", Context.MODE_PRIVATE)
    prefs.edit().putString(playlistId.toString(), uri).apply()
}

private fun loadPlaylistCoverUri(context: Context, playlistId: Long): String? {
    val prefs = context.getSharedPreferences("playlist_covers", Context.MODE_PRIVATE)
    return prefs.getString(playlistId.toString(), null)
}


/* helper */
private fun <T> MutableList<T>.swapAll(newData: List<T>) { clear(); addAll(newData) }
