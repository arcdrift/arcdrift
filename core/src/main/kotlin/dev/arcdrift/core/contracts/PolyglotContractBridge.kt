package dev.arcdrift.core.contracts

import dev.arcdrift.core.model.*
import dev.arcdrift.core.scanner.ArcDriftIgnore
import kotlinx.serialization.Serializable

/**
 * Represents a cross-language / cross-service API or event contract violation
 * (e.g., a TypeScript or Python consumer referencing a DTO field removed from a Kotlin/Java backend,
 * or calling an unmapped endpoint).
 */
@Serializable
data class ContractViolation(
    val id: String,
    val consumerNodeId: String,
    val producerNodeId: String?,
    val consumerFilePath: String? = null,
    val producerFilePath: String? = null,
    val protocol: String,
    val methodOrAction: String,
    val pathOrTopic: String,
    val missingOrMismatchedFields: List<String> = emptyList(),
    val message: String,
    val remediationHint: String
)

/**
 * Result of linking polyglot producer and consumer contracts across the graph.
 */
@Serializable
data class ContractBridgeResult(
    val enrichedGraph: ArcGraph,
    val linkedEdges: List<ArcEdge>,
    val violations: List<ContractViolation>
)

/**
 * Single producer endpoint exported from an upstream service repository for multi-repo federation.
 */
@Serializable
data class ExportedContractProducer(
    val projectName: String,
    val nodeId: String,
    val nodeLabel: String,
    val filePath: String? = null,
    val contract: ContractEndpoint
)

/**
 * Serializable manifest of producer contracts (`--export-contracts` / `--upstream-contracts`)
 * enabling cross-repository polyglot contract verification.
 */
@Serializable
data class ContractFederationManifest(
    val version: String = "1.0",
    val exportedFromProjects: List<String> = emptyList(),
    val producers: List<ExportedContractProducer> = emptyList()
)

/**
 * Links JVM backend endpoints (`REST`, `GRPC`, `KAFKA`) to TypeScript and Python consumers
 * and detects breaking DTO contract drift introduced by AI agents.
 */
object PolyglotContractBridge {

    private val SCHEME_HOST_PREFIX = Regex("""^https?://[^/]+""", RegexOption.IGNORE_CASE)
    private val LEADING_BASE_VARS_PREFIX = Regex("""^(?:\$\{[^}/]+}|\{[^}/]+})+(?=/|[a-zA-Z])""")
    private val COLON_OR_ANGLE_PARAM = Regex("""<[^>/]+>|(?<=/):[a-zA-Z_][a-zA-Z0-9_]*""")

    fun exportProducerManifest(graph: ArcGraph): ContractFederationManifest {
        val exported = mutableListOf<ExportedContractProducer>()
        for (node in graph.nodes) {
            if (node.id.startsWith("upstream:")) continue
            for (contract in node.contracts) {
                if (contract.role.equals("PRODUCER", ignoreCase = true)) {
                    exported.add(
                        ExportedContractProducer(
                            projectName = graph.projectName,
                            nodeId = node.id,
                            nodeLabel = node.label,
                            filePath = node.source?.filePath,
                            contract = contract
                        )
                    )
                }
            }
        }
        return ContractFederationManifest(
            version = "1.0",
            exportedFromProjects = listOf(graph.projectName),
            producers = exported
        )
    }

    fun mergeManifests(manifests: List<ContractFederationManifest>): ContractFederationManifest {
        return ContractFederationManifest(
            version = "1.0",
            exportedFromProjects = manifests.flatMap { it.exportedFromProjects }.distinct(),
            producers = manifests.flatMap { it.producers }.distinct()
        )
    }

