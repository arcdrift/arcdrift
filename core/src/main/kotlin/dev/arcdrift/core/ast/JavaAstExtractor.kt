package dev.arcdrift.core.ast

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.EnumDeclaration
import com.github.javaparser.ast.body.RecordDeclaration
import com.github.javaparser.ast.body.TypeDeclaration
import com.github.javaparser.ast.expr.AnnotationExpr
import com.github.javaparser.ast.expr.ArrayInitializerExpr
import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.ast.expr.FieldAccessExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.MethodReferenceExpr
import com.github.javaparser.ast.expr.NormalAnnotationExpr
import com.github.javaparser.ast.expr.ObjectCreationExpr
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr
import com.github.javaparser.ast.expr.StringLiteralExpr
import com.github.javaparser.ast.nodeTypes.NodeWithImplements
import com.github.javaparser.ast.type.ArrayType
import com.github.javaparser.ast.type.ClassOrInterfaceType
import com.github.javaparser.ast.type.Type
import dev.arcdrift.core.model.ContractEndpoint
import dev.arcdrift.core.model.MemberSignature

/**
 * Parses Java 21 source files into [JvmParsedFileAst] using `com.github.javaparser:javaparser-core` AST.
 */
object JavaAstExtractor {

    private val THREAD_LOCAL_PARSER: ThreadLocal<JavaParser> = ThreadLocal.withInitial {
        JavaParser(
            ParserConfiguration().apply {
                languageLevel = ParserConfiguration.LanguageLevel.JAVA_21
            }
        )
    }

