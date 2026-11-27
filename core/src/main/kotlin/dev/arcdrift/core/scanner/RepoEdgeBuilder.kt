package dev.arcdrift.core.scanner

import dev.arcdrift.core.ast.JvmParsedClassAst
import dev.arcdrift.core.ast.JvmParsedFileAst
import dev.arcdrift.core.model.ArcEdge
import dev.arcdrift.core.model.ArcGraph
import dev.arcdrift.core.model.ArcNode
import dev.arcdrift.core.model.EdgeKind
import dev.arcdrift.core.model.NodeKind
import java.io.File

internal data class ScannedJvmClassRecord(
    val fileAst: JvmParsedFileAst,
    val classAst: JvmParsedClassAst,
    val relPath: String,
    val language: String
)

/**
 * Resolves AST imports, supertype relationships, inline FQCN references, TypeScript relative imports,
 * Python module imports, and spec ghost edges into a deduplicated structural [ArcEdge] list.
 */
internal object RepoEdgeBuilder {

    private val TS_IMPORT_EXT_SUFFIXES = listOf(".tsx", ".ts", ".jsx", ".js", ".mjs", ".mts", ".cts")

    fun buildEdges(
        scannedJvmClasses: List<ScannedJvmClassRecord>,
        tsImportsByNodeId: Map<String, Pair<String, List<String>>>,
        pyImportsByNodeId: Map<String, List<String>>,
        nodesById: MutableMap<String, ArcNode>,
        specGraph: ArcGraph?
    ): List<ArcEdge> {
        val classesByPackage = scannedJvmClasses.map { it.classAst }.groupBy { it.pkg }
        val simpleNameToFqIds = scannedJvmClasses.map { it.classAst }.groupBy({ it.simpleName }, { it.fqId })
        val allKnownClassFqIds = scannedJvmClasses.map { it.classAst.fqId }.toSet()

        val edges = mutableListOf<ArcEdge>()
        val edgeKeys = mutableSetOf<String>()
        var edgeCounter = 1

        fun addEdgeIfNew(srcId: String, tgtId: String, kind: String, label: String? = null) {
            if (srcId == tgtId || tgtId !in nodesById) return
            val key = "$srcId->$tgtId:$kind"
            if (edgeKeys.add(key)) {
                edges.add(
                    ArcEdge(
                        id = "scan-e${edgeCounter++}",
                        sourceId = srcId,
                        targetId = tgtId,
                        kind = kind,
                        label = label
                    )
                )
            }
        }

        for (rec in scannedJvmClasses) {
            val fileAst = rec.fileAst
            val cls = rec.classAst
            val isMultiClassFile = fileAst.classes.size > 1
            val clsSimpleRefs = cls.referencedSimpleNames + cls.superTypeSimpleNames

            for (imp in fileAst.explicitImports) {
                val targetId = when {
                    imp in nodesById -> imp
                    else -> imp.substringBeforeLast('.', "").takeIf { it in nodesById }
                }
                if (targetId != null) {
                    val impSimple = imp.substringAfterLast('.')
                    val parentSimple = targetId.substringAfterLast('.')
                    val usedByThisClass = !isMultiClassFile ||
                        impSimple in clsSimpleRefs ||
                        parentSimple in clsSimpleRefs ||
                        imp in cls.referencedQualifiedNames ||
                        targetId in cls.referencedQualifiedNames
                    if (usedByThisClass) {
                        addEdgeIfNew(cls.fqId, targetId, EdgeKind.DEPENDS_ON)
                    }
                }
            }

            for (wildcardPkg in fileAst.wildcardImports) {
                val pkgClasses = classesByPackage[wildcardPkg].orEmpty()
                val referencedInPkg = pkgClasses.filter { it.simpleName in clsSimpleRefs }
                val targets = if (isMultiClassFile) referencedInPkg else referencedInPkg.ifEmpty { pkgClasses }
                for (candidate in targets) {
                    addEdgeIfNew(cls.fqId, candidate.fqId, EdgeKind.DEPENDS_ON, "wildcard-import")
                }
                if (targets.isEmpty() && !isMultiClassFile && wildcardPkg in nodesById) {
                    addEdgeIfNew(cls.fqId, wildcardPkg, EdgeKind.DEPENDS_ON, "wildcard-import")
                }
            }

            for ((alias, targetFq) in fileAst.importAliases) {
                if (targetFq in nodesById && (!isMultiClassFile || alias in clsSimpleRefs)) {
                    addEdgeIfNew(cls.fqId, targetFq, EdgeKind.DEPENDS_ON, "aliased-import")
                }
            }

            for (superSimple in cls.superTypeSimpleNames) {
                resolveSimpleNameTarget(fileAst, cls, superSimple, nodesById, simpleNameToFqIds)?.let { target ->
                    val tgtNode = nodesById[target]
                    val isExtends = cls.kind != NodeKind.INTERFACE && tgtNode != null && tgtNode.kind != NodeKind.INTERFACE
                    if (isExtends) {
                        addEdgeIfNew(cls.fqId, target, EdgeKind.EXTENDS, "extends")
                    } else {
                        addEdgeIfNew(cls.fqId, target, EdgeKind.IMPLEMENTS, "implements")
                    }
                }
            }

            for (refSimple in cls.referencedSimpleNames) {
                if (refSimple in cls.superTypeSimpleNames) continue
                resolveSimpleNameTarget(fileAst, cls, refSimple, nodesById, simpleNameToFqIds)?.let { target ->
                    addEdgeIfNew(cls.fqId, target, EdgeKind.DEPENDS_ON)
                }
            }

            for (refFq in cls.referencedQualifiedNames) {
                if (refFq in allKnownClassFqIds && refFq != cls.fqId) {
                    addEdgeIfNew(cls.fqId, refFq, EdgeKind.DEPENDS_ON, "inline-fqn")
                } else {
                    val parentFq = refFq.substringBeforeLast('.', "")
                    if (parentFq in allKnownClassFqIds && parentFq != cls.fqId) {
                        addEdgeIfNew(cls.fqId, parentFq, EdgeKind.DEPENDS_ON, "inline-fqn")
                    }
                }
            }
        }

        for ((tsNodeId, pair) in tsImportsByNodeId) {
            val (relPath, importPaths) = pair
            val parentDir = File(relPath).parentFile
            for (impPath in importPaths) {
                if (impPath.startsWith(".")) {
                    var resolvedRel = (if (parentDir != null) File(parentDir, impPath) else File(impPath))
                        .normalize()
                        .invariantSeparatorsPath
                    for (ext in TS_IMPORT_EXT_SUFFIXES) {
                        if (resolvedRel.endsWith(ext, ignoreCase = true)) {
                            resolvedRel = resolvedRel.dropLast(ext.length)
                            break
                        }
                    }
                    val baseTarget = "ts." + resolvedRel.replace('/', '.')
                    val resolvedTarget = when {
                        baseTarget in nodesById -> baseTarget
                        "$baseTarget.tsx" in nodesById -> "$baseTarget.tsx"
                        "$baseTarget.index" in nodesById -> "$baseTarget.index"
                        "$baseTarget.index.tsx" in nodesById -> "$baseTarget.index.tsx"
                        else -> baseTarget
                    }
                    addEdgeIfNew(tsNodeId, resolvedTarget, EdgeKind.DEPENDS_ON)
                }
            }
        }

        for ((pyNodeId, modules) in pyImportsByNodeId) {
            val currentPkgParts = pyNodeId.removePrefix("py.").split('.').dropLast(1)
            for (mod in modules) {
                val leadingDots = mod.takeWhile { it == '.' }.length
                val cleanMod = mod.drop(leadingDots)
                if (leadingDots > 0) {
                    val upCount = (leadingDots - 1).coerceAtLeast(0)
                    if (upCount <= currentPkgParts.size) {
                        val basePkg = currentPkgParts.dropLast(upCount)
                        val relParts = if (cleanMod.isEmpty()) basePkg else basePkg + cleanMod.split('.')
                        val relCandidate = "py." + relParts.joinToString(".")
                        val resolvedRel = when {
                            relCandidate in nodesById -> relCandidate
                            "$relCandidate.__init__" in nodesById -> "$relCandidate.__init__"
                            else -> null
                        }
                        if (resolvedRel != null) {
                            addEdgeIfNew(pyNodeId, resolvedRel, EdgeKind.DEPENDS_ON)
                            continue
                        }
                    }
                }
                if (cleanMod.isEmpty()) continue
                val directCandidate = "py.$cleanMod"
                when {
                    directCandidate in nodesById -> addEdgeIfNew(pyNodeId, directCandidate, EdgeKind.DEPENDS_ON)
                    "$directCandidate.__init__" in nodesById -> addEdgeIfNew(pyNodeId, "$directCandidate.__init__", EdgeKind.DEPENDS_ON)
                    else -> {
                        val match = nodesById.keys.firstOrNull {
                            it.startsWith("py.") && (it.endsWith(".$cleanMod") || it.endsWith(".$cleanMod.__init__"))
                        }
                        if (match != null) {
                            addEdgeIfNew(pyNodeId, match, EdgeKind.DEPENDS_ON)
                        }
                    }
                }
            }
        }

        if (specGraph != null) {
            val specNodeMap = specGraph.nodeMap()
            for ((id, node) in nodesById.entries.toList()) {
                val specNode = specNodeMap[id]
                if (specNode != null && specNode.layoutX != null && specNode.layoutY != null && node.layoutX == null) {
                    nodesById[id] = node.copy(layoutX = specNode.layoutX, layoutY = specNode.layoutY)
                }
            }
            for (e in specGraph.edges) {
                val srcKind = nodesById[e.sourceId]?.kind
                val tgtKind = nodesById[e.targetId]?.kind
                if (e.sourceId in nodesById && e.targetId in nodesById &&
                    (srcKind == NodeKind.GHOST_SPEC || tgtKind == NodeKind.GHOST_SPEC)
                ) {
                    addEdgeIfNew(e.sourceId, e.targetId, e.kind, e.label)
                }
            }
        }

        val superEdgePairs = edges
            .filter { it.kind == EdgeKind.IMPLEMENTS || it.kind == EdgeKind.EXTENDS }
            .map { "${it.sourceId}->${it.targetId}" }
            .toSet()
        return edges.filterNot {
            it.kind == EdgeKind.DEPENDS_ON && "${it.sourceId}->${it.targetId}" in superEdgePairs
        }
    }

