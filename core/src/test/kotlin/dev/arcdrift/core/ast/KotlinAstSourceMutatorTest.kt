package dev.arcdrift.core.ast

import dev.arcdrift.core.model.ArcNode
import dev.arcdrift.core.model.C4Level
import dev.arcdrift.core.model.MemberSignature
import dev.arcdrift.core.model.NodeKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class KotlinAstSourceMutatorTest {

    @Test
    fun `JvmAstRewriters dispatches to KotlinAstSourceMutator and JavaAstSourceMutator`() {
        assertSame(KotlinAstSourceMutator, JvmAstRewriters.forLanguage(JvmSourceLanguage.KOTLIN))
        assertSame(JavaAstSourceMutator, JvmAstRewriters.forLanguage(JvmSourceLanguage.JAVA))
        assertSame(KotlinAstSourceMutator, JvmAstRewriters.forLanguage(isJava = false))
        assertSame(JavaAstSourceMutator, JvmAstRewriters.forLanguage(isJava = true))
        assertSame(KotlinAstSourceMutator, JvmAstRewriters.forFileName("OrderService.kt"))
        assertSame(JavaAstSourceMutator, JvmAstRewriters.forFileName("OrderService.java"))
        assertEquals(JvmSourceLanguage.KOTLIN, KotlinAstSourceMutator.language)
        assertEquals(JvmSourceLanguage.JAVA, JavaAstSourceMutator.language)
    }

    @Test
    fun `KotlinAstSourceMutator and JavaAstSourceMutator implement full JvmAstSourceRewriter contract`() {
        val ktRewriter = JvmAstRewriters.forLanguage(JvmSourceLanguage.KOTLIN)
        val javaRewriter = JvmAstRewriters.forLanguage(JvmSourceLanguage.JAVA)

        assertFalse(ktRewriter.hasSyntaxErrors("package com.acme\nclass Good"))
        assertTrue(ktRewriter.hasSyntaxErrors("package com.acme\nclass Broken { fun ("))
        assertFalse(javaRewriter.hasSyntaxErrors("package com.acme;\npublic class Good {}"))
        assertTrue(javaRewriter.hasSyntaxErrors("package com.acme;\npublic class Broken { void ("))

        assertEquals("com.acme.svc", ktRewriter.readPackageName("package com.acme.svc\nclass A"))
        assertEquals("com.acme.svc", javaRewriter.readPackageName("package com.acme.svc;\npublic class A {}"))
        assertEquals(2, ktRewriter.countTopLevelDeclarations("package com.acme\nclass A\ninterface B"))
        assertEquals(2, javaRewriter.countTopLevelDeclarations("package com.acme;\nclass A {}\ninterface B {}"))

        val src = """
            package com.acme

            class OrderService(private val repo: String) {
                fun placeOrder(id: String): String = id
                fun helper() {}
            }
        """.trimIndent()

        val mutated = ktRewriter.addImplementsToTypeDecl(
            source = src,
            className = "OrderService",
            interfaceSimpleName = "OrderPort",
            overrideMethodNames = setOf("placeOrder")
        )
        assertTrue(mutated.contains("class OrderService(private val repo: String) : OrderPort {"))
        assertTrue(mutated.contains("override fun placeOrder(id: String): String = id"))
        assertFalse(mutated.contains("override fun helper()"))
        assertFalse(ktRewriter.hasSyntaxErrors(mutated))

        // Test addExtendsToTypeDecl on Kotlin (both without existing supertypes and with an existing interface supertype)
        val withExtends = ktRewriter.addExtendsToTypeDecl(
            source = "package com.acme\nclass SpecialOrderService(val id: String)",
            className = "SpecialOrderService",
            superSimpleName = "BaseOrderService"
        )
        assertTrue(withExtends.contains("class SpecialOrderService(val id: String) : BaseOrderService()"))
        assertEquals(withExtends, ktRewriter.addExtendsToTypeDecl(withExtends, "SpecialOrderService", "OtherBase"))

        val withExistingInterface = ktRewriter.addExtendsToTypeDecl(
            source = "package com.acme\nclass SpecialOrderService : OrderPort",
            className = "SpecialOrderService",
            superSimpleName = "BaseOrderService"
        )
        assertTrue(withExistingInterface.contains("class SpecialOrderService : BaseOrderService(), OrderPort"))
    }

    @Test
    fun `renderOrAppendTypeDeclaration generates and deduplicates Kotlin and Java declarations`() {
        val ktRewriter = JvmAstRewriters.forLanguage(JvmSourceLanguage.KOTLIN)
        val javaRewriter = JvmAstRewriters.forLanguage(JvmSourceLanguage.JAVA)

        val ktCreated = ktRewriter.renderOrAppendTypeDeclaration(
            existingSource = null,
            pkg = "com.acme.domain",
            simpleName = "OrderEvent",
            nodeKind = NodeKind.DATA_CLASS,
            sanitizedAnnotations = listOf("@Serializable")
        )
        assertTrue(ktCreated.contains("package com.acme.domain"))
        assertTrue(ktCreated.contains("@Serializable\ndata class OrderEvent(val id: String)"))
        // Appending same simpleName is a no-op
        assertEquals(
            ktCreated,
            ktRewriter.renderOrAppendTypeDeclaration(ktCreated, "com.acme.domain", "OrderEvent", NodeKind.DATA_CLASS)
        )
        // Appending a second type appends cleanly
        val ktAppended = ktRewriter.renderOrAppendTypeDeclaration(
            existingSource = ktCreated,
            pkg = "com.acme.domain",
            simpleName = "OrderStatus",
            nodeKind = NodeKind.ENUM,
            sanitizedAnnotations = listOf("Deprecated")
        )
        assertTrue(ktAppended.contains("@Deprecated\nenum class OrderStatus"))
        val ktIface = ktRewriter.renderOrAppendTypeDeclaration(null, "com.acme", "Port", NodeKind.INTERFACE)
        assertTrue(ktIface.contains("interface Port"))

        // Java declarations (class, interface, record, enum)
        val javaCreated = javaRewriter.renderOrAppendTypeDeclaration(
            existingSource = null,
            pkg = "com.acme.domain",
            simpleName = "OrderRecord",
            nodeKind = NodeKind.DATA_CLASS,
            sanitizedAnnotations = listOf("@Generated")
        )
        assertTrue(javaCreated.contains("package com.acme.domain;"))
        assertTrue(javaCreated.contains("@Generated\npublic record OrderRecord(String id)"))
        assertEquals(
            javaCreated,
            javaRewriter.renderOrAppendTypeDeclaration(javaCreated, "com.acme.domain", "OrderRecord", NodeKind.DATA_CLASS)
        )
        val javaAppended = javaRewriter.renderOrAppendTypeDeclaration(
            existingSource = javaCreated,
            pkg = "com.acme.domain",
            simpleName = "OrderState",
            nodeKind = NodeKind.ENUM,
            sanitizedAnnotations = listOf("Deprecated")
        )
        assertTrue(javaAppended.contains("@Deprecated\nenum OrderState"))
        val javaIface = javaRewriter.renderOrAppendTypeDeclaration(null, "com.acme", "Port", NodeKind.INTERFACE)
        assertTrue(javaIface.contains("public interface Port"))
        val javaClass = javaRewriter.renderOrAppendTypeDeclaration(null, "com.acme", "Svc", NodeKind.CLASS)
        assertTrue(javaClass.contains("public class Svc"))
    }

    @Test
    fun `KotlinTreeSitterSupport offsetToLine computes 1-based line numbers`() {
        val text = "line1\nline2\nline3"
        assertEquals(1, KotlinTreeSitterSupport.offsetToLine(text, 0))
        assertEquals(2, KotlinTreeSitterSupport.offsetToLine(text, 6))
        assertEquals(3, KotlinTreeSitterSupport.offsetToLine(text, 12))
        assertEquals(3, KotlinTreeSitterSupport.offsetToLine(text, 999))
    }
}
