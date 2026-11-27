package dev.arcdrift.core.refactoring

import dev.arcdrift.core.model.*
import dev.arcdrift.core.rules.ArcRuleEngine
import dev.arcdrift.core.scanner.PolyglotRepoScanner
import java.io.File
import java.nio.file.Files

/**
 * File-system + in-memory implementation of [RefactoringPort] used by the `arc` CLI and MCP server.
 *
 * When `rootDir` is provided (e.g., `arc mcp /path/to/repo`) and `previewOnly == false`,
 * every [RefactoringCommand] deterministically mutates the actual `.kt` / `.java` source files on disk
 * AND re-scans the repository so the live [currentGraph] reflects the true disk state.
 */
class InMemoryGraphRefactoringAdapter(
    initialGraph: ArcGraph,
    private val rootDir: File? = null,
    private val customScanner: ((File) -> ArcGraph)? = null
) : RefactoringPort {

    @Volatile
    var currentGraph: ArcGraph = initialGraph
        private set

    @Synchronized
    fun rescanFromDiskIfAvailable(): ArcGraph {
        val dir = rootDir
        if (dir != null && dir.exists() && dir.isDirectory) {
            val rescanned = try {
                customScanner?.invoke(dir) ?: PolyglotRepoScanner.scanRepository(dir)
            } catch (ise: IllegalStateException) {
                throw ise
            } catch (_: Throwable) {
                null
            }
            if (rescanned != null && rescanned.nodes.isNotEmpty()) {
                val hasDiskSpec = PolyglotRepoScanner.findSpecFiles(dir).isNotEmpty()
                val effectiveRules = if (hasDiskSpec || rescanned.rules.isNotEmpty()) {
                    rescanned.rules.distinctBy { it.id }
                } else {
                    currentGraph.rules
                }
                val withRules = rescanned.copy(rules = effectiveRules)
                currentGraph = ArcRuleEngine.annotateViolations(withRules, ArcRuleEngine.evaluate(withRules))
            }
        }
        return currentGraph
    }

    @Synchronized
    override fun execute(command: RefactoringCommand, previewOnly: Boolean): RefactoringResult {
        if (!previewOnly || currentGraph.nodes.isEmpty()) {
            rescanFromDiskIfAvailable()
        }
        return when (command) {
            is RefactoringCommand.RenameSymbol -> handleRename(command, previewOnly)
            is RefactoringCommand.MoveClassOrPackage -> handleMove(command, previewOnly)
            is RefactoringCommand.ExtractInterface -> handleExtractInterface(command, previewOnly)
            is RefactoringCommand.CreateNode -> handleCreateNode(command, previewOnly)
            is RefactoringCommand.AddDependency -> handleAddDependency(command, previewOnly)
        }
    }

    private fun handleRename(cmd: RefactoringCommand.RenameSymbol, previewOnly: Boolean): RefactoringResult {
        if (!DiskSourceRewriter.isValidSimpleIdentifier(cmd.newSimpleName)) {
            return RefactoringResult(false, "Invalid identifier '${cmd.newSimpleName}'.", errorMessage = "INVALID_IDENTIFIER")
        }
        val target = currentGraph.nodes.find { it.id == cmd.targetNodeId }
            ?: return RefactoringResult(false, "Target node '${cmd.targetNodeId}' not found.", errorMessage = "NODE_NOT_FOUND")
        if (target.kind == NodeKind.PACKAGE || target.kind == NodeKind.MODULE || target.kind == NodeKind.SYSTEM) {
            return RefactoringResult(false, "Cannot rename container node '${cmd.targetNodeId}' with RENAME_SYMBOL.", errorMessage = "UNSUPPORTED_NODE_KIND")
        }

        val oldSimple = target.label.substringAfterLast('.')
        val prefix = cmd.targetNodeId.substringBeforeLast('.', "")
        val newId = if (prefix.isNotEmpty()) "$prefix.${cmd.newSimpleName}" else cmd.newSimpleName
        if (newId != cmd.targetNodeId && currentGraph.nodes.any { it.id == newId }) {
            return RefactoringResult(false, "Symbol '$newId' already exists.", errorMessage = "ALREADY_EXISTS")
        }
        val impactedFiles = collectImpactedFiles(cmd.targetNodeId).toMutableList()

        if (!previewOnly) {
            val dir = rootDir
            if (dir != null && dir.exists()) {
                try {
                    DiskSourceTransaction.execute { tx ->
                        val excludedPaths = mutableSetOf<String>()
                        val targetRelPath = target.source?.filePath
                        if (targetRelPath != null) {
                            val srcFile = resolveRepoFileOrNull(dir, targetRelPath)
                            if (srcFile != null && srcFile.exists()) {
                                val isJava = srcFile.extension.equals("java", ignoreCase = true)
                                val originalText = srcFile.readText(Charsets.UTF_8)
                                val updatedSrcText = DiskSourceRewriter.rewriteSymbolInSingleFile(
                                    source = originalText,
                                    oldFqName = cmd.targetNodeId,
                                    newFqName = newId,
                                    oldSimple = oldSimple,
                                    newSimple = cmd.newSimpleName,
                                    isJava = isJava
                                )
                                DiskSourceRewriter.verifyNoNewSyntaxErrors(originalText, updatedSrcText, isJava, srcFile.name)
                                val declCount = DiskSourceRewriter.countTopLevelDeclarations(originalText, isJava)
                                val isNestedClass = target.parentId != null &&
                                    cmd.targetNodeId.startsWith("${target.parentId}.") &&
                                    cmd.targetNodeId.removePrefix("${target.parentId}.").contains('.')
                                val shouldRenameFile = !isNestedClass &&
                                    declCount <= 1 &&
                                    srcFile.nameWithoutExtension == oldSimple
                                val candidateNewFile = resolveRepoFile(dir, File(srcFile.parentFile, "${cmd.newSimpleName}.${srcFile.extension}").path)
                                val isSamePhysicalFile = candidateNewFile.exists() && runCatching {
                                    Files.isSameFile(srcFile.toPath(), candidateNewFile.toPath())
                                }.getOrDefault(false)
                                if (shouldRenameFile && candidateNewFile.exists() && !isSamePhysicalFile) {
                                    error("Target file '${candidateNewFile.name}' already exists.")
                                }
                                val outFile = if (shouldRenameFile) candidateNewFile else srcFile
                                if (shouldRenameFile) {
                                    tx.moveOrRenameWithContent(srcFile, outFile, updatedSrcText)
                                } else {
                                    tx.writeText(srcFile, updatedSrcText)
                                }
                                excludedPaths.add(outFile.canonicalPath)
                                excludedPaths.add(srcFile.canonicalPath)
                                impactedFiles.add(outFile.relativeToOrSelf(dir.canonicalFile).invariantSeparatorsPath)
                            }
                        }
                        val modifiedAcrossRepo = DiskSourceRewriter.updateSymbolAcrossJvmFiles(
                            rootDir = dir,
                            oldFqName = cmd.targetNodeId,
                            newFqName = newId,
                            oldSimple = oldSimple,
                            newSimple = cmd.newSimpleName,
                            existingTx = tx,
                            excludeCanonicalPaths = excludedPaths
                        )
                        impactedFiles.addAll(modifiedAcrossRepo)
                    }
                } catch (e: Exception) {
                    return RefactoringResult(
                        success = false,
                        summary = "Rename failed: ${e.message}",
                        errorMessage = if (e.message?.contains("already exists") == true) "ALREADY_EXISTS" else "REFACTORING_FAILED"
                    )
                }
                rescanFromDiskIfAvailable()
            }
            if (currentGraph.nodes.none { it.id == newId }) {
                val updatedNodes = currentGraph.nodes.map { n ->
                    when {
                        n.id == cmd.targetNodeId -> n.copy(
                            id = newId,
                            label = cmd.newSimpleName,
                            diffStatus = DiffStatus.MODIFIED
                        )
                        n.id.startsWith("${cmd.targetNodeId}.") -> {
                            val suffix = n.id.removePrefix(cmd.targetNodeId)
                            val nextParent = when {
                                n.parentId == cmd.targetNodeId -> newId
                                n.parentId?.startsWith("${cmd.targetNodeId}.") == true ->
                                    "$newId${n.parentId.removePrefix(cmd.targetNodeId)}"
                                else -> n.parentId
                            }
                            n.copy(id = "$newId$suffix", parentId = nextParent)
                        }
                        n.parentId == cmd.targetNodeId -> n.copy(parentId = newId)
                        else -> n
                    }
                }
                val updatedEdges = currentGraph.edges.map { e ->
                    val nextSrc = when {
                        e.sourceId == cmd.targetNodeId -> newId
                        e.sourceId.startsWith("${cmd.targetNodeId}.") -> "$newId${e.sourceId.removePrefix(cmd.targetNodeId)}"
                        else -> e.sourceId
                    }
                    val nextTgt = when {
                        e.targetId == cmd.targetNodeId -> newId
                        e.targetId.startsWith("${cmd.targetNodeId}.") -> "$newId${e.targetId.removePrefix(cmd.targetNodeId)}"
                        else -> e.targetId
                    }
                    e.copy(sourceId = nextSrc, targetId = nextTgt)
                }
                currentGraph = currentGraph.copy(nodes = updatedNodes, edges = updatedEdges)
            }
        }

        return RefactoringResult(
            success = true,
            summary = "${if (previewOnly) "[Preview] " else ""}Renamed '${target.label}' to '${cmd.newSimpleName}' ($newId)",
            touchedFiles = impactedFiles.distinct().sorted()
        )
    }

    private fun handleMove(cmd: RefactoringCommand.MoveClassOrPackage, previewOnly: Boolean): RefactoringResult {
        if (!DiskSourceRewriter.isValidPackageName(cmd.destinationPackage) || cmd.destinationPackage.isBlank()) {
            return RefactoringResult(false, "Invalid destination package '${cmd.destinationPackage}'.", errorMessage = "INVALID_PACKAGE")
        }
        val target = currentGraph.nodes.find { it.id == cmd.targetNodeId }
            ?: return RefactoringResult(false, "Target node '${cmd.targetNodeId}' not found.", errorMessage = "NODE_NOT_FOUND")

        if (target.kind == NodeKind.PACKAGE) {
            return handleMovePackage(target.id, cmd.destinationPackage, previewOnly)
        }

        val oldPkg = target.parentId ?: cmd.targetNodeId.substringBeforeLast('.', "")
        val typeChain = if (oldPkg.isNotEmpty() && cmd.targetNodeId.startsWith("$oldPkg.")) {
            cmd.targetNodeId.removePrefix("$oldPkg.")
        } else {
            cmd.targetNodeId.substringAfterLast('.')
        }
        if (typeChain.contains('.')) {
            return RefactoringResult(
                success = false,
                summary = "Cannot move nested class '${cmd.targetNodeId}' independently of its outer class.",
                errorMessage = "NESTED_CLASS_MOVE_UNSUPPORTED"
            )
        }

        val simpleName = typeChain
        val newId = "${cmd.destinationPackage}.$simpleName"
        if (newId != cmd.targetNodeId && currentGraph.nodes.any { it.id == newId }) {
            return RefactoringResult(false, "Target '$newId' already exists in '${cmd.destinationPackage}'.", errorMessage = "ALREADY_EXISTS")
        }
        val impactedFiles = collectImpactedFiles(cmd.targetNodeId).toMutableList()

        if (!previewOnly) {
            val dir = rootDir
            if (dir != null && dir.exists()) {
                try {
                    DiskSourceTransaction.execute { tx ->
                        moveSingleClassOnDiskInTx(
                            tx = tx,
                            dir = dir,
                            target = target,
                            oldPkg = oldPkg,
                            destPkg = cmd.destinationPackage,
                            simpleName = simpleName,
                            newId = newId,
                            impactedFiles = impactedFiles,
                            batchMovedSimpleNames = setOf(simpleName)
                        )
                    }
                } catch (e: Exception) {
                    val errCode = when {
                        e.message?.contains("already exists") == true -> "ALREADY_EXISTS"
                        e.message?.contains("top-level declarations") == true -> "MULTI_DECLARATION_FILE"
                        else -> "REFACTORING_FAILED"
                    }
                    return RefactoringResult(false, "Move failed: ${e.message}", errorMessage = errCode)
                }
                rescanFromDiskIfAvailable()
            }
            if (currentGraph.nodes.none { it.id == newId }) {
                applyInMemoryMoveClass(cmd.targetNodeId, newId, cmd.destinationPackage)
            }
        }

        return RefactoringResult(
            success = true,
            summary = "${if (previewOnly) "[Preview] " else ""}Moved '${target.label}' to package '${cmd.destinationPackage}' ($newId)",
            touchedFiles = impactedFiles.distinct().sorted()
        )
    }

    private fun moveSingleClassOnDiskInTx(
        tx: DiskSourceTransaction,
        dir: File,
        target: ArcNode,
        oldPkg: String,
        destPkg: String,
        simpleName: String,
        newId: String,
        impactedFiles: MutableList<String>,
        batchMovedSimpleNames: Set<String>
    ) {
        val excludedPaths = mutableSetOf<String>()
        val targetRelPath = target.source?.filePath
        if (targetRelPath != null) {
            val srcFile = resolveRepoFileOrNull(dir, targetRelPath)
            if (srcFile != null && srcFile.exists()) {
                val isJava = srcFile.extension.equals("java", ignoreCase = true)
                val content = srcFile.readText(Charsets.UTF_8)
                val declCount = DiskSourceRewriter.countTopLevelDeclarations(content, isJava)
                if (declCount > 1) {
                    error("Cannot move '$simpleName' because '${srcFile.name}' contains $declCount top-level declarations.")
                }
                val relNormalized = srcFile.relativeToOrSelf(dir.canonicalFile).invariantSeparatorsPath
                val sourceRoot = DiskSourceRewriter.inferSourceRoot(dir.canonicalFile, relNormalized)
                val destDir = resolveRepoFile(dir, File(sourceRoot, destPkg.replace('.', '/')).path)
                val destFile = resolveRepoFile(dir, File(destDir, srcFile.name).path)
                val isSamePhysicalDest = destFile.exists() && runCatching {
                    Files.isSameFile(srcFile.toPath(), destFile.toPath())
                }.getOrDefault(false)
                if (destFile.exists() && !isSamePhysicalDest) {
                    error("Destination file already exists: ${destFile.relativeToOrSelf(dir.canonicalFile).invariantSeparatorsPath}")
                }
                var updatedContent = DiskSourceRewriter.rewritePackageDirective(content, destPkg, isJava)
                // Insert explicit imports for any same-package siblings in `oldPkg` that remain behind and are still referenced
                if (oldPkg.isNotEmpty() && oldPkg != destPkg) {
                    val siblingNodes = currentGraph.nodes.filter {
                        it.parentId == oldPkg &&
                            it.id != target.id &&
                            it.kind != NodeKind.PACKAGE &&
                            it.kind != NodeKind.GHOST_SPEC &&
                            !it.id.removePrefix("$oldPkg.").contains('.')
                    }
                    val siblingFileNames = srcFile.parentFile?.listFiles()
                        ?.filter { it.isFile && it != srcFile && (it.extension == "kt" || it.extension == "java") }
                        ?.map { it.nameWithoutExtension }
                        .orEmpty()
                    val candidateSiblings = (siblingNodes.map { it.label } + siblingFileNames).distinct()
                    for (sibSimple in candidateSiblings) {
                        if (sibSimple !in batchMovedSimpleNames &&
                            !DiskSourceRewriter.hasExplicitImportForSimpleName(updatedContent, sibSimple, isJava) &&
                            DiskSourceRewriter.containsTypeReference(updatedContent, sibSimple, isJava)
                        ) {
                            updatedContent = DiskSourceRewriter.insertImport(updatedContent, "$oldPkg.$sibSimple", isJava)
                        }
                    }
                }
                DiskSourceRewriter.verifyNoNewSyntaxErrors(content, updatedContent, isJava, destFile.name)
                tx.moveOrRenameWithContent(srcFile, destFile, updatedContent)
                excludedPaths.add(destFile.canonicalPath)
                excludedPaths.add(srcFile.canonicalPath)
                impactedFiles.add(destFile.relativeToOrSelf(dir.canonicalFile).invariantSeparatorsPath)
            }
        }
        val modifiedAcrossRepo = DiskSourceRewriter.updateSymbolAcrossJvmFiles(
            rootDir = dir,
            oldFqName = target.id,
            newFqName = newId,
            oldSimple = simpleName,
            newSimple = simpleName,
            existingTx = tx,
            excludeCanonicalPaths = excludedPaths
        )
        impactedFiles.addAll(modifiedAcrossRepo)
    }

    private fun applyInMemoryMoveClass(oldId: String, newId: String, destPkg: String) {
        val oldPkg = oldId.substringBeforeLast('.', "")
        val updatedNodes = currentGraph.nodes.map { n ->
            when {
                n.id == oldId -> n.copy(
                    id = newId,
                    parentId = destPkg,
                    diffStatus = DiffStatus.MODIFIED
                )
                n.id.startsWith("$oldId.") -> {
                    val suffix = n.id.removePrefix(oldId)
                    val nextParent = when {
                        n.parentId == oldId -> newId
                        n.parentId?.startsWith("$oldId.") == true -> "$newId${n.parentId.removePrefix(oldId)}"
                        oldPkg.isNotEmpty() && n.parentId == oldPkg -> destPkg
                        else -> n.parentId
                    }
                    n.copy(id = "$newId$suffix", parentId = nextParent)
                }
                else -> n
            }
        }
        val updatedEdges = currentGraph.edges.map { e ->
            val nextSrc = when {
                e.sourceId == oldId -> newId
                e.sourceId.startsWith("$oldId.") -> "$newId${e.sourceId.removePrefix(oldId)}"
                else -> e.sourceId
            }
            val nextTgt = when {
                e.targetId == oldId -> newId
                e.targetId.startsWith("$oldId.") -> "$newId${e.targetId.removePrefix(oldId)}"
                else -> e.targetId
            }
            e.copy(sourceId = nextSrc, targetId = nextTgt)
        }
        currentGraph = currentGraph.copy(nodes = updatedNodes, edges = updatedEdges)
    }

    private fun handleMovePackage(oldPkg: String, destPkg: String, previewOnly: Boolean): RefactoringResult {
        val directClasses = currentGraph.nodes.filter {
            it.parentId == oldPkg &&
                it.kind != NodeKind.PACKAGE &&
                it.kind != NodeKind.GHOST_SPEC &&
                !it.id.removePrefix("$oldPkg.").contains('.')
        }
        if (directClasses.isEmpty()) {
            return RefactoringResult(false, "Package '$oldPkg' contains no movable top-level classes.", errorMessage = "EMPTY_PACKAGE")
        }
        for (cls in directClasses) {
            val simpleName = cls.id.removePrefix("$oldPkg.")
            val newId = "$destPkg.$simpleName"
            if (newId != cls.id && currentGraph.nodes.any { it.id == newId }) {
                return RefactoringResult(false, "Target '$newId' already exists in '$destPkg'.", errorMessage = "ALREADY_EXISTS")
            }
        }
        val allTouched = directClasses.flatMap { collectImpactedFiles(it.id) }.toMutableList()
        if (!previewOnly) {
            val dir = rootDir
            if (dir != null && dir.exists()) {
                val batchSimples = directClasses.map { it.id.removePrefix("$oldPkg.") }.toSet()
                try {
                    DiskSourceTransaction.execute { tx ->
                        for (cls in directClasses) {
                            val simpleName = cls.id.removePrefix("$oldPkg.")
                            val newId = "$destPkg.$simpleName"
                            moveSingleClassOnDiskInTx(
                                tx = tx,
                                dir = dir,
                                target = cls,
                                oldPkg = oldPkg,
                                destPkg = destPkg,
                                simpleName = simpleName,
                                newId = newId,
                                impactedFiles = allTouched,
                                batchMovedSimpleNames = batchSimples
                            )
                        }
                    }
                } catch (e: Exception) {
                    val errCode = when {
                        e.message?.contains("already exists") == true -> "ALREADY_EXISTS"
                        e.message?.contains("top-level declarations") == true -> "MULTI_DECLARATION_FILE"
                        else -> "REFACTORING_FAILED"
                    }
                    return RefactoringResult(false, "Move failed: ${e.message}", errorMessage = errCode)
                }
                rescanFromDiskIfAvailable()
            }
            for (cls in directClasses) {
                val simpleName = cls.id.removePrefix("$oldPkg.")
                val newId = "$destPkg.$simpleName"
                if (currentGraph.nodes.none { it.id == newId }) {
                    applyInMemoryMoveClass(cls.id, newId, destPkg)
                }
            }
        }
        return RefactoringResult(
            success = true,
            summary = "${if (previewOnly) "[Preview] " else ""}Moved package '$oldPkg' (${directClasses.size} classes) to '$destPkg'",
            touchedFiles = allTouched.distinct().sorted()
        )
    }

    private fun handleExtractInterface(cmd: RefactoringCommand.ExtractInterface, previewOnly: Boolean): RefactoringResult {
        if (!DiskSourceRewriter.isValidSimpleIdentifier(cmd.interfaceSimpleName) ||
            !DiskSourceRewriter.isValidPackageName(cmd.targetPackage) ||
            cmd.targetPackage.isBlank()
        ) {
            return RefactoringResult(false, "Invalid interface name or target package.", errorMessage = "INVALID_IDENTIFIER")
        }
        val sourceNode = currentGraph.nodes.find { it.id == cmd.sourceClassId }
            ?: return RefactoringResult(false, "Source class '${cmd.sourceClassId}' not found.", errorMessage = "NODE_NOT_FOUND")

        val interfaceId = "${cmd.targetPackage}.${cmd.interfaceSimpleName}"
        if (currentGraph.nodes.any { it.id == interfaceId && it.kind != NodeKind.GHOST_SPEC }) {
            return RefactoringResult(false, "Interface '$interfaceId' already exists.", errorMessage = "ALREADY_EXISTS")
        }
        val extractedMembers = if (cmd.memberNames.isEmpty()) {
            sourceNode.members.filter { it.kind == "METHOD" && it.visibility == "public" }
        } else {
            sourceNode.members.filter { it.kind == "METHOD" && it.visibility == "public" && it.name in cmd.memberNames }
        }

        val isJavaSrc = sourceNode.source?.filePath?.endsWith(".java", ignoreCase = true) == true
        val ext = if (isJavaSrc) "java" else "kt"
        val defaultSrcPrefix = if (isJavaSrc) "src/main/java" else "src/main/kotlin"
        val defaultRelPath = "$defaultSrcPrefix/${interfaceId.replace('.', '/')}.$ext"
        var primaryCreatedPath = defaultRelPath
        val impactedFiles = (collectImpactedFiles(cmd.sourceClassId) + defaultRelPath).toMutableList()

        if (!previewOnly) {
            val dir = rootDir
            val srcRelPath = sourceNode.source?.filePath
            val resolvedSrcFile = if (dir != null && srcRelPath != null) resolveRepoFileOrNull(dir, srcRelPath) else null
            if (dir != null && dir.exists() && resolvedSrcFile != null && resolvedSrcFile.exists()) {
                try {
                    primaryCreatedPath = DiskSourceRewriter.writeExtractedInterfaceAndRewireCallers(
                        dir = dir,
                        srcFile = resolvedSrcFile,
                        sourceNode = sourceNode,
                        cmd = cmd,
                        interfaceId = interfaceId,
                        extractedMembers = extractedMembers,
                        currentGraph = currentGraph,
                        impactedFiles = impactedFiles
                    )
                } catch (e: Exception) {
                    val errCode = if (e.message?.contains("already exists") == true) "ALREADY_EXISTS" else "REFACTORING_FAILED"
                    return RefactoringResult(false, "ExtractInterface failed: ${e.message}", errorMessage = errCode)
                }
                rescanFromDiskIfAvailable()
                if (currentGraph.nodes.none { it.id == interfaceId }) {
                    applyInMemoryExtractInterface(sourceNode, cmd, interfaceId, extractedMembers, defaultRelPath)
                }
            } else {
                applyInMemoryExtractInterface(sourceNode, cmd, interfaceId, extractedMembers, defaultRelPath)
            }
        }

        return RefactoringResult(
            success = true,
            summary = "${if (previewOnly) "[Preview] " else ""}Extracted interface '${cmd.interfaceSimpleName}' ($interfaceId) from '${sourceNode.label}' and rewired callers.",
            touchedFiles = (listOf(primaryCreatedPath) + impactedFiles).distinct()
        )
    }

    private fun applyInMemoryExtractInterface(
        sourceNode: ArcNode,
        cmd: RefactoringCommand.ExtractInterface,
        interfaceId: String,
        extractedMembers: List<MemberSignature>,
        defaultRelPath: String
    ) {
        val interfaceNode = ArcNode(
            id = interfaceId,
            label = cmd.interfaceSimpleName,
            c4Level = sourceNode.c4Level,
            kind = NodeKind.INTERFACE,
            parentId = cmd.targetPackage,
            members = extractedMembers,
            source = SourceLocation(defaultRelPath, 1, 20, if (defaultRelPath.endsWith(".java")) "java" else "kotlin"),
            diffStatus = DiffStatus.ADDED
        )
        val rewritableEdgeKinds = setOf(EdgeKind.DEPENDS_ON, EdgeKind.CALLS)
        val rewiredEdges = currentGraph.edges.map { e ->
            if (e.targetId == cmd.sourceClassId && e.sourceId != cmd.sourceClassId && e.kind in rewritableEdgeKinds) {
                e.copy(targetId = interfaceId, diffStatus = DiffStatus.MODIFIED)
            } else {
                e
            }
        } + ArcEdge(
            id = "impl-${sourceNode.label}-${cmd.interfaceSimpleName}",
            sourceId = cmd.sourceClassId,
            targetId = interfaceId,
            kind = EdgeKind.IMPLEMENTS,
            label = "implements",
            diffStatus = DiffStatus.ADDED
        )

        val updatedGraph = currentGraph.copy(
            nodes = currentGraph.nodes.filterNot { it.id == interfaceId } + interfaceNode,
            edges = rewiredEdges
        )
        currentGraph = ArcRuleEngine.annotateViolations(updatedGraph, ArcRuleEngine.evaluate(updatedGraph))
    }

    private fun handleCreateNode(cmd: RefactoringCommand.CreateNode, previewOnly: Boolean): RefactoringResult {
        if (!DiskSourceRewriter.isValidFqName(cmd.fqName)) {
            return RefactoringResult(false, "Invalid fully qualified name '${cmd.fqName}'.", errorMessage = "INVALID_IDENTIFIER")
        }
        val nodeKind = runCatching { NodeKind.valueOf(cmd.kind.uppercase()) }.getOrDefault(NodeKind.CLASS)
        if (nodeKind != NodeKind.GHOST_SPEC &&
            currentGraph.nodes.any { it.id == cmd.fqName && it.kind != NodeKind.GHOST_SPEC }
        ) {
            return RefactoringResult(false, "Node '${cmd.fqName}' already exists.", errorMessage = "ALREADY_EXISTS")
        }
        val simpleName = cmd.fqName.substringAfterLast('.')
        val pkg = cmd.fqName.substringBeforeLast('.', "").ifEmpty { null }

        if (nodeKind == NodeKind.GHOST_SPEC) {
            val ghostNode = ArcNode(
                id = cmd.fqName,
                label = simpleName,
                c4Level = C4Level.L3_COMPONENT,
                kind = NodeKind.GHOST_SPEC,
                parentId = pkg,
                stereotypes = cmd.stereotypes.ifEmpty { listOf("«Ghost Spec»") },
                members = listOf(MemberSignature("execute", "METHOD", "(): Result", "public")),
                diffStatus = DiffStatus.ADDED
            )
            var specRelPath = "architecture.arc.mmd"
            if (!previewOnly) {
                val dir = rootDir
                if (dir != null && dir.exists()) {
                    try {
                        val specFile = PolyglotRepoScanner.findPrimarySpecFile(dir) ?: resolveRepoFile(dir, "architecture.arc.mmd")
                        specRelPath = specFile.relativeToOrSelf(dir.canonicalFile).invariantSeparatorsPath
                        DiskSourceTransaction.execute { tx ->
                            val existingSpec = if (specFile.exists()) {
                                runCatching { dev.arcdrift.core.codec.ArcMermaidParser.parse(specFile.readText(Charsets.UTF_8)) }.getOrNull()
                            } else null
                            val baseSpec = existingSpec ?: ArcGraph(
                                projectName = currentGraph.projectName,
                                rules = currentGraph.rules
                            )
                            val mergedSpec = baseSpec.copy(
                                nodes = baseSpec.nodes.filterNot { it.id == cmd.fqName } + ghostNode
                            )
                            tx.writeText(specFile, dev.arcdrift.core.codec.ArcMermaidSerializer.serialize(mergedSpec))
                        }
                    } catch (e: Exception) {
                        return RefactoringResult(false, "Create GHOST_SPEC failed: ${e.message}", errorMessage = "REFACTORING_FAILED")
                    }
                }
                currentGraph = currentGraph.copy(
                    nodes = currentGraph.nodes.filterNot { it.id == cmd.fqName } + ghostNode
                )
            } else {
                val dir = rootDir
                if (dir != null && dir.exists()) {
                    val specFile = PolyglotRepoScanner.findPrimarySpecFile(dir) ?: resolveRepoFile(dir, "architecture.arc.mmd")
                    specRelPath = specFile.relativeToOrSelf(dir.canonicalFile).invariantSeparatorsPath
                }
            }
            return RefactoringResult(
                success = true,
                summary = "${if (previewOnly) "[Preview] " else ""}Created GHOST_SPEC '$simpleName' (${cmd.fqName})",
                touchedFiles = listOf(specRelPath)
            )
        }

        val dir = rootDir
        val useJava = if (dir != null && dir.exists()) {
            val sr = findExistingSourceRoot(dir.canonicalFile)
            DiskSourceRewriter.isJavaPreferredRoot(dir.canonicalFile, sr)
        } else {
            false
        }
        val ext = if (useJava) "java" else "kt"
        val defaultSrcDir = if (useJava) "src/main/java" else "src/main/kotlin"
        var filePath = "$defaultSrcDir/${cmd.fqName.replace('.', '/')}.$ext"

        if (!previewOnly) {
            if (dir != null && dir.exists()) {
                try {
                    val sourceRoot = findExistingSourceRoot(dir.canonicalFile)
                    val isJava = DiskSourceRewriter.isJavaPreferredRoot(dir.canonicalFile, sourceRoot)
                    val targetExt = if (isJava) "java" else "kt"
                    val pkgDir = if (pkg != null) resolveRepoFile(dir, File(sourceRoot, pkg.replace('.', '/')).path) else sourceRoot
                    val createdFile = resolveRepoFile(dir, File(pkgDir, "$simpleName.$targetExt").path)
                    val sanitizedAnnotations = cmd.stereotypes
                        .mapNotNull { DiskSourceRewriter.sanitizeAnnotationStereotype(it) }
                    val existingSource = if (createdFile.exists()) createdFile.readText(Charsets.UTF_8) else null
                    val renderedSource = DiskSourceRewriter.renderOrAppendTypeDeclaration(
                        existingSource = existingSource,
                        pkg = pkg,
                        simpleName = simpleName,
                        nodeKind = nodeKind,
                        sanitizedAnnotations = sanitizedAnnotations,
                        isJava = isJava
                    )
                    DiskSourceRewriter.verifyNoNewSyntaxErrors(existingSource, renderedSource, isJava, createdFile.name)

                    DiskSourceTransaction.execute { tx ->
                        tx.writeText(createdFile, renderedSource)

                        // If the primary .arc.mmd spec had a GHOST_SPEC placeholder for this fqName, remove it atomically
                        val specFile = PolyglotRepoScanner.findPrimarySpecFile(dir) ?: resolveRepoFile(dir, "architecture.arc.mmd")
                        if (specFile.exists()) {
                            runCatching {
                                val parsedSpec = dev.arcdrift.core.codec.ArcMermaidParser.parse(specFile.readText(Charsets.UTF_8))
                                if (parsedSpec.nodes.any { it.id == cmd.fqName && it.kind == NodeKind.GHOST_SPEC }) {
                                    val updatedSpec = parsedSpec.copy(
                                        nodes = parsedSpec.nodes.filterNot { it.id == cmd.fqName && it.kind == NodeKind.GHOST_SPEC }
                                    )
                                    tx.writeText(specFile, dev.arcdrift.core.codec.ArcMermaidSerializer.serialize(updatedSpec))
                                }
                            }
                        }
                    }
                    filePath = createdFile.relativeTo(dir.canonicalFile).invariantSeparatorsPath
                } catch (e: Exception) {
                    return RefactoringResult(false, "CreateNode failed: ${e.message}", errorMessage = "REFACTORING_FAILED")
                }
                rescanFromDiskIfAvailable()
            }
            if (currentGraph.nodes.none { it.id == cmd.fqName && it.kind != NodeKind.GHOST_SPEC }) {
                val newNode = ArcNode(
                    id = cmd.fqName,
                    label = simpleName,
                    c4Level = C4Level.L3_COMPONENT,
                    kind = nodeKind,
                    parentId = pkg,
                    stereotypes = cmd.stereotypes,
                    source = SourceLocation(filePath, 1, 15, if (filePath.endsWith(".java")) "java" else "kotlin"),
                    diffStatus = DiffStatus.ADDED
                )
                currentGraph = currentGraph.copy(nodes = currentGraph.nodes.filterNot { it.id == cmd.fqName } + newNode)
            }
        }

        return RefactoringResult(
            success = true,
            summary = "${if (previewOnly) "[Preview] " else ""}Created ${nodeKind.name} '$simpleName' (${cmd.fqName})",
            touchedFiles = listOf(filePath)
        )
    }

    private fun handleAddDependency(cmd: RefactoringCommand.AddDependency, previewOnly: Boolean): RefactoringResult {
        if (!DiskSourceRewriter.isValidFqName(cmd.targetTypeFqName)) {
            return RefactoringResult(false, "Invalid target type '${cmd.targetTypeFqName}'.", errorMessage = "INVALID_IDENTIFIER")
        }
        val src = currentGraph.nodes.find { it.id == cmd.sourceClassId }
            ?: return RefactoringResult(false, "Source node '${cmd.sourceClassId}' not found.", errorMessage = "NODE_NOT_FOUND")

        val normalizedRel = cmd.relationKind.uppercase()
        val edgeKind = when (normalizedRel) {
            "IMPLEMENTS" -> EdgeKind.IMPLEMENTS
            "EXTENDS" -> EdgeKind.EXTENDS
            else -> EdgeKind.DEPENDS_ON
        }

        val filePath = src.source?.filePath ?: "src/main/kotlin/${cmd.sourceClassId.replace('.', '/')}.kt"
        if (!previewOnly) {
            val dir = rootDir
            val srcRelPath = src.source?.filePath
            val resolvedSrcFile = if (dir != null && srcRelPath != null) resolveRepoFileOrNull(dir, srcRelPath) else null
            if (dir != null && dir.exists() && resolvedSrcFile != null && resolvedSrcFile.exists()) {
                try {
                    val srcFile = resolvedSrcFile
                    val isJava = srcFile.extension.lowercase() == "java"
                    val targetSimple = cmd.targetTypeFqName.substringAfterLast('.')
                    val targetPkg = cmd.targetTypeFqName.substringBeforeLast('.', "")
                    val srcPkg = src.parentId ?: cmd.sourceClassId.substringBeforeLast('.', "")
                    val original = srcFile.readText(Charsets.UTF_8)
                    var content = original
                    if (targetPkg.isNotEmpty() && targetPkg != srcPkg) {
                        content = DiskSourceRewriter.insertImport(content, cmd.targetTypeFqName, isJava)
                    }
                    content = when {
                        edgeKind == EdgeKind.EXTENDS && isJava ->
                            DiskSourceRewriter.addExtendsToJavaTypeDecl(content, src.label, targetSimple)
                        edgeKind == EdgeKind.EXTENDS ->
                            DiskSourceRewriter.addExtendsToKotlinTypeDecl(content, src.label, targetSimple)
                        edgeKind == EdgeKind.IMPLEMENTS ->
                            DiskSourceRewriter.addImplementsToTypeDecl(content, src.label, targetSimple, isJava)
                        else ->
                            DiskSourceRewriter.addConstructorDependencyToTypeDecl(content, src.label, targetSimple, isJava)
                    }
                    DiskSourceRewriter.verifyNoNewSyntaxErrors(original, content, isJava, srcFile.name)
                    DiskSourceTransaction.execute { tx ->
                        tx.writeText(srcFile, content)
                    }
                } catch (e: Exception) {
                    return RefactoringResult(false, "AddDependency failed: ${e.message}", errorMessage = "REFACTORING_FAILED")
                }
                rescanFromDiskIfAvailable()
            }
            if (currentGraph.edges.none { it.sourceId == cmd.sourceClassId && it.targetId == cmd.targetTypeFqName }) {
                val newEdge = ArcEdge(
                    id = "dep-${src.label}-${cmd.targetTypeFqName.substringAfterLast('.')}",
                    sourceId = cmd.sourceClassId,
                    targetId = cmd.targetTypeFqName,
                    kind = edgeKind,
                    label = cmd.relationKind.lowercase(),
                    diffStatus = DiffStatus.ADDED
                )
                val nextGraph = currentGraph.copy(edges = currentGraph.edges + newEdge)
                currentGraph = ArcRuleEngine.annotateViolations(nextGraph, ArcRuleEngine.evaluate(nextGraph))
            }
        }

        return RefactoringResult(
            success = true,
            summary = "${if (previewOnly) "[Preview] " else ""}Added ${cmd.relationKind} dependency from '${src.label}' to '${cmd.targetTypeFqName}'",
            touchedFiles = listOf(filePath)
        )
    }

    private fun resolveRepoFile(repoRoot: File, pathStr: String): File =
        DiskSourceRewriter.resolveRepoFile(repoRoot, pathStr)

    private fun resolveRepoFileOrNull(repoRoot: File, pathStr: String): File? =
        runCatching { DiskSourceRewriter.resolveRepoFile(repoRoot, pathStr) }.getOrNull()

    private fun findExistingSourceRoot(repoRoot: File): File =
        DiskSourceRewriter.findExistingSourceRoot(repoRoot)

    private fun collectImpactedFiles(nodeId: String): List<String> {
        val nodeMap = currentGraph.nodeMap()
        val relatedIds = linkedSetOf(nodeId)
        for (edge in currentGraph.edges) {
            if (edge.targetId == nodeId) {
                relatedIds.add(edge.sourceId)
            }
        }
        return relatedIds.mapNotNull { id ->
            val node = nodeMap[id]
            when {
                node?.kind == NodeKind.PACKAGE || node?.kind == NodeKind.MODULE || node?.kind == NodeKind.SYSTEM -> null
                node?.kind == NodeKind.GHOST_SPEC -> null
                node?.kind == NodeKind.TS_CLIENT || node?.kind == NodeKind.PYTHON_CONSUMER -> null
                node?.source?.filePath != null -> node.source.filePath
                node != null -> "src/main/kotlin/${id.replace('.', '/')}.kt"
                else -> null
            }
        }.distinct().sorted()
    }
}
