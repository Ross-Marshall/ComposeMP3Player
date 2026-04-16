package com.example.composemp3player

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel

import androidx.compose.ui.unit.dp

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.clickable

import androidx.media3.common.Player

import android.content.ContentUris
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.Alignment

import coil.compose.rememberAsyncImagePainter

import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Close

import androidx.compose.material3.Slider

import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow

import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.ui.text.style.TextOverflow

import com.example.composemp3player.Playlist
import androidx.compose.material.icons.automirrored.filled.QueueMusic

import androidx.compose.foundation.lazy.items
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import coil.compose.AsyncImage
import coil.request.ImageRequest

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                val vm: MusicViewModel = viewModel()
                val context = LocalContext.current

                // 1. Permission Logic
                val permissionToRequest = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    Manifest.permission.READ_MEDIA_AUDIO
                } else {
                    Manifest.permission.READ_EXTERNAL_STORAGE
                }

                val launcher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { isGranted ->
                    if (isGranted) {
                        vm.loadLibrary(context)
                    }
                }

                // 2. Initial Load
                LaunchedEffect(Unit) {
                    launcher.launch(permissionToRequest)
                }

                // 3. The UI
                MusicPlayerApp(vm)
            }
        }
    }
}

@Composable
fun AlbumArtThumb(albumId: Long?, size: Dp) {
    val artUri = albumId?.let {
        ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), it)
    }
    Image(
        painter = rememberAsyncImagePainter(artUri),
        contentDescription = null,
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(4.dp)),
        contentScale = ContentScale.Crop
    )
}

@Composable
fun AlbumGrid(vm: MusicViewModel) {
    val selectedAlbum = vm.selectedAlbum

    Column(Modifier.fillMaxSize()) {
        if (selectedAlbum != null) {
            // --- VIEW 1: THE DRILL DOWN (Songs in Album) ---

            // 1. Header with Back Button
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(8.dp).fillMaxWidth()
            ) {
                IconButton(onClick = { vm.closeAlbum() }) {
                    Icon(Icons.Default.ArrowBack, "Back to Albums")
                }
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(selectedAlbum.title, style = MaterialTheme.typography.titleLarge, maxLines = 1)
                    Text(selectedAlbum.artist, style = MaterialTheme.typography.bodySmall)
                }
            }

            HorizontalDivider()

            // 2. The Song List for THIS Album
            LazyColumn(modifier = Modifier.weight(1f)) {
                itemsIndexed(selectedAlbum.tracks) { index, song ->
                    ListItem(
                        headlineContent = { Text(song.title) },
                        supportingContent = { Text(formatTime(song.durationMs)) },
                        leadingContent = {
                            Text(
                                text = if (song.trackNumber > 0) "${song.trackNumber}" else "-",
                                Modifier.width(24.dp),
                                style = MaterialTheme.typography.labelMedium
                            )
                        },
                        modifier = Modifier.clickable {
                            vm.playSongFromList(selectedAlbum.tracks, index)
                        }
                    )
                }
            }
        } else {
            // --- VIEW 2: THE GRID (Searchable) ---

            // 1. Album Search Bar
            TextField(
                value = vm.searchQuery,
                onValueChange = { vm.searchQuery = it },
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                placeholder = { Text("Search Albums...") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                singleLine = true
            )

            // 2. The Filtered Grid
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(8.dp)
            ) {
                items(vm.filteredAlbums) { albumItem ->
                    Card(
                        modifier = Modifier
                            .padding(8.dp)
                            .clickable { vm.selectedAlbum = albumItem }, // THIS TRIGGERS THE SWITCH
                        elevation = CardDefaults.cardElevation(4.dp)
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            AlbumArtThumb(albumId = albumItem.id, size = 150.dp)
                            Text(
                                text = albumItem.title,
                                modifier = Modifier.padding(8.dp),
                                maxLines = 1,
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }
                }
            }
        }
    }
}

fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

