package dev.arcdrift.core.scanner

import dev.arcdrift.core.analysis.TarjanCycleDetector
import dev.arcdrift.core.ast.AstTypeUnwrapper
import dev.arcdrift.core.ast.JavaAstExtractor
import dev.arcdrift.core.ast.JvmParsedFileAst
import dev.arcdrift.core.ast.KotlinAstParser
import dev.arcdrift.core.ast.PyParsedFileAst
import dev.arcdrift.core.ast.PythonAstParser
import dev.arcdrift.core.ast.TsParsedFileAst
import dev.arcdrift.core.ast.TypeScriptAstParser
import dev.arcdrift.core.codec.ArcMermaidParser
import dev.arcdrift.core.contracts.PolyglotContractBridge
import dev.arcdrift.core.model.*
import java.io.File
import java.nio.file.Files

/**
 * Lock-free, parallel, SHA-256-cached polyglot repository scanner (`Java 21` via
 * `com.github.javaparser:javaparser-core`, `Kotlin` via `io.github.bonede:tree-sitter-kotlin`,
 * `TypeScript` via `io.github.bonede:tree-sitter-typescript`, and
 * `Python` via `io.github.bonede:tree-sitter-python`, plus `.arc.mmd` specs and `.arcdriftignore`).
 */
object PolyglotRepoScanner {

    private const val PARALLEL_SCAN_THRESHOLD = 8

    private val IGNORED_DIRS = setOf(
        ".git", ".gradle", ".gradle-user-home", ".idea", ".arcdrift", ".arcdrift-registry",
        "build", "dist", "node_modules", "target", "out", "__pycache__",
        "tests", "test", "e2e", "cypress", "playwright", "mocks", "__mocks__"
    )

    private sealed class ParsedSourceFile(val relPath: String, val file: File, val lineCount: Int) {
        class Jvm(
            relPath: String,
            file: File,
            lineCount: Int,
            val language: String,
            val ast: JvmParsedFileAst
        ) : ParsedSourceFile(relPath, file, lineCount)

        class TypeScript(
            relPath: String,
            file: File,
            lineCount: Int,
            val isTsx: Boolean,
            val isAmbientDts: Boolean,
            val ast: TsParsedFileAst
        ) : ParsedSourceFile(relPath, file, lineCount)

        class Python(
            relPath: String,
            file: File,
            lineCount: Int,
            val ast: PyParsedFileAst
        ) : ParsedSourceFile(relPath, file, lineCount)
    }