    fun linkAndVerify(
        graph: ArcGraph,
        upstreamManifest: ContractFederationManifest? = null,
        ignoreConfig: ArcDriftIgnore = ArcDriftIgnore.EMPTY
    ): ContractBridgeResult {
        // Strip previously synthesized upstream:* nodes, contract-edge-* edges, and contract violation strings so linkAndVerify is strictly idempotent
        val cleanedNodes = graph.nodes
            .filterNot { it.id.startsWith("upstream:") }
            .map { n ->
                val nonContractViolations = n.violations.filterNot {
                    it.startsWith("Route contract mismatch:") ||
                        it.startsWith("Unresolved ") ||
                        it.startsWith("Contract drift on ")
                }
                if (nonContractViolations.size == n.violations.size) {
                    n
                } else {
                    val nextStatus = if (nonContractViolations.isEmpty() && n.diffStatus == DiffStatus.VIOLATION) {
                        DiffStatus.UNCHANGED
                    } else {
                        n.diffStatus
                    }
                    n.copy(diffStatus = nextStatus, violations = nonContractViolations)
                }
            }
        val cleanedEdges = graph.edges.filterNot {
            it.id.startsWith("contract-edge-")
        }

        val producers = mutableListOf<Pair<ArcNode, ContractEndpoint>>()
        val consumers = mutableListOf<Pair<ArcNode, ContractEndpoint>>()

        for (node in cleanedNodes) {
            if (node.diffStatus == DiffStatus.REMOVED) continue
            for (contract in node.contracts) {
                val isConsumer = contract.role.equals("CONSUMER", ignoreCase = true) ||
                    (!contract.role.equals("PRODUCER", ignoreCase = true) &&
                        (node.kind == NodeKind.TS_CLIENT || node.kind == NodeKind.PYTHON_CONSUMER)) ||
                    contract.methodOrAction.equals("CONSUME", ignoreCase = true)
                if (isConsumer) {
                    if (!ignoreConfig.isContractIgnored(contract)) {
                        consumers.add(node to contract)
                    }
                } else {
                    producers.add(node to contract)
                }
            }
        }

        val upstreamNodesById = linkedMapOf<String, ArcNode>()
        if (upstreamManifest != null) {
            for (ext in upstreamManifest.producers) {
                val extId = "upstream:${ext.projectName}:${ext.nodeId}"
                val extKind = if (ext.contract.protocol.equals("KAFKA", ignoreCase = true)) {
                    NodeKind.MESSAGE_LISTENER
                } else {
                    NodeKind.REST_CONTROLLER
                }
                val existingExt = upstreamNodesById[extId]
                val extNode = if (existingExt != null) {
                    existingExt.copy(contracts = existingExt.contracts + ext.contract)
                } else {
                    ArcNode(
                        id = extId,
                        label = "${ext.nodeLabel} (${ext.projectName})",
                        c4Level = C4Level.L3_COMPONENT,
                        kind = extKind,
                        contracts = listOf(ext.contract),
                        source = ext.filePath?.let { SourceLocation(filePath = it) }
                    )
                }
                upstreamNodesById[extId] = extNode
                producers.add(extNode to ext.contract)
            }
        }

        val existingEdgeKeys = cleanedEdges.map { "${it.sourceId}->${it.targetId}:${it.kind}" }.toMutableSet()
        val linkedEdges = mutableListOf<ArcEdge>()
        val violations = mutableListOf<ContractViolation>()
        val violatingNodeReasons = linkedMapOf<String, MutableList<String>>()
        var edgeCounter = 1
        var violationCounter = 1

        val hasCustomManagementProducer = producers.any { (_, prod) ->
            val p = normalizeEndpointKey(prod.protocol, prod.pathOrTopic)
            p.startsWith("/management/") || p.startsWith("/actuator/")
        }

        for ((consumerNode, consumerContract) in consumers) {
            val normConsumerPath = normalizeEndpointKey(consumerContract.protocol, consumerContract.pathOrTopic)
            if (!hasCustomManagementProducer &&
                (normConsumerPath.startsWith("/management/") || normConsumerPath.startsWith("/actuator/"))
            ) {
                continue
            }
            val matchingProducers = producers.filter { (_, prodContract) ->
                prodContract.protocol.equals(consumerContract.protocol, ignoreCase = true) &&
                    methodsCompatible(prodContract.methodOrAction, consumerContract.methodOrAction) &&
                    normalizeEndpointKey(prodContract.protocol, prodContract.pathOrTopic) == normConsumerPath
            }

            if (matchingProducers.isEmpty()) {
                val candidateProducer = findCandidateRouteMismatchProducer(consumerContract, normConsumerPath, producers)
                if (candidateProducer != null) {
                    val (producerNode, producerContract) = candidateProducer
                    val msg = "Route contract mismatch: '${consumerNode.label}' calls '${consumerContract.methodOrAction} ${consumerContract.pathOrTopic}', but '${producerNode.label}' exposes '${producerContract.methodOrAction} ${producerContract.pathOrTopic}'."
                    violatingNodeReasons.getOrPut(consumerNode.id) { mutableListOf() }.add(msg)
                    violatingNodeReasons.getOrPut(producerNode.id) { mutableListOf() }.add(msg)
                    violations.add(
                        ContractViolation(
                            id = "contract-violation-${violationCounter++}",
                            consumerNodeId = consumerNode.id,
                            producerNodeId = producerNode.id,
                            consumerFilePath = consumerNode.source?.filePath,
                            producerFilePath = producerNode.source?.filePath,
                            protocol = consumerContract.protocol,
                            methodOrAction = consumerContract.methodOrAction,
                            pathOrTopic = consumerContract.pathOrTopic,
                            message = msg,
                            remediationHint = "Align route path between '${producerNode.label}' ('${producerContract.pathOrTopic}') and consumer '${consumerNode.label}' ('${consumerContract.pathOrTopic}')."
                        )
                    )
                    val edgeKey = "${consumerNode.id}->${producerNode.id}:${EdgeKind.HTTP_CONTRACT}"
                    val existingIdx = linkedEdges.indexOfFirst {
                        it.sourceId == consumerNode.id && it.targetId == producerNode.id && it.kind == EdgeKind.HTTP_CONTRACT
                    }
                    if (existingIdx >= 0) {
                        val prev = linkedEdges[existingIdx]
                        val nextLabel = "${consumerContract.methodOrAction} ${consumerContract.pathOrTopic} ≠ ${producerContract.pathOrTopic}"
                        val mergedLabel = if (prev.label == null || prev.label == nextLabel) nextLabel else "${prev.label}, $nextLabel"
                        linkedEdges[existingIdx] = prev.copy(
                            label = mergedLabel,
                            diffStatus = DiffStatus.VIOLATION,
                            violations = (prev.violations + msg).distinct()
                        )
                    } else if (edgeKey !in existingEdgeKeys) {
                        existingEdgeKeys.add(edgeKey)
                        linkedEdges.add(
                            ArcEdge(
                                id = "contract-edge-${edgeCounter++}",
                                sourceId = consumerNode.id,
                                targetId = producerNode.id,
                                kind = EdgeKind.HTTP_CONTRACT,
                                label = "${consumerContract.methodOrAction} ${consumerContract.pathOrTopic} ≠ ${producerContract.pathOrTopic}",
                                diffStatus = DiffStatus.VIOLATION,
                                violations = listOf(msg)
                            )
                        )
                    }
                } else {
                    val msg = "Unresolved ${consumerContract.protocol} contract: '${consumerContract.methodOrAction} ${consumerContract.pathOrTopic}' has no matching backend endpoint."
                    violatingNodeReasons.getOrPut(consumerNode.id) { mutableListOf() }.add(msg)
                    violations.add(
                        ContractViolation(
                            id = "contract-violation-${violationCounter++}",
                            consumerNodeId = consumerNode.id,
                            producerNodeId = null,
                            consumerFilePath = consumerNode.source?.filePath,
                            producerFilePath = null,
                            protocol = consumerContract.protocol,
                            methodOrAction = consumerContract.methodOrAction,
                            pathOrTopic = consumerContract.pathOrTopic,
                            message = "Unresolved ${consumerContract.protocol} contract: Consumer '${consumerNode.label}' calls '${consumerContract.methodOrAction} ${consumerContract.pathOrTopic}', but no matching backend producer endpoint exists.",
                            remediationHint = "Update '${consumerNode.label}' to match the current backend route or restore the '${consumerContract.pathOrTopic}' endpoint."
                        )
                    )
                }
                continue
            }

            for ((producerNode, producerContract) in matchingProducers) {
                val missingFields = mutableListOf<String>()
                val isReadOrDeleteVerb = consumerContract.methodOrAction.equals("GET", ignoreCase = true) ||
                    consumerContract.methodOrAction.equals("DELETE", ignoreCase = true)
                val producerHasTypedPayload = producerContract.dtoFields.isNotEmpty() || producerContract.responseDto != null
                if (consumerContract.dtoFields.isNotEmpty() && producerHasTypedPayload) {
                    val sharedFieldMatchCount = consumerContract.dtoFields.keys.count { it in producerContract.dtoFields }
                    for ((expectedField, expectedType) in consumerContract.dtoFields) {
                        if (expectedField == "password" && (isReadOrDeleteVerb || sharedFieldMatchCount >= 8)) {
                            continue
                        }
                        val producerType = producerContract.dtoFields[expectedField]
                        val targetDtoLabel = producerContract.responseDto ?: producerContract.requestDto ?: producerNode.label
                        if (producerType == null) {
                            missingFields.add("$expectedField (missing in producer '$targetDtoLabel')")
                        } else if (!typesCompatible(producerType, expectedType)) {
                            missingFields.add("$expectedField (producer: $producerType vs consumer: $expectedType)")
                        }
                    }
                }

                // Check Request-Body (@RequestBody / requestDtoFields) compatibility on POST/PUT/PATCH endpoints
                val producerReqFields = producerContract.requestDtoFields
                if (consumerContract.requestDtoFields.isNotEmpty() && producerReqFields.isNotEmpty()) {
                    val sharedReqMatchCount = consumerContract.requestDtoFields.keys.count { it in producerReqFields }
                    for ((expectedReqField, expectedReqType) in consumerContract.requestDtoFields) {
                        if (expectedReqField == "password" && sharedReqMatchCount >= 8) {
                            continue
                        }
                        val producerReqType = producerReqFields[expectedReqField]
                        val targetLabel = producerContract.requestDto ?: producerContract.responseDto ?: producerNode.label
                        if (producerReqType == null) {
                            val candidateMsg = "$expectedReqField (missing in producer request body '$targetLabel')"
                            if (candidateMsg !in missingFields) {
                                missingFields.add(candidateMsg)
                            }
                        } else if (!typesCompatible(producerReqType, expectedReqType)) {
                            val candidateMsg = "$expectedReqField (request body producer: $producerReqType vs consumer: $expectedReqType)"
                            if (candidateMsg !in missingFields) {
                                missingFields.add(candidateMsg)
                            }
                        }
                    }
                }

                val hasViolation = missingFields.isNotEmpty()
                val driftMsg = if (hasViolation) {
                    "Contract drift on '${consumerContract.methodOrAction} ${consumerContract.pathOrTopic}': ${missingFields.joinToString("; ")}"
                } else {
                    null
                }
                if (hasViolation && driftMsg != null) {
                    violatingNodeReasons.getOrPut(consumerNode.id) { mutableListOf() }.add(driftMsg)
                    violatingNodeReasons.getOrPut(producerNode.id) { mutableListOf() }.add(driftMsg)
                    violations.add(
                        ContractViolation(
                            id = "contract-violation-${violationCounter++}",
                            consumerNodeId = consumerNode.id,
                            producerNodeId = producerNode.id,
                            consumerFilePath = consumerNode.source?.filePath,
                            producerFilePath = producerNode.source?.filePath,
                            protocol = consumerContract.protocol,
                            methodOrAction = consumerContract.methodOrAction,
                            pathOrTopic = consumerContract.pathOrTopic,
                            missingOrMismatchedFields = missingFields,
                            message = "Cross-language DTO contract drift on '${consumerContract.methodOrAction} ${consumerContract.pathOrTopic}' between '${consumerNode.label}' and '${producerNode.label}': ${missingFields.joinToString("; ")}.",
                            remediationHint = "Synchronize DTO fields between '${producerNode.label}' (${producerContract.requestDto ?: producerContract.responseDto ?: "DTO"}) and consumer '${consumerNode.label}'."
                        )
                    )
                }

                val edgeKey = "${consumerNode.id}->${producerNode.id}:${EdgeKind.HTTP_CONTRACT}"
                val existingIdx = linkedEdges.indexOfFirst {
                    it.sourceId == consumerNode.id && it.targetId == producerNode.id && it.kind == EdgeKind.HTTP_CONTRACT
                }
                if (existingIdx >= 0) {
                    val prev = linkedEdges[existingIdx]
                    val nextLabel = "${consumerContract.methodOrAction} ${consumerContract.pathOrTopic}"
                    val mergedViolations = if (driftMsg != null) (prev.violations + driftMsg).distinct() else prev.violations
                    val mergedStatus = if (prev.diffStatus == DiffStatus.VIOLATION || hasViolation) {
                        DiffStatus.VIOLATION
                    } else {
                        prev.diffStatus
                    }
                    val mergedLabel = when {
                        prev.label == null || prev.label == nextLabel -> nextLabel
                        prev.label.split(", ").contains(nextLabel) -> prev.label
                        else -> "${prev.label}, $nextLabel"
                    }
                    linkedEdges[existingIdx] = prev.copy(
                        label = mergedLabel,
                        diffStatus = mergedStatus,
                        violations = mergedViolations
                    )
                } else if (edgeKey !in existingEdgeKeys) {
                    existingEdgeKeys.add(edgeKey)
                    val newEdge = ArcEdge(
                        id = "contract-edge-${edgeCounter++}",
                        sourceId = consumerNode.id,
                        targetId = producerNode.id,
                        kind = EdgeKind.HTTP_CONTRACT,
                        label = "${consumerContract.methodOrAction} ${consumerContract.pathOrTopic}",
                        diffStatus = if (hasViolation) DiffStatus.VIOLATION else DiffStatus.UNCHANGED,
                        violations = listOfNotNull(driftMsg)
                    )
                    linkedEdges.add(newEdge)
                }
            }
        }

        val updatedNodes = if (violatingNodeReasons.isEmpty()) {
            cleanedNodes
        } else {
            cleanedNodes.map { n ->
                val reasons = violatingNodeReasons[n.id]
                if (!reasons.isNullOrEmpty()) {
                    n.copy(
                        diffStatus = DiffStatus.VIOLATION,
                        violations = (n.violations + reasons).distinct()
                    )
                } else {
                    n
                }
            }
        }

        val existingNodeIds = updatedNodes.map { it.id }.toSet()
        val referencedUpstreamNodes = linkedEdges
            .mapNotNull { edge -> upstreamNodesById[edge.targetId] }
            .distinctBy { it.id }
            .filter { it.id !in existingNodeIds }
            .map { n ->
                val reasons = violatingNodeReasons[n.id]
                if (!reasons.isNullOrEmpty()) {
                    n.copy(diffStatus = DiffStatus.VIOLATION, violations = (n.violations + reasons).distinct())
                } else {
                    n
                }
            }

        val enrichedGraph = graph.copy(
            nodes = updatedNodes + referencedUpstreamNodes,
            edges = cleanedEdges + linkedEdges
        )

        return ContractBridgeResult(
            enrichedGraph = enrichedGraph,
            linkedEdges = linkedEdges,
            violations = violations
        )
    }

