package com.angelminga.iconifypreview

import com.google.gson.JsonParser
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.IconLoader
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.io.HttpRequests
import com.intellij.util.messages.Topic
import java.awt.Color
import java.io.IOException
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.swing.Icon

private val LOG = logger<IconifyClient>()

fun interface IconifyListener {
    /** Some icons, collections or icon sets finished loading (or settings changed). */
    fun iconsChanged()
}

enum class IconStatus { EXISTS, MISSING, UNKNOWN }

/**
 * Downloads icons and icon lists from the Iconify API and keeps them on disk, so each one is fetched once and
 * previews work offline afterwards. Lookups never block: [icon], [prefixes] and [iconSet] return null and start a
 * download, then publish [TOPIC] (and [SETS_TOPIC] for lists, which change which names are valid).
 *
 * Disk layout under `<system dir>/iconify-preview/`:
 * - `collections.tsv`: known prefixes and their titles, refreshed weekly.
 * - `sets/<prefix>.json`: every icon name of a set, as served by `/collection`, refreshed weekly.
 * - `svg/<prefix>/<name>.svg`: the icon as served (16 px high, `currentColor` fills).
 * - `render/<rrggbb>/<prefix>/<name>.svg`: the same with `currentColor` replaced by the editor's text color, because
 *   [IconLoader] only reads SVG from a URL.
 */
@Service(Service.Level.APP)
class IconifyClient : Disposable {

    private sealed interface IconState
    private data object Loading : IconState
    private data object Missing : IconState
    private class Failed(val retryAt: Long) : IconState
    private class Loaded(val icon: Icon) : IconState

    private val executor = AppExecutorUtil.createBoundedApplicationPoolExecutor("Iconify Preview", 4)
    private val cacheDir: Path = PathManager.getSystemDir().resolve("iconify-preview")

    // Keyed by "prefix:name#rrggbb" for rendered icons and by "prefix:name" for icons the API does not have.
    private val icons = ConcurrentHashMap<String, IconState>()

    private val collections = Retrying<Map<String, String>>()
    private val sets = ConcurrentHashMap<String, Retrying<IconSet>>()
    private val searches = ConcurrentHashMap<String, CompletableFuture<List<String>>>()

    /** Known icon set prefixes mapped to their titles, or null while the list loads. */
    fun prefixes(): Map<String, String>? = prefixesFuture().getNow(null)

    fun prefixesFuture(): CompletableFuture<Map<String, String>?> = collections.get(executor, ::loadCollections) {
        publish(SETS_TOPIC)
        publish(TOPIC)
    }

    /** Every name of an icon set, or null while it loads. */
    fun iconSet(prefix: String): IconSet? = iconSetFuture(prefix).getNow(null)

    fun iconSetFuture(prefix: String): CompletableFuture<IconSet?> =
        sets.computeIfAbsent(prefix) { Retrying() }.get(executor, { loadIconSet(prefix) }) { publish(SETS_TOPIC) }

    /** Whether [match] names a real icon. UNKNOWN while lists load, or when the prefix is not an icon set at all. */
    fun status(match: IconMatch): IconStatus {
        val known = prefixes() ?: return IconStatus.UNKNOWN
        if (match.prefix !in known) return IconStatus.UNKNOWN
        val set = iconSet(match.prefix)
            ?: return if (icons[match.id] === Missing) IconStatus.MISSING else IconStatus.UNKNOWN
        return if (match.name in set.all) IconStatus.EXISTS else IconStatus.MISSING
    }

    /** Full icon ids (`prefix:name`) matching a search word, across all sets. */
    fun search(query: String): CompletableFuture<List<String>> {
        if (searches.size > MAX_CACHED_SEARCHES) searches.clear()
        return searches.computeIfAbsent(query) {
            CompletableFuture.supplyAsync({
                val url = "${apiUrl()}/search?query=${URLEncoder.encode(query, Charsets.UTF_8)}&limit=$SEARCH_LIMIT"
                JsonParser.parseString(request(url)).asJsonObject.getAsJsonArray("icons")?.map { it.asString }.orEmpty()
            }, executor).exceptionally { e ->
                LOG.debug("Iconify search failed for $query", e)
                searches.remove(query)
                emptyList()
            }
        }
    }

    /** The icon drawn in [color], or null while it loads, if it doesn't exist, or if the API can't be reached. */
    fun icon(match: IconMatch, color: Color): Icon? {
        val known = prefixes() ?: return null
        if (match.prefix !in known || icons[match.id] === Missing) return null

        val hex = hex(color)
        val key = "${match.id}#$hex"
        val started = when (val state = icons[key]) {
            is Loaded -> return state.icon
            Loading, Missing -> return null
            is Failed -> System.currentTimeMillis() >= state.retryAt && icons.replace(key, state, Loading)
            null -> icons.putIfAbsent(key, Loading) == null
        }
        if (started) {
            executor.execute {
                icons[key] = load(match, hex)
                if (icons[key] is Loaded) publish(TOPIC)
            }
        }
        return null
    }

    /** Like [icon], but downloads right away on the calling thread. For background renderers such as completion. */
    fun iconNow(match: IconMatch, color: Color): Icon? {
        val key = "${match.id}#${hex(color)}"
        (icons[key] as? Loaded)?.let { return it.icon }
        if (icons[match.id] === Missing) return null
        val state = load(match, hex(color))
        if (state is Loaded) icons[key] = state
        return (state as? Loaded)?.icon
    }

    /** Forgets everything loaded in memory (the disk cache stays), e.g. after the API URL changes. */
    fun reset() {
        icons.clear()
        collections.clear()
        sets.clear()
        searches.clear()
        publish(SETS_TOPIC)
        publish(TOPIC)
    }

