package dev.arcdrift.core.refactoring

import dev.arcdrift.core.ast.JavaAstSourceMutator
import dev.arcdrift.core.ast.JvmAstRewriters
import dev.arcdrift.core.ast.KotlinAstSourceMutator
import dev.arcdrift.core.model.ArcGraph
import dev.arcdrift.core.model.ArcNode
import dev.arcdrift.core.model.EdgeKind
import dev.arcdrift.core.model.MemberSignature
import dev.arcdrift.core.model.NodeKind
import java.io.File
import java.nio.file.Files

/**
 * Disk-level source rewriter facade that coordinates file-system transactions, path validation,
 * and source-root discovery while delegating all AST source mutations to [JvmAstRewriters]
 * ([KotlinAstSourceMutator] and [JavaAstSourceMutator]).
 */
internal object DiskSourceRewriter {

    private val SIMPLE_ID_REGEX = Regex("""^[A-Za-z_][A-Za-z0-9_]*$""")
    private val FQ_NAME_REGEX = Regex("""^[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*)*$""")
    private val PACKAGE_NAME_REGEX = Regex("""^([A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*)*)?$""")
    private val SAFE_ANNOTATION_REGEX = Regex("""^@?[A-Za-z_][A-Za-z0-9_.]*(\((?:[A-Za-z0-9_.,= \t:-]|"[^"\r\n\\]*")*\))?$""")

    private val RESERVED_JVM_KEYWORDS = setOf(
        // Java & Kotlin hard keywords
        "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const",
        "continue", "default", "do", "double", "else", "enum", "extends", "false", "final", "finally",
        "float", "for", "fun", "goto", "if", "implements", "import", "in", "instanceof", "int",
        "interface", "is", "long", "native", "new", "null", "object", "package", "private", "protected",
        "public", "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this",
        "throw", "throws", "transient", "true", "try", "typealias", "typeof", "val", "var", "void",
        "volatile", "when", "while", "_"
    )

    private val IGNORED_WALK_DIRS = setOf(
        ".git", "node_modules", "build", "target", ".gradle", ".gradle-user-home",
        ".idea", "dist", "out", ".arcdrift-registry", "test", "tests"
    )

    fun isValidSimpleIdentifier(name: String): Boolean =
        name.isNotBlank() && SIMPLE_ID_REGEX.matches(name) && name !in RESERVED_JVM_KEYWORDS

    fun isValidFqName(fqName: String): Boolean =
        fqName.isNotBlank() &&
            FQ_NAME_REGEX.matches(fqName) &&
            fqName.split('.').all { it !in RESERVED_JVM_KEYWORDS }

    fun isValidPackageName(pkg: String): Boolean =
        PACKAGE_NAME_REGEX.matches(pkg) &&
            (pkg.isEmpty() || pkg.split('.').all { it !in RESERVED_JVM_KEYWORDS })

    fun sanitizeAnnotationStereotype(raw: String): String? {
        val clean = raw.replace("«", "").replace("»", "").trim()
        if (clean.isEmpty()) return null
        if (clean.contains('\n') || clean.contains('\r')) return null
        if (!SAFE_ANNOTATION_REGEX.matches(clean)) return null
        return if (clean.startsWith("@")) clean else "@$clean"
    }

    fun countTopLevelDeclarations(source: String, isJava: Boolean): Int =
        JvmAstRewriters.forLanguage(isJava).countTopLevelDeclarations(source)

    fun rewritePackageDirective(source: String, newPackage: String, isJava: Boolean): String =
        JvmAstRewriters.forLanguage(isJava).rewritePackageDirective(source, newPackage)

    fun hasExplicitImportForSimpleName(source: String, simpleName: String, isJava: Boolean): Boolean =
        JvmAstRewriters.forLanguage(isJava).hasExplicitImportForSimpleName(source, simpleName)