    private fun normalizeEndpointKey(protocol: String, rawPathOrTopic: String): String {
        if (protocol.equals("KAFKA", ignoreCase = true)) {
            return rawPathOrTopic.trim()
        }
        return normalizePath(rawPathOrTopic)
    }

    fun normalizePath(rawPath: String): String {
        val withoutQuery = rawPath.trim().substringBefore('?').substringBefore('#')
        val withoutSchemeHost = SCHEME_HOST_PREFIX.replace(withoutQuery, "")
        val withoutBaseVar = LEADING_BASE_VARS_PREFIX.replace(withoutSchemeHost, "")
        val collapsedSlashes = withoutBaseVar.replace(Regex("/+"), "/")
        val trimmed = collapsedSlashes.trimEnd('/').ifEmpty { "/" }
        val withLeading = if (trimmed.startsWith("/")) trimmed else "/$trimmed"
        val bracedReplaced = replaceBalancedBraceParams(withLeading)
        return COLON_OR_ANGLE_PARAM.replace(bracedReplaced, "{param}").lowercase()
    }

    /**
     * Replaces `{id}`, `${id}`, and Spring regex path parameters containing nested quantifiers like `{id:[0-9]{4}}`
     * with `{param}` using balanced brace tracking so trailing `}` characters are never left behind.
     */
    private fun replaceBalancedBraceParams(path: String): String {
        val sb = StringBuilder(path.length)
        var i = 0
        while (i < path.length) {
            val isDollarBrace = path[i] == '$' && i + 1 < path.length && path[i + 1] == '{'
            val isPlainBrace = path[i] == '{'
            if (isDollarBrace || isPlainBrace) {
                val startBrace = if (isDollarBrace) i + 1 else i
                var depth = 0
                var j = startBrace
                var crossedSlash = false
                while (j < path.length) {
                    when (path[j]) {
                        '{' -> depth++
                        '}' -> {
                            depth--
                            if (depth == 0) break
                        }
                        '/' -> crossedSlash = true
                    }
                    j++
                }
                if (depth == 0 && j < path.length && !crossedSlash) {
                    sb.append("{param}")
                    i = j + 1
                    continue
                }
            }
            sb.append(path[i])
            i++
        }
        return sb.toString()
    }

