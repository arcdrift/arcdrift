package dev.arcdrift.core.ast

import dev.arcdrift.core.ast.KotlinTreeSitterSupport.callCalleeNode
import dev.arcdrift.core.ast.KotlinTreeSitterSupport.callValueArguments
import dev.arcdrift.core.ast.KotlinTreeSitterSupport.children
import dev.arcdrift.core.ast.KotlinTreeSitterSupport.childrenOfType
import dev.arcdrift.core.ast.KotlinTreeSitterSupport.classDeclarationName
import dev.arcdrift.core.ast.KotlinTreeSitterSupport.convertTypeNode
import dev.arcdrift.core.ast.KotlinTreeSitterSupport.directBodyDeclarations
import dev.arcdrift.core.ast.KotlinTreeSitterSupport.extractAnnotations
import dev.arcdrift.core.ast.KotlinTreeSitterSupport.extractAnnotationsFromModifiersNode
import dev.arcdrift.core.ast.KotlinTreeSitterSupport.extractFileAnnotations
import dev.arcdrift.core.ast.KotlinTreeSitterSupport.extractStringLiteral
import dev.arcdrift.core.ast.KotlinTreeSitterSupport.firstChildOfType
import dev.arcdrift.core.ast.KotlinTreeSitterSupport.hasModifierKeyword
import dev.arcdrift.core.ast.KotlinTreeSitterSupport.inferExpressionReturnDto
import dev.arcdrift.core.ast.KotlinTreeSitterSupport.isClassOrObjectNode
import dev.arcdrift.core.ast.KotlinTreeSitterSupport.isDataOrValueClass
import dev.arcdrift.core.ast.KotlinTreeSitterSupport.isEnumDecl
import dev.arcdrift.core.ast.KotlinTreeSitterSupport.isInterfaceDecl
import dev.arcdrift.core.ast.KotlinTreeSitterSupport.isTypeNode
import dev.arcdrift.core.ast.KotlinTreeSitterSupport.namedChildren
import dev.arcdrift.core.ast.KotlinTreeSitterSupport.offsetToLine
import dev.arcdrift.core.ast.KotlinTreeSitterSupport.readImports
import dev.arcdrift.core.ast.KotlinTreeSitterSupport.readPackageName
import dev.arcdrift.core.ast.KotlinTreeSitterSupport.splitValueArgument
import dev.arcdrift.core.model.ContractEndpoint
import dev.arcdrift.core.model.MemberSignature
import dev.arcdrift.core.model.NodeKind
import org.treesitter.TSNode

/**
 * Lock-free Tree-sitter Kotlin AST extractor powered by `io.github.bonede:tree-sitter-kotlin`
 * (zero IntelliJ PSI / `kotlin-compiler-embeddable` dependencies).
 */
object KotlinAstParser {

    private val KTOR_VERBS = setOf("get", "post", "put", "delete", "patch")
    private val KAFKA_PRODUCER_TYPES = setOf(
        "KafkaTemplate",
        "ReactiveKafkaProducerTemplate",
        "KafkaProducer",
        "Producer",
        "RabbitTemplate",
        "AmqpTemplate"
    )

