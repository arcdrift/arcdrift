package dev.arcdrift.core.ast

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.ConstructorDeclaration
import com.github.javaparser.ast.body.EnumDeclaration
import com.github.javaparser.ast.body.FieldDeclaration
import com.github.javaparser.ast.body.Parameter
import com.github.javaparser.ast.body.RecordDeclaration
import com.github.javaparser.ast.body.TypeDeclaration
import com.github.javaparser.ast.expr.AnnotationExpr
import com.github.javaparser.ast.expr.FieldAccessExpr
import com.github.javaparser.ast.expr.MethodReferenceExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.nodeTypes.NodeWithImplements
import com.github.javaparser.ast.stmt.ExplicitConstructorInvocationStmt
import com.github.javaparser.ast.type.ClassOrInterfaceType
import dev.arcdrift.core.model.ArcNode
import dev.arcdrift.core.model.MemberSignature
import dev.arcdrift.core.model.NodeKind

/**
 * `com.github.javaparser:javaparser-core` AST-backed Java source rewriter implementing [JvmAstSourceRewriter].
 *
 * All mutations inspect the parsed Java 21 [CompilationUnit] AST and apply exact token-offset edits
 * in reverse-offset order — preserving original whitespace, comments, annotations, and formatting
 * while never corrupting string literals, comments, prefix-matching imports (`Order` vs `OrderService`),
 * or unrelated member accesses.
 */
object JavaAstSourceMutator : JvmAstSourceRewriter {

    override val language: JvmSourceLanguage = JvmSourceLanguage.JAVA

    private val JAVA_LANG_BUILTINS = setOf(
        "Object", "String", "CharSequence", "Boolean", "Byte", "Short", "Integer", "Long",
        "Float", "Double", "Character", "Number", "Void", "Class", "Enum", "Record",
        "Throwable", "Exception", "RuntimeException", "Iterable", "Comparable", "Override", "Deprecated", "SuppressWarnings"
    )

    private data class OffsetEdit(val startOffset: Int, val endOffset: Int, val replacement: String)

    private val config = ParserConfiguration().apply {
        languageLevel = ParserConfiguration.LanguageLevel.JAVA_21
    }

    private fun parseCu(source: String): CompilationUnit? =
        JavaParser(config).parse(source).result.orElse(null)

    private fun detectEol(source: String): String = when {
        source.contains("\r\n") -> "\r\n"
        source.contains('\r') -> "\r"
        else -> "\n"
    }

    private fun advancePastLineBreak(source: String, offset: Int): Int {
        var pos = offset.coerceIn(0, source.length)
        while (pos < source.length && source[pos] != '\n' && source[pos] != '\r') {
            pos++
        }
        if (pos < source.length && source[pos] == '\r') pos++
        if (pos < source.length && source[pos] == '\n') pos++
        return pos
    }

    override fun hasSyntaxErrors(source: String, fileName: String): Boolean {
        val parseResult = JavaParser(config).parse(source)
        return !parseResult.isSuccessful || parseResult.problems.isNotEmpty()
    }

    override fun readPackageName(source: String): String =
        parseCu(source)?.packageDeclaration?.map { it.nameAsString }?.orElse("") ?: ""

    override fun countTopLevelDeclarations(source: String): Int =
        parseCu(source)?.types?.size ?: 0

    override fun rewritePackageDirective(source: String, newPackage: String): String {
        val eol = detectEol(source)
        val cu = parseCu(source) ?: return "package $newPackage;$eol$eol$source"
        val pkgDecl = cu.packageDeclaration.orElse(null)
        if (pkgDecl != null) {
            val range = nodeOffsetRange(source, pkgDecl)
            if (range != null) {
                return applyOffsetEdits(source, listOf(OffsetEdit(range.first, range.second, "package $newPackage;")))
            }
        }
        return "package $newPackage;$eol$eol$source"
    }

    override fun insertImport(source: String, fqImport: String): String {
        val eol = detectEol(source)
        val cu = parseCu(source) ?: return "import $fqImport;$eol$source"
        val targetPkg = fqImport.substringBeforeLast('.', "")
        val alreadyImported = cu.imports.any { imp ->
            if (imp.isStatic) return@any false
            if (imp.isAsterisk) {
                targetPkg.isNotEmpty() && imp.nameAsString == targetPkg
            } else {
                imp.nameAsString == fqImport
            }
        }
        if (alreadyImported) return source

        val importStmt = "import $fqImport;"
        val lastImp = cu.imports.lastOrNull()
        if (lastImp != null) {
            val range = nodeOffsetRange(source, lastImp)
            if (range != null) {
                val afterImpLine = advancePastLineBreak(source, range.second)
                return if (afterImpLine < source.length || source.endsWith("\n") || source.endsWith("\r")) {
                    source.substring(0, afterImpLine) + "$importStmt$eol" + source.substring(afterImpLine)
                } else {
                    source + "$eol$importStmt"
                }
            }
        }
        val pkgDecl = cu.packageDeclaration.orElse(null)
        if (pkgDecl != null) {
            val range = nodeOffsetRange(source, pkgDecl)
            if (range != null) {
                val afterPkgLine = advancePastLineBreak(source, range.second)
                return if (afterPkgLine < source.length || source.endsWith("\n") || source.endsWith("\r")) {
                    source.substring(0, afterPkgLine) + "$importStmt$eol" + source.substring(afterPkgLine)
                } else {
                    source + "$eol$importStmt$eol"
                }
            }
        }
        return "$importStmt$eol$source"
    }