    fun scanRepository(
        rootDir: File,
        explicitSpecFile: File? = null,
        includeJvmSources: Boolean = true,
        fileContentOverrides: Map<String, String> = emptyMap(),
        supplementalJvmMethodNames: Set<String> = emptySet(),
        astCache: RepoAstCache = RepoAstCache.global,
        diskCacheDir: File? = null
    ): ArcGraph {
        require(rootDir.exists() && rootDir.isDirectory) {
            "Repository directory does not exist: ${rootDir.path}"
        }
        if (explicitSpecFile != null) {
            require(explicitSpecFile.exists() && explicitSpecFile.isFile) {
                "Specification file does not exist: ${explicitSpecFile.path}"
            }
        }

        val resolvedDiskCacheDir = diskCacheDir
            ?: System.getenv("ARCDRIFT_CACHE_DIR")?.trim()?.takeIf { it.isNotEmpty() }?.let { File(it) }
        if (resolvedDiskCacheDir != null) {
            astCache.loadFromDisk(resolvedDiskCacheDir)
        }

        fun readFileWithOverride(f: File): String {
            val relPath = runCatching { f.relativeTo(rootDir).invariantSeparatorsPath }.getOrNull()
            return (if (relPath != null) fileContentOverrides[relPath] else null)
                ?: fileContentOverrides[f.invariantSeparatorsPath]
                ?: fileContentOverrides[f.name]
                ?: f.readText(Charsets.UTF_8)
        }

        val ignoreConfig = ArcDriftIgnore.load(rootDir, fileContentOverrides)

        val discoveredSpecs = findSpecFiles(rootDir, ignoreConfig)
        val specFile = explicitSpecFile ?: discoveredSpecs.firstOrNull()
        val specGraph = specFile?.takeIf { it.exists() }?.let {
            ArcMermaidParser.parse(readFileWithOverride(it))
        }
        val mergedSpecRules = if (explicitSpecFile != null) {
            specGraph?.rules.orEmpty()
        } else {
            discoveredSpecs
                .flatMap { f -> runCatching { ArcMermaidParser.parse(readFileWithOverride(f)).rules }.getOrDefault(emptyList()) }
                .distinctBy { it.id }
        }

        val sourceFiles = collectSourceFiles(rootDir, ignoreConfig)
        val (openApiOperations, openApiSchemas) = OpenApiAndTypeSpecExtractor.extract(rootDir, IGNORED_DIRS, fileContentOverrides, ignoreConfig)
        val dtoDefinitions = linkedMapOf<String, Map<String, String>>().apply {
            putAll(openApiSchemas)
        }
        val tsTypeDefinitions = linkedMapOf<String, Map<String, String>>()
        val tsTypeParents = linkedMapOf<String, MutableList<String>>()
        val tsSdkMethods = linkedMapOf<String, ContractEndpoint>()
        val tsSdkCallsByNodeId = linkedMapOf<String, List<Pair<String, String>>>()
        val pyModelDefinitions = linkedMapOf<String, Map<String, String>>()
        val pyModelParents = linkedMapOf<String, MutableList<String>>()
        val pyApiPrefixes = linkedMapOf<String, String>()
        val scannedJvmClasses = mutableListOf<ScannedJvmClassRecord>()
        val tsImportsByNodeId = linkedMapOf<String, Pair<String, List<String>>>()
        val pyImportsByNodeId = linkedMapOf<String, List<String>>()
        val nodesById = linkedMapOf<String, ArcNode>()

        if (specGraph != null) {
            for (n in specGraph.nodes) {
                if (n.kind == NodeKind.GHOST_SPEC) {
                    nodesById[n.id] = n
                }
            }
        }

        fun parseSingleFile(file: File): ParsedSourceFile? {
            val relPath = file.relativeTo(rootDir).invariantSeparatorsPath
            val rawText = readFileWithOverride(file)
            val lineCount = maxOf(1, rawText.lines().size)
            return when (file.extension.lowercase()) {
                "java" -> if (includeJvmSources) {
                    val ast = astCache.getOrParseJvm("java", "", rawText) {
                        JavaAstExtractor.parseJavaFile(rawText)
                    }
                    ParsedSourceFile.Jvm(relPath, file, lineCount, "java", ast)
                } else null
                "kt" -> if (includeJvmSources) {
                    val stem = "${file.nameWithoutExtension}Kt"
                    val ast = astCache.getOrParseJvm("kotlin", stem, rawText) {
                        KotlinAstParser.parseKotlinFile(rawText, stem)
                    }
                    ParsedSourceFile.Jvm(relPath, file, lineCount, "kotlin", ast)
                } else null
                "ts", "tsx" -> {
                    val isTsx = file.extension.equals("tsx", ignoreCase = true)
                    val isAmbientDts = file.name.endsWith(".d.ts", ignoreCase = true)
                    val ast = astCache.getOrParseTs(isTsx, rawText) {
                        TypeScriptAstParser.parseTypeScriptFile(rawText, isTsx = isTsx)
                    }
                    ParsedSourceFile.TypeScript(relPath, file, lineCount, isTsx, isAmbientDts, ast)
                }
                "py" -> {
                    val ast = astCache.getOrParsePy(rawText) {
                        PythonAstParser.parsePythonFile(rawText)
                    }
                    ParsedSourceFile.Python(relPath, file, lineCount, ast)
                }
                else -> null
            }
        }

        val missesBeforeScan = astCache.stats().misses

        // Parse lock-free in parallel across cores, then fold results sequentially in sorted file order for determinism
        val parsedFiles: List<ParsedSourceFile> = if (sourceFiles.size >= PARALLEL_SCAN_THRESHOLD) {
            sourceFiles.parallelStream()
                .map { parseSingleFile(it) }
                .toList()
                .filterNotNull()
        } else {
            sourceFiles.mapNotNull { parseSingleFile(it) }
        }

        for (parsed in parsedFiles) {
            when (parsed) {
                is ParsedSourceFile.Jvm -> {
                    for (cls in parsed.ast.classes) {
                        val isComponentBean = cls.kind in setOf(NodeKind.REST_CONTROLLER, NodeKind.SERVICE_BEAN, NodeKind.REPOSITORY)
                        if (cls.fields.isNotEmpty() && (!isComponentBean || cls.simpleName !in dtoDefinitions)) {
                            dtoDefinitions[cls.simpleName] = cls.fields
                        }
                        scannedJvmClasses.add(ScannedJvmClassRecord(parsed.ast, cls, parsed.relPath, parsed.language))
                    }
                }
                is ParsedSourceFile.TypeScript -> {
                    val tsAst = parsed.ast
                    for ((tName, tFields) in tsAst.types) {
                        if (!tName.endsWith("Props", ignoreCase = true) && !tName.endsWith("State", ignoreCase = true)) {
                            tsTypeDefinitions[tName] = tFields
                        }
                    }
                    for ((childName, parents) in tsAst.typeParents) {
                        tsTypeParents.getOrPut(childName) { mutableListOf() }.addAll(parents)
                    }
                    tsSdkMethods.putAll(tsAst.sdkMethods)
                    val filteredContracts = if (ignoreConfig.isEmpty) {
                        tsAst.contracts
                    } else {
                        tsAst.contracts.filterNot { it.role.equals("CONSUMER", ignoreCase = true) && ignoreConfig.isContractIgnored(it) }
                    }
                    if (!parsed.isAmbientDts &&
                        (filteredContracts.isNotEmpty() ||
                            tsAst.types.isNotEmpty() ||
                            tsAst.importPaths.isNotEmpty() ||
                            tsAst.sdkCalls.isNotEmpty() ||
                            tsAst.sdkMethods.isNotEmpty())
                    ) {
                        val baseNodeId = "ts." + parsed.relPath.removeSuffix(".ts").removeSuffix(".tsx").replace('/', '.')
                        val nodeId = if (baseNodeId in nodesById && parsed.isTsx) "$baseNodeId.tsx" else baseNodeId
                        tsImportsByNodeId[nodeId] = parsed.relPath to tsAst.importPaths
                        if (tsAst.sdkCalls.isNotEmpty()) tsSdkCallsByNodeId[nodeId] = tsAst.sdkCalls
                        nodesById[nodeId] = ArcNode(
                            id = nodeId,
                            label = parsed.file.name,
                            c4Level = C4Level.L3_COMPONENT,
                            kind = NodeKind.TS_CLIENT,
                            contracts = filteredContracts,
                            source = SourceLocation(parsed.relPath, 1, parsed.lineCount, "typescript")
                        )
                    }
                }
                is ParsedSourceFile.Python -> {
                    val pyAst = parsed.ast
                    pyModelDefinitions.putAll(pyAst.models)
                    for ((childName, parents) in pyAst.modelParents) {
                        pyModelParents.getOrPut(childName) { mutableListOf() }.addAll(parents)
                    }
                    pyApiPrefixes.putAll(pyAst.apiPrefixConstants)
                    val filteredContracts = if (ignoreConfig.isEmpty) {
                        pyAst.contracts
                    } else {
                        pyAst.contracts.filterNot { it.role.equals("CONSUMER", ignoreCase = true) && ignoreConfig.isContractIgnored(it) }
                    }
                    if (filteredContracts.isNotEmpty() || pyAst.models.isNotEmpty() || pyAst.importModules.isNotEmpty()) {
                        val nodeId = "py." + parsed.relPath.removeSuffix(".py").replace('/', '.')
                        pyImportsByNodeId[nodeId] = pyAst.importModules
                        val isPureProducer = filteredContracts.isNotEmpty() && filteredContracts.all { it.role == "PRODUCER" }
                        val pyKind = if (isPureProducer) NodeKind.REST_CONTROLLER else NodeKind.PYTHON_CONSUMER
                        val pyStereotypes = if (isPureProducer) listOf("FastAPI Router") else emptyList()
                        nodesById[nodeId] = ArcNode(
                            id = nodeId,
                            label = parsed.file.name,
                            c4Level = C4Level.L3_COMPONENT,
                            kind = pyKind,
                            stereotypes = pyStereotypes,
                            contracts = filteredContracts,
                            source = SourceLocation(parsed.relPath, 1, parsed.lineCount, "python")
                        )
                    }
                }
            }
        }

        if (resolvedDiskCacheDir != null) {
            val hadNewMisses = astCache.stats().misses > missesBeforeScan
            val cacheFileExists = File(resolvedDiskCacheDir, RepoAstCache.CACHE_FILE_NAME).isFile
            if (hadNewMisses || !cacheFileExists) {
                astCache.saveToDisk(resolvedDiskCacheDir)
            }
        }

        propagateJvmSuperclassDtoFields(scannedJvmClasses, dtoDefinitions)
        propagateCrossFileModelInheritance(tsTypeParents, tsTypeDefinitions)
        propagateCrossFileModelInheritance(pyModelParents, pyModelDefinitions)

        val implementedJvmMethodNames = scannedJvmClasses
            .filter { rec ->
                rec.classAst.kind == NodeKind.REST_CONTROLLER ||
                    rec.classAst.superTypeSimpleNames.any { it.endsWith("Api") || it.endsWith("Controller") }
            }
            .flatMap { rec -> rec.classAst.methods.map { it.name } }
            .toSet() + supplementalJvmMethodNames

        RepoContractEnricher.enrichPolyglotContracts(
            nodesById = nodesById,
            tsTypeDefinitions = tsTypeDefinitions,
            tsSdkMethods = tsSdkMethods,
            tsSdkCallsByNodeId = tsSdkCallsByNodeId,
            pyModelDefinitions = pyModelDefinitions,
            pyApiPrefixes = pyApiPrefixes,
            openApiOperations = openApiOperations,
            implementedJvmMethodNames = implementedJvmMethodNames
        )

        if (!ignoreConfig.isEmpty) {
            for ((id, node) in nodesById.entries.toList()) {
                if (node.contracts.isNotEmpty()) {
                    val kept = node.contracts.filterNot {
                        it.role.equals("CONSUMER", ignoreCase = true) && ignoreConfig.isContractIgnored(it)
                    }
                    if (kept.size != node.contracts.size) {
                        nodesById[id] = node.copy(contracts = kept)
                    }
                }
            }
        }

        materializeJvmNodes(scannedJvmClasses, dtoDefinitions, openApiOperations, nodesById)

        val deduplicatedEdges = RepoEdgeBuilder.buildEdges(
            scannedJvmClasses = scannedJvmClasses,
            tsImportsByNodeId = tsImportsByNodeId,
            pyImportsByNodeId = pyImportsByNodeId,
            nodesById = nodesById,
            specGraph = specGraph
        )

        val fallbackProjectName = runCatching { rootDir.canonicalFile.name }.getOrNull()?.takeIf { it.isNotEmpty() && it != "." }
            ?: rootDir.absoluteFile.normalize().name.ifEmpty { "project" }
        val resolvedProjectName = specGraph?.projectName?.takeIf { it.isNotBlank() && it != "unnamed-project" }
            ?: fallbackProjectName

        val baseGraph = ArcGraph(
            projectName = resolvedProjectName,
            nodes = nodesById.values.toList(),
            edges = deduplicatedEdges,
            rules = mergedSpecRules,
            metadata = specGraph?.metadata.orEmpty()
        )

        return if (includeJvmSources) {
            val contractEnriched = PolyglotContractBridge.linkAndVerify(baseGraph, ignoreConfig = ignoreConfig).enrichedGraph
            TarjanCycleDetector.annotateGraphWithCycles(contractEnriched)
        } else {
            baseGraph
        }
    }

