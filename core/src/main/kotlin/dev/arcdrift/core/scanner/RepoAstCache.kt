package dev.arcdrift.core.scanner

import dev.arcdrift.core.ast.JvmParsedFileAst
import dev.arcdrift.core.ast.PyParsedFileAst
import dev.arcdrift.core.ast.TsParsedFileAst
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Snapshot of cache hits and misses for telemetry and test verification.
 */
data class AstCacheStats(
    val hits: Long,
    val misses: Long,
    val entries: Int
)

/**
 * Thread-safe SHA-256 incremental per-file AST cache supporting both bounded in-memory caching
 * (always active for sub-second `arc mcp` / `arc ui` / repeated scans) and optional disk persistence
 * (enabled via `ARCDRIFT_CACHE_DIR` or an explicit `diskCacheDir`).
 */
class RepoAstCache(
    private val maxEntries: Int = 16_384
) {
    init {
        require(maxEntries > 0) { "maxEntries must be positive, got $maxEntries" }
    }

    @Serializable
    internal data class DiskCacheEnvelope(
        val schemaVersion: String = SCHEMA_VERSION,
        val jvmEntries: Map<String, JvmParsedFileAst> = emptyMap(),
        val tsEntries: Map<String, TsParsedFileAst> = emptyMap(),
        val pyEntries: Map<String, PyParsedFileAst> = emptyMap()
    )

    private val jvmCache = ConcurrentHashMap<String, JvmParsedFileAst>()
    private val tsCache = ConcurrentHashMap<String, TsParsedFileAst>()
    private val pyCache = ConcurrentHashMap<String, PyParsedFileAst>()
    private val loadedDiskDirs = ConcurrentHashMap.newKeySet<String>()
    private val diskLoadLock = Any()
    private val evictLock = Any()
    private val hitCounter = AtomicLong(0)
    private val missCounter = AtomicLong(0)

    fun getOrParseJvm(
        language: String,
        defaultFileStem: String,
        source: String,
        parser: () -> JvmParsedFileAst
    ): JvmParsedFileAst {
        val key = buildKey(language, defaultFileStem, source)
        jvmCache[key]?.let {
            hitCounter.incrementAndGet()
            return it
        }
        missCounter.incrementAndGet()
        val parsed = parser()
        evictIfNeeded()
        jvmCache[key] = parsed
        return parsed
    }

    fun getOrParseTs(
        isTsx: Boolean,
        source: String,
        parser: () -> TsParsedFileAst
    ): TsParsedFileAst {
        val key = buildKey(if (isTsx) "tsx" else "ts", "", source)
        tsCache[key]?.let {
            hitCounter.incrementAndGet()
            return it
        }
        missCounter.incrementAndGet()
        val parsed = parser()
        evictIfNeeded()
        tsCache[key] = parsed
        return parsed
    }

    fun getOrParsePy(
        source: String,
        parser: () -> PyParsedFileAst
    ): PyParsedFileAst {
        val key = buildKey("py", "", source)
        pyCache[key]?.let {
            hitCounter.incrementAndGet()
            return it
        }
        missCounter.incrementAndGet()
        val parsed = parser()
        evictIfNeeded()
        pyCache[key] = parsed
        return parsed
    }

    fun loadFromDisk(cacheDir: File) {
        val canonicalDir = runCatching { cacheDir.canonicalPath }.getOrDefault(cacheDir.absolutePath)
        val file = File(cacheDir, CACHE_FILE_NAME)
        if (!file.isFile) return
        if (canonicalDir in loadedDiskDirs) return
        // Serialize loads so concurrent callers never observe a "loaded" marker before entries are populated,
        // and only mark the directory as loaded after a successful decode (transient failures can be retried).
        synchronized(diskLoadLock) {
            if (canonicalDir in loadedDiskDirs) return
            val envelope = runCatching {
                json.decodeFromString<DiskCacheEnvelope>(file.readText(Charsets.UTF_8))
            }.getOrNull() ?: return
            if (envelope.schemaVersion != SCHEMA_VERSION) {
                loadedDiskDirs.add(canonicalDir)
                return
            }
            var remaining = maxEntries - (jvmCache.size + tsCache.size + pyCache.size)
            for ((k, v) in envelope.jvmEntries) {
                if (remaining <= 0) break
                if (jvmCache.putIfAbsent(k, v) == null) remaining--
            }
            for ((k, v) in envelope.tsEntries) {
                if (remaining <= 0) break
                if (tsCache.putIfAbsent(k, v) == null) remaining--
            }
            for ((k, v) in envelope.pyEntries) {
                if (remaining <= 0) break
                if (pyCache.putIfAbsent(k, v) == null) remaining--
            }
            loadedDiskDirs.add(canonicalDir)
        }
    }

    fun saveToDisk(cacheDir: File) {
        runCatching {
            cacheDir.mkdirs()
            val target = File(cacheDir, CACHE_FILE_NAME)
            val tmp = Files.createTempFile(cacheDir.toPath(), ".$CACHE_FILE_NAME.", ".tmp").toFile()
            val envelope = DiskCacheEnvelope(
                schemaVersion = SCHEMA_VERSION,
                jvmEntries = HashMap(jvmCache),
                tsEntries = HashMap(tsCache),
                pyEntries = HashMap(pyCache)
            )
            try {
                tmp.writeText(json.encodeToString(envelope), Charsets.UTF_8)
                runCatching {
                    Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                }.onFailure {
                    Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                if (tmp.exists()) tmp.delete()
            }
        }
    }

    fun stats(): AstCacheStats = AstCacheStats(
        hits = hitCounter.get(),
        misses = missCounter.get(),
        entries = jvmCache.size + tsCache.size + pyCache.size
    )

    fun clear() {
        jvmCache.clear()
        tsCache.clear()
        pyCache.clear()
        loadedDiskDirs.clear()
        hitCounter.set(0)
        missCounter.set(0)
    }

    private fun evictIfNeeded() {
        val total = jvmCache.size + tsCache.size + pyCache.size
        if (total >= maxEntries) {
            synchronized(evictLock) {
                val recheck = jvmCache.size + tsCache.size + pyCache.size
                if (recheck >= maxEntries) {
                    jvmCache.clear()
                    tsCache.clear()
                    pyCache.clear()
                }
            }
        }
    }

    companion object {
        const val SCHEMA_VERSION = "ast-cache-v2-tskt"
        const val CACHE_FILE_NAME = "arcdrift-ast-cache.json"

        val global: RepoAstCache = RepoAstCache()

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        private val THREAD_LOCAL_SHA256: ThreadLocal<MessageDigest> = ThreadLocal.withInitial {
            MessageDigest.getInstance("SHA-256")
        }

        internal fun sha256Hex(text: String): String {
            val md = THREAD_LOCAL_SHA256.get()
            md.reset()
            val digest = md.digest(text.toByteArray(Charsets.UTF_8))
            val chars = CharArray(digest.size * 2)
            val hex = "0123456789abcdef"
            for (i in digest.indices) {
                val b = digest[i].toInt() and 0xFF
                chars[i * 2] = hex[b ushr 4]
                chars[i * 2 + 1] = hex[b and 0x0F]
            }
            return String(chars)
        }

        private fun buildKey(language: String, stem: String, source: String): String =
            "$SCHEMA_VERSION:$language:$stem:${sha256Hex(source)}"
    }
}
