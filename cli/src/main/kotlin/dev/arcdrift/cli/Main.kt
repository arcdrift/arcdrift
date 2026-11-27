package dev.arcdrift.cli

import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.default
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.versionOption
import com.github.ajalt.clikt.parameters.types.int
import dev.arcdrift.cli.reporter.MarkdownPrReporter
import dev.arcdrift.cli.ui.LocalBrowserUiServer
import dev.arcdrift.core.scanner.ArcDriftIgnore
import dev.arcdrift.core.scanner.PolyglotRepoScanner
import dev.arcdrift.core.analysis.ArchitectureSlicer
import dev.arcdrift.core.analysis.BlastRadiusDiffer
import dev.arcdrift.core.analysis.TarjanCycleDetector
import dev.arcdrift.core.codec.ArcMermaidParser
import dev.arcdrift.core.codec.ArcMermaidSerializer
import dev.arcdrift.core.contracts.ContractFederationManifest
import dev.arcdrift.core.contracts.PolyglotContractBridge
import dev.arcdrift.core.model.C4Level
import dev.arcdrift.core.refactoring.InMemoryGraphRefactoringAdapter
import dev.arcdrift.core.reporting.ComplianceReportFormatter
import dev.arcdrift.core.rules.ArcRuleEngine
import dev.arcdrift.core.rules.ArchUnitTestGenerator
import dev.arcdrift.core.spi.ArcDriftSpiLoader
import dev.arcdrift.core.spi.ComplianceCheckSummary
import dev.arcdrift.mcp.protocol.ArchitecturalComplianceReport
import dev.arcdrift.mcp.server.ArcDriftMcpServer
import dev.arcdrift.mcp.transport.StdioMcpTransport
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

private val prettyJson = Json {
    prettyPrint = true
    encodeDefaults = true
    ignoreUnknownKeys = true
}

class ArcRootCommand(
    commandName: String = "arc",
    versionString: String? = null
) : CliktCommand(name = commandName) {
    override fun help(context: Context): String = "ArcDrift — The AI Architecture Control Plane, Spec-and-Verify Engine & MCP Server."

    init {
        versionOption(versionString ?: ArcRootCommand::class.java.`package`?.implementationVersion ?: "0.1.0")
    }

    override fun run() = Unit
}

class ScanCommand : CliktCommand(name = "scan") {
    override fun help(context: Context): String = "Scans a polyglot repository (Kotlin, Java, TypeScript, Python) and outputs its .arc.mmd architecture graph."

    private val dir by argument(help = "Repository root directory to scan").default(".")
    private val format by option("-f", "--format", help = "Output format: mmd | json").default("mmd")
    private val output by option("-o", "--output", help = "Output file path")
    private val exportContracts by option("--export-contracts", help = "Path to write exported producer contracts manifest (.arc-contracts.json) for multi-repo federation")
    private val upstreamContracts by option("--upstream-contracts", help = "Comma-separated or repeatable paths to upstream producer contract manifests (.arc-contracts.json)").multiple()

    override fun run() {
        val normalizedFormat = format.lowercase()
        if (normalizedFormat !in setOf("mmd", "json")) {
            throw UsageError("Invalid --format '$format' for 'arc scan'. Expected: mmd | json")
        }
        val rootDir = requireExistingDirectory(dir)
        val scanned = runScannerOrUsageError { PolyglotRepoScanner.scanRepository(rootDir) }
        val withRules = runSpiOrUsageError { ArcDriftSpiLoader.mergeOrgRules(scanned, rootDir) }
        exportContractsIfRequested(exportContracts, withRules, rootDir)
        val mergedUpstream = resolveUpstreamManifests(withRules.projectName, rootDir, upstreamContracts)
        val ignoreConfig = ArcDriftIgnore.load(rootDir)
        val graph = if (mergedUpstream != null) {
            PolyglotContractBridge.linkAndVerify(withRules, mergedUpstream, ignoreConfig).enrichedGraph
        } else {
            withRules
        }
        val serialized = when (normalizedFormat) {
            "json" -> prettyJson.encodeToString(graph)
            else -> ArcMermaidSerializer.serialize(graph)
        }
        val outPath = output
        if (outPath != null) {
            val outFile = writeTextFile(outPath, serialized)
            echo("Wrote architecture spec (${graph.nodes.size} nodes, ${graph.edges.size} edges) to ${outFile.path}")
        } else {
            echo(serialized, trailingNewline = false)
        }
    }
}

