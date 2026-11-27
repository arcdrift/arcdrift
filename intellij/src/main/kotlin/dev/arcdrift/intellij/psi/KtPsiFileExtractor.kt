package dev.arcdrift.intellij.psi

import dev.arcdrift.core.ast.*
import dev.arcdrift.core.model.ContractEndpoint
import dev.arcdrift.core.model.MemberSignature
import dev.arcdrift.core.model.NodeKind
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import org.jetbrains.kotlin.psi.psiUtil.endOffset
import org.jetbrains.kotlin.psi.psiUtil.startOffset

/**
 * Pure Kotlin PSI (`org.jetbrains.kotlin.psi.KtFile`) extractor compiled against IntelliJ Platform's
 * unshaded `com.intellij.psi.*` hierarchy (K1 & K2 compatible).
 *
 * Note: `arcdrift-core` compiles against `kotlin-compiler-embeddable` (which shades `com.intellij.psi.*`
 * under `org.jetbrains.kotlin.com.intellij.psi.*`), so all `KtElement` PSI traversal in the IDE plugin
 * must live here in `arcdrift-intellij` while producing shared [JvmParsedFileAst] models.
 */
object KtPsiFileExtractor {

    private val KTOR_VERBS = setOf("get", "post", "put", "delete", "patch")

    fun parseKtFile(
        ktFile: KtFile,
        defaultFileSimpleName: String = ktFile.name.removeSuffix(".kt") + "Kt"
    ): JvmParsedFileAst {
        val source = ktFile.text
        val pkg = ktFile.packageFqName.asString()

        val explicitImports = mutableListOf<String>()
        val wildcardImports = mutableListOf<String>()
        val importAliases = linkedMapOf<String, String>()

        for (imp in ktFile.importDirectives) {
            val importedFq = imp.importedFqName?.asString() ?: continue
            if (imp.isAllUnder) {
                wildcardImports.add(importedFq)
            } else {
                explicitImports.add(importedFq)
                val alias = imp.aliasName
                if (!alias.isNullOrEmpty()) {
                    importAliases[alias] = importedFq
                }
            }
        }

        val totalLines = source.lines().size
        val classes = mutableListOf<JvmParsedClassAst>()

        val topLevelClasses = ktFile.declarations.filterIsInstance<KtClassOrObject>().filterNot { it is KtEnumEntry }
        fun collectKtClassesRecursively(cls: KtClassOrObject, outerPrefix: String?, isSingleTopLevel: Boolean) {
            parseKtClassOrObject(
                cls = cls,
                pkg = pkg,
                source = source,
                totalLines = totalLines,
                isSingleClassInFile = isSingleTopLevel,
                outerClassPrefix = outerPrefix
            )?.let {
                classes.add(it)
            }
            val currentPrefix = listOfNotNull(outerPrefix, cls.name).joinToString(".").ifEmpty { null }
            val nestedClassDecls = cls.declarations
                .filterIsInstance<KtClassOrObject>()
                .filterNot { it is KtEnumEntry }
                .filterNot { it is KtObjectDeclaration && it.isCompanion() }
            for (nested in nestedClassDecls) {
                collectKtClassesRecursively(nested, currentPrefix, isSingleTopLevel = false)
            }
        }
        for (cls in topLevelClasses) {
            collectKtClassesRecursively(cls, outerPrefix = null, isSingleTopLevel = topLevelClasses.size == 1)
        }

        val topLevelFunctions = ktFile.declarations.filterIsInstance<KtNamedFunction>()
        val topLevelProps = ktFile.declarations.filterIsInstance<KtProperty>()
        if (classes.isEmpty() &&
            (topLevelFunctions.isNotEmpty() || topLevelProps.isNotEmpty() || explicitImports.isNotEmpty() || wildcardImports.isNotEmpty())
        ) {
            val fqId = if (pkg.isNotEmpty()) "$pkg.$defaultFileSimpleName" else defaultFileSimpleName
            val referencedSimple = mutableSetOf<String>()
            val referencedFq = mutableSetOf<String>()
            val inlineRoutes = mutableListOf<ContractEndpoint>()
            val fields = linkedMapOf<String, String>()
            val members = mutableListOf<MemberSignature>()
            val methods = mutableListOf<AstMethod>()
            val fileAnns = ktFile.annotationEntries.map { convertKtAnnotation(it) }

            for (prop in topLevelProps) {
                extractPropertyInfo(prop, fields, members, referencedSimple, referencedFq)
            }
            for (fn in topLevelFunctions) {
                val m = convertKtFunction(fn, referencedSimple, referencedFq, fields)
                methods.add(m)
                val paramSig = m.parameters.joinToString(", ") { "${it.name}: ${it.type.render()}" }
                val retSig = m.returnType?.render() ?: m.inferredReturnTypeName ?: "Unit"
                members.add(MemberSignature(name = m.name, kind = "METHOD", typeOrSignature = "($paramSig): $retSig", visibility = m.visibility))
            }
            collectExpressionsFromPsiScope(ktFile, null, referencedSimple, referencedFq, inlineRoutes)

            classes.add(
                JvmParsedClassAst(
                    fqId = fqId,
                    simpleName = defaultFileSimpleName,
                    pkg = pkg,
                    kind = if (inlineRoutes.any { it.protocol == "REST" }) NodeKind.REST_CONTROLLER else NodeKind.CLASS,
                    stereotypes = fileAnns.map { "@${it.name}" }.distinct(),
                    annotations = fileAnns,
                    fields = fields,
                    members = members,
                    methods = methods,
                    inlineRoutes = inlineRoutes,
                    referencedSimpleNames = referencedSimple - defaultFileSimpleName,
                    referencedQualifiedNames = referencedFq,
                    startLine = 1,
                    endLine = maxOf(1, totalLines)
                )
            )
        }

        return JvmParsedFileAst(
            pkg = pkg,
            explicitImports = explicitImports,
            wildcardImports = wildcardImports,
            importAliases = importAliases,
            classes = classes
        )
    }

