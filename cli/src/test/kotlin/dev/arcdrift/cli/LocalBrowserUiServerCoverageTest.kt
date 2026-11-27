package dev.arcdrift.cli

import com.github.ajalt.clikt.core.parse
import dev.arcdrift.cli.ui.LocalBrowserUiServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * Exhaustive HTTP/protocol-level coverage for [LocalBrowserUiServer]: every endpoint's method matrix,
 * HEAD handling, static assets, size limits, error mapping, lifecycle, and message-dispatch edge cases.
 */
class LocalBrowserUiServerCoverageTest {

    private val http = HttpClient.newHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    private fun repo(root: File) {
        File(root, "src/main/kotlin/com/acme/Demo.kt").apply {
            parentFile.mkdirs()
            writeText("package com.acme\n\nclass Demo\n")
        }
    }

    private fun send(method: String, url: String, body: String? = null, headers: Map<String, String> = emptyMap()): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI.create(url))
        headers.forEach { (k, v) -> builder.header(k, v) }
        val publisher = if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body)
        return http.send(builder.method(method, publisher).build(), HttpResponse.BodyHandlers.ofString())
    }

    private inline fun withServer(root: File, server: LocalBrowserUiServer = LocalBrowserUiServer(root, requestedPort = 0), block: (LocalBrowserUiServer, String) -> Unit) {
        val base = server.start()
        try {
            block(server, base)
        } finally {
            server.stop()
        }
    }

    private fun post(server: LocalBrowserUiServer, base: String, body: String, extra: Map<String, String> = emptyMap()) =
        send(
            "POST", "$base/api/message", body,
            mapOf(LocalBrowserUiServer.CSRF_TOKEN_HEADER to server.sessionToken, "Content-Type" to "application/json") + extra
        )

    private fun notification(el: kotlinx.serialization.json.JsonElement, index: Int = 0): Pair<String, String> {
        val o = el.jsonArray[index].jsonObject
        assertEquals("NOTIFICATION", o["type"]!!.jsonPrimitive.content)
        return o["level"]!!.jsonPrimitive.content to o["message"]!!.jsonPrimitive.content
    }

    // ---------- Lifecycle ----------

    @Test
    fun `start is idempotent, stop is idempotent, and port conflicts surface cleanly`(@TempDir root: File) {
        repo(root)
        val server = LocalBrowserUiServer(root, requestedPort = 0)
        val first = server.start()
        assertEquals(first, server.start())
        assertEquals(first, server.baseUrl)
        server.stop()
        server.stop()

        ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { occupied ->
            val conflicting = LocalBrowserUiServer(root, requestedPort = occupied.localPort)
            assertThrows(java.io.IOException::class.java) { conflicting.start() }
            // A failed start must leave the server restartable-safe (no leaked state)
            conflicting.stop()
        }
    }

    @Test
    fun `unstarted server reports requested port and project name from directory`(@TempDir root: File) {
        val server = LocalBrowserUiServer(root, requestedPort = 5151)
        assertEquals(5151, server.actualPort)
        assertEquals("http://127.0.0.1:5151", server.baseUrl)
        assertEquals(32, server.sessionToken.length)
        assertEquals(root.name, server.refreshGraph().projectName)
        assertNotEquals(server.sessionToken, LocalBrowserUiServer(root, requestedPort = 0).sessionToken)
    }

    // ---------- GET/HEAD matrix ----------

    @Test
    fun `read endpoints support HEAD with correct length and reject unsupported methods with Allow header`(@TempDir root: File) {
        repo(root)
        withServer(root) { _, base ->
            for (path in listOf("/api/health", "/api/graph", "/arcdrift-browser-bridge.js", "/", "/index.html")) {
                val get = send("GET", "$base$path")
                val head = send("HEAD", "$base$path")
                assertEquals(200, get.statusCode(), path)
                assertEquals(200, head.statusCode(), path)
                assertEquals("", head.body(), path)
                assertEquals(
                    get.body().toByteArray(Charsets.UTF_8).size.toString(),
                    head.headers().firstValue("Content-Length").orElse(""),
                    "HEAD Content-Length must match GET body for $path"
                )

                val post = send("POST", "$base$path", "{}")
                assertEquals(405, post.statusCode(), path)
                assertEquals("GET, HEAD", post.headers().firstValue("Allow").orElse(""), path)
                assertEquals("nosniff", post.headers().firstValue("X-Content-Type-Options").orElse(""), path)
            }
            for (method in listOf("PUT", "DELETE", "PATCH")) {
                assertEquals(405, send(method, "$base/api/message", "{}").statusCode())
            }
        }
    }

    @Test
    fun `graph endpoint honours refresh=false and refreshes by default`(@TempDir root: File) {
        repo(root)
        withServer(root) { _, base ->
            assertTrue(send("GET", "$base/api/graph").body().contains("com.acme.Demo"))

            File(root, "src/main/kotlin/com/acme/Added.kt").writeText("package com.acme\n\nclass Added\n")
            assertFalse(send("GET", "$base/api/graph?refresh=false").body().contains("com.acme.Added"))
            assertTrue(send("GET", "$base/api/graph").body().contains("com.acme.Added"))
            assertTrue(send("GET", "$base/api/graph?refresh=false").body().contains("com.acme.Added"))
        }
    }

    @Test
    fun `static assets are served with correct mime types and unknown paths return 404 for GET and HEAD`(@TempDir root: File) {
        repo(root)
        withServer(root) { _, base ->
            val html = send("GET", "$base/").body()
            val js = Regex("""/assets/[^"']+\.js""").find(html)?.value
            val css = Regex("""/assets/[^"']+\.css""").find(html)?.value
            assertTrue(js != null, "index.html must reference a bundled JS asset")

            val jsResp = send("GET", "$base$js")
            assertEquals(200, jsResp.statusCode())
            assertTrue(jsResp.headers().firstValue("Content-Type").orElse("").startsWith("text/javascript"))
            assertFalse(jsResp.body().contains("arcdrift-browser-bridge.js\"></script>"), "Bridge tag is injected only into index.html")
            assertEquals(200, send("HEAD", "$base$js").statusCode())

            if (css != null) {
                assertTrue(send("GET", "$base$css").headers().firstValue("Content-Type").orElse("").startsWith("text/css"))
            }

            val missingGet = send("GET", "$base/nope.png")
            assertEquals(404, missingGet.statusCode())
            assertEquals("Not found", missingGet.body())
            val missingHead = send("HEAD", "$base/nope.png")
            assertEquals(404, missingHead.statusCode())
            assertEquals("", missingHead.body())
            assertEquals(405, send("DELETE", "$base/nope.png").statusCode())
        }
    }

    @Test
    fun `allowed Host and Origin header variants are accepted`(@TempDir root: File) {
        repo(root)
        withServer(root) { server, _ ->
            val port = server.actualPort
            for (host in listOf("127.0.0.1", "localhost:$port", "LOCALHOST", "[::1]:$port")) {
                java.net.Socket("127.0.0.1", port).use { sock ->
                    sock.getOutputStream().write("GET /api/health HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n\r\n".toByteArray())
                    val status = sock.getInputStream().bufferedReader().readLine().orEmpty()
                    assertTrue(status.contains("200"), "Host $host should be allowed, got: $status")
                }
            }
            val resp = post(server, server.baseUrl, """{"type":"CHANGE_ZOOM_LEVEL"}""", mapOf("Origin" to "http://localhost:$port/"))
            assertEquals(200, resp.statusCode())
        }
    }

    // ---------- /api/message protocol ----------

    @Test
    fun `message endpoint rejects oversized payloads and maps internal failures to 500 envelopes`(@TempDir root: File) {
        repo(root)
        withServer(root) { server, base ->
            val big = """{"type":"CHANGE_ZOOM_LEVEL","pad":"${"x".repeat(2 * 1024 * 1024 + 16)}"}"""
            val tooLarge = post(server, base, big)
            assertEquals(413, tooLarge.statusCode())
            assertTrue(tooLarge.body().contains("exceeds"))
        }

        // spec path is a directory -> atomic write fails -> 500 with a NOTIFICATION envelope, server keeps serving
        val spec = File(root, "custom.arc.mmd").apply { writeText("flowchart TB\n") }
        withServer(root, LocalBrowserUiServer(root, explicitSpecFile = spec, requestedPort = 0)) { server, base ->
            spec.delete()
            spec.mkdirs()
            File(spec, "keep.txt").writeText("blocks replacement")
            val resp = post(
                server, base,
                """{"type":"SAVE_SPEC_RULE","rule":{"id":"r","name":"R","fromPattern":"*.a.*","toPattern":"*.b.*","policy":"FORBIDDEN","rationale":"x"}}"""
            )
            assertEquals(500, resp.statusCode())
            val (level, message) = notification(json.parseToJsonElement(resp.body()))
            assertEquals("error", level)
            assertTrue(message.startsWith("Server error:"), message)
            assertEquals(200, send("GET", "$base/api/health").statusCode())
        }
    }

    @Test
    fun `processWebviewMessage reports malformed, incomplete, and unsupported messages`(@TempDir root: File) {
        repo(root)
        val server = LocalBrowserUiServer(root, requestedPort = 0)

        assertEquals("Invalid JSON message payload." , notification(server.processWebviewMessage("not json")).second)
        assertEquals("Invalid JSON message payload.", notification(server.processWebviewMessage("[1,2]")).second)
        assertEquals("Missing message 'type'.", notification(server.processWebviewMessage("{}")).second)
        assertEquals("Missing message 'type'.", notification(server.processWebviewMessage("""{"type":{"a":1}}""")).second)
        assertEquals(
            "warning" to "Unsupported browser bridge action: NOPE",
            notification(server.processWebviewMessage("""{"type":"NOPE"}"""))
        )

        assertEquals("Missing 'rule' payload.", notification(server.processWebviewMessage("""{"type":"SAVE_SPEC_RULE"}""")).second)
        assertEquals("Invalid boundary rule payload.", notification(server.processWebviewMessage("""{"type":"SAVE_SPEC_RULE","rule":"x"}""")).second)
        assertTrue(
            notification(
                server.processWebviewMessage("""{"type":"SAVE_SPEC_RULE","rule":{"id":"a","name":"A","fromPattern":"","toPattern":"*.b.*","policy":"FORBIDDEN","rationale":"x"}}""")
            ).second.contains("non-blank")
        )

        assertEquals("Missing 'command' payload.", notification(server.processWebviewMessage("""{"type":"APPLY_REFACTORING"}""")).second)
        assertEquals("Invalid refactoring command payload.", notification(server.processWebviewMessage("""{"type":"APPLY_REFACTORING","command":{"type":"???"}}""")).second)

        for (type in listOf("SAVE_AGENT_CONFIG", "FETCH_AGENT_MODELS")) {
            assertEquals(
                "info" to LocalBrowserUiServer.AGENT_UNAVAILABLE_MESSAGE,
                notification(server.processWebviewMessage("""{"type":"$type"}"""))
            )
        }
        assertEquals(JsonArray(emptyList()), server.processWebviewMessage("""{"type":"CHANGE_ZOOM_LEVEL"}"""))
    }

    @Test
    fun `SAVE_SPEC_RULE creates the spec when absent, replaces a rule with the same id, and reports a relative path`(@TempDir root: File) {
        repo(root)
        val server = LocalBrowserUiServer(root, requestedPort = 0)
        fun rule(rationale: String) =
            """{"type":"SAVE_SPEC_RULE","rule":{"id":"r1","name":"R","fromPattern":"*.a.*","toPattern":"*.b.*","policy":"FORBIDDEN","rationale":"$rationale"}}"""

        val first = server.processWebviewMessage(rule("first"))
        assertEquals("GRAPH_UPDATED", first.jsonArray[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("info" to "Saved boundary rule 'r1' to architecture.arc.mmd.", notification(first, 1))

        server.processWebviewMessage(rule("second"))
        val text = File(root, "architecture.arc.mmd").readText()
        assertTrue(text.contains("second"))
        assertFalse(text.contains("first"))
    }

    @Test
    fun `APPLY_REFACTORING builds its adapter lazily, reports failures with the current graph, and previews without writing`(@TempDir root: File) {
        repo(root)
        // Fresh server: adapter is null until ensureAdapter() lazily creates it
        val server = LocalBrowserUiServer(root, requestedPort = 0)

        val create = { fq: String, preview: Boolean ->
            server.processWebviewMessage(
                """{"type":"APPLY_REFACTORING","previewOnly":$preview,"command":{"type":"CREATE_NODE","fqName":"$fq","kind":"INTERFACE","stereotypes":[]}}"""
            )
        }

        val preview = create("com.acme.Port", true)
        assertEquals("GRAPH_UPDATED", preview.jsonArray[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertTrue(notification(preview, 1).second.startsWith("Preview:"))
        assertFalse(File(root, "src/main/kotlin/com/acme/Port.kt").exists())

        val applied = create("com.acme.Port", false)
        assertTrue(File(root, "src/main/kotlin/com/acme/Port.kt").isFile)
        assertEquals("info", notification(applied, 1).first)

        // Creating a node that already exists must fail and still return the current graph
        val duplicate = create("com.acme.Port", false)
        assertEquals("error", notification(duplicate, 0).first)
        assertEquals("GRAPH_UPDATED", duplicate.jsonArray[1].jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `NAVIGATE_TO_SOURCE resolves node locations, explicit paths, and unknown selections`(@TempDir root: File) {
        repo(root)
        val server = LocalBrowserUiServer(root, requestedPort = 0)
        server.refreshGraph()

        val byNode = notification(server.processWebviewMessage("""{"type":"NAVIGATE_TO_SOURCE","nodeId":"com.acme.Demo"}""")).second
        assertTrue(byNode.startsWith("Source location:") && byNode.contains("Demo.kt"), byNode)

        assertEquals(
            "Source location: a/B.kt:42",
            notification(server.processWebviewMessage("""{"type":"NAVIGATE_TO_SOURCE","filePath":"a/B.kt","line":42}""")).second
        )
        assertEquals(
            "Source location: a/B.kt:1",
            notification(server.processWebviewMessage("""{"type":"NAVIGATE_TO_SOURCE","filePath":"a/B.kt"}""")).second
        )
        assertEquals(
            "No source file is associated with 'ghost'.",
            notification(server.processWebviewMessage("""{"type":"NAVIGATE_TO_SOURCE","nodeId":"ghost"}""")).second
        )
        assertEquals(
            "No source file is associated with 'selection'.",
            notification(server.processWebviewMessage("""{"type":"NAVIGATE_TO_SOURCE"}""")).second
        )
    }

    // ---------- Companion helpers ----------

    @Test
    fun `host and origin allow-lists, html injection, and mime types behave precisely`() {
        assertTrue(LocalBrowserUiServer.isAllowedHost(" LocalHost:80 ", 80))
        assertFalse(LocalBrowserUiServer.isAllowedHost("localhost:81", 80))
        assertFalse(LocalBrowserUiServer.isAllowedHost("evil.com", 80))
        assertTrue(LocalBrowserUiServer.isAllowedOrigin("http://127.0.0.1:80/", 80))
        assertFalse(LocalBrowserUiServer.isAllowedOrigin("https://127.0.0.1:80", 80))
        assertFalse(LocalBrowserUiServer.isAllowedOrigin("http://localhost.evil.com:80", 80))

        val withHead = LocalBrowserUiServer.injectBridgeScriptTag("<html><head><title>t</title></HEAD><body></body></html>")
        assertTrue(withHead.indexOf("arcdrift-browser-bridge.js") < withHead.indexOf("</HEAD>"))
        val noHead = LocalBrowserUiServer.injectBridgeScriptTag("<div>x</div>")
        assertTrue(noHead.startsWith("<script src=\"/arcdrift-browser-bridge.js\"></script>\n"))

        val expected = mapOf(
            "a.html" to "text/html; charset=utf-8",
            "a.js" to "text/javascript; charset=utf-8",
            "a.mjs" to "text/javascript; charset=utf-8",
            "a.css" to "text/css; charset=utf-8",
            "a.svg" to "image/svg+xml",
            "a.json" to "application/json; charset=utf-8",
            "a.png" to "image/png",
            "a.woff2" to "font/woff2",
            "a.bin" to "application/octet-stream"
        )
        expected.forEach { (path, mime) -> assertEquals(mime, LocalBrowserUiServer.guessMimeType(path), path) }
    }

    // ---------- `arc ui` command ----------

    @Test
    fun `arc ui reports usage errors for bad spec, occupied port, and port above range`(@TempDir root: File) {
        repo(root)
        assertThrows(com.github.ajalt.clikt.core.UsageError::class.java) {
            buildArcCli().parse(listOf("ui", root.absolutePath, "--spec", File(root, "missing.arc.mmd").absolutePath, "--no-open"))
        }
        assertThrows(com.github.ajalt.clikt.core.UsageError::class.java) {
            buildArcCli().parse(listOf("ui", root.absolutePath, "--port", "70000", "--no-open"))
        }
        ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { occupied ->
            val error = assertThrows(com.github.ajalt.clikt.core.UsageError::class.java) {
                buildArcCli().parse(listOf("ui", root.absolutePath, "--port", occupied.localPort.toString(), "--no-open"))
            }
            assertTrue(error.message!!.contains("Failed to start ArcDrift UI server"), error.message)
        }
    }

    @Test
    fun `arc ui serves until interrupted and then shuts down cleanly`(@TempDir root: File) {
        repo(root)
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val runner = Thread({
            try {
                buildArcCli().parse(listOf("ui", root.absolutePath, "--port", "0", "--no-open"))
            } catch (t: Throwable) {
                failure.set(t)
            }
        }, "arc-ui-test-runner")
        runner.start()
        val deadline = System.currentTimeMillis() + 20_000
        while (runner.state != Thread.State.WAITING && runner.isAlive && System.currentTimeMillis() < deadline) {
            Thread.sleep(25)
        }
        assertEquals(Thread.State.WAITING, runner.state, "arc ui must block awaiting shutdown; failure=${failure.get()}")
        runner.interrupt()
        runner.join(10_000)
        assertFalse(runner.isAlive)
        assertEquals(null, failure.get())
    }
}
