package dev.arcdrift.core.refactoring

import dev.arcdrift.core.ast.JavaAstExtractor
import dev.arcdrift.core.ast.JvmAstRewriters
import dev.arcdrift.core.ast.KotlinAstParser
import dev.arcdrift.core.ast.PythonAstParser
import dev.arcdrift.core.ast.TypeScriptAstParser
import dev.arcdrift.core.model.ArcGraph
import dev.arcdrift.core.model.EdgeKind
import dev.arcdrift.core.model.NodeKind
import dev.arcdrift.core.scanner.OpenApiAndTypeSpecExtractor
import dev.arcdrift.core.scanner.PolyglotRepoScanner
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HolisticScannerAstRefactoringRegressionTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `Java, Kotlin, Python, and TypeScript AST parsers handle generics, records, companion props, routers, and unquoted options`() {
        // 1. Java: generic supertype parameter must NOT leak into superTypeSimpleNames; record implements; varargs; RabbitListener queues
        val javaAst = JavaAstExtractor.parseJavaFile(
            """
            package com.acme.orders;
            import static com.acme.shared.Constants.*;
            import org.springframework.amqp.rabbit.annotation.RabbitListener;

            public record OrderRecord(String orderId, String... tags) implements AuditablePayload<InternalSecret> {
                public static final String IGNORED_CONST = "x";
            }

            class OrderWorker extends GenericWorker<OrderRecord, UnrelatedHelper> {
                @RabbitListener(queues = {"orders.q1", "orders.q2"})
                public void onMessage(String payload) {}
            }
            """.trimIndent()
        )
        assertTrue("com.acme.shared.Constants" in javaAst.explicitImports)
        val recordCls = javaAst.classes.first { it.simpleName == "OrderRecord" }
        assertTrue("AuditablePayload" in recordCls.superTypeSimpleNames)
        assertFalse("InternalSecret" in recordCls.superTypeSimpleNames)
        assertEquals("String[]", recordCls.fields["tags"])

        val workerCls = javaAst.classes.first { it.simpleName == "OrderWorker" }
        assertEquals(setOf("GenericWorker"), workerCls.superTypeSimpleNames)
        val workerContracts = dev.arcdrift.core.ast.AstTypeUnwrapper.buildContractsForClass(
            annotations = workerCls.annotations,
            methods = workerCls.methods,
            inlineRoutes = workerCls.inlineRoutes,
            dtoDefinitions = emptyMap(),
            openApiOperations = emptyMap(),
            isControllerOrApiImpl = false
        )
        assertEquals(setOf("orders.q1", "orders.q2"), workerContracts.map { it.pathOrTopic }.toSet())

        // 2. Kotlin: companion object & const val excluded from wire fields; suspend modifier preserved; Array<T> unwrapped
        val ktAst = KotlinAstParser.parseKotlinFile(
            """
            package com.acme.orders
            data class OrderResponse(val orderId: String, val items: Array<LineItemDto>) {
                val computedLabel: String = "ok"
                companion object {
                    const val API_VERSION = "v1"
                    val DEFAULT_TIMEOUT = 30
                }
            }
            class OrderHandler {
                suspend fun loadAsync(id: String): OrderResponse = TODO()
            }
            """.trimIndent(),
            "OrderResponseKt"
        )
        val ktDto = ktAst.classes.first { it.simpleName == "OrderResponse" }
        assertTrue("orderId" in ktDto.fields)
        assertEquals("LineItemDto[]", ktDto.fields["items"])
        assertFalse("API_VERSION" in ktDto.fields)
        assertFalse("DEFAULT_TIMEOUT" in ktDto.fields)
        val ktHandler = ktAst.classes.first { it.simpleName == "OrderHandler" }
        assertTrue(ktHandler.methods.single().isSuspend)

        // 3. Python: custom APIRouter variable name, list[Dto] | None return unwrapping, and f-string URL consumer
        val pyAst = PythonAstParser.parsePythonFile(
            """
            from fastapi import APIRouter
            from pydantic import BaseModel

            API_BASE = "/api/v2"
            orders_router = APIRouter(prefix="/v2/orders")

            class OrderItemOut(BaseModel):
                sku: str

            @orders_router.get("/{order_id}")
            async def list_items(order_id: str) -> list[OrderItemOut] | None:
                return []

            def fetch_summary(client, oid: str):
                return client.get(f"{API_BASE}/orders/{oid}")
            """.trimIndent()
        )
        assertTrue(pyAst.contracts.any { it.role == "PRODUCER" && it.pathOrTopic == "/v2/orders/{order_id}" && it.responseDto == "OrderItemOut" })
        assertTrue(pyAst.contracts.any { it.role == "CONSUMER" && it.pathOrTopic == "/api/v2/orders/{oid}" })

        // 4. TypeScript: intersection types, re-exports, unquoted { url, method } overriding React Query cache key
        val tsAst = TypeScriptAstParser.parseTypeScriptFile(
            """
            export { BaseOrder } from './models.js';
            export type DetailedOrder = BaseOrder & AuditMetadata & { totalCents: number };

            export function useOrderMutation() {
                return useQuery(['orders-cache-key'], () =>
                    apiClient.request<DetailedOrder[]>({ method: 'POST', url: '/v2/orders/batch' })
                );
            }
            """.trimIndent()
        )
        assertTrue("./models.js" in tsAst.importPaths)
        assertEquals(listOf("BaseOrder", "AuditMetadata"), tsAst.typeParents["DetailedOrder"])
        val tsContract = tsAst.contracts.single()
        assertEquals("POST", tsContract.methodOrAction)
        assertEquals("/v2/orders/batch", tsContract.pathOrTopic)
        assertEquals("DetailedOrder", tsContract.responseDto)
    }

    @Test
    fun `OpenApiAndTypeSpecExtractor and PolyglotRepoScanner handle quoted OpenAPI paths, allOf, TypeSpec comments, multi-class imports, and explicitSpecFile`() {
        val repo = tempDir.resolve("scanner-repo").toFile().apply { mkdirs() }

        // Hidden .arcdrift-policies.arc.mmd must NOT be treated as primary spec
        File(repo, ".arcdrift-policies.arc.mmd").writeText(
            """
            flowchart TB
                %% @arc:project name="should-not-hijack-project-name"
            """.trimIndent()
        )

        File(repo, "openapi.yaml").writeText(
            """
            openapi: 3.0.3
            paths:
              "/v1/accounts/{id}":
                get:
                  operationId: getAccountById
                  responses:
                    '200':
                      content:
                        application/json:
                          schema:
                            ${'$'}ref: '#/components/schemas/AccountResponse'
            components:
              schemas:
                BaseAudit:
                  type: object
                  properties:
                    createdAt:
                      type: string
                AccountResponse:
                  allOf:
                    - type: object
                      properties:
                        accountId:
                          type: string
                        nestedMeta:
                          type: object
                          properties:
                            internalNestedOnly:
                              type: number
                    - ${'$'}ref: '#/components/schemas/BaseAudit'
            """.trimIndent()
        )

        File(repo, "contracts.tsp").writeText(
            """
            /* Block comment with unbalanced brace { inside */
            namespace Acme {
              namespace Billing {
                model InvoiceModel {
                  invoiceId: string;
                }
                @route("/v1/invoices")
                interface InvoicesOps {
                  @get listInvoices(): InvoiceModel[];
                }
              }
            }
            """.trimIndent()
        )

        val extracted = OpenApiAndTypeSpecExtractor.extract(repo, setOf(".git"))
        assertEquals("GET" to "/v1/accounts/{id}", extracted.operations["getAccountById"])
        val accountSchema = requireNotNull(extracted.schemas["AccountResponse"])
        assertEquals("string", accountSchema["accountId"]?.lowercase())
        assertEquals("string", accountSchema["createdAt"]?.lowercase())
        assertFalse("internalNestedOnly" in accountSchema, "Nested object properties must not flatten into outer schema")
        assertEquals("GET" to "/v1/invoices", extracted.operations["listInvoices"])

        // Multi-class Kotlin file + external Logger import must not create false edge to internal Logger or cross-pollute sibling class
        val ktDir = File(repo, "src/main/kotlin/com/acme").apply { mkdirs() }
        File(ktDir, "InternalLogger.kt").writeText(
            """
            package com.acme.logging
            class Logger
            """.trimIndent()
        )
        File(ktDir, "MultiClasses.kt").writeText(
            """
            package com.acme.app
            import org.slf4j.Logger
            import com.acme.domain.BaseEntity

            open class BaseService
            class ChildService : BaseService() {
                private val log: Logger? = null
            }
            class SiblingUsesEntity(val entity: BaseEntity)
            """.trimIndent()
        )
        File(ktDir, "BaseEntity.kt").writeText(
            """
            package com.acme.domain
            class BaseEntity
            """.trimIndent()
        )

        // TS .js import extension + index.ts resolution + Python relative import
        val webDir = File(repo, "web/src").apply { mkdirs() }
        File(webDir, "utils/index.ts").apply { parentFile.mkdirs(); writeText("export interface ApiConfig { baseUrl: string; }") }
        File(webDir, "models.ts").writeText("export interface AccountView { accountId: string; createdAt: string; }")
        File(webDir, "client.ts").writeText(
            """
            import { AccountView } from './models.js';
            import { ApiConfig } from './utils';
            export async function loadAccount(id: string): Promise<AccountView[]> {
                const r = await fetch(`/v1/accounts/${'$'}{id}`);
                return r.json();
            }
            """.trimIndent()
        )

        val pyDir = File(repo, "py_pkg").apply { mkdirs() }
        File(pyDir, "schemas.py").writeText("class PyOrder:\n    id: str\n")
        File(pyDir, "service.py").writeText("from . import schemas\n")

        val graph = PolyglotRepoScanner.scanRepository(repo)
        assertEquals("scanner-repo", graph.projectName)

        // Verify EXTENDS edge for class inheritance, no false Logger dependency, and no import leak from SiblingUsesEntity onto ChildService
        assertTrue(graph.edges.any { it.sourceId == "com.acme.app.ChildService" && it.targetId == "com.acme.app.BaseService" && it.kind == EdgeKind.EXTENDS })
        assertFalse(graph.edges.any { it.sourceId == "com.acme.app.ChildService" && it.targetId == "com.acme.logging.Logger" })
        assertFalse(graph.edges.any { it.sourceId == "com.acme.app.ChildService" && it.targetId == "com.acme.domain.BaseEntity" })

        // Verify TS .js -> .ts & directory index.ts edges and Python relative import edge
        assertTrue(graph.edges.any { it.sourceId == "ts.web.src.client" && it.targetId == "ts.web.src.models" })
        assertTrue(graph.edges.any { it.sourceId == "ts.web.src.client" && it.targetId == "ts.web.src.utils.index" })
        assertTrue(graph.edges.any { it.sourceId == "py.py_pkg.service" && it.targetId == "py.py_pkg.schemas" })

        // Explicit missing spec file must fail closed
        assertFailsWith<IllegalArgumentException> {
            PolyglotRepoScanner.scanRepository(repo, explicitSpecFile = File(repo, "does-not-exist.arc.mmd"))
        }
    }

    @Test
    fun `AST rewriters handle CRLF, file annotations, disjoint generic renames, nested classes, annotations, and CONSTRUCTOR_INJECT`() {
        val ktRewriter = JvmAstRewriters.forFileName("Sample.kt")
        val javaRewriter = JvmAstRewriters.forFileName("Sample.java")

        // 1. Kotlin insertImport with CRLF, @file:JvmName, and trailing package comment
        val ktWithHeader = "@file:JvmName(\"OrdersUtil\")\r\npackage com.acme.orders // domain package\r\n\r\nclass OrderBox\r\n"
        val ktImported = ktRewriter.insertImport(ktWithHeader, "com.acme.shared.AuditPort")
        assertTrue(ktImported.contains("\r\n"))
        assertFalse(ktRewriter.hasSyntaxErrors(ktImported))
        assertTrue(ktImported.indexOf("import com.acme.shared.AuditPort") > ktImported.indexOf("package com.acme.orders"))

        // 2. Disjoint nested generic rename (`com.acme.OldBox<OldBox>`) in Kotlin & Java
        val ktGenericSrc = """
            package com.other
            import com.acme.OldBox
            class Holder(val box: com.acme.OldBox<OldBox<String>>)
        """.trimIndent()
        val ktRenamed = ktRewriter.rewriteSymbolReferences(ktGenericSrc, "com.acme.OldBox", "com.acme.NewBox", "OldBox", "NewBox")
        assertFalse(ktRewriter.hasSyntaxErrors(ktRenamed))
        assertTrue(ktRenamed.contains("import com.acme.NewBox"))
        assertTrue(ktRenamed.contains("com.acme.NewBox<NewBox<String>>"))

        val javaGenericSrc = """
            package com.other;
            import com.acme.OldBox;
            @OldAudit
            public class Holder {
                private com.acme.OldBox<OldBox<String>> box;
            }
        """.trimIndent()
        val javaRenamed1 = javaRewriter.rewriteSymbolReferences(javaGenericSrc, "com.acme.OldBox", "com.acme.NewBox", "OldBox", "NewBox")
        val javaRenamed2 = javaRewriter.rewriteSymbolReferences(javaRenamed1, "com.other.OldAudit", "com.other.NewAudit", "OldAudit", "NewAudit")
        assertFalse(javaRewriter.hasSyntaxErrors(javaRenamed2))
        assertTrue(javaRenamed2.contains("com.acme.NewBox<NewBox<String>>"))
        assertTrue(javaRenamed2.contains("@NewAudit"))

        // 3. Nested class rename (`com.acme.Outer.Inner` -> `RenamedInner`) in Kotlin & Java
        val ktNested = "package com.acme\nclass Outer {\n    class Inner\n}\n"
        val ktNestedOut = ktRewriter.rewriteSymbolReferences(ktNested, "com.acme.Outer.Inner", "com.acme.Outer.RenamedInner", "Inner", "RenamedInner")
        assertFalse(ktRewriter.hasSyntaxErrors(ktNestedOut))
        assertTrue(ktNestedOut.contains("class RenamedInner"))

        val javaNested = "package com.acme;\npublic class Outer {\n    public static class Inner {}\n}\n"
        val javaNestedOut = javaRewriter.rewriteSymbolReferences(javaNested, "com.acme.Outer.Inner", "com.acme.Outer.RenamedInner", "Inner", "RenamedInner")
        assertFalse(javaRewriter.hasSyntaxErrors(javaNestedOut))
        assertTrue(javaNestedOut.contains("class RenamedInner"))

        // 4. rewireCallerToExtractedInterface retains concrete import when `OrderService()` constructor is still called
        val callerStillInstantiating = """
            package com.acme.web
            import com.acme.service.OrderService
            class Controller(private val svc: OrderService) {
                fun fallback(): OrderService = OrderService()
            }
        """.trimIndent()
        val rewiredCaller = ktRewriter.rewireCallerToExtractedInterface(
            source = callerStillInstantiating,
            sourceClassFqName = "com.acme.service.OrderService",
            sourceSimpleName = "OrderService",
            interfaceFqName = "com.acme.port.OrderPort",
            interfaceSimpleName = "OrderPort",
            targetPackage = "com.acme.port"
        )
        assertFalse(ktRewriter.hasSyntaxErrors(rewiredCaller))
        assertTrue(rewiredCaller.contains("import com.acme.service.OrderService"), "Must keep concrete import when constructor call remains")
        assertTrue(rewiredCaller.contains("import com.acme.port.OrderPort"))
        assertTrue(rewiredCaller.contains("private val svc: OrderPort"))

        // 5. CONSTRUCTOR_INJECT across Kotlin primary constructor, Kotlin class without primary constructor, and Java class
        val ktWithCtor = "package com.acme\nclass OrderService(private val a: String) {\n    fun run() = a\n}\n"
        val ktInjected1 = ktRewriter.addConstructorDependencyToTypeDecl(ktWithCtor, "OrderService", "AuditPort")
        assertFalse(ktRewriter.hasSyntaxErrors(ktInjected1))
        assertTrue(ktInjected1.contains("private val auditPort: AuditPort"))

        val ktNoPrimary = "package com.acme\nclass LegacyService\n"
        val ktInjected2 = ktRewriter.addConstructorDependencyToTypeDecl(ktNoPrimary, "LegacyService", "AuditPort")
        assertFalse(ktRewriter.hasSyntaxErrors(ktInjected2))
        assertTrue(ktInjected2.contains("class LegacyService(private val auditPort: AuditPort)"))

        val javaSvc = "package com.acme;\npublic class JavaOrderService {\n    public JavaOrderService(String id) {}\n}\n"
        val javaInjected = javaRewriter.addConstructorDependencyToTypeDecl(javaSvc, "JavaOrderService", "AuditPort")
        assertFalse(javaRewriter.hasSyntaxErrors(javaInjected))
        assertTrue(javaInjected.contains("private final AuditPort auditPort;"))
        assertTrue(javaInjected.contains("this.auditPort = auditPort;"))
        // Idempotent second call
        assertEquals(javaInjected, javaRewriter.addConstructorDependencyToTypeDecl(javaInjected, "JavaOrderService", "AuditPort"))

        // Java class without constructor synthesizes one; interface/record/enum implements & extends
        val javaNoCtor = "package com.acme;\npublic class PlainService {\n}\n"
        val javaSynthCtor = javaRewriter.addConstructorDependencyToTypeDecl(javaNoCtor, "PlainService", "AuditPort")
        assertFalse(javaRewriter.hasSyntaxErrors(javaSynthCtor))
        assertTrue(javaSynthCtor.contains("public PlainService(AuditPort auditPort)"))

        val javaIface = "package com.acme;\npublic interface OrderApi {}\n"
        val javaIfaceExt = javaRewriter.addImplementsToTypeDecl(javaIface, "OrderApi", "BaseApi")
        assertTrue(javaIfaceExt.contains("extends BaseApi"))
        val javaIfaceExt2 = javaRewriter.addExtendsToTypeDecl(javaIfaceExt, "OrderApi", "SecondApi")
        assertTrue(javaIfaceExt2.contains("BaseApi, SecondApi"))
        val javaClassExt = javaRewriter.addExtendsToTypeDecl("package com.acme;\npublic class ChildSvc {}\n", "ChildSvc", "BaseSvc")
        assertTrue(javaClassExt.contains("extends BaseSvc"))
        val ktClassExt = ktRewriter.addExtendsToTypeDecl("package com.acme\nclass ChildSvc\n", "ChildSvc", "BaseSvc")
        assertTrue(ktClassExt.contains(": BaseSvc()"))

        val javaRec = "package com.acme;\npublic record OrderRec(String id) {}\n"
        val javaRecImpl = javaRewriter.addImplementsToTypeDecl(javaRec, "OrderRec", "Serializable")
        assertTrue(javaRecImpl.contains("implements Serializable"))

        val javaEnum = "package com.acme;\npublic enum OrderStatus { OPEN, CLOSED }\n"
        val javaEnumImpl = javaRewriter.addImplementsToTypeDecl(javaEnum, "OrderStatus", "Codeable")
        assertTrue(javaEnumImpl.contains("implements Codeable"))

        val javaCallerWithNew = """
            package com.acme.web;
            import com.acme.service.OrderService;
            public class OrderCtrl {
                private OrderService svc;
                public OrderService create() { return new OrderService(); }
            }
        """.trimIndent()
        val javaRewired = javaRewriter.rewireCallerToExtractedInterface(
            source = javaCallerWithNew,
            sourceClassFqName = "com.acme.service.OrderService",
            sourceSimpleName = "OrderService",
            interfaceFqName = "com.acme.port.OrderPort",
            interfaceSimpleName = "OrderPort",
            targetPackage = "com.acme.port"
        )
        assertFalse(javaRewriter.hasSyntaxErrors(javaRewired))
        assertTrue(javaRewired.contains("import com.acme.service.OrderService;"))
        assertTrue(javaRewired.contains("import com.acme.port.OrderPort;"))
        assertTrue(javaRewired.contains("private OrderPort svc;"))

        val javaNoPkg = "public class NoPkg {}\r\n"
        assertTrue(javaRewriter.rewritePackageDirective(javaNoPkg, "com.acme.gen").startsWith("package com.acme.gen;"))
        assertTrue(javaRewriter.insertImport(javaNoPkg, "com.acme.AuditPort").contains("import com.acme.AuditPort;"))
        val javaAppended = javaRewriter.renderOrAppendTypeDeclaration(javaNoPkg, "com.acme", "ExtraType", NodeKind.INTERFACE)
        assertTrue(javaAppended.contains("interface ExtraType"))

        // Kotlin interface extends, empty primary constructor injection, body-only class injection, and @file: package rewrite
        val ktIface = "package com.acme\ninterface OrderApi\n"
        val ktIfaceExt = ktRewriter.addImplementsToTypeDecl(ktIface, "OrderApi", "BaseApi")
        assertTrue(ktIfaceExt.contains("interface OrderApi : BaseApi"))
        val ktIfaceExt2 = ktRewriter.addExtendsToTypeDecl(ktIfaceExt, "OrderApi", "SecondApi")
        assertTrue(ktIfaceExt2.contains("BaseApi, SecondApi"))

        val ktEmptyCtor = "package com.acme\nclass EmptyCtor() {\n    fun ok() = 1\n}\n"
        val ktEmptyInjected = ktRewriter.addConstructorDependencyToTypeDecl(ktEmptyCtor, "EmptyCtor", "AuditPort")
        assertTrue(ktEmptyInjected.contains("private val auditPort: AuditPort"))
        assertEquals(ktEmptyInjected, ktRewriter.addConstructorDependencyToTypeDecl(ktEmptyInjected, "EmptyCtor", "AuditPort"))

        val ktBodyOnly = "package com.acme\nclass BodyOnly {\n    fun ok() = 1\n}\n"
        val ktBodyInjected = ktRewriter.addConstructorDependencyToTypeDecl(ktBodyOnly, "BodyOnly", "AuditPort")
        assertTrue(ktBodyInjected.contains("class BodyOnly(private val auditPort: AuditPort)"))

        val ktFileAnnNoPkg = "@file:JvmName(\"NoPkgKt\")\nclass NoPkg\n"
        val ktPkgRewritten = ktRewriter.rewritePackageDirective(ktFileAnnNoPkg, "com.acme.added")
        assertFalse(ktRewriter.hasSyntaxErrors(ktPkgRewritten))
        assertTrue(ktPkgRewritten.contains("package com.acme.added"))
    }

    @Test
    fun `DiskSourceTransaction and InMemoryGraphRefactoringAdapter handle case-only rename, sibling imports on move, Java source root, and keyword guards`() {
        val repo = tempDir.resolve("java-refactor-repo").toFile().apply { mkdirs() }
        val javaPkgDir = File(repo, "src/main/java/com/acme/orders").apply { mkdirs() }
        File(javaPkgDir, "HelperBean.java").writeText(
            """
            package com.acme.orders;
            public class HelperBean {}
            """.trimIndent()
        )
        val svcFile = File(javaPkgDir, "OrderService.java")
        svcFile.writeText(
            """
            package com.acme.orders;
            public class OrderService {
                private HelperBean helper;
            }
            """.trimIndent()
        )

        val initial = PolyglotRepoScanner.scanRepository(repo)
        val adapter = InMemoryGraphRefactoringAdapter(initial, rootDir = repo)

        // 1. Keyword validation rejects invalid identifiers cleanly
        val badKeyword = adapter.execute(RefactoringCommand.CreateNode("com.acme.orders.class", kind = "CLASS"), previewOnly = false)
        assertFalse(badKeyword.success)

        // 2. CreateNode in a pure Java repo writes into src/main/java (not src/main/kotlin)
        val created = adapter.execute(RefactoringCommand.CreateNode("com.acme.port.AuditPort", kind = "INTERFACE"), previewOnly = false)
        assertTrue(created.success, "Expected CreateNode success: ${created.errorMessage}")
        assertTrue(File(repo, "src/main/java/com/acme/port/AuditPort.java").isFile)

        // 3. ADD_DEPENDENCY with CONSTRUCTOR_INJECT wires AuditPort into OrderService constructor
        val injected = adapter.execute(
            RefactoringCommand.AddDependency("com.acme.orders.OrderService", "com.acme.port.AuditPort", relationKind = "CONSTRUCTOR_INJECT"),
            previewOnly = false
        )
        assertTrue(injected.success, "Expected CONSTRUCTOR_INJECT success: ${injected.errorMessage}")
        assertTrue(svcFile.readText().contains("private final AuditPort auditPort;"))

        // 4. MoveClassOrPackage injects import for same-package sibling `HelperBean` left behind in `com.acme.orders`
        val moved = adapter.execute(
            RefactoringCommand.MoveClassOrPackage("com.acme.orders.OrderService", "com.acme.application"),
            previewOnly = false
        )
        assertTrue(moved.success, "Expected MoveClassOrPackage success: ${moved.errorMessage}")
        val movedFile = File(repo, "src/main/java/com/acme/application/OrderService.java")
        assertTrue(movedFile.isFile)
        val movedText = movedFile.readText()
        assertFalse(JvmAstRewriters.forFileName("OrderService.java").hasSyntaxErrors(movedText))
        assertTrue(movedText.contains("import com.acme.orders.HelperBean;"))

        // Validation guards: invalid package, collision on MoveClassOrPackage, invalid AddDependency FQN, invalid ExtractInterface name
        assertFalse(adapter.execute(RefactoringCommand.MoveClassOrPackage("com.acme.application.OrderService", "com.class.bad"), previewOnly = false).success)
        assertFalse(adapter.execute(RefactoringCommand.AddDependency("com.acme.application.OrderService", "com.bad.class", relationKind = "IMPLEMENTS"), previewOnly = false).success)
        assertFalse(adapter.execute(RefactoringCommand.ExtractInterface("com.acme.application.OrderService", "interface", "com.acme.port"), previewOnly = false).success)
        assertTrue(adapter.execute(RefactoringCommand.AddDependency("com.acme.application.OrderService", "com.acme.port.AuditPort", relationKind = "IMPLEMENTS"), previewOnly = false).success)
        assertTrue(adapter.execute(RefactoringCommand.AddDependency("com.acme.application.OrderService", "com.acme.orders.HelperBean", relationKind = "EXTENDS"), previewOnly = false).success)
        // Collision on MoveClassOrPackage when destination file already exists
        val existingDest = File(repo, "src/main/java/com/acme/orders/OrderService.java").apply {
            writeText("package com.acme.orders; public class OrderService {}")
        }
        assertFalse(adapter.execute(RefactoringCommand.MoveClassOrPackage("com.acme.application.OrderService", "com.acme.orders"), previewOnly = false).success)
        existingDest.delete()

        // 5. Case-only file rename + directory cleanup on rollback in DiskSourceTransaction
        val tx = DiskSourceTransaction()
        val caseTarget = File(repo, "src/main/java/com/acme/application/Orderservice.java")
        tx.moveOrRenameWithContent(movedFile, caseTarget, movedText)
        assertTrue(caseTarget.isFile)
        tx.rollback()
        assertTrue(movedFile.isFile)

        val txRollbackDirs = DiskSourceTransaction()
        val deepNewFile = File(repo, "src/main/java/com/acme/brandnew/deep/Temp.java")
        txRollbackDirs.writeText(deepNewFile, "package com.acme.brandnew.deep; class Temp {}")
        assertTrue(deepNewFile.isFile)
        txRollbackDirs.rollback()
        assertFalse(File(repo, "src/main/java/com/acme/brandnew").exists(), "Created empty parent dirs must be cleaned up on rollback")
    }
}
