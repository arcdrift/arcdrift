package dev.arcdrift.mcp.agent

import dev.arcdrift.core.model.ArcGraph
import dev.arcdrift.core.model.ArcNode
import dev.arcdrift.core.model.C4Level
import dev.arcdrift.core.model.NodeKind
import dev.arcdrift.core.refactoring.InMemoryGraphRefactoringAdapter
import dev.arcdrift.mcp.server.ArcDriftMcpServer
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentComponentsTest {

    @Test
    fun `AgentHttpTransport normalizes v1 base URLs and ranks models by similarity`() {
        val transport = AgentHttpTransport()
        assertEquals("http://localhost:11434/v1", transport.normalizeV1Base("http://localhost:11434/"))
        assertEquals("http://localhost:8080/v1", transport.normalizeV1Base("http://localhost:8080/v1/"))
        assertEquals("https://api.example.com/custom", transport.normalizeV1Base("https://api.example.com/custom"))
        assertEquals(
            "https://my-resource.openai.azure.com/openai/v1?api-version=2024-10-21",
            transport.normalizeV1Base("https://my-resource.openai.azure.com/openai/v1/?api-version=2024-10-21")
        )
        assertEquals(
            "https://my-resource.openai.azure.com/openai/v1/chat/completions?api-version=2024-10-21",
            transport.appendEndpointPath(
                "https://my-resource.openai.azure.com/openai/v1?api-version=2024-10-21",
                "chat/completions"
            ).toString()
        )
        kotlin.test.assertFailsWith<IllegalArgumentException> { transport.normalizeV1Base("file:///etc/passwd") }
        kotlin.test.assertFailsWith<IllegalArgumentException> { transport.normalizeV1Base("ftp://example.com/v1") }
        kotlin.test.assertFailsWith<IllegalArgumentException> { transport.normalizeV1Base("   ") }

        val ranked = transport.rankModelsBySimilarity(
            requestedModel = "qwen2.5-coder-32b",
            availableModels = listOf("llama3.3-70b", "qwen2.5-coder:32b-instruct", "qwen2.5-coder-32b")
        )
        assertEquals("qwen2.5-coder-32b", ranked.first())
        assertEquals("qwen2.5-coder:32b-instruct", ranked[1])
        assertTrue(transport.rankModelsBySimilarity("any", emptyList()).isEmpty())
    }

    @Test
    fun `AgentToolExecutor builds 5 tool schemas and executes sandboxed file read, write, and MCP tools`() {
        val tempDir = Files.createTempDirectory("arcdrift-agent-exec").toFile()
        try {
            val adapter = InMemoryGraphRefactoringAdapter(
                ArcGraph(
                    projectName = "sample",
                    nodes = listOf(
                        ArcNode("com.acme.OrderController", "OrderController", C4Level.L3_COMPONENT, NodeKind.REST_CONTROLLER)
                    )
                )
            )
            val server = ArcDriftMcpServer(
                graphProvider = { adapter.currentGraph },
                refactoringPort = adapter
            )
            val executor = AgentToolExecutor(mcpServer = server, projectRoot = tempDir)

            val schema = executor.buildOpenAiToolsSchema()
            assertEquals(5, schema.size)
            val schemaText = schema.toString()
            assertTrue(schemaText.contains("GHOST_SPEC"), "Schema must advertise GHOST_SPEC for CREATE_NODE")
            assertTrue(schemaText.contains("stereotypes"), "Schema must include stereotypes parameter")
            val toolNames = schema.mapNotNull { it.jsonObject["function"]?.jsonObject?.get("name")?.jsonPrimitive?.content }.toSet()
            assertEquals(
                setOf(
                    "verify_architectural_compliance",
                    "get_architecture_slice",
                    "read_project_file",
                    "write_project_file",
                    "execute_deterministic_refactoring"
                ),
                toolNames
            )

            val mutated = mutableListOf<File>()
            val writeOut = executor.executeAgentTool(
                name = "write_project_file",
                args = buildJsonObject {
                    put("filePath", JsonPrimitive("src/Main.kt"))
                    put("content", JsonPrimitive("class Main"))
                },
                onFileMutated = { mutated.add(it) }
            )
            assertTrue(writeOut.startsWith("Successfully wrote 10 chars"))
            assertEquals(1, mutated.size)

            val readOut = executor.executeAgentTool(
                name = "read_project_file",
                args = buildJsonObject { put("filePath", JsonPrimitive("src/Main.kt")) },
                onFileMutated = {}
            )
            assertEquals("class Main", readOut)

            val blockedOut = executor.executeAgentTool(
                name = "read_project_file",
                args = buildJsonObject { put("filePath", JsonPrimitive("../outside.txt")) },
                onFileMutated = {}
            )
            assertTrue(blockedOut.contains("outside repository root"))
            assertNull(executor.resolveSafeRepoFile(tempDir, "../outside.txt"))
            assertNull(executor.resolveSafeRepoFile(tempDir, "."))
            assertNotNull(executor.resolveSafeRepoFile(tempDir, "src/Main.kt"))

            // Secret/credential read protection & build/CI/policy write protection
            File(tempDir, ".env.local").writeText("OPENAI_API_KEY=secret")
            val blockedSecretRead = executor.executeAgentTool(
                name = "read_project_file",
                args = buildJsonObject { put("filePath", JsonPrimitive(".env.local")) },
                onFileMutated = {}
            )
            assertTrue(blockedSecretRead.contains("sensitive credential"))

            for (protectedPath in listOf(
                ".github/workflows/ci.yml",
                "build.gradle.kts",
                "settings.gradle.kts",
                "gradle.properties",
                ".arcdrift-policies.arc.mmd",
                ".GIT/hooks/pre-commit"
            )) {
                val blockedWrite = executor.executeAgentTool(
                    name = "write_project_file",
                    args = buildJsonObject {
                        put("filePath", JsonPrimitive(protectedPath))
                        put("content", JsonPrimitive("malicious"))
                    },
                    onFileMutated = {}
                )
                assertTrue(blockedWrite.contains("Refusing to modify"), "Expected $protectedPath to be blocked, got: $blockedWrite")
            }

            val sliceOut = executor.executeAgentTool(
                name = "get_architecture_slice",
                args = buildJsonObject { put("scope", JsonPrimitive("OrderController")) },
                onFileMutated = {}
            )
            assertTrue(sliceOut.contains("com.acme.OrderController"))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `OpenAiCompatibleAgentClient executes multi-turn tool calling, inline tool_call XML, model fallback, and cancellation`() {
        val tempDir = Files.createTempDirectory("arcdrift-agent-loop").toFile()
        val serverSocket = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        try {
            File(tempDir, "src/Main.kt").apply {
                parentFile.mkdirs()
                writeText("class Main")
            }
            val adapter = InMemoryGraphRefactoringAdapter(
                ArcGraph(
                    projectName = "sample",
                    nodes = listOf(
                        ArcNode("com.acme.OrderController", "OrderController", C4Level.L3_COMPONENT, NodeKind.REST_CONTROLLER)
                    )
                )
            )
            val mcpServer = ArcDriftMcpServer(
                graphProvider = { adapter.currentGraph },
                refactoringPort = adapter
            )
            val client = OpenAiCompatibleAgentClient(mcpServer = mcpServer, projectRoot = tempDir)

            var chatTurn = 0
            serverSocket.createContext("/v1/models") { exchange ->
                val body = """
                    {
                      "data": [
                        {"id": "llama3.3:70b"},
                        {"id": "qwen2.5-coder:32b", "status": {"value": "loaded"}}
                      ]
                    }
                """.trimIndent().toByteArray(Charsets.UTF_8)
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            serverSocket.createContext("/v1/chat/completions") { exchange ->
                chatTurn++
                val (status, respJson) = when (chatTurn) {
                    1 -> 404 to """{"error":"model not loaded"}"""
                    2 -> 200 to """
                        {
                          "choices": [{
                            "message": {
                              "role": "assistant",
                              "reasoning_content": "Let me inspect src/Main.kt and verify compliance.",
                              "content": "Checking files...",
                              "tool_calls": [
                                {
                                  "id": "call_1",
                                  "type": "function",
                                  "function": {
                                    "name": "read_project_file",
                                    "arguments": "{\"filePath\":\"src/Main.kt\"}"
                                  }
                                },
                                {
                                  "id": "call_2",
                                  "type": "function",
                                  "function": {
                                    "name": "verify_architectural_compliance",
                                    "arguments": "{}"
                                  }
                                }
                              ]
                            }
                          }]
                        }
                    """.trimIndent()
                    3 -> 200 to """
                        {
                          "choices": [{
                            "message": {
                              "role": "assistant",
                              "content": "Checking slice and compliance now:\n<tool_call>{\"name\":\"get_architecture_slice\",\"arguments\":{\"scope\":\"OrderController\"}}</tool_call>\n<tool_call>{\"name\":\"verify_architectural_compliance\",\"arguments\":{}}</tool_call>"
                            }
                          }]
                        }
                    """.trimIndent()
                    4 -> 200 to """
                        {
                          "choices": [{
                            "message": {
                              "role": "assistant",
                              "reasoning_content": "Everything looks clean.",
                              "content": "All violations resolved in `src/Main.kt`."
                            }
                          }]
                        }
                    """.trimIndent()
                    5 -> 200 to """not-json"""
                    6 -> 200 to """{"choices":[]}"""
                    else -> 500 to """{"error":"fatal"}"""
                }
                val bytes = respJson.toByteArray(Charsets.UTF_8)
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            serverSocket.start()
            val baseUrl = "http://127.0.0.1:${serverSocket.address.port}"

            // 1. fetchModels
            val modelsRes = client.fetchModels(baseUrl, apiKey = "sk-test")
            assertTrue(modelsRes.isSuccess)
            assertEquals("qwen2.5-coder:32b", modelsRes.getOrThrow().first())

            // 2. Full multi-turn runAgentLoop with model auto-selection + HTTP 404 fallback + tool_calls + inline <tool_call>
            val stepEvents = mutableListOf<AgentStepEvent>()
            val report = client.runAgentLoop(
                rawUrl = baseUrl,
                model = "qwen2.5-coder",
                taskPrompt = "Verify architecture",
                apiKey = "sk-test",
                maxTurns = 10,
                onStepEvent = { stepEvents.add(it) }
            )
            assertTrue(report.success, "Expected agent loop to succeed, got: ${report.finalReply}")
            assertEquals(3, report.stepsExecuted)
            assertTrue(report.finalReply.contains("All violations resolved"))
            assertTrue(stepEvents.any { it.kind == "tool_call" && it.title.contains("read_project_file") })
            assertTrue(stepEvents.any { it.kind == "tool_call" && it.title.contains("get_architecture_slice (inline)") })
            assertTrue(stepEvents.any { it.kind == "tool_call" && it.title.contains("verify_architectural_compliance (inline)") })
            assertTrue(stepEvents.any { it.kind == "assistant" && it.detail.contains("Checking slice and compliance now:") && !it.detail.contains("<tool_call>") })

            // 3. Invalid JSON response handling (chatTurn == 5)
            val badJsonReport = client.runAgentLoop(
                rawUrl = baseUrl,
                model = "qwen2.5-coder:32b",
                taskPrompt = "Test bad JSON",
                maxTurns = 2
            )
            assertTrue(!badJsonReport.success)
            assertTrue(badJsonReport.finalReply.contains("Invalid JSON response"))

            // 4. Missing choices[0].message handling (chatTurn == 6)
            val missingChoiceReport = client.runAgentLoop(
                rawUrl = baseUrl,
                model = "qwen2.5-coder:32b",
                taskPrompt = "Test missing choice",
                maxTurns = 2
            )
            assertTrue(!missingChoiceReport.success)
            assertTrue(missingChoiceReport.finalReply.contains("Missing choices[0].message"))

            // 5. Cancellation flag handling
            val cancelledReport = client.runAgentLoop(
                rawUrl = baseUrl,
                model = "qwen2.5-coder:32b",
                taskPrompt = "Cancel immediately",
                cancelFlag = java.util.concurrent.atomic.AtomicBoolean(true)
            )
            assertTrue(!cancelledReport.success)
            assertTrue(cancelledReport.finalReply.contains("cancelled by user"))

            // 6. Invalid URL scheme fails cleanly without throwing uncaught exception
            val badSchemeReport = client.runAgentLoop(
                rawUrl = "file:///etc/passwd",
                model = "qwen2.5-coder:32b",
                taskPrompt = "Should fail URL check"
            )
            assertTrue(!badSchemeReport.success)
            assertTrue(badSchemeReport.finalReply.contains("Invalid agent endpoint URL"))
        } finally {
            serverSocket.stop(0)
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `OpenAiCompatibleAgentClient handles JsonNull tool_calls, missing tool_call IDs, malformed tool JSON args, and coalesced inline tool_calls`() {
        val tempDir = Files.createTempDirectory("arcdrift-agent-null-tc").toFile()
        val serverSocket = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        try {
            val adapter = InMemoryGraphRefactoringAdapter(
                ArcGraph(
                    projectName = "sample",
                    nodes = listOf(ArcNode("com.acme.Svc", "Svc", C4Level.L3_COMPONENT, NodeKind.SERVICE_BEAN))
                )
            )
            val mcpServer = ArcDriftMcpServer(graphProvider = { adapter.currentGraph }, refactoringPort = adapter)
            val client = OpenAiCompatibleAgentClient(mcpServer = mcpServer, projectRoot = tempDir)

            val capturedBodies = mutableListOf<String>()
            var turn = 0
            serverSocket.createContext("/v1/models") { ex ->
                val b = """{"data":[{"id":"test-model","status":null}]}""".toByteArray(Charsets.UTF_8)
                ex.sendResponseHeaders(200, b.size.toLong())
                ex.responseBody.use { it.write(b) }
            }
            serverSocket.createContext("/v1/chat/completions") { ex ->
                capturedBodies.add(ex.requestBody.readBytes().toString(Charsets.UTF_8))
                turn++
                val body = when (turn) {
                    // Turn 1: tool_calls without id + malformed JSON arguments on second tool call
                    1 -> """
                        {
                          "choices": [{
                            "message": {
                              "role": "assistant",
                              "content": null,
                              "tool_calls": [
                                {"type": "function", "function": {"name": "verify_architectural_compliance", "arguments": null}},
                                {"type": "function", "function": {"name": "read_project_file", "arguments": "{truncated-json"}}
                              ]
                            }
                          }]
                        }
                    """.trimIndent()
                    // Turn 2: two inline <tool_call> blocks -> must coalesce into a single user message
                    2 -> """
                        {
                          "choices": [{
                            "message": {
                              "role": "assistant",
                              "tool_calls": null,
                              "content": "<tool_call>{\"name\":\"get_architecture_slice\",\"arguments\":{\"scope\":\"Svc\"}}</tool_call>\n<tool_call>{\"name\":\"verify_architectural_compliance\",\"arguments\":null}</tool_call>"
                            }
                          }]
                        }
                    """.trimIndent()
                    // Turn 3: final response with explicit "tool_calls": null and "reasoning_content": null
                    else -> """
                        {
                          "choices": [{
                            "message": {
                              "role": "assistant",
                              "reasoning_content": null,
                              "tool_calls": null,
                              "content": "Completed cleanly despite JsonNull fields."
                            }
                          }]
                        }
                    """.trimIndent()
                }
                val bytes = body.toByteArray(Charsets.UTF_8)
                ex.sendResponseHeaders(200, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
            serverSocket.start()
            val url = "http://127.0.0.1:${serverSocket.address.port}"

            val report = client.runAgentLoop(rawUrl = url, model = "test-model", taskPrompt = "Run checks", maxTurns = 6)
            assertTrue(report.success, "Expected success, got: ${report.finalReply}")
            assertEquals(3, report.stepsExecuted)

            // Verify Turn 2 request body had distinct synthesized IDs ("call_1_0", "call_1_1") and reported malformed JSON error
            assertTrue(capturedBodies[1].contains("call_1_0"))
            assertTrue(capturedBodies[1].contains("call_1_1"))
            assertTrue(capturedBodies[1].contains("Malformed JSON arguments"))

            // Verify Turn 3 request body coalesced both inline <tool_call> outputs into ONE user message (no consecutive user messages)
            val turn3Messages = kotlinx.serialization.json.Json.parseToJsonElement(capturedBodies[2])
                .jsonObject["messages"]!!.let { it as kotlinx.serialization.json.JsonArray }
            val roles = turn3Messages.map { it.jsonObject["role"]!!.jsonPrimitive.content }
            for (i in 1 until roles.size) {
                assertTrue(!(roles[i] == "user" && roles[i - 1] == "user"), "Consecutive user messages found: $roles")
            }
        } finally {
            serverSocket.stop(0)
            tempDir.deleteRecursively()
        }
    }
}