    fun rewriteSymbolInSingleFile(
        source: String,
        oldFqName: String,
        newFqName: String,
        oldSimple: String,
        newSimple: String,
        isJava: Boolean
    ): String = JvmAstRewriters.forLanguage(isJava).rewriteSymbolReferences(
        source = source,
        oldFqName = oldFqName,
        newFqName = newFqName,
        oldSimple = oldSimple,
        newSimple = newSimple
    )

    fun verifyNoNewSyntaxErrors(originalSource: String?, mutatedSource: String, isJava: Boolean, fileName: String) {
        val rewriter = JvmAstRewriters.forLanguage(isJava)
        val hadErrorsBefore = originalSource != null && rewriter.hasSyntaxErrors(originalSource, fileName)
        if (!hadErrorsBefore && rewriter.hasSyntaxErrors(mutatedSource, fileName)) {
            error("AST rewrite produced syntax errors in '$fileName'")
        }
    }

    fun updateSymbolAcrossJvmFiles(
        rootDir: File,
        oldFqName: String,
        newFqName: String,
        oldSimple: String,
        newSimple: String,
        existingTx: DiskSourceTransaction? = null,
        excludeCanonicalPaths: Set<String> = emptySet()
    ): List<String> {
        val rootCanonical = runCatching { rootDir.canonicalFile }.getOrDefault(rootDir.absoluteFile)
        val rootNormPath = rootCanonical.toPath().normalize()
        val modifiedRelPaths = mutableListOf<String>()

        val applyBlock: (DiskSourceTransaction) -> Unit = { tx ->
            val visitedDirs = HashSet<String>()
            rootCanonical.walkTopDown()
                .onEnter { dir ->
                    dir.name !in setOf(".git", "node_modules", "build", "target", ".gradle", ".gradle-user-home", ".idea", "dist", "out", ".arcdrift-registry") &&
                        (dir == rootCanonical || !runCatching { Files.isSymbolicLink(dir.toPath()) }.getOrDefault(false)) &&
                        runCatching { dir.canonicalFile.toPath().normalize().startsWith(rootNormPath) && visitedDirs.add(dir.canonicalPath) }.getOrDefault(false)
                }
                .filter {
                    it.isFile &&
                        !runCatching { Files.isSymbolicLink(it.toPath()) }.getOrDefault(false) &&
                        (it.extension == "kt" || it.extension == "java")
                }
                .forEach { rawFile ->
                    val file = runCatching { resolveRepoFile(rootCanonical, rawFile.path) }.getOrNull() ?: return@forEach
                    if (file.canonicalPath in excludeCanonicalPaths) return@forEach
                    val content = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return@forEach
                    if (!content.contains(oldSimple) && !content.contains(oldFqName)) return@forEach

                    val isJava = file.extension.equals("java", ignoreCase = true)
                    val rewriter = JvmAstRewriters.forLanguage(isJava)
                    val modified = rewriter.rewriteSymbolReferences(
                        source = content,
                        oldFqName = oldFqName,
                        newFqName = newFqName,
                        oldSimple = oldSimple,
                        newSimple = newSimple
                    )

                    if (modified != content) {
                        verifyNoNewSyntaxErrors(content, modified, isJava, file.name)
                        tx.writeText(file, modified)
                        modifiedRelPaths.add(file.relativeToOrSelf(rootCanonical).invariantSeparatorsPath)
                    }
                }
        }
        if (existingTx != null) {
            applyBlock(existingTx)
        } else {
            DiskSourceTransaction.execute(applyBlock)
        }
        return modifiedRelPaths
    }

