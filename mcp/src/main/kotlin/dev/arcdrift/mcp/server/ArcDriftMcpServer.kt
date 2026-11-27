package dev.arcdrift.mcp.server

import dev.arcdrift.core.analysis.ArchitectureSlicer
import dev.arcdrift.core.analysis.BlastRadiusDiffer
import dev.arcdrift.core.analysis.TarjanCycleDetector
import dev.arcdrift.core.codec.ArcMermaidParser
import dev.arcdrift.core.codec.ArcMermaidSerializer
import dev.arcdrift.core.contracts.ContractFederationManifest
import dev.arcdrift.core.contracts.PolyglotContractBridge
import dev.arcdrift.core.model.ArcGraph
import dev.arcdrift.core.model.C4Level
import dev.arcdrift.core.refactoring.RefactoringCommand
import dev.arcdrift.core.refactoring.RefactoringPort
import dev.arcdrift.core.rules.ArcRuleEngine
import dev.arcdrift.core.scanner.ArcDriftIgnore
import dev.arcdrift.core.spi.ArcDriftSpiLoader
import dev.arcdrift.core.spi.ComplianceCheckSummary
import dev.arcdrift.mcp.protocol.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

/**
 * Bidirectional Model Context Protocol (MCP) Server for ArcDrift.
 * Exposes 4 high-signal architectural tools to autonomous coding agents:
 * 1. `get_architecture_slice`
 * 2. `propose_structural_diff`
 * 3. `execute_deterministic_refactoring`
 * 4. `verify_architectural_compliance`
 */