    fun unwrapPrimaryDtoType(rawType: String): String =
        AstTypeUnwrapper.unwrapPrimaryDtoString(rawType)

    private fun propagateJvmSuperclassDtoFields(
        scannedJvmClasses: List<ScannedJvmClassRecord>,
        dtoDefinitions: MutableMap<String, Map<String, String>>
    ) {
        repeat(4) {
            var changed = false
            for (rec in scannedJvmClasses) {
                val cls = rec.classAst
                if (cls.superTypeSimpleNames.isNotEmpty()) {
                    val inherited = linkedMapOf<String, String>()
                    for (superName in cls.superTypeSimpleNames) {
                        dtoDefinitions[superName]?.let { inherited.putAll(it) }
                    }
                    if (inherited.isNotEmpty()) {
                        val existing = dtoDefinitions[cls.simpleName].orEmpty()
                        val merged = linkedMapOf<String, String>().apply {
                            putAll(inherited)
                            putAll(existing)
                            putAll(cls.fields)
                        }
                        if (merged != existing) {
                            dtoDefinitions[cls.simpleName] = merged
                            changed = true
                        }
                    }
                }
            }
            if (!changed) return
        }
    }

    private fun propagateCrossFileModelInheritance(
        parentsByChild: Map<String, List<String>>,
        definitions: MutableMap<String, Map<String, String>>
    ) {
        if (parentsByChild.isEmpty()) return
        repeat(4) {
            var changed = false
            for ((childName, parents) in parentsByChild) {
                val inherited = linkedMapOf<String, String>()
                for (p in parents) {
                    definitions[p]?.let { inherited.putAll(it) }
                }
                if (inherited.isNotEmpty()) {
                    val existing = definitions[childName].orEmpty()
                    val merged = linkedMapOf<String, String>().apply {
                        putAll(inherited)
                        putAll(existing)
                    }
                    if (merged != existing) {
                        definitions[childName] = merged
                        changed = true
                    }
                }
            }
            if (!changed) return
        }
    }

