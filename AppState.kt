import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import java.io.File
import javax.swing.JFileChooser

enum class Tab { SEARCH, LIBRARY, LOCAL, QUEUE }

const val LIKED = "__liked__"
private val AUDIO_EXT = setOf("mp3", "flac", "m4a", "aac", "ogg", "opus", "wav", "wma")

/** Thin wrapper around a vlcj MediaPlayer. Requires VLC (64-bit) installed. */
class Player(onFinished: () -> Unit) {
    val available: Boolean = NativeDiscovery().discover()

    private val mp: MediaPlayer? =
        if (available) MediaPlayerFactory("--no-video").mediaPlayers().newMediaPlayer().also {
            it.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
                override fun finished(mediaPlayer: MediaPlayer) = onFinished()
            })
        } else null

    fun play(mrl: String) { mp?.media()?.play(mrl) }
    fun setPaused(paused: Boolean) { mp?.controls()?.setPause(paused) }
    fun seek(ms: Long) { mp?.controls()?.setTime(ms) }
    fun setVolume(percent: Int) { mp?.audio()?.setVolume(percent) }
    val isPlaying: Boolean get() = mp?.status()?.isPlaying ?: false
    val time: Long get() = mp?.status()?.time() ?: 0L
    val length: Long get() = mp?.status()?.length() ?: 0L
    fun release() { mp?.release() }
}

class AppState(private val scope: CoroutineScope) {
    var tab by mutableStateOf(Tab.SEARCH)
    var query by mutableStateOf("")
    var searching by mutableStateOf(false)
    var searchMessage by mutableStateOf<String?>(null)
    var status by mutableStateOf<String?>(null)

    val results = mutableStateListOf<Track>()
    val local = mutableStateListOf<Track>()
    val queue = mutableStateListOf<Track>()
    var index by mutableStateOf(-1)

    var playing by mutableStateOf(false)
    var positionMs by mutableStateOf(0L)
    var lengthMs by mutableStateOf(0L)
    var volume by mutableStateOf(0.8f)

    var lyrics by mutableStateOf<List<LrcLine>>(emptyList())
    var plainLyrics by mutableStateOf<String?>(null)
    var lyricsStatus by mutableStateOf("Nothing playing")

    // ---- YouTube account / library ----
    var signedIn by mutableStateOf(false)
    var accountName by mutableStateOf<String?>(null)
    var syncing by mutableStateOf(false)
    var libMessage by mutableStateOf<String?>(null)
    val liked = mutableStateListOf<Track>()
    val playlists = mutableStateListOf<PlaylistRef>()
    val shownTracks = mutableStateListOf<Track>()
    var selectedList by mutableStateOf(LIKED)
    var likedIds by mutableStateOf<Set<String>>(emptySet())
    private var cookie: String? = null
    private val playlistCache = HashMap<String, List<Track>>()

    val player = Player { scope.launch { next() } }
    val current: Track? get() = queue.getOrNull(index)
    val activeLine: Int get() = lyrics.indexOfLast { it.timeMs <= positionMs }

    private var playJob: Job? = null
    private var lyricsJob: Job? = null

    /** Called ~4x per second from the UI. */
    fun tick() {
        playing = player.isPlaying
        positionMs = player.time
        val len = player.length
        lengthMs = if (len > 0) len else (current?.durationSec ?: 0L) * 1000
    }

    // ---------- search ----------

    fun search() {
        val q = query.trim()
        if (q.isEmpty()) return
        scope.launch {
            searching = true
            searchMessage = null
            try {
                val found = withContext(Dispatchers.IO) { YouTube.search(q) }
                results.clear(); results.addAll(found)
                if (found.isEmpty()) searchMessage = "No results"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                searchMessage = "Search failed: ${e.message}"
            }
            searching = false
        }
    }

    // ---------- playback ----------

    fun playList(list: List<Track>, start: Int) {
        queue.clear(); queue.addAll(list)
        playAt(start)
    }

    fun playAt(i: Int) {
        if (i !in queue.indices) return
        index = i
        val t = queue[i]
        positionMs = 0
        playJob?.cancel()
        playJob = scope.launch {
            if (!player.available) { status = "VLC not found – install VLC 64-bit"; return@launch }
            status = "Loading…"
            try {
                val mrl = if (t.source == Source.LOCAL) t.ref
                else withContext(Dispatchers.IO) { YouTube.audioUrl(t.ref) }
                player.play(mrl)
                status = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status = "Can't play: ${e.message}"
            }
        }
        loadLyrics(t)
    }

    private fun loadLyrics(t: Track) {
        lyricsJob?.cancel()
        lyrics = emptyList(); plainLyrics = null; lyricsStatus = "Searching lyrics…"
        lyricsJob = scope.launch {
            val r = runCatching { Lyrics.fetch(t) }.getOrNull()
            if (r == null) { lyricsStatus = "Couldn't reach lyrics service"; return@launch }
            lyrics = r.synced
            plainLyrics = r.plain
            if (r.synced.isEmpty() && r.plain == null) lyricsStatus = "No lyrics found"
        }
    }

    fun togglePlay() {
        if (current == null) { if (queue.isNotEmpty()) playAt(0); return }
        player.setPaused(player.isPlaying)
    }

    fun next() { if (index + 1 < queue.size) playAt(index + 1) }

    fun prev() {
        if (positionMs > 3000) player.seek(0) else playAt((index - 1).coerceAtLeast(0))
    }