    private fun findCandidateRouteMismatchProducer(
        consumerContract: ContractEndpoint,
        normConsumerPath: String,
        producers: List<Pair<ArcNode, ContractEndpoint>>
    ): Pair<ArcNode, ContractEndpoint>? {
        val consumerSegments = normConsumerPath.trim('/').split('/').filter { it.isNotEmpty() }
        val compatible = producers.filter { (_, prod) ->
            prod.protocol.equals(consumerContract.protocol, ignoreCase = true) &&
                methodsCompatible(prod.methodOrAction, consumerContract.methodOrAction)
        }
        if (compatible.isEmpty()) return null

        return compatible
            .map { pair ->
                val prodSegments = normalizeEndpointKey(pair.second.protocol, pair.second.pathOrTopic)
                    .trim('/')
                    .split('/')
                    .filter { it.isNotEmpty() }
                val consDto = consumerContract.responseDto
                val prodDto = pair.second.responseDto
                val sameDto = !consDto.isNullOrEmpty() &&
                    !prodDto.isNullOrEmpty() &&
                    (consDto.equals(prodDto, ignoreCase = true) ||
                        typesCompatible(prodDto, consDto))
                val sameLength = prodSegments.size == consumerSegments.size && prodSegments.isNotEmpty()
                val sharedSegments = prodSegments.zip(consumerSegments).count { (a, b) -> a == b }
                val prodLeaf = prodSegments.lastOrNull { it != "{param}" }
                val consLeaf = consumerSegments.lastOrNull { it != "{param}" }
                val leafMatches = prodLeaf != null && consLeaf != null &&
                    (prodLeaf == consLeaf ||
                        prodLeaf.removeSuffix("s") == consLeaf.removeSuffix("s") ||
                        (sameLength &&
                            prodLeaf.length >= 5 &&
                            consLeaf.length >= 5 &&
                            prodLeaf.commonPrefixWith(consLeaf).length >= maxOf(prodLeaf.length, consLeaf.length) - 1))
                val score = (if (sameDto) 10 else 0) +
                    (if (sameLength) 4 else 0) +
                    (sharedSegments * 2) +
                    (if (leafMatches) 6 else 0)
                Triple(pair, score, sameDto || leafMatches)
            }
            .filter { (_, score, hasDomainMatch) -> hasDomainMatch && score >= 6 }
            .maxByOrNull { (_, score, _) -> score }
            ?.first
    }

