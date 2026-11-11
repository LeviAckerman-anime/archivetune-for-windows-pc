import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.URL
import javax.imageio.ImageIO

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "TuneDesk",
        state = rememberWindowState(width = 1200.dp, height = 780.dp),
    ) {
        MaterialTheme(
            colorScheme = darkColorScheme(
                primary = Color(0xFFCBB8FF),
                background = Color(0xFF121016),
                surface = Color(0xFF1C1822),
                surfaceVariant = Color(0xFF2B2635),
            )
        ) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { App() }
        }
    }
}

@Composable
fun App() {
    val scope = rememberCoroutineScope()
    val s = remember { AppState(scope) }
    LaunchedEffect(Unit) { while (true) { s.tick(); delay(250) } }
    DisposableEffect(Unit) { onDispose { s.player.release() } }

    Column(Modifier.fillMaxSize()) {
        if (!s.player.available) {
            Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "VLC media player (64-bit) is needed for playback. Install it once, then restart TuneDesk.",
                        Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    TextButton(onClick = {
                        runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI("https://www.videolan.org/vlc/")) }
                    }) { Text("Get VLC") }
                }
            }
        }
        Row(Modifier.weight(1f)) {
            NavigationRail {
                Spacer(Modifier.height(8.dp))
                NavigationRailItem(s.tab == Tab.SEARCH, { s.tab = Tab.SEARCH }, { Icon(Icons.Default.Search, null) }, label = { Text("Search") })
                NavigationRailItem(s.tab == Tab.LOCAL, { s.tab = Tab.LOCAL }, { Icon(Icons.Default.LibraryMusic, null) }, label = { Text("Local") })
                NavigationRailItem(s.tab == Tab.LIBRARY, { s.tab = Tab.LIBRARY }, { Icon(Icons.Default.Favorite, null) }, label = { Text("Library") })
                NavigationRailItem(s.tab == Tab.QUEUE, { s.tab = Tab.QUEUE }, { Icon(Icons.Default.QueueMusic, null) }, label = { Text("Queue") })
            }
            Box(Modifier.weight(1f).fillMaxHeight().padding(16.dp)) {
                when (s.tab) {
                    Tab.SEARCH -> SearchScreen(s)
                    Tab.LIBRARY -> LibraryScreen(s)
                    Tab.LOCAL -> LocalScreen(s)
                    Tab.QUEUE -> TrackList(s.queue, s.current?.id) { s.playAt(it) }
                }
            }
            LyricsPanel(s)
        }
        PlayerBar(s)
    }
}

@Composable
fun SearchScreen(s: AppState) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = s.query,
                onValueChange = { s.query = it },
                singleLine = true,
                placeholder = { Text("Search songs on YouTube Music") },
                modifier = Modifier.weight(1f).onKeyEvent {
                    if (it.key == Key.Enter && it.type == KeyEventType.KeyUp) { s.search(); true } else false
                },
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = { s.search() }, enabled = !s.searching) { Text(if (s.searching) "Searching…" else "Search") }
        }
        s.searchMessage?.let { Text(it, Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.error) }
        Spacer(Modifier.height(12.dp))
        TrackList(s.results, s.current?.id) { s.playList(s.results.toList(), it) }
    }
}

@Composable
fun LocalScreen(s: AppState) {
    Column {
        Button(onClick = { s.addLocal() }) {
            Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("Add files or folder")
        }
        Spacer(Modifier.height(12.dp))
        if (s.local.isEmpty()) Text("No local music yet. Supported: mp3, flac, m4a, aac, ogg, opus, wav, wma.", color = Color.Gray)
        TrackList(s.local, s.current?.id) { s.playList(s.local.toList(), it) }
    }
}

@Composable
fun TrackList(tracks: List<Track>, activeId: String?, onPlay: (Int) -> Unit) {
    LazyColumn(Modifier.fillMaxSize()) {
        itemsIndexed(tracks) { i, t ->
            val active = t.id == activeId
            Row(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else Color.Transparent)
                    .clickable { onPlay(i) }
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Thumb(t.thumb, 48.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(t.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                    Text(t.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, color = Color.Gray)
                }
                if (t.durationSec > 0) Text(fmt(t.durationSec * 1000), color = Color.Gray, fontSize = 13.sp)
            }
        }
    }
}

@Composable
fun LyricsPanel(s: AppState) {
    Column(Modifier.width(340.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surface).padding(16.dp)) {
        Text("Lyrics", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))
        val active = s.activeLine
        when {
            s.lyrics.isNotEmpty() -> {
                val ls = rememberLazyListState()
                LaunchedEffect(active) { if (active >= 0) ls.animateScrollToItem((active - 2).coerceAtLeast(0)) }
                LazyColumn(state = ls) {
                    itemsIndexed(s.lyrics) { i, l ->
                        Text(
                            l.text,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (i == active) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.4f),
                            modifier = Modifier.fillMaxWidth().clickable { s.seek(l.timeMs) }.padding(vertical = 6.dp),
                        )
                    }
                }
            }
            s.plainLyrics != null -> Text(s.plainLyrics!!, Modifier.verticalScroll(rememberScrollState()), color = Color.White.copy(alpha = 0.8f))
            else -> Text(s.lyricsStatus, color = Color.Gray)
        }
    }
}