    private fun load(match: IconMatch, hex: String): IconState = try {
        val svg = svg(match)
        if (svg == null) {
            icons[match.id] = Missing
            Missing
        } else {
            Loaded(render(match, svg, hex))
        }
    } catch (e: IOException) {
        LOG.debug("Could not load ${match.id}", e)
        Failed(System.currentTimeMillis() + RETRY_MS)
    }

    /** The icon's SVG, from the disk cache or the API; null if the API doesn't have it. */
    private fun svg(match: IconMatch): String? {
        val file = cacheDir.resolve("svg").resolve(match.prefix).resolve("${match.name}.svg")
        if (Files.isRegularFile(file)) return Files.readString(file)

        val svg = try {
            request("${apiUrl()}/${match.prefix}/${match.name}.svg?height=16")
        } catch (e: HttpRequests.HttpStatusException) {
            if (e.statusCode == 404) return null
            throw e
        }
        if (!svg.trimStart().startsWith("<svg")) return null
        write(file, svg)
        return svg
    }

    private fun render(match: IconMatch, svg: String, hex: String): Icon {
        val file = cacheDir.resolve("render").resolve(hex).resolve(match.prefix).resolve("${match.name}.svg")
        if (!Files.isRegularFile(file)) write(file, svg.replace("currentColor", "#$hex"))
        return IconLoader.findIcon(file.toUri().toURL()) ?: throw IOException("Could not read $file")
    }

    private fun loadCollections(): Map<String, String> {
        val text = cachedOrFetched(cacheDir.resolve("collections.tsv"), "${apiUrl()}/collections") { json ->
            JsonParser.parseString(json).asJsonObject.entrySet().joinToString("\n") { (prefix, info) ->
                val title = info.takeIf { it.isJsonObject }?.asJsonObject?.get("name")?.asString.orEmpty()
                "$prefix\t$title"
            }
        }
        return text.lineSequence().filter { it.isNotBlank() }.associate { line ->
            line.substringBefore('\t') to line.substringAfter('\t', "")
        }
    }

    private fun loadIconSet(prefix: String): IconSet =
        IconSet.parse(cachedOrFetched(cacheDir.resolve("sets").resolve("$prefix.json"), "${apiUrl()}/collection?prefix=$prefix") { it })

    /** A weekly-refreshed file: fresh from disk, else from the API (converted), else stale from disk when offline. */
    private fun cachedOrFetched(file: Path, url: String, convert: (String) -> String): String {
        val cached = Files.isRegularFile(file)
        if (cached && System.currentTimeMillis() - Files.getLastModifiedTime(file).toMillis() < LIST_MAX_AGE_MS) {
            return Files.readString(file)
        }
        return try {
            convert(request(url)).also { write(file, it) }
        } catch (e: IOException) {
            // Offline: a stale list is better than none.
            if (cached) Files.readString(file) else throw e
        }
    }

    private fun apiUrl() = IconifyPreviewSettings.getInstance().apiUrl

    private fun request(url: String): String =
        HttpRequests.request(url)
            .productNameAsUserAgent()
            .connectTimeout(TIMEOUT_MS)
            .readTimeout(TIMEOUT_MS)
            .readString()

    private fun write(file: Path, text: String) {
        Files.createDirectories(file.parent)
        // Write then move, so a concurrent reader never sees half a file.
        val tmp = Files.createTempFile(file.parent, file.fileName.toString(), ".tmp")
        Files.writeString(tmp, text)
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun publish(topic: Topic<IconifyListener>) {
        ApplicationManager.getApplication().messageBus.syncPublisher(topic).iconsChanged()
    }

    private fun hex(color: Color) = "%06x".format(color.rgb and 0xFFFFFF)

    /** A value loaded once in the background; after a failure, the next request after [RETRY_MS] tries again. */
    private class Retrying<T : Any> {
        @Volatile private var future: CompletableFuture<T?>? = null
        @Volatile private var retryAt = 0L

        @Synchronized
        fun get(executor: java.util.concurrent.Executor, load: () -> T, onLoaded: () -> Unit): CompletableFuture<T?> {
            future?.let { current ->
                val failed = current.isDone && current.getNow(null) == null
                if (!failed || System.currentTimeMillis() < retryAt) return current
            }
            return CompletableFuture.supplyAsync({
                try {
                    load()
                } catch (e: Exception) {
                    LOG.info("Could not load an Iconify list", e)
                    retryAt = System.currentTimeMillis() + RETRY_MS
                    null
                }
            }, executor).also { created ->
                future = created
                created.thenAccept { if (it != null) onLoaded() }
            }
        }

        @Synchronized
        fun clear() {
            future = null
            retryAt = 0
        }
    }

    // Parent of every editor preview, so unloading the plugin removes all inlays.
    override fun dispose() = Unit

    companion object {
        /** Icons became available: previews should look again. */
        @Topic.AppLevel
        val TOPIC: Topic<IconifyListener> = Topic.create("Iconify icons changed", IconifyListener::class.java)

        /** Collection or icon set lists loaded: which names are valid may have changed. */
        @Topic.AppLevel
        val SETS_TOPIC: Topic<IconifyListener> = Topic.create("Iconify icon sets changed", IconifyListener::class.java)

        private const val TIMEOUT_MS = 10_000
        private const val SEARCH_LIMIT = 64
        private const val MAX_CACHED_SEARCHES = 200
        private val RETRY_MS = TimeUnit.MINUTES.toMillis(1)
        private val LIST_MAX_AGE_MS = TimeUnit.DAYS.toMillis(7)

        fun getInstance(): IconifyClient = service()

        /** The icon's page on the Iconify website. */
        fun pageUrl(match: IconMatch): String = "https://icon-sets.iconify.design/${match.prefix}/${match.name}/"
    }
}
