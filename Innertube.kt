import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration

/**
 * Minimal authenticated client for the YouTube Music web API ("Innertube").
 * Auth = the browser cookie header + a SAPISIDHASH Authorization header.
 * All functions are blocking: call them from Dispatchers.IO.
 */
object Innertube {
    class AuthException(msg: String) : Exception(msg)

    private const val BASE = "https://music.youtube.com/youtubei/v1/"
    private const val ORIGIN = "https://music.youtube.com"
    // If sync suddenly stops working, bump this to a newer WEB_REMIX version.
    private const val CLIENT_VERSION = "1.20250310.01.00"
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0"

    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    private val json = Json { ignoreUnknownKeys = true }

    // ---- auth + transport ----

    private fun cookieValue(cookie: String, name: String): String? =
        cookie.split(";").map { it.trim() }.firstOrNull { it.startsWith("$name=") }?.substringAfter("=")

    private fun authHeader(cookie: String): String {
        val sapisid = cookieValue(cookie, "SAPISID") ?: cookieValue(cookie, "__Secure-3PAPISID")
            ?: throw AuthException("That cookie has no SAPISID. Make sure you're signed in and copied the full cookie header.")
        val ts = System.currentTimeMillis() / 1000
        val hash = MessageDigest.getInstance("SHA-1")
            .digest("$ts $sapisid $ORIGIN".toByteArray())
            .joinToString("") { "%02x".format(it) }
        return "SAPISIDHASH ${ts}_$hash"
    }

    private fun post(
        cookie: String,
        endpoint: String,
        extra: JsonObjectBuilder.() -> Unit = {},
        query: String = "",
    ): JsonObject {
        val body = buildJsonObject {
            put("context", buildJsonObject {
                put("client", buildJsonObject {
                    put("clientName", "WEB_REMIX")
                    put("clientVersion", CLIENT_VERSION)
                    put("hl", "en")
                    put("gl", "US")
                })
            })
            extra()
        }
        val req = HttpRequest.newBuilder(URI("$BASE$endpoint?prettyPrint=false$query"))
            .header("Content-Type", "application/json")
            .header("Cookie", cookie)
            .header("Authorization", authHeader(cookie))
            .header("Origin", ORIGIN)
            .header("Referer", "$ORIGIN/")
            .header("X-Origin", ORIGIN)
            .header("X-Goog-AuthUser", "0")
            .header("User-Agent", UA)
            .timeout(Duration.ofSeconds(20))
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build()
        val res = http.send(req, HttpResponse.BodyHandlers.ofString())
        when (res.statusCode()) {
            200 -> {}
            401, 403 -> throw AuthException("Your session expired. Please sign in again.")
            else -> throw IOException("YouTube returned HTTP ${res.statusCode()}")
        }
        return json.parseToJsonElement(res.body()).jsonObject
    }

    // ---- JSON helpers (the API responses are deeply nested) ----

    private fun JsonElement.findAll(key: String, out: MutableList<JsonElement> = mutableListOf()): MutableList<JsonElement> {
        when (this) {
            is JsonObject -> forEach { (k, v) -> if (k == key) out += v; v.findAll(key, out) }
            is JsonArray -> forEach { it.findAll(key, out) }
            else -> {}
        }
        return out
    }

    private fun JsonElement.findFirst(key: String): JsonElement? = findAll(key).firstOrNull()

    private fun JsonElement?.at(vararg path: Any): JsonElement? {
        var cur: JsonElement? = this
        for (p in path) {
            val c = cur
            cur = when {
                c is JsonObject && p is String -> c[p]
                c is JsonArray && p is Int -> c.getOrNull(p)
                else -> null
            } ?: return null
        }
        return cur
    }

    private val JsonElement?.str: String? get() = (this as? JsonPrimitive)?.contentOrNull

    private fun lastThumb(el: JsonElement?): String? =
        (el as? JsonArray)?.lastOrNull().at("url").str

