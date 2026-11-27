package dev.arcdrift.core.ast

import dev.arcdrift.core.model.NodeKind
import dev.arcdrift.core.scanner.ArcDriftIgnore
import dev.arcdrift.core.scanner.OpenApiAndTypeSpecExtractor
import dev.arcdrift.core.scanner.PolyglotRepoScanner
import dev.arcdrift.core.scanner.RepoAstCache
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression suite for the pre-publish audit of the Tree-sitter Kotlin migration, `.arcdriftignore`,
 * and the incremental AST cache.
 */
class PrePublishAuditRegressionTest {

    // ---------- Tree-sitter: automatic-semicolon false positives ----------

    @Test
    fun `hasSyntaxErrors ignores synthetic automatic semicolons in valid single-line declarations but flags real errors`() {
        val validSingleLine = listOf(
            "package com.acme\nclass A { fun foo() = 1 }\n",
            "package com.acme\ninterface Foo { fun bar(): String }\n",
            "package com.acme\nobject Registry { val items = listOf(1, 2) }\n",
            "package com.acme\nclass D { companion object { fun create() = D() } }\n"
        )
        for (src in validSingleLine) {
            assertFalse(KotlinAstSourceMutator.hasSyntaxErrors(src, "A.kt"), "Expected no syntax errors for:\n$src")
        }
        for (src in validSingleLine.map { it.replace("\n", "\r\n") }) {
            assertFalse(KotlinAstSourceMutator.hasSyntaxErrors(src, "A.kt"), "Expected no syntax errors for CRLF source")
        }

        val broken = listOf(
            "package com.acme\nclass A { fun foo( = 1 }\n",
            "package com.acme\nclass B {\n  fun x() {\n",
            "package com.acme\nclass C(val x: ) \n"
        )
        for (src in broken) {
            assertTrue(KotlinAstSourceMutator.hasSyntaxErrors(src, "A.kt"), "Expected syntax errors for:\n$src")
        }
    }

    @Test
    fun `refactoring post-validation stays active for files containing single-line classes`() {
        val src = "package com.acme\nclass A { fun foo() = 1 }\nclass Holder(val a: A)\n"
        val renamed = KotlinAstSourceMutator.rewriteSymbolReferences(src, "com.acme.A", "com.acme.Alpha", "A", "Alpha")
        assertTrue(renamed.contains("class Alpha { fun foo() = 1 }"))
        assertTrue(renamed.contains("val a: Alpha"))
        assertFalse(KotlinAstSourceMutator.hasSyntaxErrors(renamed, "A.kt"))
    }

    @Test
    fun `single-line nested object bodies are parsed with full class and member extraction`() {
        val src = "package com.acme\nclass D { companion object { fun create() = D() } }\nclass E { object Inner { val x = 1 } }\n"
        val parsed = KotlinAstParser.parseKotlinFile(src, "DKt")
        val names = parsed.classes.map { it.simpleName }
        assertTrue("D" in names && "E" in names && "Inner" in names, "Parsed classes: $names")
        assertEquals(2, KotlinAstSourceMutator.countTopLevelDeclarations(src))
    }

    @Test
    fun `brace recovery shadow preserves length, strings, templates, char literals, and comments`() {
        val src = "val s = \"a } b\" ; val t = \"${'$'}{ x } }\" ; val c = ' ' ; /* x } */ // y }\nclass A { object B { val z = 1 } }"
        val shadow = KotlinTreeSitterSupport.buildBraceRecoveryShadow(src)
        assertEquals(src.length, shadow.length)
        assertEquals(src.toByteArray(Charsets.UTF_8).size, shadow.toByteArray(Charsets.UTF_8).size)
        assertTrue(shadow.startsWith("val s = \"a } b\" ; val t = \"${'$'}{ x } }\" ; val c = ' ' ; /* x } */ // y }\n"))
        assertTrue(shadow.endsWith("class A { object B { val z = 1\n}\n}"), shadow)
        assertEquals("class A {}", KotlinTreeSitterSupport.buildBraceRecoveryShadow("class A {}"))
    }

    @Test
    fun `brace recovery never masks genuinely broken code and mutations keep original text`() {
        assertTrue(KotlinAstSourceMutator.hasSyntaxErrors("package com.acme\nclass D { companion object { fun create( = D() } }\n", "D.kt"))
        val src = "package com.acme\nclass D { companion object { fun create() = D() } }\n"
        val renamed = KotlinAstSourceMutator.rewriteSymbolReferences(src, "com.acme.D", "com.acme.Dee", "D", "Dee")
        assertEquals("package com.acme\nclass Dee { companion object { fun create() = Dee() } }\n", renamed)
    }

