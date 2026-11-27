package dev.arcdrift.core

import dev.arcdrift.core.ast.JavaAstExtractor
import dev.arcdrift.core.ast.JavaAstSourceMutator
import dev.arcdrift.core.ast.KotlinAstParser
import dev.arcdrift.core.ast.KotlinAstSourceMutator
import dev.arcdrift.core.codec.ArcMermaidParser
import dev.arcdrift.core.codec.ArcMermaidSerializer
import dev.arcdrift.core.contracts.PolyglotContractBridge
import dev.arcdrift.core.model.ArcEdge
import dev.arcdrift.core.model.ArcGraph
import dev.arcdrift.core.model.ArcNode
import dev.arcdrift.core.model.BoundaryRule
import dev.arcdrift.core.model.C4Level
import dev.arcdrift.core.model.ContractEndpoint
import dev.arcdrift.core.model.EdgeKind
import dev.arcdrift.core.model.MemberSignature
import dev.arcdrift.core.model.NodeKind
import dev.arcdrift.core.model.RulePolicy
import dev.arcdrift.core.refactoring.DiskSourceTransaction
import dev.arcdrift.core.refactoring.InMemoryGraphRefactoringAdapter
import dev.arcdrift.core.refactoring.RefactoringCommand
import dev.arcdrift.core.rules.ArcRuleEngine
import dev.arcdrift.core.rules.ArchUnitTestGenerator
import dev.arcdrift.core.scanner.OpenApiAndTypeSpecExtractor
import dev.arcdrift.core.scanner.PolyglotRepoScanner
import dev.arcdrift.core.spi.ArcDriftSpiLoader
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.URL
import java.net.URLClassLoader

/**
 * Multi-case regression suite covering CRLF/bare-CR AST preservation, Linux case-only file collision safety,
 * atomic package moves, ArchUnit annotation generation, wildcard glob semantics, contract map compatibility,
 * Mermaid quoted-comma encoding, OpenAPI/TypeSpec edge cases, and ServiceLoader error isolation.
 */
class SecondPassHardeningRegressionTest {

