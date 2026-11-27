package dev.arcdrift.core.codec

import dev.arcdrift.core.model.*

/**
 * Parses `.arc.mmd` files (both `@arc:` directive-enriched Mermaid files and raw Mermaid flowcharts)
 * back into an immutable [ArcGraph].
 */
object ArcMermaidParser {

    private val ATTR_REGEX = Regex("""([a-zA-Z0-9_]+)="((?:[^"\\]|\\.)*)"""")
    private val RAW_NODE_REGEX = Regex("""^\s*([a-zA-Z0-9_.-]+)\s*\[\s*(?:"([^"]*)"|([^"\]]+?))\s*]\s*$""")
    private val RAW_EDGE_REGEX = Regex(
        """^\s*([a-zA-Z0-9_.-]+)\s*(-+>|-\.->|==>)\s*(?:\|\s*(?:"([^"]*)"|([^"|]+?))\s*\|\s*)?([a-zA-Z0-9_.-]+)\s*$"""
    )
    private val SUBGRAPH_START_REGEX = Regex("""^\s*subgraph\s+([a-zA-Z0-9_.-]+)(?:\s*\[\s*"?([^"\]]+?)"?\s*])?\s*$""")

    private val HEADER_REGEX = Regex("""^(?:flowchart|graph)\s+.*$""")
    private val DIRECTIVE_PREFIX_REGEX = Regex("""^%%\s*@arc:([a-zA-Z0-9_]+)(?:\s+(.*))?$""")

    fun parse(content: String): ArcGraph {
        var projectName = "unnamed-project"
        val metadata = mutableMapOf<String, String>()
        val rules = mutableListOf<BoundaryRule>()
        val nodesById = linkedMapOf<String, ArcNode>()
        val mermaidIdToCanonicalId = mutableMapOf<String, String>()
        val edges = mutableListOf<ArcEdge>()

        val subgraphStack = ArrayDeque<String>()
        var pendingEdgeDirective: Map<String, String>? = null
        var lastNodeDirectiveId: String? = null
        var autoEdgeCounter = 1

        for (rawLine in content.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty() || HEADER_REGEX.matches(line)) {
                continue
            }

            val directiveMatch = DIRECTIVE_PREFIX_REGEX.matchEntire(line)
            if (directiveMatch != null) {
                val type = directiveMatch.groupValues[1].trim()
                val attrs = parseAttributes(directiveMatch.groupValues[2].trim())

                when (type) {
                    "project" -> {
                        projectName = attrs["name"] ?: projectName
                        attrs["version"]?.let { metadata["version"] = it }
                    }
                    "meta" -> {
                        val k = attrs["key"]
                        val v = attrs["value"]
                        if (k != null && v != null) {
                            metadata[k] = v
                        }
                    }
                    "rule" -> {
                        val id = attrs["id"] ?: "rule-${rules.size + 1}"
                        val policy = attrs["policy"]?.let {
                            runCatching { RulePolicy.valueOf(it) }.getOrNull()
                        } ?: RulePolicy.FORBIDDEN
                        rules.add(
                            BoundaryRule(
                                id = id,
                                name = attrs["name"] ?: id,
                                fromPattern = attrs["from"] ?: "*",
                                toPattern = attrs["to"] ?: "*",
                                policy = policy,
                                rationale = attrs["rationale"] ?: ""
                            )
                        )
                    }
                    "node" -> {
                        pendingEdgeDirective = null
                        val id = attrs["id"] ?: continue
                        val label = attrs["label"] ?: id.substringAfterLast('.')
                        val level = attrs["level"]?.let {
                            runCatching { C4Level.valueOf(it) }.getOrNull()
                        } ?: C4Level.L3_COMPONENT
                        val kind = attrs["kind"]?.let {
                            runCatching { NodeKind.valueOf(it) }.getOrNull()
                        } ?: NodeKind.CLASS
                        val diff = attrs["diff"]?.let {
                            runCatching { DiffStatus.valueOf(it) }.getOrNull()
                        } ?: DiffStatus.UNCHANGED
                        val stereotypes = attrs["stereotypes"]
                            ?.split(",")
                            ?.map { decodeStereotypeEntry(it.trim()) }
                            ?.filter { it.isNotEmpty() }
                            .orEmpty()
                        val posParts = attrs["pos"]?.split(",")
                        val layoutX = posParts?.getOrNull(0)?.toDoubleOrNull() ?: attrs["x"]?.toDoubleOrNull()
                        val layoutY = posParts?.getOrNull(1)?.toDoubleOrNull() ?: attrs["y"]?.toDoubleOrNull()
                        val sourceLoc = attrs["file"]?.let { parseSourceLocation(it) }
                        val members = attrs["members"]?.let { parseMembers(it) }.orEmpty()
                        val contracts = attrs["contracts"]?.let { parseContracts(it) }.orEmpty()
                        val violations = attrs["violations"]
                            ?.split("||")
                            ?.filter { it.isNotEmpty() }
                            ?.map(::decodeViolationEntry)
                            .orEmpty()
                        val parentId = attrs["parent"] ?: subgraphStack.lastOrNull()

                        val node = ArcNode(
                            id = id,
                            label = label,
                            c4Level = level,
                            kind = kind,
                            parentId = parentId,
                            stereotypes = stereotypes,
                            members = members,
                            contracts = contracts,
                            source = sourceLoc,
                            diffStatus = diff,
                            violations = violations,
                            layoutX = layoutX,
                            layoutY = layoutY
                        )
                        nodesById[id] = node
                        mermaidIdToCanonicalId.putIfAbsent(ArcMermaidSerializer.toMermaidId(id), id)
                        lastNodeDirectiveId = id
                    }
                    "member" -> {
                        val targetNodeId = attrs["node"] ?: lastNodeDirectiveId
                        val mName = attrs["name"]
                        if (targetNodeId != null && !mName.isNullOrBlank()) {
                            val existingNode = nodesById[targetNodeId]
                            if (existingNode != null) {
                                val member = MemberSignature(
                                    name = mName,
                                    kind = attrs["kind"] ?: "METHOD",
                                    typeOrSignature = attrs["sig"] ?: attrs["signature"] ?: "(): Unit",
                                    visibility = attrs["vis"] ?: attrs["visibility"] ?: "public"
                                )
                                nodesById[targetNodeId] = existingNode.copy(
                                    members = existingNode.members + member
                                )
                            }
                        }
                    }
                    "edge" -> {
                        pendingEdgeDirective = attrs
                    }
                }
                continue
            }

            if (line.startsWith("%%")) {
                continue
            }

            if (line == "end") {
                if (subgraphStack.isNotEmpty()) {
                    subgraphStack.removeLast()
                }
                lastNodeDirectiveId = null
                pendingEdgeDirective = null
                continue
            }

            val subgraphMatch = SUBGRAPH_START_REGEX.matchEntire(line)
            if (subgraphMatch != null) {
                pendingEdgeDirective = null
                val rawSubId = subgraphMatch.groupValues[1]
                val rawSubTitle = subgraphMatch.groupValues[2].ifEmpty { rawSubId }
                val canonicalId = lastNodeDirectiveId
                    ?: mermaidIdToCanonicalId[rawSubId]
                    ?: rawSubId
                mermaidIdToCanonicalId[rawSubId] = canonicalId
                if (canonicalId !in nodesById) {
                    nodesById[canonicalId] = ArcNode(
                        id = canonicalId,
                        label = rawSubTitle.substringBeforeLast(" (").trim(),
                        c4Level = C4Level.L2_CONTAINER,
                        kind = NodeKind.MODULE,
                        parentId = subgraphStack.lastOrNull()
                    )
                }
                subgraphStack.addLast(canonicalId)
                lastNodeDirectiveId = null
                continue
            }

            val edgeMatch = RAW_EDGE_REGEX.matchEntire(line)
            if (edgeMatch != null) {
                val rawSrc = edgeMatch.groupValues[1]
                val arrow = edgeMatch.groupValues[2]
                val rawLabel = edgeMatch.groupValues[3].ifEmpty { edgeMatch.groupValues[4] }.takeIf { it.isNotEmpty() }
                val rawTgt = edgeMatch.groupValues[5]

                val directive = pendingEdgeDirective
                pendingEdgeDirective = null
                lastNodeDirectiveId = null

                val srcId = directive?.get("source")
                    ?: mermaidIdToCanonicalId[rawSrc]
                    ?: ensureFallbackNode(rawSrc, subgraphStack.lastOrNull(), nodesById, mermaidIdToCanonicalId)
                val tgtId = directive?.get("target")
                    ?: mermaidIdToCanonicalId[rawTgt]
                    ?: ensureFallbackNode(rawTgt, subgraphStack.lastOrNull(), nodesById, mermaidIdToCanonicalId)

                val edgeId = directive?.get("id") ?: "e${autoEdgeCounter++}"
                // When an `@arc:edge` directive is present, preserve `label == null` if `label="..."` was omitted
                val edgeLabel = if (directive != null) directive["label"] else rawLabel
                val kind = directive?.get("kind") ?: when {
                    rawLabel?.equals("implements", ignoreCase = true) == true -> EdgeKind.IMPLEMENTS
                    rawLabel?.equals("VIOLATION", ignoreCase = true) == true -> EdgeKind.FORBIDDEN_RULE
                    arrow == "-.->" -> EdgeKind.IMPLEMENTS
                    else -> EdgeKind.DEPENDS_ON
                }
                val weight = directive?.get("weight")?.toIntOrNull() ?: 1
                val inCycle = directive?.get("inCycle")?.toBooleanStrictOrNull() ?: false
                val diff = directive?.get("diff")?.let {
                    runCatching { DiffStatus.valueOf(it) }.getOrNull()
                } ?: DiffStatus.UNCHANGED
                val edgeViolations = directive?.get("violations")
                    ?.split("||")
                    ?.filter { it.isNotEmpty() }
                    ?.map(::decodeViolationEntry)
                    .orEmpty()

                edges.add(
                    ArcEdge(
                        id = edgeId,
                        sourceId = srcId,
                        targetId = tgtId,
                        kind = kind,
                        label = edgeLabel,
                        weight = weight,
                        inCycle = inCycle,
                        diffStatus = diff,
                        violations = edgeViolations
                    )
                )
                continue
            }

            val nodeMatch = RAW_NODE_REGEX.matchEntire(line)
            if (nodeMatch != null) {
                pendingEdgeDirective = null
                val rawId = nodeMatch.groupValues[1]
                val rawLabelHtml = nodeMatch.groupValues[2].ifEmpty { nodeMatch.groupValues[3] }
                val cleanLabel = rawLabelHtml.substringBefore("<br/>").trim()
                val canonicalId = lastNodeDirectiveId ?: mermaidIdToCanonicalId[rawId] ?: rawId
                mermaidIdToCanonicalId[rawId] = canonicalId

                if (canonicalId !in nodesById) {
                    val extractedStereotypes = extractInlineStereotypes(rawLabelHtml)
                    nodesById[canonicalId] = ArcNode(
                        id = canonicalId,
                        label = cleanLabel,
                        c4Level = C4Level.L3_COMPONENT,
                        kind = NodeKind.CLASS,
                        parentId = subgraphStack.lastOrNull(),
                        stereotypes = extractedStereotypes
                    )
                }
                lastNodeDirectiveId = null
            } else {
                pendingEdgeDirective = null
            }
        }

        return ArcGraph(
            projectName = projectName,
            nodes = nodesById.values.toList(),
            edges = edges,
            rules = rules,
            metadata = metadata
        )
    }