    private fun methodsCompatible(producerMethod: String, consumerMethod: String): Boolean {
        if (producerMethod.equals(consumerMethod, ignoreCase = true)) return true
        if ((producerMethod.equals("PUBLISH", ignoreCase = true) && consumerMethod.equals("CONSUME", ignoreCase = true)) ||
            (producerMethod.equals("CONSUME", ignoreCase = true) && consumerMethod.equals("PUBLISH", ignoreCase = true))
        ) {
            return true
        }
        return false
    }

    internal fun typesCompatible(jvmType: String, clientType: String): Boolean {
        val jvmIsArray = isCollectionOrArrayType(jvmType)
        val clientIsArray = isCollectionOrArrayType(clientType)
        val normJvm = normalizePrimitiveType(jvmType)
        val normClient = normalizePrimitiveType(clientType)
        // Flag collection vs scalar wire mismatch BEFORE wildcard `any` check unless one side is untyped `any` without `[]`
        val rawJvmClean = extractPrimaryNonNullBranch(jvmType).trim()
        val rawClientClean = extractPrimaryNonNullBranch(clientType).trim()
        val jvmIsUntypedDynamic = !jvmIsArray && (rawJvmClean.equals("any", true) || rawJvmClean.equals("object", true) || rawJvmClean.equals("unknown", true))
        val clientIsUntypedDynamic = !clientIsArray && (rawClientClean.equals("any", true) || rawClientClean.equals("object", true) || rawClientClean.equals("unknown", true))
        if (jvmIsUntypedDynamic || clientIsUntypedDynamic) return true

        if (jvmIsArray != clientIsArray && normJvm != "array" && normClient != "array") {
            return false
        }
        if (normJvm == "any" || normClient == "any") return true
        if (normJvm == normClient) return true

        // Nested DTO reference compatibility (e.g. BankAccountDTO <-> IBankAccount, Array<IBankAccount> <-> List<BankAccountDTO>)
        val rawClientElem = unwrapCollectionAndNullabilityCasing(clientType)
        val hasTsInterfacePrefix = rawClientElem.length >= 2 &&
            rawClientElem[0] == 'I' &&
            rawClientElem[1].isUpperCase()
        val cleanJvmDto = stripDtoAffixes(normJvm)
        val baseClientDto = if (hasTsInterfacePrefix) normClient.removePrefix("i") else normClient
        val cleanClientDto = stripDtoAffixes(baseClientDto)
        return cleanJvmDto.isNotEmpty() && cleanClientDto.isNotEmpty() && cleanJvmDto == cleanClientDto
    }

