package dev.arcdrift.intellij.psi

import com.intellij.ide.highlighter.JavaFileType
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMember
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTypesUtil
import dev.arcdrift.core.ast.AstTypeUnwrapper
import dev.arcdrift.core.ast.JvmParsedFileAst
import dev.arcdrift.core.contracts.ContractFederationManifest
import dev.arcdrift.core.contracts.PolyglotContractBridge
import dev.arcdrift.core.model.*
import dev.arcdrift.core.rules.ArcRuleEngine
import dev.arcdrift.core.scanner.OpenApiAndTypeSpecExtractor
import dev.arcdrift.core.scanner.PolyglotRepoScanner
import dev.arcdrift.core.spi.ArcDriftSpiLoader
import org.jetbrains.kotlin.idea.KotlinFileType
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UClass
import org.jetbrains.uast.UFile
import org.jetbrains.uast.UQualifiedReferenceExpression
import org.jetbrains.uast.USimpleNameReferenceExpression
import org.jetbrains.uast.toUElement
import org.jetbrains.uast.visitor.AbstractUastVisitor
import java.io.File

/**
 * Extracts a semantic [ArcGraph] from an IntelliJ [Project] using public Apache 2.0
 * PSI and UAST APIs (`org.jetbrains.uast.*`, `com.intellij.psi.*`).
 */
object UastGraphExtractor {

