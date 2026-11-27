package dev.arcdrift.cli

import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.UsageError
import dev.arcdrift.cli.ui.LocalBrowserUiServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class PrePublishCliRegressionTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val http = HttpClient.newHttpClient()

    private fun sampleRepo(root: File) {
        File(root, "src/main/kotlin/com/acme/Demo.kt").apply {
            parentFile.mkdirs()
            writeText("package com.acme\nclass Demo\n")
        }
    }

    @Test
    fun `usage errors exit with code 2 while drift and help keep their own codes`() {
        assertEquals(2, exitCodeFor(UsageError("bad option")))
        assertEquals(1, exitCodeFor(ProgramResult(1)))
        assertEquals(0, exitCodeFor(ProgramResult(0)))
        assertEquals(USAGE_ERROR_EXIT_CODE, exitCodeFor(com.github.ajalt.clikt.core.NoSuchOption("--nope")))
    }

    @Test
    fun `HANDOFF_TO_AGENT and CANCEL_AGENT_RUN always emit a terminal AGENT_STEP so the browser UI never stays stuck running`(@TempDir root: File) {
        sampleRepo(root)
        val server = LocalBrowserUiServer(rootDir = root, requestedPort = 0)
        for (type in listOf("HANDOFF_TO_AGENT", "CANCEL_AGENT_RUN")) {
            val arr = json.parseToJsonElement(server.processWebviewMessage("""{"type":"$type"}""").toString()).jsonArray
            val step = arr.single().jsonObject
            assertEquals("AGENT_STEP", step["type"]?.jsonPrimitive?.content)
            val stepObj = step["step"]!!.jsonObject
            assertTrue(stepObj["done"]!!.jsonPrimitive.boolean, "$type must finish the run")
        }
        val handoff = server.processWebviewMessage("""{"type":"HANDOFF_TO_AGENT"}""").toString()
        assertTrue(handoff.contains("arc mcp"))

        val config = json.parseToJsonElement(server.processWebviewMessage("""{"type":"SAVE_AGENT_CONFIG"}""").toString()).jsonArray
        assertEquals("NOTIFICATION", config.single().jsonObject["type"]?.jsonPrimitive?.content)
    }

    @Test
    fun `bridge script no longer triggers duplicate initial scans or tab-switch rescans`() {
        val script = LocalBrowserUiServer.buildBrowserBridgeScript("tok")
        assertFalse(script.contains("visibilitychange"))
        assertFalse(script.contains("REQUEST_INITIAL_GRAPH"))
        assertTrue(script.contains("window.__arcdriftPostMessage"))
        assertTrue(script.contains("\"tok\""))
    }

    @Test
    fun `GET api graph returns a JSON 500 instead of dropping the connection when scanning fails`(@TempDir root: File) {
        sampleRepo(root)
        var shouldFail = false
        val server = LocalBrowserUiServer(
            rootDir = root,
            upstreamManifestProvider = { if (shouldFail) error("boom: manifest unreadable") else null },
            requestedPort = 0
        )
        val baseUrl = server.start()
        try {
            shouldFail = true
            val resp = http.send(
                HttpRequest.newBuilder(URI.create("$baseUrl/api/graph")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
            )
            assertEquals(500, resp.statusCode())
            assertTrue(resp.body().contains("boom: manifest unreadable"))

            shouldFail = false
            val ok = http.send(
                HttpRequest.newBuilder(URI.create("$baseUrl/api/graph")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
            )
            assertEquals(200, ok.statusCode())
        } finally {
            server.stop()
        }
    }

    @Test
    fun `upstream manifest provider receives the spec-declared project name`(@TempDir root: File) {
        sampleRepo(root)
        File(root, "architecture.arc.mmd").writeText("flowchart TB\n    %% @arc:project name=\"orders-service\"\n")
        val seenNames = mutableListOf<String>()
        val server = LocalBrowserUiServer(
            rootDir = root,
            upstreamManifestProvider = { name -> seenNames.add(name); null },
            requestedPort = 0
        )
        server.refreshGraph()
        assertEquals(listOf("orders-service"), seenNames.distinct())
    }
}
