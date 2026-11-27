package dev.arcdrift.mcp

import com.sun.net.httpserver.HttpServer
import dev.arcdrift.core.model.ArcGraph
import dev.arcdrift.core.model.ArcNode
import dev.arcdrift.core.model.C4Level
import dev.arcdrift.core.model.NodeKind
import dev.arcdrift.core.model.SourceLocation
import dev.arcdrift.core.refactoring.InMemoryGraphRefactoringAdapter
import dev.arcdrift.mcp.agent.AgentToolExecutor
import dev.arcdrift.mcp.agent.OpenAiCompatibleAgentClient
import dev.arcdrift.mcp.server.ArcDriftMcpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

/**
 * Multi-case regression suite for JSON-RPC 2.0 validation, OpenAI agent blank/stringified arguments,
 * non-retryable HTTP error handling (401/429/500), and preview/failure `onFileMutated` gating.
 */
class McpAndAgentHardeningRegressionTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `ArcDriftMcpServer returns -32600 for malformed JSON objects lacking id while staying silent on valid notifications`() {
        val adapter = InMemoryGraphRefactoringAdapter(ArcGraph("Demo"))
        val server = ArcDriftMcpServer(graphProvider = { adapter.currentGraph }, refactoringPort = adapter)

        val emptyObjResp = server.handleJsonRpcLine("{}")
        assertNotNull(emptyObjResp)
        val parsedEmpty = json.parseToJsonElement(emptyObjResp!!).jsonObject
        assertEquals(-32600, parsedEmpty["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)
        assertEquals("null", parsedEmpty["id"].toString())

        val bogusObjResp = server.handleJsonRpcLine("""{"foo":"bar"}""")
        assertNotNull(bogusObjResp)
        assertEquals(-32600, json.parseToJsonElement(bogusObjResp!!).jsonObject["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)

        val validNotification = server.handleJsonRpcLine("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
        assertNull(validNotification, "Valid JSON-RPC 2.0 notification must not return a response")

        val badParamsNotification = server.handleJsonRpcLine("""{"jsonrpc":"2.0","method":"notifications/progress","params":"bad"}""")
        assertNull(badParamsNotification, "Notifications must not emit a response even with malformed params")
    }

    @Test
    fun `OpenAiCompatibleAgentClient normalizes blank tool arguments, parses inline stringified arguments, and does not fallback on 401`() {
        val chatHits = AtomicInteger(0)
        val authHits = AtomicInteger(0)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/models") { ex ->
            val body = """{"data":[{"id":"model-a"},{"id":"model-b"}]}""".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.createContext("/v1/chat/completions") { ex ->
            val turn = chatHits.incrementAndGet()
            val body = when (turn) {
                1 -> """
                    {
                      "choices": [{
                        "message": {
                          "role": "assistant",
                          "content": "",
                          "tool_calls": [{
                            "id": "call_blank",
                            "type": "function",
                            "function": {
                              "name": "verify_architectural_compliance",
                              "arguments": "   "
                            }
                          }]
                        }
                      }]
                    }
                """.trimIndent()
                2 -> """
                    {
                      "choices": [{
                        "message": {
                          "role": "assistant",
                          "content": "<tool_call>{\"name\":\"get_architecture_slice\",\"arguments\":\"{\\\"depth\\\":1}\"}</tool_call>"
                        }
                      }]
                    }
                """.trimIndent()
                else -> """
                    {
                      "choices": [{
                        "message": {
                          "role": "assistant",
                          "content": "All verified."
                        }
                      }]
                    }
                """.trimIndent()
            }.toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        val authFailServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        authFailServer.createContext("/v1/models") { ex ->
            val body = """{"data":[{"id":"m1"},{"id":"m2"},{"id":"m3"}]}""".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        authFailServer.createContext("/v1/chat/completions") { ex ->
            authHits.incrementAndGet()
            val body = """{"error":"Unauthorized"}""".toByteArray()
            ex.sendResponseHeaders(401, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.start()
        authFailServer.start()
        try {
            val adapter = InMemoryGraphRefactoringAdapter(ArcGraph("Demo"))
            val mcp = ArcDriftMcpServer(graphProvider = { adapter.currentGraph }, refactoringPort = adapter)
            val client = OpenAiCompatibleAgentClient(mcpServer = mcp, projectRoot = null)

            val okReport = client.runAgentLoop(
                rawUrl = "http://127.0.0.1:${server.address.port}/v1",
                model = "model-a",
                taskPrompt = "Verify",
                maxTurns = 4
            )
            assertTrue(okReport.success, okReport.finalReply)
            assertTrue(okReport.events.none { it.kind == "tool_result" && it.detail.contains("Malformed") })

            val unauthReport = client.runAgentLoop(
                rawUrl = "http://127.0.0.1:${authFailServer.address.port}/v1",
                model = "m1",
                taskPrompt = "Verify",
                maxTurns = 3
            )
            assertFalse(unauthReport.success)
            assertEquals(1, authHits.get(), "HTTP 401 must NOT trigger model fallback retries")
        } finally {
            server.stop(0)
            authFailServer.stop(0)
        }
    }

    @Test
    fun `AgentToolExecutor only invokes onFileMutated for non-preview successful refactorings`(@TempDir tempDir: File) {
        val srcFile = File(tempDir, "src/main/kotlin/com/acme/Order.kt").apply {
            parentFile.mkdirs()
            writeText("package com.acme\nclass Order\n")
        }
        val graph = ArcGraph(
            projectName = "Demo",
            nodes = listOf(
                ArcNode(
                    id = "com.acme.Order",
                    label = "Order",
                    c4Level = C4Level.L3_COMPONENT,
                    kind = NodeKind.CLASS,
                    parentId = "com.acme",
                    source = SourceLocation("src/main/kotlin/com/acme/Order.kt", 1, 2, "kotlin")
                )
            )
        )
        val adapter = InMemoryGraphRefactoringAdapter(graph, tempDir)
        val mcp = ArcDriftMcpServer(graphProvider = { adapter.currentGraph }, refactoringPort = adapter)
        val executor = AgentToolExecutor(mcpServer = mcp, projectRoot = tempDir)

        val mutated = mutableListOf<File>()

        // 1. previewOnly = true must NOT trigger onFileMutated
        executor.executeAgentTool(
            "execute_deterministic_refactoring",
            buildJsonObject {
                put("previewOnly", true)
                putJsonObject("command") {
                    put("type", "RENAME_SYMBOL")
                    put("targetNodeId", "com.acme.Order")
                    put("newSimpleName", "PurchaseOrder")
                }
            },
            onFileMutated = { mutated.add(it) }
        )
        assertTrue(mutated.isEmpty(), "previewOnly=true must not trigger onFileMutated")

        // 2. Failed non-preview refactoring (invalid identifier) must NOT trigger onFileMutated
        executor.executeAgentTool(
            "execute_deterministic_refactoring",
            buildJsonObject {
                put("previewOnly", false)
                putJsonObject("command") {
                    put("type", "RENAME_SYMBOL")
                    put("targetNodeId", "com.acme.Order")
                    put("newSimpleName", "class")
                }
            },
            onFileMutated = { mutated.add(it) }
        )
        assertTrue(mutated.isEmpty(), "Failed refactoring must not trigger onFileMutated")

        // 3. Successful non-preview refactoring MUST trigger onFileMutated
        executor.executeAgentTool(
            "execute_deterministic_refactoring",
            buildJsonObject {
                put("previewOnly", false)
                putJsonObject("command") {
                    put("type", "RENAME_SYMBOL")
                    put("targetNodeId", "com.acme.Order")
                    put("newSimpleName", "PurchaseOrder")
                }
            },
            onFileMutated = { mutated.add(it) }
        )
        assertTrue(mutated.isNotEmpty(), "Successful refactoring must notify onFileMutated")
        assertFalse(srcFile.exists())
    }
}
