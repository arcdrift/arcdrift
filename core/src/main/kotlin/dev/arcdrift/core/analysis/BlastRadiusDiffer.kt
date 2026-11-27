package dev.arcdrift.core.analysis

import dev.arcdrift.core.model.*
import kotlinx.serialization.Serializable

/**
 * Summary report of an architectural diff between a baseline graph and a current/proposed graph.
 */
@Serializable
data class BlastRadiusReport(
    val diffGraph: ArcGraph,
    val addedNodeIds: List<String>,
    val modifiedNodeIds: List<String>,
    val removedNodeIds: List<String>,
    val blastImpactedNodeIds: List<String>,
    val baselineCycleCount: Int,
    val currentCycleCount: Int,
    val couplingDeltaPercent: Double
)

/**
 * Computes the semantic difference between a `baseline` [ArcGraph] and a `current` [ArcGraph],
 * propagating `BLAST_IMPACTED` upstream to all transitive callers/dependents up to `maxDepth` hops.
 */
object BlastRadiusDiffer {

    fun diff(baseline: ArcGraph, current: ArcGraph, maxDepth: Int = 3): BlastRadiusReport {
        val effectiveMaxDepth = maxDepth.coerceAtLeast(0)
        val baseNodes = baseline.nodeMap()
        val currNodes = current.nodeMap()

        val addedIds = mutableListOf<String>()
        val modifiedIds = mutableListOf<String>()
        val removedIds = mutableListOf<String>()

        for ((id, currNode) in currNodes) {
            val baseNode = baseNodes[id]
            if (baseNode == null) {
                addedIds.add(id)
            } else if (hasSemanticNodeChange(baseNode, currNode)) {
                modifiedIds.add(id)
            }
        }

        for (id in baseNodes.keys) {
            if (id !in currNodes) {
                removedIds.add(id)
            }
        }

        val baseEdgesByPairGroups = baseline.edges.groupBy { "${it.sourceId}->${it.targetId}:${it.kind}" }
        val currEdgesByPairGroups = current.edges.groupBy { "${it.sourceId}->${it.targetId}:${it.kind}" }
        val multiEdgePairs = (baseEdgesByPairGroups.filterValues { it.size > 1 }.keys +
            currEdgesByPairGroups.filterValues { it.size > 1 }.keys)
        val baseEdgesByPair = baseline.edges.associateBy { "${it.sourceId}->${it.targetId}:${it.kind}" }
        val currEdgesByPair = current.edges.associateBy { "${it.sourceId}->${it.targetId}:${it.kind}" }
        val baseEdgesByKey = indexEdgesByUniqueKey(baseline.edges, multiEdgePairs)
        val currEdgesByKey = indexEdgesByUniqueKey(current.edges, multiEdgePairs)
        val currKeyByEdgeRef = java.util.IdentityHashMap<ArcEdge, String>().apply {
            for ((k, e) in currEdgesByKey) put(e, k)
        }

        // Callers (edge.sourceId) whose outgoing dependencies changed without direct declaration changes are blast-impacted
        val edgeTouchedNodes = mutableSetOf<String>()
        for ((key, edge) in currEdgesByKey) {
            val pairKey = "${edge.sourceId}->${edge.targetId}:${edge.kind}"
            val matchedInBase = key in baseEdgesByKey || (pairKey !in multiEdgePairs && pairKey in baseEdgesByPair)
            if (!matchedInBase) {
                if (edge.sourceId !in addedIds && edge.sourceId !in modifiedIds) {
                    edgeTouchedNodes.add(edge.sourceId)
                }
            }
        }
        for ((key, edge) in baseEdgesByKey) {
            val pairKey = "${edge.sourceId}->${edge.targetId}:${edge.kind}"
            val matchedInCurr = key in currEdgesByKey || (pairKey !in multiEdgePairs && pairKey in currEdgesByPair)
            if (!matchedInCurr) {
                if (edge.sourceId in currNodes && edge.sourceId !in addedIds && edge.sourceId !in modifiedIds) {
                    edgeTouchedNodes.add(edge.sourceId)
                }
            }
        }

        val directChangedSeeds = (addedIds + modifiedIds).toSet()

        // Reverse adjacency (target -> incoming edges from callers/dependents)
        val incomingEdgesByTarget = current.edges
            .filter { it.kind != EdgeKind.FORBIDDEN_RULE && it.diffStatus != DiffStatus.REMOVED }
            .groupBy { it.targetId }

        val blastImpacted = mutableSetOf<String>()
        blastImpacted.addAll(edgeTouchedNodes)
        val queue = ArrayDeque<Pair<String, Int>>()
        val visited = mutableSetOf<String>()

        for (seed in (directChangedSeeds + edgeTouchedNodes)) {
            queue.addLast(seed to 0)
            visited.add(seed)
        }
        // Also propagate upstream from removed nodes using baseline incoming edges
        val baseIncomingByTarget = baseline.edges
            .filter { it.kind != EdgeKind.FORBIDDEN_RULE && it.diffStatus != DiffStatus.REMOVED }
            .groupBy { it.targetId }
        if (effectiveMaxDepth >= 1) {
            for (removedId in removedIds) {
                for (inEdge in baseIncomingByTarget[removedId].orEmpty()) {
                    val caller = inEdge.sourceId
                    if (caller in currNodes && caller !in visited) {
                        visited.add(caller)
                        if (caller !in directChangedSeeds) {
                            blastImpacted.add(caller)
                        }
                        queue.addLast(caller to 1)
                    }
                }
            }
        }

        while (queue.isNotEmpty()) {
            val (nodeId, depth) = queue.removeFirst()
            if (depth >= effectiveMaxDepth) continue
            for (inEdge in incomingEdgesByTarget[nodeId].orEmpty()) {
                val upstreamCaller = inEdge.sourceId
                if (upstreamCaller !in visited) {
                    visited.add(upstreamCaller)
                    if (upstreamCaller !in directChangedSeeds && upstreamCaller !in addedIds && upstreamCaller !in modifiedIds) {
                        blastImpacted.add(upstreamCaller)
                    }
                    queue.addLast(upstreamCaller to (depth + 1))
                }
            }
        }

        val mergedNodes = mutableListOf<ArcNode>()
        for (node in current.nodes) {
            val status = when {
                node.diffStatus == DiffStatus.VIOLATION -> DiffStatus.VIOLATION
                node.id in addedIds -> DiffStatus.ADDED
                node.id in modifiedIds -> DiffStatus.MODIFIED
                node.id in blastImpacted -> DiffStatus.BLAST_IMPACTED
                else -> DiffStatus.UNCHANGED
            }
            mergedNodes.add(node.copy(diffStatus = status))
        }
        for (removedId in removedIds) {
            val ghostRemoved = baseNodes.getValue(removedId).copy(diffStatus = DiffStatus.REMOVED)
            mergedNodes.add(ghostRemoved)
        }

        val usedEdgeIds = current.edges.map { it.id }.toMutableSet()
        val mergedEdges = mutableListOf<ArcEdge>()
        for (edge in current.edges) {
            val pairKey = "${edge.sourceId}->${edge.targetId}:${edge.kind}"
            val key = currKeyByEdgeRef[edge] ?: edgeBaseKey(edge, multiEdgePairs)
            val baseEdge = baseEdgesByKey[key] ?: if (pairKey !in multiEdgePairs) {
                baseEdgesByPair[pairKey]
            } else {
                null
            }
            val status = when {
                edge.diffStatus == DiffStatus.VIOLATION -> DiffStatus.VIOLATION
                baseEdge == null -> DiffStatus.ADDED
                baseEdge.weight != edge.weight || baseEdge.label != edge.label -> DiffStatus.MODIFIED
                edge.targetId in directChangedSeeds || edge.targetId in blastImpacted -> DiffStatus.BLAST_IMPACTED
                else -> DiffStatus.UNCHANGED
            }
            mergedEdges.add(edge.copy(diffStatus = status))
        }
        for ((key, baseEdge) in baseEdgesByKey) {
            val pairKey = "${baseEdge.sourceId}->${baseEdge.targetId}:${baseEdge.kind}"
            val matchedAsSinglePair = pairKey !in multiEdgePairs && pairKey in currEdgesByPair
            if (key !in currEdgesByKey && !matchedAsSinglePair) {
                var removedEdgeId = if (baseEdge.id in usedEdgeIds) "removed-${baseEdge.id}" else baseEdge.id
                var suffix = 2
                while (!usedEdgeIds.add(removedEdgeId)) {
                    removedEdgeId = "removed-${baseEdge.id}-$suffix"
                    suffix++
                }
                mergedEdges.add(
                    baseEdge.copy(
                        id = removedEdgeId,
                        inCycle = false,
                        diffStatus = DiffStatus.REMOVED
                    )
                )
            }
        }

        val baseCycles = TarjanCycleDetector.findCycles(baseline).size
        val currCycles = TarjanCycleDetector.findCycles(current).size

        val baseCoupling = computeAverageDegree(baseline)
        val currCoupling = computeAverageDegree(current)
        val couplingDelta = if (baseCoupling == 0.0) {
            if (currCoupling > 0.0) 100.0 else 0.0
        } else {
            ((currCoupling - baseCoupling) / baseCoupling) * 100.0
        }

        val annotatedDiffGraph = TarjanCycleDetector.annotateGraphWithCycles(
            current.copy(nodes = mergedNodes, edges = mergedEdges)
        )

        return BlastRadiusReport(
            diffGraph = annotatedDiffGraph,
            addedNodeIds = addedIds.sorted(),
            modifiedNodeIds = modifiedIds.sorted(),
            removedNodeIds = removedIds.sorted(),
            blastImpactedNodeIds = blastImpacted.sorted(),
            baselineCycleCount = baseCycles,
            currentCycleCount = currCycles,
            couplingDeltaPercent = kotlin.math.round(couplingDelta * 10.0) / 10.0
        )
    }