    override fun hasExplicitImportForSimpleName(source: String, simpleName: String): Boolean {
        val cu = parseCu(source) ?: return false
        return cu.imports.any { imp ->
            !imp.isAsterisk && !imp.isStatic && imp.nameAsString.substringAfterLast('.') == simpleName
        }
    }

    override fun collectReferencedImportLines(source: String, methodsStub: String): List<String> {
        val srcCu = parseCu(source) ?: return emptyList()
        val stubCu = parseCu("public interface __Stub {\n$methodsStub\n}")
        val referencedSimples = stubCu?.findAll(ClassOrInterfaceType::class.java)
            ?.map { it.nameAsString }
            ?.toSet()
            .orEmpty()

        val explicitlySatisfied = mutableSetOf<String>()
        val kept = mutableListOf<String>()
        for (imp in srcCu.imports) {
            if (imp.isAsterisk) continue
            val importedPath = imp.nameAsString
            val simple = importedPath.substringAfterLast('.')
            if (simple in referencedSimples) {
                explicitlySatisfied.add(simple)
                val staticPart = if (imp.isStatic) "static " else ""
                kept.add("import $staticPart$importedPath;")
            }
        }
        val unresolvedSimples = referencedSimples - explicitlySatisfied - JAVA_LANG_BUILTINS
        if (unresolvedSimples.isNotEmpty()) {
            for (imp in srcCu.imports) {
                if (imp.isAsterisk && !imp.isStatic) {
                    kept.add("import ${imp.nameAsString}.*;")
                }
            }
        }
        return kept.distinct()
    }

    override fun containsTypeReference(methodsStub: String, simpleName: String): Boolean {
        val wrapped = if (methodsStub.contains("class ") || methodsStub.contains("interface ") ||
            methodsStub.contains("record ") || methodsStub.contains("enum ") || methodsStub.contains("package ")
        ) {
            methodsStub
        } else {
            "public interface __Stub {\n$methodsStub\n}"
        }
        val stubCu = parseCu(wrapped) ?: return false
        return stubCu.findAll(ClassOrInterfaceType::class.java).any { it.nameAsString == simpleName } ||
            stubCu.findAll(NameExpr::class.java).any { it.nameAsString == simpleName } ||
            stubCu.findAll(AnnotationExpr::class.java).any { it.nameAsString.substringAfterLast('.') == simpleName }
    }