    private fun materializeJvmNodes(
        scannedJvmClasses: List<ScannedJvmClassRecord>,
        dtoDefinitions: Map<String, Map<String, String>>,
        openApiOperations: Map<String, Pair<String, String>>,
        nodesById: MutableMap<String, ArcNode>
    ) {
        for (rec in scannedJvmClasses) {
            val cls = rec.classAst
            if (cls.pkg.isNotEmpty() && cls.pkg !in nodesById) {
                nodesById[cls.pkg] = ArcNode(
                    id = cls.pkg,
                    label = cls.pkg.substringAfterLast('.'),
                    c4Level = C4Level.L2_CONTAINER,
                    kind = NodeKind.PACKAGE
                )
            }

            val isControllerOrApiImpl = cls.kind == NodeKind.REST_CONTROLLER ||
                cls.superTypeSimpleNames.any { it.endsWith("Api") || it.endsWith("Controller") }
            val contracts = AstTypeUnwrapper.buildContractsForClass(
                annotations = cls.annotations,
                methods = cls.methods,
                inlineRoutes = cls.inlineRoutes,
                dtoDefinitions = dtoDefinitions,
                openApiOperations = openApiOperations,
                isControllerOrApiImpl = isControllerOrApiImpl
            )

            nodesById[cls.fqId] = ArcNode(
                id = cls.fqId,
                label = cls.simpleName,
                c4Level = C4Level.L3_COMPONENT,
                kind = cls.kind,
                parentId = cls.pkg.ifEmpty { null },
                stereotypes = cls.stereotypes,
                members = cls.members,
                contracts = contracts,
                source = SourceLocation(
                    filePath = rec.relPath,
                    startLine = cls.startLine,
                    endLine = cls.endLine,
                    language = rec.language
                )
            )
        }
    }