    private fun parseDuration(s: String?): Long =
        s?.split(":")?.mapNotNull { it.trim().toLongOrNull() }?.fold(0L) { acc, n -> acc * 60 + n } ?: 0L

    // ---- parsers ----

    private fun parseTrack(item: JsonElement): Track? {
        val id = item.findFirst("videoId").str ?: return null
        val cols = item.at("flexColumns") as? JsonArray ?: return null
        fun runs(i: Int): List<String> =
            (cols.getOrNull(i).at("musicResponsiveListItemFlexColumnRenderer", "text", "runs") as? JsonArray)
                ?.mapNotNull { it.at("text").str }.orEmpty()
        val title = runs(0).firstOrNull() ?: return null
        val artist = runs(1).firstOrNull().orEmpty()
        val dur = parseDuration(
            item.at("fixedColumns", 0, "musicResponsiveListItemFixedColumnRenderer", "text", "runs", 0, "text").str
        )
        val thumb = lastThumb(item.at("thumbnail", "musicThumbnailRenderer", "thumbnail", "thumbnails"))
        val url = "https://www.youtube.com/watch?v=$id"
        return Track(url, title, artist, dur, thumb, Source.YOUTUBE, url)
    }

    private fun parsePlaylist(item: JsonElement): PlaylistRef? {
        val id = item.at("navigationEndpoint", "browseEndpoint", "browseId").str ?: return null
        if (!id.startsWith("VL") || id == "VLLM") return null // LM = liked songs, handled separately
        val title = item.at("title", "runs", 0, "text").str ?: return null
        val subtitle = (item.at("subtitle", "runs") as? JsonArray)
            ?.mapNotNull { it.at("text").str }?.joinToString("").orEmpty()
        val thumb = lastThumb(item.at("thumbnailRenderer", "musicThumbnailRenderer", "thumbnail", "thumbnails"))
        return PlaylistRef(id, title, subtitle, thumb)
    }

    // ---- public API ----

    /** Returns the account display name, or throws AuthException if the cookie isn't a signed-in session. */
    fun accountName(cookie: String): String? {
        val r = post(cookie, "account/account_menu")
        return r.findFirst("accountName").at("runs", 0, "text").str
            ?: throw AuthException("Not signed in. The cookie may be incomplete or expired.")
    }

    /** All tracks of a playlist, e.g. "VLLM" (Liked songs) or "VLPL…". Follows continuation pages. */
    fun tracks(cookie: String, browseId: String, maxPages: Int = 30): List<Track> {
        var resp = post(cookie, "browse", { put("browseId", browseId) })
        val out = mutableListOf<Track>()
        var lastToken: String? = null
        repeat(maxPages) {
            val shelves = resp.findAll("musicPlaylistShelfRenderer") + resp.findAll("musicPlaylistShelfContinuation")
            val scope: List<JsonElement> = shelves.ifEmpty { listOf(resp) }
            scope.flatMap { it.findAll("musicResponsiveListItemRenderer") }.mapNotNullTo(out) { parseTrack(it) }
            val token = resp.findFirst("nextContinuationData").at("continuation").str
                ?: resp.findFirst("continuationCommand").at("token").str
            if (token == null || token == lastToken) return out.distinctBy { it.id }
            lastToken = token
            resp = post(cookie, "browse", { put("continuation", token) }, "&ctoken=$token&continuation=$token&type=next")
        }
        return out.distinctBy { it.id }
    }

    fun playlists(cookie: String): List<PlaylistRef> =
        post(cookie, "browse", { put("browseId", "FEmusic_liked_playlists") })
            .findAll("musicTwoRowItemRenderer")
            .mapNotNull { parsePlaylist(it) }
            .distinctBy { it.id }

    fun setLike(cookie: String, videoId: String, like: Boolean) {
        post(cookie, if (like) "like/like" else "like/removelike", {
            put("target", buildJsonObject { put("videoId", videoId) })
        })
    }
}