class CheckCommand : CliktCommand(name = "check") {
    override fun help(context: Context): String = "Verifies architectural compliance (cycles, .arc.mmd boundary rules, and cross-language contracts)."

    private val dir by argument(help = "Repository root directory").default(".")
    private val spec by option("-s", "--spec", help = "Explicit .arc.mmd specification file")
    private val generateArchunit by option("--generate-archunit", help = "Path to output generated JUnit5 ArchUnit test file")
    private val basePackage by option("--base-package", help = "Base package for generated ArchUnit test").default("com.acme")
    private val exportContracts by option("--export-contracts", help = "Path to write exported producer contracts manifest (.arc-contracts.json)")
    private val upstreamContracts by option("--upstream-contracts", help = "Comma-separated or repeatable paths to upstream producer contract manifests (.arc-contracts.json)").multiple()
    private val format by option("-f", "--format", help = "Output format: text | json | sarif | sonar").default("text")
    private val output by option("-o", "--output", help = "Optional file path to write the compliance report")

    override fun run() {
        val normalizedFormat = format.lowercase()
        if (normalizedFormat !in setOf("text", "json", "sarif", "sonar")) {
            throw UsageError("Invalid --format '$format' for 'arc check'. Expected: text | json | sarif | sonar")
        }
        val rootDir = requireExistingDirectory(dir)
        val specFile = spec?.let { requireExistingFile(it, "Specification file (--spec)") }
        val scannedGraph = runScannerOrUsageError { PolyglotRepoScanner.scanRepository(rootDir, specFile) }
        val graph = runSpiOrUsageError { ArcDriftSpiLoader.mergeOrgRules(scannedGraph, rootDir) }

        exportContractsIfRequested(exportContracts, graph, rootDir)

        val mergedUpstreamManifest = resolveUpstreamManifests(graph.projectName, rootDir, upstreamContracts)

        generateArchunit?.let { targetPath ->
            val archUnitCode = ArchUnitTestGenerator.generateKotlinJUnit5(
                projectName = graph.projectName,
                basePackage = basePackage,
                rules = graph.rules
            )
            writeTextFile(targetPath, archUnitCode)
        }

        val cycles = TarjanCycleDetector.findCycles(graph)
        val ruleViolations = ArcRuleEngine.evaluate(graph)
        val ignoreConfig = ArcDriftIgnore.load(rootDir)
        val contractViolations = PolyglotContractBridge.linkAndVerify(graph, mergedUpstreamManifest, ignoreConfig).violations
        val passed = cycles.isEmpty() && ruleViolations.isEmpty() && contractViolations.isEmpty()
        ArcDriftSpiLoader.recordComplianceCheck(
            ComplianceCheckSummary(
                projectName = graph.projectName,
                passed = passed,
                cycleCount = cycles.size,
                ruleViolationCount = ruleViolations.size,
                contractViolationCount = contractViolations.size
            )
        )

        val report = ArchitecturalComplianceReport(
            projectName = graph.projectName,
            passed = passed,
            cycleCount = cycles.size,
            ruleViolationCount = ruleViolations.size,
            contractViolationCount = contractViolations.size,
            cycles = cycles,
            ruleViolations = ruleViolations,
            contractViolations = contractViolations,
            summary = if (passed) {
                "PASSED: '${graph.projectName}' has 0 cycles, 0 boundary violations, and 0 contract violations."
            } else {
                val cycleWord = if (cycles.size == 1) "cycle" else "cycles"
                val boundaryWord = if (ruleViolations.size == 1) "boundary violation" else "boundary violations"
                val contractWord = if (contractViolations.size == 1) "contract violation" else "contract violations"
                "FAILED: '${graph.projectName}' has ${cycles.size} $cycleWord, ${ruleViolations.size} $boundaryWord, and ${contractViolations.size} $contractWord."
            }
        )

        val rendered = when (format.lowercase()) {
            "json" -> prettyJson.encodeToString(report)
            "sarif" -> ComplianceReportFormatter.toSarifJson(graph, cycles, ruleViolations, contractViolations)
            "sonar" -> ComplianceReportFormatter.toSonarGenericIssuesJson(graph, cycles, ruleViolations, contractViolations)
            "text" -> buildString {
                appendLine(report.summary)
                for (c in cycles) {
                    appendLine("  [CYCLE] ${c.id}: ${c.nodeIds.joinToString(" -> ")} | Hint: ${c.recommendationRationale}")
                }
                for (rv in ruleViolations) {
                    appendLine("  [RULE VIOLATION] ${rv.ruleId}: ${rv.message}")
                }
                for (cv in contractViolations) {
                    appendLine("  [CONTRACT DRIFT] ${cv.id}: ${cv.message}")
                }
            }.trimEnd()
            else -> throw UsageError("Invalid --format '$format' for 'arc check'. Expected: text | json | sarif | sonar")
        }

        val outPath = output
        if (outPath != null) {
            writeTextFile(outPath, rendered)
        } else {
            echo(rendered)
        }

        if (!passed) {
            throw ProgramResult(1)
        }
    }
}