    fun parseJavaFile(source: String): JvmParsedFileAst {
        val parser = THREAD_LOCAL_PARSER.get()
        val result = parser.parse(source)
        val cu = result.result.orElse(null)
            ?: return JvmParsedFileAst(
                pkg = "",
                explicitImports = emptyList(),
                wildcardImports = emptyList(),
                importAliases = emptyMap(),
                classes = emptyList()
            )

        val pkg = cu.packageDeclaration.map { it.nameAsString }.orElse("")
        val explicitImports = mutableListOf<String>()
        val wildcardImports = mutableListOf<String>()

        for (imp in cu.imports) {
            if (imp.isAsterisk) {
                if (imp.isStatic) {
                    // `import static com.acme.Utils.*;` imports static members of class `com.acme.Utils`, not a package
                    explicitImports.add(imp.nameAsString)
                } else {
                    wildcardImports.add(imp.nameAsString)
                }
            } else {
                explicitImports.add(imp.nameAsString)
            }
        }

        val totalLines = source.lines().size
        val classes = mutableListOf<JvmParsedClassAst>()

        val typeDecls = cu.findAll(TypeDeclaration::class.java)
        for (typeDecl in typeDecls) {
            val simpleName = typeDecl.nameAsString
            val enclosingTypes = mutableListOf<String>()
            var parentNode = typeDecl.parentNode.orElse(null)
            while (parentNode != null) {
                if (parentNode is TypeDeclaration<*>) {
                    enclosingTypes.add(0, parentNode.nameAsString)
                }
                parentNode = parentNode.parentNode.orElse(null)
            }
            val qualifiedSimple = if (enclosingTypes.isNotEmpty()) {
                "${enclosingTypes.joinToString(".")}.$simpleName"
            } else {
                simpleName
            }
            val fqId = if (pkg.isNotEmpty()) "$pkg.$qualifiedSimple" else qualifiedSimple
            val declKeyword = when (typeDecl) {
                is RecordDeclaration -> "record"
                is EnumDeclaration -> "enum"
                is ClassOrInterfaceDeclaration -> if (typeDecl.isInterface) "interface" else "class"
                else -> "class"
            }

            val classAnns = typeDecl.annotations.map { convertAnnotation(it) }
            val methodDecls = typeDecl.methods
            val allAnnsInClass = (classAnns + methodDecls.flatMap { m -> m.annotations.map { convertAnnotation(it) } })
            val stereotypes = allAnnsInClass.map { "@${it.name}" }.distinct()

            val fieldsMap = linkedMapOf<String, String>()
            val members = mutableListOf<MemberSignature>()
            val referencedSimple = mutableSetOf<String>()
            val referencedFq = mutableSetOf<String>()
            val superSimple = mutableSetOf<String>()

            if (typeDecl is RecordDeclaration) {
                for (param in typeDecl.parameters) {
                    val tRef = convertType(param.type, isVararg = param.isVarArgs)
                    tRef.collectAllSimpleTypeNames(referencedSimple)
                    val wireRaw = if (param.isVarArgs) "${tRef.render().removeSuffix("...")}[]" else tRef.render()
                    val unwrapped = AstTypeUnwrapper.unwrapFieldWireTypeString(wireRaw)
                    val paramAnns = param.annotations.map { convertAnnotation(it) }
                    val wireName = AstAnnotation.resolveSerializedFieldName(paramAnns, param.nameAsString)
                    fieldsMap[wireName] = unwrapped
                    members.add(MemberSignature(name = param.nameAsString, kind = "FIELD", typeOrSignature = tRef.render()))
                }
            }

            if (typeDecl is ClassOrInterfaceDeclaration) {
                for (ext in typeDecl.extendedTypes) {
                    superSimple.add(ext.nameAsString)
                    convertType(ext).collectAllSimpleTypeNames(referencedSimple)
                }
            }
            if (typeDecl is NodeWithImplements<*>) {
                for (impl in typeDecl.implementedTypes) {
                    superSimple.add(impl.nameAsString)
                    convertType(impl).collectAllSimpleTypeNames(referencedSimple)
                }
            }

            // Iterate direct class fields & constructors (avoiding anonymous inner class fields inside method bodies)
            for (field in typeDecl.fields) {
                if (field.isStatic) continue
                val tRef = convertType(field.elementType)
                tRef.collectAllSimpleTypeNames(referencedSimple)
                val unwrapped = AstTypeUnwrapper.unwrapFieldWireTypeString(tRef.render())
                val fieldAnns = field.annotations.map { convertAnnotation(it) }
                for (v in field.variables) {
                    val wireName = AstAnnotation.resolveSerializedFieldName(fieldAnns, v.nameAsString)
                    fieldsMap.putIfAbsent(wireName, unwrapped)
                    members.add(MemberSignature(name = v.nameAsString, kind = "FIELD", typeOrSignature = tRef.render()))
                }
            }

            for (ctor in typeDecl.constructors) {
                for (p in ctor.parameters) {
                    val tRef = convertType(p.type, isVararg = p.isVarArgs)
                    tRef.collectAllSimpleTypeNames(referencedSimple)
                    if (members.none { it.name == p.nameAsString && it.kind == "FIELD" }) {
                        members.add(MemberSignature(name = p.nameAsString, kind = "FIELD", typeOrSignature = tRef.render()))
                    }
                }
            }

            val astMethods = mutableListOf<AstMethod>()
            for (m in methodDecls) {
                val mAnns = m.annotations.map { convertAnnotation(it) }
                val mParams = m.parameters.map { p ->
                    val pType = convertType(p.type, isVararg = p.isVarArgs)
                    pType.collectAllSimpleTypeNames(referencedSimple)
                    AstParam(
                        name = p.nameAsString,
                        type = pType,
                        annotations = p.annotations.map { convertAnnotation(it) }
                    )
                }
                val retType = convertType(m.type)
                retType.collectAllSimpleTypeNames(referencedSimple)
                val vis = when {
                    m.isPrivate -> "private"
                    m.isProtected -> "protected"
                    m.isPublic || (typeDecl is ClassOrInterfaceDeclaration && typeDecl.isInterface) -> "public"
                    else -> "package-private"
                }
                val sigStr = "(${mParams.joinToString(", ") { "${it.type.render()} ${it.name}" }}): ${retType.render()}"
                members.add(MemberSignature(name = m.nameAsString, kind = "METHOD", typeOrSignature = sigStr, visibility = vis))
                astMethods.add(
                    AstMethod(
                        name = m.nameAsString,
                        parameters = mParams,
                        returnType = retType,
                        annotations = mAnns,
                        visibility = vis
                    )
                )
            }

            for (cit in typeDecl.findAll(ClassOrInterfaceType::class.java)) {
                if (cit.findAncestor(TypeDeclaration::class.java).orElse(null) !== typeDecl) continue
                val fullText = qualifiedTypeName(cit)
                if (fullText.contains('.')) {
                    referencedFq.add(fullText)
                } else {
                    referencedSimple.add(cit.nameAsString)
                }
            }
            for (objNew in typeDecl.findAll(ObjectCreationExpr::class.java)) {
                if (objNew.findAncestor(TypeDeclaration::class.java).orElse(null) !== typeDecl) continue
                val typeStr = qualifiedTypeName(objNew.type)
                if (typeStr.contains('.')) {
                    referencedFq.add(typeStr)
                } else {
                    referencedSimple.add(objNew.type.nameAsString)
                }
            }
            val inlineRoutes = mutableListOf<ContractEndpoint>()
            for (call in typeDecl.findAll(MethodCallExpr::class.java)) {
                if (call.findAncestor(TypeDeclaration::class.java).orElse(null) !== typeDecl) continue
                call.scope.ifPresent { scope ->
                    val scopeStr = scope.toString()
                    if (scopeStr.contains('.') && scopeStr[0].isLowerCase()) {
                        referencedFq.add(scopeStr)
                    } else if (scopeStr.isNotEmpty() && scopeStr[0].isUpperCase()) {
                        referencedSimple.add(scopeStr.substringBefore('.'))
                    }
                    if ((scopeStr.endsWith("kafkaTemplate") || scopeStr.endsWith("producer")) &&
                        call.nameAsString == "send" && call.arguments.isNotEmpty()
                    ) {
                        val firstArg = call.arguments[0]
                        if (firstArg is StringLiteralExpr) {
                            inlineRoutes.add(
                                ContractEndpoint(
                                    protocol = "KAFKA",
                                    methodOrAction = "PUBLISH",
                                    pathOrTopic = firstArg.asString(),
                                    role = "PRODUCER"
                                )
                            )
                        }
                    }
                }
            }
            for (fa in typeDecl.findAll(FieldAccessExpr::class.java)) {
                if (fa.findAncestor(TypeDeclaration::class.java).orElse(null) !== typeDecl) continue
                val full = fa.toString()
                if (full.contains('.')) {
                    referencedFq.add(full)
                    val parent = full.substringBeforeLast('.')
                    if (parent.contains('.')) referencedFq.add(parent)
                }
            }
            for (mr in typeDecl.findAll(MethodReferenceExpr::class.java)) {
                if (mr.findAncestor(TypeDeclaration::class.java).orElse(null) !== typeDecl) continue
                val scopeStr = mr.scope.toString().substringBefore('<')
                if (scopeStr.contains('.')) {
                    referencedFq.add(scopeStr)
                } else if (scopeStr.isNotEmpty() && scopeStr[0].isUpperCase()) {
                    referencedSimple.add(scopeStr)
                }
            }

            referencedSimple.remove(simpleName)
            val nodeKind = AstTypeUnwrapper.classifyNodeKind(
                declKeyword = declKeyword,
                stereotypes = stereotypes,
                simpleName = simpleName,
                isDataOrRecord = typeDecl is RecordDeclaration
            )

            classes.add(
                JvmParsedClassAst(
                    fqId = fqId,
                    simpleName = simpleName,
                    pkg = pkg,
                    kind = nodeKind,
                    stereotypes = stereotypes,
                    annotations = classAnns,
                    fields = fieldsMap,
                    members = members,
                    methods = astMethods,
                    inlineRoutes = inlineRoutes,
                    referencedSimpleNames = referencedSimple,
                    referencedQualifiedNames = referencedFq,
                    superTypeSimpleNames = superSimple,
                    startLine = if (typeDecls.size == 1) 1 else typeDecl.begin.map { it.line }.orElse(1),
                    endLine = if (typeDecls.size == 1) totalLines else typeDecl.end.map { it.line }.orElse(totalLines)
                )
            )
        }

        return JvmParsedFileAst(
            pkg = pkg,
            explicitImports = explicitImports,
            wildcardImports = wildcardImports,
            importAliases = emptyMap(),
            classes = classes
        )
    }

