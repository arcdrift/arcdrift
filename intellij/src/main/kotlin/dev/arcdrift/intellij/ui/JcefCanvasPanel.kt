package dev.arcdrift.intellij.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.ui.jcef.*
import com.intellij.util.Alarm
import dev.arcdrift.core.model.BoundaryRule
import dev.arcdrift.core.model.RulePolicy
import dev.arcdrift.core.refactoring.RefactoringCommand
import dev.arcdrift.core.rules.ArcRuleEngine
import dev.arcdrift.intellij.service.ArcDriftProjectService
import dev.arcdrift.mcp.agent.OpenAiCompatibleAgentClient
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.handler.CefRequestHandlerAdapter
import org.cef.handler.CefResourceHandler
import org.cef.handler.CefResourceRequestHandler
import org.cef.handler.CefResourceRequestHandlerAdapter
import org.cef.misc.BoolRef
import org.cef.network.CefRequest
import java.awt.BorderLayout
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants

/**
 * Hosts the embedded `JBCefBrowser` running the compiled `arcdrift-webview` React Flow bundle.
 * Bridges two-way IPC via `JBCefJSQuery` and delegates persistence, source navigation,
 * and asset serving to [AgentSettingsStore], [IdeSourceNavigator], and [WebviewLoopbackServer].
 */
