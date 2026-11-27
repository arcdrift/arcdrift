package dev.arcdrift.cli.ui

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.arcdrift.core.analysis.TarjanCycleDetector
import dev.arcdrift.core.codec.ArcMermaidParser
import dev.arcdrift.core.codec.ArcMermaidSerializer
import dev.arcdrift.core.contracts.ContractFederationManifest
import dev.arcdrift.core.contracts.PolyglotContractBridge
import dev.arcdrift.core.model.ArcGraph
import dev.arcdrift.core.model.BoundaryRule
import dev.arcdrift.core.model.NodeKind
import dev.arcdrift.core.refactoring.InMemoryGraphRefactoringAdapter
import dev.arcdrift.core.refactoring.RefactoringCommand
import dev.arcdrift.core.rules.ArcRuleEngine
import dev.arcdrift.core.scanner.ArcDriftIgnore
import dev.arcdrift.core.scanner.PolyglotRepoScanner
import dev.arcdrift.core.spi.ArcDriftSpiLoader
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Embedded 127.0.0.1 loopback HTTP server powering `arc ui` — an IDE-agnostic interactive
 * architecture explorer serving the bundled `arcdrift-webview` React Flow canvas to any
 * local web browser.
 */
class LocalBrowserUiServer(
    private val rootDir: File,
    private val explicitSpecFile: File? = null,
    private val upstreamManifestProvider: (projectName: String) -> ContractFederationManifest? = { null },
    private val requestedPort: Int = DEFAULT_PORT
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val stateLock = Any()
    private val sessionTokenBytes: ByteArray = ByteArray(16).also { SecureRandom().nextBytes(it) }
    val sessionToken: String = sessionTokenBytes.joinToString("") { "%02x".format(it) }

    @Volatile
    private var server: HttpServer? = null

    @Volatile
    private var executor: ExecutorService? = null

    @Volatile
    private var adapter: InMemoryGraphRefactoringAdapter? = null

    @Volatile
    private var currentGraph: ArcGraph = ArcGraph(projectName = rootDir.name.ifBlank { "project" })

    val actualPort: Int
        get() = server?.address?.port ?: requestedPort

    val baseUrl: String
        get() = "http://127.0.0.1:$actualPort"

    @Synchronized
    fun start(): String {
        if (server != null) return baseUrl
        refreshGraph()

        var createdServer: HttpServer? = null
        var createdExec: ExecutorService? = null
        try {
            val httpServer = HttpServer.create(InetSocketAddress("127.0.0.1", requestedPort), 0)
            createdServer = httpServer
            val exec = Executors.newCachedThreadPool { runnable ->
                Thread(runnable, "arcdrift-ui-server").apply { isDaemon = true }
            }
            createdExec = exec
            httpServer.executor = exec

            httpServer.createContext("/api/health") { exchange -> handleHealth(exchange) }
            httpServer.createContext("/api/graph") { exchange -> handleGraph(exchange) }
            httpServer.createContext("/api/message") { exchange -> handleMessage(exchange) }
            httpServer.createContext("/arcdrift-browser-bridge.js") { exchange -> handleBridgeScript(exchange) }
            httpServer.createContext("/") { exchange -> handleStaticAsset(exchange) }

            httpServer.start()
            server = httpServer
            executor = exec
            return baseUrl
        } catch (t: Throwable) {
            runCatching { createdServer?.stop(0) }
            runCatching { createdExec?.shutdownNow() }
            throw t
        }
    }

    @Synchronized
    fun stop() {
        runCatching { server?.stop(0) }
        runCatching { executor?.shutdownNow() }
        server = null
        executor = null
    }

    fun refreshGraph(): ArcGraph = synchronized(stateLock) {
        val scanned = scanAndAnnotateGraph(rootDir)
        currentGraph = scanned
        adapter = InMemoryGraphRefactoringAdapter(
            initialGraph = scanned,
            rootDir = rootDir,
            customScanner = { dir -> scanAndAnnotateGraph(dir) }
        )
        scanned
    }

    private fun scanAndAnnotateGraph(dir: File): ArcGraph {
        val scanned = PolyglotRepoScanner.scanRepository(dir, explicitSpecFile)
        val withOrgRules = ArcDriftSpiLoader.mergeOrgRules(scanned, dir)
        val ignoreConfig = ArcDriftIgnore.load(dir)
        val linked = PolyglotContractBridge.linkAndVerify(
            graph = withOrgRules,
            upstreamManifest = upstreamManifestProvider(withOrgRules.projectName),
            ignoreConfig = ignoreConfig
        ).enrichedGraph
        val ruleViolations = ArcRuleEngine.evaluate(linked)
        val withRuleAnnotations = ArcRuleEngine.annotateViolations(linked, ruleViolations)
        return TarjanCycleDetector.annotateGraphWithCycles(withRuleAnnotations)
    }

    private fun handleHealth(exchange: HttpExchange) {
        try {
            if (!rejectDisallowedHost(exchange)) return
            val method = exchange.requestMethod?.uppercase() ?: "GET"
            if (method != "GET" && method != "HEAD") {
                sendMethodNotAllowed(exchange, "GET, HEAD")
                return
            }
            val graph = currentGraph
            val payload = buildJsonObject {
                put("status", "ok")
                put("projectName", graph.projectName)
                put("nodes", graph.nodes.size)
                put("edges", graph.edges.size)
                put("rules", graph.rules.size)
            }
            sendJson(exchange, 200, json.encodeToString(payload), headOnly = method == "HEAD")
        } finally {
            runCatching { exchange.close() }
        }
    }

    private fun handleGraph(exchange: HttpExchange) {
        try {
            if (!rejectDisallowedHost(exchange)) return
            val method = exchange.requestMethod?.uppercase() ?: "GET"
            if (method != "GET" && method != "HEAD") {
                sendMethodNotAllowed(exchange, "GET, HEAD")
                return
            }
            val query = exchange.requestURI?.rawQuery.orEmpty()
            val shouldRefresh = !query.contains("refresh=false")
            val graph = if (shouldRefresh) refreshGraph() else currentGraph
            sendJson(exchange, 200, json.encodeToString(graph), headOnly = method == "HEAD")
        } catch (e: Exception) {
            runCatching {
                sendJson(
                    exchange,
                    500,
                    json.encodeToString(buildJsonObject { put("error", "Failed to scan repository: ${e.message ?: e.javaClass.simpleName}") })
                )
            }
        } finally {
            runCatching { exchange.close() }
        }
    }

    private fun handleMessage(exchange: HttpExchange) {
        try {
            if (!rejectDisallowedHost(exchange)) return
            val method = exchange.requestMethod?.uppercase() ?: "POST"
            if (method != "POST") {
                sendMethodNotAllowed(exchange, "POST")
                return
            }
            val originHeader = exchange.requestHeaders.getFirst("Origin")
            if (originHeader != null && !isAllowedOrigin(originHeader, actualPort)) {
                sendJson(
                    exchange,
                    403,
                    json.encodeToString(buildJsonObject { put("error", "Disallowed Origin header") })
                )
                return
            }
            val providedToken = exchange.requestHeaders.getFirst(CSRF_TOKEN_HEADER).orEmpty()
            if (!isValidToken(providedToken)) {
                sendJson(
                    exchange,
                    403,
                    json.encodeToString(buildJsonObject { put("error", "Missing or invalid $CSRF_TOKEN_HEADER") })
                )
                return
            }

            val bodyBytes = readLimitedBody(exchange, MAX_MESSAGE_BYTES)
            if (bodyBytes == null) {
                sendJson(
                    exchange,
                    413,
                    json.encodeToString(buildJsonObject { put("error", "Payload exceeds $MAX_MESSAGE_BYTES bytes") })
                )
                return
            }

            val rawBody = bodyBytes.toString(Charsets.UTF_8)
            val responseEnvelopes = processWebviewMessage(rawBody)
            sendJson(exchange, 200, json.encodeToString(responseEnvelopes))
        } catch (e: Exception) {
            val errorEnvelopes = buildJsonArray {
                add(notificationEnvelope("error", "Server error: ${e.message ?: e.javaClass.simpleName}"))
            }
            sendJson(exchange, 500, json.encodeToString(errorEnvelopes))
        } finally {
            runCatching { exchange.close() }
        }
    }

    internal fun processWebviewMessage(rawPayload: String): JsonElement {
        val obj = runCatching { json.parseToJsonElement(rawPayload) as? JsonObject }.getOrNull()
            ?: return buildJsonArray {
                add(notificationEnvelope("error", "Invalid JSON message payload."))
            }
        val msgType = obj["type"].asPrimitiveOrNull()?.contentOrNull
            ?: return buildJsonArray {
                add(notificationEnvelope("error", "Missing message 'type'."))
            }

        return when (msgType) {
            "REQUEST_INITIAL_GRAPH" -> {
                val graph = refreshGraph()
                buildJsonArray {
                    add(graphUpdatedEnvelope(graph))
                }
            }
            "SAVE_SPEC_RULE" -> handleSaveSpecRule(obj)
            "APPLY_REFACTORING" -> handleApplyRefactoring(obj)
            "NAVIGATE_TO_SOURCE" -> handleNavigateToSource(obj)
            "CHANGE_ZOOM_LEVEL" -> buildJsonArray { }
            "SAVE_AGENT_CONFIG", "FETCH_AGENT_MODELS" -> {
                buildJsonArray {
                    add(notificationEnvelope("info", AGENT_UNAVAILABLE_MESSAGE))
                }
            }
            "HANDOFF_TO_AGENT", "CANCEL_AGENT_RUN" -> {
                // Always emit a terminal AGENT_STEP so the webview never stays stuck in `agentRunning = true`
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("type", "AGENT_STEP")
                            put(
                                "step",
                                buildJsonObject {
                                    put("kind", if (msgType == "CANCEL_AGENT_RUN") "status" else "error")
                                    put("title", if (msgType == "CANCEL_AGENT_RUN") "Agent run stopped." else AGENT_UNAVAILABLE_MESSAGE)
                                    put("detail", "")
                                    put("turn", 0)
                                    put("maxTurns", 0)
                                    put("done", true)
                                    put("success", msgType == "CANCEL_AGENT_RUN")
                                }
                            )
                        }
                    )
                }
            }
            else -> buildJsonArray {
                add(notificationEnvelope("warning", "Unsupported browser bridge action: $msgType"))
            }
        }
    }

    private fun handleSaveSpecRule(obj: JsonObject): JsonElement {
        val ruleElement = obj["rule"]
            ?: return buildJsonArray { add(notificationEnvelope("error", "Missing 'rule' payload.")) }
        val rule = runCatching { json.decodeFromJsonElement<BoundaryRule>(ruleElement) }.getOrNull()
            ?: return buildJsonArray { add(notificationEnvelope("error", "Invalid boundary rule payload.")) }
        if (rule.id.isBlank() || rule.fromPattern.isBlank() || rule.toPattern.isBlank()) {
            return buildJsonArray {
                add(notificationEnvelope("error", "Boundary rule requires non-blank 'id', 'fromPattern', and 'toPattern'."))
            }
        }

        val (updatedGraph, targetSpecFile) = synchronized(stateLock) {
            val specFile = explicitSpecFile
                ?: PolyglotRepoScanner.findPrimarySpecFile(rootDir)
                ?: File(rootDir, "architecture.arc.mmd")
            val existingSpec = if (specFile.isFile) {
                ArcMermaidParser.parse(specFile.readText(Charsets.UTF_8))
            } else {
                ArcGraph(
                    projectName = currentGraph.projectName,
                    nodes = currentGraph.nodes.filter { it.kind == NodeKind.GHOST_SPEC }
                )
            }
            val mergedSpec = existingSpec.copy(
                rules = existingSpec.rules.filterNot { it.id == rule.id } + rule
            )
            writeTextAtomically(specFile, ArcMermaidSerializer.serialize(mergedSpec))
            val rescanned = refreshGraph()
            rescanned to specFile
        }
        val relSpec = runCatching { targetSpecFile.relativeTo(rootDir).invariantSeparatorsPath }
            .getOrDefault(targetSpecFile.name)
        return buildJsonArray {
            add(graphUpdatedEnvelope(updatedGraph))
            add(notificationEnvelope("info", "Saved boundary rule '${rule.id}' to $relSpec."))
        }
    }

    private fun ensureAdapter(): InMemoryGraphRefactoringAdapter {
        adapter?.let { return it }
        val scanned = scanAndAnnotateGraph(rootDir)
        currentGraph = scanned
        val created = InMemoryGraphRefactoringAdapter(
            initialGraph = scanned,
            rootDir = rootDir,
            customScanner = { dir -> scanAndAnnotateGraph(dir) }
        )
        adapter = created
        return created
    }

    private fun handleApplyRefactoring(obj: JsonObject): JsonElement {
        val previewOnly = obj["previewOnly"].asPrimitiveOrNull()?.booleanOrNull ?: false
        val cmdElement = obj["command"]
            ?: return buildJsonArray { add(notificationEnvelope("error", "Missing 'command' payload.")) }
        val command = runCatching { json.decodeFromJsonElement<RefactoringCommand>(cmdElement) }.getOrNull()
            ?: return buildJsonArray { add(notificationEnvelope("error", "Invalid refactoring command payload.")) }

        return synchronized(stateLock) {
            val currentAdapter = ensureAdapter()
            val result = currentAdapter.execute(command, previewOnly = previewOnly)
            if (!result.success) {
                buildJsonArray {
                    add(notificationEnvelope("error", result.errorMessage ?: result.summary))
                    add(graphUpdatedEnvelope(currentGraph))
                }
            } else if (!previewOnly) {
                val rescanned = refreshGraph()
                buildJsonArray {
                    add(graphUpdatedEnvelope(rescanned))
                    add(notificationEnvelope("info", result.summary))
                }
            } else {
                val previewGraph = currentAdapter.currentGraph
                val violations = ArcRuleEngine.evaluate(previewGraph)
                val annotated = TarjanCycleDetector.annotateGraphWithCycles(
                    ArcRuleEngine.annotateViolations(previewGraph, violations)
                )
                buildJsonArray {
                    add(graphUpdatedEnvelope(annotated))
                    add(notificationEnvelope("info", "Preview: ${result.summary}"))
                }
            }
        }
    }

    private fun handleNavigateToSource(obj: JsonObject): JsonElement {
        val nodeId = obj["nodeId"].asPrimitiveOrNull()?.contentOrNull
        val explicitPath = obj["filePath"].asPrimitiveOrNull()?.contentOrNull
        val explicitLine = obj["line"].asPrimitiveOrNull()?.intOrNull
        val node = nodeId?.let { id -> currentGraph.nodes.firstOrNull { it.id == id } }
        val filePath = explicitPath ?: node?.source?.filePath
        val line = explicitLine ?: node?.source?.startLine ?: 1
        val message = if (filePath != null) {
            "Source location: $filePath:$line"
        } else {
            "No source file is associated with '${nodeId ?: "selection"}'."
        }
        return buildJsonArray {
            add(notificationEnvelope("info", message))
        }
    }

    private fun handleBridgeScript(exchange: HttpExchange) {
        try {
            if (!rejectDisallowedHost(exchange)) return
            val method = exchange.requestMethod?.uppercase() ?: "GET"
            if (method != "GET" && method != "HEAD") {
                sendMethodNotAllowed(exchange, "GET, HEAD")
                return
            }
            val script = buildBrowserBridgeScript(sessionToken)
            val bytes = script.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.set("Content-Type", "text/javascript; charset=utf-8")
            setSecurityHeaders(exchange)
            if (method == "HEAD") {
                exchange.responseHeaders.set("Content-Length", bytes.size.toString())
                exchange.sendResponseHeaders(200, -1)
            } else {
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
        } finally {
            runCatching { exchange.close() }
        }
    }

    private fun handleStaticAsset(exchange: HttpExchange) {
        try {
            if (!rejectDisallowedHost(exchange)) return
            val method = exchange.requestMethod?.uppercase() ?: "GET"
            if (method != "GET" && method != "HEAD") {
                sendMethodNotAllowed(exchange, "GET, HEAD")
                return
            }
            val resourcePath = sanitizeWebviewResourcePath(exchange.requestURI?.rawPath)
            val stream = resourcePath?.let { javaClass.getResourceAsStream(it) }
            if (resourcePath != null && stream != null) {
                val rawBytes = stream.use { it.readBytes() }
                val bytes = if (resourcePath == "/webview/index.html") {
                    injectBridgeScriptTag(rawBytes.toString(Charsets.UTF_8)).toByteArray(Charsets.UTF_8)
                } else {
                    rawBytes
                }
                exchange.responseHeaders.set("Content-Type", guessMimeType(resourcePath))
                setSecurityHeaders(exchange)
                if (method == "HEAD") {
                    exchange.responseHeaders.set("Content-Length", bytes.size.toString())
                    exchange.sendResponseHeaders(200, -1)
                } else {
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                }
            } else {
                val msg = "Not found".toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.set("Content-Type", "text/plain; charset=utf-8")
                setSecurityHeaders(exchange)
                if (method == "HEAD") {
                    exchange.sendResponseHeaders(404, -1)
                } else {
                    exchange.sendResponseHeaders(404, msg.size.toLong())
                    exchange.responseBody.use { it.write(msg) }
                }
            }
        } finally {
            runCatching { exchange.close() }
        }
    }

    private fun graphUpdatedEnvelope(graph: ArcGraph): JsonObject = buildJsonObject {
        put("type", "GRAPH_UPDATED")
        put("graph", json.encodeToJsonElement(graph))
    }

    private fun notificationEnvelope(level: String, message: String): JsonObject = buildJsonObject {
        put("type", "NOTIFICATION")
        put("level", level)
        put("message", message)
    }

    private fun isValidToken(provided: String): Boolean {
        if (provided.isEmpty()) return false
        return MessageDigest.isEqual(
            provided.toByteArray(Charsets.UTF_8),
            sessionToken.toByteArray(Charsets.UTF_8)
        )
    }

    private fun readLimitedBody(exchange: HttpExchange, maxBytes: Int): ByteArray? {
        val buf = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(4096)
        exchange.requestBody.use { input ->
            while (true) {
                val read = input.read(chunk)
                if (read < 0) break
                if (buf.size() + read > maxBytes) return null
                buf.write(chunk, 0, read)
            }
        }
        return buf.toByteArray()
    }

    private fun sendJson(exchange: HttpExchange, status: Int, jsonBody: String, headOnly: Boolean = false) {
        val bytes = jsonBody.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
        setSecurityHeaders(exchange)
        if (headOnly) {
            exchange.responseHeaders.set("Content-Length", bytes.size.toString())
            exchange.sendResponseHeaders(status, -1)
        } else {
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
    }

    private fun sendMethodNotAllowed(exchange: HttpExchange, allow: String) {
        exchange.responseHeaders.set("Allow", allow)
        setSecurityHeaders(exchange)
        exchange.sendResponseHeaders(405, -1)
    }

    private fun rejectDisallowedHost(exchange: HttpExchange): Boolean {
        val hostHeader = exchange.requestHeaders.getFirst("Host")
        if (hostHeader != null && !isAllowedHost(hostHeader, actualPort)) {
            sendJson(
                exchange,
                403,
                json.encodeToString(buildJsonObject { put("error", "Disallowed Host header") })
            )
            return false
        }
        return true
    }

    private fun writeTextAtomically(target: File, content: String) {
        val parent = target.absoluteFile.parentFile
        parent?.mkdirs()
        val tmp = Files.createTempFile(parent.toPath(), ".${target.name}.", ".tmp").toFile()
        try {
            tmp.writeText(content, Charsets.UTF_8)
            runCatching {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            }.onFailure {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    private fun setSecurityHeaders(exchange: HttpExchange) {
        exchange.responseHeaders.set("X-Content-Type-Options", "nosniff")
        exchange.responseHeaders.set("Cache-Control", "no-store")
        exchange.responseHeaders.set("X-Frame-Options", "DENY")
        exchange.responseHeaders.set("Cross-Origin-Resource-Policy", "same-origin")
    }

    private fun JsonElement?.asPrimitiveOrNull(): JsonPrimitive? = this as? JsonPrimitive

    companion object {
        const val DEFAULT_PORT = 4747
        const val CSRF_TOKEN_HEADER = "X-ArcDrift-Token"
        private const val MAX_MESSAGE_BYTES = 2 * 1024 * 1024
        internal const val AGENT_UNAVAILABLE_MESSAGE =
            "Autonomous agent runs are triggered via 'arc mcp' in Claude Code, Cursor, or Windsurf, or inside IntelliJ IDEA."

        internal fun isAllowedHost(hostHeader: String, port: Int): Boolean {
            val normalized = hostHeader.trim().lowercase()
            return normalized in setOf(
                "127.0.0.1",
                "127.0.0.1:$port",
                "localhost",
                "localhost:$port",
                "[::1]",
                "[::1]:$port"
            )
        }

        internal fun isAllowedOrigin(originHeader: String, port: Int): Boolean {
            val normalized = originHeader.trim().lowercase().trimEnd('/')
            return normalized in setOf(
                "http://127.0.0.1:$port",
                "http://localhost:$port",
                "http://[::1]:$port"
            )
        }

        internal fun sanitizeWebviewResourcePath(rawPath: String?): String? {
            val path = rawPath.orEmpty().substringBefore('?').substringBefore('#').trim().ifEmpty { "/index.html" }
            if (path.contains('\\') ||
                path.contains('\u0000') ||
                path.contains("%00") ||
                path.contains("%2e", ignoreCase = true) ||
                path.contains("%2f", ignoreCase = true) ||
                path.contains("%5c", ignoreCase = true)
            ) {
                return null
            }
            val normalizedSegments = mutableListOf<String>()
            for (segment in path.split('/')) {
                if (segment.isEmpty() || segment == ".") continue
                if (segment == "..") return null
                normalizedSegments.add(segment)
            }
            val cleanRel = if (normalizedSegments.isEmpty()) "index.html" else normalizedSegments.joinToString("/")
            return "/webview/$cleanRel"
        }

        internal fun injectBridgeScriptTag(html: String): String {
            val tag = """<script src="/arcdrift-browser-bridge.js"></script>"""
            val headClose = html.indexOf("</head>", ignoreCase = true)
            return if (headClose >= 0) {
                html.substring(0, headClose) + "    $tag\n  " + html.substring(headClose)
            } else {
                "$tag\n$html"
            }
        }

        internal fun guessMimeType(path: String): String = when {
            path.endsWith(".html") -> "text/html; charset=utf-8"
            path.endsWith(".js") || path.endsWith(".mjs") -> "text/javascript; charset=utf-8"
            path.endsWith(".css") -> "text/css; charset=utf-8"
            path.endsWith(".svg") -> "image/svg+xml"
            path.endsWith(".json") -> "application/json; charset=utf-8"
            path.endsWith(".png") -> "image/png"
            path.endsWith(".woff2") -> "font/woff2"
            else -> "application/octet-stream"
        }

        internal fun buildBrowserBridgeScript(token: String): String = """
            (function() {
              const CSRF_TOKEN = "$token";
              function dispatchInbound(msg) {
                if (typeof window.__arcdriftReceiveMessage === 'function') {
                  window.__arcdriftReceiveMessage(msg);
                } else {
                  window.__arcdriftPendingInbound = window.__arcdriftPendingInbound || [];
                  window.__arcdriftPendingInbound.push(msg);
                }
              }
              window.__arcdriftPostMessage = function(payloadStr) {
                fetch('/api/message', {
                  method: 'POST',
                  headers: {
                    'Content-Type': 'application/json',
                    'X-ArcDrift-Token': CSRF_TOKEN
                  },
                  body: payloadStr
                })
                  .then(function(resp) {
                    if (!resp.ok) throw new Error('HTTP ' + resp.status);
                    return resp.json();
                  })
                  .then(function(envelopes) {
                    if (Array.isArray(envelopes)) {
                      envelopes.forEach(dispatchInbound);
                    }
                  })
                  .catch(function(err) {
                    dispatchInbound({
                      type: 'NOTIFICATION',
                      level: 'error',
                      message: 'ArcDrift local server error: ' + (err && err.message ? err.message : String(err))
                    });
                  });
              };
              if (Array.isArray(window.__arcdriftPendingOutbound)) {
                while (window.__arcdriftPendingOutbound.length > 0) {
                  window.__arcdriftPostMessage(window.__arcdriftPendingOutbound.shift());
                }
              }
              // The React canvas requests the initial graph itself on mount; the bridge must not trigger
              // duplicate repository scans or discard unsaved Sandbox Preview / Ghost Spec state on tab switches.
            })();
        """.trimIndent()

        fun openInSystemBrowser(url: String): Boolean {
            val desktopResult = runCatching {
                if (java.awt.Desktop.isDesktopSupported()) {
                    val desktop = java.awt.Desktop.getDesktop()
                    if (desktop.isSupported(java.awt.Desktop.Action.BROWSE)) {
                        desktop.browse(java.net.URI(url))
                        return true
                    }
                }
                false
            }.getOrDefault(false)
            if (desktopResult) return true

            val os = System.getProperty("os.name").orEmpty().lowercase()
            val cmd = when {
                os.contains("mac") -> listOf("open", url)
                os.contains("win") -> listOf("cmd", "/c", "start", "", url)
                else -> listOf("xdg-open", url)
            }
            return runCatching {
                ProcessBuilder(cmd)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
                true
            }.getOrDefault(false)
        }
    }
}