    private fun hasSemanticNodeChange(a: ArcNode, b: ArcNode): Boolean {
        val isSketchBaselineAgainstScannedCode = a.source == null && b.source != null
        val membersChanged = (!isSketchBaselineAgainstScannedCode || a.members.isNotEmpty()) && a.members != b.members
        val contractsChanged = (!isSketchBaselineAgainstScannedCode || a.contracts.isNotEmpty()) && a.contracts != b.contracts
        val sourceFileChanged = (a.source != null && b.source != null &&
            (a.source.filePath != b.source.filePath ||
                a.source.language != b.source.language))

        return a.label != b.label ||
            a.kind != b.kind ||
            a.c4Level != b.c4Level ||
            a.parentId != b.parentId ||
            a.stereotypes != b.stereotypes ||
            membersChanged ||
            contractsChanged ||
            sourceFileChanged
    }

    private fun indexEdgesByUniqueKey(
        edges: List<ArcEdge>,
        multiEdgePairs: Set<String>
    ): LinkedHashMap<String, ArcEdge> {
        val map = linkedMapOf<String, ArcEdge>()
        val counts = mutableMapOf<String, Int>()
        for (edge in edges) {
            val baseKey = edgeBaseKey(edge, multiEdgePairs)
            val occ = (counts[baseKey] ?: 0) + 1
            counts[baseKey] = occ
            val uniqueKey = if (occ == 1) baseKey else "$baseKey#$occ"
            map[uniqueKey] = edge
        }
        return map
    }

    private fun edgeBaseKey(edge: ArcEdge, multiEdgePairs: Set<String>): String {
        val pairKey = "${edge.sourceId}->${edge.targetId}:${edge.kind}"
        return if (pairKey in multiEdgePairs) {
            "$pairKey:${edge.label.orEmpty()}"
        } else {
            pairKey
        }
    }

    private fun computeAverageDegree(graph: ArcGraph): Double {
        if (graph.nodes.isEmpty()) return 0.0
        val depEdges = graph.edges.count { it.kind != EdgeKind.FORBIDDEN_RULE && it.diffStatus != DiffStatus.REMOVED }
        return depEdges.toDouble() / graph.nodes.size.toDouble()
    }
}