    // ---------- Tree-sitter: comment nodes ----------

    @Test
    fun `comments inside annotation arguments, expression bodies, and named arguments are skipped`() {
        val src = """
            package com.acme.web

            @RestController
            class ItemController {
                @GetMapping(path = /* versioned */ "/v1/items")
                fun list() = /* dto */ ItemDto()
            }
        """.trimIndent()
        val parsed = KotlinAstParser.parseKotlinFile(src, "ItemControllerKt")
        val cls = parsed.classes.single { it.simpleName == "ItemController" }
        val method = cls.methods.single { it.name == "list" }
        val getMapping = method.annotations.single { it.name == "GetMapping" }
        assertEquals(listOf("/v1/items"), getMapping.allPathsOrValues())
        assertEquals("ItemDto", method.inferredReturnTypeName)

        val callSrc = "package com.acme\nclass Order\nfun build() = call(Order /* label */ = Order())\n"
        val renamed = KotlinAstSourceMutator.rewriteSymbolReferences(callSrc, "com.acme.Order", "com.acme.NewOrder", "Order", "NewOrder")
        assertTrue(renamed.contains("call(Order /* label */ = NewOrder())"), "Named argument label must not be renamed:\n$renamed")
        assertTrue(renamed.contains("class NewOrder"))
    }

    // ---------- rewriteSymbolReferences: shadowed imports & qualified types ----------

    @Test
    fun `rename and move never touch a same-named type imported from another package`() {
        val shadowed = "package com.acme.oldpkg\nimport com.other.Order\nclass Consumer(val o: Order)\n"

        val renamed = KotlinAstSourceMutator.rewriteSymbolReferences(
            shadowed, "com.acme.oldpkg.Order", "com.acme.oldpkg.PurchaseOrder", "Order", "PurchaseOrder"
        )
        assertEquals(shadowed, renamed)

        val moved = KotlinAstSourceMutator.rewriteSymbolReferences(
            shadowed, "com.acme.oldpkg.Order", "com.acme.newpkg.Order", "Order", "Order"
        )
        assertEquals(shadowed, moved, "Must not add a conflicting import com.acme.newpkg.Order")
    }

    @Test
    fun `move does not insert imports for fully qualified foreign types and still imports genuinely referenced siblings`() {
        val qualifiedForeign = "package com.acme.oldpkg\nclass Consumer(val o: com.other.Order)\n"
        val moved = KotlinAstSourceMutator.rewriteSymbolReferences(
            qualifiedForeign, "com.acme.oldpkg.Order", "com.acme.newpkg.Order", "Order", "Order"
        )
        assertFalse(moved.contains("import com.acme.newpkg.Order"), "Spurious import inserted:\n$moved")

        val sameFqRewritten = "package com.acme.oldpkg\nclass Consumer(val o: com.acme.oldpkg.Order)\n"
        val movedFq = KotlinAstSourceMutator.rewriteSymbolReferences(
            sameFqRewritten, "com.acme.oldpkg.Order", "com.acme.newpkg.Order", "Order", "Order"
        )
        assertTrue(movedFq.contains("val o: com.acme.newpkg.Order"))
        assertFalse(movedFq.contains("import com.acme.newpkg.Order"))

        val unqualifiedSibling = "package com.acme.oldpkg\nclass Consumer(val o: Order)\n"
        val movedSibling = KotlinAstSourceMutator.rewriteSymbolReferences(
            unqualifiedSibling, "com.acme.oldpkg.Order", "com.acme.newpkg.Order", "Order", "Order"
        )
        assertTrue(movedSibling.contains("import com.acme.newpkg.Order"))
    }

    @Test
    fun `files in the destination package are not rewritten unless they referenced the old symbol`() {
        val destFile = "package com.acme.newpkg\nclass Order\nclass LocalUser(val o: Order)\n"
        val renamed = KotlinAstSourceMutator.rewriteSymbolReferences(
            destFile, "com.acme.oldpkg.Order", "com.acme.newpkg.PurchaseOrder", "Order", "PurchaseOrder"
        )
        assertEquals(destFile, renamed)

        val importingDest = "package com.acme.newpkg\nimport com.acme.oldpkg.Order\nclass User(val o: Order)\n"
        val movedIntoDest = KotlinAstSourceMutator.rewriteSymbolReferences(
            importingDest, "com.acme.oldpkg.Order", "com.acme.newpkg.PurchaseOrder", "Order", "PurchaseOrder"
        )
        assertTrue(movedIntoDest.contains("val o: PurchaseOrder"))
    }

