package dev.arcdrift.core.ast

import dev.arcdrift.core.ast.KotlinTreeSitterSupport.children
import dev.arcdrift.core.ast.KotlinTreeSitterSupport.childrenOfType
import dev.arcdrift.core.ast.KotlinTreeSitterSupport.classDeclarationName
import dev.arcdrift.core.ast.KotlinTreeSitterSupport.classDeclarationNameNode
import dev.arcdrift.core.ast.KotlinTreeSitterSupport.directBodyDeclarations
import dev.arcdrift.core.ast.KotlinTreeSitterSupport.findModifierKeywordNode
import dev.arcdrift.core.ast.KotlinTreeSitterSupport.firstChildOfType
import dev.arcdrift.core.ast.KotlinTreeSitterSupport.hasModifierKeyword
import dev.arcdrift.core.ast.KotlinTreeSitterSupport.isClassOrObjectNode
import dev.arcdrift.core.ast.KotlinTreeSitterSupport.isEnumDecl
import dev.arcdrift.core.ast.KotlinTreeSitterSupport.isInterfaceDecl
import dev.arcdrift.core.ast.KotlinTreeSitterSupport.namedChildren
import dev.arcdrift.core.ast.KotlinTreeSitterSupport.qualifiedUserTypeText
import dev.arcdrift.core.ast.KotlinTreeSitterSupport.readImports
import dev.arcdrift.core.ast.KotlinTreeSitterSupport.readPackageHeaderNode
import dev.arcdrift.core.ast.KotlinTreeSitterSupport.readPackageName as readPkgFromCst
import dev.arcdrift.core.ast.KotlinTreeSitterSupport.userTypeNameEndChar
import dev.arcdrift.core.ast.KotlinTreeSitterSupport.walkNamed
import dev.arcdrift.core.model.ArcNode
import dev.arcdrift.core.model.MemberSignature
import dev.arcdrift.core.model.NodeKind
import org.treesitter.TSNode

/**
 * Lock-free Tree-sitter Kotlin AST source rewriter (`io.github.bonede:tree-sitter-kotlin`)
 * implementing [JvmAstSourceRewriter].
 *
 * All mutations operate on parsed Tree-sitter Kotlin CST nodes and apply exact UTF-16 character
 * range replacements in reverse-offset order — preserving comments, string literals, whitespace,
 * line endings, and unrelated identifiers without requiring IntelliJ PSI or `kotlin-compiler-embeddable`.
 */
object KotlinAstSourceMutator : JvmAstSourceRewriter {

    override val language: JvmSourceLanguage = JvmSourceLanguage.KOTLIN

    private val KOTLIN_DEFAULT_IMPORTS_BUILTINS = setOf(
        "Any", "Unit", "Nothing", "Boolean", "Byte", "Short", "Int", "Long", "Float", "Double",
        "Char", "String", "CharSequence", "Number", "Throwable", "Exception", "RuntimeException",
        "Array", "List", "MutableList", "Set", "MutableSet", "Map", "MutableMap",
        "Collection", "MutableCollection", "Iterable", "MutableIterable", "Sequence",
        "Pair", "Triple", "Result", "Comparable"
    )

    private data class TextRangeEdit(val startOffset: Int, val endOffset: Int, val replacement: String)

    private fun detectEol(source: String): String = when {
        source.contains("\r\n") -> "\r\n"
        source.contains('\r') -> "\r"
        else -> "\n"
    }

    private fun normalizeLf(source: String): String =
        source.replace("\r\n", "\n").replace('\r', '\n')

    private fun parseLf(source: String): ParsedKotlinCst =
        KotlinTreeSitterSupport.parse(normalizeLf(source))

    private fun advancePastLineBreak(source: String, offset: Int): Int {
        var pos = offset.coerceIn(0, source.length)
        while (pos < source.length && source[pos] != '\n' && source[pos] != '\r') {
            pos++
        }
        if (pos < source.length && source[pos] == '\r') pos++
        if (pos < source.length && source[pos] == '\n') pos++
        return pos
    }

    override fun hasSyntaxErrors(source: String, fileName: String): Boolean =
        KotlinTreeSitterSupport.hasSyntaxErrors(parseLf(source))

    override fun readPackageName(source: String): String =
        readPkgFromCst(parseLf(source))

    override fun countTopLevelDeclarations(source: String): Int {
        val cst = parseLf(source)
        return namedChildren(cst.root).count { isClassOrObjectNode(it) }
    }

    private inline fun withNormalizedLineEndings(source: String, block: (lfSource: String) -> String): String {
        val eol = detectEol(source)
        val lfSource = normalizeLf(source)
        val resultLf = block(lfSource)
        return if (eol == "\n") resultLf else resultLf.replace("\n", eol)
    }

    override fun rewritePackageDirective(source: String, newPackage: String): String =
        withNormalizedLineEndings(source) { lfSource ->
            val cst = KotlinTreeSitterSupport.parse(lfSource)
            val pkgHeader = readPackageHeaderNode(cst)
            if (pkgHeader != null) {
                applyRangeEdits(
                    lfSource,
                    listOf(TextRangeEdit(cst.startChar(pkgHeader), cst.endChar(pkgHeader), "package $newPackage"))
                )
            } else {
                val lastFileAnn = childrenOfType(cst.root, "file_annotation").lastOrNull()
                if (lastFileAnn != null) {
                    val insertPos = advancePastLineBreak(lfSource, cst.endChar(lastFileAnn))
                    if (insertPos < lfSource.length || lfSource.endsWith("\n")) {
                        lfSource.substring(0, insertPos) + "package $newPackage\n" + lfSource.substring(insertPos)
                    } else {
                        lfSource + "\npackage $newPackage\n"
                    }
                } else {
                    "package $newPackage\n\n$lfSource"
                }
            }
        }

