package dev.arcdrift.core.analysis

import dev.arcdrift.core.model.ArcEdge
import dev.arcdrift.core.model.ArcGraph
import dev.arcdrift.core.model.DiffStatus
import dev.arcdrift.core.model.EdgeKind
import kotlinx.serialization.Serializable

/**
 * Represents a strongly connected component (circular dependency cycle)
 * detected in an [ArcGraph], along with a recommended edge to break the cycle.
 */
@Serializable
data class DependencyCycle(
    val id: String,
    val nodeIds: List<String>,
    val cycleEdges: List<ArcEdge>,
    val recommendedBreakEdge: ArcEdge?,
    val recommendationRationale: String
)

/**
 * Deterministic Tarjan's Strongly Connected Components (SCC) cycle detector.
 * Identifies all multi-node cycles and self-loops (excluding non-dependency rule edges and removed diff elements)
 * and suggests the minimum-weight feedback edge to break each cycle (e.g., via interface extraction).
 */
object TarjanCycleDetector {

    fun findCycles(graph: ArcGraph): List<DependencyCycle> {
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
        val adjacency = structuralEdges.groupBy { it.sourceId }
        val activeGraphNodeIds = graph.nodes
            .filter { it.diffStatus != DiffStatus.REMOVED }
            .map { it.id }
        val allNodeIds = (activeGraphNodeIds + structuralEdges.flatMap { listOf(it.sourceId, it.targetId) })
            .distinct()
            .sorted()

        val sccs = computeSccs(allNodeIds, adjacency)

        val cycles = mutableListOf<DependencyCycle>()
        var cycleCounter = 1

        for (component in sccs.sortedBy { it.firstOrNull() ?: "" }) {
            val componentSet = component.toSet()
            val internalEdges = structuralEdges.filter {
                it.sourceId in componentSet && it.targetId in componentSet
            }
            val isSelfLoop = component.size == 1 && internalEdges.any { it.sourceId == it.targetId }
            if (component.size > 1 || isSelfLoop) {
                val orderedNodes = orderComponentAlongCycle(component, internalEdges)
                val breakCandidate = selectBreakEdge(component, internalEdges)
                val rationale = if (breakCandidate != null) {
                    val srcShort = breakCandidate.sourceId.substringAfterLast('.')
                    val tgtShort = breakCandidate.targetId.substringAfterLast('.')
                    "Extract an interface from '$tgtShort' or invert the dependency '$srcShort -> $tgtShort' (lowest coupling weight: ${breakCandidate.weight})."
                } else {
                    "Break circular dependency among ${orderedNodes.joinToString(", ")}."
                }
                cycles.add(
                    DependencyCycle(
                        id = "cycle-${cycleCounter++}",
                        nodeIds = orderedNodes,
                        cycleEdges = internalEdges,
                        recommendedBreakEdge = breakCandidate,
                        recommendationRationale = rationale
                    )
                )
            }
        }

        return cycles
    }

    private fun computeSccs(
        sortedNodeIds: List<String>,
        adjacency: Map<String, List<ArcEdge>>
    ): List<List<String>> {
        var index = 0
        val indices = mutableMapOf<String, Int>()
        val lowlink = mutableMapOf<String, Int>()
        val onStack = mutableSetOf<String>()
        val stack = ArrayDeque<String>()
        val sccs = mutableListOf<List<String>>()

        fun strongConnect(nodeId: String) {
            indices[nodeId] = index
            lowlink[nodeId] = index
            index++
            stack.addLast(nodeId)
            onStack.add(nodeId)

            for (edge in adjacency[nodeId].orEmpty()) {
                val neighbor = edge.targetId
                if (neighbor !in indices) {
                    strongConnect(neighbor)
                    lowlink[nodeId] = minOf(lowlink.getValue(nodeId), lowlink.getValue(neighbor))
                } else if (neighbor in onStack) {
                    lowlink[nodeId] = minOf(lowlink.getValue(nodeId), indices.getValue(neighbor))
                }
            }

            if (lowlink[nodeId] == indices[nodeId]) {
                val component = mutableListOf<String>()
                while (true) {
                    val w = stack.removeLast()
                    onStack.remove(w)
                    component.add(w)
                    if (w == nodeId) break
                }
                sccs.add(component.sorted())
            }
        }

        for (nodeId in sortedNodeIds) {
            if (nodeId !in indices) {
                strongConnect(nodeId)
            }
        }
        return sccs
    }

