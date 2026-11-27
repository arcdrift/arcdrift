package dev.arcdrift.core.codec

import dev.arcdrift.core.model.*

/**
 * Serializes an [ArcGraph] into a Git-native, deterministic `.arc.mmd` Mermaid file.
 * All semantic metadata (C4 level, node kind, coordinates, stereotypes, boundary rules)
 * is embedded in standard `%% @arc:` comments so the file renders natively in GitHub/GitLab.
 */
object ArcMermaidSerializer {

    fun serialize(graph: ArcGraph): String {
        val sb = StringBuilder()
        sb.appendLine("flowchart TB")
        val versionAttr = graph.metadata["version"]?.let { " version=\"${escapeAttr(it)}\"" } ?: ""
        sb.appendLine("    %% @arc:project name=\"${escapeAttr(graph.projectName)}\"$versionAttr")

        for ((key, value) in graph.metadata.entries.sortedBy { it.key }) {
            if (key != "version") {
                sb.appendLine("    %% @arc:meta key=\"${escapeAttr(key)}\" value=\"${escapeAttr(value)}\"")
            }
        }

        val sortedRules = graph.rules.sortedBy { it.id }
        if (sortedRules.isNotEmpty()) {
            sb.appendLine()
            for (rule in sortedRules) {
                sb.appendLine(
                    "    %% @arc:rule id=\"${escapeAttr(rule.id)}\" " +
                        "name=\"${escapeAttr(rule.name)}\" " +
                        "from=\"${escapeAttr(rule.fromPattern)}\" " +
                        "to=\"${escapeAttr(rule.toPattern)}\" " +
                        "policy=\"${rule.policy.name}\" " +
                        "rationale=\"${escapeAttr(rule.rationale)}\""
                )
            }
        }

        val sortedNodes = graph.nodes.sortedBy { it.id }
        val nodesById = sortedNodes.associateBy { it.id }
        val nodesByParent = sortedNodes.groupBy { it.parentId }

        val allReferencedIds = (sortedNodes.map { it.id } + graph.edges.flatMap { listOf(it.sourceId, it.targetId) }).distinct()
        val safeMermaidIds = allocateUniqueMermaidIds(allReferencedIds)
        fun mermaidIdFor(id: String): String = safeMermaidIds[id] ?: toMermaidId(id)

        val topLevelNodes = nodesByParent[null].orEmpty()
        // Only treat parentIds that correspond to actual graph nodes as subgraph containers;
        // orphan parentIds remain recorded in child `@arc:node parent="..."` directives without synthesizing phantom MODULE nodes.
        val parentIds = nodesByParent.keys.filterNotNull().filter { it in nodesById }.toSet()

        if (sortedNodes.isNotEmpty()) {
            sb.appendLine()
        }

        val emittedNodeIds = mutableSetOf<String>()

        fun emitSubgraphOrNode(nodeId: String, indent: String) {
            if (!emittedNodeIds.add(nodeId)) return
            val node = nodesById[nodeId] ?: return
            val children = nodesByParent[nodeId].orEmpty().filter { it.id != nodeId }
            if (nodeId in parentIds && children.isNotEmpty()) {
                val subgraphSafeId = mermaidIdFor(nodeId)
                val subgraphTitle = "${node.label} (${node.kind.name})"
                appendNodeDirective(sb, indent, node)
                sb.appendLine("${indent}subgraph $subgraphSafeId[\"${escapeMermaidLabel(subgraphTitle)}\"]")
                for (child in children) {
                    emitSubgraphOrNode(child.id, "$indent    ")
                }
                sb.appendLine("${indent}end")
            } else {
                appendNode(sb, indent, node, mermaidIdFor(node.id))
            }
        }

        // 1. Emit root subgraphs (parentIds whose parent is null or not an active subgraph parent)
        val rootParentIds = parentIds
            .filter { pid ->
                val pNode = nodesById[pid]
                pNode == null || pNode.parentId == null || pNode.parentId == pid || pNode.parentId !in parentIds
            }
            .sorted()
        for (parentId in rootParentIds) {
            emitSubgraphOrNode(parentId, "    ")
        }

        // 2. Emit any remaining subgraphs (e.g. cyclic parentId references)
        for (parentId in parentIds.sorted()) {
            if (parentId !in emittedNodeIds) {
                emitSubgraphOrNode(parentId, "    ")
            }
        }

        // 3. Emit remaining top-level nodes not already emitted as subgraph headers
        for (node in topLevelNodes) {
            if (node.id !in emittedNodeIds) {
                emitSubgraphOrNode(node.id, "    ")
            }
        }

        // 4. Emit any nodes whose parentId was an orphan (not in graph.nodes) or not yet emitted
        for (node in sortedNodes) {
            if (node.id !in emittedNodeIds) {
                emitSubgraphOrNode(node.id, "    ")
            }
        }

        val sortedEdges = graph.edges.sortedWith(compareBy({ it.sourceId }, { it.targetId }, { it.id }))
        if (sortedEdges.isNotEmpty()) {
            sb.appendLine()
            for (edge in sortedEdges) {
                val labelAttr = edge.label?.let { " label=\"${escapeAttr(it)}\"" } ?: ""
                val violationsAttr = if (edge.violations.isNotEmpty()) {
                    val encodedViolations = edge.violations.joinToString("||") { encodeViolationEntry(it) }
                    " violations=\"${escapeAttr(encodedViolations)}\""
                } else {
                    ""
                }
                sb.appendLine(
                    "    %% @arc:edge id=\"${escapeAttr(edge.id)}\" " +
                        "source=\"${escapeAttr(edge.sourceId)}\" " +
                        "target=\"${escapeAttr(edge.targetId)}\" " +
                        "kind=\"${escapeAttr(edge.kind)}\" " +
                        "weight=\"${edge.weight}\" " +
                        "inCycle=\"${edge.inCycle}\" " +
                        "diff=\"${edge.diffStatus.name}\"$labelAttr$violationsAttr"
                )
                val srcSafe = mermaidIdFor(edge.sourceId)
                val tgtSafe = mermaidIdFor(edge.targetId)
                val arrow = when {
                    edge.kind == EdgeKind.FORBIDDEN_RULE || edge.diffStatus == DiffStatus.VIOLATION -> "-.->|\"${escapeMermaidLabel(edge.label ?: "VIOLATION")}\"|"
                    edge.kind == EdgeKind.IMPLEMENTS -> "-.->|\"${escapeMermaidLabel(edge.label ?: "implements")}\"|"
                    edge.label != null -> "-->|\"${escapeMermaidLabel(edge.label)}\"|"
                    else -> "-->"
                }
                sb.appendLine("    $srcSafe $arrow $tgtSafe")
            }
        }

        return sb.toString().trimEnd() + "\n"
    }