    fun writeExtractedInterfaceAndRewireCallers(
        dir: File,
        srcFile: File,
        sourceNode: ArcNode,
        cmd: RefactoringCommand.ExtractInterface,
        interfaceId: String,
        extractedMembers: List<MemberSignature>,
        currentGraph: ArcGraph,
        impactedFiles: MutableList<String>
    ): String {
        val isJava = srcFile.extension.lowercase() == "java"
        val ext = if (isJava) "java" else "kt"
        val rewriter = JvmAstRewriters.forLanguage(isJava)
        val relNormalized = srcFile.relativeToOrSelf(dir.canonicalFile).invariantSeparatorsPath
        val sourceRoot = inferSourceRoot(dir.canonicalFile, relNormalized)
        val targetPkgDir = resolveRepoFile(dir, File(sourceRoot, cmd.targetPackage.replace('.', '/')).path)
        val interfaceFile = resolveRepoFile(dir, File(targetPkgDir, "${cmd.interfaceSimpleName}.$ext").path)
        check(!interfaceFile.exists()) {
            "Interface file already exists: ${interfaceFile.relativeToOrSelf(dir.canonicalFile).invariantSeparatorsPath}"
        }

        val srcOriginalText = srcFile.readText(Charsets.UTF_8)
        val srcPkg = rewriter.readPackageName(srcOriginalText).ifEmpty {
            sourceNode.parentId ?: cmd.sourceClassId.substringBeforeLast('.', "")
        }

        val interfaceSource = renderExtractedInterfaceSource(
            srcOriginalText = srcOriginalText,
            srcPkg = srcPkg,
            targetPackage = cmd.targetPackage,
            interfaceSimpleName = cmd.interfaceSimpleName,
            extractedMembers = extractedMembers,
            isJava = isJava,
            graphNodes = currentGraph.nodes
        )
        verifyNoNewSyntaxErrors(null, interfaceSource, isJava, interfaceFile.name)

        var updatedSrc = srcOriginalText
        if (srcPkg != cmd.targetPackage && cmd.targetPackage.isNotEmpty()) {
            updatedSrc = rewriter.insertImport(updatedSrc, interfaceId)
        }
        val extractedNames = extractedMembers.map { it.name }.toSet()
        updatedSrc = rewriter.addImplementsToTypeDecl(
            source = updatedSrc,
            className = sourceNode.label,
            interfaceSimpleName = cmd.interfaceSimpleName,
            overrideMethodNames = extractedNames
        )
        verifyNoNewSyntaxErrors(srcOriginalText, updatedSrc, isJava, srcFile.name)

        val callerNodes = currentGraph.edges
            .filter {
                it.targetId == cmd.sourceClassId &&
                    it.sourceId != cmd.sourceClassId &&
                    it.kind in setOf(EdgeKind.DEPENDS_ON, EdgeKind.CALLS, EdgeKind.IMPLEMENTS, EdgeKind.EXTENDS)
            }
            .mapNotNull { e -> currentGraph.nodes.find { it.id == e.sourceId } }
            .filter { it.kind != NodeKind.TS_CLIENT && it.kind != NodeKind.PYTHON_CONSUMER }
            .distinctBy { it.id }

        val callerUpdates = mutableListOf<Pair<File, String>>()
        for (caller in callerNodes) {
            val callerRel = caller.source?.filePath ?: continue
            val callerFile = runCatching { resolveRepoFile(dir, callerRel) }.getOrNull() ?: continue
            if (!callerFile.exists() || callerFile.canonicalPath == srcFile.canonicalPath) continue
            val callerIsJava = callerFile.extension.equals("java", ignoreCase = true)
            val callerRewriter = JvmAstRewriters.forLanguage(callerIsJava)
            val callerText = callerFile.readText(Charsets.UTF_8)
            val modifiedCaller = callerRewriter.rewireCallerToExtractedInterface(
                source = callerText,
                sourceClassFqName = cmd.sourceClassId,
                sourceSimpleName = sourceNode.label,
                interfaceFqName = interfaceId,
                interfaceSimpleName = cmd.interfaceSimpleName,
                targetPackage = cmd.targetPackage
            )
            if (modifiedCaller != callerText) {
                verifyNoNewSyntaxErrors(callerText, modifiedCaller, callerIsJava, callerFile.name)
                callerUpdates.add(callerFile to modifiedCaller)
                impactedFiles.add(callerFile.relativeToOrSelf(dir.canonicalFile).invariantSeparatorsPath)
            }
        }

        DiskSourceTransaction.execute { tx ->
            tx.writeText(interfaceFile, interfaceSource)
            tx.writeText(srcFile, updatedSrc)
            for ((callerFile, modifiedCaller) in callerUpdates) {
                tx.writeText(callerFile, modifiedCaller)
            }
        }

        val createdRelPath = interfaceFile.relativeToOrSelf(dir.canonicalFile).invariantSeparatorsPath
        impactedFiles.add(createdRelPath)
        return createdRelPath
    }