class JcefCanvasPanel(
    private val project: Project,
    parentDisposable: Disposable,
    private val enableJcefBrowser: Boolean = !ApplicationManager.getApplication().isUnitTestMode
) : JPanel(BorderLayout()), Disposable {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Volatile
    private var browser: JBCefBrowser? = null
    @Volatile
    private var jsQuery: JBCefJSQuery? = null
    @Volatile
    private var currentAgentCancelFlag = AtomicBoolean(false)
    private val activeAgentRuns = AtomicInteger(0)
    private val agentRunSeq = AtomicLong(0)
    private val vfsRefreshAlarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)

    init {
        Disposer.register(parentDisposable, this)
        installAutoRefreshListener()
        initOrFallback()
    }

    internal fun openPrimarySpecInEditor() {
        val base = project.basePath ?: return
        val specFile = ArcDriftProjectService.findPrimarySpecFile(File(base)) ?: File(base, "architecture.arc.mmd")
        if (specFile.exists()) {
            val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(specFile)
            if (vf != null) {
                FileEditorManager.getInstance(project).openTextEditor(OpenFileDescriptor(project, vf, 0, 0), true)
            }
        }
    }

    internal fun onDebouncedVfsChange() {
        if (project.isDisposed || activeAgentRuns.get() > 0) return
        val service = ArcDriftProjectService.getInstance(project)
        service.refreshProjectGraph()
        pushGraphToWebview()
    }

    private fun installAutoRefreshListener() {
        project.messageBus.connect(this).subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    if (activeAgentRuns.get() > 0) return
                    val base = project.basePath ?: return
                    val relevant = events.any { ev ->
                        IdeSourceNavigator.shouldTriggerGraphRefresh(ev.path, base)
                    }
                    if (!relevant) return
                    vfsRefreshAlarm.cancelAllRequests()
                    vfsRefreshAlarm.addRequest({ onDebouncedVfsChange() }, 700)
                }
            }
        )
    }

    private fun initOrFallback() {
        removeAll()
        jsQuery?.let { runCatching { Disposer.dispose(it) } }
        jsQuery = null
        browser?.let { runCatching { Disposer.dispose(it) } }
        browser = null
        if (enableJcefBrowser && JBCefApp.isSupported()) {
            try {
                initJcefBrowser()
                revalidate()
                repaint()
                return
            } catch (pce: com.intellij.openapi.progress.ProcessCanceledException) {
                throw pce
            } catch (_: Throwable) {
                // Fall through to retry UI
            }
        }
        val fallbackPanel = JPanel(BorderLayout())
        val service = ArcDriftProjectService.getInstance(project)
        val graph = service.currentGraph
        val violationCount = graph.nodes.count { it.diffStatus == dev.arcdrift.core.model.DiffStatus.VIOLATION }
        val summaryHtml = buildString {
            append("<html><div style='text-align:center;padding:16px;'>")
            append("<b>ArcDrift Non-Canvas Fallback Mode</b> (JCEF embedded browser is unavailable in this IDE runtime)<br/><br/>")
            append("Nodes: <b>${graph.nodes.size}</b> &nbsp;|&nbsp; Edges: <b>${graph.edges.size}</b> &nbsp;|&nbsp; Violations: <b>$violationCount</b>")
            append("</div></html>")
        }
        val buttonBar = JPanel()
        val openSpecBtn = JButton("Open architecture.arc.mmd in Editor").apply {
            addActionListener { openPrimarySpecInEditor() }
        }
        val retryBtn = JButton("Retry JCEF Canvas Initialization").apply {
            addActionListener { initOrFallback() }
        }
        buttonBar.add(openSpecBtn)
        buttonBar.add(retryBtn)
        fallbackPanel.add(JLabel(summaryHtml, SwingConstants.CENTER), BorderLayout.CENTER)
        fallbackPanel.add(buttonBar, BorderLayout.SOUTH)
        add(fallbackPanel, BorderLayout.CENTER)
        revalidate()
        repaint()
    }

    private fun initJcefBrowser() {
        val cefBrowser = JBCefBrowser.createBuilder().build()
        browser = cefBrowser
        Disposer.register(this, cefBrowser)

        val query = JBCefJSQuery.create(cefBrowser as JBCefBrowserBase)
        jsQuery = query
        Disposer.register(this, query)

        query.addHandler { rawPayload ->
            handleIncomingWebviewMessage(rawPayload)
            null
        }

        cefBrowser.jbCefClient.addRequestHandler(
            object : CefRequestHandlerAdapter() {
                override fun getResourceRequestHandler(
                    browser: CefBrowser?,
                    frame: CefFrame?,
                    request: CefRequest?,
                    isNavigation: Boolean,
                    isDownload: Boolean,
                    requestInitiator: String?,
                    disableDefaultHandling: BoolRef?
                ): CefResourceRequestHandler? {
                    val url = request?.url ?: return null
                    if (url != ORIGIN_PREFIX && !url.startsWith("$ORIGIN_PREFIX/")) return null
                    return object : CefResourceRequestHandlerAdapter() {
                        override fun getResourceHandler(
                            browser: CefBrowser?,
                            frame: CefFrame?,
                            request: CefRequest?
                        ): CefResourceHandler = ClasspathWebviewResourceHandler()
                    }
                }
            },
            cefBrowser.cefBrowser
        )

        cefBrowser.jbCefClient.addLoadHandler(
            object : CefLoadHandlerAdapter() {
                override fun onLoadEnd(browser: CefBrowser?, frame: CefFrame?, httpStatusCode: Int) {
                    if (frame != null && !frame.isMain) return
                    val injectScript = """
                        window.__arcdriftPostMessage = function(payloadStr) {
                            ${query.inject("payloadStr")}
                        };
                        if (Array.isArray(window.__arcdriftPendingOutbound)) {
                            while (window.__arcdriftPendingOutbound.length > 0) {
                                window.__arcdriftPostMessage(window.__arcdriftPendingOutbound.shift());
                            }
                        }
                        if (window.__arcdriftReceiveMessage) {
                            window.__arcdriftPostMessage(JSON.stringify({ type: 'REQUEST_INITIAL_GRAPH' }));
                        }
                    """.trimIndent()
                    browser?.executeJavaScript(injectScript, browser.url, 0)
                    pushAgentConfigToWebview()
                    pushGraphToWebview()
                }
            },
            cefBrowser.cefBrowser
        )

        ArcDriftProjectService.getInstance(project).addGraphListener(this) {
            pushGraphToWebview()
        }

        add(cefBrowser.component, BorderLayout.CENTER)
        val loopbackUrl = WebviewLoopbackServer.ensureStarted()
        cefBrowser.loadURL(loopbackUrl ?: "$ORIGIN_PREFIX/index.html")
    }

    fun pushGraphToWebview() {
        val service = ArcDriftProjectService.getInstance(project)
        val currentGraph = service.currentGraph
        if (currentGraph.nodes.isEmpty() && !service.hasCompletedInitialScan.get()) {
            service.scheduleRefreshProjectGraph(0)
            return
        }
        val envelope = buildJsonObject {
            put("type", "GRAPH_UPDATED")
            put("graph", json.encodeToJsonElement(currentGraph))
        }
        sendEnvelopeToWebview(envelope)
    }

    private fun pushAgentConfigToWebview() {
        ApplicationManager.getApplication().executeOnPooledThread {
            if (project.isDisposed) return@executeOnPooledThread
            val settings = AgentSettingsStore.load()
            sendEnvelopeToWebview(
                buildJsonObject {
                    put("type", "AGENT_CONFIG_LOADED")
                    put("baseUrl", settings.baseUrl)
                    put("model", settings.model)
                    put("apiKey", "")
                    put("hasSavedApiKey", settings.hasSavedApiKey)
                    put("maxTurns", settings.maxTurns)
                }
            )
        }
    }

    private fun JsonElement?.asPrimitiveOrNull(): JsonPrimitive? = this as? JsonPrimitive

    internal fun handleIncomingWebviewMessage(rawPayload: String) {
        val obj = runCatching { json.parseToJsonElement(rawPayload) as? JsonObject }.getOrNull() ?: return
        val msgType = obj["type"].asPrimitiveOrNull()?.contentOrNull ?: return
        val service = ArcDriftProjectService.getInstance(project)

        when (msgType) {
            "REQUEST_INITIAL_GRAPH" -> {
                pushAgentConfigToWebview()
                ApplicationManager.getApplication().executeOnPooledThread {
                    if (project.isDisposed) return@executeOnPooledThread
                    service.refreshProjectGraph()
                    pushGraphToWebview()
                }
            }
            "SAVE_AGENT_CONFIG" -> {
                val baseUrl = obj["baseUrl"].asPrimitiveOrNull()?.contentOrNull
                val model = obj["model"].asPrimitiveOrNull()?.contentOrNull
                val apiKey = obj["apiKey"].asPrimitiveOrNull()?.contentOrNull
                val maxTurns = obj["maxTurns"].asPrimitiveOrNull()?.intOrNull
                ApplicationManager.getApplication().executeOnPooledThread {
                    if (project.isDisposed) return@executeOnPooledThread
                    AgentSettingsStore.save(
                        baseUrl = baseUrl,
                        model = model,
                        apiKey = apiKey,
                        maxTurns = maxTurns
                    )
                }
            }
            "APPLY_REFACTORING" -> handleApplyRefactoring(obj, service)
            "SAVE_SPEC_RULE" -> handleSaveSpecRule(obj, service)
            "NAVIGATE_TO_SOURCE" -> {
                IdeSourceNavigator.openSourceInEditor(
                    project = project,
                    explicitPath = obj["filePath"].asPrimitiveOrNull()?.contentOrNull,
                    nodeId = obj["nodeId"].asPrimitiveOrNull()?.contentOrNull,
                    explicitLine = obj["line"].asPrimitiveOrNull()?.intOrNull,
                    nodes = service.currentGraph.nodes
                )
            }
            "FETCH_AGENT_MODELS" -> handleFetchAgentModels(obj, service)
            "CANCEL_AGENT_RUN" -> currentAgentCancelFlag.set(true)
            "HANDOFF_TO_AGENT" -> handleHandoffToAgent(obj, service)
            "CHANGE_ZOOM_LEVEL" -> Unit
        }
    }

    private fun handleApplyRefactoring(obj: JsonObject, service: ArcDriftProjectService) {
        val previewOnly = obj["previewOnly"].asPrimitiveOrNull()?.booleanOrNull ?: false
        val cmdElement = obj["command"] ?: return
        val cmd = runCatching { json.decodeFromJsonElement<RefactoringCommand>(cmdElement) }.getOrNull() ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            if (project.isDisposed) return@executeOnPooledThread
            if (!previewOnly) {
                IdeSourceNavigator.flushUnsavedEditorDocuments(project)
            }
            if (service.currentGraph.nodes.isEmpty()) {
                service.refreshProjectGraph()
            }
            val res = service.psiRefactoringAdapter.execute(cmd, previewOnly = previewOnly)
            if (!res.success) {
                sendEnvelopeToWebview(
                    buildJsonObject {
                        put("type", "NOTIFICATION")
                        put("level", "error")
                        put("message", res.errorMessage ?: res.summary)
                    }
                )
            } else if (!previewOnly) {
                if (res.touchedFiles.isNotEmpty()) {
                    IdeSourceNavigator.syncDiskFilesIntoOpenDocuments(project, res.touchedFiles)
                    IdeSourceNavigator.refreshProjectVfsAndExplorer(project, res.touchedFiles, openPrimaryFile = true)
                }
                service.refreshProjectGraph()
            } else {
                val nextGraph = service.inMemoryAdapter.currentGraph
                if (nextGraph.nodes.isNotEmpty()) {
                    val annotated = ArcRuleEngine.annotateViolations(nextGraph, ArcRuleEngine.evaluate(nextGraph))
                    service.updateGraph(annotated)
                }
            }
            pushGraphToWebview()
        }
    }

    private fun handleSaveSpecRule(obj: JsonObject, service: ArcDriftProjectService) {
        val ruleElement = obj["rule"] ?: return
        val rule = runCatching { json.decodeFromJsonElement<BoundaryRule>(ruleElement) }.getOrNull()
        if (rule == null) {
            sendEnvelopeToWebview(
                buildJsonObject {
                    put("type", "NOTIFICATION")
                    put("level", "error")
                    put("message", "Invalid boundary rule specification payload.")
                }
            )
            return
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            if (project.isDisposed) return@executeOnPooledThread
            service.addBoundaryRuleAndSave(rule)
            val savedSpecPath = project.basePath?.let { bp ->
                ArcDriftProjectService.findPrimarySpecFile(File(bp))?.absolutePath
            } ?: "architecture.arc.mmd"
            IdeSourceNavigator.refreshProjectVfsAndExplorer(project, listOf(savedSpecPath), openPrimaryFile = false)
            pushGraphToWebview()
        }
    }

    private fun handleFetchAgentModels(obj: JsonObject, service: ArcDriftProjectService) {
        val explicitBaseUrl = obj["baseUrl"].asPrimitiveOrNull()?.contentOrNull
        val explicitApiKey = obj["apiKey"].asPrimitiveOrNull()?.contentOrNull?.takeIf { it.isNotBlank() }
        ApplicationManager.getApplication().executeOnPooledThread {
            if (project.isDisposed) return@executeOnPooledThread
            val resolved = AgentSettingsStore.resolveForRequest(
                explicitBaseUrl = explicitBaseUrl,
                explicitApiKey = explicitApiKey
            )
            AgentSettingsStore.save(baseUrl = explicitBaseUrl, apiKey = explicitApiKey)
            val client = createAgentClient(service, preferUnsavedDocs = false)
            val result = client.fetchModels(resolved.baseUrl, resolved.apiKey.ifBlank { null })
            val envelope = buildJsonObject {
                put("type", "AGENT_MODELS_LOADED")
                put("baseUrl", resolved.baseUrl)
                putJsonArray("models") {
                    result.getOrDefault(emptyList()).forEach { add(JsonPrimitive(it)) }
                }
                result.exceptionOrNull()?.message?.let { put("error", it) }
            }
            sendEnvelopeToWebview(envelope)
        }
    }

    private fun handleHandoffToAgent(obj: JsonObject, service: ArcDriftProjectService) {
        val explicitBaseUrl = obj["agentBaseUrl"].asPrimitiveOrNull()?.contentOrNull
        val explicitModel = obj["agentModel"].asPrimitiveOrNull()?.contentOrNull
        val explicitApiKey = obj["agentApiKey"].asPrimitiveOrNull()?.contentOrNull?.takeIf { it.isNotBlank() }
        val explicitMaxTurns = obj["maxTurns"].asPrimitiveOrNull()?.intOrNull
        val specPrompt = obj["specPrompt"].asPrimitiveOrNull()?.contentOrNull
            ?: "Resolve all architectural violations and verify compliance."

        currentAgentCancelFlag.set(true)
        val cancelFlag = AtomicBoolean(false)
        currentAgentCancelFlag = cancelFlag
        val runId = agentRunSeq.incrementAndGet()

        ApplicationManager.getApplication().executeOnPooledThread {
            if (project.isDisposed) return@executeOnPooledThread
            activeAgentRuns.incrementAndGet()
            try {
                val resolved = AgentSettingsStore.resolveForRequest(
                    explicitBaseUrl = explicitBaseUrl,
                    explicitModel = explicitModel,
                    explicitApiKey = explicitApiKey,
                    explicitMaxTurns = explicitMaxTurns
                )
                AgentSettingsStore.save(
                    baseUrl = explicitBaseUrl,
                    model = explicitModel,
                    apiKey = explicitApiKey,
                    maxTurns = explicitMaxTurns
                )
                IdeSourceNavigator.flushUnsavedEditorDocuments(project)
                val client = createAgentClient(service, preferUnsavedDocs = true)
                val report = client.runAgentLoop(
                    rawUrl = resolved.baseUrl,
                    model = resolved.model,
                    taskPrompt = specPrompt,
                    apiKey = resolved.apiKey.ifBlank { null },
                    maxTurns = resolved.maxTurns,
                    cancelFlag = cancelFlag,
                    onStepEvent = { ev ->
                        if (runId == agentRunSeq.get() && !project.isDisposed) {
                            sendEnvelopeToWebview(
                                buildJsonObject {
                                    put("type", "AGENT_STEP")
                                    put("step", json.encodeToJsonElement(ev))
                                }
                            )
                        }
                    },
                    onFileMutated = { mutatedFile ->
                        if (runId == agentRunSeq.get() && !project.isDisposed) {
                            handleAgentFileMutation(mutatedFile, service)
                        }
                    }
                )
                if (runId == agentRunSeq.get() && !project.isDisposed) {
                    if (report.model.isNotBlank() && report.model != "default") {
                        AgentSettingsStore.save(
                            baseUrl = explicitBaseUrl,
                            model = report.model,
                            apiKey = explicitApiKey,
                            maxTurns = explicitMaxTurns
                        )
                    }
                    service.refreshProjectGraph()
                    pushGraphToWebview()
                }
            } finally {
                activeAgentRuns.decrementAndGet()
            }
        }
    }

    internal fun createAgentClient(service: ArcDriftProjectService, preferUnsavedDocs: Boolean): OpenAiCompatibleAgentClient {
        return OpenAiCompatibleAgentClient(
            mcpServer = service.mcpServer,
            projectRoot = project.basePath?.let { File(it) },
            fileReader = { ioFile -> readProjectFileWithDocumentOverride(ioFile, preferUnsavedDocs) }
        )
    }

    internal fun readProjectFileWithDocumentOverride(ioFile: File, preferUnsavedDocs: Boolean): String {
        return com.intellij.openapi.application.ReadAction.compute<String, RuntimeException> {
            val vf = VfsUtil.findFileByIoFile(ioFile, false)
            val doc = vf?.let { FileDocumentManager.getInstance().getCachedDocument(it) }
            if (doc != null && (!preferUnsavedDocs || FileDocumentManager.getInstance().isDocumentUnsaved(doc))) {
                doc.text
            } else {
                ioFile.readText(Charsets.UTF_8)
            }
        }
    }

    internal fun handleAgentFileMutation(mutatedFile: File, service: ArcDriftProjectService) {
        IdeSourceNavigator.syncDiskFilesIntoOpenDocuments(project, listOf(mutatedFile.absolutePath))
        IdeSourceNavigator.refreshProjectVfsAndExplorer(project, listOf(mutatedFile.absolutePath), openPrimaryFile = false)
        service.refreshProjectGraph()
        pushGraphToWebview()
    }

    internal fun formatEnvelopeScript(envelope: JsonObject): String {
        val safeJson = json.encodeToString(envelope)
            .replace("\u2028", "\\u2028")
            .replace("\u2029", "\\u2029")
            .replace("</script", "<\\/script")
        return "(function(){ var m = $safeJson; if (window.__arcdriftReceiveMessage) { window.__arcdriftReceiveMessage(m); } else { window.__arcdriftPendingInbound = window.__arcdriftPendingInbound || []; window.__arcdriftPendingInbound.push(m); } })();"
    }

    private fun sendEnvelopeToWebview(envelope: JsonObject) {
        val script = formatEnvelopeScript(envelope)
        val b = browser?.cefBrowser ?: return
        b.executeJavaScript(
            script,
            b.url,
            0
        )
        ApplicationManager.getApplication().invokeLater {
            browser?.component?.repaint()
            repaint()
        }
    }

    override fun dispose() {
        currentAgentCancelFlag.set(true)
        browser = null
        jsQuery = null
    }

    companion object {
        const val ORIGIN_PREFIX = "http://arcdrift.local"
    }
}