    private fun allocateUniqueMermaidIds(ids: List<String>): Map<String, String> {
        val result = linkedMapOf<String, String>()
        val used = mutableSetOf<String>()
        for (id in ids) {
            val base = toMermaidId(id)
            var candidate = base
            var suffix = 2
            while (!used.add(candidate)) {
                candidate = "${base}_$suffix"
                suffix++
            }
            result[id] = candidate
        }
        return result
    }

    private fun appendNodeDirective(sb: StringBuilder, indent: String, node: ArcNode) {
        val posAttr = when {
            node.layoutX != null && node.layoutY != null -> " pos=\"${node.layoutX},${node.layoutY}\""
            node.layoutX != null -> " x=\"${node.layoutX}\""
            node.layoutY != null -> " y=\"${node.layoutY}\""
            else -> ""
        }
        val parentAttr = node.parentId?.let { " parent=\"${escapeAttr(it)}\"" } ?: ""
        val stereoAttr = if (node.stereotypes.isNotEmpty()) {
            val encodedStereos = node.stereotypes.joinToString(",") { encodeStereotypeEntry(it) }
            " stereotypes=\"${escapeAttr(encodedStereos)}\""
        } else {
            ""
        }
        val fileAttr = node.source?.let {
            " file=\"${escapeAttr(it.filePath)}:${it.startLine}:${it.endLine}:${escapeAttr(it.language)}\""
        } ?: ""
        val membersAttr = if (node.members.isNotEmpty()) {
            val encoded = node.members.joinToString(";") { m ->
                val encKind = encodeMemberSegment(m.kind)
                val encName = encodeMemberSegment(m.name)
                val encSig = encodeSemicolonPercent(m.typeOrSignature)
                val encVis = encodeMemberSegment(m.visibility)
                "$encKind:$encName:$encSig:$encVis"
            }
            " members=\"${escapeAttr(encoded)}\""
        } else {
            ""
        }
        val contractsAttr = if (node.contracts.isNotEmpty()) {
            val encoded = node.contracts.joinToString(";") { c ->
                val fieldsStr = c.dtoFields.entries.joinToString(",") {
                    "${encodeContractMapKey(it.key)}=${encodeContractFieldValue(it.value)}"
                }
                val reqFieldsStr = c.requestDtoFields.entries.joinToString(",") {
                    "${encodeContractMapKey(it.key)}=${encodeContractFieldValue(it.value)}"
                }
                val base = listOf(
                    encodeContractColumn(c.protocol),
                    encodeContractColumn(c.methodOrAction),
                    encodeContractColumn(c.pathOrTopic),
                    encodeContractColumn(c.role),
                    encodeContractColumn(c.requestDto.orEmpty()),
                    encodeContractColumn(c.responseDto.orEmpty()),
                    fieldsStr
                ).joinToString("|")
                if (reqFieldsStr.isNotEmpty()) "$base|$reqFieldsStr" else base
            }
            " contracts=\"${escapeAttr(encoded)}\""
        } else {
            ""
        }
        val violationsAttr = if (node.violations.isNotEmpty()) {
            val encodedViolations = node.violations.joinToString("||") { encodeViolationEntry(it) }
            " violations=\"${escapeAttr(encodedViolations)}\""
        } else {
            ""
        }

        sb.appendLine(
            "$indent%% @arc:node id=\"${escapeAttr(node.id)}\" " +
                "label=\"${escapeAttr(node.label)}\" " +
                "level=\"${node.c4Level.name}\" " +
                "kind=\"${node.kind.name}\" " +
                "diff=\"${node.diffStatus.name}\"" +
                "$parentAttr$stereoAttr$posAttr$fileAttr$membersAttr$contractsAttr$violationsAttr"
        )
    }

