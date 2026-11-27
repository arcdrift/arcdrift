package dev.arcdrift.core.ast

import dev.arcdrift.core.model.ArcNode
import dev.arcdrift.core.model.MemberSignature
import dev.arcdrift.core.model.NodeKind

/**
 * Supported JVM source languages for AST-level structural rewrites.
 */
enum class JvmSourceLanguage {
    KOTLIN,
    JAVA
}

/**
 * Client-agnostic JVM AST source rewriting contract.
 *
 * Implementations ([KotlinAstSourceMutator] for Tree-sitter Kotlin CST and [JavaAstSourceMutator] for
 * JavaParser AST) encapsulate all parse-tree inspection, token-offset edits, import management,
 * declaration generation, and caller rewiring without leaking Tree-sitter or JavaParser AST types
 * into callers (CLI, MCP, [dev.arcdrift.core.refactoring.InMemoryGraphRefactoringAdapter],
 * [dev.arcdrift.core.refactoring.DiskSourceRewriter], or IDE adapters).
 */
interface JvmAstSourceRewriter {

    val language: JvmSourceLanguage

    fun hasSyntaxErrors(source: String, fileName: String = defaultDummyFileName()): Boolean

    fun readPackageName(source: String): String

    fun countTopLevelDeclarations(source: String): Int

    fun rewritePackageDirective(source: String, newPackage: String): String

    fun insertImport(source: String, fqImport: String): String

    fun hasExplicitImportForSimpleName(source: String, simpleName: String): Boolean

    fun collectReferencedImportLines(source: String, methodsStub: String): List<String>

    fun containsTypeReference(methodsStub: String, simpleName: String): Boolean

    fun extractNeededImports(
        srcText: String,
        methodsText: String,
        srcPkg: String,
        targetPkg: String,
        graphNodes: List<ArcNode> = emptyList()
    ): List<String>

    fun addImplementsToTypeDecl(
        source: String,
        className: String,
        interfaceSimpleName: String,
        overrideMethodNames: Set<String> = emptySet()
    ): String

    fun addExtendsToTypeDecl(
        source: String,
        className: String,
        superSimpleName: String
    ): String

    fun addConstructorDependencyToTypeDecl(
        source: String,
        className: String,
        targetSimpleName: String
    ): String

    fun rewriteSymbolReferences(
        source: String,
        oldFqName: String,
        newFqName: String,
        oldSimple: String,
        newSimple: String
    ): String

    fun rewireCallerToExtractedInterface(
        source: String,
        sourceClassFqName: String,
        sourceSimpleName: String,
        interfaceFqName: String,
        interfaceSimpleName: String,
        targetPackage: String
    ): String

    fun renderExtractedInterfaceSource(
        srcOriginalText: String,
        srcPkg: String,
        targetPackage: String,
        interfaceSimpleName: String,
        extractedMembers: List<MemberSignature>,
        graphNodes: List<ArcNode> = emptyList()
    ): String

    fun renderOrAppendTypeDeclaration(
        existingSource: String?,
        pkg: String?,
        simpleName: String,
        nodeKind: NodeKind,
        sanitizedAnnotations: List<String> = emptyList()
    ): String

    private fun defaultDummyFileName(): String =
        if (language == JvmSourceLanguage.JAVA) "Check.java" else "Check.kt"
}

/**
 * Dispatcher and factory for language-specific [JvmAstSourceRewriter] implementations.
 */
object JvmAstRewriters {

    fun forLanguage(language: JvmSourceLanguage): JvmAstSourceRewriter = when (language) {
        JvmSourceLanguage.KOTLIN -> KotlinAstSourceMutator
        JvmSourceLanguage.JAVA -> JavaAstSourceMutator
    }

    fun forLanguage(isJava: Boolean): JvmAstSourceRewriter =
        if (isJava) JavaAstSourceMutator else KotlinAstSourceMutator

    fun forFileName(fileName: String): JvmAstSourceRewriter =
        if (fileName.endsWith(".java", ignoreCase = true)) JavaAstSourceMutator else KotlinAstSourceMutator
}