class DiffCommand : CliktCommand(name = "diff") {
    override fun help(context: Context): String = "Computes the visual architecture diff and upstream blast radius between a baseline .arc.mmd spec and current directory."

    private val baselineSpec by argument(help = "Path to baseline .arc.mmd file")
    private val currentDir by argument(help = "Path to current repository directory or .arc.mmd file").default(".")
    private val format by option("-f", "--format", help = "Output format: markdown | mmd | json").default("markdown")
    private val depth by option("-d", "--depth", help = "Maximum blast-radius propagation depth").int().default(3)
    private val output by option("-o", "--output", help = "Optional file path to write the diff report")
    private val upstreamContracts by option("--upstream-contracts", help = "Comma-separated or repeatable paths to upstream producer contract manifests (.arc-contracts.json)").multiple()

    override fun run() {
        val normalizedFormat = format.lowercase()
        if (normalizedFormat !in setOf("markdown", "md", "mmd", "json")) {
            throw UsageError("Invalid --format '$format' for 'arc diff'. Expected: markdown | mmd | json")
        }
        if (depth < 0) {
            throw UsageError("Invalid --depth '$depth' for 'arc diff': must be >= 0.")
        }
        val baseFile = requireExistingFile(baselineSpec, "Baseline specification file")
        val baselineGraph = parseMermaidFileOrUsageError(baseFile, "Baseline specification file")

        val currTarget = File(currentDir)
        if (!currTarget.exists()) {
            throw UsageError("Current target path does not exist: $currentDir")
        }
        val repoRootForSpi = if (currTarget.isDirectory) currTarget else currTarget.parentFile ?: File(".")
        val rawCurrentGraph = if (currTarget.isFile) {
            val parsed = parseMermaidFileOrUsageError(currTarget, "Current specification file")
            val withFallback = parsed.copy(rules = (baselineGraph.rules + parsed.rules).distinctBy { it.id })
            runSpiOrUsageError { ArcDriftSpiLoader.mergeOrgRules(withFallback, repoRootForSpi) }
        } else if (currTarget.isDirectory) {
            val scanned = runScannerOrUsageError { PolyglotRepoScanner.scanRepository(currTarget) }
            val withFallback = scanned.copy(rules = (baselineGraph.rules + scanned.rules).distinctBy { it.id })
            runSpiOrUsageError { ArcDriftSpiLoader.mergeOrgRules(withFallback, currTarget) }
        } else {
            throw UsageError("Current target path must be a directory or .arc.mmd file: $currentDir")
        }

        val mergedUpstreamManifest = resolveUpstreamManifests(rawCurrentGraph.projectName, repoRootForSpi, upstreamContracts)
        val ignoreConfig = ArcDriftIgnore.load(repoRootForSpi)
        val contractVerification = PolyglotContractBridge.linkAndVerify(rawCurrentGraph, mergedUpstreamManifest, ignoreConfig)
        val currentGraph = if (mergedUpstreamManifest != null) {
            contractVerification.enrichedGraph
        } else {
            rawCurrentGraph
        }

        val diffReport = BlastRadiusDiffer.diff(baselineGraph, currentGraph, maxDepth = depth)
        val cycles = TarjanCycleDetector.findCycles(currentGraph)
        val ruleViolations = ArcRuleEngine.evaluate(currentGraph)
        val contractViolations = contractVerification.violations

        val rendered = when (format.lowercase()) {
            "mmd" -> ArcMermaidSerializer.serialize(diffReport.diffGraph)
            "json" -> prettyJson.encodeToString(diffReport)
            "markdown", "md" -> MarkdownPrReporter.generatePrReport(
                projectName = currentGraph.projectName,
                diffReport = diffReport,
                cycles = cycles,
                ruleViolations = ruleViolations,
                contractViolations = contractViolations
            )
            else -> throw UsageError("Invalid --format '$format' for 'arc diff'. Expected: markdown | mmd | json")
        }

        val outPath = output
        if (outPath != null) {
            writeTextFile(outPath, rendered)
        } else {
            echo(rendered, trailingNewline = format.lowercase() != "mmd")
        }
    }
}