    @Test
    fun `KotlinAstSourceMutator and KotlinAstParser handle CRLF and bare CR across every method without PSI AssertionError`() {
        for (eol in listOf("\r\n", "\r")) {
            val baseSrc = listOf(
                "package com.acme.orders",
                "",
                "import com.acme.shared.OrderDto",
                "import com.acme.other.UnusedType",
                "",
                "class OrderService(val repo: OrderRepo) {",
                "    fun load(id: String): OrderDto = OrderDto(id)",
                "}"
            ).joinToString(eol) + eol

            // 1. Read-only & analysis methods
            assertFalse(KotlinAstSourceMutator.hasSyntaxErrors(baseSrc, "OrderService.kt"))
            assertEquals("com.acme.orders", KotlinAstSourceMutator.readPackageName(baseSrc))
            assertEquals(1, KotlinAstSourceMutator.countTopLevelDeclarations(baseSrc))
            assertTrue(KotlinAstSourceMutator.hasExplicitImportForSimpleName(baseSrc, "OrderDto"))
            assertFalse(KotlinAstSourceMutator.hasExplicitImportForSimpleName(baseSrc, "MissingType"))
            assertTrue(KotlinAstSourceMutator.containsTypeReference(baseSrc, "OrderDto"))

            val neededImports = KotlinAstSourceMutator.collectReferencedImportLines(
                baseSrc,
                "fun load(id: String): OrderDto$eol"
            )
            assertEquals(listOf("import com.acme.shared.OrderDto"), neededImports)

            // 2. KotlinAstParser line numbers on multi-class CRLF and bare CR files (exercises offsetToLine directly)
            val multiClassSrc = baseSrc + "class SecondHelper$eol"
            val parsedMulti = KotlinAstParser.parseKotlinFile(multiClassSrc, "OrderServiceKt")
            assertEquals(2, parsedMulti.classes.size)
            val firstCls = parsedMulti.classes.first { it.simpleName == "OrderService" }
            val secondCls = parsedMulti.classes.first { it.simpleName == "SecondHelper" }
            assertEquals(6, firstCls.startLine, "Expected OrderService to start on line 6 for EOL=${eol.map { it.code }}")
            assertEquals(8, firstCls.endLine, "Expected OrderService to end on line 8 for EOL=${eol.map { it.code }}")
            assertEquals(9, secondCls.startLine, "Expected SecondHelper to start on line 9 for EOL=${eol.map { it.code }}")

            // 3. All mutation methods preserve EOL and remain syntax-clean
            val pkgUpdated = KotlinAstSourceMutator.rewritePackageDirective(baseSrc, "com.acme.billing")
            assertTrue(pkgUpdated.contains("package com.acme.billing$eol"))
            assertFalse(KotlinAstSourceMutator.hasSyntaxErrors(pkgUpdated))

            val impInserted = KotlinAstSourceMutator.insertImport(baseSrc, "com.acme.audit.AuditClient")
            assertTrue(impInserted.contains("import com.acme.audit.AuditClient$eol"))
            assertFalse(KotlinAstSourceMutator.hasSyntaxErrors(impInserted))

            val implAdded = KotlinAstSourceMutator.addImplementsToTypeDecl(
                baseSrc, "OrderService", "OrderPort", setOf("load")
            )
            assertTrue(implAdded.contains("class OrderService(val repo: OrderRepo) : OrderPort {"))
            assertTrue(implAdded.contains("override fun load("))
            assertFalse(KotlinAstSourceMutator.hasSyntaxErrors(implAdded))

            val extAdded = KotlinAstSourceMutator.addExtendsToTypeDecl(baseSrc, "OrderService", "BaseService")
            assertTrue(extAdded.contains("class OrderService(val repo: OrderRepo) : BaseService() {"))
            assertFalse(KotlinAstSourceMutator.hasSyntaxErrors(extAdded))

            val ctorAdded = KotlinAstSourceMutator.addConstructorDependencyToTypeDecl(baseSrc, "OrderService", "AuditClient")
            assertTrue(ctorAdded.contains("private val auditClient: AuditClient"))
            assertFalse(KotlinAstSourceMutator.hasSyntaxErrors(ctorAdded))

            val renamed = KotlinAstSourceMutator.rewriteSymbolReferences(
                baseSrc, "com.acme.orders.OrderService", "com.acme.orders.BillingService", "OrderService", "BillingService"
            )
            assertTrue(renamed.contains("class BillingService("))
            assertFalse(KotlinAstSourceMutator.hasSyntaxErrors(renamed))

            val callerSrc = listOf(
                "package com.acme.web",
                "import com.acme.orders.OrderService",
                "class OrderController(val svc: OrderService)"
            ).joinToString(eol) + eol
            val rewired = KotlinAstSourceMutator.rewireCallerToExtractedInterface(
                callerSrc,
                "com.acme.orders.OrderService",
                "OrderService",
                "com.acme.ports.OrderPort",
                "OrderPort",
                "com.acme.ports"
            )
            assertTrue(rewired.contains("val svc: OrderPort"))
            assertTrue(rewired.contains("import com.acme.ports.OrderPort"))
            assertFalse(KotlinAstSourceMutator.hasSyntaxErrors(rewired))

            val appended = KotlinAstSourceMutator.renderOrAppendTypeDeclaration(
                existingSource = baseSrc,
                pkg = "com.acme.orders",
                simpleName = "SecondaryHelper",
                nodeKind = NodeKind.CLASS
            )
            assertTrue(appended.contains("class SecondaryHelper"))
            assertFalse(KotlinAstSourceMutator.hasSyntaxErrors(appended))
        }
    }