    fun extractProjectGraph(project: Project, existingRules: List<BoundaryRule> = emptyList()): ArcGraph {
        val rootDir = project.basePath?.let { File(it) }?.takeIf { it.exists() }
        val openDocumentOverrides = ReadAction.compute<Map<String, String>, RuntimeException> {
            val fileDocManager = FileDocumentManager.getInstance()
            val overrides = linkedMapOf<String, String>()
            if (rootDir != null) {
                for (doc in fileDocManager.unsavedDocuments) {
                    val vf = fileDocManager.getFile(doc) ?: continue
                    overrides[vf.path] = doc.text
                    val ioFile = File(vf.path)
                    if (ioFile.isAbsolute && runCatching { ioFile.canonicalFile.toPath().normalize().startsWith(rootDir.canonicalFile.toPath().normalize()) }.getOrDefault(false)) {
                        overrides[ioFile.relativeTo(rootDir).invariantSeparatorsPath] = doc.text
                    }
                }
            }
            overrides
        }

        val ignoredDirs = setOf(
            ".git", ".gradle", ".gradle-user-home", ".idea", "build", "dist", "node_modules", "target", "out", "__pycache__",
            "tests", "test", "e2e", "cypress", "playwright", "mocks", "__mocks__"
        )
        val openApiResult = rootDir?.let { root ->
            runCatching { OpenApiAndTypeSpecExtractor.extract(root, ignoredDirs) }.getOrNull()
        }

        // Run non-JVM disk, OpenAPI/TypeSpec, spec coordinates, and Tree-Sitter scan outside the PSI ReadAction so EDT write actions are not blocked by disk I/O
        val polyglotScannedGraph = rootDir?.let { root ->
            runCatching {
                PolyglotRepoScanner.scanRepository(
                    rootDir = root,
                    includeJvmSources = false,
                    fileContentOverrides = openDocumentOverrides,
                    supplementalJvmMethodNames = openApiResult?.operations?.keys.orEmpty()
                )
            }.getOrNull()
        }
        val upstreamManifest = rootDir?.let { root ->
            runCatching {
                val manifests = ArcDriftSpiLoader.loadAdditionalUpstreamManifests(project.name, root)
                if (manifests.isEmpty()) null else PolyglotContractBridge.mergeManifests(manifests)
            }.getOrNull()
        }

        val specCoordsById = linkedMapOf<String, Pair<Double, Double>>()
        if (rootDir != null) {
            val canonicalNames = setOf("architecture.arc.mmd", "arcdrift.arc.mmd", "spec.arc.mmd")
            val specFile = rootDir.listFiles { f -> f.isFile && !f.name.startsWith(".") && f.name.endsWith(".arc.mmd") }
                ?.sortedWith(
                    compareBy<File>(
                        { f ->
                            val lower = f.name.lowercase()
                            when {
                                lower in canonicalNames -> 0
                                lower.contains("baseline") || lower.contains("snapshot") || lower.contains("diff") -> 2
                                else -> 1
                            }
                        },
                        { -it.length() }
                    )
                )
                ?.firstOrNull()
            if (specFile != null) {
                val specText = openDocumentOverrides[specFile.name]
                    ?: openDocumentOverrides[specFile.invariantSeparatorsPath]
                    ?: runCatching { specFile.readText(Charsets.UTF_8) }.getOrNull()
                if (specText != null) {
                    val specGraph = runCatching { dev.arcdrift.core.codec.ArcMermaidParser.parse(specText) }.getOrNull()
                    specGraph?.nodes?.forEach { n ->
                        val lx = n.layoutX
                        val ly = n.layoutY
                        if (lx != null && ly != null) {
                            specCoordsById[n.id] = lx to ly
                        }
                    }
                }
            }
        }

        val diskFiles = if (rootDir != null) {
            rootDir.walkTopDown()
                .onEnter { dir ->
                    dir.name !in setOf(".git", ".gradle", ".idea", "build", "dist", "node_modules", "target", "out") &&
                        !(dir.name == "test" && dir.parentFile?.name == "src")
                }
                .filter {
                    it.isFile &&
                        (it.extension == "kt" || it.extension == "java") &&
                        !it.invariantSeparatorsPath.contains("/src/test/") &&
                        !it.name.endsWith("ArchitectureTest.kt")
                }
                .toList()
        } else {
            emptyList()
        }

        val rootPrefix = rootDir?.invariantSeparatorsPath?.trimEnd('/')

        return ReadAction.compute<ArcGraph, RuntimeException> {
            val isDumb = DumbService.isDumb(project)
            val psiManager = PsiManager.getInstance(project)
            val psiFileFactory = PsiFileFactory.getInstance(project)

            val indexedVirtualFiles = if (!isDumb) {
                val scope = GlobalSearchScope.projectScope(project)
                val javaFiles = FileTypeIndex.getFiles(JavaFileType.INSTANCE, scope)
                val kotlinFiles = FileTypeIndex.getFiles(KotlinFileType.INSTANCE, scope)
                (javaFiles + kotlinFiles).distinct()
            } else {
                emptyList()
            }

            val fallbackPathByUFile = java.util.IdentityHashMap<UFile, String>()
            val ktParsedFiles = mutableListOf<Pair<JvmParsedFileAst, String?>>()
            val seenPaths = mutableSetOf<String>()
            val uFiles = mutableListOf<UFile>()

            val fileDocManager = FileDocumentManager.getInstance()
            val psiDocManager = PsiDocumentManager.getInstance(project)

            fun toRelOrOriginalPath(rawPath: String): String {
                val norm = rawPath.replace('\\', '/')
                return if (rootPrefix != null && norm.startsWith("$rootPrefix/")) {
                    norm.removePrefix("$rootPrefix/")
                } else {
                    norm
                }
            }

            for (vf in indexedVirtualFiles) {
                com.intellij.openapi.progress.ProgressManager.checkCanceled()
                if (vf.path.contains("/src/test/") || vf.path.endsWith("ArchitectureTest.kt")) continue
                val cachedDoc = fileDocManager.getCachedDocument(vf)
                val fileType = if (vf.name.endsWith(".kt", ignoreCase = true)) KotlinFileType.INSTANCE else JavaFileType.INSTANCE
                val isDirtyBuffer = cachedDoc != null && (psiDocManager.isUncommited(cachedDoc) || fileDocManager.isDocumentUnsaved(cachedDoc))
                val psiFile = if (isDirtyBuffer) {
                    runPsiSafely { psiFileFactory.createFileFromText(vf.name, fileType, cachedDoc.text) }.getOrNull()
                        ?: psiManager.findFile(vf)
                } else {
                    psiManager.findFile(vf)
                } ?: continue

                seenPaths.add(vf.path.replace('\\', '/'))
                val displayPath = toRelOrOriginalPath(vf.path)
                var parsedAsKt = false
                if (psiFile is KtFile) {
                    runPsiSafely { KtPsiFileExtractor.parseKtFile(psiFile) }.getOrNull()?.let { ktAst ->
                        ktParsedFiles.add(ktAst to displayPath)
                        parsedAsKt = true
                    }
                }
                if (!parsedAsKt) {
                    val uFile = runPsiSafely { psiFile.toUElement(UFile::class.java) }.getOrNull()
                    if (uFile != null) {
                        fallbackPathByUFile[uFile] = displayPath
                        uFiles.add(uFile)
                    }
                }
            }

            // Pick up any newly created or unindexed disk source files not yet present in FileTypeIndex
            if (rootDir != null && diskFiles.isNotEmpty()) {
                for (ioFile in diskFiles) {
                    com.intellij.openapi.progress.ProgressManager.checkCanceled()
                    val absPath = ioFile.invariantSeparatorsPath
                    if (absPath in seenPaths) continue
                    val vf = VfsUtil.findFileByIoFile(ioFile, false)
                    val cachedDoc = vf?.let { fileDocManager.getCachedDocument(it) }
                    val fileType = if (ioFile.extension.equals("kt", ignoreCase = true)) {
                        KotlinFileType.INSTANCE
                    } else {
                        JavaFileType.INSTANCE
                    }
                    val psiFile = when {
                        cachedDoc != null -> runPsiSafely {
                            psiFileFactory.createFileFromText(ioFile.name, fileType, cachedDoc.text)
                        }.getOrNull()
                        else -> vf?.let { psiManager.findFile(it) }
                    } ?: runPsiSafely {
                        psiFileFactory.createFileFromText(ioFile.name, fileType, ioFile.readText(Charsets.UTF_8))
                    }.getOrNull() ?: continue

                    seenPaths.add(absPath)
                    val relPath = ioFile.relativeTo(rootDir).invariantSeparatorsPath
                    var parsedAsKt = false
                    if (psiFile is KtFile) {
                        runPsiSafely { KtPsiFileExtractor.parseKtFile(psiFile) }.getOrNull()?.let { ktAst ->
                            ktParsedFiles.add(ktAst to relPath)
                            parsedAsKt = true
                        }
                    }
                    if (!parsedAsKt) {
                        val uFile = runPsiSafely { psiFile.toUElement(UFile::class.java) }.getOrNull()
                        if (uFile != null) {
                            fallbackPathByUFile[uFile] = relPath
                            uFiles.add(uFile)
                        }
                    }
                }
            }

            val mergedRules = (existingRules + polyglotScannedGraph?.rules.orEmpty()).distinctBy { it.id }

            extractFromUFiles(
                projectName = project.name,
                uFiles = uFiles,
                existingRules = mergedRules,
                supplementalNodes = polyglotScannedGraph?.nodes.orEmpty(),
                supplementalEdges = polyglotScannedGraph?.edges.orEmpty(),
                fallbackPathByUFile = fallbackPathByUFile,
                ktParsedFiles = ktParsedFiles,
                specCoordsById = specCoordsById,
                openApiOperations = openApiResult?.operations.orEmpty(),
                openApiSchemas = openApiResult?.schemas.orEmpty(),
                upstreamManifest = upstreamManifest
            )
        }
    }