@Composable
fun SongList(vm: MusicViewModel) {
    Column {

        // 2. SEARCH BAR
        TextField(
            value = vm.searchQuery,
            onValueChange = { vm.searchQuery = it },
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            placeholder = { Text("Search 13k+ tracks...") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = {
                if (vm.searchQuery.isNotEmpty()) {
                    IconButton(onClick = { vm.searchQuery = "" }) {
                        Icon(Icons.Default.Close, "Clear Search")
                    }
                }
            },
            singleLine = true
        )

        // 3. THE LIST
        LazyColumn(modifier = Modifier.weight(1f)) {
            itemsIndexed(vm.filteredSongs) { index, song ->
                val isCurrent = vm.currentSong?.id == song.id
                ListItem(
                    headlineContent = {
                        Text(song.title, maxLines = 1,
                            color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    },
                    supportingContent = { Text("${song.artist} • ${song.album}", maxLines = 1) },
                    leadingContent = { AlbumArtThumb(albumId = song.albumId, size = 45.dp) },
                    modifier = Modifier.clickable { vm.playSongFromList(vm.filteredSongs, index) }
                )
                HorizontalDivider(thickness = 0.5.dp, modifier = Modifier.padding(horizontal = 16.dp))
            }
        }
    }
}

fun formatDuration(ms: Long): String {
    val minutes = (ms / 1000) / 60
    val seconds = (ms / 1000) % 60
    return "%d:%02d".format(minutes, seconds)
}

@Composable
fun PlaylistFolderArt(playlist: Playlist, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(60.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.DarkGray),
        contentAlignment = Alignment.Center
    ) {
        if (playlist.customCoverUri != null) {
            // Show the user's custom chosen image
            AsyncImage(
                model = playlist.customCoverUri,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else if (playlist.artIds.isEmpty()) {
            Icon(Icons.AutoMirrored.Filled.QueueMusic, null, tint = Color.LightGray)
        } else if (playlist.artIds.size < 4) {
            PlaylistImage(albumId = playlist.artIds[0], modifier = Modifier.fillMaxSize())
        } else {
            // Your existing 2x2 logic
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.weight(1f)) {
                    PlaylistImage(albumId = playlist.artIds[0], modifier = Modifier.weight(1f).fillMaxHeight())
                    PlaylistImage(albumId = playlist.artIds[1], modifier = Modifier.weight(1f).fillMaxHeight())
                }
                Row(Modifier.weight(1f)) {
                    PlaylistImage(albumId = playlist.artIds[2], modifier = Modifier.weight(1f).fillMaxHeight())
                    PlaylistImage(albumId = playlist.artIds[3], modifier = Modifier.weight(1f).fillMaxHeight())
                }
            }
        }
    }
}

@Composable
fun PlaylistImage(albumId: Long, modifier: Modifier) {
    // Construct the URI
    val artUri = ContentUris.withAppendedId(
        Uri.parse("content://media/external/audio/albumart"),
        albumId
    )

    // Log the URI so we can see it in Logcat while the UI is scrolling
    // println("UI_DEBUG: Loading Art URI: $artUri")

    AsyncImage(
        model = artUri,
        contentDescription = null,
        modifier = modifier,
        contentScale = ContentScale.Crop,
        // If these remain the generic icon, the MediaStore is blocking the stream
        error = rememberVectorPainter(Icons.Default.MusicNote),
        placeholder = rememberVectorPainter(Icons.Default.MusicNote)
    )
}

@Composable
fun PlaylistTab(vm: MusicViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val selected = vm.selectedPlaylist

    if (selected != null) {
        Column(Modifier.fillMaxSize()) {
            // Header: Back Button + Title
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
                IconButton(onClick = { vm.closePlaylist() }) {
                    Icon(Icons.Default.ArrowBack, "Back")
                }
                Column {
                    Text(selected.name, style = MaterialTheme.typography.titleLarge)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "${vm.playlistTracks.size} Tracks",
                            style = MaterialTheme.typography.labelSmall
                        )
                        Text(
                            vm.formatDuration(vm.playlistTracks.sumOf { it.durationMs }),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                    }
                }
            }
            HorizontalDivider()

            LazyColumn(Modifier.weight(1f)) {
                itemsIndexed(vm.playlistTracks) { index, song ->
                    ListItem(
                        leadingContent = {
                            val albumArtUri = ContentUris.withAppendedId(
                                Uri.parse("content://media/external/audio/albumart"),
                                song.albumId
                            )

                            AsyncImage(
                                model = albumArtUri,
                                contentDescription = null,
                                modifier = Modifier
                                    .size(50.dp)
                                    .clip(RoundedCornerShape(4.dp)),
                                contentScale = ContentScale.Crop,
                                error = rememberVectorPainter(Icons.Default.MusicNote),
                                placeholder = rememberVectorPainter(Icons.Default.MusicNote)
                            )
                        },
                        headlineContent = { Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(song.artist ?: "Unknown Artist", modifier = Modifier.weight(1f))
                                Text(formatDuration(song.durationMs))
                            }
                        },
                        modifier = Modifier.clickable {
                            vm.playSongFromList(vm.playlistTracks, index)
                        }
                    )
                }
            }
        }
    } else { // Main Playlist List
        Column(Modifier.fillMaxSize()) {
            OutlinedTextField(
                value = vm.playlistSearchQuery,
                onValueChange = { vm.playlistSearchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                placeholder = { Text("Search playlists or songs within...") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    if (vm.playlistSearchQuery.isNotEmpty()) {
                        IconButton(onClick = { vm.playlistSearchQuery = "" }) {
                            Icon(Icons.Default.Close, null)
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )

            LazyColumn(Modifier.weight(1f)) {
                items(vm.filteredPlaylists) { playlist ->
                    val launcher = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.OpenDocument() // Use OpenDocument for persistent access
                    ) { uri: Uri? ->
                        uri?.let {
                            // Take persistent permission so the URI works after a reboot
                            val contentResolver = context.contentResolver
                            val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION
                            contentResolver.takePersistableUriPermission(it, takeFlags)

                            vm.updatePlaylistCover(playlist.id, it.toString())
                        }
                    }
                    ListItem(
                        headlineContent = { Text(playlist.name) },
                        supportingContent = {
                            Column {
                                Text("${playlist.count} tracks")
                                // If the search query matches a song, show the first match
                                if (vm.playlistSearchQuery.isNotEmpty() && !playlist.name.contains(
                                        vm.playlistSearchQuery,
                                        true
                                    )
                                ) {
                                    val matchedSong = playlist.songTitles.find {
                                        it.contains(
                                            vm.playlistSearchQuery,
                                            true
                                        )
                                    }
                                    if (matchedSong != null) {
                                        Text(
                                            "Contains: $matchedSong",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                        },
                        leadingContent = { PlaylistFolderArt(playlist = playlist) },
                        trailingContent = {
                            IconButton(onClick = { launcher.launch(arrayOf("image/*")) }) {
                                Icon(Icons.Default.Edit, contentDescription = "Change Cover", Modifier.size(20.dp))
                            }
                        },
                        modifier = Modifier.clickable {
                            vm.playlistSearchQuery = ""
                            vm.openPlaylist(context, playlist)
                        }
                    )
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                }
            }
        }
    }
} // <--- THIS WAS THE MISSING BRACKET

@Composable
fun MusicPlayerApp(vm: MusicViewModel) {
    BackHandler(enabled = vm.selectedAlbum != null || vm.selectedPlaylist != null) {
        if (vm.selectedAlbum != null) vm.closeAlbum()
        if (vm.selectedPlaylist != null) vm.closePlaylist()
    }

    Scaffold(
        bottomBar = {
            BottomAppBar {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    IconButton(onClick = { vm.toggleShuffle() }) {
                        Icon(
                            Icons.Default.Shuffle,
                            "Shuffle",
                            tint = if (vm.isShuffleOn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    }
                    IconButton(onClick = { vm.previous() }) { Icon(Icons.Default.SkipPrevious, "Prev") }
                    IconButton(onClick = { vm.togglePlay() }) {
                        Icon(if (vm.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, "Play")
                    }
                    IconButton(onClick = { vm.next() }) { Icon(Icons.Default.SkipNext, "Next") }
                    IconButton(onClick = { vm.cycleRepeatMode() }) {
                        val icon = if (vm.repeatMode == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat
                        Icon(
                            icon,
                            "Repeat",
                            tint = if (vm.repeatMode != Player.REPEAT_MODE_OFF) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            TabRow(selectedTabIndex = vm.selectedTab) {
                Tab(selected = vm.selectedTab == 0, onClick = { vm.selectedTab = 0 }) { Text("Songs", Modifier.padding(16.dp)) }
                Tab(selected = vm.selectedTab == 1, onClick = { vm.selectedTab = 1 }) { Text("Albums", Modifier.padding(16.dp)) }
                Tab(selected = vm.selectedTab == 2, onClick = { vm.selectedTab = 2 }) { Text("Playlists", Modifier.padding(16.dp)) }
            }

            Surface(tonalElevation = 4.dp, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "${vm.songs.size} Tracks Found",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(16.dp)
                )
            }

            vm.currentSong?.let { song ->
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // WRAPPED IN COLUMN so Slider sits below the text
                    Column(Modifier.padding(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AlbumArtThumb(albumId = song.albumId, size = 50.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(song.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(song.artist ?: "Unknown Artist", style = MaterialTheme.typography.bodySmall, maxLines = 1, modifier = Modifier.weight(1f))
                                    Text(
                                        text = "${formatTime(vm.currentPosition)} / ${formatTime(vm.totalDuration)}",
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            }
                        }
                        Slider(
                            value = vm.currentPosition.toFloat(),
                            onValueChange = { vm.seekTo(it.toLong()) },
                            valueRange = 0f..vm.totalDuration.toFloat().coerceAtLeast(1f),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            when (vm.selectedTab) {
                0 -> SongList(vm)
                1 -> AlbumGrid(vm)
                2 -> PlaylistTab(vm)
            }
        }
    }
}