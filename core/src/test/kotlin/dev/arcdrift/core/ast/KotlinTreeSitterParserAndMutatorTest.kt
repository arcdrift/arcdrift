package dev.arcdrift.core.ast

import dev.arcdrift.core.model.ArcNode
import dev.arcdrift.core.model.C4Level
import dev.arcdrift.core.model.MemberSignature
import dev.arcdrift.core.model.NodeKind
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Comprehensive verification suite for the Tree-sitter Kotlin (`io.github.bonede:tree-sitter-kotlin`)
 * parser and source mutator, covering classpath isolation, multi-byte UTF-8 offsets,
 * lock-free concurrency, Kotlin grammar coverage, typed messaging producers, and refactoring edge cases.
 */
class KotlinTreeSitterParserAndMutatorTest {

    @Test
    fun `kotlin-compiler-embeddable shaded PSI classes are absent from arcdrift-core runtime classpath`() {
        assertFailsWith<ClassNotFoundException> {
            Class.forName("org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment")
        }
        assertFailsWith<ClassNotFoundException> {
            Class.forName("org.jetbrains.kotlin.psi.KtPsiFactory")
        }
    }

    @Test
    fun `multi-byte UTF-8 comments and strings (accented, CJK, 4-byte emoji surrogate pairs) preserve exact AST offsets across all mutations`() {
        val utf8Source = """
            // 🚀 Café 日本語 comment with 2-byte (é), 3-byte (日), and 4-byte (🚀🔥) UTF-8 sequences
            package com.acme.orders

            import com.acme.shared.OrderDto
            import com.acme.unused.UnusedDto

            /** 注文サービス — manages café orders 🔥 */
            class OrderService(
                private val repo: OrderRepo, // 🗄️ repository
            ) {
                val greeting: String = "Привет 🌍 こんにちは"

                suspend fun loadOrder(id: String): OrderDto = OrderDto(id)
            }
        """.trimIndent() + "\n"

        val parsed = KotlinAstParser.parseKotlinFile(utf8Source, "OrderServiceKt")
        assertEquals("com.acme.orders", parsed.pkg)
        assertEquals(1, parsed.classes.size)
        val cls = parsed.classes.single()
        assertEquals("OrderService", cls.simpleName)
        assertEquals(1, cls.startLine)
        assertEquals(utf8Source.lines().size, cls.endLine)
        assertTrue("OrderRepo" in cls.referencedSimpleNames)
        assertTrue("OrderDto" in cls.referencedSimpleNames)

        // Verify every mutation preserves multi-byte UTF-8 characters intact with zero syntax errors
        val pkgUpdated = KotlinAstSourceMutator.rewritePackageDirective(utf8Source, "com.acme.billing")
        assertTrue(pkgUpdated.contains("// 🚀 Café 日本語"))
        assertTrue(pkgUpdated.contains("package com.acme.billing\n"))
        assertFalse(KotlinAstSourceMutator.hasSyntaxErrors(pkgUpdated))

        val impInserted = KotlinAstSourceMutator.insertImport(utf8Source, "com.acme.audit.AuditClient")
        assertTrue(impInserted.contains("import com.acme.audit.AuditClient\n"))
        assertTrue(impInserted.contains("Привет 🌍 こんにちは"))
        assertFalse(KotlinAstSourceMutator.hasSyntaxErrors(impInserted))

        val implAdded = KotlinAstSourceMutator.addImplementsToTypeDecl(
            utf8Source, "OrderService", "OrderPort", setOf("loadOrder")
        )
        assertTrue(implAdded.contains(") : OrderPort {"))
        assertTrue(implAdded.contains("override suspend fun loadOrder(id: String): OrderDto"))
        assertTrue(implAdded.contains("注文サービス — manages café orders 🔥"))
        assertFalse(KotlinAstSourceMutator.hasSyntaxErrors(implAdded))

        val extAdded = KotlinAstSourceMutator.addExtendsToTypeDecl(utf8Source, "OrderService", "BaseService")
        assertTrue(extAdded.contains(") : BaseService() {"))
        assertFalse(KotlinAstSourceMutator.hasSyntaxErrors(extAdded))

        val ctorAdded = KotlinAstSourceMutator.addConstructorDependencyToTypeDecl(utf8Source, "OrderService", "AuditClient")
        assertFalse(ctorAdded.contains(",,"))
        assertTrue(ctorAdded.contains("private val auditClient: AuditClient,"))
        assertTrue(ctorAdded.contains("// 🗄️ repository"))
        assertFalse(KotlinAstSourceMutator.hasSyntaxErrors(ctorAdded))

        val renamed = KotlinAstSourceMutator.rewriteSymbolReferences(
            utf8Source, "com.acme.orders.OrderService", "com.acme.orders.BillingService", "OrderService", "BillingService"
        )
        assertTrue(renamed.contains("class BillingService("))
        assertTrue(renamed.contains("Привет 🌍 こんにちは"))
        assertFalse(KotlinAstSourceMutator.hasSyntaxErrors(renamed))

        val callerUtf8 = """
            // 🌐 Caller with emoji header 🚀
            package com.acme.web
            import com.acme.orders.OrderService
            class WebController(private val svc: OrderService) // 日本語
        """.trimIndent() + "\n"
        val rewired = KotlinAstSourceMutator.rewireCallerToExtractedInterface(
            callerUtf8, "com.acme.orders.OrderService", "OrderService",
            "com.acme.ports.OrderPort", "OrderPort", "com.acme.ports"
        )
        assertTrue(rewired.contains("private val svc: OrderPort"))
        assertTrue(rewired.contains("import com.acme.ports.OrderPort"))
        assertTrue(rewired.contains("// 🌐 Caller with emoji header 🚀"))
        assertTrue(rewired.contains("// 日本語"))
        assertFalse(KotlinAstSourceMutator.hasSyntaxErrors(rewired))
    }