    private fun appendNode(sb: StringBuilder, indent: String, node: ArcNode, safeId: String) {
        appendNodeDirective(sb, indent, node)
        val stereoSuffix = if (node.stereotypes.isNotEmpty()) {
            "<br/><i>«${escapeMermaidLabel(node.stereotypes.joinToString(", "))}»</i>"
        } else {
            ""
        }
        sb.appendLine("$indent$safeId[\"${escapeMermaidLabel(node.label)}$stereoSuffix\"]")
    }

    internal fun encodeStereotypeEntry(raw: String): String =
        raw.replace("%", "%25").replace(",", "%2C")

    internal fun encodeViolationEntry(raw: String): String =
        raw.replace("%", "%25").replace("|", "%7C")

    internal fun encodeMemberSegment(raw: String): String =
        raw.replace("%", "%25").replace(";", "%3B").replace(":", "%3A")

    internal fun encodeSemicolonPercent(raw: String): String =
        raw.replace("%", "%25").replace(";", "%3B")

    internal fun encodeContractColumn(raw: String): String =
        raw.replace("%", "%25").replace("|", "%7C").replace(";", "%3B")

    internal fun encodeContractMapKey(raw: String): String =
        raw.replace("%", "%25")
            .replace("=", "%3D")
            .replace(",", "%2C")
            .replace("|", "%7C")
            .replace(";", "%3B")

    internal fun encodeContractFieldValue(raw: String): String =
        raw.replace("%", "%25")
            .replace("|", "%7C")
            .replace(";", "%3B")
            .replace(",", "%2C")

    private val MERMAID_RESERVED_KEYWORDS = setOf(
        "end", "subgraph", "graph", "flowchart", "style", "class", "classdef", "click", "direction"
    )

    fun toMermaidId(id: String): String {
        val sanitized = id.replace(Regex("[^a-zA-Z0-9_]"), "_")
        return if (sanitized.lowercase() in MERMAID_RESERVED_KEYWORDS) "node_$sanitized" else sanitized
    }

    private fun escapeAttr(raw: String): String =
        raw
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")

    private fun escapeMermaidLabel(raw: String): String =
        raw.replace("\"", "'").replace("\n", " ").replace("\r", "")
}