    override fun insertImport(source: String, fqImport: String): String =
        withNormalizedLineEndings(source) { lfSource ->
            insertImportNormalized(lfSource, fqImport)
        }

    override fun hasExplicitImportForSimpleName(source: String, simpleName: String): Boolean {
        val cst = parseLf(source)
        return readImports(cst).any { imp ->
            if (imp.isWildcard) return@any false
            val visible = imp.aliasName ?: imp.fqName.substringAfterLast('.')
            visible == simpleName
        }
    }

    private fun insertImportNormalized(source: String, fqImport: String): String {
        val eol = detectEol(source)
        val cst = KotlinTreeSitterSupport.parse(source)
        val imports = readImports(cst)
        val targetPkg = fqImport.substringBeforeLast('.', "")
        val alreadyImported = imports.any { imp ->
            if (imp.isWildcard) {
                targetPkg.isNotEmpty() && imp.fqName == targetPkg
            } else {
                imp.fqName == fqImport && imp.aliasName == null
            }
        }
        if (alreadyImported) return source

        val importStmt = "import $fqImport"
        val lastImp = imports.lastOrNull()
        if (lastImp != null) {
            val lineEnd = advancePastLineBreak(source, cst.endChar(lastImp.node))
            return if (lineEnd < source.length || source.endsWith("\n") || source.endsWith("\r")) {
                source.substring(0, lineEnd) + "$importStmt$eol" + source.substring(lineEnd)
            } else {
                source + "$eol$importStmt"
            }
        }
        val pkgHeader = readPackageHeaderNode(cst)
        if (pkgHeader != null) {
            val afterPkgLine = advancePastLineBreak(source, cst.endChar(pkgHeader))
            return if (afterPkgLine < source.length || source.endsWith("\n") || source.endsWith("\r")) {
                source.substring(0, afterPkgLine) + "$importStmt$eol" + source.substring(afterPkgLine)
            } else {
                source + "$eol$importStmt$eol"
            }
        }
        val lastFileAnn = childrenOfType(cst.root, "file_annotation").lastOrNull()
        if (lastFileAnn != null) {
            val afterAnnLine = advancePastLineBreak(source, cst.endChar(lastFileAnn))
            return if (afterAnnLine < source.length || source.endsWith("\n") || source.endsWith("\r")) {
                source.substring(0, afterAnnLine) + "$importStmt$eol" + source.substring(afterAnnLine)
            } else {
                source + "$eol$importStmt$eol"
            }
        }
        return "$importStmt$eol$source"
    }

    override fun collectReferencedImportLines(
        source: String,
        methodsStub: String
    ): List<String> = collectReferencedImportLinesInternal(source, methodsStub)

    private fun collectReferencedImportLinesInternal(
        source: String,
        methodsStub: String
    ): List<String> {
        val srcCst = parseLf(source)
        val stubCst = KotlinTreeSitterSupport.parse("interface __Stub {\n${normalizeLf(methodsStub)}\n}")

        val referencedTypeSimples = mutableSetOf<String>()
        val referencedAllSimples = mutableSetOf<String>()
        walkNamed(stubCst.root) { node ->
            when (node.type) {
                "user_type" -> {
                    val idParts = namedChildren(node)
                        .filter { it.type == "type_identifier" || it.type == "simple_identifier" }
                        .map { stubCst.slice(it).trim() }
                        .filter { it.isNotEmpty() }
                    val rootType = idParts.firstOrNull()
                    if (!rootType.isNullOrEmpty() && rootType[0].isUpperCase()) {
                        // Only the root qualifier (e.g. `Outer` in `Outer.Inner`) requires an import;
                        // FQN types like `com.acme.Outer` start with a lowercase package segment.
                        referencedAllSimples.add(rootType)
                        referencedTypeSimples.add(rootType)
                    }
                }
                "simple_identifier" -> {
                    if (isExpressionReferenceIdentifier(node)) {
                        val t = stubCst.slice(node).trim()
                        if (t.isNotEmpty()) referencedAllSimples.add(t)
                    }
                }
            }
        }

        val imports = readImports(srcCst)
        val explicitlySatisfiedSimples = mutableSetOf<String>()
        val kept = mutableListOf<String>()
        for (imp in imports) {
            if (imp.isWildcard) continue
            val visibleSimple = imp.aliasName ?: imp.fqName.substringAfterLast('.')
            if (visibleSimple in referencedAllSimples) {
                explicitlySatisfiedSimples.add(visibleSimple)
                kept.add("import " + srcCst.slice(imp.node).removePrefix("import").trim())
            }
        }
        // Only retain wildcard imports if the method signatures reference non-builtin types not covered by explicit imports
        val unresolvedTypeSimples = referencedTypeSimples - explicitlySatisfiedSimples - KOTLIN_DEFAULT_IMPORTS_BUILTINS
        if (unresolvedTypeSimples.isNotEmpty()) {
            for (imp in imports) {
                if (imp.isWildcard) {
                    kept.add("import " + srcCst.slice(imp.node).removePrefix("import").trim())
                }
            }
        }
        return kept.distinct()
    }

    override fun containsTypeReference(methodsStub: String, simpleName: String): Boolean =
        containsTypeReferenceInternal(methodsStub, simpleName)