    fun seek(ms: Long) { player.seek(ms); positionMs = ms }
    fun seekFraction(f: Float) { if (lengthMs > 0) seek((lengthMs * f).toLong()) }

    fun setVolume(v: Float) { volume = v; player.setVolume((v * 100).toInt()) }

    // ---------- local files ----------

    fun addLocal() {
        val chooser = JFileChooser().apply {
            dialogTitle = "Add music files or folders"
            isMultiSelectionEnabled = true
            fileSelectionMode = JFileChooser.FILES_AND_DIRECTORIES
        }
        if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) return
        chooser.selectedFiles
            .flatMap { if (it.isDirectory) it.walkTopDown().toList() else listOf(it) }
            .filter { it.isFile && it.extension.lowercase() in AUDIO_EXT }
            .sortedBy { it.name.lowercase() }
            .forEach { f -> if (local.none { it.ref == f.absolutePath }) local.add(localTrack(f)) }
        Store.write("local.json", local.toList())
    }

    private fun localTrack(f: File): Track {
        val name = f.nameWithoutExtension
        val parts = name.split(" - ", limit = 2)
        val (artist, title) = if (parts.size == 2) parts[0].trim() to parts[1].trim() else "Local file" to name
        return Track(f.absolutePath, title, artist, 0L, null, Source.LOCAL, f.absolutePath)
    }

    // ---------- YouTube sign-in & library sync ----------

    /** [raw] is the "cookie" request header copied from a signed-in music.youtube.com browser tab. */
    fun signIn(raw: String) {
        val c = raw.trim().removePrefix("cookie:").removePrefix("Cookie:").trim()
            .filter { it.code in 32..126 } // header-safe; also drops pasted newlines
        scope.launch {
            libMessage = "Signing in…"
            try {
                val name = withContext(Dispatchers.IO) { Innertube.accountName(c) }
                cookie = c; accountName = name; signedIn = true
                Store.write("session.json", Session(c, name))
                libMessage = null
                sync()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                libMessage = "Sign-in failed: ${e.message}"
            }
        }
    }

    fun signOut() {
        cookie = null; signedIn = false; accountName = null
        liked.clear(); playlists.clear(); shownTracks.clear(); likedIds = emptySet(); playlistCache.clear()
        selectedList = LIKED
        Store.delete("session.json"); Store.delete("library.json")
    }

    fun sync() {
        val c = cookie ?: return
        if (syncing) return
        scope.launch {
            syncing = true
            libMessage = null
            try {
                val (likedList, lists) = withContext(Dispatchers.IO) {
                    Innertube.tracks(c, "VLLM") to Innertube.playlists(c)
                }
                playlistCache.clear()
                applyLibrary(likedList, lists)
                Store.write("library.json", LibraryCache(likedList, lists))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Innertube.AuthException) {
                signOut(); libMessage = e.message
            } catch (e: Exception) {
                libMessage = "Sync failed: ${e.message}"
            }
            syncing = false
        }
    }

    private fun applyLibrary(likedList: List<Track>, lists: List<PlaylistRef>) {
        liked.clear(); liked.addAll(likedList)
        playlists.clear(); playlists.addAll(lists)
        likedIds = liked.map { it.videoId }.toSet()
        if (selectedList == LIKED) { shownTracks.clear(); shownTracks.addAll(liked) }
    }

    fun openLiked() {
        selectedList = LIKED
        shownTracks.clear(); shownTracks.addAll(liked)
    }

    fun openPlaylist(p: PlaylistRef) {
        val c = cookie ?: return
        selectedList = p.id
        shownTracks.clear()
        playlistCache[p.id]?.let { shownTracks.addAll(it); return }
        scope.launch {
            libMessage = "Loading ${p.title}…"
            try {
                val list = withContext(Dispatchers.IO) { Innertube.tracks(c, p.id) }
                playlistCache[p.id] = list
                if (selectedList == p.id) { shownTracks.clear(); shownTracks.addAll(list) }
                libMessage = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                libMessage = "Couldn't load playlist: ${e.message}"
            }
        }
    }

    /** Likes / unlikes the current YouTube track on your account. */
    fun toggleLike() {
        val c = cookie ?: return
        val t = current ?: return
        if (t.source != Source.YOUTUBE) return
        val id = t.videoId
        val like = id !in likedIds
        scope.launch {
            try {
                withContext(Dispatchers.IO) { Innertube.setLike(c, id, like) }
                if (like) liked.add(0, t) else liked.removeAll { it.videoId == id }
                likedIds = liked.map { it.videoId }.toSet()
                if (selectedList == LIKED) { shownTracks.clear(); shownTracks.addAll(liked) }
                Store.write("library.json", LibraryCache(liked.toList(), playlists.toList()))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                libMessage = "Couldn't update like: ${e.message}"
            }
        }
    }

    // Keep last: restores saved data once all properties above exist.
    init {
        player.setVolume((volume * 100).toInt())
        Store.read<List<Track>>("local.json")?.let { saved -> local.addAll(saved.filter { File(it.ref).exists() }) }
        Store.read<LibraryCache>("library.json")?.let { applyLibrary(it.liked, it.playlists) }
        Store.read<Session>("session.json")?.let {
            cookie = it.cookie; accountName = it.name; signedIn = true
            sync() // refresh quietly in the background
        }
    }
}
