import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class PlaylistRef(val id: String, val title: String, val subtitle: String, val thumb: String?)

@Serializable
data class Session(val cookie: String, val name: String?)

@Serializable
data class LibraryCache(val liked: List<Track>, val playlists: List<PlaylistRef>)

/** Saves small JSON files in ~/.tunedesk (session, library cache, local file list). */
object Store {
    @PublishedApi internal val json = Json { ignoreUnknownKeys = true }
    @PublishedApi internal val dir: File = File(System.getProperty("user.home"), ".tunedesk").also { it.mkdirs() }

    inline fun <reified T> read(name: String): T? =
        runCatching { json.decodeFromString<T>(File(dir, name).readText()) }.getOrNull()

    inline fun <reified T> write(name: String, value: T) {
        runCatching { File(dir, name).writeText(json.encodeToString(value)) }
    }

    fun delete(name: String) { File(dir, name).delete() }
}