    private fun ensureFallbackNode(
        rawId: String,
        parentId: String?,
        nodesById: MutableMap<String, ArcNode>,
        mermaidIdToCanonicalId: MutableMap<String, String>
    ): String {
        val existing = mermaidIdToCanonicalId[rawId]
        if (existing != null) return existing
        mermaidIdToCanonicalId[rawId] = rawId
        if (rawId !in nodesById) {
            nodesById[rawId] = ArcNode(
                id = rawId,
                label = rawId.substringAfterLast('.'),
                c4Level = C4Level.L3_COMPONENT,
                kind = NodeKind.CLASS,
                parentId = parentId
            )
        }
        return rawId
    }

    private fun extractInlineStereotypes(rawLabelHtml: String): List<String> {
        val start = rawLabelHtml.indexOf('«')
        val end = rawLabelHtml.indexOf('»')
        if (start in 0 until end) {
            return rawLabelHtml.substring(start + 1, end)
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        }
        return emptyList()
    }

    private fun parseSourceLocation(raw: String): SourceLocation? {
        val parts = raw.split(":")
        if (parts.isEmpty()) return null
        if (parts.size >= 4) {
            val startLine = parts[parts.size - 3].toIntOrNull()
            val endLine = parts[parts.size - 2].toIntOrNull()
            if (startLine != null && endLine != null) {
                return SourceLocation(
                    filePath = parts.dropLast(3).joinToString(":"),
                    startLine = startLine,
                    endLine = endLine,
                    language = parts.last()
                )
            }
        }
        return SourceLocation(
            filePath = parts[0],
            startLine = parts.getOrNull(1)?.toIntOrNull() ?: 1,
            endLine = parts.getOrNull(2)?.toIntOrNull() ?: 1,
            language = parts.getOrNull(3) ?: "kotlin"
        )
    }

