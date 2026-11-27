package dev.arcdrift.intellij.service

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiTreeChangeAdapter
import com.intellij.psi.PsiTreeChangeEvent
import com.intellij.util.Alarm
import dev.arcdrift.core.contracts.PolyglotContractBridge
import dev.arcdrift.core.scanner.PolyglotRepoScanner
import dev.arcdrift.core.codec.ArcMermaidParser
import dev.arcdrift.core.codec.ArcMermaidSerializer
import dev.arcdrift.core.model.ArcGraph
import dev.arcdrift.core.model.BoundaryRule
import dev.arcdrift.core.model.NodeKind
import dev.arcdrift.core.refactoring.InMemoryGraphRefactoringAdapter
import dev.arcdrift.core.rules.ArcRuleEngine
import dev.arcdrift.core.spi.ArcDriftSpiLoader
import dev.arcdrift.intellij.psi.UastGraphExtractor
import dev.arcdrift.intellij.refactoring.IntellijPsiRefactoringAdapter
import dev.arcdrift.intellij.ui.IdeSourceNavigator
import dev.arcdrift.mcp.server.ArcDriftMcpServer
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.CancellationException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Project-level IntelliJ Service coordinating:
 * 1. Live UAST/PSI graph extraction (`UastGraphExtractor`).
 * 2. `.arc.mmd` specification file persistence.
 * 3. `PsiTreeChangeListener` incremental graph updates.
 * 4. Bidirectional `ArcDriftMcpServer` for AI coding agents.
 */
@Service(Service.Level.PROJECT)
class ArcDriftProjectService(private val project: Project) : Disposable {