    /**
     * Orders `component` starting from its lexicographically smallest node following directed edges
     * so `A -> C -> B -> A` is reported in true traversal order `["A", "C", "B"]` rather than alphabetical `["A", "B", "C"]`.
     */
    private fun orderComponentAlongCycle(component: List<String>, internalEdges: List<ArcEdge>): List<String> {
        if (component.size <= 2) return component
        val start = component.first()
        val adj = internalEdges
            .filter { it.sourceId != it.targetId }
            .groupBy({ it.sourceId }, { it.targetId })
            .mapValues { (_, targets) -> targets.distinct().sorted() }

        // Try to find a Hamiltonian / longest simple cycle walk starting at `start`
        val visited = linkedSetOf(start)
        fun dfsWalk(curr: String) {
            for (next in adj[curr].orEmpty()) {
                if (next !in visited) {
                    visited.add(next)
                    dfsWalk(next)
                }
            }
        }
        dfsWalk(start)
        for (rem in component) {
            visited.add(rem)
        }
        return visited.toList()
    }

    private fun selectBreakEdge(component: List<String>, internalEdges: List<ArcEdge>): ArcEdge? {
        if (internalEdges.isEmpty()) return null
        val edgeComparator = compareBy<ArcEdge> { it.weight }
            .thenBy { it.sourceId }
            .thenBy { it.targetId }
            .thenBy { it.id }
        if (component.size == 1) {
            return internalEdges.filter { it.sourceId == it.targetId }.minWithOrNull(edgeComparator)
                ?: internalEdges.minWithOrNull(edgeComparator)
        }
        // Prefer an edge whose removal breaks the SCC into smaller components (no remaining multi-node cycle or fewer cyclic nodes)
        val nonSelfEdges = internalEdges.filter { it.sourceId != it.targetId }.ifEmpty { internalEdges }
        val ranked = nonSelfEdges.map { candidate ->
            val remaining = internalEdges.filter { it.id != candidate.id }
            val remAdj = remaining.groupBy { it.sourceId }
            val subSccs = computeSccs(component, remAdj)
            val remainingCyclicNodes = subSccs.sumOf { scc ->
                val sccSet = scc.toSet()
                val hasSelf = scc.size == 1 && remaining.any { it.sourceId in sccSet && it.sourceId == it.targetId }
                if (scc.size > 1 || hasSelf) scc.size else 0
            }
            candidate to remainingCyclicNodes
        }
        val bestRemainingCount = ranked.minOf { it.second }
        return ranked
            .filter { it.second == bestRemainingCount }
            .map { it.first }
            .minWithOrNull(edgeComparator)
    }

    /**
     * Returns a copy of [graph] with `inCycle = true` marked on all edges participating in any active cycle.
     */
    fun annotateGraphWithCycles(graph: ArcGraph): ArcGraph {
        val cycles = findCycles(graph)
        val cycleEdgeIds = cycles.flatMap { c -> c.cycleEdges.map { it.id } }.toSet()
        if (cycleEdgeIds.isEmpty() && graph.edges.none { it.inCycle }) return graph
        return graph.copy(
            edges = graph.edges.map { edge ->
                val isActiveCycleEdge = edge.diffStatus != DiffStatus.REMOVED && edge.id in cycleEdgeIds
                if (isActiveCycleEdge) edge.copy(inCycle = true) else edge.copy(inCycle = false)
            }
        )
    }
}