    private fun parseMembers(raw: String): List<MemberSignature> {
        if (raw.isBlank()) return emptyList()
        return raw.split(";").mapNotNull { entry ->
            val parts = entry.split(":")
            if (parts.size < 3) return@mapNotNull null
            val kind = decodeMemberSegment(parts[0])
            val name = decodeMemberSegment(parts[1])
            val lastRaw = parts.last()
            // Treat trailing segment as visibility when >= 4 colon-separated parts and last segment is a word/encoded visibility
            val hasExplicitVis = parts.size >= 4 && !lastRaw.contains(' ') && !lastRaw.contains('(') && !lastRaw.contains(')')
            val visibility = if (hasExplicitVis) decodeMemberSegment(lastRaw) else "public"
            val sigEndIdx = if (hasExplicitVis) parts.size - 1 else parts.size
            val typeOrSignature = decodeSemicolonPercent(parts.subList(2, sigEndIdx).joinToString(":"))
            MemberSignature(
                name = name,
                kind = kind,
                typeOrSignature = typeOrSignature,
                visibility = visibility
            )
        }
    }

    private fun parseContracts(raw: String): List<ContractEndpoint> {
        if (raw.isBlank()) return emptyList()
        return raw.split(";").mapNotNull { entry ->
            val parts = entry.split("|")
            if (parts.size < 4) return@mapNotNull null
            val fieldsMap = linkedMapOf<String, String>()
            val rawFields = parts.getOrNull(6).orEmpty()
            if (rawFields.isNotBlank()) {
                for (pair in splitTopLevelEntries(rawFields)) {
                    val kv = pair.split("=", limit = 2)
                    if (kv.size == 2 && kv[0].isNotBlank()) {
                        fieldsMap[decodeContractMapKey(kv[0])] = decodeContractFieldValue(kv[1])
                    }
                }
            }
            val reqFieldsMap = linkedMapOf<String, String>()
            val rawReqFields = parts.getOrNull(7).orEmpty()
            if (rawReqFields.isNotBlank()) {
                for (pair in splitTopLevelEntries(rawReqFields)) {
                    val kv = pair.split("=", limit = 2)
                    if (kv.size == 2 && kv[0].isNotBlank()) {
                        reqFieldsMap[decodeContractMapKey(kv[0])] = decodeContractFieldValue(kv[1])
                    }
                }
            }
            ContractEndpoint(
                protocol = decodeContractColumn(parts[0]),
                methodOrAction = decodeContractColumn(parts[1]),
                pathOrTopic = decodeContractColumn(parts[2]),
                role = decodeContractColumn(parts[3]),
                requestDto = parts.getOrNull(4)?.let(::decodeContractColumn)?.ifEmpty { null },
                responseDto = parts.getOrNull(5)?.let(::decodeContractColumn)?.ifEmpty { null },
                dtoFields = fieldsMap,
                requestDtoFields = reqFieldsMap
            )
        }
    }