class SliceCommand : CliktCommand(name = "slice") {
    override fun help(context: Context): String = "Extracts a compact semantic slice of the repository architecture around a scope query."

    private val dir by argument(help = "Repository root directory").default(".")
    private val scope by option("-s", "--scope", help = "Scope substring (package, class, or component name)")
    private val level by option("-l", "--level", help = "Maximum C4 level (L1_SYSTEM, L2_CONTAINER, L3_COMPONENT, L4_CODE)")
    private val depth by option("-d", "--depth", help = "Hop depth").int().default(2)
    private val upstreamContracts by option("--upstream-contracts", help = "Comma-separated or repeatable paths to upstream producer contract manifests (.arc-contracts.json)").multiple()

    override fun run() {
        if (depth < 0) {
            throw UsageError("Invalid --depth '$depth' for 'arc slice': must be >= 0.")
        }
        val c4 = level?.let { rawLevel ->
            runCatching { C4Level.valueOf(rawLevel.trim().uppercase()) }.getOrElse {
                throw UsageError(
                    "Invalid --level '$rawLevel' for 'arc slice'. Expected: ${C4Level.entries.joinToString(" | ") { it.name }}"
                )
            }
        }
        val rootDir = requireExistingDirectory(dir)
        val baseGraph = runSpiOrUsageError {
            ArcDriftSpiLoader.mergeOrgRules(
                runScannerOrUsageError { PolyglotRepoScanner.scanRepository(rootDir) },
                rootDir
            )
        }
        val mergedUpstream = resolveUpstreamManifests(baseGraph.projectName, rootDir, upstreamContracts)
        val ignoreConfig = ArcDriftIgnore.load(rootDir)
        val graph = if (mergedUpstream != null) {
            PolyglotContractBridge.linkAndVerify(baseGraph, mergedUpstream, ignoreConfig).enrichedGraph
        } else {
            baseGraph
        }
        val sliced = ArchitectureSlicer.slice(graph, scopeQuery = scope, maxC4Level = c4, depth = depth)
        echo(prettyJson.encodeToString(sliced))
    }
}

class McpCommand : CliktCommand(name = "mcp") {
    override fun help(context: Context): String = "Starts the ArcDrift Model Context Protocol (MCP) JSON-RPC 2.0 server over stdio."