    fun parseKotlinFile(source: String, defaultFileSimpleName: String = "FileKt"): JvmParsedFileAst {
        val lfSource = source.replace("\r\n", "\n").replace('\r', '\n')
        val cst = KotlinTreeSitterSupport.parse(lfSource)
        val pkg = readPackageName(cst)

        val explicitImports = mutableListOf<String>()
        val wildcardImports = mutableListOf<String>()
        val importAliases = linkedMapOf<String, String>()

        for (imp in readImports(cst)) {
            if (imp.isWildcard) {
                wildcardImports.add(imp.fqName)
            } else {
                explicitImports.add(imp.fqName)
                if (!imp.aliasName.isNullOrEmpty()) {
                    importAliases[imp.aliasName] = imp.fqName
                }
            }
        }

        val totalLines = lfSource.lines().size
        val classes = mutableListOf<JvmParsedClassAst>()

        val topLevelDecls = namedChildren(cst.root)
        val topLevelClasses = topLevelDecls.filter { isClassOrObjectNode(it) }

        fun collectKtClassesRecursively(cls: TSNode, outerPrefix: String?, isSingleTopLevel: Boolean) {
            parseClassOrObject(
                cst = cst,
                cls = cls,
                pkg = pkg,
                source = lfSource,
                totalLines = totalLines,
                isSingleClassInFile = isSingleTopLevel,
                outerClassPrefix = outerPrefix
            )?.let { classes.add(it) }

            val clsName = classDeclarationName(cst, cls)
            val currentPrefix = listOfNotNull(outerPrefix, clsName).joinToString(".").ifEmpty { null }
            val nestedClassDecls = directBodyDeclarations(cls).filter { isClassOrObjectNode(it) }
            for (nested in nestedClassDecls) {
                collectKtClassesRecursively(nested, currentPrefix, isSingleTopLevel = false)
            }
        }

        for (cls in topLevelClasses) {
            collectKtClassesRecursively(cls, outerPrefix = null, isSingleTopLevel = topLevelClasses.size == 1)
        }

        // Handle Kotlin files with top-level functions / Ktor routes and no class declarations
        val topLevelFunctions = topLevelDecls.filter { it.type == "function_declaration" }
        val topLevelProps = topLevelDecls.filter { it.type == "property_declaration" }
        if (classes.isEmpty() &&
            (topLevelFunctions.isNotEmpty() || topLevelProps.isNotEmpty() || explicitImports.isNotEmpty() || wildcardImports.isNotEmpty())
        ) {
            val fqId = if (pkg.isNotEmpty()) "$pkg.$defaultFileSimpleName" else defaultFileSimpleName
            val referencedSimple = mutableSetOf<String>()
            val referencedFq = mutableSetOf<String>()
            val inlineRoutes = mutableListOf<ContractEndpoint>()
            val fields = linkedMapOf<String, String>()
            val localMemberTypes = linkedMapOf<String, String>()
            val members = mutableListOf<MemberSignature>()
            val methods = mutableListOf<AstMethod>()
            val fileAnns = extractFileAnnotations(cst)

            for (prop in topLevelProps) {
                extractPropertyInfo(cst, prop, fields, localMemberTypes, members, referencedSimple, referencedFq)
            }
            for (fn in topLevelFunctions) {
                val m = convertFunction(cst, fn, referencedSimple, referencedFq, fields)
                methods.add(m)
                members.add(renderMethodMemberSignature(m))
            }
            collectExpressionsFromCstScope(
                cst = cst,
                scope = cst.root,
                selfSimpleName = null,
                localMemberTypes = localMemberTypes,
                referencedSimple = referencedSimple,
                referencedFq = referencedFq,
                inlineRoutes = inlineRoutes,
                skipDirectNestedClasses = false
            )

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
                    referencedSimpleNames = referencedSimple,
                    referencedQualifiedNames = referencedFq,
                    startLine = 1,
                    endLine = totalLines
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

    private fun parseClassOrObject(
        cst: ParsedKotlinCst,
        cls: TSNode,
        pkg: String,
        source: String,
        totalLines: Int,
        isSingleClassInFile: Boolean = false,
        outerClassPrefix: String? = null
    ): JvmParsedClassAst? {
        val simpleName = classDeclarationName(cst, cls) ?: return null
        val qualifiedSimple = if (!outerClassPrefix.isNullOrEmpty()) "$outerClassPrefix.$simpleName" else simpleName
        val fqId = if (pkg.isNotEmpty()) "$pkg.$qualifiedSimple" else qualifiedSimple

        val declKeyword = when {
            cls.type == "object_declaration" -> "object"
            isInterfaceDecl(cls) -> "interface"
            isEnumDecl(cst, cls) -> "enum class"
            else -> "class"
        }
        val isDataOrValue = isDataOrValueClass(cst, cls)

        val classAnns = extractAnnotations(cst, cls)
        val fields = linkedMapOf<String, String>()
        val localMemberTypes = linkedMapOf<String, String>()
        val members = mutableListOf<MemberSignature>()
        val methods = mutableListOf<AstMethod>()
        val inlineRoutes = mutableListOf<ContractEndpoint>()
        val referencedSimple = mutableSetOf<String>()
        val referencedFq = mutableSetOf<String>()

        // 1. Primary constructor parameters
        val primaryCtor = firstChildOfType(cls, "primary_constructor")
        if (primaryCtor != null) {
            for (param in childrenOfType(primaryCtor, "class_parameter")) {
                val pName = firstChildOfType(param, "simple_identifier")?.let { cst.slice(it).trim() }
                val typeNode = namedChildren(param).firstOrNull { isTypeNode(it) }
                val pType = convertTypeNode(cst, typeNode, referencedSimple, referencedFq)
                if (!pName.isNullOrEmpty()) {
                    localMemberTypes[pName] = pType.rawName.substringAfterLast('.')
                }
                val hasValOrVar = firstChildOfType(param, "binding_pattern_kind") != null
                if (hasValOrVar && !pName.isNullOrEmpty()) {
                    val rendered = pType.render()
                    val paramAnns = extractAnnotations(cst, param)
                    val wireName = AstAnnotation.resolveSerializedFieldName(paramAnns, pName)
                    fields[wireName] = AstTypeUnwrapper.unwrapFieldWireTypeString(rendered)
                    members.add(MemberSignature(name = pName, kind = "FIELD", typeOrSignature = rendered))
                }
            }
        }

        // 2. SuperTypes (`: BaseService<T>(), PortInterface`)
        val superSimple = mutableSetOf<String>()
        for (delegSpec in childrenOfType(cls, "delegation_specifier")) {
            val superTypeNode = extractSuperTypeNode(delegSpec)
            val sType = convertTypeNode(cst, superTypeNode, referencedSimple, referencedFq)
            val sName = sType.rawName.substringAfterLast('.')
            if (sName.isNotEmpty() && sName[0].isUpperCase()) {
                superSimple.add(sName)
            }
        }

        // 3. Properties, Functions, and Companion Object inside class body
        val directDecls = directBodyDeclarations(cls)
        val companionDecls = directDecls
            .filter { it.type == "companion_object" }
            .flatMap { directBodyDeclarations(it) }

        for (decl in directDecls) {
            when (decl.type) {
                "property_declaration" -> {
                    if (!hasModifierKeyword(cst, decl, "const")) {
                        extractPropertyInfo(cst, decl, fields, localMemberTypes, members, referencedSimple, referencedFq)
                    } else {
                        val varDecl = firstChildOfType(decl, "variable_declaration")
                        val typeNode = varDecl?.let { vd -> namedChildren(vd).firstOrNull { isTypeNode(it) } }
                        if (typeNode != null) {
                            convertTypeNode(cst, typeNode, referencedSimple, referencedFq)
                        }
                    }
                }
                "function_declaration" -> {
                    val m = convertFunction(cst, decl, referencedSimple, referencedFq, fields)
                    methods.add(m)
                    members.add(renderMethodMemberSignature(m))
                }
            }
        }
        for (decl in companionDecls) {
            when (decl.type) {
                "property_declaration" -> {
                    val varDecl = firstChildOfType(decl, "variable_declaration")
                    val typeNode = varDecl?.let { vd -> namedChildren(vd).firstOrNull { isTypeNode(it) } }
                    if (typeNode != null) {
                        convertTypeNode(cst, typeNode, referencedSimple, referencedFq)
                    }
                }
                "function_declaration" -> {
                    val m = convertFunction(cst, decl, referencedSimple, referencedFq, fields)
                    methods.add(m)
                }
            }
        }

        // 4. Collect all method calls, constructor calls, type references, and qualified expressions in the CST subtree
        collectExpressionsFromCstScope(
            cst = cst,
            scope = cls,
            selfSimpleName = simpleName,
            localMemberTypes = localMemberTypes,
            referencedSimple = referencedSimple,
            referencedFq = referencedFq,
            inlineRoutes = inlineRoutes,
            skipDirectNestedClasses = true
        )

        val allAnns = (classAnns + methods.flatMap { it.annotations })
        val stereotypes = allAnns.map { "@${it.name}" }.distinct()
        val nodeKind = AstTypeUnwrapper.classifyNodeKind(
            declKeyword = declKeyword,
            stereotypes = stereotypes,
            simpleName = simpleName,
            isDataOrRecord = isDataOrValue
        )

        val startLine = if (isSingleClassInFile) 1 else offsetToLine(source, cst.startChar(cls))
        val endLine = if (isSingleClassInFile) totalLines else maxOf(startLine, offsetToLine(source, cst.endChar(cls)))

        return JvmParsedClassAst(
            fqId = fqId,
            simpleName = simpleName,
            pkg = pkg,
            kind = nodeKind,
            stereotypes = stereotypes,
            annotations = classAnns,
            fields = fields,
            members = members,
            methods = methods,
            inlineRoutes = inlineRoutes,
            referencedSimpleNames = referencedSimple,
            referencedQualifiedNames = referencedFq,
            superTypeSimpleNames = superSimple,
            startLine = startLine,
            endLine = minOf(totalLines, endLine)
        )
    }

    private fun extractSuperTypeNode(delegSpec: TSNode): TSNode? {
        val ctorInv = firstChildOfType(delegSpec, "constructor_invocation")
        if (ctorInv != null) {
            return namedChildren(ctorInv).firstOrNull { isTypeNode(it) }
        }
        val explicitDeleg = firstChildOfType(delegSpec, "explicit_delegation")
        if (explicitDeleg != null) {
            return namedChildren(explicitDeleg).firstOrNull { isTypeNode(it) }
        }
        return namedChildren(delegSpec).firstOrNull { isTypeNode(it) }
    }

    private fun extractPropertyInfo(
        cst: ParsedKotlinCst,
        prop: TSNode,
        fields: MutableMap<String, String>,
        localMemberTypes: MutableMap<String, String>,
        members: MutableList<MemberSignature>,
        referencedSimple: MutableSet<String>,
        referencedFq: MutableSet<String>
    ) {
        val varDecl = firstChildOfType(prop, "variable_declaration") ?: return
        val name = firstChildOfType(varDecl, "simple_identifier")?.let { cst.slice(it).trim() } ?: return
        val typeNode = namedChildren(varDecl).firstOrNull { isTypeNode(it) } ?: return
        val typeRef = convertTypeNode(cst, typeNode, referencedSimple, referencedFq)
        localMemberTypes[name] = typeRef.rawName.substringAfterLast('.')
        val rendered = typeRef.render()
        val propAnns = extractAnnotations(cst, prop)
        val wireName = AstAnnotation.resolveSerializedFieldName(propAnns, name)
        fields.putIfAbsent(wireName, AstTypeUnwrapper.unwrapFieldWireTypeString(rendered))
        if (members.none { it.name == name }) {
            members.add(MemberSignature(name = name, kind = "FIELD", typeOrSignature = rendered))
        }
    }

    private fun convertFunction(
        cst: ParsedKotlinCst,
        fn: TSNode,
        referencedSimple: MutableSet<String>,
        referencedFq: MutableSet<String>,
        fields: Map<String, String> = emptyMap()
    ): AstMethod {
        val mAnns = extractAnnotations(cst, fn)
        val params = mutableListOf<AstParam>()

        val paramsNode = firstChildOfType(fn, "function_value_parameters")
        if (paramsNode != null) {
            // In tree-sitter-kotlin, `parameter_modifiers` is a sibling immediately preceding `parameter`
            var pendingModifiers: TSNode? = null
            for (child in namedChildren(paramsNode)) {
                when (child.type) {
                    "parameter_modifiers" -> pendingModifiers = child
                    "parameter" -> {
                        val pName = firstChildOfType(child, "simple_identifier")?.let { cst.slice(it).trim() }
                        val typeNode = namedChildren(child).firstOrNull { isTypeNode(it) }
                        if (!pName.isNullOrEmpty()) {
                            val isVararg = pendingModifiers?.let { pm ->
                                namedChildren(pm).any { it.type != "annotation" && cst.slice(it).trim() == "vararg" }
                            } ?: false
                            val pAnns = pendingModifiers?.let { extractAnnotationsFromModifiersNode(cst, it) }.orEmpty()
                            val pType = convertTypeNode(cst, typeNode, referencedSimple, referencedFq)
                                .copy(isVararg = isVararg)
                            params.add(AstParam(name = pName, type = pType, annotations = pAnns))
                        }
                        pendingModifiers = null
                    }
                }
            }
        }

        // Distinguish extension receiver type (before `function_value_parameters`) from return type (after)
        var seenParams = false
        var returnTypeNode: TSNode? = null
        for (child in namedChildren(fn)) {
            if (child.type == "function_value_parameters") {
                seenParams = true
                continue
            }
            if (!seenParams && isTypeNode(child)) {
                convertTypeNode(cst, child, referencedSimple, referencedFq)
            } else if (seenParams && isTypeNode(child) && returnTypeNode == null) {
                returnTypeNode = child
            }
        }

        val retType = returnTypeNode?.let { convertTypeNode(cst, it, referencedSimple, referencedFq) }
        val fnBody = firstChildOfType(fn, "function_body")
        val inferredRet = if (retType == null) inferExpressionReturnDto(cst, fnBody, params, fields) else null

        val vis = when {
            hasModifierKeyword(cst, fn, "private") -> "private"
            hasModifierKeyword(cst, fn, "protected") -> "protected"
            hasModifierKeyword(cst, fn, "internal") -> "internal"
            else -> "public"
        }

        val fnName = firstChildOfType(fn, "simple_identifier")?.let { cst.slice(it).trim() } ?: "anonymous"

        return AstMethod(
            name = fnName,
            parameters = params,
            returnType = retType,
            inferredReturnTypeName = inferredRet,
            annotations = mAnns,
            visibility = vis,
            isSuspend = hasModifierKeyword(cst, fn, "suspend")
        )
    }

    private fun renderMethodMemberSignature(m: AstMethod): MemberSignature {
        val paramSig = m.parameters.joinToString(", ") {
            val varargPrefix = if (it.type.isVararg) "vararg " else ""
            "$varargPrefix${it.name}: ${it.type.render().removeSuffix("...")}"
        }
        val retSig = m.returnType?.render() ?: m.inferredReturnTypeName ?: "Unit"
        val suspendPrefix = if (m.isSuspend) "suspend " else ""
        return MemberSignature(
            name = m.name,
            kind = "METHOD",
            typeOrSignature = "$suspendPrefix($paramSig): $retSig",
            visibility = m.visibility
        )
    }

    private fun collectExpressionsFromCstScope(
        cst: ParsedKotlinCst,
        scope: TSNode,
        selfSimpleName: String?,
        localMemberTypes: Map<String, String>,
        referencedSimple: MutableSet<String>,
        referencedFq: MutableSet<String>,
        inlineRoutes: MutableList<ContractEndpoint>,
        skipDirectNestedClasses: Boolean
    ) {
        fun visit(node: TSNode, isRootScope: Boolean) {
            if (node.isNull) return
            if (!isRootScope && skipDirectNestedClasses && isClassOrObjectNode(node)) {
                return
            }

            when (node.type) {
                "user_type" -> {
                    convertTypeNode(cst, node, referencedSimple, referencedFq)
                }
                "call_expression" -> {
                    handleCallExpression(cst, node, localMemberTypes, referencedSimple, referencedFq, inlineRoutes)
                }
                "navigation_expression" -> {
                    handleNavigationExpression(cst, node, referencedSimple, referencedFq)
                }
                "callable_reference" -> {
                    handleCallableReference(cst, node, referencedSimple, referencedFq)
                }
            }

            for (i in 0 until node.namedChildCount) {
                visit(node.getNamedChild(i), isRootScope = false)
            }
        }

        visit(scope, isRootScope = true)

        if (selfSimpleName != null) {
            referencedSimple.remove(selfSimpleName)
        }
    }

    private fun handleCallExpression(
        cst: ParsedKotlinCst,
        call: TSNode,
        localMemberTypes: Map<String, String>,
        referencedSimple: MutableSet<String>,
        referencedFq: MutableSet<String>,
        inlineRoutes: MutableList<ContractEndpoint>
    ) {
        val callee = callCalleeNode(call) ?: return
        when (callee.type) {
            "simple_identifier" -> {
                val calleeText = cst.slice(callee).trim()
                if (calleeText.isNotEmpty() && calleeText[0].isUpperCase()) {
                    referencedSimple.add(calleeText)
                } else if (calleeText in KTOR_VERBS) {
                    val args = callValueArguments(call)
                    if (args.isNotEmpty()) {
                        val firstExpr = splitValueArgument(cst, args[0]).second
                        val firstStr = extractStringLiteral(cst, firstExpr)
                        if (!firstStr.isNullOrEmpty() && firstStr.startsWith("/")) {
                            inlineRoutes.add(
                                ContractEndpoint(
                                    protocol = "REST",
                                    methodOrAction = calleeText.uppercase(),
                                    pathOrTopic = firstStr,
                                    role = "PRODUCER"
                                )
                            )
                        }
                    }
                }
            }
            "navigation_expression" -> {
                val (receiverNode, op, selectorName) = splitNavigation(cst, callee) ?: return
                val receiverText = cst.slice(receiverNode).replace(Regex("""\s*\.\s*"""), ".").trim()
                val normalizedReceiver = receiverText.removePrefix("this.")
                val isMessagingSend = op == "." &&
                    (selectorName == "send" || selectorName == "convertAndSend") &&
                    (normalizedReceiver == "kafkaTemplate" ||
                        normalizedReceiver == "producer" ||
                        localMemberTypes[normalizedReceiver] in KAFKA_PRODUCER_TYPES)
                if (isMessagingSend) {
                    val args = callValueArguments(call)
                    val firstExpr = args.firstOrNull()?.let { splitValueArgument(cst, it).second }
                    val topicStr = extractStringLiteral(cst, firstExpr)
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
            }
        }
    }

    private fun handleNavigationExpression(
        cst: ParsedKotlinCst,
        nav: TSNode,
        referencedSimple: MutableSet<String>,
        referencedFq: MutableSet<String>
    ) {
        val (receiverNode, op, selectorName) = splitNavigation(cst, nav) ?: return
        val receiverText = cst.slice(receiverNode).replace(Regex("""\s*\.\s*"""), ".").trim()

        if (op == "::") {
            val cleanRecv = receiverText.substringBefore('<').trim()
            if (cleanRecv.contains('.')) {
                referencedFq.add(cleanRecv)
            } else if (cleanRecv.isNotEmpty() && cleanRecv[0].isUpperCase()) {
                referencedSimple.add(cleanRecv)
            }
            if (selectorName != "class" && selectorName.isNotEmpty() && selectorName[0].isUpperCase()) {
                referencedSimple.add(selectorName)
            }
            return
        }

        if (receiverText.isNotEmpty() && receiverText[0].isUpperCase() && !receiverText.contains('.')) {
            referencedSimple.add(receiverText)
        } else if (receiverText.contains('.')) {
            if (selectorName.isNotEmpty() && selectorName[0].isUpperCase()) {
                referencedFq.add("$receiverText.$selectorName")
            }
            val lastRecvSeg = receiverText.substringAfterLast('.')
            if (lastRecvSeg.isNotEmpty() && lastRecvSeg[0].isUpperCase()) {
                referencedFq.add(receiverText)
            }
        }
    }

    private fun handleCallableReference(
        cst: ParsedKotlinCst,
        callableRef: TSNode,
        referencedSimple: MutableSet<String>,
        referencedFq: MutableSet<String>
    ) {
        var seenDoubleColon = false
        for (child in children(callableRef)) {
            if (!child.isNamed && child.type == "::") {
                seenDoubleColon = true
                continue
            }
            if (!child.isNamed) continue
            val text = cst.slice(child).substringBefore('<').trim()
            if (!seenDoubleColon) {
                if (text.contains('.')) {
                    referencedFq.add(text)
                } else if (text.isNotEmpty() && text[0].isUpperCase()) {
                    referencedSimple.add(text)
                }
            } else if (text.isNotEmpty() && text[0].isUpperCase()) {
                referencedSimple.add(text)
            }
        }
    }

    private fun splitNavigation(cst: ParsedKotlinCst, nav: TSNode): Triple<TSNode, String, String>? {
        if (nav.namedChildCount < 2) return null
        val receiver = nav.getNamedChild(0)
        val suffix = nav.getNamedChild(1)
        if (suffix.type != "navigation_suffix") return null
        var op = "."
        var selector = ""
        for (child in children(suffix)) {
            if (!child.isNamed) {
                if (child.type in setOf(".", "?.", "::")) {
                    op = child.type
                } else if (child.type == "class") {
                    selector = "class"
                }
            } else if (selector.isEmpty()) {
                selector = cst.slice(child).trim()
            }
        }
        return Triple(receiver, op, selector)
    }
}