    private fun splitTopLevelEntries(raw: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var bracketDepth = 0
        var activeQuote: Char? = null
        var i = 0
        while (i < raw.length) {
            val ch = raw[i]
            if (activeQuote != null) {
                current.append(ch)
                if (ch == '\\' && i + 1 < raw.length) {
                    current.append(raw[i + 1])
                    i += 2
                    continue
                }
                if (ch == activeQuote) {
                    activeQuote = null
                }
                i++
                continue
            }
            when (ch) {
                '\'', '"' -> {
                    activeQuote = ch
                    current.append(ch)
                }
                '<', '[', '(', '{' -> {
                    bracketDepth++
                    current.append(ch)
                }
                '>' -> {
                    // Ignore `=>` arrow token in TypeScript function types (e.g. `(x: number) => void`)
                    if (i == 0 || raw[i - 1] != '=') {
                        bracketDepth = maxOf(0, bracketDepth - 1)
                    }
                    current.append(ch)
                }
                ']', ')', '}' -> {
                    bracketDepth = maxOf(0, bracketDepth - 1)
                    current.append(ch)
                }
                ',' -> if (bracketDepth == 0) {
                    result.add(current.toString())
                    current.clear()
                } else {
                    current.append(ch)
                }
                else -> current.append(ch)
            }
            i++
        }
        if (current.isNotEmpty()) {
            result.add(current.toString())
        }
        return result
    }