    private fun containsTypeReferenceInternal(methodsStub: String, simpleName: String): Boolean {
        val lfStub = normalizeLf(methodsStub)
        val wrapped = if (lfStub.contains("class ") || lfStub.contains("interface ") || lfStub.contains("object ") || lfStub.contains("package ")) {
            lfStub
        } else {
            "interface __Stub {\n$lfStub\n}"
        }
        val stubCst = KotlinTreeSitterSupport.parse(wrapped)
        var found = false
        walkNamed(stubCst.root) { node ->
            if (found) return@walkNamed
            if (isInsideImportOrPackage(node)) return@walkNamed
            when (node.type) {
                "type_identifier" -> {
                    val parent = node.parent
                    val parentType = parent?.type
                    if (parentType == "callable_reference" && stubCst.slice(node).trim() == simpleName) {
                        found = true
                    } else if (parentType == "user_type" && !parent.isNull) {
                        val firstPart = namedChildren(parent)
                            .firstOrNull { it.type == "type_identifier" || it.type == "simple_identifier" }
                        if (firstPart != null &&
                            firstPart.startByte == node.startByte &&
                            stubCst.slice(node).trim() == simpleName
                        ) {
                            found = true
                        }
                    }
                }
                "simple_identifier" -> {
                    if (isExpressionReferenceIdentifier(node) && stubCst.slice(node).trim() == simpleName) {
                        found = true
                    }
                }
            }
        }
        return found
    }

    override fun extractNeededImports(
        srcText: String,
        methodsText: String,
        srcPkg: String,
        targetPkg: String,
        graphNodes: List<ArcNode>
    ): List<String> = extractNeededImportsInternal(srcText, methodsText, srcPkg, targetPkg, graphNodes)

    private fun extractNeededImportsInternal(
        srcText: String,
        methodsText: String,
        srcPkg: String,
        targetPkg: String,
        graphNodes: List<ArcNode>
    ): List<String> {
        val result = collectReferencedImportLinesInternal(srcText, methodsText).toMutableList()
        if (srcPkg.isNotEmpty() && srcPkg != targetPkg) {
            for (node in graphNodes) {
                if (node.parentId == srcPkg && containsTypeReferenceInternal(methodsText, node.label)) {
                    val stmt = "import ${node.id}"
                    if (stmt !in result) result.add(stmt)
                }
            }
        }
        return result.distinct()
    }

    override fun addImplementsToTypeDecl(
        source: String,
        className: String,
        interfaceSimpleName: String,
        overrideMethodNames: Set<String>
    ): String = withNormalizedLineEndings(source) { lfSource ->
        val cst = KotlinTreeSitterSupport.parse(lfSource)
        val cls = findTargetClassDecl(cst, className) ?: return@withNormalizedLineEndings lfSource

        val edits = mutableListOf<TextRangeEdit>()
        val delegSpecs = childrenOfType(cls, "delegation_specifier")
        if (delegSpecs.isNotEmpty()) {
            val alreadyPresent = delegSpecs.any { spec ->
                extractDelegationSuperTypeSimpleName(cst, spec) == interfaceSimpleName
            }
            if (!alreadyPresent) {
                val endOffset = KotlinTreeSitterSupport.delegationSpecifierHeaderEndChar(cst, delegSpecs.last())
                edits.add(TextRangeEdit(endOffset, endOffset, ", $interfaceSimpleName"))
            }
        } else {
            val headerEnd = classHeaderEndChar(cst, cls)
            if (headerEnd != null) {
                edits.add(TextRangeEdit(headerEnd, headerEnd, " : $interfaceSimpleName"))
            }
        }

        if (overrideMethodNames.isNotEmpty()) {
            for (fn in directBodyDeclarations(cls).filter { it.type == "function_declaration" }) {
                val fnName = firstChildOfType(fn, "simple_identifier")?.let { cst.slice(it).trim() } ?: continue
                val isPublicMethod = !hasModifierKeyword(cst, fn, "private") &&
                    !hasModifierKeyword(cst, fn, "protected") &&
                    !hasModifierKeyword(cst, fn, "internal")
                if (fnName in overrideMethodNames &&
                    isPublicMethod &&
                    !hasModifierKeyword(cst, fn, "override")
                ) {
                    val anchorNode = findModifierKeywordNode(cst, fn, "suspend")
                        ?: findAnonTokenNode(fn, "fun")
                        ?: continue
                    val anchorOffset = cst.startChar(anchorNode)
                    edits.add(TextRangeEdit(anchorOffset, anchorOffset, "override "))
                }
            }
        }

        applyRangeEdits(lfSource, edits)
    }

    override fun addExtendsToTypeDecl(
        source: String,
        className: String,
        superSimpleName: String
    ): String = withNormalizedLineEndings(source) { lfSource ->
        val cst = KotlinTreeSitterSupport.parse(lfSource)
        val cls = findTargetClassDecl(cst, className) ?: return@withNormalizedLineEndings lfSource

        if (isEnumDecl(cst, cls)) return@withNormalizedLineEndings lfSource
        val isInterface = isInterfaceDecl(cls)
        val hasPrimaryCtor = firstChildOfType(cls, "primary_constructor") != null
        val hasSecondaryCtors = directBodyDeclarations(cls).any { it.type == "secondary_constructor" }
        val hasOnlySecondaryCtors = !isInterface && !hasPrimaryCtor && hasSecondaryCtors
        val superEntryText = if (isInterface || hasOnlySecondaryCtors) superSimpleName else "$superSimpleName()"

        val delegSpecs = childrenOfType(cls, "delegation_specifier")
        if (delegSpecs.isNotEmpty()) {
            val alreadyPresent = delegSpecs.any { spec ->
                (!isInterface && firstChildOfType(spec, "constructor_invocation") != null) ||
                    extractDelegationSuperTypeSimpleName(cst, spec) == superSimpleName
            }
            if (alreadyPresent) return@withNormalizedLineEndings lfSource
            return@withNormalizedLineEndings if (isInterface) {
                val endOffset = cst.endChar(delegSpecs.last())
                applyRangeEdits(lfSource, listOf(TextRangeEdit(endOffset, endOffset, ", $superEntryText")))
            } else {
                val startOffset = cst.startChar(delegSpecs.first())
                applyRangeEdits(lfSource, listOf(TextRangeEdit(startOffset, startOffset, "$superEntryText, ")))
            }
        }
        val headerEnd = classHeaderEndChar(cst, cls) ?: return@withNormalizedLineEndings lfSource
        applyRangeEdits(lfSource, listOf(TextRangeEdit(headerEnd, headerEnd, " : $superEntryText")))
    }