    private fun parseKtClassOrObject(
        cls: KtClassOrObject,
        pkg: String,
        source: String,
        totalLines: Int,
        isSingleClassInFile: Boolean,
        outerClassPrefix: String? = null
    ): JvmParsedClassAst? {
        val simpleName = cls.name ?: return null
        val qualifiedSimple = if (!outerClassPrefix.isNullOrEmpty()) "$outerClassPrefix.$simpleName" else simpleName
        val fqId = if (pkg.isNotEmpty()) "$pkg.$qualifiedSimple" else qualifiedSimple

        val declKeyword = when (cls) {
            is KtObjectDeclaration -> "object"
            is KtClass -> when {
                cls.isInterface() -> "interface"
                cls.isEnum() -> "enum class"
                cls.isData() -> "data class"
                else -> "class"
            }
            else -> "class"
        }

        val classAnns = cls.annotationEntries.map { convertKtAnnotation(it) }
        val fieldsMap = linkedMapOf<String, String>()
        val members = mutableListOf<MemberSignature>()
        val referencedSimple = mutableSetOf<String>()
        val referencedFq = mutableSetOf<String>()
        val superSimple = mutableSetOf<String>()

        for (param in cls.primaryConstructorParameters) {
            val typeRef = convertKtTypeRef(param.typeReference, referencedSimple, referencedFq)
            if (param.hasValOrVar()) {
                val unwrapped = AstTypeUnwrapper.unwrapFieldWireTypeString(typeRef.render())
                val pName = param.name ?: continue
                val paramAnns = param.annotationEntries.map { convertKtAnnotation(it) }
                val wireName = AstAnnotation.resolveSerializedFieldName(paramAnns, pName)
                fieldsMap[wireName] = unwrapped
                members.add(MemberSignature(name = pName, kind = "FIELD", typeOrSignature = typeRef.render(), visibility = extractVisibility(param)))
            }
        }

        for (superEntry in cls.superTypeListEntries) {
            val tRef = convertKtTypeRef(superEntry.typeReference, referencedSimple, referencedFq)
            val sSimple = tRef.rawName.substringAfterLast('.')
            if (sSimple.isNotEmpty() && sSimple[0].isUpperCase()) {
                superSimple.add(sSimple)
            }
        }

        val instanceProps = cls.declarations.filterIsInstance<KtProperty>()
        for (prop in instanceProps) {
            extractPropertyInfo(prop, fieldsMap, members, referencedSimple, referencedFq, includeInWireFields = !prop.hasModifier(KtTokens.CONST_KEYWORD))
        }
        val companionProps = cls.companionObjects.flatMap { it.declarations }.filterIsInstance<KtProperty>()
        for (prop in companionProps) {
            extractPropertyInfo(prop, fieldsMap, members, referencedSimple, referencedFq, includeInWireFields = false)
        }

        val bodyDecls = cls.declarations + cls.companionObjects.flatMap { it.declarations }

        val bodyFns = bodyDecls.filterIsInstance<KtNamedFunction>()
        val methods = mutableListOf<AstMethod>()
        for (fn in bodyFns) {
            val m = convertKtFunction(fn, referencedSimple, referencedFq, fieldsMap)
            methods.add(m)
            val paramSig = m.parameters.joinToString(", ") { "${it.name}: ${it.type.render()}" }
            val retSig = m.returnType?.render() ?: m.inferredReturnTypeName ?: "Unit"
            members.add(MemberSignature(name = m.name, kind = "METHOD", typeOrSignature = "($paramSig): $retSig", visibility = m.visibility))
        }

        for (secCtor in cls.secondaryConstructors) {
            for (p in secCtor.valueParameters) {
                convertKtTypeRef(p.typeReference, referencedSimple, referencedFq)
            }
        }

        val inlineRoutes = mutableListOf<ContractEndpoint>()
        val nestedClasses = cls.declarations
            .filterIsInstance<KtClassOrObject>()
            .filterNot { it is KtEnumEntry }
            .filterNot { it is KtObjectDeclaration && it.isCompanion() }
        val scopeElement: KtElement = if (isSingleClassInFile && nestedClasses.isEmpty()) cls.containingKtFile else cls
        collectExpressionsFromPsiScope(scopeElement, cls.takeIf { nestedClasses.isNotEmpty() }, referencedSimple, referencedFq, inlineRoutes)

        val allAnns = classAnns + methods.flatMap { it.annotations }
        val stereotypes = allAnns.map { "@${it.name}" }.distinct()
        val isDataClass = cls is KtClass && (cls.isData() || cls.isValue() || cls.hasModifier(KtTokens.INLINE_KEYWORD))

        val nodeKind = if (inlineRoutes.any { it.protocol == "REST" }) {
            NodeKind.REST_CONTROLLER
        } else {
            AstTypeUnwrapper.classifyNodeKind(
                declKeyword = declKeyword,
                stereotypes = stereotypes,
                simpleName = simpleName,
                isDataOrRecord = isDataClass
            )
        }

        referencedSimple.remove(simpleName)

        return JvmParsedClassAst(
            fqId = fqId,
            simpleName = simpleName,
            pkg = pkg,
            kind = nodeKind,
            stereotypes = stereotypes,
            annotations = classAnns,
            fields = fieldsMap,
            members = members,
            methods = methods,
            inlineRoutes = inlineRoutes,
            referencedSimpleNames = referencedSimple,
            referencedQualifiedNames = referencedFq,
            superTypeSimpleNames = superSimple,
            startLine = offsetToLine(source, cls.startOffset),
            endLine = maxOf(1, minOf(totalLines, offsetToLine(source, cls.endOffset)))
        )
    }