    @Test
    fun `KotlinAstSourceMutator skips qualified KtUserType, rewrites wildcard member imports, handles trailing commas, and skips unused port import`() {
        // 1. Qualified type reference `com.other.Order` and `Other.Order` must NOT be renamed when renaming `com.acme.Order`
        val qualifiedCollisionSrc = """
            package com.acme

            class Order
            class Holder(
                val mine: Order,
                val externalFq: com.other.Order,
                val externalNested: Other.Order
            )
        """.trimIndent()
        val renamedQualified = KotlinAstSourceMutator.rewriteSymbolReferences(
            qualifiedCollisionSrc,
            "com.acme.Order",
            "com.acme.PurchaseOrder",
            "Order",
            "PurchaseOrder"
        )
        assertTrue(renamedQualified.contains("class PurchaseOrder"))
        assertTrue(renamedQualified.contains("val mine: PurchaseOrder"))
        assertTrue(renamedQualified.contains("val externalFq: com.other.Order"))
        assertTrue(renamedQualified.contains("val externalNested: Other.Order"))
        assertFalse(KotlinAstSourceMutator.hasSyntaxErrors(renamedQualified))

        // 2. Wildcard member/nested imports (`import com.acme.OrderStatus.*`) are rewritten
        val wildcardImportSrc = """
            package com.acme.web

            import com.acme.OrderStatus.*
            import com.acme.OrderStatus.Nested.*

            class StatusConsumer
        """.trimIndent()
        val renamedWildcard = KotlinAstSourceMutator.rewriteSymbolReferences(
            wildcardImportSrc,
            "com.acme.OrderStatus",
            "com.acme.NewOrderStatus",
            "OrderStatus",
            "NewOrderStatus"
        )
        assertTrue(renamedWildcard.contains("import com.acme.NewOrderStatus.*"))
        assertTrue(renamedWildcard.contains("import com.acme.NewOrderStatus.Nested.*"))
        assertFalse(KotlinAstSourceMutator.hasSyntaxErrors(renamedWildcard))

        // 3. Trailing commas in multiline & single-line constructors never emit `,,`
        val multilineTrailing = """
            package com.acme

            class OrderService(
                private val repo: OrderRepo, // existing dep
            )
        """.trimIndent()
        val withMultilineDep = KotlinAstSourceMutator.addConstructorDependencyToTypeDecl(
            multilineTrailing, "OrderService", "AuditClient"
        )
        assertFalse(withMultilineDep.contains(",,"))
        assertTrue(withMultilineDep.contains("private val auditClient: AuditClient,"))
        assertFalse(KotlinAstSourceMutator.hasSyntaxErrors(withMultilineDep))

        val singleLineTrailing = """
            package com.acme
            class OrderService(private val repo: OrderRepo,)
        """.trimIndent()
        val withSingleLineDep = KotlinAstSourceMutator.addConstructorDependencyToTypeDecl(
            singleLineTrailing, "OrderService", "AuditClient"
        )
        assertFalse(withSingleLineDep.contains(",,"))
        assertTrue(withSingleLineDep.contains("private val auditClient: AuditClient"))
        assertFalse(KotlinAstSourceMutator.hasSyntaxErrors(withSingleLineDep))

        // 4. Untouched caller (`class Sub : OrderService()`) must NOT receive an unused port import
        val subclassCaller = """
            package com.acme.web

            import com.acme.orders.OrderService

            class ExtendedOrderService : OrderService()
        """.trimIndent()
        val untouchedRewire = KotlinAstSourceMutator.rewireCallerToExtractedInterface(
            subclassCaller,
            "com.acme.orders.OrderService",
            "OrderService",
            "com.acme.ports.OrderServicePort",
            "OrderServicePort",
            "com.acme.ports"
        )
        assertFalse(untouchedRewire.contains("OrderServicePort"))
        assertTrue(untouchedRewire.contains("import com.acme.orders.OrderService"))
    }

