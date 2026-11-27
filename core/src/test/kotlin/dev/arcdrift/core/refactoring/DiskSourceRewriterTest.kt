package dev.arcdrift.core.refactoring

import dev.arcdrift.core.model.ArcNode
import dev.arcdrift.core.model.C4Level
import dev.arcdrift.core.model.MemberSignature
import dev.arcdrift.core.model.NodeKind
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiskSourceRewriterTest {

    @Test
    fun `insertImport places import after package and avoids duplicates for Kotlin and Java`() {
        val ktSrc = "package com.acme.svc\n\nclass OrderService"
        val withImport = DiskSourceRewriter.insertImport(ktSrc, "com.acme.port.OrderPort", isJava = false)
        assertTrue(withImport.contains("package com.acme.svc\nimport com.acme.port.OrderPort\n"))
        assertEquals(withImport, DiskSourceRewriter.insertImport(withImport, "com.acme.port.OrderPort", isJava = false))

        val javaSrc = "package com.acme.svc;\n\npublic class OrderService {}"
        val javaWithImport = DiskSourceRewriter.insertImport(javaSrc, "com.acme.port.OrderPort", isJava = true)
        assertTrue(javaWithImport.contains("package com.acme.svc;\nimport com.acme.port.OrderPort;\n"))
    }

    @Test
    fun `addImplementsToTypeDecl appends interface to Java classes without duplicating`() {
        val plainJava = "package com.acme;\npublic class OrderService {\n}"
        val withImpl = DiskSourceRewriter.addImplementsToTypeDecl(
            content = plainJava,
            className = "OrderService",
            interfaceSimpleName = "OrderPort",
            isJava = true
        )
        assertTrue(withImpl.contains("public class OrderService implements OrderPort {"))

        val alreadyImpl = "package com.acme;\npublic class OrderService implements Serializable {\n}"
        val withSecond = DiskSourceRewriter.addImplementsToTypeDecl(
            content = alreadyImpl,
            className = "OrderService",
            interfaceSimpleName = "OrderPort",
            isJava = true
        )
        assertTrue(withSecond.contains("implements Serializable, OrderPort"))
        assertEquals(
            withSecond,
            DiskSourceRewriter.addImplementsToTypeDecl(
                content = withSecond,
                className = "OrderService",
                interfaceSimpleName = "OrderPort",
                isJava = true
            )
        )
    }

    @Test
    fun `renderExtractedInterfaceSource includes only needed imports and sibling package DTO imports`() {
        val src = """
            package com.acme.service
            import com.acme.dto.OrderDto
            import com.acme.dto.OrderDtoBuilder
            import com.acme.unused.HelperUtil

            class OrderService {
                fun findOrder(id: String): OrderDto = TODO()
            }
        """.trimIndent()
        val nodes = listOf(
            ArcNode("com.acme.service.LocalStatus", "LocalStatus", C4Level.L3_COMPONENT, NodeKind.CLASS, parentId = "com.acme.service")
        )
        val rendered = DiskSourceRewriter.renderExtractedInterfaceSource(
            srcOriginalText = src,
            srcPkg = "com.acme.service",
            targetPackage = "com.acme.port",
            interfaceSimpleName = "OrderPort",
            extractedMembers = listOf(
                MemberSignature("findOrder", "METHOD", "(id: String): OrderDto", "public"),
                MemberSignature("status", "METHOD", "(): LocalStatus", "public")
            ),
            isJava = false,
            graphNodes = nodes
        )
        assertTrue(rendered.contains("package com.acme.port"))
        assertTrue(rendered.contains("import com.acme.dto.OrderDto"))
        assertFalse(rendered.contains("OrderDtoBuilder"), "Word-boundary import check must not copy prefix-colliding imports")
        assertTrue(rendered.contains("import com.acme.service.LocalStatus"))
        assertFalse(rendered.contains("HelperUtil"))
        assertTrue(rendered.contains("interface OrderPort {"))
        assertTrue(rendered.contains("fun findOrder(id: String): OrderDto"))

        // Also test Java interface rendering with generic parameters and addExtendsToJavaTypeDecl
        val javaRendered = DiskSourceRewriter.renderExtractedInterfaceSource(
            srcOriginalText = "package com.acme.service;\nimport com.acme.dto.OrderDto;\npublic class OrderService {}",
            srcPkg = "com.acme.service",
            targetPackage = "com.acme.port",
            interfaceSimpleName = "OrderPort",
            extractedMembers = listOf(
                MemberSignature("find", "METHOD", "(tags: Map<String, Int>, id: String): OrderDto", "public"),
                MemberSignature("ping", "METHOD", "(): Unit", "public")
            ),
            isJava = true,
            graphNodes = nodes
        )
        assertTrue(javaRendered.contains("public interface OrderPort {"))
        assertTrue(javaRendered.contains("OrderDto find(Map<String, Int> tags, String id);"))
        assertTrue(javaRendered.contains("void ping();"))

        val withExt = DiskSourceRewriter.addExtendsToJavaTypeDecl("public class Child implements Serializable {}", "Child", "Base")
        assertTrue(withExt.contains("public class Child extends Base implements Serializable {"))
        assertEquals(withExt, DiskSourceRewriter.addExtendsToJavaTypeDecl(withExt, "Child", "OtherBase"))
        val plainExt = DiskSourceRewriter.addExtendsToJavaTypeDecl("public class Plain {}", "Plain", "Base")
        assertTrue(plainExt.contains("public class Plain extends Base {"))
    }

    @Test
    fun `inferSourceRoot and findExistingSourceRoot detect kotlin and java source roots`() {
        val tempDir = Files.createTempDirectory("arcdrift-ref-root").toFile()
        try {
            val javaFile = tempDir.resolve("module-a/src/main/java/com/acme/App.java").apply {
                parentFile.mkdirs()
                writeText("package com.acme; class App {}")
            }
            val root = DiskSourceRewriter.findExistingSourceRoot(tempDir)
            assertEquals(tempDir.resolve("module-a/src/main/java").canonicalPath, root.canonicalPath)
            assertEquals(
                javaFile.canonicalPath,
                DiskSourceRewriter.resolveRepoFile(tempDir, "module-a/src/main/java/com/acme/App.java").canonicalPath
            )
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `AST-backed Kotlin and Java rewriters handle irregular whitespace, prefix-colliding imports, comments, string literals, and annotations with braces`() {
        // 1. Kotlin AST rewriteSymbolReferences: must NOT touch prefix-colliding `import com.acme.OrderService`, comments, or string literals
        val ktSource = """
            package   com.acme.billing
            import   com.acme.domain.Order
            import   com.acme.domain.OrderService

            // Comment mentioning Order and com.acme.domain.Order
            class BillingProcessor(
                private val primary:   Order,
                private val service: OrderService
            ) {
                val sql = "SELECT * FROM Order WHERE id = 'com.acme.domain.Order'"
                fun process(item: com.acme.domain.Order): List<Order> {
                    val created = Order("1")
                    return listOf(created, item)
                }
            }
        """.trimIndent()

        val ktRewritten = DiskSourceRewriter.rewriteSymbolInSingleFile(
            source = ktSource,
            oldFqName = "com.acme.domain.Order",
            newFqName = "com.acme.orders.PurchaseOrder",
            oldSimple = "Order",
            newSimple = "PurchaseOrder",
            isJava = false
        )
        assertTrue(ktRewritten.contains("import   com.acme.orders.PurchaseOrder"))
        assertTrue(ktRewritten.contains("import   com.acme.domain.OrderService"), "Prefix-colliding import OrderService must remain untouched")
        assertTrue(ktRewritten.contains("// Comment mentioning Order and com.acme.domain.Order"), "Comments must remain untouched")
        assertTrue(ktRewritten.contains("\"SELECT * FROM Order WHERE id = 'com.acme.domain.Order'\""), "String literals must remain untouched")
        assertTrue(ktRewritten.contains("private val primary:   PurchaseOrder"))
        assertTrue(ktRewritten.contains("fun process(item: com.acme.orders.PurchaseOrder): List<PurchaseOrder>"))
        assertTrue(ktRewritten.contains("val created = PurchaseOrder(\"1\")"))

        // 2. Java AST rewriteSymbolReferences & addImplementsToTypeDecl with @SuppressWarnings({"unchecked"}) braces and irregular whitespace
        val javaSource = """
            package   com.acme.billing ;
            import    com.acme.domain.Order ;
            import    com.acme.domain.OrderService ;

            /* Block comment with package com.fake; and Order */
            @SuppressWarnings({"unchecked", "rawtypes"})
            public class BillingProcessor {
                private final String query = "SELECT * FROM Order";
                private final Order primary;
                private final OrderService svc;

                public BillingProcessor(Order primary, OrderService svc) {
                    this.primary = primary;
                    this.svc = svc;
                }
            }
        """.trimIndent()

        val javaWithImpl = DiskSourceRewriter.addImplementsToTypeDecl(
            content = javaSource,
            className = "BillingProcessor",
            interfaceSimpleName = "BillingPort",
            isJava = true
        )
        assertTrue(
            javaWithImpl.contains("public class BillingProcessor implements BillingPort {"),
            "JavaParser AST must add implements after class name even when annotation contains braces, got:\n$javaWithImpl"
        )

        val javaRewritten = DiskSourceRewriter.rewriteSymbolInSingleFile(
            source = javaWithImpl,
            oldFqName = "com.acme.domain.Order",
            newFqName = "com.acme.orders.PurchaseOrder",
            oldSimple = "Order",
            newSimple = "PurchaseOrder",
            isJava = true
        )
        assertTrue(javaRewritten.contains("import    com.acme.orders.PurchaseOrder ;"))
        assertTrue(javaRewritten.contains("import    com.acme.domain.OrderService ;"), "Java prefix-colliding import OrderService must remain untouched")
        assertTrue(javaRewritten.contains("\"SELECT * FROM Order\""), "Java string literal must remain untouched")
        assertTrue(javaRewritten.contains("private final PurchaseOrder primary;"))
        assertTrue(javaRewritten.contains("public BillingProcessor(PurchaseOrder primary, OrderService svc)"))

        // 3. Java signature rendering without return type suffix or without space after colon
        val javaNoSpaceSig = DiskSourceRewriter.renderExtractedInterfaceSource(
            srcOriginalText = "package com.acme;\npublic class Svc {}",
            srcPkg = "com.acme",
            targetPackage = "com.acme",
            interfaceSimpleName = "SvcPort",
            extractedMembers = listOf(
                MemberSignature("compute", "METHOD", "(id:String):Int", "public"),
                MemberSignature("fire", "METHOD", "(String event)", "public")
            ),
            isJava = true,
            graphNodes = emptyList()
        )
        assertTrue(javaNoSpaceSig.contains("Int compute(String id);"), "Got:\n$javaNoSpaceSig")
        assertTrue(javaNoSpaceSig.contains("void fire(String event);"), "Got:\n$javaNoSpaceSig")

        // 4. Java caller rewiring (fields, constructor parameters, generic types, qualified types) while preserving `extends ConcreteService`
        val javaCallerSrc = """
            package com.acme.caller;
            import com.acme.service.OrderService;
            import com.acme.service.OrderService.SubConfig;
            import java.util.List;

            public class JavaOrderCaller extends OrderService {
                private final OrderService directField;
                private final List<OrderService> serviceList;
                private final com.acme.service.OrderService fqField;

                public JavaOrderCaller(OrderService param, List<com.acme.service.OrderService> fqList) {
                    this.directField = param;
                    this.serviceList = null;
                    this.fqField = param;
                    OrderService.staticHelper();
                }
            }
        """.trimIndent()
        val rewiredJavaCaller = dev.arcdrift.core.ast.JavaAstSourceMutator.rewireCallerToExtractedInterface(
            source = javaCallerSrc,
            sourceClassFqName = "com.acme.service.OrderService",
            sourceSimpleName = "OrderService",
            interfaceFqName = "com.acme.port.OrderPort",
            interfaceSimpleName = "OrderPort",
            targetPackage = "com.acme.port"
        )
        assertTrue(rewiredJavaCaller.contains("import com.acme.port.OrderPort;"))
        assertTrue(rewiredJavaCaller.contains("extends OrderService"), "Java `extends OrderService` must not be rewritten to interface")
        assertTrue(rewiredJavaCaller.contains("private final OrderPort directField;"))
        assertTrue(rewiredJavaCaller.contains("private final List<OrderPort> serviceList;"))
        assertTrue(rewiredJavaCaller.contains("private final OrderPort fqField;"))
        assertTrue(rewiredJavaCaller.contains("public JavaOrderCaller(OrderPort param, List<OrderPort> fqList)"))

        // 5. Java rewritePackageDirective (with and without existing package), wildcard import dedup, qualified type & method reference rewrites
        val noPkgJava = "public class NoPkg { com.acme.old.Item x; }"
        val withPkgJava = DiskSourceRewriter.rewritePackageDirective(noPkgJava, "com.acme.moved", isJava = true)
        assertTrue(withPkgJava.startsWith("package com.acme.moved;"))
        val updatedPkgJava = DiskSourceRewriter.rewritePackageDirective(withPkgJava, "com.acme.renamed", isJava = true)
        assertTrue(updatedPkgJava.startsWith("package com.acme.renamed;"))

        val wildcardJava = """
            package com.acme.consumer;
            import com.acme.old.*;
            import com.acme.old.Item.NestedEnum;

            public class Consumer<T> {
                private com.acme.old.Item<String> qualifiedGeneric;
                public void run() {
                    Object cls = com.acme.old.Item.class;
                    Runnable r = Item::staticRun;
                    Item.staticCall();
                }
            }
        """.trimIndent()
        val wildcardAdded = DiskSourceRewriter.insertImport(wildcardJava, "com.acme.old.AlreadyCoveredByStar", isJava = true)
        assertEquals(wildcardJava, wildcardAdded, "Wildcard import of package must suppress redundant single-type import")

        val rewrittenWildcardJava = DiskSourceRewriter.rewriteSymbolInSingleFile(
            source = wildcardJava,
            oldFqName = "com.acme.old.Item",
            newFqName = "com.acme.newpkg.RenamedItem",
            oldSimple = "Item",
            newSimple = "RenamedItem",
            isJava = true
        )
        assertTrue(rewrittenWildcardJava.contains("import com.acme.newpkg.RenamedItem.NestedEnum;"))
        assertTrue(rewrittenWildcardJava.contains("import com.acme.newpkg.RenamedItem;"))
        assertTrue(rewrittenWildcardJava.contains("com.acme.newpkg.RenamedItem<String> qualifiedGeneric;"))
        assertTrue(rewrittenWildcardJava.contains("RenamedItem::staticRun"))
        assertTrue(rewrittenWildcardJava.contains("RenamedItem.staticCall()"))

        val genericJava = "package com.acme;\npublic class GenericBox<T> {}"
        val genericExt = DiskSourceRewriter.addExtendsToJavaTypeDecl(genericJava, "GenericBox", "BaseBox")
        assertTrue(genericExt.contains("public class GenericBox<T> extends BaseBox"))
        val genericImpl = DiskSourceRewriter.addImplementsToTypeDecl(genericJava, "GenericBox", "BoxPort", isJava = true)
        assertTrue(genericImpl.contains("public class GenericBox<T> implements BoxPort"))
    }
}