    fun extractFromPsiFiles(
        projectName: String,
        psiFiles: List<PsiFile>,
        existingRules: List<BoundaryRule> = emptyList()
    ): ArcGraph {
        return ReadAction.compute<ArcGraph, RuntimeException> {
            val ktParsedFiles = psiFiles.filterIsInstance<KtFile>().mapNotNull { ktFile ->
                runPsiSafely { KtPsiFileExtractor.parseKtFile(ktFile) to ktFile.virtualFile?.path }.getOrNull()
            }
            val uFiles = psiFiles.mapNotNull { runPsiSafely { it.toUElement(UFile::class.java) }.getOrNull() }
            extractFromUFiles(projectName, uFiles, existingRules, ktParsedFiles = ktParsedFiles)
        }
    }

    private fun extractFromUFiles(
        projectName: String,
        uFiles: List<UFile>,
        existingRules: List<BoundaryRule>,
        supplementalNodes: List<ArcNode> = emptyList(),
        supplementalEdges: List<ArcEdge> = emptyList(),
        fallbackPathByUFile: Map<UFile, String> = emptyMap(),
        ktParsedFiles: List<Pair<JvmParsedFileAst, String?>> = emptyList(),
        specCoordsById: Map<String, Pair<Double, Double>> = emptyMap(),
        openApiOperations: Map<String, Pair<String, String>> = emptyMap(),
        openApiSchemas: Map<String, Map<String, String>> = emptyMap(),
        upstreamManifest: ContractFederationManifest? = null
    ): ArcGraph {
        val nodesById = linkedMapOf<String, ArcNode>()
        val rawDependencies = mutableListOf<Triple<String, String, String>>() // srcId, targetFqName, edgeKind

        // First pass: collect DTO/class field maps so controller endpoints can attach dtoFields
        val dtoFieldsBySimpleName = linkedMapOf<String, Map<String, String>>().apply {
            putAll(openApiSchemas)
        }
        for ((ktFileAst, _) in ktParsedFiles) {
            for (cls in ktFileAst.classes) {
                val isComponentBean = cls.kind in setOf(NodeKind.REST_CONTROLLER, NodeKind.SERVICE_BEAN, NodeKind.REPOSITORY)
                if (cls.fields.isNotEmpty() && (!isComponentBean || cls.simpleName !in dtoFieldsBySimpleName)) {
                    dtoFieldsBySimpleName[cls.simpleName] = cls.fields
                }
            }
        }
        for (uFile in uFiles) {
            val uClasses = collectAllUClasses(runPsiSafely { uFile.classes }.getOrDefault(emptyList()))
            for (uClass in uClasses) {
                val simpleName = uClass.name ?: continue
                if (simpleName in dtoFieldsBySimpleName) continue
                val fields = linkedMapOf<String, String>()
                for (f in uClass.fields) {
                    if (f.isStatic) continue
                    val wireName = SpringHiltSemanticEnricher.resolveUastSerializedFieldName(f.uAnnotations, f.name)
                    fields[wireName] = AstTypeUnwrapper.unwrapFieldWireTypeString(f.type.presentableText)
                }
                for (ctor in uClass.methods.filter { it.isConstructor }) {
                    for (p in ctor.uastParameters) {
                        val wireName = SpringHiltSemanticEnricher.resolveUastSerializedFieldName(p.uAnnotations, p.name)
                        fields.putIfAbsent(wireName, AstTypeUnwrapper.unwrapFieldWireTypeString(p.type.presentableText))
                    }
                }
                if (fields.isNotEmpty()) {
                    dtoFieldsBySimpleName[simpleName] = fields
                }
            }
        }

        // Propagate superclass DTO fields across Kotlin & Java classes
        for (pass in 0 until 4) {
            var changed = false
            for ((ktFileAst, _) in ktParsedFiles) {
                for (cls in ktFileAst.classes) {
                    if (cls.superTypeSimpleNames.isNotEmpty()) {
                        val inherited = linkedMapOf<String, String>()
                        for (superName in cls.superTypeSimpleNames) {
                            dtoFieldsBySimpleName[superName]?.let { inherited.putAll(it) }
                        }
                        if (inherited.isNotEmpty()) {
                            val existing = dtoFieldsBySimpleName[cls.simpleName].orEmpty()
                            val merged = linkedMapOf<String, String>().apply {
                                putAll(inherited)
                                putAll(existing)
                                putAll(cls.fields)
                            }
                            if (merged != existing) {
                                dtoFieldsBySimpleName[cls.simpleName] = merged
                                changed = true
                            }
                        }
                    }
                }
            }
            for (uFile in uFiles) {
                val uClasses = runPsiSafely { uFile.classes }.getOrNull().orEmpty()
                for (uClass in uClasses) {
                    val simpleName = uClass.name ?: continue
                    val superNames = uClass.uastSuperTypes.mapNotNull { superRef ->
                        superRef.type.canonicalText.substringBefore('<').substringAfterLast('.').takeIf { it.isNotEmpty() && it != "Object" }
                    }
                    if (superNames.isNotEmpty()) {
                        val inherited = linkedMapOf<String, String>()
                        for (superName in superNames) {
                            dtoFieldsBySimpleName[superName]?.let { inherited.putAll(it) }
                        }
                        if (inherited.isNotEmpty()) {
                            val existing = dtoFieldsBySimpleName[simpleName].orEmpty()
                            val merged = linkedMapOf<String, String>().apply {
                                putAll(inherited)
                                putAll(existing)
                            }
                            if (merged != existing) {
                                dtoFieldsBySimpleName[simpleName] = merged
                                changed = true
                            }
                        }
                    }
                }
            }
            if (!changed) break
        }

        // Second pass: populate Kotlin PSI nodes & dependencies (works even without K2 Light Classes / Gradle module)
        val referencedSimplesBySrcId = linkedMapOf<String, Set<String>>()
        val explicitImportsBySrcId = linkedMapOf<String, Map<String, String>>()
        val wildcardImportsBySrcId = linkedMapOf<String, List<String>>()
        for ((ktFileAst, relPath) in ktParsedFiles) {
            val pkgName = ktFileAst.pkg
            val isSingleClassFile = ktFileAst.classes.size == 1
            val fileExplicitMap = linkedMapOf<String, String>().apply {
                for (imp in ktFileAst.explicitImports) {
                    put(imp.substringAfterLast('.'), imp)
                }
                for ((alias, target) in ktFileAst.importAliases) {
                    put(alias, target)
                }
            }
            if (pkgName.isNotEmpty() && ktFileAst.classes.isNotEmpty() && pkgName !in nodesById) {
                nodesById[pkgName] = ArcNode(
                    id = pkgName,
                    label = pkgName.substringAfterLast('.'),
                    c4Level = C4Level.L2_CONTAINER,
                    kind = NodeKind.PACKAGE
                )
            }
            for (cls in ktFileAst.classes) {
                val clsRefs = cls.referencedSimpleNames + cls.superTypeSimpleNames
                referencedSimplesBySrcId[cls.fqId] = clsRefs
                explicitImportsBySrcId[cls.fqId] = fileExplicitMap
                wildcardImportsBySrcId[cls.fqId] = ktFileAst.wildcardImports
                val isControllerOrApiImpl = cls.kind == NodeKind.REST_CONTROLLER ||
                    cls.superTypeSimpleNames.any { it.endsWith("Api") || it.endsWith("Controller") }
                val contracts = AstTypeUnwrapper.buildContractsForClass(
                    annotations = cls.annotations,
                    methods = cls.methods,
                    inlineRoutes = cls.inlineRoutes,
                    dtoDefinitions = dtoFieldsBySimpleName,
                    openApiOperations = openApiOperations,
                    isControllerOrApiImpl = isControllerOrApiImpl
                )
                val sourceLoc = relPath?.let { pathStr ->
                    SourceLocation(
                        filePath = pathStr,
                        startLine = cls.startLine,
                        endLine = cls.endLine,
                        language = "kotlin"
                    )
                }
                nodesById[cls.fqId] = ArcNode(
                    id = cls.fqId,
                    label = cls.simpleName,
                    c4Level = C4Level.L3_COMPONENT,
                    kind = cls.kind,
                    parentId = pkgName.ifEmpty { null },
                    stereotypes = cls.stereotypes,
                    members = cls.members,
                    contracts = contracts,
                    source = sourceLoc
                )
                for (superSimple in cls.superTypeSimpleNames) {
                    rawDependencies.add(Triple(cls.fqId, superSimple, EdgeKind.IMPLEMENTS))
                }
                for (imp in ktFileAst.explicitImports) {
                    val impSimple = imp.substringAfterLast('.')
                    if (impSimple in cls.superTypeSimpleNames) continue
                    if (isSingleClassFile || impSimple in clsRefs) {
                        rawDependencies.add(Triple(cls.fqId, imp, EdgeKind.DEPENDS_ON))
                    }
                }
                for (wildcard in ktFileAst.wildcardImports) {
                    rawDependencies.add(Triple(cls.fqId, "$wildcard.*", EdgeKind.DEPENDS_ON))
                }
                for ((alias, aliasTarget) in ktFileAst.importAliases) {
                    if (isSingleClassFile || alias in clsRefs) {
                        rawDependencies.add(Triple(cls.fqId, aliasTarget, EdgeKind.DEPENDS_ON))
                    }
                }
                for (refSimple in cls.referencedSimpleNames) {
                    if (refSimple in cls.superTypeSimpleNames) continue
                    rawDependencies.add(Triple(cls.fqId, refSimple, EdgeKind.DEPENDS_ON))
                }
                for (refFq in cls.referencedQualifiedNames) {
                    rawDependencies.add(Triple(cls.fqId, refFq, EdgeKind.DEPENDS_ON))
                }
            }
        }

        for (uFile in uFiles) {
            val pkgName = runPsiSafely { uFile.packageName }.getOrDefault("")
            val uClasses = collectAllUClasses(runPsiSafely { uFile.classes }.getOrDefault(emptyList()))
            if (pkgName.isNotEmpty() && uClasses.isNotEmpty() && pkgName !in nodesById) {
                nodesById[pkgName] = ArcNode(
                    id = pkgName,
                    label = pkgName.substringAfterLast('.'),
                    c4Level = C4Level.L2_CONTAINER,
                    kind = NodeKind.PACKAGE
                )
            }

            val uExplicitMap = linkedMapOf<String, String>()
            val uWildcards = mutableListOf<String>()
            val importedFqNames = runPsiSafely { uFile.imports }.getOrDefault(emptyList()).flatMap { uImp ->
                val ref = uImp.importReference?.asRenderString()?.removeSuffix(".*") ?: return@flatMap emptyList()
                if (uImp.isOnDemand) {
                    uWildcards.add(ref)
                    listOf(ref, "$ref.*")
                } else {
                    uExplicitMap[ref.substringAfterLast('.')] = ref
                    listOf(ref)
                }
            }

            for (uClass in uClasses) {
                uClass.qualifiedName?.let { fq ->
                    explicitImportsBySrcId.putIfAbsent(fq, uExplicitMap)
                    wildcardImportsBySrcId.putIfAbsent(fq, uWildcards)
                }
                processUClass(
                    uClass = uClass,
                    parentPackage = pkgName.ifEmpty { null },
                    fileImports = if (uClasses.size == 1) importedFqNames else emptyList(),
                    dtoFieldsBySimpleName = dtoFieldsBySimpleName,
                    nodesById = nodesById,
                    rawDependencies = rawDependencies,
                    fallbackFilePath = fallbackPathByUFile[uFile]
                )
            }
        }

        for (suppNode in supplementalNodes) {
            val existing = nodesById[suppNode.id]
            if (existing == null) {
                nodesById[suppNode.id] = suppNode
            } else if (suppNode.layoutX != null && suppNode.layoutY != null && existing.layoutX == null) {
                nodesById[suppNode.id] = existing.copy(layoutX = suppNode.layoutX, layoutY = suppNode.layoutY)
            }
        }
        for ((id, coords) in specCoordsById) {
            val existing = nodesById[id]
            if (existing != null && existing.layoutX == null) {
                nodesById[id] = existing.copy(layoutX = coords.first, layoutY = coords.second)
            }
        }

        val edges = mutableListOf<ArcEdge>()
        val seenEdgeKeys = mutableSetOf<String>()
        var edgeCounter = 1

        val simpleNameToFqIds = nodesById.values
            .filter { it.c4Level == C4Level.L3_COMPONENT }
            .groupBy({ it.label }, { it.id })

        for ((srcId, targetId, kind) in rawDependencies) {
            val resolvedTargets = when {
                targetId.endsWith(".*") -> {
                    val pkgPrefix = targetId.removeSuffix(".*")
                    val pkgMembers = nodesById.values.filter { it.parentId == pkgPrefix }
                    val refSimples = referencedSimplesBySrcId[srcId]
                    val matchedMembers = if (!refSimples.isNullOrEmpty()) {
                        pkgMembers.filter { it.label in refSimples }
                    } else {
                        pkgMembers
                    }
                    val effectiveMembers = matchedMembers.ifEmpty { pkgMembers }.map { it.id }
                    effectiveMembers.ifEmpty { listOfNotNull(pkgPrefix.takeIf { it in nodesById }) }
                }
                targetId in nodesById -> listOf(targetId)
                targetId.contains('.') && targetId.substringBeforeLast('.') in nodesById -> {
                    listOf(targetId.substringBeforeLast('.'))
                }
                !targetId.contains('.') -> {
                    val explicitMap = explicitImportsBySrcId[srcId]
                    val explicitImpFq = explicitMap?.get(targetId)
                    if (explicitImpFq != null) {
                        listOfNotNull(explicitImpFq.takeIf { it in nodesById && it != srcId })
                    } else {
                        val srcPkg = nodesById[srcId]?.parentId ?: srcId.substringBeforeLast('.', "")
                        val samePkgCandidate = if (srcPkg.isNotEmpty()) "$srcPkg.$targetId" else targetId
                        if (samePkgCandidate in nodesById && samePkgCandidate != srcId) {
                            listOf(samePkgCandidate)
                        } else {
                            val wildcards = wildcardImportsBySrcId[srcId].orEmpty()
                            val wildcardMatches = wildcards
                                .map { "$it.$targetId" }
                                .filter { it in nodesById && it != srcId }
                                .distinct()
                            if (wildcardMatches.isNotEmpty()) {
                                wildcardMatches
                            } else if (explicitMap.isNullOrEmpty() && wildcards.isEmpty()) {
                                val candidates = simpleNameToFqIds[targetId].orEmpty()
                                if (candidates.size == 1) candidates else emptyList()
                            } else {
                                emptyList()
                            }
                        }
                    }
                }
                else -> emptyList()
            }
            for (resolvedTarget in resolvedTargets) {
                if (srcId != resolvedTarget) {
                    val srcNode = nodesById[srcId]
                    val tgtNode = nodesById[resolvedTarget]
                    val effectiveKind = if (
                        kind == EdgeKind.IMPLEMENTS &&
                        (srcNode?.kind == NodeKind.INTERFACE || (tgtNode != null && tgtNode.kind != NodeKind.INTERFACE))
                    ) {
                        EdgeKind.EXTENDS
                    } else {
                        kind
                    }
                    val key = "$srcId->$resolvedTarget:$effectiveKind"
                    if (seenEdgeKeys.add(key)) {
                        edges.add(
                            ArcEdge(
                                id = "uast-e${edgeCounter++}",
                                sourceId = srcId,
                                targetId = resolvedTarget,
                                kind = effectiveKind
                            )
                        )
                    }
                }
            }
        }

        for (suppEdge in supplementalEdges) {
            if (suppEdge.sourceId != suppEdge.targetId &&
                suppEdge.sourceId in nodesById &&
                suppEdge.targetId in nodesById &&
                suppEdge.kind != EdgeKind.HTTP_CONTRACT
            ) {
                val key = "${suppEdge.sourceId}->${suppEdge.targetId}:${suppEdge.kind}"
                if (seenEdgeKeys.add(key)) {
                    edges.add(
                        suppEdge.copy(id = "uast-e${edgeCounter++}")
                    )
                }
            }
        }

        val superEdgePairs = edges
            .filter { it.kind == EdgeKind.IMPLEMENTS || it.kind == EdgeKind.EXTENDS }
            .map { "${it.sourceId}->${it.targetId}" }
            .toSet()
        val deduplicatedEdges = edges.filterNot {
            it.kind == EdgeKind.DEPENDS_ON && "${it.sourceId}->${it.targetId}" in superEdgePairs
        }

        val rawGraph = ArcGraph(
            projectName = projectName,
            nodes = nodesById.values.toList(),
            edges = deduplicatedEdges,
            rules = existingRules
        )

        val contractEnriched = PolyglotContractBridge.linkAndVerify(rawGraph, upstreamManifest).enrichedGraph
        val cycleEnriched = dev.arcdrift.core.analysis.TarjanCycleDetector.annotateGraphWithCycles(contractEnriched)
        val ruleViolations = ArcRuleEngine.evaluate(cycleEnriched)
        return ArcRuleEngine.annotateViolations(cycleEnriched, ruleViolations)
    }

    private fun processUClass(
        uClass: UClass,
        parentPackage: String?,
        fileImports: List<String>,
        dtoFieldsBySimpleName: Map<String, Map<String, String>>,
        nodesById: MutableMap<String, ArcNode>,
        rawDependencies: MutableList<Triple<String, String, String>>,
        fallbackFilePath: String? = null
    ) = UastClassExtractor.processUClass(
        uClass = uClass,
        parentPackage = parentPackage,
        fileImports = fileImports,
        dtoFieldsBySimpleName = dtoFieldsBySimpleName,
        nodesById = nodesById,
        rawDependencies = rawDependencies,
        fallbackFilePath = fallbackFilePath
    )

    private fun collectAllUClasses(roots: List<UClass>): List<UClass> {
        val out = mutableListOf<UClass>()
        fun visit(cls: UClass) {
            out.add(cls)
            val inners = runPsiSafely { cls.innerClasses.toList() }.getOrDefault(emptyList())
            for (inner in inners) {
                visit(inner)
            }
        }
        for (r in roots) {
            visit(r)
        }
        return out
    }
}