    override fun extractNeededImports(
        srcText: String,
        methodsText: String,
        srcPkg: String,
        targetPkg: String,
        graphNodes: List<ArcNode>
    ): List<String> {
        val result = collectReferencedImportLines(srcText, methodsText).toMutableList()
        if (srcPkg.isNotEmpty() && srcPkg != targetPkg) {
            for (node in graphNodes) {
                if (node.parentId == srcPkg && containsTypeReference(methodsText, node.label)) {
                    val stmt = "import ${node.id};"
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
    ): String {
        val cu = parseCu(source) ?: return source
        val decl = findTargetTypeDecl(cu, className) ?: return source

        // In Java, an interface extends another interface (`interface A extends B`) rather than `implements`
        if (decl is ClassOrInterfaceDeclaration && decl.isInterface) {
            return addExtendsToTypeDecl(source, className, interfaceSimpleName)
        }

        if (decl is NodeWithImplements<*>) {
            if (decl.implementedTypes.any { it.nameAsString == interfaceSimpleName }) {
                return source
            }
            if (decl.implementedTypes.isNotEmpty()) {
                val lastImpl = decl.implementedTypes.last()
                val lastRange = nodeOffsetRange(source, lastImpl) ?: return source
                return applyOffsetEdits(
                    source,
                    listOf(OffsetEdit(lastRange.second, lastRange.second, ", $interfaceSimpleName"))
                )
            }
        }

        val insertOffset = findDeclarationHeaderEndOffset(source, decl, preferAfterExtends = true) ?: return source
        return applyOffsetEdits(
            source,
            listOf(OffsetEdit(insertOffset, insertOffset, " implements $interfaceSimpleName"))
        )
    }

    override fun addExtendsToTypeDecl(
        source: String,
        className: String,
        superSimpleName: String
    ): String {
        val cu = parseCu(source) ?: return source
        val cls = findTargetTypeDecl(cu, className) as? ClassOrInterfaceDeclaration ?: return source

        if (cls.extendedTypes.any { it.nameAsString == superSimpleName }) return source
        if (cls.extendedTypes.isNotEmpty()) {
            // Java classes only allow single inheritance; Java interfaces allow multiple `extends A, B`
            if (!cls.isInterface) return source
            val lastExt = cls.extendedTypes.last()
            val lastRange = nodeOffsetRange(source, lastExt) ?: return source
            return applyOffsetEdits(
                source,
                listOf(OffsetEdit(lastRange.second, lastRange.second, ", $superSimpleName"))
            )
        }

        val insertOffset = findDeclarationHeaderEndOffset(source, cls, preferAfterExtends = false) ?: return source
        return applyOffsetEdits(
            source,
            listOf(OffsetEdit(insertOffset, insertOffset, " extends $superSimpleName"))
        )
    }

    override fun addConstructorDependencyToTypeDecl(
        source: String,
        className: String,
        targetSimpleName: String
    ): String {
        val eol = detectEol(source)
        val cu = parseCu(source) ?: return source
        val decl = findTargetTypeDecl(cu, className) ?: return source
        val fieldName = targetSimpleName.replaceFirstChar { it.lowercaseChar() }

        if (decl is RecordDeclaration) {
            val alreadyInRecord = decl.parameters.any {
                it.nameAsString == fieldName || it.type.asString().substringBefore('<').trim() == targetSimpleName
            }
            if (alreadyInRecord) return source
            val nameRange = nodeOffsetRange(source, decl.name) ?: return source
            val closeParenIdx = findMatchingRecordCloseParen(source, nameRange.second) ?: return source
            val paramInjection = if (decl.parameters.isEmpty()) {
                "$targetSimpleName $fieldName"
            } else {
                ", $targetSimpleName $fieldName"
            }
            return applyOffsetEdits(source, listOf(OffsetEdit(closeParenIdx, closeParenIdx, paramInjection)))
        }

        if (decl !is ClassOrInterfaceDeclaration || decl.isInterface) return source
        val alreadyHasField = decl.fields.any { f ->
            f.elementType.asString().substringBefore('<').trim() == targetSimpleName ||
                f.variables.any { it.nameAsString == fieldName }
        }
        if (alreadyHasField) return source

        val declRange = nodeOffsetRange(source, decl) ?: return source
        val headerEnd = findDeclarationHeaderEndOffset(source, decl, preferAfterExtends = true) ?: return source
        val openBraceIdx = findCodeCharSkippingComments(source, headerEnd, declRange.second, '{') ?: return source

        val existingCtor = decl.constructors.firstOrNull()
        if (existingCtor != null) {
            val edits = mutableListOf<OffsetEdit>()
            edits.add(OffsetEdit(openBraceIdx + 1, openBraceIdx + 1, "${eol}    private final $targetSimpleName $fieldName;"))
            val ctorNameRange = nodeOffsetRange(source, existingCtor.name)
            val ctorBodyRange = nodeOffsetRange(source, existingCtor.body)
            if (ctorNameRange != null && ctorBodyRange != null) {
                val closeParen = findMatchingCloseParen(source, ctorNameRange.second, ctorBodyRange.first)
                if (closeParen != null) {
                    val paramInj = if (existingCtor.parameters.isEmpty()) {
                        "$targetSimpleName $fieldName"
                    } else {
                        ", $targetSimpleName $fieldName"
                    }
                    edits.add(OffsetEdit(closeParen, closeParen, paramInj))
                }
                val firstStmt = existingCtor.body.statements.firstOrNull()
                if (firstStmt is ExplicitConstructorInvocationStmt) {
                    val firstStmtRange = nodeOffsetRange(source, firstStmt)
                    if (firstStmtRange != null) {
                        edits.add(OffsetEdit(firstStmtRange.second, firstStmtRange.second, "${eol}        this.$fieldName = $fieldName;"))
                    }
                } else {
                    val bodyOpenBrace = findCodeCharSkippingComments(source, ctorBodyRange.first, ctorBodyRange.second, '{')
                    if (bodyOpenBrace != null) {
                        edits.add(OffsetEdit(bodyOpenBrace + 1, bodyOpenBrace + 1, "${eol}        this.$fieldName = $fieldName;"))
                    }
                }
            }
            return applyOffsetEdits(source, edits)
        }

        val simpleCls = decl.nameAsString
        val injectionBlock = buildString {
            append(eol)
            append("    private final $targetSimpleName $fieldName;$eol$eol")
            append("    public $simpleCls($targetSimpleName $fieldName) {$eol")
            append("        this.$fieldName = $fieldName;$eol")
            append("    }")
        }
        return applyOffsetEdits(source, listOf(OffsetEdit(openBraceIdx + 1, openBraceIdx + 1, injectionBlock)))
    }

    override fun rewriteSymbolReferences(
        source: String,
        oldFqName: String,
        newFqName: String,
        oldSimple: String,
        newSimple: String
    ): String {
        val cu = parseCu(source) ?: return source
        val filePkg = cu.packageDeclaration.map { it.nameAsString }.orElse("")
        val (oldTruePkg, oldTypeChain) = splitPackageAndTypeChain(oldFqName, filePkg)
        val (newTruePkg, newTypeChain) = splitPackageAndTypeChain(newFqName, oldTruePkg.takeIf { newFqName.startsWith("$it.") } ?: "")

        val edits = mutableListOf<OffsetEdit>()
        val replacedRanges = mutableSetOf<Pair<Int, Int>>()
        var hasExplicitImportOfOld = false
        var hasWildcardImportOfOldPkg = false

        for (imp in cu.imports) {
            val impName = imp.nameAsString
            if (imp.isAsterisk) {
                if (!imp.isStatic && oldTruePkg.isNotEmpty() && impName == oldTruePkg) {
                    hasWildcardImportOfOldPkg = true
                } else if (impName == oldFqName) {
                    val range = nodeOffsetRange(source, imp.name)
                    if (range != null && replacedRanges.add(range)) {
                        edits.add(OffsetEdit(range.first, range.second, newFqName))
                    }
                } else if (impName.startsWith("$oldFqName.")) {
                    val suffix = impName.removePrefix(oldFqName)
                    val range = nodeOffsetRange(source, imp.name)
                    if (range != null && replacedRanges.add(range)) {
                        edits.add(OffsetEdit(range.first, range.second, "$newFqName$suffix"))
                    }
                }
            } else if (!imp.isStatic && impName == oldFqName) {
                hasExplicitImportOfOld = true
                val range = nodeOffsetRange(source, imp.name)
                if (range != null && replacedRanges.add(range)) {
                    edits.add(OffsetEdit(range.first, range.second, newFqName))
                }
            } else if (impName.startsWith("$oldFqName.")) {
                val suffix = impName.removePrefix(oldFqName)
                val range = nodeOffsetRange(source, imp.name)
                if (range != null && replacedRanges.add(range)) {
                    edits.add(OffsetEdit(range.first, range.second, "$newFqName$suffix"))
                }
            }
        }

        val isInOldOrNewPkg = oldTruePkg.isEmpty() || filePkg == oldTruePkg || (newTruePkg.isNotEmpty() && filePkg == newTruePkg)
        val canResolveUnqualified = hasExplicitImportOfOld || hasWildcardImportOfOldPkg || isInOldOrNewPkg

        // 1. Rename top-level or nested type declaration and its constructors if in the target package
        if (oldSimple != newSimple && (oldTruePkg.isEmpty() || filePkg == oldTruePkg)) {
            val targetDecl = findTargetTypeDecl(cu, oldTypeChain)
            if (targetDecl != null) {
                nodeOffsetRange(source, targetDecl.name)?.let { r ->
                    if (replacedRanges.add(r)) {
                        edits.add(OffsetEdit(r.first, r.second, newSimple))
                    }
                }
                for (ctor in targetDecl.constructors) {
                    if (ctor.nameAsString == oldSimple) {
                        nodeOffsetRange(source, ctor.name)?.let { r ->
                            if (replacedRanges.add(r)) {
                                edits.add(OffsetEdit(r.first, r.second, newSimple))
                            }
                        }
                    }
                }
            }
        }

        // 2. Rewrite ClassOrInterfaceType AST nodes WITHOUT spanning `<...>` type arguments
        for (cit in cu.findAll(ClassOrInterfaceType::class.java)) {
            val qName = qualifiedTypeName(cit)
            val citRange = nodeOffsetRange(source, cit) ?: continue
            val nameRange = nodeOffsetRange(source, cit.name) ?: continue
            val prefixAndNameRange = citRange.first to nameRange.second
            if (qName == oldFqName) {
                if (replacedRanges.add(prefixAndNameRange)) {
                    edits.add(OffsetEdit(prefixAndNameRange.first, prefixAndNameRange.second, newFqName))
                }
            } else if (oldTypeChain.contains('.') && canResolveUnqualified && qName == oldTypeChain) {
                val repl = if (oldTruePkg == newTruePkg || filePkg == newTruePkg) newTypeChain else newFqName
                if (replacedRanges.add(prefixAndNameRange)) {
                    edits.add(OffsetEdit(prefixAndNameRange.first, prefixAndNameRange.second, repl))
                }
            } else if (!cit.scope.isPresent && oldSimple != newSimple && canResolveUnqualified && cit.nameAsString == oldSimple) {
                if (replacedRanges.add(nameRange)) {
                    edits.add(OffsetEdit(nameRange.first, nameRange.second, newSimple))
                }
            }
        }

        // 3. Rewrite AnnotationExpr (`@OldAnn`, `@com.acme.OldAnn`)
        for (ann in cu.findAll(AnnotationExpr::class.java)) {
            val annName = ann.nameAsString
            val nameRange = nodeOffsetRange(source, ann.name) ?: continue
            if (annName == oldFqName) {
                if (replacedRanges.add(nameRange)) {
                    edits.add(OffsetEdit(nameRange.first, nameRange.second, newFqName))
                }
            } else if (oldTypeChain.contains('.') && canResolveUnqualified && annName == oldTypeChain) {
                val repl = if (oldTruePkg == newTruePkg || filePkg == newTruePkg) newTypeChain else newFqName
                if (replacedRanges.add(nameRange)) {
                    edits.add(OffsetEdit(nameRange.first, nameRange.second, repl))
                }
            } else if (!annName.contains('.') && oldSimple != newSimple && canResolveUnqualified && annName == oldSimple) {
                if (replacedRanges.add(nameRange)) {
                    edits.add(OffsetEdit(nameRange.first, nameRange.second, newSimple))
                }
            }
        }

        // 4. Rewrite FieldAccessExpr and static NameExpr / MethodReferenceExpr
        for (fa in cu.findAll(FieldAccessExpr::class.java)) {
            val cleanText = fa.toString().replace(Regex("""\s*\.\s*"""), ".").trim()
            val matchesFq = cleanText == oldFqName
            val matchesChain = oldTypeChain.contains('.') && canResolveUnqualified && cleanText == oldTypeChain
            if (matchesFq || matchesChain) {
                val range = nodeOffsetRange(source, fa) ?: continue
                val repl = if (matchesFq) newFqName else if (oldTruePkg == newTruePkg || filePkg == newTruePkg) newTypeChain else newFqName
                if (replacedRanges.none { it.first <= range.first && it.second >= range.second } && replacedRanges.add(range)) {
                    edits.add(OffsetEdit(range.first, range.second, repl))
                }
            }
        }

        if (oldSimple != newSimple && canResolveUnqualified) {
            for (ne in cu.findAll(NameExpr::class.java)) {
                if (ne.nameAsString == oldSimple) {
                    val range = nodeOffsetRange(source, ne.name) ?: continue
                    if (replacedRanges.none { it.first <= range.first && it.second >= range.second } && replacedRanges.add(range)) {
                        edits.add(OffsetEdit(range.first, range.second, newSimple))
                    }
                }
            }
            for (mr in cu.findAll(MethodReferenceExpr::class.java)) {
                val scopeStr = mr.scope.toString().trim()
                if (scopeStr == oldSimple) {
                    val range = nodeOffsetRange(source, mr.scope) ?: continue
                    if (replacedRanges.none { it.first <= range.first && it.second >= range.second } && replacedRanges.add(range)) {
                        edits.add(OffsetEdit(range.first, range.second, newSimple))
                    }
                }
            }
        }

        var modified = applyOffsetEdits(source, edits)

        // 5. If moved packages and file is in oldTruePkg (or wildcard-imported oldTruePkg) and still references newSimple, insert import
        if (oldTruePkg != newTruePkg && newTruePkg.isNotEmpty() && filePkg != newTruePkg) {
            if ((filePkg == oldTruePkg || hasWildcardImportOfOldPkg) && referencesSymbolInJavaAst(modified, newSimple, newFqName)) {
                modified = insertImport(modified, newFqName)
            }
        }

        return modified
    }

    override fun rewireCallerToExtractedInterface(
        source: String,
        sourceClassFqName: String,
        sourceSimpleName: String,
        interfaceFqName: String,
        interfaceSimpleName: String,
        targetPackage: String
    ): String {
        val cu = parseCu(source) ?: return source
        val callerPkg = cu.packageDeclaration.map { it.nameAsString }.orElse("")
        val edits = mutableListOf<OffsetEdit>()
        val replacedRanges = mutableSetOf<Pair<Int, Int>>()

        // Rewire field declaration types and method/constructor parameter types (and their generic type arguments),
        // never touching `extends ConcreteClass` or `new ConcreteClass()`
        val targetTypeContainers = cu.findAll(FieldDeclaration::class.java).map { it.elementType } +
            cu.findAll(Parameter::class.java).map { it.type }
        val candidateClassTypes = targetTypeContainers.flatMap { it.findAll(ClassOrInterfaceType::class.java) }

        for (cit in candidateClassTypes) {
            if (cit.parentNode.orElse(null) is ClassOrInterfaceType &&
                (cit.parentNode.get() as ClassOrInterfaceType).scope.orElse(null) === cit
            ) {
                continue
            }
            val qName = qualifiedTypeName(cit)
            val citRange = nodeOffsetRange(source, cit) ?: continue
            val nameRange = nodeOffsetRange(source, cit.name) ?: continue
            val prefixAndNameRange = citRange.first to nameRange.second
            if (qName == sourceClassFqName) {
                if (replacedRanges.add(prefixAndNameRange)) {
                    edits.add(OffsetEdit(prefixAndNameRange.first, prefixAndNameRange.second, interfaceSimpleName))
                }
            } else if (!cit.scope.isPresent && cit.nameAsString == sourceSimpleName) {
                if (replacedRanges.add(nameRange)) {
                    edits.add(OffsetEdit(nameRange.first, nameRange.second, interfaceSimpleName))
                }
            }
        }

        if (edits.isEmpty()) return source

        var modified = applyOffsetEdits(source, edits)

        // Only replace/remove `import $sourceClassFqName;` if no remaining reference to `sourceSimpleName` exists
        // (e.g. `extends OrderService`, `new OrderService()`, `OrderService.staticHelper()`, `OrderService.SubConfig`)
        val stillReferencesSource = containsTypeReference(modified, sourceSimpleName)
        if (!stillReferencesSource) {
            val reParsed = parseCu(modified)
            if (reParsed != null) {
                val impEdits = mutableListOf<OffsetEdit>()
                for (imp in reParsed.imports) {
                    if (!imp.isStatic && !imp.isAsterisk && imp.nameAsString == sourceClassFqName) {
                        if (callerPkg != targetPackage && targetPackage.isNotEmpty()) {
                            nodeOffsetRange(modified, imp.name)?.let { r ->
                                impEdits.add(OffsetEdit(r.first, r.second, interfaceFqName))
                            }
                        } else if (callerPkg == targetPackage) {
                            nodeOffsetRange(modified, imp)?.let { r ->
                                val lineEnd = advancePastLineBreak(modified, r.second)
                                impEdits.add(OffsetEdit(r.first, lineEnd, ""))
                            }
                        }
                    }
                }
                modified = applyOffsetEdits(modified, impEdits)
            }
        }

        if (callerPkg != targetPackage && targetPackage.isNotEmpty()) {
            modified = insertImport(modified, interfaceFqName)
        }
        return modified
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
            val (rawParams, rawRet) = parseSignatureParamsAndReturn(m.typeOrSignature.removePrefix("suspend ").trim())
            val ret = if (rawRet == "Unit" || rawRet.isEmpty()) "void" else rawRet
            val javaParams = if (rawParams.isEmpty()) {
                ""
            } else {
                splitTopLevelParams(rawParams).joinToString(", ") { p ->
                    val trimmed = p.trim()
                    val colonIdx = findTopLevelColon(trimmed)
                    if (colonIdx >= 0) {
                        val rawName = trimmed.substring(0, colonIdx).trim()
                        val isVararg = rawName.startsWith("vararg ")
                        val pName = rawName.removePrefix("vararg ").trim()
                        val pType = trimmed.substring(colonIdx + 1).trim()
                        if (isVararg && !pType.endsWith("...")) "$pType... $pName" else "$pType $pName"
                    } else {
                        trimmed
                    }
                }
            }
            "    $ret ${m.name}($javaParams);"
        }
        val neededImports = extractNeededImports(srcOriginalText, methodsText, srcPkg, targetPackage, graphNodes)
        return buildString {
            if (targetPackage.isNotEmpty()) {
                appendLine("package $targetPackage;")
                appendLine()
            }
            if (neededImports.isNotEmpty()) {
                neededImports.forEach { appendLine(it) }
                appendLine()
            }
            appendLine("public interface $interfaceSimpleName {")
            if (methodsText.isNotBlank()) appendLine(methodsText)
            appendLine("}")
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
        val isExistingFile = existingSource != null && existingSource.isNotBlank()
        val publicMod = if (isExistingFile) "" else "public "
        val decl = when (nodeKind) {
            NodeKind.INTERFACE -> "${annBlock}${publicMod}interface $simpleName {\n}\n"
            NodeKind.DATA_CLASS -> "${annBlock}${publicMod}record $simpleName(String id) {\n}\n"
            NodeKind.ENUM -> "${annBlock}${publicMod}enum $simpleName {\n    DEFAULT\n}\n"
            else -> "${annBlock}${publicMod}class $simpleName {\n}\n"
        }
        if (!isExistingFile) {
            val pkgLine = if (!pkg.isNullOrBlank()) "package $pkg;\n\n" else ""
            return "$pkgLine$decl"
        }
        val cu = parseCu(existingSource!!)
        val alreadyExists = cu?.types?.any { it.nameAsString == simpleName } == true
        return if (alreadyExists) {
            existingSource
        } else {
            existingSource.trimEnd() + "\n\n$decl"
        }
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

    private fun qualifiedDeclChain(decl: TypeDeclaration<*>): String {
        val names = mutableListOf(decl.nameAsString)
        var p = decl.parentNode.orElse(null)
        while (p != null) {
            if (p is TypeDeclaration<*>) {
                names.add(0, p.nameAsString)
            }
            p = p.parentNode.orElse(null)
        }
        return names.joinToString(".")
    }

    private fun findTargetTypeDecl(cu: CompilationUnit, classNameOrChain: String): TypeDeclaration<*>? {
        if (!classNameOrChain.contains('.')) {
            return cu.types.firstOrNull { it.nameAsString == classNameOrChain }
                ?: cu.findAll(TypeDeclaration::class.java).firstOrNull { it.nameAsString == classNameOrChain }
        }
        return cu.findAll(TypeDeclaration::class.java).firstOrNull { qualifiedDeclChain(it) == classNameOrChain }
    }

    private fun findDeclarationHeaderEndOffset(
        source: String,
        decl: TypeDeclaration<*>,
        preferAfterExtends: Boolean
    ): Int? {
        val declRange = nodeOffsetRange(source, decl) ?: return null
        val nameRange = nodeOffsetRange(source, decl.name) ?: return null
        if (decl is RecordDeclaration) {
            val closeParen = findMatchingRecordCloseParen(source, nameRange.second) ?: return null
            return closeParen + 1
        }
        if (decl is EnumDeclaration) {
            return nameRange.second
        }
        if (decl is ClassOrInterfaceDeclaration) {
            if (preferAfterExtends && decl.extendedTypes.isNotEmpty()) {
                return nodeOffsetRange(source, decl.extendedTypes.last())?.second
            }
            if (decl.typeParameters.isNotEmpty()) {
                val lastTpRange = nodeOffsetRange(source, decl.typeParameters.last()) ?: return null
                val gtIdx = findCodeCharSkippingComments(source, lastTpRange.second, declRange.second, '>') ?: return null
                return gtIdx + 1
            }
            return nameRange.second
        }
        return nameRange.second
    }

    private fun findMatchingRecordCloseParen(source: String, startOffset: Int): Int? =
        findMatchingCloseParen(source, startOffset, source.length)

    private fun findMatchingCloseParen(source: String, startOffset: Int, untilOffset: Int): Int? {
        val limit = untilOffset.coerceAtMost(source.length)
        val openParen = findCodeCharSkippingComments(source, startOffset, limit, '(') ?: return null
        var i = openParen
        var depth = 0
        while (i < limit) {
            val next = skipCommentOrStringAt(source, i)
            if (next > i) {
                i = next
                continue
            }
            when (source[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        return null
    }

    private fun findCodeCharSkippingComments(source: String, from: Int, until: Int, target: Char): Int? {
        var i = from.coerceAtLeast(0)
        val limit = until.coerceAtMost(source.length)
        while (i < limit) {
            val next = skipCommentOrStringAt(source, i)
            if (next > i) {
                i = next
                continue
            }
            if (source[i] == target) return i
            i++
        }
        return null
    }

    private fun skipCommentOrStringAt(source: String, idx: Int): Int {
        if (idx + 1 < source.length && source[idx] == '/' && source[idx + 1] == '/') {
            var j = idx + 2
            while (j < source.length && source[j] != '\n' && source[j] != '\r') j++
            return j
        }
        if (idx + 1 < source.length && source[idx] == '/' && source[idx + 1] == '*') {
            var j = idx + 2
            while (j + 1 < source.length && !(source[j] == '*' && source[j + 1] == '/')) j++
            return (j + 2).coerceAtMost(source.length)
        }
        if (source[idx] == '"' || source[idx] == '\'') {
            val quote = source[idx]
            var j = idx + 1
            while (j < source.length) {
                if (source[j] == '\\') {
                    j += 2
                    continue
                }
                if (source[j] == quote) return j + 1
                j++
            }
            return source.length
        }
        return idx
    }

    private fun parseSignatureParamsAndReturn(rawSignature: String): Pair<String, String> {
        val sig = rawSignature.trim()
        if (!sig.startsWith("(")) {
            return "" to sig.removePrefix(":").trim().ifEmpty { "void" }
        }
        var parenDepth = 0
        var angleDepth = 0
        var closeParenIdx = -1
        for (i in sig.indices) {
            when (sig[i]) {
                '(' -> if (angleDepth == 0) parenDepth++
                ')' -> if (angleDepth == 0) {
                    parenDepth--
                    if (parenDepth == 0) {
                        closeParenIdx = i
                        break
                    }
                }
                '<' -> angleDepth++
                '>' -> angleDepth = maxOf(0, angleDepth - 1)
            }
        }
        if (closeParenIdx < 0) {
            return sig.removePrefix("(").removeSuffix(")").trim() to "void"
        }
        val paramsInside = sig.substring(1, closeParenIdx).trim()
        val afterParens = sig.substring(closeParenIdx + 1).trim()
        val returnType = if (afterParens.startsWith(":")) {
            afterParens.removePrefix(":").trim().ifEmpty { "void" }
        } else {
            "void"
        }
        return paramsInside to returnType
    }

    private fun findTopLevelColon(paramDecl: String): Int {
        var angleDepth = 0
        var parenDepth = 0
        for (i in paramDecl.indices) {
            when (paramDecl[i]) {
                '<' -> angleDepth++
                '>' -> angleDepth = maxOf(0, angleDepth - 1)
                '(' -> parenDepth++
                ')' -> parenDepth = maxOf(0, parenDepth - 1)
                ':' -> if (angleDepth == 0 && parenDepth == 0) return i
            }
        }
        return -1
    }

    private fun splitTopLevelParams(rawParams: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var angleDepth = 0
        for (ch in rawParams) {
            when (ch) {
                '<' -> {
                    angleDepth++
                    current.append(ch)
                }
                '>' -> {
                    angleDepth = maxOf(0, angleDepth - 1)
                    current.append(ch)
                }
                ',' -> if (angleDepth == 0) {
                    result.add(current.toString())
                    current.clear()
                } else {
                    current.append(ch)
                }
                else -> current.append(ch)
            }
        }
        if (current.isNotEmpty()) {
            result.add(current.toString())
        }
        return result
    }

    private fun qualifiedTypeName(cit: ClassOrInterfaceType): String {
        val scope = cit.scope.orElse(null)
        return if (scope != null) "${qualifiedTypeName(scope)}.${cit.nameAsString}" else cit.nameAsString
    }

    private fun referencesSymbolInJavaAst(source: String, simpleName: String, fqName: String): Boolean {
        val cu = parseCu(source) ?: return false
        if (cu.imports.any { !it.isAsterisk && !it.isStatic && it.nameAsString == fqName }) return false
        return cu.findAll(ClassOrInterfaceType::class.java).any { !it.scope.isPresent && it.nameAsString == simpleName } ||
            cu.findAll(NameExpr::class.java).any { it.nameAsString == simpleName } ||
            cu.findAll(AnnotationExpr::class.java).any { !it.nameAsString.contains('.') && it.nameAsString == simpleName }
    }

    private fun nodeOffsetRange(source: String, node: Node): Pair<Int, Int>? {
        val range = node.range.orElse(null) ?: return null
        val lineStarts = computeLineStartOffsets(source)
        val beginLineIdx = range.begin.line - 1
        val endLineIdx = range.end.line - 1
        if (beginLineIdx !in lineStarts.indices || endLineIdx !in lineStarts.indices) return null
        val startOffset = (lineStarts[beginLineIdx] + (range.begin.column - 1)).coerceIn(0, source.length)
        val endOffset = (lineStarts[endLineIdx] + range.end.column).coerceIn(startOffset, source.length)
        return startOffset to endOffset
    }

    private fun computeLineStartOffsets(source: String): IntArray {
        val starts = mutableListOf(0)
        var i = 0
        while (i < source.length) {
            when (source[i]) {
                '\r' -> {
                    if (i + 1 < source.length && source[i + 1] == '\n') {
                        starts.add(i + 2)
                        i += 2
                        continue
                    } else {
                        starts.add(i + 1)
                    }
                }
                '\n' -> starts.add(i + 1)
            }
            i++
        }
        return starts.toIntArray()
    }

    private fun applyOffsetEdits(source: String, edits: List<OffsetEdit>): String {
        if (edits.isEmpty()) return source
        var result = source
        val sorted = edits
            .distinctBy { Triple(it.startOffset, it.endOffset, it.replacement) }
            .sortedWith(compareByDescending<OffsetEdit> { it.startOffset }.thenByDescending { it.endOffset })
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
