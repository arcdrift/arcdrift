package dev.arcdrift.intellij.ui

import com.sun.net.httpserver.HttpServer
import org.cef.callback.CefCallback
import org.cef.handler.CefResourceHandlerAdapter
import org.cef.misc.IntRef
import org.cef.misc.StringRef
import org.cef.network.CefRequest
import org.cef.network.CefResponse
import java.net.InetSocketAddress

internal fun guessWebviewMimeType(path: String, includeUtf8Charset: Boolean = false): String {
    val suffix = if (includeUtf8Charset) "; charset=utf-8" else ""
    return when {
        path.endsWith(".html") -> "text/html$suffix"
        path.endsWith(".js") || path.endsWith(".mjs") -> "text/javascript$suffix"
        path.endsWith(".css") -> "text/css$suffix"
        path.endsWith(".svg") -> "image/svg+xml"
        path.endsWith(".json") -> "application/json"
        path.endsWith(".png") -> "image/png"
        else -> "application/octet-stream"
    }
}

/**
 * Serves `/webview/` assets from a fast 127.0.0.1 loopback server so Out-of-Process JCEF
 * never stalls on `.local` mDNS DNS lookups or async CefRequestHandler registration.
 */
internal object WebviewLoopbackServer {
    @Volatile
    private var baseUrl: String? = null
    @Volatile
    private var serverInstance: HttpServer? = null
    @Volatile
    private var executorInstance: java.util.concurrent.ExecutorService? = null
    @Volatile
    private var shutdownRegistered: Boolean = false

    fun sanitizeWebviewResourcePath(rawPath: String?): String? {
        val path = rawPath.orEmpty().substringBefore('?').substringBefore('#').trim().ifEmpty { "/index.html" }
        if (path.contains('\\') || path.contains('\u0000')) return null
        val normalizedSegments = mutableListOf<String>()
        for (segment in path.split('/')) {
            if (segment.isEmpty() || segment == ".") continue
            if (segment == "..") return null
            normalizedSegments.add(segment)
        }
        val cleanRel = if (normalizedSegments.isEmpty()) "index.html" else normalizedSegments.joinToString("/")
        return "/webview/$cleanRel"
    }

    @Synchronized
    fun ensureStarted(): String? {
        baseUrl?.let { return "$it/index.html" }
        var createdServer: HttpServer? = null
        var createdExec: java.util.concurrent.ExecutorService? = null
        return try {
            val server = runCatching { HttpServer.create(InetSocketAddress("127.0.0.1", 49281), 0) }
                .getOrElse { HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0) }
            createdServer = server
            val exec = java.util.concurrent.Executors.newCachedThreadPool { r ->
                Thread(r, "arcdrift-webview-loopback").apply { isDaemon = true }
            }
            createdExec = exec
            server.executor = exec
            server.createContext("/") { exchange ->
                try {
                    val method = exchange.requestMethod?.uppercase() ?: "GET"
                    if (method != "GET" && method != "HEAD") {
                        exchange.responseHeaders.set("Allow", "GET, HEAD")
                        exchange.responseHeaders.set("X-Content-Type-Options", "nosniff")
                        exchange.sendResponseHeaders(405, -1)
                        return@createContext
                    }
                    val resourcePath = sanitizeWebviewResourcePath(exchange.requestURI?.path)
                    val stream = resourcePath?.let { ClasspathWebviewResourceHandler::class.java.getResourceAsStream(it) }
                    if (resourcePath != null && stream != null) {
                        val bytes = stream.use { it.readBytes() }
                        exchange.responseHeaders.set("Content-Type", guessWebviewMimeType(resourcePath, includeUtf8Charset = true))
                        exchange.responseHeaders.set("X-Content-Type-Options", "nosniff")
                        exchange.responseHeaders.set("Cache-Control", "no-store")
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
                        exchange.responseHeaders.set("X-Content-Type-Options", "nosniff")
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
            server.start()
            serverInstance = server
            executorInstance = exec
            val url = "http://127.0.0.1:${server.address.port}"
            baseUrl = url
            if (!shutdownRegistered) {
                runCatching {
                    val app = com.intellij.openapi.application.ApplicationManager.getApplication()
                    if (app != null && !app.isDisposed) {
                        com.intellij.openapi.util.Disposer.register(app) { stop() }
                        shutdownRegistered = true
                    }
                }
            }
            "$url/index.html"
        } catch (_: Throwable) {
            runCatching { createdServer?.stop(0) }
            runCatching { createdExec?.shutdownNow() }
            null
        }
    }

    @Synchronized
    fun stop() {
        runCatching { serverInstance?.stop(0) }
        runCatching { executorInstance?.shutdownNow() }
        serverInstance = null
        executorInstance = null
        baseUrl = null
    }
}

/**
 * Streams compiled `arcdrift-webview` assets from `/webview/` inside the plugin JAR.
 */
internal class ClasspathWebviewResourceHandler : CefResourceHandlerAdapter() {

    private var dataBytes: ByteArray = ByteArray(0)
    private var offset: Int = 0
    private var mimeType: String = "text/html"
    private var statusCode: Int = 200

    override fun processRequest(request: CefRequest?, callback: CefCallback?): Boolean =
        processUrl(request?.url, callback)

    internal fun processUrl(url: String?, callback: CefCallback? = null): Boolean {
        if (url == null) return false
        val relPath = url.removePrefix(JcefCanvasPanel.ORIGIN_PREFIX)
            .substringBefore('?')
            .substringBefore('#')
            .ifEmpty { "/index.html" }

        val resourcePath = WebviewLoopbackServer.sanitizeWebviewResourcePath(relPath)
        val stream = resourcePath?.let { javaClass.getResourceAsStream(it) }

        if (resourcePath != null && stream != null) {
            dataBytes = stream.use { it.readBytes() }
            mimeType = guessWebviewMimeType(resourcePath)
            statusCode = 200
        } else {
            dataBytes = "Resource not found".toByteArray(Charsets.UTF_8)
            mimeType = "text/plain"
            statusCode = 404
        }
        offset = 0
        callback?.Continue()
        return true
    }

    override fun getResponseHeaders(
        response: CefResponse?,
        responseLength: IntRef?,
        redirectUrl: StringRef?
    ) {
        response?.mimeType = mimeType
        response?.status = statusCode
        responseLength?.set(dataBytes.size)
    }

    override fun readResponse(
        dataOut: ByteArray?,
        bytesToRead: Int,
        bytesRead: IntRef?,
        callback: CefCallback?
    ): Boolean {
        if (dataOut == null || offset >= dataBytes.size) {
            bytesRead?.set(0)
            return false
        }
        val count = minOf(bytesToRead, dataBytes.size - offset)
        System.arraycopy(dataBytes, offset, dataOut, 0, count)
        offset += count
        bytesRead?.set(count)
        return true
    }
}