    @Test
    fun `JavaAstSourceMutator handles annotated constructor params, super calls, wildcard static imports, untouched callers, and package-private visibility`() {
        // 1. Annotated param + super(...) call in existing constructor
        val javaCtorSrc = """
            package com.acme;

            public class OrderService extends BaseService {
                private final OrderRepo repo;

                public OrderService(@Qualifier("primary") OrderRepo repo) {
                    super(repo);
                    this.repo = repo;
                }
            }
        """.trimIndent()
        val withDep = JavaAstSourceMutator.addConstructorDependencyToTypeDecl(javaCtorSrc, "OrderService", "AuditClient")
        assertFalse(JavaAstSourceMutator.hasSyntaxErrors(withDep, "OrderService.java"))
        assertTrue(withDep.contains("public OrderService(@Qualifier(\"primary\") OrderRepo repo, AuditClient auditClient)"))
        val superIdx = withDep.indexOf("super(repo);")
        val assignIdx = withDep.indexOf("this.auditClient = auditClient;")
        assertTrue(superIdx > 0 && assignIdx > superIdx, "Assignment must be inserted AFTER super(...): $withDep")

        // 2. Static & nested wildcard imports rewritten cleanly
        val javaWildcardSrc = """
            package com.acme.web;

            import static com.acme.OrderStatus.*;
            import com.acme.OrderStatus.Inner.*;

            public class StatusConsumer {}
        """.trimIndent()
        val renamedJavaWildcard = JavaAstSourceMutator.rewriteSymbolReferences(
            javaWildcardSrc,
            "com.acme.OrderStatus",
            "com.acme.NewOrderStatus",
            "OrderStatus",
            "NewOrderStatus"
        )
        assertTrue(renamedJavaWildcard.contains("import static com.acme.NewOrderStatus.*;"))
        assertTrue(renamedJavaWildcard.contains("import com.acme.NewOrderStatus.Inner.*;"))
        assertFalse(JavaAstSourceMutator.hasSyntaxErrors(renamedJavaWildcard, "StatusConsumer.java"))

        // 3. Untouched Java subclass caller must NOT receive an unused interface import
        val javaSubclass = """
            package com.acme.web;

            import com.acme.orders.OrderService;

            public class SubOrderService extends OrderService {}
        """.trimIndent()
        val untouchedJava = JavaAstSourceMutator.rewireCallerToExtractedInterface(
            javaSubclass,
            "com.acme.orders.OrderService",
            "OrderService",
            "com.acme.ports.OrderServicePort",
            "OrderServicePort",
            "com.acme.ports"
        )
        assertFalse(untouchedJava.contains("OrderServicePort"))

        // 4. JavaAstExtractor distinguishes package-private methods on classes while keeping interface methods public
        val javaVisSrc = """
            package com.acme;

            public class VisibilitySample {
                public void pubMethod() {}
                protected void protMethod() {}
                private void privMethod() {}
                void pkgPrivateMethod() {}
            }

            interface SampleInterface {
                void ifaceMethod();
            }
        """.trimIndent()
        val parsed = JavaAstExtractor.parseJavaFile(javaVisSrc)
        val cls = parsed.classes.first { it.simpleName == "VisibilitySample" }
        assertEquals("public", cls.methods.first { it.name == "pubMethod" }.visibility)
        assertEquals("protected", cls.methods.first { it.name == "protMethod" }.visibility)
        assertEquals("private", cls.methods.first { it.name == "privMethod" }.visibility)
        assertEquals("package-private", cls.methods.first { it.name == "pkgPrivateMethod" }.visibility)
        val iface = parsed.classes.first { it.simpleName == "SampleInterface" }
        assertEquals("public", iface.methods.first { it.name == "ifaceMethod" }.visibility)
    }