    fun renderExtractedInterfaceSource(
        srcOriginalText: String,
        srcPkg: String,
        targetPackage: String,
        interfaceSimpleName: String,
        extractedMembers: List<MemberSignature>,
        isJava: Boolean,
        graphNodes: List<ArcNode> = emptyList()
    ): String = JvmAstRewriters.forLanguage(isJava).renderExtractedInterfaceSource(
        srcOriginalText = srcOriginalText,
        srcPkg = srcPkg,
        targetPackage = targetPackage,
        interfaceSimpleName = interfaceSimpleName,
        extractedMembers = extractedMembers,
        graphNodes = graphNodes
    )

    fun renderOrAppendTypeDeclaration(
        existingSource: String?,
        pkg: String?,
        simpleName: String,
        nodeKind: NodeKind,
        sanitizedAnnotations: List<String>,
        isJava: Boolean = false
    ): String = JvmAstRewriters.forLanguage(isJava).renderOrAppendTypeDeclaration(
        existingSource = existingSource,
        pkg = pkg,
        simpleName = simpleName,
        nodeKind = nodeKind,
        sanitizedAnnotations = sanitizedAnnotations
    )

    fun extractNeededImports(
        srcText: String,
        methodsText: String,
        srcPkg: String,
        targetPkg: String,
        isJava: Boolean,
        graphNodes: List<ArcNode>
    ): List<String> = JvmAstRewriters.forLanguage(isJava).extractNeededImports(
        srcText = srcText,
        methodsText = methodsText,
        srcPkg = srcPkg,
        targetPkg = targetPkg,
        graphNodes = graphNodes
    )

    fun insertImport(content: String, fqImport: String, isJava: Boolean): String =
        JvmAstRewriters.forLanguage(isJava).insertImport(content, fqImport)

    fun containsTypeReference(content: String, simpleName: String, isJava: Boolean): Boolean =
        JvmAstRewriters.forLanguage(isJava).containsTypeReference(content, simpleName)

    fun addImplementsToTypeDecl(
        content: String,
        className: String,
        interfaceSimpleName: String,
        isJava: Boolean,
        overrideMethodNames: Set<String> = emptySet()
    ): String = JvmAstRewriters.forLanguage(isJava).addImplementsToTypeDecl(
        source = content,
        className = className,
        interfaceSimpleName = interfaceSimpleName,
        overrideMethodNames = overrideMethodNames
    )

    fun addExtendsToJavaTypeDecl(
        content: String,
        className: String,
        superSimpleName: String
    ): String = JavaAstSourceMutator.addExtendsToTypeDecl(content, className, superSimpleName)

    fun addExtendsToKotlinTypeDecl(
        content: String,
        className: String,
        superSimpleName: String
    ): String = KotlinAstSourceMutator.addExtendsToTypeDecl(content, className, superSimpleName)

    fun addConstructorDependencyToTypeDecl(
        content: String,
        className: String,
        targetSimpleName: String,
        isJava: Boolean
    ): String = JvmAstRewriters.forLanguage(isJava).addConstructorDependencyToTypeDecl(
        source = content,
        className = className,
        targetSimpleName = targetSimpleName
    )

    fun resolveRepoFile(repoRoot: File, pathStr: String): File {
        val rootCanonical = runCatching { repoRoot.canonicalFile }.getOrDefault(repoRoot.absoluteFile)
        val rootPath = rootCanonical.toPath().normalize()
        val candidate = File(pathStr)
        val resolved = if (candidate.isAbsolute) candidate else File(rootCanonical, pathStr)
        val resolvedCanonical = runCatching { resolved.canonicalFile }.getOrDefault(resolved.absoluteFile)
        require(resolvedCanonical.toPath().normalize().startsWith(rootPath)) {
            "Resolved file path escapes repository root: $pathStr"
        }
        return resolvedCanonical
    }