    @Test
    fun `lock-free thread-local Tree-sitter parser and mutator are safe under high concurrency`() {
        val threadCount = 16
        val iterationsPerThread = 40
        val pool = Executors.newFixedThreadPool(threadCount)
        val startGate = CountDownLatch(1)

        try {
            val futures = (0 until threadCount).map { tid ->
                pool.submit(Callable {
                    startGate.await()
                    for (iter in 0 until iterationsPerThread) {
                        val clsName = "Service_${tid}_${iter}"
                        val src = """
                            package com.acme.concurrent
                            import com.acme.dto.Dto_$tid

                            @RestController
                            class $clsName(private val repo: Repo_$tid) {
                                @GetMapping("/v1/items/$tid/$iter")
                                suspend fun fetch(id: String): Dto_$tid = Dto_$tid(id)
                            }
                        """.trimIndent()

                        val ast = KotlinAstParser.parseKotlinFile(src, "${clsName}Kt")
                        assertEquals("com.acme.concurrent", ast.pkg)
                        assertEquals(1, ast.classes.size)
                        val parsedCls = ast.classes.single()
                        assertEquals(clsName, parsedCls.simpleName)
                        assertEquals(NodeKind.REST_CONTROLLER, parsedCls.kind)
                        assertTrue("Repo_$tid" in parsedCls.referencedSimpleNames)

                        val mutated = KotlinAstSourceMutator.addImplementsToTypeDecl(
                            src, clsName, "Port_$tid", setOf("fetch")
                        )
                        assertTrue(mutated.contains("class $clsName(private val repo: Repo_$tid) : Port_$tid {"))
                        assertTrue(mutated.contains("override suspend fun fetch("))
                        assertFalse(KotlinAstSourceMutator.hasSyntaxErrors(mutated))
                    }
                    true
                })
            }
            startGate.countDown()
            for (f in futures) {
                assertTrue(f.get())
            }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `parses Kotlin declarations, use-site annotations, extension functions, expression bodies, Ktor routes, and typed messaging producers`() {
        val code = """
            @file:Suppress("UNUSED")
            package com.acme.orders

            import com.acme.shared.BaseDto
            import com.acme.shared.OrderEvent as WireOrderEvent
            import org.springframework.kafka.core.KafkaTemplate
            import org.springframework.amqp.rabbit.core.RabbitTemplate

            @JvmInline
            value class OrderId(val raw: String)

            data class OrderPayload(
                @get:JsonProperty("wire_order_id") val id: String,
                @field:SerialName("amount_cents") val amount: Long,
                @param:Json("currency_code") val currency: String,
                val tags: List<String>?
            ) : BaseDto() {
                const val SCHEMA_VERSION = 2
                val computedNote: String = "ok"
            }

            @RestController
            @RequestMapping(path = arrayOf("/v1/orders"))
            class OrderHandler @Inject constructor(
                private val eventBus: KafkaTemplate<String, WireOrderEvent>,
                private val amqpClient: RabbitTemplate,
                private val fallbackDto: OrderPayload
            ) {
                @PostMapping(value = listOf("/create"))
                suspend fun createOrder(
                    @RequestBody body: OrderPayload,
                    vararg flags: String
                ) = ResponseEntity.ok(body)

                fun String.toOrderSummary(): OrderPayload = fallbackDto

                fun publishEvents(evt: WireOrderEvent) {
                    eventBus.send("orders.created", evt)
                    this.eventBus.send("orders.updated", evt)
                    amqpClient.convertAndSend("billing.exchange", evt)
                }

                fun literalInt() = 42
                fun literalLong() = 42L
                fun literalDouble() = 3.14
                fun literalBool() = true
                fun literalStr() = "hi"
                fun literalList() = listOf("a")
                fun literalSet() = setOf("a")
                fun literalMap() = mapOf("k" to "v")
            }

            enum class OrderState {
                OPEN, CLOSED;
                fun isTerminal(): Boolean = this == CLOSED
            }

            object OrderRegistry : MarkerInterface
        """.trimIndent()

        val ast = KotlinAstParser.parseKotlinFile(code)
        assertEquals("com.acme.orders", ast.pkg)
        assertEquals(mapOf("WireOrderEvent" to "com.acme.shared.OrderEvent"), ast.importAliases)
        assertEquals(5, ast.classes.size)

        val orderId = ast.classes.first { it.simpleName == "OrderId" }
        assertEquals(NodeKind.DATA_CLASS, orderId.kind)

        val payload = ast.classes.first { it.simpleName == "OrderPayload" }
        assertEquals(NodeKind.DATA_CLASS, payload.kind)
        assertEquals(setOf("BaseDto"), payload.superTypeSimpleNames)
        assertEquals("String", payload.fields["wire_order_id"])
        assertEquals("Long", payload.fields["amount_cents"])
        assertEquals("String", payload.fields["currency_code"])
        assertEquals("String[]", payload.fields["tags"])
        assertEquals("String", payload.fields["computedNote"])
        assertFalse("SCHEMA_VERSION" in payload.fields, "const val must be excluded from DTO wire fields")

        val handler = ast.classes.first { it.simpleName == "OrderHandler" }
        assertEquals(NodeKind.REST_CONTROLLER, handler.kind)
        assertEquals(listOf("/v1/orders"), handler.annotations.first { it.name == "RequestMapping" }.allPathsOrValues())

        val createFn = handler.methods.first { it.name == "createOrder" }
        assertTrue(createFn.isSuspend)
        assertEquals(listOf("/create"), createFn.annotations.first { it.name == "PostMapping" }.allPathsOrValues())
        assertEquals("OrderPayload", createFn.inferredReturnTypeName)
        assertEquals(2, createFn.parameters.size)
        assertTrue(createFn.parameters[0].annotations.any { it.name == "RequestBody" })
        assertTrue(createFn.parameters[1].type.isVararg)

        // Extension function: receiver String must NOT overwrite return type OrderPayload
        val extFn = handler.methods.first { it.name == "toOrderSummary" }
        assertEquals("OrderPayload", extFn.returnType?.render())

        // Expression body literal inference
        assertEquals("Int", handler.methods.first { it.name == "literalInt" }.inferredReturnTypeName)
        assertEquals("Long", handler.methods.first { it.name == "literalLong" }.inferredReturnTypeName)
        assertEquals("Double", handler.methods.first { it.name == "literalDouble" }.inferredReturnTypeName)
        assertEquals("Boolean", handler.methods.first { it.name == "literalBool" }.inferredReturnTypeName)
        assertEquals("String", handler.methods.first { it.name == "literalStr" }.inferredReturnTypeName)
        assertEquals("List<Any>", handler.methods.first { it.name == "literalList" }.inferredReturnTypeName)
        assertEquals("Set<Any>", handler.methods.first { it.name == "literalSet" }.inferredReturnTypeName)
        assertEquals("Map<Any, Any>", handler.methods.first { it.name == "literalMap" }.inferredReturnTypeName)

        // Typed KafkaTemplate & RabbitTemplate publisher detection (including `this.` receiver)
        val kafkaTopics = handler.inlineRoutes.filter { it.protocol == "KAFKA" && it.methodOrAction == "PUBLISH" }
            .map { it.pathOrTopic }.toSet()
        assertEquals(setOf("orders.created", "orders.updated", "billing.exchange"), kafkaTopics)

        val enumCls = ast.classes.first { it.simpleName == "OrderState" }
        assertEquals(NodeKind.ENUM, enumCls.kind)

        val objCls = ast.classes.first { it.simpleName == "OrderRegistry" }
        assertEquals(setOf("MarkerInterface"), objCls.superTypeSimpleNames)

        // Top-level Ktor routes without double-counting nested call_expression
        val ktorCode = """
            package com.acme.ktor
            import io.ktor.server.routing.*

            fun Route.orderRoutes() {
                get("/v1/health") { }
                post("/v1/orders") { }
            }
        """.trimIndent()
        val ktorAst = KotlinAstParser.parseKotlinFile(ktorCode, "OrderRoutesKt")
        val ktorFileNode = ktorAst.classes.single()
        assertEquals(NodeKind.REST_CONTROLLER, ktorFileNode.kind)
        assertEquals(2, ktorFileNode.inlineRoutes.size)
        assertEquals(
            setOf("GET /v1/health", "POST /v1/orders"),
            ktorFileNode.inlineRoutes.map { "${it.methodOrAction} ${it.pathOrTopic}" }.toSet()
        )
    }

    @Test
    fun `mutator handles file annotations, secondary-only constructors, nested prefixes, named args, aliases, and annotation immunity`() {
        // 1. File annotations: package & import inserted after `@file:...`
        val fileAnnSrc = "@file:Suppress(\"UNUSED\")\nclass Plain\n"
        val withPkg = KotlinAstSourceMutator.rewritePackageDirective(fileAnnSrc, "com.acme.gen")
        assertTrue(withPkg.startsWith("@file:Suppress(\"UNUSED\")\npackage com.acme.gen\n"))
        assertFalse(KotlinAstSourceMutator.hasSyntaxErrors(withPkg))

        val withImp = KotlinAstSourceMutator.insertImport(fileAnnSrc, "com.acme.Dep")
        assertTrue(withImp.startsWith("@file:Suppress(\"UNUSED\")\nimport com.acme.Dep\n"))
        assertFalse(KotlinAstSourceMutator.hasSyntaxErrors(withImp))

        // 2. Secondary-only constructor omits `()` on supertype, interface omits `()`, enum is no-op
        val secCtorSrc = "package com.acme\nclass SecOnly {\n    constructor(id: String)\n}\n"
        val secExt = KotlinAstSourceMutator.addExtendsToTypeDecl(secCtorSrc, "SecOnly", "Base")
        assertTrue(secExt.contains("class SecOnly : Base {"))
        assertFalse(secExt.contains("Base()"))
        assertFalse(KotlinAstSourceMutator.hasSyntaxErrors(secExt))

        val ifaceSrc = "package com.acme\ninterface ChildPort : ExistingPort\n"
        val ifaceExt = KotlinAstSourceMutator.addExtendsToTypeDecl(ifaceSrc, "ChildPort", "BasePort")
        assertTrue(ifaceExt.contains("interface ChildPort : ExistingPort, BasePort"))
        assertFalse(KotlinAstSourceMutator.hasSyntaxErrors(ifaceExt))

        val enumSrc = "package com.acme\nenum class Kind { A }\n"
        assertEquals(enumSrc, KotlinAstSourceMutator.addExtendsToTypeDecl(enumSrc, "Kind", "Base"))
        assertEquals(enumSrc, KotlinAstSourceMutator.addConstructorDependencyToTypeDecl(enumSrc, "Kind", "Dep"))

        // 3. Nested prefix rename (`Order.Config`), safe-call immunity (`other?.Order`), named arg label immunity (`call(Order = Order)`)
        val edgeRenameSrc = """
            package com.acme

            class Order {
                class Config
            }

            class Consumer(
                val cfg: Order.Config,
                val fqCfg: com.acme.Order.Config,
                val otherFq: com.other.Order
            ) {
                fun use(other: Any?) {
                    val a = Order.Config()
                    val b = Order::class
                    val c = other?.Order
                    val d = other.Order()
                    val e = com.other.Order()
                    call(Order = Order())
                }
            }
        """.trimIndent()
        val edgeRenamed = KotlinAstSourceMutator.rewriteSymbolReferences(
            edgeRenameSrc, "com.acme.Order", "com.acme.PurchaseOrder", "Order", "PurchaseOrder"
        )
        assertTrue(edgeRenamed.contains("class PurchaseOrder {"))
        assertTrue(edgeRenamed.contains("val cfg: PurchaseOrder.Config"))
        assertTrue(edgeRenamed.contains("val fqCfg: com.acme.PurchaseOrder.Config"))
        assertTrue(edgeRenamed.contains("val otherFq: com.other.Order"))
        assertTrue(edgeRenamed.contains("val a = PurchaseOrder.Config()"))
        assertTrue(edgeRenamed.contains("val b = PurchaseOrder::class"))
        assertTrue(edgeRenamed.contains("val c = other?.Order"))
        assertTrue(edgeRenamed.contains("val d = other.Order()"))
        assertTrue(edgeRenamed.contains("val e = com.other.Order()"))
        assertTrue(edgeRenamed.contains("call(Order = PurchaseOrder())"))
        assertFalse(KotlinAstSourceMutator.hasSyntaxErrors(edgeRenamed))

        // 4. Nested class rename (`com.acme.Outer.Inner` -> `com.acme.Outer.RenamedInner`)
        val nestedRenameSrc = """
            package com.acme

            class Outer {
                class Inner
            }

            class NestedCaller(val x: Outer.Inner, val y: com.acme.Outer.Inner) {
                fun make() = Outer.Inner()
            }
        """.trimIndent()
        val nestedRenamed = KotlinAstSourceMutator.rewriteSymbolReferences(
            nestedRenameSrc, "com.acme.Outer.Inner", "com.acme.Outer.RenamedInner", "Inner", "RenamedInner"
        )
        assertTrue(nestedRenamed.contains("class RenamedInner"))
        assertTrue(nestedRenamed.contains("val x: Outer.RenamedInner"))
        assertTrue(nestedRenamed.contains("val y: com.acme.Outer.RenamedInner"))
        assertTrue(nestedRenamed.contains("fun make() = Outer.RenamedInner()"))
        assertFalse(KotlinAstSourceMutator.hasSyntaxErrors(nestedRenamed))

        // 5. Aliased import stays aliased on rename + package move
        val aliasSrc = """
            package com.acme.web

            import com.acme.orders.Order as WireOrder

            class WebView(val o: WireOrder)
        """.trimIndent()
        val aliasMoved = KotlinAstSourceMutator.rewriteSymbolReferences(
            aliasSrc, "com.acme.orders.Order", "com.acme.billing.PurchaseOrder", "Order", "PurchaseOrder"
        )
        assertTrue(aliasMoved.contains("import com.acme.billing.PurchaseOrder as WireOrder"))
        assertTrue(aliasMoved.contains("class WebView(val o: WireOrder)"))
        assertFalse(KotlinAstSourceMutator.hasSyntaxErrors(aliasMoved))

        // 6. rewireCallerToExtractedInterface preserves instantiation, nested qualifier, and annotations while rewiring property type
        val mixedCaller = """
            package com.acme.web

            import com.acme.orders.OrderService

            @OrderService
            class MixedCaller(val svc: OrderService, val cfg: OrderService.Config) {
                fun factory() = OrderService()
            }
        """.trimIndent()
        val mixedRewired = KotlinAstSourceMutator.rewireCallerToExtractedInterface(
            mixedCaller,
            "com.acme.orders.OrderService",
            "OrderService",
            "com.acme.ports.OrderPort",
            "OrderPort",
            "com.acme.ports"
        )
        assertTrue(mixedRewired.contains("@OrderService"))
        assertTrue(mixedRewired.contains("val svc: OrderPort"))
        assertTrue(mixedRewired.contains("val cfg: OrderService.Config"))
        assertTrue(mixedRewired.contains("fun factory() = OrderService()"))
        assertTrue(mixedRewired.contains("import com.acme.orders.OrderService"), "Must keep concrete import while instantiation remains")
        assertTrue(mixedRewired.contains("import com.acme.ports.OrderPort"))
        assertFalse(KotlinAstSourceMutator.hasSyntaxErrors(mixedRewired))

        // 7. collectReferencedImportLines retains nested outer import and drops unnecessary wildcards
        val srcWithImports = """
            package com.acme.svc
            import com.acme.dto.Outer
            import com.acme.unused.Unused
            import com.acme.wild.*
            class Svc
        """.trimIndent()
        val keptOnlyExplicit = KotlinAstSourceMutator.collectReferencedImportLines(
            srcWithImports,
            "fun load(id: String): Outer.Inner"
        )
        assertEquals(listOf("import com.acme.dto.Outer"), keptOnlyExplicit)

        val keptWithWildcard = KotlinAstSourceMutator.collectReferencedImportLines(
            srcWithImports,
            "fun load(id: String): UnknownWildcardDto"
        )
        assertEquals(listOf("import com.acme.wild.*"), keptWithWildcard)

        // 8. renderExtractedInterfaceSource with suspend + cross-package graph nodes
        val renderedIface = KotlinAstSourceMutator.renderExtractedInterfaceSource(
            srcOriginalText = srcWithImports,
            srcPkg = "com.acme.svc",
            targetPackage = "com.acme.ports",
            interfaceSimpleName = "SvcPort",
            extractedMembers = listOf(
                MemberSignature("fetch", "METHOD", "suspend (id: String): LocalDto", "public"),
                MemberSignature("secret", "METHOD", "(): Unit", "private")
            ),
            graphNodes = listOf(
                ArcNode(id = "com.acme.svc.LocalDto", label = "LocalDto", c4Level = C4Level.L4_CODE, kind = NodeKind.DATA_CLASS, parentId = "com.acme.svc")
            )
        )
        assertTrue(renderedIface.contains("package com.acme.ports"))
        assertTrue(renderedIface.contains("import com.acme.svc.LocalDto"))
        assertTrue(renderedIface.contains("suspend fun fetch(id: String): LocalDto"))
        assertFalse(renderedIface.contains("secret"))
        assertFalse(KotlinAstSourceMutator.hasSyntaxErrors(renderedIface))
    }

    @Test
    fun `covers delegation-by, function and star-projected types, cross-package move import insertion, empty or no-ctor dependency injection, and block comments in trailing commas`() {
        // 1. Explicit delegation (`by`), function types, parenthesized nullable types, star projections, multiline annotation strings, `::OrderRepo`
        val tq = "\"\"\""
        val richSyntax = """
            package com.acme.rich

            class DelegatedService(
                private val impl: OrderPort,
                val callback: (OrderInput) -> OrderOutput,
                val items: List<*>,
                val wrapped: (OrderInput)?
            ) : OrderPort by impl {
                @GetMapping($tq/v1/raw$tq)
                fun handle(input: OrderInput) = input

                fun refs() {
                    val f1 = ::OrderOutput
                    val f2 = OrderInput::toString
                }
            }
        """.trimIndent()
        val richAst = KotlinAstParser.parseKotlinFile(richSyntax)
        val delegated = richAst.classes.single()
        assertTrue("OrderPort" in delegated.superTypeSimpleNames)
        assertTrue("OrderInput" in delegated.referencedSimpleNames)
        assertTrue("OrderOutput" in delegated.referencedSimpleNames)
        assertEquals(listOf("/v1/raw"), delegated.methods.first { it.name == "handle" }.annotations.first().allPathsOrValues())
        val delegatedImpl = KotlinAstSourceMutator.addImplementsToTypeDecl(richSyntax, "DelegatedService", "ExtraPort", setOf("handle"))
        assertTrue(delegatedImpl.contains(": OrderPort by impl, ExtraPort {"))
        assertTrue(delegatedImpl.contains("override fun handle(input: OrderInput) = input"))
        assertFalse(KotlinAstSourceMutator.hasSyntaxErrors(delegatedImpl))

        // 2. Cross-package move inserts import in same-package caller and wildcard-import caller (`referencesSymbolInAst`)
        val samePkgCaller = """
            package com.acme.oldpkg

            class SamePkgCaller(val target: MovedDto) {
                fun create() = MovedDto()
            }
        """.trimIndent()
        val movedSamePkg = KotlinAstSourceMutator.rewriteSymbolReferences(
            samePkgCaller, "com.acme.oldpkg.MovedDto", "com.acme.newpkg.MovedDto", "MovedDto", "MovedDto"
        )
        assertTrue(movedSamePkg.contains("import com.acme.newpkg.MovedDto"))
        assertFalse(KotlinAstSourceMutator.hasSyntaxErrors(movedSamePkg))

        val wildcardCaller = """
            package com.acme.web

            import com.acme.oldpkg.*

            class WildcardCaller {
                val ref = MovedDto::class
            }
        """.trimIndent()
        val movedWildcard = KotlinAstSourceMutator.rewriteSymbolReferences(
            wildcardCaller, "com.acme.oldpkg.MovedDto", "com.acme.newpkg.MovedDto", "MovedDto", "MovedDto"
        )
        assertTrue(movedWildcard.contains("import com.acme.newpkg.MovedDto"))
        assertFalse(KotlinAstSourceMutator.hasSyntaxErrors(movedWildcard))

        // 3. Empty primary constructor, no-constructor class, and nested block comments around trailing comma
        val emptyCtor = "package com.acme\nclass Empty()\n"
        val withEmptyDep = KotlinAstSourceMutator.addConstructorDependencyToTypeDecl(emptyCtor, "Empty", "AuditClient")
        assertTrue(withEmptyDep.contains("class Empty(private val auditClient: AuditClient)"))
        assertFalse(KotlinAstSourceMutator.hasSyntaxErrors(withEmptyDep))

        val noCtor = "package com.acme\nclass Bare<T>\n"
        val withBareDep = KotlinAstSourceMutator.addConstructorDependencyToTypeDecl(noCtor, "Bare", "AuditClient")
        assertTrue(withBareDep.contains("class Bare<T>(private val auditClient: AuditClient)"))
        assertFalse(KotlinAstSourceMutator.hasSyntaxErrors(withBareDep))

        val blockCommentComma = """
            package com.acme
            class Commented(
                private val repo: OrderRepo /* , /* nested , */ */,
            )
        """.trimIndent()
        val withCommentedDep = KotlinAstSourceMutator.addConstructorDependencyToTypeDecl(blockCommentComma, "Commented", "AuditClient")
        assertFalse(withCommentedDep.contains(",,"))
        assertTrue(withCommentedDep.contains("private val auditClient: AuditClient,"))
        assertFalse(KotlinAstSourceMutator.hasSyntaxErrors(withCommentedDep))

        // 4. Bare file without package/imports: rewritePackageDirective & insertImport
        val bare = "class Standalone\n"
        assertTrue(KotlinAstSourceMutator.rewritePackageDirective(bare, "com.acme.bare").startsWith("package com.acme.bare\n\nclass Standalone"))
        assertTrue(KotlinAstSourceMutator.insertImport(bare, "com.acme.Dep").startsWith("import com.acme.Dep\nclass Standalone"))

        // 5. rewireCallerToExtractedInterface deletes old import when caller is already in targetPackage
        val sameTargetPkgCaller = """
            package com.acme.ports

            import com.acme.impl.OrderService

            class PortConsumer(val svc: OrderService)
        """.trimIndent()
        val rewiredSameTarget = KotlinAstSourceMutator.rewireCallerToExtractedInterface(
            sameTargetPkgCaller,
            "com.acme.impl.OrderService",
            "OrderService",
            "com.acme.ports.OrderPort",
            "OrderPort",
            "com.acme.ports"
        )
        assertTrue(rewiredSameTarget.contains("val svc: OrderPort"))
        assertFalse(rewiredSameTarget.contains("import com.acme.impl.OrderService"))
        assertFalse(rewiredSameTarget.contains("import com.acme.ports.OrderPort"))
        assertFalse(KotlinAstSourceMutator.hasSyntaxErrors(rewiredSameTarget))
    }
}