    @Test
    fun `Linux distinct-file case-only rename collision refuses overwrite while genuine single-file case rename succeeds`(@TempDir tempDir: File) {
        val pkgDir = File(tempDir, "src/main/kotlin/com/acme").apply { mkdirs() }
        val upperFile = File(pkgDir, "OrderDTO.kt")
        val lowerFile = File(pkgDir, "OrderDto.kt")
        upperFile.writeText("package com.acme\nclass OrderDTO\n")
        lowerFile.writeText("package com.acme\nclass OtherHolder\n")

        // Only run distinct-file assertion on case-sensitive filesystems (Linux)
        val isCaseSensitiveFs = !java.nio.file.Files.isSameFile(upperFile.toPath(), lowerFile.toPath())
        if (isCaseSensitiveFs) {
            val graph = PolyglotRepoScanner.scanRepository(tempDir)
            val adapter = InMemoryGraphRefactoringAdapter(graph, tempDir)
            val res = adapter.execute(
                RefactoringCommand.RenameSymbol("com.acme.OrderDTO", "OrderDto"),
                previewOnly = false
            )
            assertFalse(res.success, "Distinct target file OrderDto.kt must block rename")
            assertEquals("ALREADY_EXISTS", res.errorMessage)
            assertTrue(upperFile.exists() && upperFile.readText().contains("class OrderDTO"))
            assertTrue(lowerFile.exists() && lowerFile.readText().contains("class OtherHolder"))

            // Direct DiskSourceTransaction defense-in-depth check
            assertThrows(IllegalStateException::class.java) {
                DiskSourceTransaction.execute { tx ->
                    tx.moveOrRenameWithContent(upperFile, lowerFile, "package com.acme\nclass OrderDto\n")
                }
            }
            assertTrue(lowerFile.readText().contains("class OtherHolder"))
        }

        // Now remove the colliding file and confirm genuine case-only rename succeeds cleanly
        lowerFile.delete()
        val freshGraph = PolyglotRepoScanner.scanRepository(tempDir)
        val freshAdapter = InMemoryGraphRefactoringAdapter(freshGraph, tempDir)
        val okRes = freshAdapter.execute(
            RefactoringCommand.RenameSymbol("com.acme.OrderDTO", "OrderDto"),
            previewOnly = false
        )
        assertTrue(okRes.success, okRes.summary)
        assertTrue(lowerFile.exists())
        assertTrue(lowerFile.readText().contains("class OrderDto"))
    }

    @Test
    fun `MoveClass and ExtractInterface succeed on CRLF Kotlin files with siblings, and MovePackage rolls back atomically on failure`(@TempDir tempDir: File) {
        val oldDir = File(tempDir, "src/main/kotlin/com/acme/old").apply { mkdirs() }
        val otherDir = File(tempDir, "src/main/kotlin/com/acme/other").apply { mkdirs() }
        File(otherDir, "Status.kt").writeText("package com.acme.other\nenum class Status { OK }\n")
        File(oldDir, "Status.kt").writeText("package com.acme.old\nenum class Status { LEGACY }\n")
        val svcFile = File(oldDir, "OrderService.kt")
        svcFile.writeText(
            "package com.acme.old\r\n" +
                "import com.acme.other.Status\r\n" +
                "class OrderService {\r\n" +
                "    fun check(): Status = Status.OK\r\n" +
                "}\r\n"
        )

        val graph = PolyglotRepoScanner.scanRepository(tempDir)
        val adapter = InMemoryGraphRefactoringAdapter(graph, tempDir)

        // 1. MoveClass on CRLF file with same-package sibling `Status` when `com.acme.other.Status` is already imported
        val moveRes = adapter.execute(
            RefactoringCommand.MoveClassOrPackage("com.acme.old.OrderService", "com.acme.moved"),
            previewOnly = false
        )
        assertTrue(moveRes.success, moveRes.summary)
        val movedFile = File(tempDir, "src/main/kotlin/com/acme/moved/OrderService.kt")
        assertTrue(movedFile.exists())
        val movedText = movedFile.readText()
        assertTrue(movedText.contains("package com.acme.moved\r\n"))
        assertTrue(movedText.contains("import com.acme.other.Status"))
        assertFalse(movedText.contains("import com.acme.old.Status"), "Must not insert conflicting sibling import: $movedText")

        // 2. ExtractInterface on CRLF Kotlin file
        val extractRes = adapter.execute(
            RefactoringCommand.ExtractInterface("com.acme.moved.OrderService", "OrderPort", "com.acme.ports"),
            previewOnly = false
        )
        assertTrue(extractRes.success, extractRes.summary)
        val portFile = File(tempDir, "src/main/kotlin/com/acme/ports/OrderPort.kt")
        assertTrue(portFile.exists())
        assertTrue(portFile.readText().contains("import com.acme.other.Status"))

        // 3. Atomic handleMovePackage rollback when second class in package is unmovable (2 top-level declarations)
        val pkgDir = File(tempDir, "src/main/kotlin/com/acme/batch").apply { mkdirs() }
        val firstFile = File(pkgDir, "Alpha.kt").apply { writeText("package com.acme.batch\nclass Alpha\n") }
        val multiFile = File(pkgDir, "Beta.kt").apply { writeText("package com.acme.batch\nclass Beta\nclass Gamma\n") }
        val callerFile = File(tempDir, "src/main/kotlin/com/acme/web/Caller.kt").apply {
            parentFile.mkdirs()
            writeText("package com.acme.web\nimport com.acme.batch.Alpha\nclass Caller(val a: Alpha)\n")
        }
        val batchAdapter = InMemoryGraphRefactoringAdapter(PolyglotRepoScanner.scanRepository(tempDir), tempDir)
        val failedPkgMove = batchAdapter.execute(
            RefactoringCommand.MoveClassOrPackage("com.acme.batch", "com.acme.batchdest"),
            previewOnly = false
        )
        assertFalse(failedPkgMove.success)
        assertEquals("MULTI_DECLARATION_FILE", failedPkgMove.errorMessage)
        assertTrue(firstFile.exists(), "Alpha.kt must be rolled back on batch failure")
        assertFalse(File(tempDir, "src/main/kotlin/com/acme/batchdest/Alpha.kt").exists())
        assertTrue(callerFile.readText().contains("import com.acme.batch.Alpha"), "Caller.kt import must be rolled back")
        assertTrue(multiFile.exists())
    }