    private fun resolveSimpleNameTarget(
        fileAst: JvmParsedFileAst,
        cls: JvmParsedClassAst,
        simpleName: String,
        nodesById: Map<String, ArcNode>,
        simpleNameToFqIds: Map<String, List<String>>
    ): String? {
        // 1. Check import aliases (`import com.acme.v2.OrderPort as Port`)
        fileAst.importAliases[simpleName]?.takeIf { it in nodesById && it != cls.fqId }?.let { return it }

        // 2. Check explicit imports (`import com.acme.v2.OrderPort`) — also disambiguates multiple classes sharing `simpleName`!
        val explicitMatches = fileAst.explicitImports.filter { it.substringAfterLast('.') == simpleName }
        if (explicitMatches.isNotEmpty()) {
            return explicitMatches.firstOrNull { it in nodesById && it != cls.fqId }
        }

        // 3. Same-package visibility
        val samePkgCandidate = if (cls.pkg.isNotEmpty()) "${cls.pkg}.$simpleName" else simpleName
        if (samePkgCandidate in nodesById && samePkgCandidate != cls.fqId) {
            return samePkgCandidate
        }

        // 4. Wildcard imports (`import com.acme.v2.*`)
        for (wildcardPkg in fileAst.wildcardImports) {
            val wildcardCandidate = "$wildcardPkg.$simpleName"
            if (wildcardCandidate in nodesById && wildcardCandidate != cls.fqId) {
                return wildcardCandidate
            }
        }

        // 5. Only fall back to unique global match when source omitted packages/imports altogether (synthetic snippets)
        if (cls.pkg.isEmpty() && fileAst.explicitImports.isEmpty() && fileAst.wildcardImports.isEmpty()) {
            val candidates = simpleNameToFqIds[simpleName].orEmpty()
            return if (candidates.size == 1 && candidates.first() != cls.fqId) candidates.first() else null
        }

        return null
    }
}