    private fun qualifiedTypeName(cit: ClassOrInterfaceType): String {
        val scope = cit.scope.orElse(null)
        return if (scope != null) "${qualifiedTypeName(scope)}.${cit.nameAsString}" else cit.nameAsString
    }

    private fun convertType(type: Type, isVararg: Boolean = false): AstTypeRef {
        if (type is ArrayType) {
            val comp = convertType(type.componentType)
            return AstTypeRef(rawName = "${comp.render()}[]", isVararg = isVararg)
        }
        if (type is ClassOrInterfaceType) {
            val args = type.typeArguments.map { list -> list.map { convertType(it) } }.orElse(emptyList())
            return AstTypeRef(rawName = qualifiedTypeName(type), typeArguments = args, isVararg = isVararg)
        }
        return AstTypeRef(rawName = type.asString(), isVararg = isVararg)
    }

    private fun convertAnnotation(ann: AnnotationExpr): AstAnnotation {
        val shortName = ann.nameAsString.substringAfterLast('.')
        val named = linkedMapOf<String, List<String>>()
        val positional = mutableListOf<String>()

        when (ann) {
            is SingleMemberAnnotationExpr -> {
                val vals = extractExprStrings(ann.memberValue)
                positional.addAll(vals)
                named["value"] = vals
            }
            is NormalAnnotationExpr -> {
                for (pair in ann.pairs) {
                    val vals = extractExprStrings(pair.value)
                    named[pair.nameAsString] = vals
                    if (pair.nameAsString in setOf("value", "path", "topics", "queues")) {
                        positional.addAll(vals)
                    }
                }
            }
        }
        return AstAnnotation(
            name = shortName,
            namedArgs = named,
            positionalStrings = positional,
            rawTokensText = ann.toString()
        )
    }

    private fun extractExprStrings(expr: Expression): List<String> {
        return when (expr) {
            is StringLiteralExpr -> listOf(expr.asString())
            is ArrayInitializerExpr -> expr.values.flatMap { extractExprStrings(it) }
            else -> listOf(expr.toString().removeSurrounding("\""))
        }
    }
}