    @Test
    fun `in-memory RenameSymbol and MoveClass update nested child IDs, parentIds, and edges`() {
        val graph = ArcGraph(
            projectName = "NestedTest",
            nodes = listOf(
                ArcNode("com.old.Outer", "Outer", C4Level.L3_COMPONENT, NodeKind.CLASS, parentId = "com.old"),
                ArcNode("com.old.Outer.Inner", "Inner", C4Level.L3_COMPONENT, NodeKind.CLASS, parentId = "com.old"),
                ArcNode("com.web.Caller", "Caller", C4Level.L3_COMPONENT, NodeKind.CLASS, parentId = "com.web")
            ),
            edges = listOf(
                ArcEdge("e1", "com.web.Caller", "com.old.Outer.Inner", EdgeKind.DEPENDS_ON)
            )
        )
        val adapter = InMemoryGraphRefactoringAdapter(graph, rootDir = null)
        val ren = adapter.execute(RefactoringCommand.RenameSymbol("com.old.Outer", "RenamedOuter"), previewOnly = false)
        assertTrue(ren.success)
        assertTrue(adapter.currentGraph.nodes.any { it.id == "com.old.RenamedOuter.Inner" })
        assertTrue(adapter.currentGraph.edges.any { it.targetId == "com.old.RenamedOuter.Inner" })

        val mov = adapter.execute(RefactoringCommand.MoveClassOrPackage("com.old.RenamedOuter", "com.dest"), previewOnly = false)
        assertTrue(mov.success)
        val movedInner = adapter.currentGraph.nodes.first { it.id == "com.dest.RenamedOuter.Inner" }
        assertEquals("com.dest", movedInner.parentId)
        assertTrue(adapter.currentGraph.edges.any { it.targetId == "com.dest.RenamedOuter.Inner" })
    }