    private fun decodeStereotypeEntry(raw: String): String =
        raw.replace("%2C", ",").replace("%25", "%")

    private fun decodeViolationEntry(raw: String): String =
        raw.replace("%7C", "|").replace("%25", "%")

    private fun decodeMemberSegment(raw: String): String =
        raw.replace("%3A", ":").replace("%3B", ";").replace("%25", "%")

    private fun decodeSemicolonPercent(raw: String): String =
        raw.replace("%3B", ";").replace("%25", "%")

    private fun decodeContractColumn(raw: String): String =
        raw.replace("%7C", "|").replace("%3B", ";").replace("%25", "%")

    private fun decodeContractMapKey(raw: String): String =
        raw.replace("%3B", ";")
            .replace("%7C", "|")
            .replace("%2C", ",")
            .replace("%3D", "=")
            .replace("%25", "%")

    private fun decodeContractFieldValue(raw: String): String =
        raw.replace("%2C", ",").replace("%7C", "|").replace("%3B", ";").replace("%25", "%")

    private fun parseAttributes(attrString: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for (match in ATTR_REGEX.findAll(attrString)) {
            val key = match.groupValues[1]
            result[key] = unescapeAttr(match.groupValues[2])
        }
        return result
    }

    private fun unescapeAttr(raw: String): String {
        if (!raw.contains('\\')) return raw
        val sb = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val ch = raw[i]
            if (ch == '\\' && i + 1 < raw.length) {
                when (val next = raw[i + 1]) {
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    else -> {
                        sb.append('\\')
                        sb.append(next)
                    }
                }
                i += 2
            } else {
                sb.append(ch)
                i++
            }
        }
        return sb.toString()
    }
}