    private fun stripDtoAffixes(lowerName: String): String {
        var s = lowerName
        for (prefix in listOf("rest", "api", "new")) {
            if (s.startsWith(prefix) && s.length > prefix.length + 2) {
                s = s.removePrefix(prefix)
            }
        }
        var changed = true
        while (changed) {
            changed = false
            for (suffix in listOf("dto", "model", "response", "request", "payload", "entity", "record", "public", "create", "update", "enum", "vo")) {
                if (s.endsWith(suffix) && s.length > suffix.length + 1) {
                    s = s.removeSuffix(suffix)
                    changed = true
                }
            }
        }
        return s
    }

    /**
     * Splits union branches (`A | null`) only at top-level bracket/paren depth 0 so `Array<string | null>` and `(string | null)[]`
     * are not split in the middle of `<...>` or `(...)`.
     */
    private fun splitTopLevelUnion(raw: String): List<String> {
        val branches = mutableListOf<String>()
        val current = StringBuilder()
        var depth = 0
        for (i in raw.indices) {
            val ch = raw[i]
            when (ch) {
                '<', '[', '(', '{' -> {
                    depth++
                    current.append(ch)
                }
                '>' -> {
                    if (i == 0 || raw[i - 1] != '=') depth = maxOf(0, depth - 1)
                    current.append(ch)
                }
                ']', ')', '}' -> {
                    depth = maxOf(0, depth - 1)
                    current.append(ch)
                }
                '|' -> if (depth == 0) {
                    branches.add(current.toString().trim())
                    current.clear()
                } else {
                    current.append(ch)
                }
                else -> current.append(ch)
            }
        }
        if (current.isNotEmpty()) branches.add(current.toString().trim())
        return branches.filter { it.isNotEmpty() }
    }