    private fun isSafeWalkDir(dir: File, rootDir: File, visited: MutableSet<String>): Boolean {
        if (dir.name in IGNORED_DIRS) return false
        if (dir != rootDir && runCatching { Files.isSymbolicLink(dir.toPath()) }.getOrDefault(false)) return false
        val canonical = runCatching { dir.canonicalPath }.getOrNull() ?: return false
        return visited.add(canonical)
    }

    fun findPrimarySpecFile(rootDir: File): File? = findSpecFiles(rootDir).firstOrNull()

    fun findSpecFiles(rootDir: File, ignoreConfig: ArcDriftIgnore = ArcDriftIgnore.EMPTY): List<File> {
        val canonicalNames = setOf("architecture.arc.mmd", "arcdrift.arc.mmd", "spec.arc.mmd")
        val visitedDirs = HashSet<String>()
        fun isIgnored(f: File): Boolean {
            if (ignoreConfig === ArcDriftIgnore.EMPTY || f == rootDir) return false
            val rel = runCatching { f.relativeTo(rootDir).invariantSeparatorsPath }.getOrNull() ?: return false
            return ignoreConfig.isPathIgnored(rel) || (f.isDirectory && ignoreConfig.isPathIgnored("$rel/"))
        }
        return rootDir.walkTopDown()
            .onEnter { isSafeWalkDir(it, rootDir, visitedDirs) && !isIgnored(it) }
            .filter {
                it.isFile &&
                    !isIgnored(it) &&
                    !runCatching { Files.isSymbolicLink(it.toPath()) }.getOrDefault(false) &&
                    !it.name.startsWith(".") &&
                    it.name.endsWith(".arc.mmd", ignoreCase = true)
            }
            .sortedWith(
                compareBy<File>(
                    { it.relativeTo(rootDir).invariantSeparatorsPath.count { ch -> ch == '/' } },
                    {
                        val lower = it.name.lowercase()
                        when {
                            lower == "architecture.arc.mmd" -> 0
                            lower in canonicalNames -> 1
                            lower.contains("baseline") || lower.contains("snapshot") || lower.contains("diff") -> 3
                            else -> 2
                        }
                    },
                    { -it.length() },
                    { it.invariantSeparatorsPath }
                )
            )
            .toList()
    }

