package dev.arcdrift.core.analysis

import dev.arcdrift.core.model.*

/**
 * Extracts compact, token-efficient subgraphs ("architecture slices") around a requested scope
 * and/or C4 zoom level so coding agents can inspect architecture in <1,500 tokens without grepping 50 files.
 */
object ArchitectureSlicer {

    fun slice(
        graph: ArcGraph,
        scopeQuery: String? = null,
        maxC4Level: C4Level? = null,
        depth: Int = 2
    ): ArcGraph {
        val effectiveDepth = depth.coerceAtLeast(0)
        val nodeMap = graph.nodeMap()
        val levelFilteredNodes = if (maxC4Level != null) {
            graph.nodes.filter { it.c4Level.ordinal <= maxC4Level.ordinal }
        } else {
            graph.nodes
        }
        val allowedByLevel = levelFilteredNodes.map { it.id }.toSet()

        fun resolveVisibleAncestor(nodeId: String): String? {
            if (nodeId in allowedByLevel) return nodeId
            val visited = mutableSetOf(nodeId)
            var curr = nodeMap[nodeId]?.parentId
            while (curr != null && visited.add(curr)) {
                if (curr in allowedByLevel) return curr
                curr = nodeMap[curr]?.parentId
            }
            return null
        }

        // Lift edges to visible container ancestors when maxC4Level filters out lower-level child nodes
        val projectedEdges: List<ArcEdge> = if (maxC4Level != null) {
            val liftedByKey = linkedMapOf<String, ArcEdge>()
            for (edge in graph.edges) {
                val srcVis = resolveVisibleAncestor(edge.sourceId) ?: continue
                val tgtVis = resolveVisibleAncestor(edge.targetId) ?: continue
                if (srcVis == tgtVis) continue
                val key = "$srcVis->$tgtVis:${edge.kind}"
                val existing = liftedByKey[key]
                if (existing == null) {
                    val liftedId = if (srcVis == edge.sourceId && tgtVis == edge.targetId) {
                        edge.id
                    } else {
                        "lifted-${edge.kind}-$srcVis-$tgtVis"
                    }
                    liftedByKey[key] = edge.copy(
                        id = liftedId,
                        sourceId = srcVis,
                        targetId = tgtVis
                    )
                } else {
                    val mergedViolations = (existing.violations + edge.violations).distinct()
                    val isViolation = existing.diffStatus == DiffStatus.VIOLATION || edge.diffStatus == DiffStatus.VIOLATION
                    liftedByKey[key] = existing.copy(
                        weight = existing.weight + edge.weight,
                        inCycle = existing.inCycle || edge.inCycle,
                        diffStatus = if (isViolation) DiffStatus.VIOLATION else existing.diffStatus,
                        violations = mergedViolations
                    )
                }
            }
            liftedByKey.values.toList()
        } else {
            graph.edges.filter { it.sourceId in allowedByLevel && it.targetId in allowedByLevel }
        }

        if (scopeQuery.isNullOrBlank()) {
            return graph.copy(nodes = levelFilteredNodes, edges = projectedEdges)
        }

        val queryLower = scopeQuery.lowercase()
        val directSeeds = levelFilteredNodes.filter { node ->
            node.id.lowercase().contains(queryLower) ||
                node.label.lowercase().contains(queryLower) ||
                node.parentId?.lowercase()?.contains(queryLower) == true ||
                node.stereotypes.any { it.lowercase().contains(queryLower) }
        }.map { it.id }.toMutableSet()

        // If a matched seed is a container (e.g. PACKAGE or MODULE), also seed its visible descendants
        if (directSeeds.isNotEmpty()) {
            for (candidate in levelFilteredNodes) {
                val visited = mutableSetOf(candidate.id)
                var parent = candidate.parentId
                while (parent != null && visited.add(parent)) {
                    if (parent in directSeeds) {
                        directSeeds.add(candidate.id)
                        break
                    }
                    parent = nodeMap[parent]?.parentId
                }
            }
        }

        if (directSeeds.isEmpty()) {
            return graph.copy(nodes = emptyList(), edges = emptyList())
        }

        val traversalEdges = projectedEdges.filter {
            it.kind != EdgeKind.FORBIDDEN_RULE && it.diffStatus != DiffStatus.REMOVED
        }
        val outgoing = traversalEdges.groupBy { it.sourceId }
        val incoming = traversalEdges.groupBy { it.targetId }
        val maxHubDegreeAtSecondaryHops = 20

        val includedIds = mutableSetOf<String>()
        val queue = ArrayDeque<Pair<String, Int>>()

        for (seed in directSeeds) {
            includedIds.add(seed)
            queue.addLast(seed to 0)
        }

        while (queue.isNotEmpty()) {
            val (currentId, currentDepth) = queue.removeFirst()
            if (currentDepth >= effectiveDepth) continue

            val outEdges = outgoing[currentId].orEmpty()
            val inEdges = incoming[currentId].orEmpty()
            val totalDegree = outEdges.size + inEdges.size

            val neighbors = if (currentDepth == 0 || totalDegree <= maxHubDegreeAtSecondaryHops) {
                outEdges.map { it.targetId } + inEdges.map { it.sourceId }
            } else {
                // At hop >= 2, avoid fanning out through high-degree shared utility/hub nodes
                // while still preserving cross-language HTTP_CONTRACT edges and direct outgoing dependencies if bounded
                val contractNeighbors = (outEdges + inEdges)
                    .filter { it.kind == EdgeKind.HTTP_CONTRACT }
                    .flatMap { listOf(it.sourceId, it.targetId) }
                val boundedOut = if (outEdges.size <= maxHubDegreeAtSecondaryHops) {
                    outEdges.map { it.targetId }
                } else {
                    emptyList()
                }
                contractNeighbors + boundedOut
            }

            for (neighbor in neighbors) {
                if (neighbor in allowedByLevel && neighbor !in includedIds) {
                    includedIds.add(neighbor)
                    queue.addLast(neighbor to (currentDepth + 1))
                }
            }
        }

        // Also include parent containers of any included node if those parents are in allowedByLevel (cycle-safe)
        val parentIdsToInclude = mutableSetOf<String>()
        for (id in includedIds) {
            val visitedParents = mutableSetOf(id)
            var parent = nodeMap[id]?.parentId
            while (parent != null && visitedParents.add(parent)) {
                if (parent in allowedByLevel) {
                    parentIdsToInclude.add(parent)
                }
                parent = nodeMap[parent]?.parentId
            }
        }
        includedIds.addAll(parentIdsToInclude)

        val slicedNodes = levelFilteredNodes.filter { it.id in includedIds }
        val slicedEdges = projectedEdges.filter {
            it.sourceId in includedIds && it.targetId in includedIds
        }

        return graph.copy(
            nodes = slicedNodes,
            edges = slicedEdges
        )
    }
}
