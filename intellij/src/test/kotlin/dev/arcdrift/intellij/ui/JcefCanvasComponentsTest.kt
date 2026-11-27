package dev.arcdrift.intellij.ui

import dev.arcdrift.core.model.ArcNode
import dev.arcdrift.core.model.C4Level
import dev.arcdrift.core.model.NodeKind
import dev.arcdrift.core.model.SourceLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class JcefCanvasComponentsTest {

    @Test
    fun `AgentSettingsStore loads defaults, respects env fallbacks, and clamps maxTurns`() {
        val defaults = AgentSettingsStore.load(
            getProp = { null },
            getIntProp = { _, d -> d },
            getSecret = { null },
            getEnv = { null }
        )
        assertEquals("http://localhost:11434", defaults.baseUrl)
        assertEquals("", defaults.model)
        assertEquals("", defaults.apiKey)
        assertEquals(30, defaults.maxTurns)

        val fromEnv = AgentSettingsStore.load(
            getProp = { null },
            getIntProp = { _, _ -> 999 },
            getSecret = { null },
            getEnv = { key ->
                when (key) {
                    "OPENAI_API_BASE" -> "https://api.openai.com/v1"
                    "OPENAI_MODEL" -> "gpt-4o"
                    "OPENAI_API_KEY" -> "sk-test"
                    else -> null
                }
            }
        )
        assertEquals("https://api.openai.com/v1", fromEnv.baseUrl)
        assertEquals("gpt-4o", fromEnv.model)
        assertEquals("sk-test", fromEnv.apiKey)
        assertFalse("Env var fallback must not set hasSavedApiKey = true", fromEnv.hasSavedApiKey)
        assertEquals(100, fromEnv.maxTurns)

        // Verify saved secret in PasswordSafe sets hasSavedApiKey = true and takes precedence over env var
        val withSavedSecret = AgentSettingsStore.load(
            getProp = { null },
            getIntProp = { _, d -> d },
            getSecret = { "saved-secret-key" },
            getEnv = { "sk-env-ignored" }
        )
        assertEquals("saved-secret-key", withSavedSecret.apiKey)
        assertTrue("Saved PasswordSafe key must set hasSavedApiKey = true", withSavedSecret.hasSavedApiKey)
    }

    @Test
    fun `AgentSettingsStore save trims values and resolveForRequest prefers explicit arguments`() {
        val props = mutableMapOf<String, String>()
        val intProps = mutableMapOf<String, Int>()
        AgentSettingsStore.save(
            baseUrl = "  http://localhost:8080  ",
            model = "  qwen2.5-coder:32b ",
            apiKey = "secret",
            maxTurns = 0,
            setProp = { k, v -> props[k] = v },
            setIntProp = { k, v, _ -> intProps[k] = v }
        )

        val loaded = AgentSettingsStore.load(
            getProp = { props[it] },
            getIntProp = { k, d -> intProps[k] ?: d },
            getSecret = { props["arcdrift.agent.apiKey"] },
            getEnv = { null }
        )
        assertEquals("http://localhost:8080", loaded.baseUrl)
        assertEquals("qwen2.5-coder:32b", loaded.model)
        assertEquals("secret", loaded.apiKey)
        assertEquals(1, loaded.maxTurns)

        val overridden = AgentSettingsStore.resolveForRequest(
            explicitBaseUrl = "  https://openrouter.ai/api/v1 ",
            explicitModel = null,
            explicitApiKey = "",
            explicitMaxTurns = 45,
            getProp = { props[it] },
            getIntProp = { k, d -> intProps[k] ?: d },
            getSecret = { props["arcdrift.agent.apiKey"] },
            getEnv = { null }
        )
        assertEquals("https://openrouter.ai/api/v1", overridden.baseUrl)
        assertEquals("qwen2.5-coder:32b", overridden.model)
        assertEquals("secret", overridden.apiKey)
        assertEquals(45, overridden.maxTurns)
    }

    @Test
    fun `IdeSourceNavigator parses line numbers and resolves files across paths, nodes, and tree`() {
        assertEquals(0, IdeSourceNavigator.parseZeroBasedLine(null, "src/App.ts"))
        assertEquals(41, IdeSourceNavigator.parseZeroBasedLine(null, "src/App.ts#L42"))
        assertEquals(14, IdeSourceNavigator.parseZeroBasedLine(null, "src/App.ts:15:3"))
        assertEquals(9, IdeSourceNavigator.parseZeroBasedLine(10, "src/App.ts#L42"))
        assertEquals(0, IdeSourceNavigator.parseZeroBasedLine(-5, null))

        val tempDir = Files.createTempDirectory("arcdrift-nav-test").toFile()
        try {
            val controllerFile = File(tempDir, "src/main/kotlin/OrderController.kt").apply {
                parentFile.mkdirs()
                writeText("class OrderController")
            }
            val specFile = File(tempDir, "architecture.arc.mmd").apply {
                writeText("flowchart TB")
            }
            File(tempDir, "node_modules/ignored.ts").apply {
                parentFile.mkdirs()
                writeText("// ignored")
            }

            val nodes = listOf(
                ArcNode(
                    id = "com.acme.OrderController",
                    label = "OrderController",
                    c4Level = C4Level.L3_COMPONENT,
                    kind = NodeKind.REST_CONTROLLER,
                    source = SourceLocation("src/main/kotlin/OrderController.kt", 1, 1)
                )
            )

            // 1. file:// URI + #L line suffix
            assertEquals(
                controllerFile.canonicalPath,
                IdeSourceNavigator.resolveTargetSourceFile(
                    tempDir.absolutePath,
                    "file://src/main/kotlin/OrderController.kt#L1",
                    null,
                    nodes
                )?.canonicalPath
            )
            // 2. .arc.mmd spec alias
            assertEquals(
                specFile.canonicalPath,
                IdeSourceNavigator.resolveTargetSourceFile(tempDir.absolutePath, "*.arc.mmd", null, nodes)?.canonicalPath
            )
            // 3. Node ID lookup
            assertEquals(
                controllerFile.canonicalPath,
                IdeSourceNavigator.resolveTargetSourceFile(tempDir.absolutePath, null, "com.acme.OrderController", nodes)?.canonicalPath
            )
            // 4. Tree fallback skips node_modules and falls back to architecture.arc.mmd
            assertEquals(
                specFile.canonicalPath,
                IdeSourceNavigator.resolveTargetSourceFile(tempDir.absolutePath, "ignored.ts", null, nodes)?.canonicalPath
            )
            // 5. Missing basePath returns null
            assertNull(IdeSourceNavigator.resolveTargetSourceFile(null, "missing.kt", null, nodes))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `guessWebviewMimeType returns expected MIME types for assets and sanitizeWebviewResourcePath blocks traversal`() {
        assertEquals("text/html; charset=utf-8", guessWebviewMimeType("/webview/index.html", includeUtf8Charset = true))
        assertEquals("text/javascript", guessWebviewMimeType("/webview/assets/app.js"))
        assertEquals("text/css", guessWebviewMimeType("/webview/assets/app.css"))
        assertEquals("image/svg+xml", guessWebviewMimeType("/webview/logo.svg"))
        assertEquals("application/json", guessWebviewMimeType("/webview/manifest.json"))
        assertEquals("application/octet-stream", guessWebviewMimeType("/webview/font.woff2"))

        assertEquals("/webview/index.html", WebviewLoopbackServer.sanitizeWebviewResourcePath("/"))
        assertEquals("/webview/assets/index.js", WebviewLoopbackServer.sanitizeWebviewResourcePath("/assets/index.js"))
        assertNull(WebviewLoopbackServer.sanitizeWebviewResourcePath("/../META-INF/plugin.xml"))
        assertNull(WebviewLoopbackServer.sanitizeWebviewResourcePath("/assets/../../META-INF/plugin.xml"))
        assertNull(WebviewLoopbackServer.sanitizeWebviewResourcePath("\\..\\META-INF\\plugin.xml"))

        // Start real loopback server on 127.0.0.1, fetch /index.html and 404 path, then stop
        val startedUrl = WebviewLoopbackServer.ensureStarted()
        if (startedUrl != null) {
            try {
                assertTrue(startedUrl.startsWith("http://127.0.0.1:"))
                assertEquals(startedUrl, WebviewLoopbackServer.ensureStarted())
                val client = java.net.http.HttpClient.newHttpClient()
                val okResp = client.send(
                    java.net.http.HttpRequest.newBuilder(java.net.URI.create(startedUrl)).GET().build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString()
                )
                assertEquals(200, okResp.statusCode())
                assertEquals("nosniff", okResp.headers().firstValue("X-Content-Type-Options").orElse(""))

                val missingUrl = startedUrl.substringBeforeLast('/') + "/nonexistent-asset.xyz"
                val notFoundResp = client.send(
                    java.net.http.HttpRequest.newBuilder(java.net.URI.create(missingUrl)).GET().build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString()
                )
                assertEquals(404, notFoundResp.statusCode())

                // HEAD returns 200 with empty body; POST returns 405 Method Not Allowed
                val headResp = client.send(
                    java.net.http.HttpRequest.newBuilder(java.net.URI.create(startedUrl))
                        .method("HEAD", java.net.http.HttpRequest.BodyPublishers.noBody())
                        .build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString()
                )
                assertEquals(200, headResp.statusCode())
                assertEquals("", headResp.body())

                val headMissingResp = client.send(
                    java.net.http.HttpRequest.newBuilder(java.net.URI.create(missingUrl))
                        .method("HEAD", java.net.http.HttpRequest.BodyPublishers.noBody())
                        .build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString()
                )
                assertEquals(404, headMissingResp.statusCode())

                val postResp = client.send(
                    java.net.http.HttpRequest.newBuilder(java.net.URI.create(startedUrl))
                        .POST(java.net.http.HttpRequest.BodyPublishers.ofString("x"))
                        .build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString()
                )
                assertEquals(405, postResp.statusCode())
            } finally {
                WebviewLoopbackServer.stop()
            }
        }

        // Also exercise ClasspathWebviewResourceHandler response buffer streaming for 200 and 404 URLs
        val handler = ClasspathWebviewResourceHandler()
        assertFalse(handler.processRequest(null, null))
        assertTrue(handler.processUrl("http://arcdrift.local/index.html"))
        val lenRef = org.cef.misc.IntRef()
        handler.getResponseHeaders(null, lenRef, null)
        assertTrue(lenRef.get() > 0)
        val bytesRead = org.cef.misc.IntRef()
        assertTrue(handler.readResponse(ByteArray(65536), 65536, bytesRead, null))
        assertTrue(bytesRead.get() > 0)
        assertFalse(handler.readResponse(ByteArray(16), 16, bytesRead, null))

        assertTrue(handler.processUrl("http://arcdrift.local/missing-file.xyz"))
        handler.getResponseHeaders(null, lenRef, null)
        assertTrue(lenRef.get() > 0)
    }

    @Test
    fun `IdeSourceNavigator filters VFS changes for debounced auto-refresh`() {
        val base = "/workspace/my-app"
        assertTrue(IdeSourceNavigator.shouldTriggerGraphRefresh("/workspace/my-app/src/OrderController.kt", base))
        assertTrue(IdeSourceNavigator.shouldTriggerGraphRefresh("/workspace/my-app/architecture.arc.mmd", base))
        assertTrue(IdeSourceNavigator.shouldTriggerGraphRefresh("/workspace/my-app/web/client.ts", base))
        assertFalse(IdeSourceNavigator.shouldTriggerGraphRefresh("/workspace/my-app/build/classes/OrderController.class", base))
        assertFalse(IdeSourceNavigator.shouldTriggerGraphRefresh("/workspace/my-app/node_modules/pkg/index.ts", base))
        assertFalse(IdeSourceNavigator.shouldTriggerGraphRefresh("/workspace/other-app/src/OrderController.kt", base))
        assertFalse(IdeSourceNavigator.shouldTriggerGraphRefresh("/workspace/my-app/README.md", base))
    }
}