    private fun collectSourceFiles(
        rootDir: File,
        ignoreConfig: ArcDriftIgnore = ArcDriftIgnore.EMPTY
    ): List<File> {
        val allowedExts = setOf("kt", "java", "ts", "tsx", "py")
        val ignoredSuffixes = setOf(".spec.ts", ".test.ts", ".spec.tsx", ".test.tsx", "_test.py")
        val visitedDirs = HashSet<String>()
        return rootDir.walkTopDown()
            .onEnter { isSafeWalkDir(it, rootDir, visitedDirs) }
            .filter { file ->
                if (!file.isFile) return@filter false
                if (runCatching { Files.isSymbolicLink(file.toPath()) }.getOrDefault(false)) return@filter false
                val ext = file.extension.lowercase()
                if (ext !in allowedExts) return@filter false
                val lowerName = file.name.lowercase()
                if (ignoredSuffixes.any { lowerName.endsWith(it) } || lowerName.startsWith("test_")) {
                    return@filter false
                }
                val relPathOriginal = file.relativeTo(rootDir).invariantSeparatorsPath
                if (ignoreConfig.isPathIgnored(relPathOriginal)) {
                    return@filter false
                }
                val relPath = relPathOriginal.lowercase()
                if (relPath.contains("/src/test/") || relPath.startsWith("src/test/") || lowerName.endsWith("architecturetest.kt")) {
                    return@filter false
                }
                true
            }
            .sortedBy { it.invariantSeparatorsPath }
            .toList()
    }
}
