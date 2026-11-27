package dev.arcdrift.core.model

import kotlinx.serialization.Serializable

/**
 * C4 semantic zoom level for architectural synthesis.
 */
@Serializable
enum class C4Level {
    L1_SYSTEM,
    L2_CONTAINER,
    L3_COMPONENT,
    L4_CODE
}

/**
 * Semantic classification of an architectural graph node.
 */
@Serializable
enum class NodeKind {
    SYSTEM,
    MODULE,
    PACKAGE,
    SERVICE_BEAN,
    REST_CONTROLLER,
    REPOSITORY,
    ENTITY,
    MESSAGE_LISTENER,
    CLASS,
    INTERFACE,
    DATA_CLASS,
    ENUM,
    TS_CLIENT,
    PYTHON_CONSUMER,
    GHOST_SPEC
}

/**
 * Diff & compliance status used for visual overlays and PR blast-radius reporting.
 */
@Serializable
enum class DiffStatus {
    UNCHANGED,
    ADDED,
    MODIFIED,
    REMOVED,
    VIOLATION,
    BLAST_IMPACTED
}

/**
 * Source file location reference (always relative to project root).
 */
@Serializable
data class SourceLocation(
    val filePath: String,
    val startLine: Int = 1,
    val endLine: Int = 1,
    val language: String = "kotlin"
)

/**
 * Member signature inside a component or class node.
 */
@Serializable
data class MemberSignature(
    val name: String,
    val kind: String, // "METHOD" | "FIELD" | "ENDPOINT"
    val typeOrSignature: String,
    val visibility: String = "public"
)

/**
 * Cross-language or cross-service contract endpoint metadata.
 */
@Serializable
data class ContractEndpoint(
    val protocol: String,      // "REST" | "GRPC" | "KAFKA"
    val methodOrAction: String, // "GET", "POST", "PUT", "DELETE", "PUBLISH", "CONSUME"
    val pathOrTopic: String,    // e.g. "/v1/orders/{id}"
    val role: String = "PRODUCER", // "PRODUCER" | "CONSUMER"
    val requestDto: String? = null,
    val responseDto: String? = null,
    val dtoFields: Map<String, String> = emptyMap(), // fieldName -> typeName
    val requestDtoFields: Map<String, String> = emptyMap(), // request payload fieldName -> typeName
    val targetHost: String? = null // optional external hostname extracted from absolute HTTP/HTTPS consumer URLs
)

/**
 * Canonical semantic node in an ArcDrift graph.
 */
@Serializable
data class ArcNode(
    val id: String,
    val label: String,
    val c4Level: C4Level = C4Level.L3_COMPONENT,
    val kind: NodeKind = NodeKind.CLASS,
    val parentId: String? = null,
    val stereotypes: List<String> = emptyList(),
    val members: List<MemberSignature> = emptyList(),
    val contracts: List<ContractEndpoint> = emptyList(),
    val source: SourceLocation? = null,
    val diffStatus: DiffStatus = DiffStatus.UNCHANGED,
    val violations: List<String> = emptyList(),
    val layoutX: Double? = null,
    val layoutY: Double? = null
)

/**
 * Well-known edge kinds in ArcDrift graphs.
 */
object EdgeKind {
    const val DEPENDS_ON = "DEPENDS_ON"
    const val EXTENDS = "EXTENDS"
    const val IMPLEMENTS = "IMPLEMENTS"
    const val CALLS = "CALLS"
    const val HTTP_CONTRACT = "HTTP_CONTRACT"
    const val FORBIDDEN_RULE = "FORBIDDEN_RULE"
}

/**
 * Directed relationship between two [ArcNode]s.
 */
@Serializable
data class ArcEdge(
    val id: String,
    val sourceId: String,
    val targetId: String,
    val kind: String = EdgeKind.DEPENDS_ON,
    val label: String? = null,
    val weight: Int = 1,
    val inCycle: Boolean = false,
    val diffStatus: DiffStatus = DiffStatus.UNCHANGED,
    val violations: List<String> = emptyList()
)

/**
 * Architectural rule enforcement policy.
 */
@Serializable
enum class RulePolicy {
    FORBIDDEN,
    ALLOWED_ONLY,
    MUST_DEPEND_VIA_INTERFACE
}

/**
 * Declarative architectural boundary rule stored in `.arc.mmd` files.
 */
@Serializable
data class BoundaryRule(
    val id: String,
    val name: String,
    val fromPattern: String,
    val toPattern: String,
    val policy: RulePolicy = RulePolicy.FORBIDDEN,
    val rationale: String = ""
)

/**
 * Complete immutable architectural graph model.
 */
@Serializable
data class ArcGraph(
    val projectName: String,
    val nodes: List<ArcNode> = emptyList(),
    val edges: List<ArcEdge> = emptyList(),
    val rules: List<BoundaryRule> = emptyList(),
    val metadata: Map<String, String> = emptyMap()
) {
    fun nodeMap(): Map<String, ArcNode> = nodes.associateBy { it.id }
}