    private fun extractPropertyInfo(
        prop: KtProperty,
        fieldsMap: MutableMap<String, String>,
        members: MutableList<MemberSignature>,
        referencedSimple: MutableSet<String>,
        referencedFq: MutableSet<String>,
        includeInWireFields: Boolean = true
    ) {
        val pName = prop.name ?: return
        val explicitType = prop.typeReference?.let { convertKtTypeRef(it, referencedSimple, referencedFq) }
        val inferredType = if (explicitType == null) {
            inferExpressionReturnDto(prop.initializer)?.let { AstTypeRef(it) }
        } else null
        val typeToUse = explicitType ?: inferredType ?: AstTypeRef("Any")
        if (inferredType != null) {
            inferredType.collectAllSimpleTypeNames(referencedSimple)
        }
        if (includeInWireFields) {
            val unwrapped = AstTypeUnwrapper.unwrapFieldWireTypeString(typeToUse.render())
            val propAnns = prop.annotationEntries.map { convertKtAnnotation(it) }
            val wireName = AstAnnotation.resolveSerializedFieldName(propAnns, pName)
            fieldsMap.putIfAbsent(wireName, unwrapped)
        }
        members.add(MemberSignature(name = pName, kind = "FIELD", typeOrSignature = typeToUse.render(), visibility = extractVisibility(prop)))
    }

