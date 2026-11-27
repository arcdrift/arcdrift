package dev.arcdrift.cli

import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.UsageError
import dev.arcdrift.cli.ui.LocalBrowserUiServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class LocalBrowserUiServerTest {

    private val http = HttpClient.newHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    private fun createSamplePolyglotRepo(root: File) {
        File(root, "src/main/kotlin/com/acme/orders/OrderController.kt").apply {
            parentFile.mkdirs()
            writeText(
                """
                package com.acme.orders

                @RestController
                @RequestMapping("/api/v1/orders")
                class OrderController {
                    @GetMapping("/{id}")
                    fun getOrder(id: String): String = id
                }
                """.trimIndent()
            )
        }
        File(root, "web/ordersClient.ts").apply {
            parentFile.mkdirs()
            writeText(
                """
                export async function loadOrder(id: string) {
                    return fetch(`/api/v1/orders/${'$'}{id}`);
                }
                """.trimIndent()
            )
        }
    }

    @Test
    fun `server serves injected index html, bridge script, health, graph, security headers, and blocks traversal`(@TempDir tempDir: File) {
        createSamplePolyglotRepo(tempDir)
        val server = LocalBrowserUiServer(rootDir = tempDir, requestedPort = 0)
        val baseUrl = server.start()
        try {
            assertTrue(baseUrl.startsWith("http://127.0.0.1:"))
            assertTrue(server.actualPort > 0)

            // GET / serves bundled webview index.html with injected browser bridge script + security headers
            val indexResp = http.send(
                HttpRequest.newBuilder(URI.create("$baseUrl/")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
            )
            assertEquals(200, indexResp.statusCode())
            assertTrue(indexResp.headers().firstValue("Content-Type").orElse("").startsWith("text/html"))
            assertEquals("nosniff", indexResp.headers().firstValue("X-Content-Type-Options").orElse(""))
            assertEquals("no-store", indexResp.headers().firstValue("Cache-Control").orElse(""))
            assertEquals("DENY", indexResp.headers().firstValue("X-Frame-Options").orElse(""))
            assertEquals("same-origin", indexResp.headers().firstValue("Cross-Origin-Resource-Policy").orElse(""))
            assertTrue(indexResp.body().contains("""<script src="/arcdrift-browser-bridge.js"></script>"""))

            // GET /arcdrift-browser-bridge.js embeds the CSRF token
            val bridgeResp = http.send(
                HttpRequest.newBuilder(URI.create("$baseUrl/arcdrift-browser-bridge.js")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
            )
            assertEquals(200, bridgeResp.statusCode())
            assertTrue(bridgeResp.headers().firstValue("Content-Type").orElse("").startsWith("text/javascript"))
            assertTrue(bridgeResp.body().contains(server.sessionToken))

            // GET /api/health and HEAD /api/health
            val healthResp = http.send(
                HttpRequest.newBuilder(URI.create("$baseUrl/api/health")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
            )
            assertEquals(200, healthResp.statusCode())
            val healthObj = json.parseToJsonElement(healthResp.body()).jsonObject
            assertEquals("ok", healthObj["status"]?.jsonPrimitive?.content)
            assertTrue((healthObj["nodes"]?.jsonPrimitive?.content?.toInt() ?: 0) >= 2)

            val headResp = http.send(
                HttpRequest.newBuilder(URI.create("$baseUrl/api/health")).method("HEAD", HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString()
            )
            assertEquals(200, headResp.statusCode())
            assertEquals("", headResp.body())

            // GET /api/graph returns scanned polyglot graph
            val graphResp = http.send(
                HttpRequest.newBuilder(URI.create("$baseUrl/api/graph")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
            )
            assertEquals(200, graphResp.statusCode())
            assertTrue(graphResp.body().contains("com.acme.orders.OrderController"))

            // Path traversal attempts must be rejected with 404
            for (badPath in listOf("/../etc/passwd", "/%2e%2e/etc/passwd", "/assets/../../secret", "/does-not-exist.js")) {
                val resp = http.send(
                    HttpRequest.newBuilder(URI.create("$baseUrl$badPath")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
                )
                assertEquals(404, resp.statusCode(), "Expected 404 for path $badPath")
            }

            // Unsupported HTTP methods return 405
            val methodResp = http.send(
                HttpRequest.newBuilder(URI.create("$baseUrl/api/message")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
            )
            assertEquals(405, methodResp.statusCode())
            assertEquals("POST", methodResp.headers().firstValue("Allow").orElse(""))
        } finally {
            server.stop()
        }
    }

    @Test
    fun `api message enforces CSRF token and handles initial graph, save rule, refactoring, navigation, and zoom`(@TempDir tempDir: File) {
        createSamplePolyglotRepo(tempDir)
        val server = LocalBrowserUiServer(rootDir = tempDir, requestedPort = 0)
        val baseUrl = server.start()
        try {
            // 1. Missing CSRF token -> 403 Forbidden
            val noTokenResp = http.send(
                HttpRequest.newBuilder(URI.create("$baseUrl/api/message"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("""{"type":"REQUEST_INITIAL_GRAPH"}"""))
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            )
            assertEquals(403, noTokenResp.statusCode())

            // 2. Wrong CSRF token -> 403 Forbidden
            val badTokenResp = http.send(
                HttpRequest.newBuilder(URI.create("$baseUrl/api/message"))
                    .header("Content-Type", "application/json")
                    .header(LocalBrowserUiServer.CSRF_TOKEN_HEADER, "deadbeef")
                    .POST(HttpRequest.BodyPublishers.ofString("""{"type":"REQUEST_INITIAL_GRAPH"}"""))
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            )
            assertEquals(403, badTokenResp.statusCode())

            fun postMsg(body: String): HttpResponse<String> = http.send(
                HttpRequest.newBuilder(URI.create("$baseUrl/api/message"))
                    .header("Content-Type", "application/json")
                    .header(LocalBrowserUiServer.CSRF_TOKEN_HEADER, server.sessionToken)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            )

            // 3. REQUEST_INITIAL_GRAPH
            val initResp = postMsg("""{"type":"REQUEST_INITIAL_GRAPH"}""")
            assertEquals(200, initResp.statusCode())
            val initArr = json.parseToJsonElement(initResp.body()).jsonArray
            assertEquals("GRAPH_UPDATED", initArr[0].jsonObject["type"]?.jsonPrimitive?.content)

            // 4. SAVE_SPEC_RULE persists to architecture.arc.mmd on disk
            val saveRuleResp = postMsg(
                """
                {
                  "type": "SAVE_SPEC_RULE",
                  "rule": {
                    "id": "no-web-to-db",
                    "name": "No Web To DB",
                    "fromPattern": "*.web.*",
                    "toPattern": "*.db.*",
                    "policy": "FORBIDDEN",
                    "rationale": "Enforce layered separation"
                  }
                }
                """.trimIndent()
            )
            assertEquals(200, saveRuleResp.statusCode())
            val specFile = File(tempDir, "architecture.arc.mmd")
            assertTrue(specFile.isFile, "SAVE_SPEC_RULE must create architecture.arc.mmd")
            assertTrue(specFile.readText().contains("no-web-to-db"))

            // 5. APPLY_REFACTORING (CREATE_NODE previewOnly=true, then previewOnly=false)
            val previewResp = postMsg(
                """
                {
                  "type": "APPLY_REFACTORING",
                  "previewOnly": true,
                  "command": {
                    "type": "CREATE_NODE",
                    "fqName": "com.acme.orders.OrderPort",
                    "kind": "INTERFACE",
                    "stereotypes": []
                  }
                }
                """.trimIndent()
            )
            assertEquals(200, previewResp.statusCode())
            assertTrue(previewResp.body().contains("com.acme.orders.OrderPort"))
            val expectedPortFile = File(tempDir, "src/main/kotlin/com/acme/orders/OrderPort.kt")
            assertFalse(expectedPortFile.exists(), "previewOnly=true must not write files to disk")

            val applyResp = postMsg(
                """
                {
                  "type": "APPLY_REFACTORING",
                  "previewOnly": false,
                  "command": {
                    "type": "CREATE_NODE",
                    "fqName": "com.acme.orders.OrderPort",
                    "kind": "INTERFACE",
                    "stereotypes": []
                  }
                }
                """.trimIndent()
            )
            assertEquals(200, applyResp.statusCode())
            assertTrue(expectedPortFile.isFile, "previewOnly=false must create OrderPort.kt on disk")

            // 6. NAVIGATE_TO_SOURCE, CHANGE_ZOOM_LEVEL, HANDOFF_TO_AGENT
            val navResp = postMsg("""{"type":"NAVIGATE_TO_SOURCE","nodeId":"com.acme.orders.OrderController"}""")
            assertEquals(200, navResp.statusCode())
            assertTrue(navResp.body().contains("OrderController.kt"))

            val zoomResp = postMsg("""{"type":"CHANGE_ZOOM_LEVEL","level":"L2_CONTAINER"}""")
            assertEquals(200, zoomResp.statusCode())
            assertEquals(0, json.parseToJsonElement(zoomResp.body()).jsonArray.size)

            val agentResp = postMsg("""{"type":"HANDOFF_TO_AGENT","specPrompt":"fix","ghostNodes":[],"rules":[]}""")
            assertEquals(200, agentResp.statusCode())
            assertTrue(agentResp.body().contains("arc mcp"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun `sanitizeWebviewResourcePath rejects traversal and control characters`() {
        assertEquals("/webview/index.html", LocalBrowserUiServer.sanitizeWebviewResourcePath("/"))
        assertEquals("/webview/index.html", LocalBrowserUiServer.sanitizeWebviewResourcePath(""))
        assertEquals("/webview/assets/app.js", LocalBrowserUiServer.sanitizeWebviewResourcePath("/assets/app.js?v=1"))
        assertNull(LocalBrowserUiServer.sanitizeWebviewResourcePath("/../secret.txt"))
        assertNull(LocalBrowserUiServer.sanitizeWebviewResourcePath("/assets/%2e%2e/secret.txt"))
        assertNull(LocalBrowserUiServer.sanitizeWebviewResourcePath("/assets\\app.js"))
        assertNull(LocalBrowserUiServer.sanitizeWebviewResourcePath("/assets/app.js\u0000.png"))
        assertNull(LocalBrowserUiServer.sanitizeWebviewResourcePath("/assets%2f..%2fsecret"))
        assertNull(LocalBrowserUiServer.sanitizeWebviewResourcePath("/assets/app.js%00.png"))
    }

    @Test
    fun `server blocks DNS rebinding Host headers and disallowed cross-origin POST requests`(@TempDir tempDir: File) {
        createSamplePolyglotRepo(tempDir)
        val server = LocalBrowserUiServer(rootDir = tempDir, requestedPort = 0)
        val baseUrl = server.start()
        try {
            // Raw TCP request simulating DNS rebinding attack with Host: evil.com
            for (path in listOf("/", "/arcdrift-browser-bridge.js", "/api/health", "/api/graph")) {
                java.net.Socket("127.0.0.1", server.actualPort).use { sock ->
                    val out = sock.getOutputStream()
                    out.write("GET $path HTTP/1.1\r\nHost: evil.com:${server.actualPort}\r\nConnection: close\r\n\r\n".toByteArray())
                    out.flush()
                    val statusLine = sock.getInputStream().bufferedReader().readLine().orEmpty()
                    assertTrue(
                        statusLine.contains("403"),
                        "Expected 403 Forbidden for DNS rebinding Host on $path, got: $statusLine"
                    )
                }
            }

            // Cross-origin POST /api/message must be rejected with 403 even with a valid CSRF token
            val crossOriginResp = http.send(
                HttpRequest.newBuilder(URI.create("$baseUrl/api/message"))
                    .header("Content-Type", "application/json")
                    .header("Origin", "https://evil.example")
                    .header(LocalBrowserUiServer.CSRF_TOKEN_HEADER, server.sessionToken)
                    .POST(HttpRequest.BodyPublishers.ofString("""{"type":"REQUEST_INITIAL_GRAPH"}"""))
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            )
            assertEquals(403, crossOriginResp.statusCode())
        } finally {
            server.stop()
        }
    }

    @Test
    fun `SAVE_SPEC_RULE preserves existing spec ghost nodes, excludes synthetic upstream nodes, and rejects blank rule fields`(@TempDir tempDir: File) {
        createSamplePolyglotRepo(tempDir)
        val specFile = File(tempDir, "architecture.arc.mmd")
        specFile.writeText(
            """
            flowchart TB
                %% @arc:project name="orders"
                %% @arc:node id="com.acme.orders.FutureGateway" label="FutureGateway" level="L3_COMPONENT" kind="GHOST_SPEC" diff="UNCHANGED"
                com_acme_orders_FutureGateway["FutureGateway"]
            """.trimIndent()
        )
        val upstreamManifest = dev.arcdrift.core.contracts.ContractFederationManifest(
            exportedFromProjects = listOf("payments-service"),
            producers = listOf(
                dev.arcdrift.core.contracts.ExportedContractProducer(
                    projectName = "payments-service",
                    nodeId = "com.acme.payments.PaymentController",
                    nodeLabel = "PaymentController",
                    contract = dev.arcdrift.core.model.ContractEndpoint("REST", "POST", "/api/v1/charge", "PRODUCER")
                )
            )
        )

        val server = LocalBrowserUiServer(
            rootDir = tempDir,
            upstreamManifestProvider = { upstreamManifest },
            requestedPort = 0
        )
        val baseUrl = server.start()
        try {
            fun postMsg(body: String): HttpResponse<String> = http.send(
                HttpRequest.newBuilder(URI.create("$baseUrl/api/message"))
                    .header("Content-Type", "application/json")
                    .header(LocalBrowserUiServer.CSRF_TOKEN_HEADER, server.sessionToken)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            )

            // Blank rule id must be rejected without corrupting spec file
            val invalidResp = postMsg(
                """
                {
                  "type": "SAVE_SPEC_RULE",
                  "rule": {
                    "id": "   ",
                    "name": "Blank",
                    "fromPattern": "*.a.*",
                    "toPattern": "*.b.*",
                    "policy": "FORBIDDEN",
                    "rationale": "invalid"
                  }
                }
                """.trimIndent()
            )
            assertEquals(200, invalidResp.statusCode())
            assertTrue(invalidResp.body().contains("error"))

            // Valid rule preserves FutureGateway ghost node and does NOT pollute spec with upstream:* nodes
            val validResp = postMsg(
                """
                {
                  "type": "SAVE_SPEC_RULE",
                  "rule": {
                    "id": "no-direct-repo",
                    "name": "No Direct Repo",
                    "fromPattern": "*.controller.*",
                    "toPattern": "*.repository.*",
                    "policy": "FORBIDDEN",
                    "rationale": "Use services"
                  }
                }
                """.trimIndent()
            )
            assertEquals(200, validResp.statusCode())
            val savedText = specFile.readText()
            assertTrue(savedText.contains("FutureGateway"), "Existing ghost spec node must be preserved")
            assertTrue(savedText.contains("no-direct-repo"), "Saved rule must be persisted")
            assertFalse(savedText.contains("upstream:"), "Synthetic upstream federation nodes must never be serialized into local spec")
        } finally {
            server.stop()
        }
    }

    @Test
    fun `arc check respects arcdriftignore external hosts and routes end-to-end`(@TempDir tempDir: File) {
        createSamplePolyglotRepo(tempDir)
        File(tempDir, "worker/webhook.py").apply {
            parentFile.mkdirs()
            writeText(
                """
                import requests
                def send():
                    requests.post("https://api.opsgenie.com/v2/alerts")
                    requests.post("/webhooks/pagerduty")
                """.trimIndent()
            )
        }

        // Without .arcdriftignore, arc check fails with ProgramResult(1) on unimplemented external routes
        val failResult = assertThrows(ProgramResult::class.java) {
            buildArcCli().parse(listOf("check", tempDir.absolutePath))
        }
        assertEquals(1, failResult.statusCode)

        // With .arcdriftignore allowlisting external host & webhook route, arc check passes cleanly
        File(tempDir, ".arcdriftignore").writeText(
            """
            host:api.opsgenie.com
            route:POST /webhooks/**
            """.trimIndent()
        )
        buildArcCli().parse(listOf("check", tempDir.absolutePath))
    }

    @Test
    fun `arc ui validates port bounds and directory existence`(@TempDir tempDir: File) {
        assertNotNull(
            assertThrows(UsageError::class.java) {
                buildArcCli().parse(listOf("ui", tempDir.absolutePath, "--port", "-1", "--no-open"))
            }
        )
        assertNotNull(
            assertThrows(UsageError::class.java) {
                buildArcCli().parse(listOf("ui", File(tempDir, "does-not-exist").absolutePath, "--no-open"))
            }
        )
    }
}