    override fun addConstructorDependencyToTypeDecl(
        source: String,
        className: String,
        targetSimpleName: String
    ): String = withNormalizedLineEndings(source) { lfSource ->
        val cst = KotlinTreeSitterSupport.parse(lfSource)
        val cls = findTargetClassDecl(cst, className) ?: return@withNormalizedLineEndings lfSource

        if (cls.type != "class_declaration" || isInterfaceDecl(cls) || isEnumDecl(cst, cls)) {
            return@withNormalizedLineEndings lfSource
        }
        val propBaseName = targetSimpleName.replaceFirstChar { it.lowercaseChar() }

        val primaryCtor = firstChildOfType(cls, "primary_constructor")
        val ctorParams = primaryCtor?.let { childrenOfType(it, "class_parameter") }.orEmpty()
        val existingParamOrProp = ctorParams.any { param ->
            val pName = firstChildOfType(param, "simple_identifier")?.let { cst.slice(it).trim() }
            val pType = namedChildren(param).firstOrNull { KotlinTreeSitterSupport.isTypeNode(it) }
                ?.let { cst.slice(it).substringBefore('<').trim() }
            pName == propBaseName || pType == targetSimpleName
        } || directBodyDeclarations(cls).any { decl ->
            when (decl.type) {
                "property_declaration" -> firstChildOfType(decl, "variable_declaration")
                    ?.let { firstChildOfType(it, "simple_identifier") }
                    ?.let { cst.slice(it).trim() } == propBaseName
                "function_declaration" -> firstChildOfType(decl, "simple_identifier")
                    ?.let { cst.slice(it).trim() } == propBaseName
                else -> false
            }
        }
        if (existingParamOrProp) return@withNormalizedLineEndings lfSource

        if (primaryCtor != null) {
            val rightParen = findLastAnonTokenNode(primaryCtor, ")")
            val closeParenOffset = rightParen?.let { cst.startChar(it) } ?: (cst.endChar(primaryCtor) - 1)
            if (ctorParams.isEmpty()) {
                return@withNormalizedLineEndings applyRangeEdits(
                    lfSource,
                    listOf(TextRangeEdit(closeParenOffset, closeParenOffset, "private val $propBaseName: $targetSimpleName"))
                )
            }
            val lastParam = ctorParams.last()
            val trailingCommaOffset = findTrailingCommaOffset(lfSource, cst.endChar(lastParam), closeParenOffset)
            if (trailingCommaOffset != null) {
                val isMultiline = lfSource.substring(cst.startChar(primaryCtor), closeParenOffset).contains('\n')
                if (isMultiline) {
                    val indent = extractLineIndent(lfSource, cst.startChar(lastParam))
                    val afterCommaLine = advancePastLineBreak(lfSource, trailingCommaOffset + 1)
                    val insertOffset = if (afterCommaLine <= closeParenOffset) afterCommaLine else closeParenOffset
                    return@withNormalizedLineEndings applyRangeEdits(
                        lfSource,
                        listOf(TextRangeEdit(insertOffset, insertOffset, "${indent}private val $propBaseName: $targetSimpleName,\n"))
                    )
                }
                return@withNormalizedLineEndings applyRangeEdits(
                    lfSource,
                    listOf(TextRangeEdit(closeParenOffset, closeParenOffset, " private val $propBaseName: $targetSimpleName"))
                )
            }
            return@withNormalizedLineEndings applyRangeEdits(
                lfSource,
                listOf(TextRangeEdit(closeParenOffset, closeParenOffset, ", private val $propBaseName: $targetSimpleName"))
            )
        }

        val anchorEnd = firstChildOfType(cls, "type_parameters")?.let { cst.endChar(it) }
            ?: classDeclarationNameNode(cls)?.let { cst.endChar(it) }
            ?: return@withNormalizedLineEndings lfSource
        applyRangeEdits(lfSource, listOf(TextRangeEdit(anchorEnd, anchorEnd, "(private val $propBaseName: $targetSimpleName)")))
    }