    private fun convertKtFunction(
        fn: KtNamedFunction,
        referencedSimple: MutableSet<String>,
        referencedFq: MutableSet<String>,
        classFields: Map<String, String> = emptyMap()
    ): AstMethod {
        val fnAnns = fn.annotationEntries.map { convertKtAnnotation(it) }
        val params = fn.valueParameters.map { p ->
            val pType = convertKtTypeRef(p.typeReference, referencedSimple, referencedFq)
            val pAnns = p.annotationEntries.map { convertKtAnnotation(it) }
            AstParam(name = p.name ?: "arg", type = pType, annotations = pAnns, isProperty = p.hasValOrVar())
        }
        val explicitRet = fn.typeReference?.let { convertKtTypeRef(it, referencedSimple, referencedFq) }
        val inferredRet = if (explicitRet == null && fn.bodyExpression != null && fn.bodyBlockExpression == null) {
            inferExpressionReturnDto(fn.bodyExpression, params, classFields)
        } else null

        return AstMethod(
            name = fn.name ?: "anonymous",
            parameters = params,
            returnType = explicitRet,
            inferredReturnTypeName = inferredRet,
            annotations = fnAnns,
            visibility = extractVisibility(fn),
            isSuspend = fn.hasModifier(KtTokens.SUSPEND_KEYWORD)
        )
    }

    private fun collectExpressionsFromPsiScope(
        scope: KtElement,
        enclosingClass: KtClassOrObject?,
        referencedSimple: MutableSet<String>,
        referencedFq: MutableSet<String>,
        inlineRoutes: MutableList<ContractEndpoint>
    ) {
        val nestedClasses = enclosingClass?.declarations
            ?.filterIsInstance<KtClassOrObject>()
            ?.filterNot { it is KtEnumEntry }
            ?.filterNot { it is KtObjectDeclaration && it.isCompanion() }
            .orEmpty()

        fun isInsideNestedClass(el: KtElement): Boolean {
            if (nestedClasses.isEmpty()) return false
            var p = el.parent
            while (p != null && p !== enclosingClass) {
                if (p in nestedClasses) return true
                p = p.parent
            }
            return false
        }

        val callExprs = scope.collectDescendantsOfType<KtCallExpression>().filterNot(::isInsideNestedClass)
        for (typeRef in scope.collectDescendantsOfType<KtTypeReference>().filterNot(::isInsideNestedClass)) {
            convertKtTypeRef(typeRef, referencedSimple, referencedFq)
        }
        for (call in callExprs) {
            val calleeText = call.calleeExpression?.text?.trim().orEmpty()
            if (calleeText.isNotEmpty() && calleeText[0].isUpperCase()) {
                referencedSimple.add(calleeText.substringBefore('<'))
            }
            for (ta in call.typeArguments) {
                convertKtTypeRef(ta.typeReference, referencedSimple, referencedFq)
            }
            if (calleeText in KTOR_VERBS && call.valueArguments.isNotEmpty() && call.lambdaArguments.isNotEmpty()) {
                val firstArgExpr = call.valueArguments.first().getArgumentExpression()
                val routePath = extractKtStringLiteral(firstArgExpr)
                if (!routePath.isNullOrEmpty() && routePath.startsWith("/")) {
                    val lambdaBody = call.lambdaArguments.firstOrNull()?.getLambdaExpression()?.bodyExpression
                    val respondDto = lambdaBody?.collectDescendantsOfType<KtCallExpression>()
                        ?.firstOrNull { it.calleeExpression?.text == "respond" }
                        ?.valueArguments?.firstOrNull()?.getArgumentExpression()
                        ?.let { inferExpressionReturnDto(it) }
                    inlineRoutes.add(
                        ContractEndpoint(
                            protocol = "REST",
                            methodOrAction = calleeText.uppercase(),
                            pathOrTopic = routePath,
                            role = "PRODUCER",
                            responseDto = respondDto
                        )
                    )
                }
            }
        }

        val dotExprs = scope.collectDescendantsOfType<KtDotQualifiedExpression>().filterNot(::isInsideNestedClass)
        for (dot in dotExprs) {
            val receiverText = dot.receiverExpression.text.trim()
            if (receiverText.isNotEmpty() && receiverText[0].isUpperCase() && !receiverText.contains('(') && !receiverText.contains('"')) {
                referencedSimple.add(receiverText.substringBefore('.').substringBefore('<'))
            }
            val selectorCall = dot.selectorExpression as? KtCallExpression
            if (selectorCall?.calleeExpression?.text == "send" &&
                (receiverText.endsWith("kafkaTemplate") || receiverText.endsWith("producer"))
            ) {
                val topicArg = selectorCall.valueArguments.firstOrNull()?.getArgumentExpression()
                val topicStr = extractKtStringLiteral(topicArg)
                if (!topicStr.isNullOrEmpty()) {
                    inlineRoutes.add(
                        ContractEndpoint(
                            protocol = "KAFKA",
                            methodOrAction = "PUBLISH",
                            pathOrTopic = topicStr,
                            role = "PRODUCER"
                        )
                    )
                }
            }
            val fullText = dot.text.substringBefore('(').trim()
            if (Regex("""^[a-z][a-zA-Z0-9_]*(\.[a-z][a-zA-Z0-9_]*)+(\.[A-Z][a-zA-Z0-9_]*)+$""").matches(fullText)) {
                val parts = fullText.split('.')
                val classIdx = parts.indexOfFirst { it.isNotEmpty() && it[0].isUpperCase() }
                if (classIdx > 0) {
                    referencedFq.add(parts.subList(0, classIdx + 1).joinToString("."))
                }
            }
        }

        for (callableRef in scope.collectDescendantsOfType<KtCallableReferenceExpression>().filterNot(::isInsideNestedClass)) {
            val recv = callableRef.receiverExpression?.text?.substringBefore('<')?.trim().orEmpty()
            if (recv.contains('.') && recv.substringAfterLast('.').firstOrNull()?.isUpperCase() == true) {
                referencedFq.add(recv)
            } else if (recv.isNotEmpty() && recv[0].isUpperCase()) {
                referencedSimple.add(recv)
            }
        }

        for (classLit in scope.collectDescendantsOfType<KtClassLiteralExpression>().filterNot(::isInsideNestedClass)) {
            val recv = classLit.receiverExpression?.text?.substringBefore('<')?.trim().orEmpty()
            if (recv.contains('.') && recv.substringAfterLast('.').firstOrNull()?.isUpperCase() == true) {
                referencedFq.add(recv)
            } else if (recv.isNotEmpty() && recv[0].isUpperCase()) {
                referencedSimple.add(recv)
            }
        }
    }