@Composable
fun PlayerBar(s: AppState) {
    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Column {
            var drag by remember { mutableStateOf<Float?>(null) }
            val frac = if (s.lengthMs > 0) (s.positionMs.toFloat() / s.lengthMs).coerceIn(0f, 1f) else 0f
            Slider(
                value = drag ?: frac,
                onValueChange = { drag = it },
                onValueChangeFinished = { drag?.let { s.seekFraction(it) }; drag = null },
                modifier = Modifier.fillMaxWidth().height(20.dp),
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                val t = s.current
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Thumb(t?.thumb, 52.dp)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(t?.title ?: "Nothing playing", maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold)
                        Text(s.status ?: t?.artist.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, color = Color.Gray)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { s.prev() }) { Icon(Icons.Default.SkipPrevious, "Previous") }
                    FilledIconButton(onClick = { s.togglePlay() }) {
                        Icon(if (s.playing) Icons.Default.Pause else Icons.Default.PlayArrow, "Play/Pause")
                    }
                    IconButton(onClick = { s.next() }) { Icon(Icons.Default.SkipNext, "Next") }
                    Spacer(Modifier.width(12.dp))
                    Text("${fmt(s.positionMs)} / ${fmt(s.lengthMs)}", fontSize = 13.sp, color = Color.Gray)
                }
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    val ytTrack = s.current?.takeIf { it.source == Source.YOUTUBE }
                    if (s.signedIn && ytTrack != null) {
                        val isLiked = ytTrack.videoId in s.likedIds
                        IconButton(onClick = { s.toggleLike() }) {
                            Icon(
                                if (isLiked) Icons.Default.Favorite else Icons.Default.FavoriteBorder, "Like",
                                tint = if (isLiked) MaterialTheme.colorScheme.primary else Color.Gray,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                    }
                    Icon(Icons.Default.VolumeUp, null, tint = Color.Gray)
                    Slider(value = s.volume, onValueChange = { s.setVolume(it) }, modifier = Modifier.width(120.dp))
                }
            }
        }
    }
}

@Composable
fun Thumb(url: String?, size: Dp) {
    val img by produceState<ImageBitmap?>(null, url) {
        value = if (url == null) null else withContext(Dispatchers.IO) {
            runCatching { ImageIO.read(URL(url))?.toComposeImageBitmap() }.getOrNull()
        }
    }
    Box(
        Modifier.size(size).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val b = img
        if (b != null) Image(b, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(Icons.Default.MusicNote, null, tint = Color.Gray)
    }
}

fun fmt(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}

@Composable
fun LibraryScreen(s: AppState) {
    var showSignIn by remember { mutableStateOf(false) }
    if (!s.signedIn) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Sign in to see your YouTube Music library", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            Button(onClick = { showSignIn = true }) { Text("Sign in") }
            s.libMessage?.let { Text(it, Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.error) }
        }
    } else {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Signed in as ${s.accountName ?: "your account"}", Modifier.weight(1f), fontWeight = FontWeight.Bold)
                OutlinedButton(onClick = { s.sync() }, enabled = !s.syncing) {
                    Icon(Icons.Default.Sync, null); Spacer(Modifier.width(6.dp)); Text(if (s.syncing) "Syncing…" else "Sync")
                }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { s.signOut() }) { Text("Sign out") }
            }
            s.libMessage?.let { Text(it, Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.error) }
            Spacer(Modifier.height(12.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    FilterChip(
                        selected = s.selectedList == LIKED, onClick = { s.openLiked() },
                        label = { Text("Liked songs (${s.liked.size})") },
                    )
                }
                items(s.playlists) { p ->
                    FilterChip(selected = s.selectedList == p.id, onClick = { s.openPlaylist(p) }, label = { Text(p.title) })
                }
            }
            Spacer(Modifier.height(12.dp))
            TrackList(s.shownTracks, s.current?.id) { s.playList(s.shownTracks.toList(), it) }
        }
    }
    if (showSignIn) SignInDialog(s) { showSignIn = false }
}

@Composable
fun SignInDialog(s: AppState, onClose: () -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Sign in to YouTube Music") },
        text = {
            Column {
                Text(
                    "1. Open music.youtube.com in your browser and sign in.\n" +
                        "2. Press F12, open the Network tab, reload, and click any request to music.youtube.com.\n" +
                        "3. In Request Headers, copy the full value of \"cookie\" and paste it below.\n\n" +
                        "The cookie is stored only on this PC (~/.tunedesk) and sent only to YouTube.",
                    fontSize = 13.sp,
                )
                TextButton(onClick = {
                    runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI("https://music.youtube.com")) }
                }) { Text("Open music.youtube.com") }
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth().height(120.dp),
                    label = { Text("Cookie header") },
                )
            }
        },
        confirmButton = { Button(onClick = { s.signIn(text); onClose() }, enabled = text.isNotBlank()) { Text("Sign in") } },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}
