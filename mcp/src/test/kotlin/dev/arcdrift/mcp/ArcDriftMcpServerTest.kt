package dev.arcdrift.mcp

import dev.arcdrift.core.model.*
import dev.arcdrift.core.refactoring.InMemoryGraphRefactoringAdapter
import dev.arcdrift.core.refactoring.RefactoringResult
import dev.arcdrift.mcp.agent.OpenAiCompatibleAgentClient
import dev.arcdrift.mcp.protocol.*
import dev.arcdrift.mcp.server.ArcDriftMcpServer
import dev.arcdrift.mcp.transport.StdioMcpTransport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ArcDriftMcpServerTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun createSampleServer(): Pair<ArcDriftMcpServer, InMemoryGraphRefactoringAdapter> {
        val initialGraph = ArcGraph(
            projectName = "payments-service",
            rules = listOf(
                BoundaryRule(
                    id = "no-controller-to-repo",
                    name = "Controllers must not call repositories directly",
                    fromPattern = "*.controller.*",
                    toPattern = "*.repository.*",
                    policy = RulePolicy.FORBIDDEN,
                    rationale = "Use PaymentService instead"
                )
            ),
            nodes = listOf(
                ArcNode(
                    id = "com.acme.payments.controller.PaymentController",
                    label = "PaymentController",
                    c4Level = C4Level.L3_COMPONENT,
                    kind = NodeKind.REST_CONTROLLER,
                    contracts = listOf(
                        ContractEndpoint(
                            protocol = "REST",
                            methodOrAction = "POST",
                            pathOrTopic = "/v1/payments",
                            role = "PRODUCER",
                            responseDto = "PaymentResultDto",
                            dtoFields = mapOf("paymentId" to "String", "status" to "String")
                        )
                    )
                ),
                ArcNode(
                    id = "com.acme.payments.service.PaymentService",
                    label = "PaymentService",
                    c4Level = C4Level.L3_COMPONENT,
                    kind = NodeKind.SERVICE_BEAN,
                    members = listOf(MemberSignature("charge", "METHOD", "(ChargeReq): PaymentResultDto"))
                ),
                ArcNode(
                    id = "com.acme.payments.repository.PaymentRepository",
                    label = "PaymentRepository",
                    c4Level = C4Level.L3_COMPONENT,
                    kind = NodeKind.REPOSITORY
                )
            ),
            edges = listOf(
                ArcEdge(
                    id = "e1",
                    sourceId = "com.acme.payments.controller.PaymentController",
                    targetId = "com.acme.payments.service.PaymentService",
                    kind = EdgeKind.CALLS
                ),
                ArcEdge(
                    id = "e2",
                    sourceId = "com.acme.payments.service.PaymentService",
                    targetId = "com.acme.payments.repository.PaymentRepository",
                    kind = EdgeKind.DEPENDS_ON
                )
            )
        )
        val adapter = InMemoryGraphRefactoringAdapter(initialGraph)
        val server = ArcDriftMcpServer(
            graphProvider = { adapter.currentGraph },
            refactoringPort = adapter
        )
        return server to adapter
    }

    @Test
    fun `initialize, tools list and notifications roundtrip over StdioMcpTransport`() {
        val (server, _) = createSampleServer()
        val inputLines = listOf(
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}""",
            """{"jsonrpc":"2.0","method":"notifications/initialized"}""",
            """{"jsonrpc":"2.0","id":2,"method":"tools/list"}"""
        ).joinToString("\n") + "\n"

        val outStream = ByteArrayOutputStream()
        val transport = StdioMcpTransport(
            server = server,
            input = ByteArrayInputStream(inputLines.toByteArray(Charsets.UTF_8)),
            output = outStream
        )
        transport.runLoop()

        val responses = outStream.toString(Charsets.UTF_8).trim().lines()
        // Notification should not produce a response line, so exactly 2 responses expected
        assertEquals(2, responses.size)

        val initResp = json.decodeFromString<JsonRpcResponse>(responses[0])
        val initResult = json.decodeFromJsonElement<McpInitializeResult>(requireNotNull(initResp.result))
        assertEquals("arcdrift-mcp", initResult.serverInfo.name)

        val listResp = json.decodeFromString<JsonRpcResponse>(responses[1])
        val listResult = json.decodeFromJsonElement<McpListToolsResult>(requireNotNull(listResp.result))
        assertEquals(
            setOf(
                "get_architecture_slice",
                "propose_structural_diff",
                "execute_deterministic_refactoring",
                "verify_architectural_compliance"
            ),
            listResult.tools.map { it.name }.toSet()
        )

        // Malformed JSON line must return JSON-RPC -32700 Parse error with explicit "id":null without crashing the stdio loop
        val malformedOut = ByteArrayOutputStream()
        StdioMcpTransport(
            server = server,
            input = ByteArrayInputStream("{bad-json\n".toByteArray(Charsets.UTF_8)),
            output = malformedOut
        ).runLoop()
        val rawMalformed = malformedOut.toString(Charsets.UTF_8).trim()
        assertTrue(rawMalformed.contains("\"id\":null"), "Expected explicit \"id\":null in JSON-RPC error: $rawMalformed")
        val errResp = json.decodeFromString<JsonRpcResponse>(rawMalformed)
        assertEquals(-32700, errResp.error?.code)
    }

    @Test
    fun `JSON-RPC 2_0 validation handles batches, notifications vs id-present requests, invalid params, and baseline rule retention`() {
        val (server, _) = createSampleServer()

        // 1. Empty batch -> -32600; non-object -> -32600; null/boolean id -> -32600
        val emptyBatch = requireNotNull(server.handleJsonRpcLine("[]"))
        assertEquals(-32600, json.decodeFromString<JsonRpcResponse>(emptyBatch).error?.code)
        val nonObject = requireNotNull(server.handleJsonRpcLine("42"))
        assertEquals(-32600, json.decodeFromString<JsonRpcResponse>(nonObject).error?.code)
        val nullIdReq = requireNotNull(server.handleJsonRpcLine("""{"jsonrpc":"2.0","id":null,"method":"ping"}"""))
        assertEquals(-32600, json.decodeFromString<JsonRpcResponse>(nullIdReq).error?.code)
        val boolIdReq = requireNotNull(server.handleJsonRpcLine("""{"jsonrpc":"2.0","id":true,"method":"ping"}"""))
        assertEquals(-32600, json.decodeFromString<JsonRpcResponse>(boolIdReq).error?.code)

        // 2. True notification (no "id" key) returns null even on unknown method, while request WITH "id" on notifications/* returns -32601
        assertEquals(null, server.handleJsonRpcLine("""{"jsonrpc":"2.0","method":"notifications/cancelled"}"""))
        val idOnNotification = requireNotNull(
            server.handleJsonRpcLine("""{"jsonrpc":"2.0","id":77,"method":"notifications/initialized"}""")
        )
        assertEquals(-32601, json.decodeFromString<JsonRpcResponse>(idOnNotification).error?.code)

        // 3. Array params -> -32602 Invalid params
        val arrayParamsResp = requireNotNull(
            server.handleJsonRpcLine("""{"jsonrpc":"2.0","id":78,"method":"tools/call","params":[1,2]}""")
        )
        assertEquals(-32602, json.decodeFromString<JsonRpcResponse>(arrayParamsResp).error?.code)

        // 4. Batch request with 2 requests + 1 notification returns JSON array of 2 responses
        val batchResp = requireNotNull(
            server.handleJsonRpcLine(
                """[{"jsonrpc":"2.0","id":"a","method":"ping"},{"jsonrpc":"2.0","method":"notifications/initialized"},{"jsonrpc":"2.0","id":2,"method":"unknown_method"}]"""
            )
        )
        val batchList = json.decodeFromString<List<JsonRpcResponse>>(batchResp)
        assertEquals(2, batchList.size)
        assertEquals(-32601, batchList[1].error?.code)

        // 5. get_architecture_slice rejects invalid c4Level and negative depth
        val badLevel = requireNotNull(
            server.handleJsonRpcLine("""{"jsonrpc":"2.0","id":80,"method":"tools/call","params":{"name":"get_architecture_slice","arguments":{"c4Level":"L9_INVALID"}}}""")
        )
        val badLevelRes = json.decodeFromJsonElement<McpCallToolResult>(requireNotNull(json.decodeFromString<JsonRpcResponse>(badLevel).result))
        assertTrue(badLevelRes.isError)

        val negDepth = requireNotNull(
            server.handleJsonRpcLine("""{"jsonrpc":"2.0","id":81,"method":"tools/call","params":{"name":"get_architecture_slice","arguments":{"depth":-1}}}""")
        )
        val negDepthRes = json.decodeFromJsonElement<McpCallToolResult>(requireNotNull(json.decodeFromString<JsonRpcResponse>(negDepth).result))
        assertTrue(negDepthRes.isError)

        // 6. propose_structural_diff cannot drop baseline rules by supplying an innocuous rule in mermaidSpec
        val bypassAttemptSpec = """
            flowchart TB
                %% @arc:rule id="harmless-rule" name="Harmless" from="*.foo.*" to="*.bar.*" policy="FORBIDDEN"
                %% @arc:node id="com.acme.payments.controller.PaymentController" label="PaymentController" level="L3_COMPONENT" kind="REST_CONTROLLER"
                c["PaymentController"]
                %% @arc:node id="com.acme.payments.repository.PaymentRepository" label="PaymentRepository" level="L3_COMPONENT" kind="REPOSITORY"
                r["PaymentRepository"]
                c --> r
        """.trimIndent().replace("\n", "\\n").replace("\"", "\\\"")
        val bypassResp = requireNotNull(
            server.handleJsonRpcLine("""{"jsonrpc":"2.0","id":82,"method":"tools/call","params":{"name":"propose_structural_diff","arguments":{"mermaidSpec":"$bypassAttemptSpec"}}}""")
        )
        val bypassToolRes = json.decodeFromJsonElement<McpCallToolResult>(requireNotNull(json.decodeFromString<JsonRpcResponse>(bypassResp).result))
        assertTrue(bypassToolRes.isError)
        val preview = json.decodeFromString<StructuralDiffPreviewResult>(bypassToolRes.content.first().text)
        assertFalse(preview.approvedByRules)
        assertTrue(preview.ruleViolations.any { it.ruleId == "no-controller-to-repo" })
    }

    @Test
    fun `get_architecture_slice returns filtered subgraph JSON`() {
        val (server, _) = createSampleServer()
        val req = """
            {"jsonrpc":"2.0","id":10,"method":"tools/call","params":{"name":"get_architecture_slice","arguments":{"scope":"PaymentController","depth":1}}}
        """.trimIndent()

        val rawResp = requireNotNull(server.handleJsonRpcLine(req))
        val rpcResp = json.decodeFromString<JsonRpcResponse>(rawResp)
        val callResult = json.decodeFromJsonElement<McpCallToolResult>(requireNotNull(rpcResp.result))
        assertFalse(callResult.isError)

        val slicedGraph = json.decodeFromString<ArcGraph>(callResult.content.first().text)
        // Depth 1 from PaymentController includes PaymentController and PaymentService, excluding PaymentRepository
        assertEquals(
            setOf(
                "com.acme.payments.controller.PaymentController",
                "com.acme.payments.service.PaymentService"
            ),
            slicedGraph.nodes.map { it.id }.toSet()
        )
    }

    @Test
    fun `propose_structural_diff catches boundary rule violation before agent writes code`() {
        val (server, _) = createSampleServer()
        // Propose an illegal direct edge from PaymentController to PaymentRepository
        val badMermaid = """
            flowchart TB
                %% @arc:node id="com.acme.payments.controller.PaymentController" label="PaymentController" level="L3_COMPONENT" kind="REST_CONTROLLER"
                com_acme_payments_controller_PaymentController["PaymentController"]
                %% @arc:node id="com.acme.payments.repository.PaymentRepository" label="PaymentRepository" level="L3_COMPONENT" kind="REPOSITORY"
                com_acme_payments_repository_PaymentRepository["PaymentRepository"]
                com_acme_payments_controller_PaymentController --> com_acme_payments_repository_PaymentRepository
        """.trimIndent().replace("\n", "\\n").replace("\"", "\\\"")

        val req = """
            {"jsonrpc":"2.0","id":11,"method":"tools/call","params":{"name":"propose_structural_diff","arguments":{"mermaidSpec":"$badMermaid"}}}
        """.trimIndent()

        val rawResp = requireNotNull(server.handleJsonRpcLine(req))
        val rpcResp = json.decodeFromString<JsonRpcResponse>(rawResp)
        val callResult = json.decodeFromJsonElement<McpCallToolResult>(requireNotNull(rpcResp.result))
        assertTrue(callResult.isError)

        val preview = json.decodeFromString<StructuralDiffPreviewResult>(callResult.content.first().text)
        assertFalse(preview.approvedByRules)
        assertEquals(1, preview.ruleViolations.size)
        assertEquals("no-controller-to-repo", preview.ruleViolations.first().ruleId)
    }

    @Test
    fun `execute_deterministic_refactoring and verify_architectural_compliance succeed`() {
        val (server, adapter) = createSampleServer()

        val refactorReq = """
            {
              "jsonrpc": "2.0",
              "id": 12,
              "method": "tools/call",
              "params": {
                "name": "execute_deterministic_refactoring",
                "arguments": {
                  "previewOnly": false,
                  "command": {
                    "type": "EXTRACT_INTERFACE",
                    "sourceClassId": "com.acme.payments.service.PaymentService",
                    "interfaceSimpleName": "PaymentUseCase",
                    "targetPackage": "com.acme.payments.port",
                    "memberNames": ["charge"]
                  }
                }
              }
            }
        """.trimIndent().lines().joinToString(" ")

        val rawRefactorResp = requireNotNull(server.handleJsonRpcLine(refactorReq))
        val rpcRefactor = json.decodeFromString<JsonRpcResponse>(rawRefactorResp)
        val refactorCallResult = json.decodeFromJsonElement<McpCallToolResult>(requireNotNull(rpcRefactor.result))
        assertFalse(refactorCallResult.isError)
        val refResult = json.decodeFromString<RefactoringResult>(refactorCallResult.content.first().text)
        assertTrue(refResult.success)
        assertNotNull(adapter.currentGraph.nodeMap()["com.acme.payments.port.PaymentUseCase"])

        // Verify compliance passes
        val verifyReq = """
            {"jsonrpc":"2.0","id":13,"method":"tools/call","params":{"name":"verify_architectural_compliance","arguments":{"includeContracts":true}}}
        """.trimIndent()
        val rawVerifyResp = requireNotNull(server.handleJsonRpcLine(verifyReq))
        val verifyCallResult = json.decodeFromJsonElement<McpCallToolResult>(
            requireNotNull(json.decodeFromString<JsonRpcResponse>(rawVerifyResp).result)
        )
        assertFalse(verifyCallResult.isError)
        val report = json.decodeFromString<ArchitecturalComplianceReport>(verifyCallResult.content.first().text)
        assertTrue(report.passed)
        assertEquals(0, report.cycleCount)
        assertEquals(0, report.ruleViolationCount)
    }

    @Test
    fun `verify_architectural_compliance links upstream contract manifest across repositories`() {
        val consumerGraph = ArcGraph(
            projectName = "frontend-app",
            nodes = listOf(
                ArcNode(
                    id = "ts.src.billing.client",
                    label = "billingClient.ts",
                    kind = NodeKind.TS_CLIENT,
                    contracts = listOf(
                        ContractEndpoint(
                            protocol = "REST",
                            methodOrAction = "POST",
                            pathOrTopic = "/v2/billing/subscribe",
                            role = "CONSUMER",
                            requestDto = "SubscribeRequest",
                            requestDtoFields = mapOf("planId" to "string")
                        )
                    )
                )
            )
        )
        val adapter = InMemoryGraphRefactoringAdapter(consumerGraph)
        val upstreamManifest = dev.arcdrift.core.contracts.ContractFederationManifest(
            exportedFromProjects = listOf("billing-cloud"),
            producers = listOf(
                dev.arcdrift.core.contracts.ExportedContractProducer(
                    projectName = "billing-cloud",
                    nodeId = "com.acme.cloud.BillingController",
                    nodeLabel = "BillingController",
                    contract = ContractEndpoint(
                        protocol = "REST",
                        methodOrAction = "POST",
                        pathOrTopic = "/v2/billing/subscribe",
                        role = "PRODUCER",
                        requestDto = "SubscribeRequestDto",
                        requestDtoFields = mapOf("planId" to "String")
                    )
                )
            )
        )
        val server = ArcDriftMcpServer(
            graphProvider = { adapter.currentGraph },
            refactoringPort = adapter,
            upstreamManifestProvider = { upstreamManifest }
        )

        val verifyReq = """
            {"jsonrpc":"2.0","id":20,"method":"tools/call","params":{"name":"verify_architectural_compliance","arguments":{"includeContracts":true}}}
        """.trimIndent()
        val rawResp = requireNotNull(server.handleJsonRpcLine(verifyReq))
        val callResult = json.decodeFromJsonElement<McpCallToolResult>(
            requireNotNull(json.decodeFromString<JsonRpcResponse>(rawResp).result)
        )
        assertFalse(callResult.isError)
        val report = json.decodeFromString<ArchitecturalComplianceReport>(callResult.content.first().text)
        assertTrue(report.passed)
        assertEquals(0, report.contractViolationCount)
    }

    @Test
    fun `normalizeV1Base appends v1 only for bare hosts and preserves explicit provider paths`() {
        val (server, _) = createSampleServer()
        val client = OpenAiCompatibleAgentClient(server, projectRoot = null)
        assertEquals("http://localhost:11434/v1", client.normalizeV1Base("http://localhost:11434"))
        assertEquals("https://api.openai.com/v1", client.normalizeV1Base("https://api.openai.com/"))
        assertEquals("https://api.openai.com/v1", client.normalizeV1Base("https://api.openai.com/v1/"))
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/openai",
            client.normalizeV1Base("https://generativelanguage.googleapis.com/v1beta/openai/")
        )
        assertEquals(
            "https://open.bigmodel.cn/api/paas/v4",
            client.normalizeV1Base("https://open.bigmodel.cn/api/paas/v4")
        )
    }

    @Test
    fun `resolveSafeRepoFile blocks sibling-prefix bypass, parent traversal, symlinks, and blank paths`() {
        val parentDir = java.nio.file.Files.createTempDirectory("arcdrift-sandbox-test").toFile()
        try {
            val repoRoot = java.io.File(parentDir, "app").apply { mkdirs() }
            val siblingSecretDir = java.io.File(parentDir, "app-secrets").apply { mkdirs() }
            val insideFile = java.io.File(repoRoot, "src/Main.kt").apply {
                parentFile.mkdirs()
                writeText("fun main() {}")
            }
            val siblingSecretFile = java.io.File(siblingSecretDir, ".env").apply {
                writeText("SECRET=123")
            }

            val (server, _) = createSampleServer()
            val client = OpenAiCompatibleAgentClient(server, projectRoot = repoRoot)

            // Valid relative and absolute paths inside repoRoot
            assertEquals(insideFile.canonicalFile, client.resolveSafeRepoFile(repoRoot, "src/Main.kt"))
            assertEquals(insideFile.canonicalFile, client.resolveSafeRepoFile(repoRoot, "./src/Main.kt"))
            assertEquals(insideFile.canonicalFile, client.resolveSafeRepoFile(repoRoot, insideFile.absolutePath))

            // Blank paths
            assertEquals(null, client.resolveSafeRepoFile(repoRoot, ""))
            assertEquals(null, client.resolveSafeRepoFile(repoRoot, "   "))

            // Parent traversal
            assertEquals(null, client.resolveSafeRepoFile(repoRoot, "../app-secrets/.env"))
            assertEquals(null, client.resolveSafeRepoFile(repoRoot, "src/../../app-secrets/.env"))

            // Sibling-directory prefix bypass (/tmp/.../app vs /tmp/.../app-secrets/.env)
            assertEquals(null, client.resolveSafeRepoFile(repoRoot, siblingSecretFile.absolutePath))

            // Symlink inside repoRoot pointing outside repoRoot
            val linkPath = repoRoot.toPath().resolve("leak.env")
            val symlinkCreated = runCatching {
                java.nio.file.Files.createSymbolicLink(linkPath, siblingSecretFile.toPath())
                true
            }.getOrDefault(false)
            if (symlinkCreated) {
                assertEquals(null, client.resolveSafeRepoFile(repoRoot, "leak.env"))
            }
        } finally {
            parentDir.deleteRecursively()
        }
    }
}
