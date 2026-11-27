package dev.arcdrift.intellij.psi

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.arcdrift.core.model.BoundaryRule
import dev.arcdrift.core.model.NodeKind
import dev.arcdrift.core.model.RulePolicy
import dev.arcdrift.core.refactoring.RefactoringCommand
import dev.arcdrift.intellij.service.ArcDriftProjectService

class IntellijPsiExtractorAndRefactoringTest : BasePlatformTestCase() {

    fun testUastGraphExtractorAndPsiRefactoringRoundtrip() {
        val repoFile = myFixture.addFileToProject(
            "com/acme/orders/OrderRepository.java",
            """
            package com.acme.orders;
            public interface OrderRepository {
                String findOrderById(String id);
            }
            """.trimIndent()
        )
        val svcFile = myFixture.addFileToProject(
            "com/acme/orders/OrderService.java",
            """
            package com.acme.orders;
            public class OrderService {
                private final OrderRepository repository;
                public OrderService(OrderRepository repository) {
                    this.repository = repository;
                }
                public String loadOrder(String id) {
                    return repository.findOrderById(id);
                }
            }
            """.trimIndent()
        )

        val graph = UastGraphExtractor.extractFromPsiFiles(
            projectName = project.name,
            psiFiles = listOf(repoFile, svcFile),
            existingRules = listOf(
                BoundaryRule(
                    id = "no-ctrl-to-repo",
                    name = "No Controller to Repo",
                    fromPattern = "*.controller.*",
                    toPattern = "*.repository.*",
                    policy = RulePolicy.FORBIDDEN
                )
            )
        )

        val nodeMap = graph.nodeMap()
        assertTrue("com.acme.orders.OrderRepository" in nodeMap)
        assertTrue("com.acme.orders.OrderService" in nodeMap)
        assertEquals(NodeKind.REPOSITORY, nodeMap.getValue("com.acme.orders.OrderRepository").kind)
        assertTrue(graph.edges.any {
            it.sourceId == "com.acme.orders.OrderService" &&
                it.targetId == "com.acme.orders.OrderRepository"
        })

        // Exercise ArcDriftProjectService + IntellijPsiRefactoringAdapter
        val service = ArcDriftProjectService.getInstance(project)
        service.updateGraph(graph)

        val addDepResult = service.psiRefactoringAdapter.execute(
            RefactoringCommand.AddDependency(
                sourceClassId = "com.acme.orders.OrderService",
                targetTypeFqName = "java.io.Serializable",
                relationKind = "IMPLEMENTS"
            ),
            previewOnly = false
        )
        assertTrue(addDepResult.success)
        assertTrue(svcFile.text.contains("Serializable"))
    }

    fun testUastGraphExtractorPropagatesJavaSuperclassDtoFieldsAndNestedClassFqIds() {
        val baseDtoFile = myFixture.addFileToProject(
            "com/acme/orders/BaseAuditDto.java",
            """
            package com.acme.orders;
            public class BaseAuditDto {
                private String createdBy;
            }
            """.trimIndent()
        )
        val childDtoFile = myFixture.addFileToProject(
            "com/acme/orders/OrderDetailDto.java",
            """
            package com.acme.orders;
            public class OrderDetailDto extends BaseAuditDto {
                private String orderId;
            }
            """.trimIndent()
        )
        val ctrlWithNestedFile = myFixture.addFileToProject(
            "com/acme/orders/OrderResource.java",
            """
            package com.acme.orders;
            import org.springframework.web.bind.annotation.RestController;
            import org.springframework.web.bind.annotation.GetMapping;

            @RestController
            public class OrderResource {
                @GetMapping("/api/orders/{id}")
                public OrderDetailDto getOrder(String id) {
                    return null;
                }

                public static class NestedHelper {
                    public void help() {}
                }
            }
            """.trimIndent()
        )

        val graph = UastGraphExtractor.extractFromPsiFiles(
            projectName = project.name,
            psiFiles = listOf(baseDtoFile, childDtoFile, ctrlWithNestedFile),
            existingRules = emptyList()
        )
        val nodeMap = graph.nodeMap()
        assertTrue("com.acme.orders.OrderResource" in nodeMap)
        assertTrue("com.acme.orders.OrderResource.NestedHelper" in nodeMap)

        val ctrl = nodeMap.getValue("com.acme.orders.OrderResource")
        assertEquals(1, ctrl.contracts.size)
        val dtoFields = ctrl.contracts.first().dtoFields
        assertEquals("String", dtoFields["orderId"])
        assertEquals("String", dtoFields["createdBy"])
    }