    // ---------- rewireCallerToExtractedInterface ----------

    @Test
    fun `rewire only rewrites unqualified references that resolve to the extracted source class`() {
        val foreign = "package com.acme.web\nimport com.other.OrderService\nclass Web(val svc: OrderService)\n"
        val untouched = KotlinAstSourceMutator.rewireCallerToExtractedInterface(
            foreign, "com.acme.orders.OrderService", "OrderService", "com.acme.ports.OrderPort", "OrderPort", "com.acme.ports"
        )
        assertEquals(foreign, untouched)

        val samePkg = "package com.acme.orders\nclass Web(val svc: OrderService)\n"
        val rewiredSamePkg = KotlinAstSourceMutator.rewireCallerToExtractedInterface(
            samePkg, "com.acme.orders.OrderService", "OrderService", "com.acme.ports.OrderPort", "OrderPort", "com.acme.ports"
        )
        assertTrue(rewiredSamePkg.contains("val svc: OrderPort"))
        assertTrue(rewiredSamePkg.contains("import com.acme.ports.OrderPort"))
    }

    @Test
    fun `rewire removes the dead concrete import even when qualified foreign types or member access share the simple name`() {
        val withQualifiedForeign = "package com.acme.web\nimport com.acme.orders.OrderService\nclass W(val svc: OrderService, val other: com.other.OrderService)\n"
        val out1 = KotlinAstSourceMutator.rewireCallerToExtractedInterface(
            withQualifiedForeign, "com.acme.orders.OrderService", "OrderService", "com.acme.ports.OrderPort", "OrderPort", "com.acme.ports"
        )
        assertFalse(out1.contains("import com.acme.orders.OrderService"), out1)
        assertTrue(out1.contains("import com.acme.ports.OrderPort"))
        assertTrue(out1.contains("val other: com.other.OrderService"))

        val withMemberAccess = "package com.acme.web\nimport com.acme.orders.OrderService\nclass W(val svc: OrderService) {\n    fun f(other: Holder) = other.OrderService\n}\n"
        val out2 = KotlinAstSourceMutator.rewireCallerToExtractedInterface(
            withMemberAccess, "com.acme.orders.OrderService", "OrderService", "com.acme.ports.OrderPort", "OrderPort", "com.acme.ports"
        )
        assertFalse(out2.contains("import com.acme.orders.OrderService"), out2)
        assertTrue(out2.contains("other.OrderService"))
    }

    // ---------- File annotation edge cases & EOL consistency ----------

    @Test
    fun `package and import insertion after a trailing file annotation never glue onto the same line`() {
        val src = "@file:JvmName(\"Utils\")"
        val withPkg = KotlinAstSourceMutator.rewritePackageDirective(src, "com.acme")
        assertTrue(withPkg.lines().any { it.trim() == "package com.acme" }, withPkg)
        assertFalse(KotlinAstSourceMutator.hasSyntaxErrors(withPkg, "Utils.kt"))

        val withImport = KotlinAstSourceMutator.insertImport(src, "com.acme.Foo")
        assertTrue(withImport.lines().any { it.trim() == "import com.acme.Foo" }, withImport)
        assertFalse(KotlinAstSourceMutator.hasSyntaxErrors(withImport, "Utils.kt"))
    }

    @Test
    fun `rendered extracted interface source uses LF only regardless of platform line separator`() {
        val rendered = KotlinAstSourceMutator.renderExtractedInterfaceSource(
            srcOriginalText = "package com.acme\nclass S { fun a(): String = \"\" }\n",
            srcPkg = "com.acme",
            targetPackage = "com.acme.ports",
            interfaceSimpleName = "SPort",
            extractedMembers = listOf(
                dev.arcdrift.core.model.MemberSignature("a", "METHOD", "(): String", "public"),
                dev.arcdrift.core.model.MemberSignature("b", "METHOD", "(x: Int): Int", "public")
            ),
            graphNodes = emptyList()
        )
        assertFalse(rendered.contains('\r'))
        assertTrue(rendered.startsWith("package com.acme.ports\n\ninterface SPort {\n"))
    }