    override fun rewriteSymbolReferences(
        source: String,
        oldFqName: String,
        newFqName: String,
        oldSimple: String,
        newSimple: String
    ): String = withNormalizedLineEndings(source) { lfSource ->
        val cst = KotlinTreeSitterSupport.parse(lfSource)
        val filePkg = readPkgFromCst(cst)
        val (oldTruePkg, oldTypeChain) = splitPackageAndTypeChain(oldFqName, filePkg)
        val (newTruePkg, newTypeChain) = splitPackageAndTypeChain(newFqName, oldTruePkg.takeIf { newFqName.startsWith("$it.") } ?: "")

        val edits = mutableListOf<TextRangeEdit>()
        var hasExplicitImportOfOld = false
        var hasWildcardImportOfOldPkg = false
        var hasConflictingExplicitImport = false
        var aliasForOld: String? = null

        for (imp in readImports(cst)) {
            val idNode = imp.identifierNode ?: continue
            if (imp.isWildcard) {
                if (oldTruePkg.isNotEmpty() && imp.fqName == oldTruePkg) {
                    hasWildcardImportOfOldPkg = true
                } else if (imp.fqName == oldFqName) {
                    edits.add(TextRangeEdit(cst.startChar(idNode), cst.endChar(idNode), newFqName))
                } else if (imp.fqName.startsWith("$oldFqName.")) {
                    val suffix = imp.fqName.removePrefix(oldFqName)
                    edits.add(TextRangeEdit(cst.startChar(idNode), cst.endChar(idNode), "$newFqName$suffix"))
                }
            } else if (imp.fqName == oldFqName) {
                hasExplicitImportOfOld = true
                aliasForOld = imp.aliasName
                edits.add(TextRangeEdit(cst.startChar(idNode), cst.endChar(idNode), newFqName))
            } else if (imp.fqName.startsWith("$oldFqName.")) {
                val suffix = imp.fqName.removePrefix(oldFqName)
                edits.add(TextRangeEdit(cst.startChar(idNode), cst.endChar(idNode), "$newFqName$suffix"))
            } else {
                val visibleSimple = imp.aliasName ?: imp.fqName.substringAfterLast('.')
                if (visibleSimple == oldSimple) {
                    hasConflictingExplicitImport = true
                }
            }
        }

        val isInOldPkg = oldTruePkg.isEmpty() || filePkg == oldTruePkg
        val canResolveUnqualified = hasExplicitImportOfOld ||
            (!hasConflictingExplicitImport && (hasWildcardImportOfOldPkg || isInOldPkg))

        val replacedRanges = mutableSetOf<Pair<Int, Int>>()

        fun addEditIfNotCovered(start: Int, end: Int, replacement: String) {
            val range = start to end
            if (replacedRanges.none { it.first <= range.first && it.second >= range.second } && replacedRanges.add(range)) {
                edits.add(TextRangeEdit(start, end, replacement))
            }
        }

        // 1. Rename matching top-level or nested class/interface/object declaration in the target package
        if (oldSimple != newSimple && (oldTruePkg.isEmpty() || filePkg == oldTruePkg)) {
            val targetDecl = findTargetClassDecl(cst, oldTypeChain)
            val nameId = targetDecl?.let { classDeclarationNameNode(it) }
            if (nameId != null) {
                addEditIfNotCovered(cst.startChar(nameId), cst.endChar(nameId), newSimple)
            }
        }

        // 2. Rewrite fully-qualified, outer-qualified (`Outer.Inner`), nested-prefix (`Order.Config`), and simple `user_type` references
        //    WITHOUT spanning `<...>` type arguments
        walkNamed(cst.root) { node ->
            if (node.type != "user_type") return@walkNamed
            val idParts = namedChildren(node).filter { it.type == "type_identifier" || it.type == "simple_identifier" }
            if (idParts.isEmpty()) return@walkNamed
            val qText = idParts.joinToString(".") { cst.slice(it).trim() }
            val nameStart = cst.startChar(idParts.first())
            val nameEnd = cst.endChar(idParts.last())

            if (qText == oldFqName) {
                addEditIfNotCovered(nameStart, nameEnd, newFqName)
            } else if (qText.startsWith("$oldFqName.")) {
                val segCount = oldFqName.count { it == '.' } + 1
                if (idParts.size >= segCount) {
                    addEditIfNotCovered(nameStart, cst.endChar(idParts[segCount - 1]), newFqName)
                }
            } else if (oldTypeChain.contains('.') && canResolveUnqualified && qText == oldTypeChain) {
                val replacementChain = if (oldTruePkg == newTruePkg || filePkg == newTruePkg) newTypeChain else newFqName
                addEditIfNotCovered(nameStart, nameEnd, replacementChain)
            } else if (oldTypeChain.contains('.') && canResolveUnqualified && qText.startsWith("$oldTypeChain.")) {
                val segCount = oldTypeChain.count { it == '.' } + 1
                if (idParts.size >= segCount) {
                    val replacementChain = if (oldTruePkg == newTruePkg || filePkg == newTruePkg) newTypeChain else newFqName
                    addEditIfNotCovered(nameStart, cst.endChar(idParts[segCount - 1]), replacementChain)
                }
            } else if (!qText.contains('.') &&
                oldSimple != newSimple &&
                aliasForOld == null &&
                canResolveUnqualified &&
                qText == oldSimple
            ) {
                addEditIfNotCovered(nameStart, nameEnd, newSimple)
            } else if (!oldTypeChain.contains('.') &&
                oldSimple != newSimple &&
                aliasForOld == null &&
                canResolveUnqualified &&
                qText.startsWith("$oldSimple.")
            ) {
                val firstPart = idParts.first()
                addEditIfNotCovered(cst.startChar(firstPart), cst.endChar(firstPart), newSimple)
            }
        }

        // 3. Rewrite fully-qualified and outer-qualified (`Outer.Inner`) `navigation_expression` references & calls
        walkNamed(cst.root) { node ->
            if (node.type != "navigation_expression") return@walkNamed
            if (isInsideImportOrPackage(node)) return@walkNamed
            val cleanText = cst.slice(node).replace(Regex("""\s*\.\s*"""), ".").trim()
            val matchesFq = cleanText == oldFqName
            val matchesChain = oldTypeChain.contains('.') && canResolveUnqualified && cleanText == oldTypeChain
            if (matchesFq || matchesChain) {
                val targetReplacement = if (matchesFq) {
                    newFqName
                } else if (oldTruePkg == newTruePkg || filePkg == newTruePkg) {
                    newTypeChain
                } else {
                    newFqName
                }
                addEditIfNotCovered(cst.startChar(node), cst.endChar(node), targetReplacement)
            }
        }

        // 4. Rewrite unqualified expression references (`Order()`, `Order.create()`, `Order::class`, `Order::foo`)
        if (oldSimple != newSimple && aliasForOld == null && canResolveUnqualified) {
            walkNamed(cst.root) { node ->
                if (isInsideImportOrPackage(node)) return@walkNamed
                when (node.type) {
                    "type_identifier" -> {
                        // `callable_reference` receiver (`Order::class`, `Order::foo`) — `user_type` was handled in Step 2
                        if (node.parent?.type == "callable_reference" && cst.slice(node).trim() == oldSimple) {
                            addEditIfNotCovered(cst.startChar(node), cst.endChar(node), newSimple)
                        }
                    }
                    "simple_identifier" -> {
                        if (cst.slice(node).trim() != oldSimple) return@walkNamed
                        if (!isExpressionReferenceIdentifier(node)) return@walkNamed
                        // Do not rename member access on a receiver (`other.Order`, `other?.Order`, `other.Order()`)
                        if (node.parent?.type == "navigation_suffix") return@walkNamed
                        addEditIfNotCovered(cst.startChar(node), cst.endChar(node), newSimple)
                    }
                }
            }
        }

        var modified = applyRangeEdits(lfSource, edits)

        // 5. If symbol moved packages and this file is in oldTruePkg (or used wildcard import of oldTruePkg) and references newSimple, insert import
        if (oldTruePkg != newTruePkg && newTruePkg.isNotEmpty() && filePkg != newTruePkg && !hasConflictingExplicitImport) {
            if ((filePkg == oldTruePkg || hasWildcardImportOfOldPkg) && referencesSymbolInAst(modified, newSimple, newFqName)) {
                modified = insertImportNormalized(modified, newFqName)
            }
        }

        modified
    }