    private fun extractPrimaryNonNullBranch(raw: String): String {
        val branches = splitTopLevelUnion(raw.trim())
        return branches.firstOrNull { it !in setOf("null", "undefined", "void", "None") } ?: raw.trim()
    }

    private fun isCollectionOrArrayType(raw: String): Boolean {
        var trimmed = extractPrimaryNonNullBranch(raw).removeSuffix("?").trim()
        if (trimmed.startsWith("readonly ", ignoreCase = true)) {
            trimmed = trimmed.substring(9).trim()
        }
        if (trimmed.startsWith("Optional[", ignoreCase = true) && trimmed.endsWith("]")) {
            trimmed = trimmed.substringAfter('[').removeSuffix("]").trim()
        } else if (trimmed.startsWith("Optional<", ignoreCase = true) && trimmed.endsWith(">")) {
            trimmed = trimmed.substringAfter('<').removeSuffix(">").trim()
        }
        if (trimmed.endsWith("[]")) return true
        val pyPrefixes = listOf("list[", "set[", "sequence[", "tuple[", "frozenset[")
        if (pyPrefixes.any { trimmed.startsWith(it, ignoreCase = true) } && trimmed.endsWith("]")) return true
        val genericPrefixes = listOf(
            "Array<", "ReadonlyArray<", "List<", "MutableList<", "Set<", "MutableSet<",
            "Collection<", "MutableCollection<", "Iterable<", "MutableIterable<"
        )
        if (genericPrefixes.any { trimmed.startsWith(it, ignoreCase = true) } && trimmed.endsWith(">")) return true
        return false
    }