    private val graphListeners = CopyOnWriteArrayList<(ArcGraph) -> Unit>()
    private val refreshAlarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)
    private val refreshSeq = AtomicLong(0)
    private val specFileLock = Any()
    val hasCompletedInitialScan = AtomicBoolean(false)

    @Volatile
    var currentGraph: ArcGraph = ArcGraph(projectName = project.name)
        private set

    @Volatile
    internal var inMemoryAdapter = InMemoryGraphRefactoringAdapter(
        currentGraph,
        project.basePath?.let { File(it) },
        customScanner = { UastGraphExtractor.extractProjectGraph(project, loadRulesFromSpecFile()) }
    )

    val psiRefactoringAdapter = IntellijPsiRefactoringAdapter(
        project = project,
        fallbackGraphAdapterProvider = { inMemoryAdapter }
    )

    val mcpServer = ArcDriftMcpServer(
        graphProvider = { refreshProjectGraph() },
        refactoringPort = psiRefactoringAdapter,
        onGraphUpdated = { updated -> updateGraph(updated) },
        upstreamManifestProvider = {
            val root = project.basePath?.let { File(it) }?.takeIf { it.exists() }
            if (root != null) {
                val manifests = runCatching {
                    ArcDriftSpiLoader.loadAdditionalUpstreamManifests(currentGraph.projectName, root)
                }.getOrDefault(emptyList())
                if (manifests.isEmpty()) null else PolyglotContractBridge.mergeManifests(manifests)
            } else {
                null
            }
        }
    )

    init {
        PsiManager.getInstance(project).addPsiTreeChangeListener(
            object : PsiTreeChangeAdapter() {
                override fun childrenChanged(event: PsiTreeChangeEvent) {
                    val file = event.file ?: return
                    val vfPath = file.virtualFile?.path
                    if (vfPath != null) {
                        if (IdeSourceNavigator.shouldTriggerGraphRefresh(vfPath, project.basePath)) {
                            scheduleRefreshProjectGraph()
                        }
                    } else {
                        val name = file.name
                        if (
                            name.endsWith(".kt") ||
                            name.endsWith(".java") ||
                            name.endsWith(".ts") ||
                            name.endsWith(".tsx") ||
                            name.endsWith(".py") ||
                            name.endsWith(".arc.mmd")
                        ) {
                            scheduleRefreshProjectGraph()
                        }
                    }
                }
            },
            this
        )
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(
            object : DocumentListener {
                override fun documentChanged(event: DocumentEvent) {
                    val vf = FileDocumentManager.getInstance().getFile(event.document) ?: return
                    if (IdeSourceNavigator.shouldTriggerGraphRefresh(vf.path, project.basePath)) {
                        scheduleRefreshProjectGraph()
                    }
                }
            },
            this
        )
        DumbService.getInstance(project).runWhenSmart {
            scheduleRefreshProjectGraph()
        }
    }

    fun scheduleRefreshProjectGraph(delayMs: Int = 120) {
        if (project.isDisposed) return
        refreshAlarm.cancelAllRequests()
        refreshAlarm.addRequest({ if (!project.isDisposed) refreshProjectGraph() }, delayMs)
    }

    internal fun resolveFallbackProjectGraph(
        uastGraph: ArcGraph,
        basePath: String?,
        specRules: List<BoundaryRule>
    ): ArcGraph {
        if (uastGraph.nodes.isNotEmpty() || basePath == null || !File(basePath).exists()) {
            return uastGraph
        }
        val root = File(basePath)
        val specFile = findPrimarySpecFile(root)
        val specParsed = specFile?.let {
            runCatching { ArcMermaidParser.parse(it.readText(Charsets.UTF_8)) }.getOrNull()
        }
        return if (specParsed != null && specParsed.nodes.isNotEmpty()) {
            specParsed.copy(rules = (specRules + specParsed.rules).distinctBy { it.id })
        } else {
            runCatching { PolyglotRepoScanner.scanRepository(root, includeJvmSources = false) }
                .getOrDefault(uastGraph)
        }
    }

    fun refreshProjectGraph(): ArcGraph {
        val seq = refreshSeq.incrementAndGet()
        val specRules = loadRulesFromSpecFile()
        val extracted = try {
            val uastGraph = UastGraphExtractor.extractProjectGraph(project, specRules)
            resolveFallbackProjectGraph(uastGraph, project.basePath, specRules)
        } catch (pce: ProcessCanceledException) {
            throw pce
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            currentGraph
        }
        val rootDir = project.basePath?.let { File(it) }?.takeIf { it.exists() }
        val withOrgRules = if (rootDir != null) {
            runCatching { ArcDriftSpiLoader.mergeOrgRules(extracted, rootDir) }.getOrDefault(extracted)
        } else {
            extracted
        }
        val annotated = ArcRuleEngine.annotateViolations(withOrgRules, ArcRuleEngine.evaluate(withOrgRules))
        hasCompletedInitialScan.set(true)
        if (seq == refreshSeq.get() && (annotated.nodes.isNotEmpty() || currentGraph.nodes.isEmpty())) {
            updateGraph(annotated)
        }
        return currentGraph
    }

    fun updateGraph(newGraph: ArcGraph) {
        currentGraph = newGraph
        inMemoryAdapter = InMemoryGraphRefactoringAdapter(
            newGraph,
            project.basePath?.let { File(it) },
            customScanner = { UastGraphExtractor.extractProjectGraph(project, loadRulesFromSpecFile()) }
        )
        for (listener in graphListeners) {
            listener(newGraph)
        }
    }

    fun addBoundaryRuleAndSave(rule: BoundaryRule) {
        synchronized(specFileLock) {
            val updatedRules = (currentGraph.rules.filterNot { it.id == rule.id } + rule)
            val rawGraph = currentGraph.copy(rules = updatedRules)
            val nextGraph = ArcRuleEngine.annotateViolations(rawGraph, ArcRuleEngine.evaluate(rawGraph))
            updateGraph(nextGraph)

            val basePath = project.basePath ?: return
            val root = File(basePath)
            val specFile = findPrimarySpecFile(root) ?: File(root, "architecture.arc.mmd")
            runCatching {
                val existingSpec = if (specFile.exists()) {
                    ArcMermaidParser.parse(specFile.readText(Charsets.UTF_8))
                } else {
                    ArcGraph(
                        projectName = currentGraph.projectName,
                        nodes = currentGraph.nodes.filter { it.kind == NodeKind.GHOST_SPEC }
                    )
                }
                val mergedSpec = existingSpec.copy(
                    rules = (existingSpec.rules.filterNot { it.id == rule.id } + rule)
                )
                val rendered = ArcMermaidSerializer.serialize(mergedSpec)
                specFile.parentFile?.mkdirs()
                val tmp = File(specFile.parentFile, ".${specFile.name}.${System.nanoTime()}.tmp")
                try {
                    tmp.writeText(rendered, Charsets.UTF_8)
                    runCatching {
                        Files.move(tmp.toPath(), specFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                    }.onFailure {
                        Files.move(tmp.toPath(), specFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    }
                } finally {
                    if (tmp.exists()) tmp.delete()
                }
            }
        }
    }

    fun addGraphListener(listener: (ArcGraph) -> Unit) {
        graphListeners.add(listener)
    }

    fun addGraphListener(parentDisposable: Disposable, listener: (ArcGraph) -> Unit) {
        graphListeners.add(listener)
        Disposer.register(parentDisposable) {
            graphListeners.remove(listener)
        }
    }

    internal fun loadRulesFromSpecFile(basePath: String? = project.basePath): List<BoundaryRule> {
        val bp = basePath ?: return currentGraph.rules
        val root = File(bp)
        if (!root.exists()) return currentGraph.rules
        var parsedPrimary = false
        val primarySpec = findPrimarySpecFile(root)
        if (primarySpec != null) {
            val parsed = runCatching { ArcMermaidParser.parse(primarySpec.readText(Charsets.UTF_8)) }.getOrNull()
            if (parsed != null) {
                parsedPrimary = true
                if (parsed.rules.isNotEmpty()) return parsed.rules.distinctBy { it.id }
            }
        }
        val specFiles = root.listFiles { f -> f.isFile && !f.name.startsWith(".") && f.name.endsWith(".arc.mmd") }?.toList().orEmpty()
        if (specFiles.isEmpty()) return if (parsedPrimary) emptyList() else currentGraph.rules
        var parsedAny = parsedPrimary
        val collected = mutableListOf<BoundaryRule>()
        for (f in specFiles) {
            val parsed = runCatching { ArcMermaidParser.parse(f.readText(Charsets.UTF_8)) }.getOrNull()
            if (parsed != null) {
                parsedAny = true
                collected.addAll(parsed.rules)
            }
        }
        return if (parsedAny) collected.distinctBy { it.id } else currentGraph.rules
    }

    override fun dispose() {
        graphListeners.clear()
    }

    companion object {
        private val CANONICAL_SPEC_NAMES = setOf("architecture.arc.mmd", "arcdrift.arc.mmd", "spec.arc.mmd")

        fun findPrimarySpecFile(root: File): File? {
            if (!root.exists()) return null
            return root.listFiles { f -> f.isFile && !f.name.startsWith(".") && f.name.endsWith(".arc.mmd") }
                ?.sortedWith(
                    compareBy<File>(
                        { f ->
                            val lower = f.name.lowercase()
                            when {
                                lower == "architecture.arc.mmd" -> 0
                                lower in CANONICAL_SPEC_NAMES -> 1
                                lower.contains("baseline") || lower.contains("snapshot") || lower.contains("diff") -> 3
                                else -> 2
                            }
                        },
                        { -it.length() }
                    )
                )
                ?.firstOrNull()
        }

        fun getInstance(project: Project): ArcDriftProjectService =
            project.getService(ArcDriftProjectService::class.java)
    }
}