    override fun rewireCallerToExtractedInterface(
        source: String,
        sourceClassFqName: String,
        sourceSimpleName: String,
        interfaceFqName: String,
        interfaceSimpleName: String,
        targetPackage: String
    ): String = withNormalizedLineEndings(source) { lfSource ->
        val cst = KotlinTreeSitterSupport.parse(lfSource)
        val callerPkg = readPkgFromCst(cst)
        val (sourcePkg, _) = splitPackageAndTypeChain(sourceClassFqName, callerPkg)
        var hasExplicitImportOfSource = false
        var hasWildcardImportOfSourcePkg = false
        var hasConflictingImport = false
        var sourceAlias: String? = null
        for (imp in readImports(cst)) {
            if (imp.isWildcard) {
                if (sourcePkg.isNotEmpty() && imp.fqName == sourcePkg) {
                    hasWildcardImportOfSourcePkg = true
                }
            } else if (imp.fqName == sourceClassFqName) {
                hasExplicitImportOfSource = true
                sourceAlias = imp.aliasName
            } else {
                val visible = imp.aliasName ?: imp.fqName.substringAfterLast('.')
                if (visible == sourceSimpleName) {
                    hasConflictingImport = true
                }
            }
        }
        val canResolveSourceUnqualified = sourceAlias == null && (
            hasExplicitImportOfSource ||
                (!hasConflictingImport && (hasWildcardImportOfSourcePkg || sourcePkg.isEmpty() || callerPkg == sourcePkg))
            )

        val edits = mutableListOf<TextRangeEdit>()
        val replacedRanges = mutableSetOf<Pair<Int, Int>>()

        walkNamed(cst.root) { node ->
            if (node.type != "user_type") return@walkNamed
            // Never rewrite superclass constructor calls (`class Sub : ConcreteService()`) or annotations
            if (isInsideSuperTypeCallEntry(node) || isInsideAnnotation(node)) return@walkNamed

            val qText = qualifiedUserTypeText(cst, node)
            // Never rewrite nested type qualifiers (`OrderService.Config` or `com.acme.OrderService.Config`)
            if (qText.startsWith("$sourceSimpleName.") || qText.startsWith("$sourceClassFqName.")) return@walkNamed

            val nameStart = cst.startChar(node)
            val nameEnd = userTypeNameEndChar(cst, node)
            if (qText == sourceClassFqName) {
                val range = nameStart to nameEnd
                if (replacedRanges.add(range)) {
                    edits.add(TextRangeEdit(nameStart, nameEnd, interfaceSimpleName))
                }
            } else if (!qText.contains('.') && canResolveSourceUnqualified && qText == sourceSimpleName) {
                val range = nameStart to nameEnd
                if (replacedRanges.add(range)) {
                    edits.add(TextRangeEdit(nameStart, nameEnd, interfaceSimpleName))
                }
            }
        }

        if (edits.isEmpty()) return@withNormalizedLineEndings lfSource

        var modified = applyRangeEdits(lfSource, edits)

        // Only replace `import $sourceClassFqName` if the rewired file no longer references `sourceSimpleName`
        // (e.g. via `OrderService()` instantiation, `: OrderService()` inheritance, `OrderService.Companion`, or `OrderService::class`)
        val stillReferencesSourceClass = containsTypeReferenceInternal(modified, sourceSimpleName)
        if (!stillReferencesSourceClass) {
            val reParsed = KotlinTreeSitterSupport.parse(modified)
            val impEdits = mutableListOf<TextRangeEdit>()
            for (imp in readImports(reParsed)) {
                if (!imp.isWildcard && imp.fqName == sourceClassFqName) {
                    val idNode = imp.identifierNode
                    if (callerPkg != targetPackage && targetPackage.isNotEmpty() && idNode != null) {
                        impEdits.add(TextRangeEdit(reParsed.startChar(idNode), reParsed.endChar(idNode), interfaceFqName))
                    } else if (callerPkg == targetPackage) {
                        val lineEnd = advancePastLineBreak(modified, reParsed.endChar(imp.node))
                        impEdits.add(TextRangeEdit(reParsed.startChar(imp.node), lineEnd, ""))
                    }
                }
            }
            modified = applyRangeEdits(modified, impEdits)
        }

        if (callerPkg != targetPackage && targetPackage.isNotEmpty()) {
            modified = insertImportNormalized(modified, interfaceFqName)
        }
        modified
    }