    private fun unwrapCollectionAndNullabilityCasing(raw: String): String {
        var s = extractPrimaryNonNullBranch(raw).removeSuffix("?").trim()
        var changed = true
        while (changed) {
            changed = false
            if (s.startsWith("readonly ", ignoreCase = true)) {
                s = s.substring(9).trim()
                changed = true
            }
            if (s.endsWith("[]")) {
                s = s.removeSuffix("[]").trim()
                changed = true
            }
            if (s.startsWith("(") && s.endsWith(")")) {
                s = extractPrimaryNonNullBranch(s.substring(1, s.length - 1).trim())
                changed = true
            }
            val pyWrappers = listOf("list[", "set[", "sequence[", "tuple[", "frozenset[", "optional[")
            if (pyWrappers.any { s.startsWith(it, ignoreCase = true) } && s.endsWith("]")) {
                s = extractPrimaryNonNullBranch(s.substringAfter('[').removeSuffix("]").trim())
                changed = true
            }
            val angleWrappers = listOf(
                "Array<", "ReadonlyArray<", "List<", "MutableList<", "Set<", "MutableSet<",
                "Collection<", "MutableCollection<", "Iterable<", "MutableIterable<", "Optional<"
            )
            if (angleWrappers.any { s.startsWith(it, ignoreCase = true) } && s.endsWith(">")) {
                s = extractPrimaryNonNullBranch(s.substringAfter('<').removeSuffix(">").trim())
                changed = true
            }
            if (s.endsWith("?")) {
                s = s.removeSuffix("?").trim()
                changed = true
            }
        }
        return s
    }

    private fun normalizePrimitiveType(raw: String): String {
        val unwrapped = unwrapCollectionAndNullabilityCasing(raw)
        val lower = unwrapped.lowercase()
        if (lower.startsWith("(typeof ") || lower.startsWith("keyof ")) return "any"
        if (lower.startsWith("dict[") ||
            lower.startsWith("mapping[") ||
            lower.startsWith("mutablemapping[") ||
            lower.startsWith("map<") ||
            lower.startsWith("mutablemap<") ||
            lower.startsWith("record<") ||
            lower.startsWith("readonlyrecord<")
        ) {
            return "any"
        }
        return when (lower) {
            "string", "str", "charsequence", "uuid", "uuid.uuid", "emailstr", "pydantic.emailstr",
            "instant", "localdate", "localdatetime", "zoneddatetime", "offsetdatetime",
            "date", "datetime", "utcdatetime", "dayjs.dayjs", "dayjs", "moment" -> "string"
            "int", "integer", "int32", "int64", "long", "double", "float", "float32", "float64",
            "bigdecimal", "number", "short", "byte", "decimal" -> "number"
            "boolean", "bool" -> "boolean"
            "list", "array", "set", "collection", "iterable", "sequence" -> "array"
            "any", "object", "dict", "map", "record", "unknown" -> "any"
            else -> lower
        }
    }
}