    private val dir by argument(help = "Repository root directory to serve").default(".")
    private val upstreamContracts by option("--upstream-contracts", help = "Comma-separated or repeatable paths to upstream producer contract manifests (.arc-contracts.json)").multiple()

    override fun run() {
        val rootDir = requireExistingDirectory(dir)
        val mcpOut = System.out
        System.setOut(System.err)
        try {
            val initialGraph = runSpiOrUsageError {
                ArcDriftSpiLoader.mergeOrgRules(
                    runScannerOrUsageError { PolyglotRepoScanner.scanRepository(rootDir) },
                    rootDir
                )
            }
            val adapter = InMemoryGraphRefactoringAdapter(
                initialGraph = initialGraph,
                rootDir = rootDir,
                customScanner = { d -> ArcDriftSpiLoader.mergeOrgRules(PolyglotRepoScanner.scanRepository(d), d) }
            )
            val server = ArcDriftMcpServer(
                graphProvider = { adapter.rescanFromDiskIfAvailable() },
                refactoringPort = adapter,
                upstreamManifestProvider = {
                    resolveUpstreamManifests(adapter.currentGraph.projectName, rootDir, upstreamContracts)
                },
                ignoreProvider = { ArcDriftIgnore.load(rootDir) }
            )
            StdioMcpTransport(server, output = mcpOut).runLoop()
        } finally {
            System.setOut(mcpOut)
        }
    }
}

class UiCommand : CliktCommand(name = "ui") {
    override fun help(context: Context): String = "Launches the local browser-based interactive ArcDrift architecture explorer (127.0.0.1)."

    private val dir by argument(help = "Repository root directory to explore").default(".")
    private val port by option("-p", "--port", help = "Localhost TCP port to bind (0 for ephemeral)").int().default(LocalBrowserUiServer.DEFAULT_PORT)
    private val spec by option("-s", "--spec", help = "Explicit .arc.mmd specification file")
    private val upstreamContracts by option("--upstream-contracts", help = "Comma-separated or repeatable paths to upstream producer contract manifests (.arc-contracts.json)").multiple()
    private val noOpen by option("--no-open", help = "Do not automatically open the system web browser").flag(default = false)

    override fun run() {
        if (port !in 0..65535) {
            throw UsageError("Invalid --port '$port' for 'arc ui': must be between 0 and 65535.")
        }
        val rootDir = requireExistingDirectory(dir)
        val specFile = spec?.let { requireExistingFile(it, "Specification file (--spec)") }
        val server = LocalBrowserUiServer(
            rootDir = rootDir,
            explicitSpecFile = specFile,
            upstreamManifestProvider = { projectName ->
                resolveUpstreamManifests(projectName, rootDir, upstreamContracts)
            },
            requestedPort = port
        )
        val url = try {
            runScannerOrUsageError { runSpiOrUsageError { server.start() } }
        } catch (e: UsageError) {
            throw e
        } catch (e: Exception) {
            throw UsageError("Failed to start ArcDrift UI server on 127.0.0.1:$port: ${e.message}")
        }

        val shutdownLatch = java.util.concurrent.CountDownLatch(1)
        val shutdownHook = Thread({
            server.stop()
            shutdownLatch.countDown()
        }, "arcdrift-ui-shutdown")
        Runtime.getRuntime().addShutdownHook(shutdownHook)

        echo("ArcDrift interactive architecture explorer running at $url (press Ctrl+C to stop)")
        if (!noOpen) {
            LocalBrowserUiServer.openInSystemBrowser(url)
        }
        try {
            shutdownLatch.await()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            server.stop()
            runCatching { Runtime.getRuntime().removeShutdownHook(shutdownHook) }
        }
    }
}

private fun requireExistingDirectory(path: String): File {
    val dir = File(path)
    if (!dir.exists() || !dir.isDirectory) {
        throw UsageError("Repository directory does not exist or is not a directory: $path")
    }
    return dir
}

