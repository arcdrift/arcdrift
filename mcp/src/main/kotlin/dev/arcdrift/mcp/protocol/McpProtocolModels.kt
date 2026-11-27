package dev.arcdrift.mcp.protocol

import dev.arcdrift.core.analysis.DependencyCycle
import dev.arcdrift.core.contracts.ContractViolation
import dev.arcdrift.core.model.ArcGraph
import dev.arcdrift.core.rules.RuleViolation
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

fun JsonElement?.asJsonObjectOrNull(): JsonObject? = this as? JsonObject

fun JsonElement?.asJsonArrayOrNull(): JsonArray? = this as? JsonArray

fun JsonElement?.asJsonPrimitiveOrNull(): JsonPrimitive? =
    (this as? JsonPrimitive)?.takeUnless { it is JsonNull }

@Serializable
data class JsonRpcRequest(
    val jsonrpc: String = "2.0",
    val id: JsonElement? = null,
    val method: String,
    val params: JsonObject? = null
)

@Serializable
data class JsonRpcError(
    val code: Int,
    val message: String,
    val data: JsonElement? = null
)

@Serializable
data class JsonRpcResponse(
    val jsonrpc: String = "2.0",
    val id: JsonElement = JsonNull,
    val result: JsonElement? = null,
    val error: JsonRpcError? = null
)

@Serializable
data class McpServerInfo(
    val name: String = "arcdrift-mcp",
    val version: String = "0.1.0"
)

@Serializable
data class McpCapabilities(
    val tools: Map<String, Boolean> = mapOf("listChanged" to false)
)

@Serializable
data class McpInitializeResult(
    val protocolVersion: String = "2024-11-05",
    val capabilities: McpCapabilities = McpCapabilities(),
    val serverInfo: McpServerInfo = McpServerInfo()
)

@Serializable
data class McpToolDescriptor(
    val name: String,
    val description: String,
    val inputSchema: JsonObject
)

@Serializable
data class McpListToolsResult(
    val tools: List<McpToolDescriptor>
)

@Serializable
data class McpTextContent(
    val type: String = "text",
    val text: String
)

@Serializable
data class McpCallToolResult(
    val content: List<McpTextContent>,
    val isError: Boolean = false
)

/**
 * Structured compliance payload returned by `verify_architectural_compliance`.
 */
@Serializable
data class ArchitecturalComplianceReport(
    val projectName: String,
    val passed: Boolean,
    val cycleCount: Int,
    val ruleViolationCount: Int,
    val contractViolationCount: Int,
    val cycles: List<DependencyCycle>,
    val ruleViolations: List<RuleViolation>,
    val contractViolations: List<ContractViolation>,
    val summary: String
)

/**
 * Structured diff payload returned by `propose_structural_diff`.
 */
@Serializable
data class StructuralDiffPreviewResult(
    val approvedByRules: Boolean,
    val addedNodeIds: List<String>,
    val modifiedNodeIds: List<String>,
    val removedNodeIds: List<String>,
    val blastImpactedNodeIds: List<String>,
    val couplingDeltaPercent: Double,
    val newCycleCount: Int,
    val ruleViolations: List<RuleViolation>,
    val contractViolations: List<ContractViolation>,
    val diffMermaid: String
)