    private val KMP_MAIN_MARKER_REGEX = Regex("""^(?:.*/)?src/[a-zA-Z0-9_]*[Mm]ain/(?:kotlin|java)/""")

    fun inferSourceRoot(repoRoot: File, relFilePath: String): File {
        val norm = relFilePath.replace('\\', '/')
        val stdMarkers = listOf("src/main/kotlin/", "src/main/java/")
        for (marker in stdMarkers) {
            val idx = norm.indexOf(marker)
            if (idx >= 0) {
                return File(repoRoot, norm.substring(0, idx + marker.length).trimEnd('/'))
            }
        }
        val kmpMatch = KMP_MAIN_MARKER_REGEX.find(norm)
        if (kmpMatch != null) {
            return File(repoRoot, kmpMatch.value.trimEnd('/'))
        }
        val srcIdx = norm.indexOf("src/")
        if (srcIdx >= 0 && !norm.substring(srcIdx).startsWith("src/test/")) {
            return File(repoRoot, norm.substring(0, srcIdx + "src/".length).trimEnd('/'))
        }
        return File(repoRoot, "src/main/kotlin")
    }

    fun findExistingSourceRoot(repoRoot: File): File {
        val stdKt = File(repoRoot, "src/main/kotlin")
        if (stdKt.isDirectory) return stdKt
        val stdJava = File(repoRoot, "src/main/java")
        if (stdJava.isDirectory) return stdJava

        val visitedDirs = HashSet<String>()
        val candidates = repoRoot.walkTopDown()
            .onEnter { dir ->
                dir.name !in IGNORED_WALK_DIRS &&
                    (dir == repoRoot || !runCatching { Files.isSymbolicLink(dir.toPath()) }.getOrDefault(false)) &&
                    runCatching { visitedDirs.add(dir.canonicalPath) }.getOrDefault(false)
            }
            .filter {
                it.isFile &&
                    !runCatching { Files.isSymbolicLink(it.toPath()) }.getOrDefault(false) &&
                    (it.extension == "kt" || it.extension == "java") &&
                    !it.relativeTo(repoRoot).invariantSeparatorsPath.contains("/src/test/") &&
                    !it.relativeTo(repoRoot).invariantSeparatorsPath.startsWith("src/test/")
            }
            .sortedWith(
                compareBy<File>(
                    { f ->
                        val rel = f.relativeTo(repoRoot).invariantSeparatorsPath
                        when {
                            rel.contains("src/main/kotlin/") -> 0
                            rel.contains("src/main/java/") -> 1
                            KMP_MAIN_MARKER_REGEX.containsMatchIn(rel) -> 2
                            else -> 3
                        }
                    },
                    { it.relativeTo(repoRoot).invariantSeparatorsPath }
                )
            )
            .toList()

        val firstMainSource = candidates.firstOrNull()
        if (firstMainSource != null) {
            return inferSourceRoot(repoRoot, firstMainSource.relativeTo(repoRoot).invariantSeparatorsPath)
        }
        return File(repoRoot, "src/main/kotlin")
    }

    fun isJavaPreferredRoot(repoRoot: File, sourceRoot: File): Boolean {
        val normRoot = sourceRoot.invariantSeparatorsPath.trimEnd('/')
        if (normRoot.endsWith("/java")) return true
        if (normRoot.endsWith("/kotlin")) return false
        val visited = HashSet<String>()
        var javaCount = 0
        var ktCount = 0
        repoRoot.walkTopDown()
            .onEnter { dir ->
                dir.name !in IGNORED_WALK_DIRS &&
                    (dir == repoRoot || !runCatching { Files.isSymbolicLink(dir.toPath()) }.getOrDefault(false)) &&
                    runCatching { visited.add(dir.canonicalPath) }.getOrDefault(false)
            }
            .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .forEach { f ->
                if (f.extension == "java") javaCount++ else ktCount++
            }
        return javaCount > 0 && ktCount == 0
    }
}