    @Test
    fun `ArchUnitTestGenerator emits valid Kotlin syntax for all RulePolicy types with annotation patterns`() {
        val rules = listOf(
            BoundaryRule("r-forbid-ann", "No Ctrl to Repo", "@RestController", "@Repository", RulePolicy.FORBIDDEN, "use service"),
            BoundaryRule("r-allow-ann", "Ctrl only Services", "@RestController", "@Service", RulePolicy.ALLOWED_ONLY, "stay layered"),
            BoundaryRule("r-allow-ann-to-pkg", "Ctrl only domain pkg", "@RestController", "com.acme.domain..*", RulePolicy.ALLOWED_ONLY, "stay in domain"),
            BoundaryRule("r-iface-ann", "Via interface", "com.acme.web..*", "@Repository", RulePolicy.MUST_DEPEND_VIA_INTERFACE, "use port"),
            BoundaryRule("r-escape-ann", "Escaped ann", "@com.acme.Ann\"Quote", "@*Custom\"Ann", RulePolicy.FORBIDDEN, "escape test")
        )
        val generated = ArchUnitTestGenerator.generateKotlinJUnit5("Demo", "com.acme", rules)
        assertFalse(generated.contains("dependOnClassesThat()("), "Must not emit empty parens before predicate: $generated")
        assertFalse(generated.contains(".and().areNotInterfaces()"), "Must not chain .and().areNotInterfaces() on predicate: $generated")
        assertFalse(generated.contains("\"RestController..\""), "Must not treat @RestController as a package matcher: $generated")
        assertTrue(generated.contains("DescribedPredicate.not(JavaClass.Predicates.INTERFACES)"))
        assertFalse(
            KotlinAstSourceMutator.hasSyntaxErrors(generated, "ArcDriftArchitectureTest.kt"),
            "Generated ArchUnit Kotlin code must have zero syntax errors:\n$generated"
        )
    }

    @Test
    fun `ArcRuleEngine patternToRegex matches ArchUnit dot-dot-star and star-dot-dot wildcards accurately`() {
        val pkgStar = ArcRuleEngine.patternToRegex("com.acme..")
        val pkgDotDotStar = ArcRuleEngine.patternToRegex("com.acme..*")
        val leadStarDotDot = ArcRuleEngine.patternToRegex("*..repository..*")
        val midWildcard = ArcRuleEngine.patternToRegex("com.acme.domain..*Service")
        val allWildcard = ArcRuleEngine.patternToRegex("*..*")

        assertTrue(pkgStar.matches("com.acme"))
        assertTrue(pkgDotDotStar.matches("com.acme"))
        assertTrue(pkgDotDotStar.matches("com.acme.OrderService"))
        assertTrue(pkgDotDotStar.matches("com.acme.sub.OrderService"))
        assertFalse(pkgDotDotStar.matches("com.acme2.OrderService"))

        assertTrue(leadStarDotDot.matches("repository"))
        assertTrue(leadStarDotDot.matches("com.acme.repository.OrderRepo"))
        assertTrue(leadStarDotDot.matches("com.acme.repository"))
        assertFalse(leadStarDotDot.matches("com.acme.myrepository.OrderRepo"))

        assertTrue(midWildcard.matches("com.acme.domain.OrderService"))
        assertTrue(midWildcard.matches("com.acme.domain.sub.OrderService"))
        assertFalse(midWildcard.matches("com.acme.web.OrderService"))

        assertTrue(allWildcard.matches("anything.at.all"))
    }

    @Test
    fun `PolyglotContractBridge treats parameterized dict, Map, and Record types as compatible any types`() {
        val producer = ArcNode(
            id = "py.api.routes",
            label = "routes",
            c4Level = C4Level.L3_COMPONENT,
            kind = NodeKind.REST_CONTROLLER,
            contracts = listOf(
                ContractEndpoint(
                    protocol = "REST",
                    methodOrAction = "GET",
                    pathOrTopic = "/api/v1/meta",
                    role = "PRODUCER",
                    responseDto = "MetaDto",
                    dtoFields = mapOf("attrs" to "dict[str, Any]", "counts" to "Mapping[str, int]")
                )
            )
        )
        val consumer = ArcNode(
            id = "ts.client.metaApi",
            label = "metaApi",
            c4Level = C4Level.L3_COMPONENT,
            kind = NodeKind.TS_CLIENT,
            contracts = listOf(
                ContractEndpoint(
                    protocol = "REST",
                    methodOrAction = "GET",
                    pathOrTopic = "/api/v1/meta",
                    role = "CONSUMER",
                    responseDto = "MetaResponse",
                    dtoFields = mapOf("attrs" to "Record<string, unknown>", "counts" to "Map<String, Object>")
                )
            )
        )
        val res = PolyglotContractBridge.linkAndVerify(ArcGraph("PolyMap", listOf(producer, consumer)))
        assertTrue(res.violations.isEmpty(), "Expected zero contract violations, got: ${res.violations}")
    }