    @Test
    fun `byte-to-char table encodes unpaired surrogates as a single UTF-8 replacement byte`() {
        val src = "a\uD800b"
        val bytes = src.toByteArray(Charsets.UTF_8)
        val table = ParsedKotlinCst.buildByteToCharTable(src, bytes.size)
        assertEquals(3, bytes.size)
        assertEquals(listOf(0, 1, 2, 3), table.toList())
    }

    // ---------- .arcdriftignore BOM & spec/OpenAPI scoping ----------

    @Test
    fun `arcdriftignore strips a UTF-8 BOM from the first rule`() {
        val ignore = ArcDriftIgnore.parse("\uFEFFgenerated/**\nvendor/\n")
        assertTrue(ignore.isPathIgnored("generated/Api.kt"))
        assertTrue(ignore.isPathIgnored("vendor/lib.ts"))
    }

    @Test
    fun `ignored directories are excluded from spec discovery and OpenAPI or TypeSpec extraction`(@TempDir root: File) {
        File(root, ".arcdriftignore").writeText("vendor/\ngenerated/**\n")
        File(root, "architecture.arc.mmd").writeText("flowchart TB\n  %% @arc:rule id=\"root-rule\" name=\"Root\" from=\"*.a.*\" to=\"*.b.*\" policy=\"FORBIDDEN\"\n")
        File(root, "vendor/thirdparty.arc.mmd").apply {
            parentFile.mkdirs()
            writeText("flowchart TB\n  %% @arc:rule id=\"vendor-rule\" name=\"Vendor\" from=\"*.x.*\" to=\"*.y.*\" policy=\"FORBIDDEN\"\n")
        }
        File(root, "generated/openapi.yaml").apply {
            parentFile.mkdirs()
            writeText(
                """
                openapi: 3.0.3
                paths:
                  /generated:
                    get:
                      operationId: generatedOp
                components:
                  schemas:
                    GeneratedDto:
                      type: object
                      properties:
                        id:
                          type: string
                """.trimIndent()
            )
        }
        File(root, "vendor/svc.tsp").writeText("@route(\"/vendor\")\ninterface V {\n  @get op vendorOp(): string;\n}\n")
        File(root, "api/openapi.yaml").apply {
            parentFile.mkdirs()
            writeText("openapi: 3.0.3\npaths:\n  /real:\n    get:\n      operationId: realOp\n")
        }

        val ignore = ArcDriftIgnore.load(root)
        val specs = PolyglotRepoScanner.findSpecFiles(root, ignore).map { it.name }
        assertEquals(listOf("architecture.arc.mmd"), specs)
        assertEquals(2, PolyglotRepoScanner.findSpecFiles(root).size, "Default overload keeps legacy behaviour")

        val extracted = OpenApiAndTypeSpecExtractor.extract(root, emptySet(), emptyMap(), ignore)
        assertTrue("realOp" in extracted.operations)
        assertFalse("generatedOp" in extracted.operations)
        assertFalse("vendorOp" in extracted.operations)
        assertFalse(extracted.schemas.containsKey("GeneratedDto"))

        val graph = PolyglotRepoScanner.scanRepository(root)
        assertTrue(graph.rules.any { it.id == "root-rule" })
        assertFalse(graph.rules.any { it.id == "vendor-rule" })
    }

    // ---------- RepoAstCache ----------

    @Test
    fun `concurrent loadFromDisk callers all observe fully populated entries and transient failures can be retried`(@TempDir cacheDir: File) {
        val seed = RepoAstCache()
        repeat(50) { i ->
            seed.getOrParsePy("x_$i = $i\n") { dev.arcdrift.core.ast.PythonAstParser.parsePythonFile("x_$i = $i\n") }
        }
        seed.saveToDisk(cacheDir)
        assertTrue(File(cacheDir, RepoAstCache.CACHE_FILE_NAME).isFile)
        assertTrue(cacheDir.listFiles().orEmpty().none { it.name.endsWith(".tmp") })

        val shared = RepoAstCache()
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        try {
            val futures = (0 until 8).map {
                pool.submit(Callable {
                    start.await()
                    shared.loadFromDisk(cacheDir)
                    shared.stats().entries
                })
            }
            start.countDown()
            futures.forEach { assertEquals(50, it.get(), "Every caller must see the fully loaded cache") }
        } finally {
            pool.shutdownNow()
        }

        // Transient corruption must not permanently lock the directory out
        val retryDir = File(cacheDir, "retry").apply { mkdirs() }
        File(retryDir, RepoAstCache.CACHE_FILE_NAME).writeText("{ not json")
        val retrying = RepoAstCache()
        retrying.loadFromDisk(retryDir)
        assertEquals(0, retrying.stats().entries)
        File(cacheDir, RepoAstCache.CACHE_FILE_NAME).copyTo(File(retryDir, RepoAstCache.CACHE_FILE_NAME), overwrite = true)
        retrying.loadFromDisk(retryDir)
        assertEquals(50, retrying.stats().entries)
    }