    private fun inferExpressionReturnDto(
        expr: KtExpression?,
        params: List<AstParam> = emptyList(),
        fields: Map<String, String> = emptyMap()
    ): String? {
        if (expr == null) return null
        if (expr is KtNameReferenceExpression) {
            val refName = expr.getReferencedName()
            return params.find { it.name == refName }?.type?.render() ?: fields[refName]
        }
        if (expr is KtStringTemplateExpression) return "String"
        if (expr is KtConstantExpression) {
            val text = expr.text.trim()
            return when {
                text == "true" || text == "false" -> "Boolean"
                text.endsWith("L") || text.endsWith("l") -> "Long"
                text.contains('.') -> "Double"
                text.toIntOrNull() != null -> "Int"
                else -> null
            }
        }
        if (expr is KtCallExpression) {
            val callee = expr.calleeExpression?.text ?: return null
            if (callee.isNotEmpty() && callee[0].isUpperCase()) return callee
            return when (callee) {
                "listOf", "emptyList", "mutableListOf" -> "List<Any>"
                "setOf", "emptySet", "mutableSetOf" -> "Set<Any>"
                "mapOf", "emptyMap", "mutableMapOf" -> "Map<Any, Any>"
                else -> null
            }
        }
        if (expr is KtDotQualifiedExpression) {
            val receiver = expr.receiverExpression.text
            val selector = expr.selectorExpression
            if (receiver == "ResponseEntity" && selector is KtCallExpression) {
                val firstArg = selector.valueArguments.firstOrNull()?.getArgumentExpression()
                if (firstArg is KtCallExpression) {
                    val innerCallee = firstArg.calleeExpression?.text
                    if (!innerCallee.isNullOrEmpty() && innerCallee[0].isUpperCase()) return innerCallee
                }
            }
        }
        return null
    }