    @Test
    fun `ArcMermaidSerializer and ArcMermaidParser round-trip commas inside quoted literal union contract field values`() {
        val original = ArcGraph(
            projectName = "CommaContracts",
            nodes = listOf(
                ArcNode(
                    id = "com.acme.Api",
                    label = "Api",
                    c4Level = C4Level.L3_COMPONENT,
                    kind = NodeKind.REST_CONTROLLER,
                    contracts = listOf(
                        ContractEndpoint(
                            protocol = "REST",
                            methodOrAction = "POST",
                            pathOrTopic = "/v1/events",
                            role = "PRODUCER",
                            dtoFields = linkedMapOf(
                                "mode" to "'a,b' | 'c'",
                                "mapping" to "Record<string, number>",
                                "note" to "\"hello, world\""
                            )
                        )
                    )
                )
            )
        )
        val serialized = ArcMermaidSerializer.serialize(original)
        val parsed = ArcMermaidParser.parse(serialized)
        val fields = parsed.nodes.single().contracts.single().dtoFields
        assertEquals("'a,b' | 'c'", fields["mode"])
        assertEquals("Record<string, number>", fields["mapping"])
        assertEquals("\"hello, world\"", fields["note"])

        // Backward compatibility with legacy unencoded quoted commas
        val legacyMmd = """
            flowchart TB
              %% @arc:node id="com.acme.Legacy" kind="REST_CONTROLLER" level="L3_COMPONENT" contracts="REST|GET|/v1/l|PRODUCER|||mode='a,b'|'c',other=string"
              com_acme_Legacy["Legacy"]
        """.trimIndent()
        val legacyParsed = ArcMermaidParser.parse(legacyMmd)
        val legacyFields = legacyParsed.nodes.single().contracts.single().dtoFields
        assertEquals("'a,b'", legacyFields["mode"]) // pipe is column separator, comma inside quotes preserved
    }

    @Test
    fun `OpenApiAndTypeSpecExtractor handles items before type array, double-slash inside TypeSpec strings, and inline multi-decorator ops`(@TempDir tempDir: File) {
        File(tempDir, "openapi.yaml").writeText(
            """
            openapi: 3.0.3
            components:
              schemas:
                OrderPayload:
                  type: object
                  properties:
                    tags:
                      items:
                        type: string
                      type: array
                    lines:
                      items:
                        ${'$'}ref: '#/components/schemas/LineItem'
                      type: array
                LineItem:
                  type: object
                  properties:
                    sku:
                      type: string
            """.trimIndent()
        )
        File(tempDir, "service.tsp").writeText(
            """
            @route("/api/v1/orders")
            interface Orders {
              @doc("Docs at https://example.com/orders // not a comment")
              @route("/{id}") @post op updateOrder(): OrderPayload;

              @doc("Never use @delete here")
              @get op getOrder(): OrderPayload;
            }
            """.trimIndent()
        )
        val extracted = OpenApiAndTypeSpecExtractor.extract(tempDir, emptySet())
        assertEquals("string[]", extracted.schemas["OrderPayload"]?.get("tags"))
        assertEquals("LineItem[]", extracted.schemas["OrderPayload"]?.get("lines"))
        assertEquals("POST" to "/api/v1/orders/{id}", extracted.operations["updateOrder"])
        assertEquals("GET" to "/api/v1/orders", extracted.operations["getOrder"])
    }

    @Test
    fun `ArcDriftSpiLoader terminates cleanly when ServiceLoader provider file is malformed`(@TempDir tempDir: File) {
        val metaDir = File(tempDir, "META-INF/services").apply { mkdirs() }
        // Invalid class name triggers ServiceConfigurationError during ServiceLoader iteration
        File(metaDir, "dev.arcdrift.core.spi.PolicyPackProvider").writeText("com.nonexistent.BrokenProvider\n")
        val brokenLoader = URLClassLoader(arrayOf(tempDir.toURI().toURL()), null)
        val loaded = ArcDriftSpiLoader.loadPolicyPackProviders(brokenLoader)
        assertTrue(loaded.isEmpty())
    }
}