class ArcDriftMcpServer(
    private val graphProvider: () -> ArcGraph,
    private val refactoringPort: RefactoringPort,
    private val onGraphUpdated: ((ArcGraph) -> Unit)? = null,
    private val upstreamManifestProvider: () -> ContractFederationManifest? = { null },
    private val ignoreProvider: () -> ArcDriftIgnore = { ArcDriftIgnore.EMPTY }
) {

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        prettyPrint = false
    }

    fun handleJsonRpcLine(rawLine: String): String? {
        val trimmed = rawLine.trim()
        if (trimmed.isEmpty()) return null

        val parsed = try {
            json.parseToJsonElement(trimmed)
        } catch (e: Exception) {
            val errResp = JsonRpcResponse(
                id = JsonNull,
                error = JsonRpcError(code = -32700, message = "Parse error: ${e.message}")
            )
            return json.encodeToString(errResp)
        }

        if (parsed is JsonArray) {
            if (parsed.isEmpty()) {
                return json.encodeToString(
                    JsonRpcResponse(
                        id = JsonNull,
                        error = JsonRpcError(code = -32600, message = "Invalid Request: empty batch array")
                    )
                )
            }
            val batchResponses = parsed.mapNotNull { handleJsonElement(it) }
            return if (batchResponses.isEmpty()) null else json.encodeToString(batchResponses)
        }

        val response = handleJsonElement(parsed) ?: return null
        return json.encodeToString(response)
    }

    private fun handleJsonElement(element: JsonElement): JsonRpcResponse? {
        val obj = element.asJsonObjectOrNull()
            ?: return JsonRpcResponse(
                id = JsonNull,
                error = JsonRpcError(code = -32600, message = "Invalid Request: expected JSON object")
            )

        val hasId = "id" in obj
        val rawId = obj["id"]
        val validRequestId = if (hasId) {
            rawId?.asJsonPrimitiveOrNull()?.takeIf { prim ->
                prim.isString || prim.longOrNull != null || prim.doubleOrNull != null
            }
                ?: return JsonRpcResponse(
                    id = JsonNull,
                    error = JsonRpcError(
                        code = -32600,
                        message = "Invalid Request: 'id' must be a string or number when present"
                    )
                )
        } else {
            null
        }

        val jsonrpcVer = obj["jsonrpc"].asJsonPrimitiveOrNull()?.takeIf { it.isString }?.contentOrNull
        val method = obj["method"].asJsonPrimitiveOrNull()?.takeIf { it.isString }?.contentOrNull
        if (jsonrpcVer != "2.0" || method.isNullOrBlank()) {
            return JsonRpcResponse(
                id = validRequestId ?: JsonNull,
                error = JsonRpcError(
                    code = -32600,
                    message = "Invalid Request: 'jsonrpc' must be '2.0' and 'method' must be a non-empty string"
                )
            )
        }

        val rawParams = obj["params"]
        if (rawParams != null && rawParams !is JsonNull && rawParams !is JsonObject) {
            return if (validRequestId != null) {
                JsonRpcResponse(
                    id = validRequestId,
                    error = JsonRpcError(
                        code = -32602,
                        message = "Invalid params: expected JSON object"
                    )
                )
            } else {
                // JSON-RPC 2.0 notifications (without "id") must NEVER return a response
                null
            }
        }

        if (validRequestId == null) {
            // Valid JSON-RPC 2.0 notification (no "id" member) must NEVER return a response
            return null
        }

        return handleRequest(
            JsonRpcRequest(
                jsonrpc = "2.0",
                id = validRequestId,
                method = method,
                params = rawParams.asJsonObjectOrNull()
            )
        )
    }

    fun handleRequest(request: JsonRpcRequest): JsonRpcResponse? {
        // JSON-RPC 2.0 notifications (no id) must not return a response
        val reqId = request.id?.takeUnless { it is JsonNull } ?: return null
        if (request.jsonrpc != "2.0" || request.method.isBlank()) {
            return JsonRpcResponse(
                id = reqId,
                error = JsonRpcError(code = -32600, message = "Invalid Request")
            )
        }

        return try {
            when (request.method) {
                "initialize" -> JsonRpcResponse(
                    id = reqId,
                    result = json.encodeToJsonElement(McpInitializeResult())
                )
                "ping" -> JsonRpcResponse(
                    id = reqId,
                    result = buildJsonObject {}
                )
                "tools/list" -> JsonRpcResponse(
                    id = reqId,
                    result = json.encodeToJsonElement(McpListToolsResult(buildToolDescriptors()))
                )
                "tools/call" -> {
                    val toolResult = dispatchToolCall(request.params)
                    JsonRpcResponse(
                        id = reqId,
                        result = json.encodeToJsonElement(toolResult)
                    )
                }
                else -> JsonRpcResponse(
                    id = reqId,
                    error = JsonRpcError(code = -32601, message = "Method not found: ${request.method}")
                )
            }
        } catch (e: Exception) {
            JsonRpcResponse(
                id = reqId,
                error = JsonRpcError(code = -32603, message = "Internal error: ${e.message}")
            )
        }
    }

    private fun dispatchToolCall(params: JsonObject?): McpCallToolResult {
        if (params == null) {
            return errorToolResult("Missing 'params' object in tools/call.")
        }
        val toolName = params["name"].asJsonPrimitiveOrNull()?.takeIf { it.isString }?.contentOrNull
            ?: return errorToolResult("Missing 'name' parameter in tools/call.")
        val rawArgs = params["arguments"]
        if (rawArgs != null && rawArgs !is JsonNull && rawArgs !is JsonObject) {
            return errorToolResult("Invalid 'arguments' parameter in tools/call: expected JSON object.")
        }
        val args = rawArgs.asJsonObjectOrNull() ?: buildJsonObject {}

        return when (toolName) {
            "get_architecture_slice" -> handleGetArchitectureSlice(args)
            "propose_structural_diff" -> handleProposeStructuralDiff(args)
            "execute_deterministic_refactoring" -> handleExecuteRefactoring(args)
            "verify_architectural_compliance" -> handleVerifyCompliance(args)
            else -> errorToolResult("Unknown tool '$toolName'.")
        }
    }

    private fun handleGetArchitectureSlice(args: JsonObject): McpCallToolResult {
        val scope = args["scope"].asJsonPrimitiveOrNull()?.contentOrNull
        val levelStr = args["c4Level"].asJsonPrimitiveOrNull()?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        val maxLevel = if (levelStr != null) {
            runCatching { C4Level.valueOf(levelStr.uppercase()) }.getOrNull()
                ?: return errorToolResult(
                    "Invalid 'c4Level' '$levelStr'. Expected one of: ${C4Level.entries.joinToString { it.name }}."
                )
        } else {
            null
        }

        val rawDepth = args["depth"]
        val depth = if (rawDepth == null || rawDepth is JsonNull) {
            2
        } else {
            val parsedDepth = rawDepth.asJsonPrimitiveOrNull()?.intOrNull
            if (parsedDepth == null || parsedDepth < 0) {
                return errorToolResult("Invalid 'depth': expected a non-negative integer.")
            }
            parsedDepth
        }

        val rawGraph = graphProvider()
        val upstream = upstreamManifestProvider()
        val fullGraph = if (upstream != null) {
            PolyglotContractBridge.linkAndVerify(rawGraph, upstream, ignoreProvider()).enrichedGraph
        } else {
            rawGraph
        }
        val sliced = ArchitectureSlicer.slice(
            graph = fullGraph,
            scopeQuery = scope,
            maxC4Level = maxLevel,
            depth = depth
        )

        val payload = json.encodeToString(sliced)
        return McpCallToolResult(
            content = listOf(McpTextContent(text = payload)),
            isError = false
        )
    }

    private fun handleProposeStructuralDiff(args: JsonObject): McpCallToolResult {
        val mermaidSpec = args["mermaidSpec"].asJsonPrimitiveOrNull()?.takeIf { it.isString }?.contentOrNull
            ?: return errorToolResult("Missing required argument 'mermaidSpec'.")

        val baseline = graphProvider()
        val effectiveRules: List<dev.arcdrift.core.model.BoundaryRule>
        val proposedGraph = try {
            val parsed = ArcMermaidParser.parse(mermaidSpec)
            // Always retain baseline/organization rules so proposed specs cannot drop or weaken them
            effectiveRules = (baseline.rules + parsed.rules).distinctBy { it.id }
            parsed.copy(rules = effectiveRules)
        } catch (e: Exception) {
            return errorToolResult("Failed to parse 'mermaidSpec': ${e.message}")
        }

        val diffReport = BlastRadiusDiffer.diff(baseline, proposedGraph)
        val ruleViolations = ArcRuleEngine.evaluate(proposedGraph, effectiveRules)
        val contractResult = PolyglotContractBridge.linkAndVerify(proposedGraph, upstreamManifestProvider(), ignoreProvider())
        val approved = ruleViolations.isEmpty() &&
            contractResult.violations.isEmpty() &&
            diffReport.currentCycleCount <= diffReport.baselineCycleCount

        val preview = StructuralDiffPreviewResult(
            approvedByRules = approved,
            addedNodeIds = diffReport.addedNodeIds,
            modifiedNodeIds = diffReport.modifiedNodeIds,
            removedNodeIds = diffReport.removedNodeIds,
            blastImpactedNodeIds = diffReport.blastImpactedNodeIds,
            couplingDeltaPercent = diffReport.couplingDeltaPercent,
            newCycleCount = maxOf(0, diffReport.currentCycleCount - diffReport.baselineCycleCount),
            ruleViolations = ruleViolations,
            contractViolations = contractResult.violations,
            diffMermaid = ArcMermaidSerializer.serialize(diffReport.diffGraph)
        )

        return McpCallToolResult(
            content = listOf(McpTextContent(text = json.encodeToString(preview))),
            isError = !approved
        )
    }

    private fun handleExecuteRefactoring(args: JsonObject): McpCallToolResult {
        val previewOnly = args["previewOnly"].asJsonPrimitiveOrNull()?.booleanOrNull ?: false
        val cmdElement = args["command"]?.takeUnless { it is JsonNull }
            ?: return errorToolResult("Missing required argument 'command'.")

        val command = try {
            json.decodeFromJsonElement<RefactoringCommand>(cmdElement)
        } catch (e: Exception) {
            return errorToolResult("Invalid RefactoringCommand payload: ${e.message}")
        }

        val result = refactoringPort.execute(command, previewOnly = previewOnly)
        if (result.success && !previewOnly) {
            onGraphUpdated?.invoke(graphProvider())
        }

        return McpCallToolResult(
            content = listOf(McpTextContent(text = json.encodeToString(result))),
            isError = !result.success
        )
    }

    private fun handleVerifyCompliance(args: JsonObject): McpCallToolResult {
        val includeContracts = args["includeContracts"].asJsonPrimitiveOrNull()?.booleanOrNull ?: true
        val graph = graphProvider()

        val cycles = TarjanCycleDetector.findCycles(graph)
        val ruleViolations = ArcRuleEngine.evaluate(graph)
        val contractViolations = if (includeContracts) {
            PolyglotContractBridge.linkAndVerify(graph, upstreamManifestProvider(), ignoreProvider()).violations
        } else {
            emptyList()
        }

        val passed = cycles.isEmpty() && ruleViolations.isEmpty() && contractViolations.isEmpty()
        ArcDriftSpiLoader.recordComplianceCheck(
            ComplianceCheckSummary(
                projectName = graph.projectName,
                passed = passed,
                cycleCount = cycles.size,
                ruleViolationCount = ruleViolations.size,
                contractViolationCount = contractViolations.size
            )
        )

        val cycleWord = if (cycles.size == 1) "cycle" else "cycles"
        val ruleWord = if (ruleViolations.size == 1) "rule violation" else "rule violations"
        val contractWord = if (contractViolations.size == 1) "contract violation" else "contract violations"
        val summary = if (passed) {
            "Architectural compliance PASSED for '${graph.projectName}' (0 cycles, 0 rule violations, 0 contract violations)."
        } else {
            "Architectural compliance FAILED for '${graph.projectName}': ${cycles.size} $cycleWord, ${ruleViolations.size} $ruleWord, ${contractViolations.size} $contractWord."
        }

        val report = ArchitecturalComplianceReport(
            projectName = graph.projectName,
            passed = passed,
            cycleCount = cycles.size,
            ruleViolationCount = ruleViolations.size,
            contractViolationCount = contractViolations.size,
            cycles = cycles,
            ruleViolations = ruleViolations,
            contractViolations = contractViolations,
            summary = summary
        )

        return McpCallToolResult(
            content = listOf(McpTextContent(text = json.encodeToString(report))),
            isError = !passed
        )
    }

    private fun errorToolResult(msg: String): McpCallToolResult =
        McpCallToolResult(
            content = listOf(McpTextContent(text = msg)),
            isError = true
        )

    private fun buildToolDescriptors(): List<McpToolDescriptor> = listOf(
        McpToolDescriptor(
            name = "get_architecture_slice",
            description = "Returns a token-efficient semantic slice of the system architecture (C4 components, contracts, dependencies, and active boundary rules) around a requested scope.",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("scope") {
                        put("type", "string")
                        put("description", "Optional package, class, or component name substring to center the slice on.")
                    }
                    putJsonObject("c4Level") {
                        put("type", "string")
                        put("description", "Optional maximum C4 level: L1_SYSTEM, L2_CONTAINER, L3_COMPONENT, or L4_CODE.")
                    }
                    putJsonObject("depth") {
                        put("type", "integer")
                        put("description", "Hop distance from matched seed nodes (default 2).")
                    }
                }
            }
        ),
        McpToolDescriptor(
            name = "propose_structural_diff",
            description = "Previews a proposed .arc.mmd Mermaid architecture change against the baseline graph, calculating the blast radius and checking for boundary rule or contract violations before code is written.",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonArray("required") { add(JsonPrimitive("mermaidSpec")) }
                putJsonObject("properties") {
                    putJsonObject("mermaidSpec") {
                        put("type", "string")
                        put("description", "Proposed .arc.mmd or Mermaid flowchart definition.")
                    }
                }
            }
        ),
        McpToolDescriptor(
            name = "execute_deterministic_refactoring",
            description = "Executes a deterministic PSI/AST refactoring (RENAME_SYMBOL, MOVE_CLASS_OR_PACKAGE, EXTRACT_INTERFACE, CREATE_NODE, ADD_DEPENDENCY) safely without raw text edits.",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonArray("required") { add(JsonPrimitive("command")) }
                putJsonObject("properties") {
                    putJsonObject("command") {
                        put("type", "object")
                        put("description", "Polymorphic RefactoringCommand JSON object with 'type' discriminator (EXTRACT_INTERFACE, RENAME_SYMBOL, MOVE_CLASS_OR_PACKAGE, CREATE_NODE, ADD_DEPENDENCY).")
                        putJsonArray("required") { add(JsonPrimitive("type")) }
                        putJsonObject("properties") {
                            putJsonObject("type") {
                                put("type", "string")
                                put("description", "One of: EXTRACT_INTERFACE, RENAME_SYMBOL, MOVE_CLASS_OR_PACKAGE, CREATE_NODE, ADD_DEPENDENCY")
                            }
                            putJsonObject("sourceClassId") {
                                put("type", "string")
                                put("description", "Required for EXTRACT_INTERFACE and ADD_DEPENDENCY: fully qualified source class ID.")
                            }
                            putJsonObject("interfaceSimpleName") {
                                put("type", "string")
                                put("description", "Required for EXTRACT_INTERFACE: simple name of the extracted interface.")
                            }
                            putJsonObject("targetPackage") {
                                put("type", "string")
                                put("description", "Required for EXTRACT_INTERFACE: target package for the extracted interface.")
                            }
                            putJsonObject("memberNames") {
                                put("type", "array")
                                putJsonObject("items") { put("type", "string") }
                                put("description", "Optional for EXTRACT_INTERFACE: method names to extract (empty list extracts all public methods).")
                            }
                            putJsonObject("targetNodeId") {
                                put("type", "string")
                                put("description", "Required for RENAME_SYMBOL and MOVE_CLASS_OR_PACKAGE: fully qualified node ID.")
                            }
                            putJsonObject("newSimpleName") {
                                put("type", "string")
                                put("description", "Required for RENAME_SYMBOL: new simple class name.")
                            }
                            putJsonObject("destinationPackage") {
                                put("type", "string")
                                put("description", "Required for MOVE_CLASS_OR_PACKAGE: destination package.")
                            }
                            putJsonObject("fqName") {
                                put("type", "string")
                                put("description", "Required for CREATE_NODE: fully qualified name of the new node.")
                            }
                            putJsonObject("kind") {
                                put("type", "string")
                                put("description", "Optional for CREATE_NODE: CLASS, INTERFACE, DATA_CLASS, SERVICE_BEAN, REST_CONTROLLER, REPOSITORY, or GHOST_SPEC.")
                            }
                            putJsonObject("stereotypes") {
                                put("type", "array")
                                putJsonObject("items") { put("type", "string") }
                                put("description", "Optional for CREATE_NODE: annotations or stereotypes (e.g. ['@Service']).")
                            }
                            putJsonObject("targetTypeFqName") {
                                put("type", "string")
                                put("description", "Required for ADD_DEPENDENCY: fully qualified target type.")
                            }
                            putJsonObject("relationKind") {
                                put("type", "string")
                                put("description", "Optional for ADD_DEPENDENCY: DEPENDS_ON, IMPLEMENTS, or EXTENDS.")
                            }
                        }
                    }
                    putJsonObject("previewOnly") {
                        put("type", "boolean")
                        put("description", "If true, simulates the refactoring and returns touched files without mutating disk.")
                    }
                }
            }
        ),
        McpToolDescriptor(
            name = "verify_architectural_compliance",
            description = "Verifies the repository against circular dependencies (Tarjan SCC), .arc.mmd boundary rules, and cross-language REST/Kafka DTO contracts.",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("includeContracts") {
                        put("type", "boolean")
                        put("description", "Whether to verify cross-language TypeScript/Python contracts (default true).")
                    }
                }
            }
        )
    )
}