    override fun renderExtractedInterfaceSource(
        srcOriginalText: String,
        srcPkg: String,
        targetPackage: String,
        interfaceSimpleName: String,
        extractedMembers: List<MemberSignature>,
        graphNodes: List<ArcNode>
    ): String {
        val methodsOnly = extractedMembers.filter { it.kind == "METHOD" && it.visibility == "public" }
        val methodsText = methodsOnly.joinToString("\n") { m ->
            val rawSig = m.typeOrSignature.trim()
            val isSuspend = rawSig.startsWith("suspend ")
            val sigBody = rawSig.removePrefix("suspend ").trim()
            val normalizedSig = if (sigBody.startsWith("(")) sigBody else "(): $sigBody"
            val suspendMod = if (isSuspend) "suspend " else ""
            "    ${suspendMod}fun ${m.name}$normalizedSig"
        }
        val neededImports = extractNeededImportsInternal(srcOriginalText, methodsText, srcPkg, targetPackage, graphNodes)
        return buildString {
            if (targetPackage.isNotEmpty()) {
                append("package $targetPackage\n\n")
            }
            if (neededImports.isNotEmpty()) {
                neededImports.forEach { append(it).append('\n') }
                append('\n')
            }
            append("interface $interfaceSimpleName {\n")
            if (methodsText.isNotBlank()) append(methodsText).append('\n')
            append("}\n")
        }
    }

    override fun renderOrAppendTypeDeclaration(
        existingSource: String?,
        pkg: String?,
        simpleName: String,
        nodeKind: NodeKind,
        sanitizedAnnotations: List<String>
    ): String {
        val annBlock = if (sanitizedAnnotations.isNotEmpty()) {
            sanitizedAnnotations.joinToString("\n") { ann ->
                val trimmed = ann.trim()
                if (trimmed.startsWith("@")) trimmed else "@$trimmed"
            } + "\n"
        } else {
            ""
        }
        val decl = when (nodeKind) {
            NodeKind.INTERFACE -> "${annBlock}interface $simpleName\n"
            NodeKind.DATA_CLASS -> "${annBlock}data class $simpleName(val id: String)\n"
            NodeKind.ENUM -> "${annBlock}enum class $simpleName {\n    DEFAULT\n}\n"
            else -> "${annBlock}class $simpleName\n"
        }
        if (existingSource == null || existingSource.isBlank()) {
            val pkgLine = if (!pkg.isNullOrBlank()) "package $pkg\n\n" else ""
            return "$pkgLine$decl"
        }
        return withNormalizedLineEndings(existingSource) { lfExisting ->
            val cst = KotlinTreeSitterSupport.parse(lfExisting)
            val alreadyExists = namedChildren(cst.root)
                .filter { isClassOrObjectNode(it) }
                .any { classDeclarationName(cst, it) == simpleName }
            if (alreadyExists) {
                lfExisting
            } else {
                lfExisting.trimEnd() + "\n\n$decl"
            }
        }
    }

    private fun classHeaderEndChar(cst: ParsedKotlinCst, cls: TSNode): Int? {
        val anchor = firstChildOfType(cls, "primary_constructor")
            ?: firstChildOfType(cls, "type_parameters")
            ?: classDeclarationNameNode(cls)
            ?: return null
        return cst.endChar(anchor)
    }

    private fun extractDelegationSuperTypeSimpleName(cst: ParsedKotlinCst, delegSpec: TSNode): String {
        val ctorInv = firstChildOfType(delegSpec, "constructor_invocation")
        val uType = ctorInv?.let { firstChildOfType(it, "user_type") }
            ?: firstChildOfType(delegSpec, "explicit_delegation")?.let { firstChildOfType(it, "user_type") }
            ?: firstChildOfType(delegSpec, "user_type")
        if (uType != null) {
            return qualifiedUserTypeText(cst, uType).substringAfterLast('.')
        }
        return cst.slice(delegSpec).substringBefore('<').substringBefore('(').substringAfterLast('.').trim()
    }