private fun requireExistingFile(path: String, label: String): File {
    val file = File(path)
    if (!file.exists() || !file.isFile) {
        throw UsageError("$label does not exist or is not a file: $path")
    }
    return file
}

private fun parseMermaidFileOrUsageError(file: File, label: String): dev.arcdrift.core.model.ArcGraph {
    return try {
        ArcMermaidParser.parse(file.readText(Charsets.UTF_8))
    } catch (e: Exception) {
        throw UsageError("Failed to read or parse $label '${file.path}': ${e.message}")
    }
}

private inline fun <T> runScannerOrUsageError(block: () -> T): T {
    return try {
        block()
    } catch (e: IllegalArgumentException) {
        throw UsageError(e.message ?: "Invalid repository or specification argument.")
    }
}

private inline fun <T> runSpiOrUsageError(block: () -> T): T {
    return try {
        block()
    } catch (e: IllegalStateException) {
        throw UsageError(e.message ?: "Enterprise policy or registry configuration error.")
    }
}

private fun writeTextFile(path: String, content: String): File {
    try {
        val file = File(path)
        file.parentFile?.mkdirs()
        file.writeText(content, Charsets.UTF_8)
        return file
    } catch (e: Exception) {
        throw UsageError("Failed to write output file '$path': ${e.message}")
    }
}

private fun exportContractsIfRequested(
    exportContractsPath: String?,
    graph: dev.arcdrift.core.model.ArcGraph,
    repoRoot: File
) {
    val path = exportContractsPath ?: return
    val manifest = PolyglotContractBridge.exportProducerManifest(graph)
    writeTextFile(path, prettyJson.encodeToString(manifest))
    runSpiOrUsageError {
        ArcDriftSpiLoader.publishProducerManifest(graph, manifest, repoRoot = repoRoot)
    }
}

private fun resolveUpstreamManifests(
    projectName: String,
    rootDir: File,
    upstreamContracts: List<String>
): ContractFederationManifest? {
    val spiManifests = runSpiOrUsageError {
        ArcDriftSpiLoader.loadAdditionalUpstreamManifests(projectName, rootDir)
    }
    val explicitManifest = loadMergedUpstreamManifest(upstreamContracts)
    val allManifests = listOfNotNull(explicitManifest) + spiManifests
    return if (allManifests.isEmpty()) null else PolyglotContractBridge.mergeManifests(allManifests)
}

private fun loadMergedUpstreamManifest(upstreamContracts: List<String>): ContractFederationManifest? {
    return upstreamContracts
        .flatMap { it.split(",") }
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { path ->
            val f = requireExistingFile(path, "Upstream contracts manifest (--upstream-contracts)")
            try {
                prettyJson.decodeFromString<ContractFederationManifest>(f.readText(Charsets.UTF_8))
            } catch (e: Exception) {
                throw UsageError("Failed to parse upstream contracts manifest '${f.path}': ${e.message}")
            }
        }
        .takeIf { it.isNotEmpty() }
        ?.let { PolyglotContractBridge.mergeManifests(it) }
}

fun buildArcCli(
    commandName: String = "arc",
    versionString: String? = null
): CliktCommand =
    ArcRootCommand(commandName = commandName, versionString = versionString).subcommands(
        ScanCommand(),
        CheckCommand(),
        DiffCommand(),
        SliceCommand(),
        McpCommand(),
        UiCommand()
    )

/** Exit code returned for invalid arguments/options so CI can distinguish usage errors from architectural drift (`1`). */
internal const val USAGE_ERROR_EXIT_CODE = 2

internal fun exitCodeFor(error: com.github.ajalt.clikt.core.CliktError): Int =
    if (error is UsageError) USAGE_ERROR_EXIT_CODE else error.statusCode

fun main(args: Array<String>) {
    val cli = buildArcCli()
    try {
        cli.parse(args.toList())
    } catch (e: com.github.ajalt.clikt.core.CliktError) {
        cli.echoFormattedHelp(e)
        kotlin.system.exitProcess(exitCodeFor(e))
    }
}