    @Test
    fun `loadFromDisk respects maxEntries`(@TempDir cacheDir: File) {
        val seed = RepoAstCache()
        repeat(40) { i ->
            seed.getOrParsePy("y_$i = $i\n") { dev.arcdrift.core.ast.PythonAstParser.parsePythonFile("y_$i = $i\n") }
        }
        seed.saveToDisk(cacheDir)
        val bounded = RepoAstCache(maxEntries = 10)
        bounded.loadFromDisk(cacheDir)
        assertEquals(10, bounded.stats().entries)
    }

    // ---------- Kotlin 1.9 `data object` ----------

    @Test
    fun `data object declarations parse cleanly on one line and multiple lines`() {
        val cases = mapOf(
            "package com.acme\ndata object EmptyState\n" to listOf("EmptyState"),
            "package com.acme\ndata object EmptyState : State\n" to listOf("EmptyState"),
            "package com.acme\ndata object EmptyState { val code = 200 }\n" to listOf("EmptyState"),
            "package com.acme\ndata object EmptyState : State { fun label() = \"empty\" }\n" to listOf("EmptyState"),
            "package com.acme\nsealed interface UiState { data object Loading : UiState; data object Idle : UiState }\n" to
                listOf("UiState", "Loading", "Idle"),
            "package com.acme\nsealed interface UiState {\n    data object Loading : UiState\n    data class Ready(val id: String) : UiState\n}\n" to
                listOf("UiState", "Loading", "Ready")
        )
        for ((src, expected) in cases) {
            assertFalse(KotlinAstSourceMutator.hasSyntaxErrors(src, "S.kt"), "syntax errors for:\n$src")
            val parsed = KotlinAstParser.parseKotlinFile(src, "SKt")
            val names = parsed.classes.map { it.simpleName }
            assertEquals(expected.sorted(), names.sorted(), "classes for:\n$src")
        }
        val top = "package com.acme\ndata object A\ndata object B : S\nobject C\n"
        assertEquals(3, KotlinAstSourceMutator.countTopLevelDeclarations(top))
        val kinds = KotlinAstParser.parseKotlinFile(top, "TKt").classes.associate { it.simpleName to it.kind }
        assertEquals(NodeKind.DATA_CLASS, kinds["A"])
        assertEquals(NodeKind.CLASS, kinds["C"])
    }

    @Test
    fun `data object can be renamed and receives interfaces and supertypes`() {
        val src = "package com.acme\ndata object EmptyState : State\nfun f(): State = EmptyState\n"
        val renamed = KotlinAstSourceMutator.rewriteSymbolReferences(src, "com.acme.EmptyState", "com.acme.NoItems", "EmptyState", "NoItems")
        assertEquals("package com.acme\ndata object NoItems : State\nfun f(): State = NoItems\n", renamed)

        val bare = "package com.acme\ndata object EmptyState\n"
        val impl = KotlinAstSourceMutator.addImplementsToTypeDecl(bare, "EmptyState", "State", emptySet())
        assertEquals("package com.acme\ndata object EmptyState : State\n", impl)

        val oneLine = "package com.acme\ndata object EmptyState { val code = 200 }\n"
        val impl2 = KotlinAstSourceMutator.addImplementsToTypeDecl(oneLine, "EmptyState", "State", emptySet())
        assertEquals("package com.acme\ndata object EmptyState : State { val code = 200 }\n", impl2)
        assertFalse(KotlinAstSourceMutator.hasSyntaxErrors(impl2, "S.kt"))

        val already = KotlinAstSourceMutator.addImplementsToTypeDecl(impl, "EmptyState", "State", emptySet())
        assertEquals(impl, already)
    }
}