    private fun findAnonTokenNode(node: TSNode, tokenText: String): TSNode? {
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (!child.isNamed && child.type == tokenText) return child
        }
        return null
    }

    private fun findLastAnonTokenNode(node: TSNode, tokenText: String): TSNode? {
        for (i in node.childCount - 1 downTo 0) {
            val child = node.getChild(i)
            if (!child.isNamed && child.type == tokenText) return child
        }
        return null
    }

    private fun findTargetClassDecl(cst: ParsedKotlinCst, targetChain: String): TSNode? {
        val topLevel = namedChildren(cst.root).filter { isClassOrObjectNode(it) }
        val segments = targetChain.split('.')
        var currentCandidates = topLevel
        var matched: TSNode? = null
        for (seg in segments) {
            matched = currentCandidates.firstOrNull { classDeclarationName(cst, it) == seg }
            if (matched == null) break
            currentCandidates = directBodyDeclarations(matched).filter { isClassOrObjectNode(it) }
        }
        if (matched != null) return matched

        // Fallback: search any descendant class/object matching qualifiedClassChain or simple name
        var fallback: TSNode? = null
        walkNamed(cst.root) { node ->
            if (fallback != null || !isClassOrObjectNode(node)) return@walkNamed
            if (targetChain.contains('.')) {
                if (qualifiedClassChain(cst, node) == targetChain) fallback = node
            } else if (classDeclarationName(cst, node) == targetChain) {
                fallback = node
            }
        }
        return fallback
    }

    private fun qualifiedClassChain(cst: ParsedKotlinCst, cls: TSNode): String {
        val names = mutableListOf<String>()
        var curr: TSNode? = cls
        while (curr != null && !curr.isNull) {
            if (isClassOrObjectNode(curr)) {
                classDeclarationName(cst, curr)?.let { names.add(it) }
            }
            curr = curr.parent.takeIf { !it.isNull }
        }
        return names.asReversed().joinToString(".")
    }

    private fun isInsideImportOrPackage(node: TSNode): Boolean {
        var curr: TSNode? = node
        while (curr != null && !curr.isNull) {
            val t = curr.type
            if (t == "import_header" || t == "import_list" || t == "package_header") return true
            curr = curr.parent.takeIf { !it.isNull }
        }
        return false
    }

    private fun isInsideSuperTypeCallEntry(node: TSNode): Boolean {
        var curr: TSNode? = node
        var sawCtorInvocation = false
        while (curr != null && !curr.isNull) {
            if (curr.type == "constructor_invocation") {
                sawCtorInvocation = true
            } else if (curr.type == "delegation_specifier" && sawCtorInvocation) {
                return true
            }
            curr = curr.parent.takeIf { !it.isNull }
        }
        return false
    }

    private fun isInsideAnnotation(node: TSNode): Boolean {
        var curr: TSNode? = node
        while (curr != null && !curr.isNull) {
            if (curr.type == "annotation" || curr.type == "file_annotation") return true
            curr = curr.parent.takeIf { !it.isNull }
        }
        return false
    }

    private fun isExpressionReferenceIdentifier(node: TSNode): Boolean {
        val parent = node.parent
        if (parent.isNull) return false
        return when (parent.type) {
            "user_type",
            "class_declaration",
            "object_declaration",
            "function_declaration",
            "variable_declaration",
            "parameter",
            "class_parameter",
            "enum_entry",
            "type_parameter",
            "type_alias",
            "import_header",
            "import_alias",
            "package_header",
            "identifier",
            "label",
            "use_site_target",
            "navigation_suffix" -> false
            "value_argument" -> {
                // Skip named argument label (`foo = bar`): `simple_identifier` followed by `=` (ignoring comments)
                val kids = children(parent)
                val idx = kids.indexOfFirst { it.startByte == node.startByte && it.endByte == node.endByte }
                val next = if (idx >= 0) {
                    kids.drop(idx + 1).firstOrNull { !KotlinTreeSitterSupport.isCommentNode(it) }
                } else {
                    null
                }
                !(next != null && !next.isNamed && next.type == "=")
            }
            else -> true
        }
    }

    private fun referencesSymbolInAst(
        source: String,
        simpleName: String,
        fqName: String
    ): Boolean {
        val cst = parseLf(source)
        val alreadyImported = readImports(cst).any { !it.isWildcard && it.fqName == fqName }
        if (alreadyImported) return false
        var found = false
        walkNamed(cst.root) { node ->
            if (found || isInsideImportOrPackage(node)) return@walkNamed
            when (node.type) {
                "type_identifier" -> {
                    val parent = node.parent
                    val parentType = parent?.type
                    if (parentType == "callable_reference" && cst.slice(node).trim() == simpleName) {
                        found = true
                    } else if (parentType == "user_type" && !parent.isNull) {
                        val firstPart = namedChildren(parent)
                            .firstOrNull { it.type == "type_identifier" || it.type == "simple_identifier" }
                        if (firstPart != null &&
                            firstPart.startByte == node.startByte &&
                            cst.slice(node).trim() == simpleName
                        ) {
                            found = true
                        }
                    }
                }
                "simple_identifier" -> {
                    if (isExpressionReferenceIdentifier(node) && cst.slice(node).trim() == simpleName) {
                        found = true
                    }
                }
            }
        }
        return found
    }

    private fun findTrailingCommaOffset(source: String, fromOffset: Int, untilOffset: Int): Int? {
        var i = fromOffset.coerceAtLeast(0)
        val limit = untilOffset.coerceAtMost(source.length)
        var lastComma: Int? = null
        while (i < limit) {
            if (i + 1 < limit && source[i] == '/' && source[i + 1] == '/') {
                i += 2
                while (i < limit && source[i] != '\n' && source[i] != '\r') i++
                continue
            }
            if (i + 1 < limit && source[i] == '/' && source[i + 1] == '*') {
                i += 2
                var depth = 1
                while (i + 1 < limit && depth > 0) {
                    if (source[i] == '/' && source[i + 1] == '*') {
                        depth++
                        i += 2
                    } else if (source[i] == '*' && source[i + 1] == '/') {
                        depth--
                        i += 2
                    } else {
                        i++
                    }
                }
                continue
            }
            if (source[i] == ',') {
                lastComma = i
            }
            i++
        }
        return lastComma
    }

    private fun extractLineIndent(source: String, offset: Int): String {
        val clamped = offset.coerceIn(0, source.length)
        val lineStart = source.lastIndexOf('\n', (clamped - 1).coerceAtLeast(0))
            .let { if (it < 0) 0 else it + 1 }
        var i = lineStart
        while (i < source.length && (source[i] == ' ' || source[i] == '\t')) i++
        return source.substring(lineStart, i)
    }

    private fun splitPackageAndTypeChain(fqName: String, knownFilePkg: String): Pair<String, String> {
        if (knownFilePkg.isNotEmpty() && fqName.startsWith("$knownFilePkg.")) {
            return knownFilePkg to fqName.removePrefix("$knownFilePkg.")
        }
        val parts = fqName.split('.')
        val firstUpperIdx = parts.indexOfFirst { it.isNotEmpty() && it[0].isUpperCase() }
        return if (firstUpperIdx > 0) {
            parts.subList(0, firstUpperIdx).joinToString(".") to parts.subList(firstUpperIdx, parts.size).joinToString(".")
        } else if (firstUpperIdx == 0) {
            "" to fqName
        } else {
            fqName.substringBeforeLast('.', "") to fqName.substringAfterLast('.')
        }
    }

    private fun applyRangeEdits(source: String, edits: List<TextRangeEdit>): String {
        if (edits.isEmpty()) return source
        var result = source
        val sorted = edits
            .distinctBy { Triple(it.startOffset, it.endOffset, it.replacement) }
            .sortedWith(compareByDescending<TextRangeEdit> { it.startOffset }.thenByDescending { it.endOffset })
        var lastStart = Int.MAX_VALUE
        for (edit in sorted) {
            if (edit.startOffset >= 0 && edit.endOffset <= result.length && edit.startOffset <= edit.endOffset && edit.endOffset <= lastStart) {
                result = result.substring(0, edit.startOffset) + edit.replacement + result.substring(edit.endOffset)
                lastStart = edit.startOffset
            }
        }
        return result
    }
}
