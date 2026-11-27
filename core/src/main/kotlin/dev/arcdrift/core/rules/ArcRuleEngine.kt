package dev.arcdrift.core.rules

import dev.arcdrift.core.model.*
import kotlinx.serialization.Serializable

/**
 * Represents a concrete violation of a [BoundaryRule] discovered in an [ArcGraph].
 */
@Serializable
data class RuleViolation(
    val ruleId: String,
    val ruleName: String,
    val policy: RulePolicy,
    val sourceNodeId: String,
    val targetNodeId: String,
    val offendingEdgeId: String,
    val sourceFilePath: String? = null,
    val sourceLine: Int? = null,
    val message: String,
    val remediationHint: String
)

/**
 * Evaluates declarative [BoundaryRule]s against an [ArcGraph].
 * Supports glob matching (`*`, `**`, `..`) on node IDs, ancestor containers, and `@Stereotype` annotations.
 */
object ArcRuleEngine {

    fun evaluate(graph: ArcGraph, extraRules: List<BoundaryRule> = emptyList()): List<RuleViolation> {
        val rules = (graph.rules + extraRules).distinctBy { it.id }
        if (rules.isEmpty()) return emptyList()

        val nodeMap = graph.nodeMap()
        val removedNodeIds = graph.nodes
            .filter { it.diffStatus == DiffStatus.REMOVED }
            .map { it.id }
            .toSet()
        val structuralEdges = graph.edges.filter {
            it.kind != EdgeKind.FORBIDDEN_RULE &&
                it.diffStatus != DiffStatus.REMOVED &&
                it.sourceId !in removedNodeIds &&
                it.targetId !in removedNodeIds
        }
        val violations = mutableListOf<RuleViolation>()

        for (rule in rules) {
            val fromMatcher = compilePattern(rule.fromPattern)
            val toMatcher = compilePattern(rule.toPattern)

            when (rule.policy) {
                RulePolicy.FORBIDDEN -> {
                    for (edge in structuralEdges) {
                        val srcNode = nodeMap[edge.sourceId]
                        val tgtNode = nodeMap[edge.targetId]
                        if (matchesNode(edge.sourceId, srcNode, nodeMap, fromMatcher) &&
                            matchesNode(edge.targetId, tgtNode, nodeMap, toMatcher)
                        ) {
                            violations.add(
                                RuleViolation(
                                    ruleId = rule.id,
                                    ruleName = rule.name,
                                    policy = rule.policy,
                                    sourceNodeId = edge.sourceId,
                                    targetNodeId = edge.targetId,
                                    offendingEdgeId = edge.id,
                                    sourceFilePath = srcNode?.source?.filePath,
                                    sourceLine = srcNode?.source?.startLine,
                                    message = "Rule '${rule.name}' (${rule.id}) violated: '${edge.sourceId}' must not depend on '${edge.targetId}'. ${rule.rationale}".trim(),
                                    remediationHint = "Remove direct dependency from '${edge.sourceId.substringAfterLast('.')}' to '${edge.targetId.substringAfterLast('.')}' or route through an application service / domain port interface."
                                )
                            )
                        }
                    }
                }

                RulePolicy.MUST_DEPEND_VIA_INTERFACE -> {
                    for (edge in structuralEdges) {
                        val srcNode = nodeMap[edge.sourceId]
                        val tgtNode = nodeMap[edge.targetId]
                        if (matchesNode(edge.sourceId, srcNode, nodeMap, fromMatcher) &&
                            matchesNode(edge.targetId, tgtNode, nodeMap, toMatcher)
                        ) {
                            val isTargetInterface = tgtNode?.kind == NodeKind.INTERFACE
                            if (!isTargetInterface) {
                                violations.add(
                                    RuleViolation(
                                        ruleId = rule.id,
                                        ruleName = rule.name,
                                        policy = rule.policy,
                                        sourceNodeId = edge.sourceId,
                                        targetNodeId = edge.targetId,
                                        offendingEdgeId = edge.id,
                                        sourceFilePath = srcNode?.source?.filePath,
                                        sourceLine = srcNode?.source?.startLine,
                                        message = "Rule '${rule.name}' (${rule.id}) violated: '${edge.sourceId}' depends on concrete type '${edge.targetId}' instead of an interface. ${rule.rationale}".trim(),
                                        remediationHint = "Use `execute_deterministic_refactoring(EXTRACT_INTERFACE)` on '${edge.targetId}' and depend on the extracted interface."
                                    )
                                )
                            }
                        }
                    }
                }

                RulePolicy.ALLOWED_ONLY -> {
                    // Any outgoing edge from a node matching `fromPattern` must target a node matching `toPattern` (or stay within `fromPattern`)
                    for (edge in structuralEdges) {
                        val srcNode = nodeMap[edge.sourceId]
                        val tgtNode = nodeMap[edge.targetId]
                        if (matchesNode(edge.sourceId, srcNode, nodeMap, fromMatcher)) {
                            val targetAllowed = matchesNode(edge.targetId, tgtNode, nodeMap, toMatcher) ||
                                matchesNode(edge.targetId, tgtNode, nodeMap, fromMatcher)
                            if (!targetAllowed) {
                                violations.add(
                                    RuleViolation(
                                        ruleId = rule.id,
                                        ruleName = rule.name,
                                        policy = rule.policy,
                                        sourceNodeId = edge.sourceId,
                                        targetNodeId = edge.targetId,
                                        offendingEdgeId = edge.id,
                                        sourceFilePath = srcNode?.source?.filePath,
                                        sourceLine = srcNode?.source?.startLine,
                                        message = "Rule '${rule.name}' (${rule.id}) violated: '${edge.sourceId}' is only allowed to depend on '${rule.toPattern}', but depends on '${edge.targetId}'. ${rule.rationale}".trim(),
                                        remediationHint = "Remove unauthorized dependency on '${edge.targetId}'."
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }

        return violations
    }

    /**
     * Marks any nodes and edges involved in [RuleViolation]s with `DiffStatus.VIOLATION`
     * and attaches human-readable violation descriptions to `violations`.
     */
    fun annotateViolations(graph: ArcGraph, violations: List<RuleViolation>): ArcGraph {
        val violationsByEdgeId = violations.groupBy { it.offendingEdgeId }
        val nodeViolationReasons = linkedMapOf<String, MutableList<String>>()
        for (v in violations) {
            val srcShort = v.sourceNodeId.substringAfterLast('.')
            val tgtShort = v.targetNodeId.substringAfterLast('.')
            nodeViolationReasons
                .getOrPut(v.sourceNodeId) { mutableListOf() }
                .add("[${v.ruleId}] ${v.ruleName}: '$srcShort' -> '$tgtShort' is ${v.policy.name}")
            nodeViolationReasons
                .getOrPut(v.targetNodeId) { mutableListOf() }
                .add("[${v.ruleId}] Illegal dependency from '$srcShort' (${v.ruleName})")
        }

        return graph.copy(
            nodes = graph.nodes.map { n ->
                val nonRuleViolations = n.violations.filterNot(::isRuleViolationSummary)
                val reasons = nodeViolationReasons[n.id].orEmpty()
                val mergedViolations = (nonRuleViolations + reasons).distinct()
                val nextStatus = when {
                    mergedViolations.isNotEmpty() -> DiffStatus.VIOLATION
                    n.diffStatus == DiffStatus.VIOLATION && n.violations.isNotEmpty() -> DiffStatus.UNCHANGED
                    else -> n.diffStatus
                }
                n.copy(
                    diffStatus = nextStatus,
                    violations = mergedViolations
                )
            },
            edges = graph.edges.map { e ->
                val nonRuleViolations = e.violations.filterNot { it.startsWith("Rule '") }
                val edgeViolations = violationsByEdgeId[e.id].orEmpty()
                val mergedViolations = (nonRuleViolations + edgeViolations.map { it.message }).distinct()
                if (edgeViolations.isNotEmpty()) {
                    val reasons = edgeViolations.map { "[${it.ruleId}] ${it.ruleName}" }
                    e.copy(
                        diffStatus = DiffStatus.VIOLATION,
                        label = e.label ?: reasons.first(),
                        violations = mergedViolations
                    )
                } else {
                    val nextStatus = if (mergedViolations.isEmpty() && e.diffStatus == DiffStatus.VIOLATION && e.violations.isNotEmpty()) {
                        DiffStatus.UNCHANGED
                    } else {
                        e.diffStatus
                    }
                    val cleanedLabel = if (e.label != null && isRuleEdgeLabel(e.label, e.violations)) null else e.label
                    e.copy(
                        diffStatus = nextStatus,
                        label = cleanedLabel,
                        violations = mergedViolations
                    )
                }
            }
        )
    }

    private fun isRuleViolationSummary(text: String): Boolean {
        if (!text.startsWith("[")) return false
        val closeIdx = text.indexOf("] ")
        if (closeIdx <= 1) return false
        val body = text.substring(closeIdx + 2)
        return body.contains(": '") || body.startsWith("Illegal dependency from '") || body.startsWith("Required dependency ")
    }

    private fun isRuleEdgeLabel(label: String, previousViolations: List<String>): Boolean {
        if (!label.startsWith("[")) return false
        val closeIdx = label.indexOf("] ")
        if (closeIdx <= 1) return false
        val ruleId = label.substring(1, closeIdx)
        return ruleId.isNotBlank() && previousViolations.any { it.startsWith("Rule '") && it.contains("($ruleId)") }
    }

    private data class CompiledPattern(
        val regex: Regex,
        val isUnqualifiedSimplePattern: Boolean
    )

    private fun matchesNode(
        nodeId: String,
        node: ArcNode?,
        nodeMap: Map<String, ArcNode>,
        compiled: CompiledPattern
    ): Boolean {
        val regex = compiled.regex
        if (regex.matches(nodeId)) return true
        if (compiled.isUnqualifiedSimplePattern && regex.matches(nodeId.substringAfterLast('.'))) return true
        if (node != null) {
            if (regex.matches(node.label)) return true
            if (node.stereotypes.any { regex.matches(it) }) return true
            val visitedParents = mutableSetOf(node.id)
            var parentId = node.parentId
            while (parentId != null && visitedParents.add(parentId)) {
                if (regex.matches(parentId)) return true
                parentId = nodeMap[parentId]?.parentId
            }
        }
        return false
    }

    private fun compilePattern(glob: String): CompiledPattern {
        val raw = glob.trim()
        val isSimple = !raw.contains('.') && !raw.startsWith("@")
        return CompiledPattern(patternToRegex(raw), isSimple)
    }

    internal fun patternToRegex(glob: String): Regex {
        var normalized = glob.trim()
        if (normalized in setOf("*", "**", "..", "*.", ".*", "**.", ".**", "..*", "*..", "*..*")) {
            return Regex("^.*$", RegexOption.IGNORE_CASE)
        }
        // Support ArchUnit-style `..`, `..*`, `*..`, and `**` package boundary wildcards
        if (normalized.startsWith("*..")) {
            normalized = "*." + normalized.removePrefix("*..")
        } else if (normalized.startsWith("..")) {
            normalized = "*." + normalized.removePrefix("..")
        } else if (normalized.startsWith("**.")) {
            normalized = "*." + normalized.removePrefix("**.")
        }
        if (normalized.endsWith("..*")) {
            normalized = normalized.removeSuffix("..*") + ".*"
        } else if (normalized.endsWith("..")) {
            normalized = normalized.removeSuffix("..") + ".*"
        } else if (normalized.endsWith(".**")) {
            normalized = normalized.removeSuffix(".**") + ".*"
        }

        val optionalLeadingPrefix = normalized.startsWith("*.")
        if (optionalLeadingPrefix) {
            normalized = normalized.removePrefix("*.")
        }
        val optionalTrailingSuffix = normalized.endsWith(".*")
        if (optionalTrailingSuffix) {
            normalized = normalized.removeSuffix(".*")
        }

        if (normalized.isEmpty() || normalized == "*" || normalized == "**") {
            return Regex("^.*$", RegexOption.IGNORE_CASE)
        }

        val sb = StringBuilder("^")
        if (optionalLeadingPrefix) {
            sb.append("(?:.*\\.)?")
        }
        var i = 0
        while (i < normalized.length) {
            when (val c = normalized[i]) {
                '.' -> {
                    if (i + 1 < normalized.length && normalized[i + 1] == '.') {
                        // ArchUnit `..` between segments matches `.` or `.any.nested.pkg.`
                        sb.append("\\.(?:.*\\.)?")
                        i++
                    } else {
                        sb.append("\\.")
                    }
                }
                '*' -> {
                    if (i + 1 < normalized.length && normalized[i + 1] == '*') {
                        sb.append(".*")
                        i++
                    } else {
                        // Single `*` matches within a single package/identifier segment (`[^.]*`)
                        sb.append("[^.]*")
                    }
                }
                '?' -> sb.append("[^.]")
                '(', ')', '[', ']', '{', '}', '+', '^', '$', '|', '\\' -> {
                    sb.append('\\').append(c)
                }
                else -> sb.append(c)
            }
            i++
        }
        if (optionalTrailingSuffix) {
            sb.append("(?:\\..*)?")
        }
        sb.append("$")
        return Regex(sb.toString(), RegexOption.IGNORE_CASE)
    }
}
