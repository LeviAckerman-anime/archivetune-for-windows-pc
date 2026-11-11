import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request as NpRequest
import org.schabi.newpipe.extractor.downloader.Response as NpResponse
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.TimeUnit

// ---------- Models ----------

@Serializable
enum class Source { YOUTUBE, LOCAL }

val Track.videoId: String get() = ref.substringAfter("v=").substringBefore("&")

@Serializable
data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val durationSec: Long,
    val thumb: String?,
    val source: Source,
    val ref: String, // YouTube URL or absolute file path
)

data class LrcLine(val timeMs: Long, val text: String)

data class LyricsResult(val synced: List<LrcLine>, val plain: String?)

// ---------- YouTube Music (via NewPipe Extractor) ----------

private class DownloaderImpl : Downloader() {
    private val client = OkHttpClient.Builder()
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    override fun execute(request: NpRequest): NpResponse {
        val method = request.httpMethod()
        val data = request.dataToSend()
        val body = when {
            data != null -> data.toRequestBody()
            method == "POST" -> ByteArray(0).toRequestBody()
            else -> null
        }
        val builder = okhttp3.Request.Builder()
            .url(request.url())
            .method(method, body)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0")
        request.headers().forEach { (k, values) ->
            builder.removeHeader(k)
            values.forEach { builder.addHeader(k, it) }
        }
        client.newCall(builder.build()).execute().use { r ->
            if (r.code == 429) throw ReCaptchaException("Rate limited", request.url())
            return NpResponse(r.code, r.message, r.headers.toMultimap(), r.body?.string(), r.request.url.toString())
        }
    }
}

object YouTube {
    private val ready: Unit by lazy { NewPipe.init(DownloaderImpl()) }

    /** Blocking – call from Dispatchers.IO. */
    fun search(query: String): List<Track> {
        ready
        val yt = ServiceList.YouTube
        val handler = yt.searchQHFactory.fromQuery(
            query, listOf(YoutubeSearchQueryHandlerFactory.MUSIC_SONGS), ""
        )
        return SearchInfo.getInfo(yt, handler).relatedItems
            .filterIsInstance<StreamInfoItem>()
            .map {
                Track(
                    id = it.url,
                    title = it.name,
                    artist = (it.uploaderName ?: "").removeSuffix(" - Topic"),
                    durationSec = it.duration,
                    thumb = it.thumbnails.maxByOrNull { img -> img.height }?.url,
                    source = Source.YOUTUBE,
                    ref = it.url,
                )
            }
    }

    /** Blocking – resolves a direct audio stream URL for the best-bitrate stream. */
    fun audioUrl(videoUrl: String): String {
        ready
        val info = StreamInfo.getInfo(videoUrl)
        val streams = info.audioStreams
        val best = streams.filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
            .maxByOrNull { it.averageBitrate }
            ?: streams.maxByOrNull { it.averageBitrate }
            ?: error("No audio stream found")
        return best.content
    }
}

// ---------- Lyrics (LRCLIB, free, no API key) ----------

@Serializable
private data class LrcResp(val syncedLyrics: String? = null, val plainLyrics: String? = null)

object Lyrics {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    private val json = Json { ignoreUnknownKeys = true }
    private val tag = Regex("""\[(\d+):(\d+)(?:\.(\d+))?]""")
    private val brackets = Regex("""\s*[(\[].*?[)\]]""")

    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8)

    private fun get(url: String): String? = runCatching {
        val req = HttpRequest.newBuilder(URI(url))
            .header("User-Agent", "TuneDesk/1.0")
            .timeout(Duration.ofSeconds(10))
            .build()
        val res = http.send(req, HttpResponse.BodyHandlers.ofString())
        if (res.statusCode() == 200) res.body() else null
    }.getOrNull()

    suspend fun fetch(t: Track): LyricsResult = withContext(Dispatchers.IO) {
        val title = t.title.replace(brackets, "").trim()
        val artist = t.artist.removeSuffix(" - Topic")
        var url = "https://lrclib.net/api/get?track_name=${enc(title)}&artist_name=${enc(artist)}"
        if (t.durationSec > 0) url += "&duration=${t.durationSec}"

        var hit: LrcResp? = get(url)?.let { runCatching { json.decodeFromString<LrcResp>(it) }.getOrNull() }
        if (hit == null || (hit.syncedLyrics.isNullOrBlank() && hit.plainLyrics.isNullOrBlank())) {
            val list = get("https://lrclib.net/api/search?q=${enc("$artist $title".trim())}")
                ?.let { runCatching { json.decodeFromString<List<LrcResp>>(it) }.getOrNull() }
                .orEmpty()
            hit = list.firstOrNull { !it.syncedLyrics.isNullOrBlank() } ?: list.firstOrNull()
        }
        LyricsResult(parseLrc(hit?.syncedLyrics), hit?.plainLyrics?.takeIf { it.isNotBlank() })
    }

    private fun parseLrc(raw: String?): List<LrcLine> {
        if (raw.isNullOrBlank()) return emptyList()
        val out = mutableListOf<LrcLine>()
        for (line in raw.lines()) {
            val stamps = tag.findAll(line).toList()
            val text = line.replace(tag, "").trim()
            if (stamps.isEmpty() || text.isEmpty()) continue
            for (m in stamps) {
                val min = m.groupValues[1].toLong()
                val sec = m.groupValues[2].toLong()
                val frac = m.groupValues[3].padEnd(3, '0').take(3).toLongOrNull() ?: 0L
                out += LrcLine(min * 60_000 + sec * 1000 + frac, text)
            }
        }
        return out.sortedBy { it.timeMs }
    }
}