    private fun convertKtTypeRef(
        typeRef: KtTypeReference?,
        referencedSimple: MutableSet<String>,
        referencedFq: MutableSet<String>
    ): AstTypeRef {
        val elem = typeRef?.typeElement ?: return AstTypeRef("Any")
        return convertKtTypeElement(elem, isNullable = false, referencedSimple = referencedSimple, referencedFq = referencedFq)
    }

    private fun convertKtTypeElement(
        elem: KtTypeElement,
        isNullable: Boolean,
        referencedSimple: MutableSet<String>,
        referencedFq: MutableSet<String>
    ): AstTypeRef {
        if (elem is KtNullableType) {
            val inner = elem.innerType ?: return AstTypeRef("Any", isNullable = true)
            return convertKtTypeElement(inner, isNullable = true, referencedSimple = referencedSimple, referencedFq = referencedFq)
        }
        if (elem is KtUserType) {
            val qualifierText = elem.qualifier?.text?.replace(Regex("\\s+"), "")
            val refName = elem.referencedName ?: "Any"
            val fullRawName = if (!qualifierText.isNullOrEmpty()) "$qualifierText.$refName" else refName
            if (fullRawName.contains('.')) {
                referencedFq.add(fullRawName)
            } else if (refName.isNotEmpty() && refName[0].isUpperCase()) {
                referencedSimple.add(refName)
            }
            val args = elem.typeArgumentsAsTypes.map { argRef ->
                convertKtTypeRef(argRef, referencedSimple, referencedFq)
            }
            return AstTypeRef(rawName = fullRawName, typeArguments = args, isNullable = isNullable)
        }
        return AstTypeRef(rawName = elem.text, isNullable = isNullable)
    }

    private fun convertKtAnnotation(entry: KtAnnotationEntry): AstAnnotation {
        val shortName = entry.shortName?.asString() ?: entry.typeReference?.text?.substringAfterLast('.') ?: "Annotation"
        val named = linkedMapOf<String, List<String>>()
        val positional = mutableListOf<String>()

        for (arg in entry.valueArguments) {
            val vals = arg.getArgumentExpression()?.let { extractKtExprStrings(it) }.orEmpty()
            val argName = arg.getArgumentName()?.asName?.asString()
            if (!argName.isNullOrEmpty()) {
                named[argName] = vals
                if (argName in setOf("value", "path", "topics", "queues", "name")) {
                    positional.addAll(vals)
                }
            } else {
                positional.addAll(vals)
            }
        }

        return AstAnnotation(
            name = shortName,
            namedArgs = named,
            positionalStrings = positional,
            rawTokensText = entry.text
        )
    }

    private fun extractKtExprStrings(expr: KtExpression): List<String> {
        return when (expr) {
            is KtStringTemplateExpression -> listOfNotNull(extractKtStringLiteral(expr))
            is KtCollectionLiteralExpression -> expr.getInnerExpressions().flatMap { extractKtExprStrings(it) }
            is KtCallExpression -> {
                val callee = expr.calleeExpression?.text.orEmpty()
                if (callee == "arrayOf" || callee == "listOf") {
                    expr.valueArguments.mapNotNull { it.getArgumentExpression() }.flatMap { extractKtExprStrings(it) }
                } else {
                    listOf(expr.text)
                }
            }
            else -> listOf(expr.text.removeSurrounding("\""))
        }
    }

    private fun extractKtStringLiteral(expr: KtExpression?): String? =
        (expr as? KtStringTemplateExpression)?.entries?.joinToString("") { it.text }

    private fun offsetToLine(source: String, offset: Int): Int {
        val clamped = offset.coerceIn(0, source.length)
        var line = 1
        for (i in 0 until clamped) {
            if (source[i] == '\n') line++
        }
        return line
    }

    private fun extractVisibility(modifierListOwner: KtModifierListOwner): String {
        return when {
            modifierListOwner.hasModifier(KtTokens.PRIVATE_KEYWORD) -> "private"
            modifierListOwner.hasModifier(KtTokens.PROTECTED_KEYWORD) -> "protected"
            modifierListOwner.hasModifier(KtTokens.INTERNAL_KEYWORD) -> "internal"
            else -> "public"
        }
    }
}