    fun testKotlinPsiExtractionBoundaryInspectionRefactoringAndCanvasBridge() {
        val repoFile = myFixture.addFileToProject(
            "com/acme/orders/repository/OrderRepository.java",
            """
            package com.acme.orders.repository;
            import org.springframework.stereotype.Repository;
            @Repository
            public class OrderRepository {
                public String findById(String id) { return id; }
            }
            """.trimIndent()
        )
        val ktCtrlFile = myFixture.addFileToProject(
            "com/acme/orders/controller/KotlinOrderController.kt",
            """
            package com.acme.orders.controller
            import com.acme.orders.repository.OrderRepository
            import com.acme.orders.repository.*
            import com.acme.orders.repository.OrderRepository as RepoAlias
            import org.springframework.web.bind.annotation.RestController
            import org.springframework.web.bind.annotation.GetMapping

            open class BaseKotlinDto(val createdBy: String = "system")
            data class ChildKotlinDto(val orderId: String) : BaseKotlinDto()

            @RestController
            class KotlinOrderController(val repo: OrderRepository) {
                val defaultTag = "v1"
                val maxItems = 100L
                val enabled = true
                val ratio = 1.5
                val count = 10
                val listProp = listOf("a")
                val setProp = setOf("b")
                val mapProp = mapOf("k" to "v")

                constructor(repo: OrderRepository, tag: String) : this(repo)

                @GetMapping(path = ["/v1/kt-orders", "/v2/kt-orders"])
                fun list(): ChildKotlinDto = ChildKotlinDto(repo.findById("1"))

                fun wrapped() = ResponseEntity.ok(ChildKotlinDto("2"))

                fun refs() {
                    val fnRef = OrderRepository::findById
                    val clsRef = OrderRepository::class
                    kafkaTemplate.send("orders.events", "evt")
                    val q = com.acme.orders.repository.OrderRepository()
                }

                class InnerKotlinHelper {
                    private fun helper() = "ok"
                    class DeepNestedKotlinHelper {
                        fun deep() = "deep"
                    }
                }

                companion object {
                    const val COMPANION_CONST = "v1"
                    fun companionFactory(): String = COMPANION_CONST
                }
            }
            """.trimIndent()
        )
        val ktTopLevelRoutesFile = myFixture.addFileToProject(
            "com/acme/orders/controller/KtorRoutes.kt",
            """
            package com.acme.orders.controller
            import com.acme.orders.repository.OrderRepository

            val routePrefix = "/v1"

            fun registerOrderRoutes() {
                get("/v1/ktor-orders") {
                    call.respond(ChildKotlinDto("1"))
                }
            }
            """.trimIndent()
        )
        val javaCtrlFile = myFixture.addFileToProject(
            "com/acme/orders/controller/JavaOrderController.java",
            """
            package com.acme.orders.controller;
            import com.acme.orders.repository.OrderRepository;
            import com.fasterxml.jackson.annotation.JsonProperty;
            import org.springframework.web.bind.annotation.*;

            @RestController
            @RequestMapping("/v1/java-orders")
            @KafkaListener(topics = "class.orders.topic")
            public class JavaOrderController {
                @JsonProperty("wire_repo")
                private final OrderRepository repo;
                public JavaOrderController(OrderRepository repo) {
                    this.repo = repo;
                }
                @PostMapping("/create")
                public String create(@RequestBody String body) {
                    return repo.findById(body);
                }
                @PutMapping("/{id}")
                public String update(String id) { return id; }
                @DeleteMapping("/{id}")
                public void delete(String id) {}
                @PatchMapping("/{id}")
                public String patch(String id) { return id; }
                @RequestMapping(value = "/rm-post", method = RequestMethod.POST)
                public String rmPost() { return "ok"; }
                @RequestMapping(value = "/rm-put", method = RequestMethod.PUT)
                public String rmPut() { return "ok"; }
                @RequestMapping(value = "/rm-del", method = RequestMethod.DELETE)
                public String rmDel() { return "ok"; }
                @RequestMapping(value = "/rm-patch", method = RequestMethod.PATCH)
                public String rmPatch() { return "ok"; }
                @KafkaListener(topics = "orders.topic")
                public void onEvent(String evt) {}
            }
            """.trimIndent()
        )

        val rule = BoundaryRule(
            id = "no-ctrl-to-repo",
            name = "Controllers must not depend on Repositories",
            fromPattern = "*.controller.*",
            toPattern = "*.repository.*",
            policy = RulePolicy.FORBIDDEN,
            rationale = "Use service layer"
        )

        val service = ArcDriftProjectService.getInstance(project)
        val ktParsed = KtPsiFileExtractor.parseKtFile(ktCtrlFile as org.jetbrains.kotlin.psi.KtFile)
        assertTrue(ktParsed.classes.any { it.simpleName == "KotlinOrderController" })
        assertTrue(ktParsed.classes.any { it.fqId == "com.acme.orders.controller.KotlinOrderController.InnerKotlinHelper.DeepNestedKotlinHelper" })
        val ktCtrlAst = ktParsed.classes.first { it.simpleName == "KotlinOrderController" }
        assertTrue(ktCtrlAst.methods.any { it.name == "companionFactory" })
        val ktorParsed = KtPsiFileExtractor.parseKtFile(ktTopLevelRoutesFile as org.jetbrains.kotlin.psi.KtFile)
        assertEquals(1, ktorParsed.classes.size)

        val extracted = UastGraphExtractor.extractFromPsiFiles(
            projectName = project.name,
            psiFiles = listOf(repoFile, ktCtrlFile, ktTopLevelRoutesFile, javaCtrlFile),
            existingRules = listOf(rule)
        )
        service.updateGraph(extracted)
        assertTrue("com.acme.orders.controller.KotlinOrderController" in service.currentGraph.nodeMap())
        assertTrue("com.acme.orders.controller.JavaOrderController" in service.currentGraph.nodeMap())

        // 1. Test ArcBoundaryInspection metadata and visitor evaluation on Java and Kotlin PSI files
        val inspection = dev.arcdrift.intellij.inspection.ArcBoundaryInspection()
        assertEquals("ArcBoundaryViolation", inspection.shortName)
        assertEquals("ArcDrift Architecture", inspection.groupDisplayName)
        assertTrue(inspection.displayName.contains("ArcDrift"))
        assertTrue(inspection.isEnabledByDefault)

        service.updateGraph(extracted)
        val inspectionManager = com.intellij.codeInspection.InspectionManager.getInstance(project)
        val javaHolder = com.intellij.codeInspection.ProblemsHolder(inspectionManager, javaCtrlFile, true)
        val javaVisitor = inspection.buildVisitor(javaHolder, true)
        javaCtrlFile.accept(object : com.intellij.psi.PsiRecursiveElementWalkingVisitor() {
            override fun visitElement(element: com.intellij.psi.PsiElement) {
                super.visitElement(element)
                javaVisitor.visitElement(element)
            }
        })
        assertTrue(javaHolder.results.any { it.descriptionTemplate.contains("no-ctrl-to-repo") })

        service.updateGraph(extracted)
        val ktHolder = com.intellij.codeInspection.ProblemsHolder(inspectionManager, ktCtrlFile, true)
        val ktVisitor = inspection.buildVisitor(ktHolder, true)
        ktCtrlFile.accept(object : com.intellij.psi.PsiRecursiveElementWalkingVisitor() {
            override fun visitElement(element: com.intellij.psi.PsiElement) {
                super.visitElement(element)
                ktVisitor.visitElement(element)
            }
        })
        assertTrue(ktHolder.results.any { it.descriptionTemplate.contains("no-ctrl-to-repo") })

        // 2. Test IntellijPsiRefactoringAdapter in-memory PSI operations BEFORE creating baseDir on disk
        val baseDir = project.basePath?.let { java.io.File(it) }
        baseDir?.deleteRecursively()

        val adapter = service.psiRefactoringAdapter
        assertTrue(
            adapter.execute(
                RefactoringCommand.RenameSymbol("com.acme.orders.repository.OrderRepository", "PreviewRepo"),
                previewOnly = true
            ).success
        )
        assertTrue(
            adapter.execute(
                RefactoringCommand.AddDependency(
                    sourceClassId = "com.acme.orders.repository.OrderRepository",
                    targetTypeFqName = "java.util.ArrayList",
                    relationKind = "EXTENDS"
                ),
                previewOnly = false
            ).success
        )
        assertTrue(
            adapter.execute(
                RefactoringCommand.AddDependency(
                    sourceClassId = "com.acme.orders.repository.OrderRepository",
                    targetTypeFqName = "java.lang.String",
                    relationKind = "DEPENDS_ON"
                ),
                previewOnly = false
            ).success
        )
        assertTrue(
            adapter.execute(
                RefactoringCommand.CreateNode("com.acme.orders.repository.OrderPort", "INTERFACE"),
                previewOnly = false
            ).success
        )
        assertTrue(
            adapter.execute(
                RefactoringCommand.CreateNode("com.acme.orders.repository.OrderHelper", "CLASS"),
                previewOnly = false
            ).success
        )
        assertTrue(
            adapter.execute(
                RefactoringCommand.ExtractInterface(
                    sourceClassId = "com.acme.orders.repository.OrderRepository",
                    interfaceSimpleName = "ExtractedRepoPort",
                    targetPackage = "com.acme.orders.repository",
                    memberNames = listOf("findById")
                ),
                previewOnly = false
            ).success
        )
        assertTrue(
            adapter.execute(
                RefactoringCommand.MoveClassOrPackage(
                    targetNodeId = "com.acme.orders.repository.OrderHelper",
                    destinationPackage = "com.acme.orders.shared"
                ),
                previewOnly = false
            ).success
        )
        val javaRenameRes = adapter.execute(
            RefactoringCommand.RenameSymbol(
                targetNodeId = "com.acme.orders.controller.JavaOrderController",
                newSimpleName = "RenamedJavaOrderController"
            ),
            previewOnly = false
        )
        assertTrue(javaRenameRes.success)
        assertTrue(javaRenameRes.touchedFiles.isNotEmpty())

        // Now create baseDir on disk to exercise disk-backed refreshProjectGraph, openDocumentOverrides, and agent handoff
        if (baseDir != null) {
            baseDir.mkdirs()
            java.io.File(baseDir, "architecture.arc.mmd").writeText(
                """
                flowchart TB
                    %% @arc:project name="${project.name}"
                    %% @arc:rule id="no-ctrl-to-repo" name="No Ctrl to Repo" from="*.controller.*" to="*.repository.*" policy="FORBIDDEN"
                    %% @arc:node id="com.acme.orders.controller.KotlinOrderController" kind="REST_CONTROLLER" level="L3_COMPONENT" x="120.0" y="80.0"
                    com_acme_orders_controller_KotlinOrderController["KotlinOrderController"]
                """.trimIndent()
            )
            java.io.File(baseDir, "src/main/kotlin/com/acme/disk/DiskService.kt").apply {
                parentFile.mkdirs()
                writeText("package com.acme.disk\nclass DiskService {\n  fun ping(): String = \"pong\"\n}")
            }
        }
        val projectGraph = service.refreshProjectGraph()
        assertTrue(projectGraph.nodes.isNotEmpty())

        // 3. Test ArcDriftProjectService listeners, rules, MCP server, and IdeSourceNavigator
        var listenerCount = 0
        service.addGraphListener(testRootDisposable) { listenerCount++ }
        service.addBoundaryRuleAndSave(rule)
        assertTrue(listenerCount >= 1)
        val mcpResp = service.mcpServer.handleJsonRpcLine("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""")
        assertNotNull(mcpResp)
        assertTrue(mcpResp!!.contains("verify_architectural_compliance"))

        dev.arcdrift.intellij.ui.IdeSourceNavigator.flushUnsavedEditorDocuments(project)
        dev.arcdrift.intellij.ui.IdeSourceNavigator.openSourceInEditor(
            project = project,
            explicitPath = null,
            nodeId = "com.acme.orders.repository.OrderRepository",
            explicitLine = 2,
            nodes = service.currentGraph.nodes
        )

        // 4. Test JcefCanvasPanel message dispatchers (including FETCH_AGENT_MODELS and HANDOFF_TO_AGENT against local mock server)
        val mockAgentServer = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        try {
            mockAgentServer.createContext("/v1/models") { ex ->
                val b = """{"data":[{"id":"qwen2.5-coder:32b"}]}""".toByteArray(Charsets.UTF_8)
                ex.sendResponseHeaders(200, b.size.toLong())
                ex.responseBody.use { it.write(b) }
            }
            val chatTurns = java.util.concurrent.atomic.AtomicInteger(0)
            mockAgentServer.createContext("/v1/chat/completions") { ex ->
                val turn = chatTurns.incrementAndGet()
                val body = if (turn == 1) {
                    """
                    {
                      "choices": [{
                        "message": {
                          "role": "assistant",
                          "content": null,
                          "tool_calls": [
                            {
                              "id": "call_read",
                              "type": "function",
                              "function": {
                                "name": "read_project_file",
                                "arguments": "{\"path\":\"architecture.arc.mmd\"}"
                              }
                            },
                            {
                              "id": "call_write",
                              "type": "function",
                              "function": {
                                "name": "write_project_file",
                                "arguments": "{\"path\":\"architecture.arc.mmd\",\"content\":\"graph TD\\n  A --> B\"}"
                              }
                            }
                          ]
                        }
                      }]
                    }
                    """.trimIndent()
                } else {
                    """
                    {
                      "choices": [{
                        "message": {
                          "role": "assistant",
                          "content": "Verified `architecture.arc.mmd`."
                        }
                      }]
                    }
                    """.trimIndent()
                }
                val b = body.toByteArray(Charsets.UTF_8)
                ex.sendResponseHeaders(200, b.size.toLong())
                ex.responseBody.use { it.write(b) }
            }
            mockAgentServer.start()
            val mockUrl = "http://127.0.0.1:${mockAgentServer.address.port}"

            val panel = dev.arcdrift.intellij.ui.JcefCanvasPanel(project, testRootDisposable)
            val script = panel.formatEnvelopeScript(
                kotlinx.serialization.json.buildJsonObject {
                    put("type", kotlinx.serialization.json.JsonPrimitive("TEST"))
                }
            )
            assertTrue(script.contains("window.__arcdriftReceiveMessage"))
            panel.pushGraphToWebview()
            panel.handleIncomingWebviewMessage("""{"type":"REQUEST_INITIAL_GRAPH"}""")
            panel.handleIncomingWebviewMessage("""{"type":"SAVE_AGENT_CONFIG","baseUrl":"$mockUrl","model":"qwen2.5-coder:32b","apiKey":"sk-test","maxTurns":3}""")
            panel.handleIncomingWebviewMessage("""{"type":"FETCH_AGENT_MODELS","baseUrl":"$mockUrl","apiKey":"sk-test"}""")
            panel.handleIncomingWebviewMessage("""{"type":"HANDOFF_TO_AGENT","agentBaseUrl":"$mockUrl","agentModel":"qwen2.5-coder:32b","agentApiKey":"sk-test","specPrompt":"Verify","maxTurns":3}""")
            panel.handleIncomingWebviewMessage("""{"type":"SAVE_SPEC_RULE","rule":{"id":"r2","name":"R2","fromPattern":"*.a.*","toPattern":"*.b.*","policy":"FORBIDDEN","rationale":"test"}}""")
            panel.handleIncomingWebviewMessage("""{"type":"SAVE_SPEC_RULE","rule":{}}""")
            panel.handleIncomingWebviewMessage("""{"type":"APPLY_REFACTORING","previewOnly":true,"command":{"type":"CREATE_NODE","fqName":"com.acme.Ghost","kind":"GHOST_SPEC"}}""")
            panel.handleIncomingWebviewMessage("""{"type":"APPLY_REFACTORING","previewOnly":false,"command":{"type":"CREATE_NODE","fqName":"com.acme.GhostApplied","kind":"GHOST_SPEC"}}""")
            panel.handleIncomingWebviewMessage("""{"type":"NAVIGATE_TO_SOURCE","nodeId":"com.acme.orders.repository.OrderRepository","line":1}""")
            panel.handleIncomingWebviewMessage("""{"type":"CHANGE_ZOOM_LEVEL","level":"L2_CONTAINER"}""")

            // Wait for background agent loop turns to complete before testing cancel
            val deadline = System.currentTimeMillis() + 3000L
            while (chatTurns.get() < 2 && System.currentTimeMillis() < deadline) {
                Thread.sleep(50)
            }
            panel.handleIncomingWebviewMessage("""{"type":"CANCEL_AGENT_RUN"}""")

            assertEquals("OrderDto", SpringHiltSemanticEnricher.unwrapDtoName("ResponseEntity<List<OrderDto?>>"))
            assertEquals("OrderDto", SpringHiltSemanticEnricher.unwrapDtoName("ResponseEntity<Array<OrderDto?>>"))
            assertEquals("CustomBox", SpringHiltSemanticEnricher.unwrapDtoName("CustomBox<OrderDto>"))

            // Verify KtPsiFileExtractor excludes companion/const fields and preserves suspend modifier, and UastGraphExtractor scopes multi-class imports & ignores external Logger shadowing
            val ktMulti = com.intellij.psi.PsiFileFactory.getInstance(project).createFileFromText(
                "MultiAndDto.kt",
                org.jetbrains.kotlin.idea.KotlinFileType.INSTANCE,
                """
                package com.acme.demo
                import org.slf4j.Logger
                data class InvoiceDto(val invoiceId: String) {
                    companion object {
                        const val VERSION = "v1"
                    }
                }
                open class BaseWorker
                class InvoiceWorker : BaseWorker() {
                    private val log: Logger? = null
                    suspend fun load(): InvoiceDto = InvoiceDto("1")
                }
                """.trimIndent()
            ) as org.jetbrains.kotlin.psi.KtFile
            val ktInternalLogger = com.intellij.psi.PsiFileFactory.getInstance(project).createFileFromText(
                "Logger.kt",
                org.jetbrains.kotlin.idea.KotlinFileType.INSTANCE,
                "package com.acme.internal\nclass Logger"
            )
            val parsedKt = KtPsiFileExtractor.parseKtFile(ktMulti)
            val dtoAst = parsedKt.classes.first { it.simpleName == "InvoiceDto" }
            assertTrue("invoiceId" in dtoAst.fields)
            assertFalse("VERSION" in dtoAst.fields)
            assertTrue(parsedKt.classes.first { it.simpleName == "InvoiceWorker" }.methods.single().isSuspend)

            val multiGraph = UastGraphExtractor.extractFromPsiFiles(project.name, listOf(ktMulti, ktInternalLogger))
            assertFalse(multiGraph.edges.any { it.sourceId == "com.acme.demo.InvoiceWorker" && it.targetId == "com.acme.internal.Logger" })
            assertTrue(multiGraph.edges.any { it.sourceId == "com.acme.demo.InvoiceWorker" && it.targetId == "com.acme.demo.BaseWorker" && it.kind == dev.arcdrift.core.model.EdgeKind.EXTENDS })

            // Verify IntellijPsiRefactoringAdapter rejects invalid identifiers upfront & handles duplicate-safe PSI edits
            val badRename = service.psiRefactoringAdapter.execute(
                dev.arcdrift.core.refactoring.RefactoringCommand.RenameSymbol("com.acme.orders.repository.OrderRepository", "class"),
                previewOnly = false
            )
            assertFalse(badRename.success)
            val badDep = service.psiRefactoringAdapter.execute(
                dev.arcdrift.core.refactoring.RefactoringCommand.AddDependency("com.acme.orders.repository.OrderRepository", "com.bad.class", "DEPENDS_ON"),
                previewOnly = false
            )
            assertFalse(badDep.success)
            val moveRes = service.psiRefactoringAdapter.execute(
                dev.arcdrift.core.refactoring.RefactoringCommand.MoveClassOrPackage("com.acme.orders.nonexistent.Missing", "com.acme.moved"),
                previewOnly = false
            )
            assertFalse(moveRes.success)
            val dupImpl1 = service.psiRefactoringAdapter.execute(
                dev.arcdrift.core.refactoring.RefactoringCommand.AddDependency("com.acme.orders.repository.OrderRepository", "java.io.Serializable", "IMPLEMENTS"),
                previewOnly = false
            )
            assertTrue(dupImpl1.success)
            val dupImpl2 = service.psiRefactoringAdapter.execute(
                dev.arcdrift.core.refactoring.RefactoringCommand.AddDependency("com.acme.orders.repository.OrderRepository", "java.io.Serializable", "IMPLEMENTS"),
                previewOnly = false
            )
            assertTrue(dupImpl2.success)
            val dupExt = service.psiRefactoringAdapter.execute(
                dev.arcdrift.core.refactoring.RefactoringCommand.AddDependency("com.acme.orders.repository.OrderRepository", "java.lang.Object", "EXTENDS"),
                previewOnly = false
            )
            assertTrue(dupExt.success)
            val ctorInjectRes = service.psiRefactoringAdapter.execute(
                dev.arcdrift.core.refactoring.RefactoringCommand.AddDependency("com.acme.orders.repository.OrderRepository", "com.acme.orders.service.OrderService", "CONSTRUCTOR_INJECT"),
                previewOnly = false
            )
            assertTrue(ctorInjectRes.success)
            panel.handleIncomingWebviewMessage("""{"type":"APPLY_REFACTORING","previewOnly":false,"command":{"type":"RENAME_SYMBOL","targetNodeId":"com.acme.orders.repository.OrderRepository","newSimpleName":"class"}}""")

            // Verify runPsiSafely rethrows ProcessCanceledException & CancellationException while catching normal exceptions
            assertTrue(runPsiSafely { error("normal error") }.isFailure)
            try {
                runPsiSafely { throw com.intellij.openapi.progress.ProcessCanceledException() }
                fail("Expected ProcessCanceledException to be rethrown")
            } catch (_: com.intellij.openapi.progress.ProcessCanceledException) {
                // expected
            }
            try {
                runPsiSafely { throw java.util.concurrent.CancellationException("cancelled") }
                fail("Expected CancellationException to be rethrown")
            } catch (_: java.util.concurrent.CancellationException) {
                // expected
            }

            // Verify SpringHiltSemanticEnricher extracts multi-queue @RabbitListener, @JsonProperty, and trims trailing slash on empty @GetMapping
            val javaListenerFile = com.intellij.psi.PsiFileFactory.getInstance(project).createFileFromText(
                "BillingApi.java",
                com.intellij.ide.highlighter.JavaFileType.INSTANCE,
                """
                package com.acme.billing;
                import org.springframework.web.bind.annotation.*;
                import org.springframework.amqp.rabbit.annotation.RabbitListener;
                import com.fasterxml.jackson.annotation.JsonProperty;

                @RestController
                @RequestMapping("/api/invoices")
                @RabbitListener(queues = {"billing.q1", "billing.q2"})
                public class BillingApi {
                    public static final String IGNORED_STATIC = "x";
                    @JsonProperty(value = "invoice_id")
                    public String invoiceId;

                    @GetMapping
                    public BillingApi listAll() { return this; }

                    @RequestMapping(value = "/{id}", method = RequestMethod.DELETE)
                    public void deleteOne() {}

                    @RequestMapping(value = "/{id}", method = RequestMethod.PATCH)
                    public void patchViaRequestMapping() {}

                    @RequestMapping(value = "/{id}", method = RequestMethod.PUT)
                    public void putViaRequestMapping() {}
                }
                """.trimIndent()
            )
            val billingGraph = UastGraphExtractor.extractFromPsiFiles(project.name, listOf(javaListenerFile))
            val billingNode = billingGraph.nodes.first { it.id == "com.acme.billing.BillingApi" }
            assertTrue(billingNode.contracts.any { it.protocol == "REST" && it.pathOrTopic == "/api/invoices" })
            assertTrue(billingNode.contracts.any { it.protocol == "KAFKA" && it.pathOrTopic == "billing.q1" })
            assertTrue(billingNode.contracts.any { it.protocol == "KAFKA" && it.pathOrTopic == "billing.q2" })

            // Verify ArcBoundaryInspection checks annotated KtTypeReference and wildcard imports without shadowing external Logger
            val ktInspectedFile = myFixture.addFileToProject(
                "src/main/kotlin/com/acme/orders/controller/OrderApiController.kt",
                """
                package com.acme.orders.controller
                import org.slf4j.Logger
                import com.acme.orders.repository.*
                class OrderApiController(val repo: @Suppress("UNUSED") OrderRepository, val log: Logger?)
                """.trimIndent()
            )
            val problems = mutableListOf<String>()
            val holder = com.intellij.codeInspection.ProblemsHolder(
                com.intellij.codeInspection.InspectionManager.getInstance(project),
                ktInspectedFile,
                false
            )
            val visitor = dev.arcdrift.intellij.inspection.ArcBoundaryInspection().buildVisitor(holder, false)
            ktInspectedFile.accept(object : com.intellij.psi.PsiRecursiveElementVisitor() {
                override fun visitElement(element: com.intellij.psi.PsiElement) {
                    visitor.visitElement(element)
                    super.visitElement(element)
                }
            })
            problems.addAll(holder.results.map { it.descriptionTemplate })
            assertTrue("Expected ArcBoundaryInspection to flag forbidden repository reference", problems.isNotEmpty())

            // Non-JVM file returns EMPTY_VISITOR cleanly and nonexistent spec root returns null
            val plainFile = myFixture.addFileToProject("notes.txt", "plain text")
            val plainHolder = com.intellij.codeInspection.ProblemsHolder(
                com.intellij.codeInspection.InspectionManager.getInstance(project),
                plainFile,
                false
            )
            assertEquals(
                com.intellij.psi.PsiElementVisitor.EMPTY_VISITOR,
                dev.arcdrift.intellij.inspection.ArcBoundaryInspection().buildVisitor(plainHolder, false)
            )
            assertNull(dev.arcdrift.intellij.service.ArcDriftProjectService.findPrimarySpecFile(java.io.File("/tmp/arcdrift-nonexistent-spec-dir-xyz")))

            service.addGraphListener { }

            val action = dev.arcdrift.intellij.actions.IlluminateArchitectureAction()
            assertNotNull(action.actionUpdateThread)
            val actionEvent = com.intellij.openapi.actionSystem.AnActionEvent.createFromDataContext(
                "test",
                null,
                com.intellij.openapi.actionSystem.impl.SimpleDataContext.getProjectContext(project)
            )
            action.update(actionEvent)
            assertTrue(actionEvent.presentation.isEnabledAndVisible)
            action.actionPerformed(actionEvent)
            assertNotNull(dev.arcdrift.intellij.ui.ArcDriftToolWindowFactory())

            if (baseDir != null) {
                val diskSample = java.io.File(baseDir, "architecture.arc.mmd")
                assertEquals(
                    diskSample.canonicalPath,
                    dev.arcdrift.intellij.service.ArcDriftProjectService.findPrimarySpecFile(baseDir)?.canonicalPath
                )
                assertTrue(panel.readProjectFileWithDocumentOverride(diskSample, preferUnsavedDocs = false).isNotEmpty())
                panel.handleAgentFileMutation(diskSample, service)
                panel.openPrimarySpecInEditor()
                panel.onDebouncedVfsChange()
                val psiDiskFile = com.intellij.psi.PsiFileFactory.getInstance(project).createFileFromText(
                    "SamplePsi.kt",
                    org.jetbrains.kotlin.idea.KotlinFileType.INSTANCE,
                    "package com.acme.psi\nclass SamplePsi"
                )
                assertTrue(UastGraphExtractor.extractFromPsiFiles(project.name, listOf(psiDiskFile)).nodes.isNotEmpty())
                val tempSpecDir = kotlin.io.path.createTempDirectory("arcdrift-spec-discovery").toFile()
                try {
                    // Hidden enterprise policy pack must NEVER be returned as the primary workspace spec
                    java.io.File(tempSpecDir, ".arcdrift-policies.arc.mmd").writeText("flowchart TB")
                    assertNull(dev.arcdrift.intellij.service.ArcDriftProjectService.findPrimarySpecFile(tempSpecDir))

                    val baselineSpec = java.io.File(tempSpecDir, "baseline.arc.mmd").apply { writeText("graph TD") }
                    assertEquals(
                        baselineSpec.canonicalPath,
                        dev.arcdrift.intellij.service.ArcDriftProjectService.findPrimarySpecFile(tempSpecDir)?.canonicalPath
                    )
                    val customSpec = java.io.File(tempSpecDir, "custom.arc.mmd").apply { writeText("graph TD") }
                    assertEquals(
                        customSpec.canonicalPath,
                        dev.arcdrift.intellij.service.ArcDriftProjectService.findPrimarySpecFile(tempSpecDir)?.canonicalPath
                    )
                    val arcdriftSpec = java.io.File(tempSpecDir, "arcdrift.arc.mmd").apply { writeText("graph TD") }
                    assertEquals(
                        arcdriftSpec.canonicalPath,
                        dev.arcdrift.intellij.service.ArcDriftProjectService.findPrimarySpecFile(tempSpecDir)?.canonicalPath
                    )
                    // Primary spec has no rules, secondary spec has a rule -> tests loadRulesFromSpecFile fallback across specFiles
                    java.io.File(tempSpecDir, "secondary.arc.mmd").writeText(
                        "flowchart TB\n  %% @arc:rule id=\"sec-rule\" name=\"Sec\" from=\"*.a.*\" to=\"*.b.*\" policy=\"FORBIDDEN\""
                    )
                    assertTrue(service.loadRulesFromSpecFile(tempSpecDir.absolutePath).any { it.id == "sec-rule" })
                    val emptyGraph = dev.arcdrift.core.model.ArcGraph(projectName = "empty")
                    assertTrue(service.resolveFallbackProjectGraph(emptyGraph, tempSpecDir.absolutePath, emptyList()).nodes.isEmpty())
                    arcdriftSpec.writeText(
                        "flowchart TB\n  %% @arc:node id=\"com.acme.SpecOnly\" kind=\"SERVICE_BEAN\" level=\"L3_COMPONENT\"\n  com_acme_SpecOnly[\"SpecOnly\"]"
                    )
                    assertEquals(1, service.resolveFallbackProjectGraph(emptyGraph, tempSpecDir.absolutePath, emptyList()).nodes.size)
                } finally {
                    tempSpecDir.deleteRecursively()
                }
                dev.arcdrift.intellij.ui.IdeSourceNavigator.syncDiskFilesIntoOpenDocuments(project, listOf(diskSample.absolutePath))
                dev.arcdrift.intellij.ui.IdeSourceNavigator.refreshProjectVfsAndExplorer(project, listOf(diskSample.absolutePath), openPrimaryFile = true)
            }
            com.intellij.testFramework.PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        } finally {
            mockAgentServer.stop(0)
        }
        val fem = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project)
        fem.openFiles.forEach { fem.closeFile(it) }
        if (baseDir != null) {
            baseDir.deleteRecursively()
        }
    }
}
