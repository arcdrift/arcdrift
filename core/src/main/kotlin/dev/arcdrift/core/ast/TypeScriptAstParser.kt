package dev.arcdrift.core.ast

import dev.arcdrift.core.model.ContractEndpoint
import dev.arcdrift.core.scanner.ArcDriftIgnore
import kotlinx.serialization.Serializable
import org.treesitter.TSNode
import org.treesitter.TSParser
import org.treesitter.TreeSitterTsx
import org.treesitter.TreeSitterTypescript

@Serializable
data class TsParsedFileAst(
    val importPaths: List<String>,
    val types: Map<String, Map<String, String>>,
    val contracts: List<ContractEndpoint>,
    val sdkMethods: Map<String, ContractEndpoint> = emptyMap(),
    val sdkCalls: List<Pair<String, String>> = emptyList(),
    val typeParents: Map<String, List<String>> = emptyMap()
)

/**
 * Lock-free Tree-sitter TypeScript AST extractor powered by `io.github.bonede:tree-sitter-typescript`
 * ([TSParser] + [TreeSitterTypescript] / [TreeSitterTsx]).
 */
object TypeScriptAstParser {

    private val HTTP_VERBS = setOf("get", "post", "put", "delete", "patch")
    private val HTTP_CLIENT_RECEIVERS = setOf("axios", "api", "client", "http", "httpClient", "this.http", "this.api", "this.client")
    private val OBJECT_CONFIG_CALLS = setOf("useApiQuery", "useApiMutation", "useQueryApi", "axios", "request")

    private val TS_LANGUAGE = TreeSitterTypescript()
    private val TSX_LANGUAGE = TreeSitterTsx()

    private val TS_PARSER: ThreadLocal<TSParser> = ThreadLocal.withInitial {
        TSParser().apply { setLanguage(TS_LANGUAGE) }
    }
    private val TSX_PARSER: ThreadLocal<TSParser> = ThreadLocal.withInitial {
        TSParser().apply { setLanguage(TSX_LANGUAGE) }
    }

    fun parseTypeScriptFile(source: String, isTsx: Boolean = false): TsParsedFileAst {
        val parser = if (isTsx) TSX_PARSER.get() else TS_PARSER.get()
        val tree = parser.parseString(null, source)
        val root = tree.rootNode
        val bytes = source.toByteArray(Charsets.UTF_8)

        val importPaths = mutableListOf<String>()
        val tsTypes = linkedMapOf<String, Map<String, String>>()
        val tsTypeParents = linkedMapOf<String, MutableList<String>>()
        val stringVars = linkedMapOf<String, String>()
        val contracts = mutableListOf<ContractEndpoint>()
        val sdkMethods = linkedMapOf<String, ContractEndpoint>()
        val sdkCalls = mutableListOf<Pair<String, String>>()

        // Pass 1: Walk Tree-sitter CST/AST for `import_statement`, `interface_declaration`, `type_alias_declaration`, and `variable_declarator`
        walkTree(root) { node ->
            when (node.type) {
                "import_statement", "export_statement" -> {
                    val srcNode = node.getChildByFieldName("source")
                    if (!srcNode.isNull) {
                        val raw = unquoteTsString(sliceText(bytes, srcNode))
                        if (raw.isNotEmpty()) importPaths.add(raw)
                    }
                }
                "interface_declaration" -> {
                    val nameNode = node.getChildByFieldName("name")
                    val bodyNode = node.getChildByFieldName("body")
                    if (!nameNode.isNull && !bodyNode.isNull) {
                        val typeName = sliceText(bytes, nameNode).trim()
                        val parents = mutableListOf<String>()
                        for (k in 0 until node.namedChildCount) {
                            val child = node.getNamedChild(k)
                            if (child.type == "extends_type_clause") {
                                for (m in 0 until child.namedChildCount) {
                                    val parentName = sliceText(bytes, child.getNamedChild(m)).substringBefore('<').trim()
                                    if (parentName.isNotEmpty()) {
                                        parents.add(parentName)
                                    }
                                }
                            }
                        }
                        if (typeName.isNotEmpty() && parents.isNotEmpty()) {
                            tsTypeParents.getOrPut(typeName) { mutableListOf() }.addAll(parents)
                        }
                        val ownFields = extractObjectProperties(bytes, bodyNode)
                        if (typeName.isNotEmpty() && (ownFields.isNotEmpty() || parents.isNotEmpty())) {
                            tsTypes[typeName] = ownFields
                        }
                    }
                }
                "type_alias_declaration" -> {
                    val nameNode = node.getChildByFieldName("name")
                    val valueNode = node.getChildByFieldName("value")
                    if (!nameNode.isNull && !valueNode.isNull) {
                        val typeName = sliceText(bytes, nameNode).trim()
                        if (valueNode.type == "intersection_type") {
                            val parents = mutableListOf<String>()
                            val ownFields = linkedMapOf<String, String>()
                            collectIntersectionMembers(bytes, valueNode, parents, ownFields)
                            if (typeName.isNotEmpty() && parents.isNotEmpty()) {
                                tsTypeParents.getOrPut(typeName) { mutableListOf() }.addAll(parents)
                            }
                            if (typeName.isNotEmpty() && (ownFields.isNotEmpty() || parents.isNotEmpty())) {
                                tsTypes[typeName] = ownFields
                            }
                        } else {
                            val fields = extractObjectProperties(bytes, valueNode)
                            if (typeName.isNotEmpty() && fields.isNotEmpty()) {
                                tsTypes[typeName] = fields
                            }
                        }
                    }
                }
                "variable_declarator", "public_field_definition" -> {
                    val nameNode = node.getChildByFieldName("name")
                    val valueNode = node.getChildByFieldName("value")
                    if (!nameNode.isNull && !valueNode.isNull &&
                        (nameNode.type == "identifier" || nameNode.type == "property_identifier")
                    ) {
                        val varName = sliceText(bytes, nameNode).trim()
                        val extractedUrl = extractUrlExpression(bytes, valueNode, stringVars)
                        if (varName.isNotEmpty() && !extractedUrl.isNullOrEmpty()) {
                            stringVars[varName] = extractedUrl
                            if (node.type == "public_field_definition") {
                                stringVars["this.$varName"] = extractedUrl
                            }
                        }
                    }
                }
            }
        }

        // Propagate intra-file TypeScript `extends` inheritance regardless of declaration order
        repeat(4) {
            var changed = false
            for ((childName, parents) in tsTypeParents) {
                val inherited = linkedMapOf<String, String>()
                for (p in parents) {
                    tsTypes[p]?.let { inherited.putAll(it) }
                }
                if (inherited.isNotEmpty()) {
                    val existing = tsTypes[childName].orEmpty()
                    val merged = linkedMapOf<String, String>().apply {
                        putAll(inherited)
                        putAll(existing)
                    }
                    if (merged != existing) {
                        tsTypes[childName] = merged
                        changed = true
                    }
                }
            }
            if (!changed) return@repeat
        }

        // Pass 2: Walk Tree-sitter CST/AST for `call_expression` (`fetch` / `axios` / `useApiQuery` / SDK methods)
        walkTree(root) { node ->
            if (node.type == "call_expression") {
                val rawFnNode = node.getChildByFieldName("function")
                val argsNode = node.getChildByFieldName("arguments")
                if (!rawFnNode.isNull && !argsNode.isNull) {
                    var fnNode = rawFnNode
                    while (!fnNode.isNull && (fnNode.type == "await_expression" || fnNode.type == "parenthesized_expression" || fnNode.type == "non_null_expression")) {
                        val nextChild = if (fnNode.namedChildCount > 0) fnNode.getNamedChild(0) else break
                        fnNode = nextChild
                    }
                    val isMemberExpr = fnNode.type == "member_expression"
                    val receiverPart = if (isMemberExpr) {
                        val objNode = fnNode.getChildByFieldName("object")
                        if (!objNode.isNull) sliceText(bytes, objNode).replace(Regex("""\s*\.\s*"""), ".").trim() else ""
                    } else ""
                    val rawMethodName = if (isMemberExpr) {
                        val propNode = fnNode.getChildByFieldName("property")
                        if (!propNode.isNull) sliceText(bytes, propNode).trim() else ""
                    } else ""
                    val fnText = if (isMemberExpr && receiverPart.isNotEmpty() && rawMethodName.isNotEmpty()) {
                        "$receiverPart.$rawMethodName"
                    } else {
                        sliceText(bytes, fnNode).replace(Regex("""^await\s+"""), "").trim()
                    }
                    val isFetch = fnText == "fetch"
                    val methodPart = rawMethodName.lowercase()
                    val isClientCall = fnText == "axios" ||
                        (methodPart in HTTP_VERBS &&
                            (receiverPart in HTTP_CLIENT_RECEIVERS ||
                                receiverPart.endsWith("client)") ||
                                receiverPart.endsWith("Client")))
                    val isReactQueryHook = fnText in setOf("useApiQuery", "useApiMutation", "useQueryApi") ||
                        fnText.endsWith("ApiQuery") ||
                        fnText.endsWith("ApiMutation")
                    val isObjectConfigCall = isReactQueryHook ||
                        fnText in OBJECT_CONFIG_CALLS ||
                        (methodPart == "request" && (receiverPart in HTTP_CLIENT_RECEIVERS || receiverPart.endsWith("Client")))
                    if (isFetch || isClientCall || isObjectConfigCall) {
                        var verb = when {
                            methodPart in HTTP_VERBS && fnText.contains('.') -> methodPart.uppercase()
                            fnText.endsWith("Mutation") -> "POST"
                            else -> "GET"
                        }

                        // Check explicit type arguments `<DtoName>` on `call_expression`
                        var explicitGenericDto: String? = null
                        val typeArgsNode = node.getChildByFieldName("type_arguments")
                        if (!typeArgsNode.isNull) {
                            val firstTypeArgText = if (typeArgsNode.namedChildCount > 0) {
                                sliceText(bytes, typeArgsNode.getNamedChild(0)).trim()
                            } else {
                                sliceText(bytes, typeArgsNode).removePrefix("<").removeSuffix(">").trim()
                            }
                            explicitGenericDto = unwrapTsDtoTypeName(firstTypeArgText).takeIf {
                                it.isNotEmpty() && !it.equals("any", ignoreCase = true) &&
                                    !it.equals("unknown", ignoreCase = true) && !it.equals("void", ignoreCase = true)
                            }
                        }

                        // Extract URL (preferring explicit `{ url: '...' }` option over non-URL cache keys like `useApiQuery('orders-key', { url: '/v1/orders' })`)
                        var url: String? = null
                        for (i in 0 until argsNode.namedChildCount) {
                            val arg = argsNode.getNamedChild(i)
                            if (arg.type == "object") {
                                val urlOpt = extractPropertyUrlFromOptionsObject(bytes, arg, "url", stringVars)
                                if (!urlOpt.isNullOrEmpty()) {
                                    url = urlOpt
                                }
                                val methodOpt = extractMethodFromOptionsObject(bytes, arg)
                                if (methodOpt != null) {
                                    verb = methodOpt.uppercase()
                                }
                            } else if (url == null) {
                                val candidateUrl = extractUrlExpression(bytes, arg, stringVars)
                                if (!candidateUrl.isNullOrEmpty()) {
                                    val looksLikeUrlOrPath = !isReactQueryHook ||
                                        candidateUrl.startsWith("/") ||
                                        candidateUrl.startsWith("http://") ||
                                        candidateUrl.startsWith("https://") ||
                                        candidateUrl.startsWith("\${")
                                    if (looksLikeUrlOrPath) {
                                        url = candidateUrl
                                    }
                                }
                            }
                        }

                        if (url != null) {
                            val enclosingPromiseDto = findEnclosingFunctionPromiseType(bytes, node)
                            val candidateDtoTypes = tsTypes.filterKeys { name ->
                                !name.endsWith("Props", ignoreCase = true) &&
                                    !name.endsWith("State", ignoreCase = true) &&
                                    !name.endsWith("Params", ignoreCase = true) &&
                                    (name.endsWith("Dto", ignoreCase = true) ||
                                        name.endsWith("Model", ignoreCase = true) ||
                                        name.endsWith("Response", ignoreCase = true) ||
                                        name.endsWith("Record", ignoreCase = true) ||
                                        (name.length >= 3 && name[0] == 'I' && name[1].isUpperCase()))
                            }
                            val resolvedDto = when {
                                explicitGenericDto != null && explicitGenericDto in tsTypes -> explicitGenericDto
                                enclosingPromiseDto != null && enclosingPromiseDto in tsTypes -> enclosingPromiseDto
                                candidateDtoTypes.size == 1 -> candidateDtoTypes.keys.first()
                                else -> explicitGenericDto ?: enclosingPromiseDto
                            }
                            val dtoFields = resolvedDto?.let { tsTypes[it] }.orEmpty()
                            val (resolvedReqDto, inlineReqFields) = if (!isReactQueryHook && verb in setOf("POST", "PUT", "PATCH")) {
                                findEnclosingFunctionRequestPayload(bytes, node)
                            } else {
                                null to emptyMap()
                            }
                            val reqDtoFields = when {
                                inlineReqFields.isNotEmpty() -> inlineReqFields
                                resolvedReqDto != null -> tsTypes[resolvedReqDto].orEmpty()
                                else -> emptyMap()
                            }
                            val endpoint = ContractEndpoint(
                                protocol = "REST",
                                methodOrAction = verb,
                                pathOrTopic = normalizeClientUrl(url),
                                role = "CONSUMER",
                                requestDto = resolvedReqDto,
                                responseDto = resolvedDto,
                                dtoFields = dtoFields,
                                requestDtoFields = reqDtoFields,
                                targetHost = ArcDriftIgnore.extractHost(url)
                            )
                            val enclosingSdk = if (receiverPart.endsWith("client)")) {
                                findEnclosingSdkFunctionOrMethod(bytes, node)
                            } else {
                                findEnclosingClassAndMethod(bytes, node)
                            }
                            if (enclosingSdk != null && receiverPart != "this.http") {
                                val (clsName, mName) = enclosingSdk
                                if (!clsName.isNullOrEmpty()) {
                                    sdkMethods["$clsName.$mName"] = endpoint
                                }
                                sdkMethods[mName] = endpoint
                            } else {
                                contracts.add(endpoint)
                            }
                        }
                    } else if (fnText.contains('.') && methodPart !in HTTP_VERBS && rawMethodName.isNotEmpty()) {
                        val cleanReceiver = receiverPart.substringAfterLast('.').trim()
                        if (cleanReceiver.endsWith("Service") ||
                            cleanReceiver.endsWith("Api") ||
                            cleanReceiver.endsWith("ApiClient") ||
                            cleanReceiver.endsWith("Client") ||
                            cleanReceiver == "api"
                        ) {
                            sdkCalls.add(cleanReceiver to rawMethodName)
                        }
                    } else if (!fnText.contains('.') && fnNode.type == "identifier" && fnText.length >= 6 &&
                        (fnText.startsWith("komga") || fnText.startsWith("api") || fnText.endsWith("Api"))
                    ) {
                        sdkCalls.add("" to fnText)
                    }
                }
            }
        }

        return TsParsedFileAst(
            importPaths = importPaths,
            types = tsTypes,
            contracts = contracts,
            sdkMethods = sdkMethods,
            sdkCalls = sdkCalls,
            typeParents = tsTypeParents
        )
    }

    private val TEMPLATE_INTERP_REGEX = Regex("""\$\{((?:this\.)?[a-zA-Z_][a-zA-Z0-9_]*)}""")

    private fun extractUrlExpression(bytes: ByteArray, exprNode: TSNode, stringVars: Map<String, String>): String? {
        if (exprNode.isNull) return null
        return when (exprNode.type) {
            "string", "template_string" -> {
                val raw = unquoteTsString(sliceText(bytes, exprNode))
                TEMPLATE_INTERP_REGEX.replace(raw) { match ->
                    val varName = match.groupValues[1]
                    stringVars[varName] ?: match.value
                }
            }
            "identifier", "member_expression" -> stringVars[sliceText(bytes, exprNode).trim()]
            "parenthesized_expression" -> {
                if (exprNode.namedChildCount > 0) {
                    extractUrlExpression(bytes, exprNode.getNamedChild(0), stringVars)
                } else null
            }
            "call_expression" -> {
                val fnNode = exprNode.getChildByFieldName("function")
                val argsNode = exprNode.getChildByFieldName("arguments")
                if (!fnNode.isNull && fnNode.type == "member_expression") {
                    val propNode = fnNode.getChildByFieldName("property")
                    val propName = if (!propNode.isNull) sliceText(bytes, propNode).trim() else ""
                    if (propName == "getEndpointFor" && !argsNode.isNull && argsNode.namedChildCount > 0) {
                        extractUrlExpression(bytes, argsNode.getNamedChild(0), stringVars)
                    } else {
                        val objNode = fnNode.getChildByFieldName("object")
                        extractUrlExpression(bytes, objNode, stringVars)
                    }
                } else if (!fnNode.isNull && fnNode.type == "identifier" && !argsNode.isNull && argsNode.namedChildCount > 0) {
                    extractUrlExpression(bytes, argsNode.getNamedChild(0), stringVars)
                } else {
                    null
                }
            }
            "binary_expression" -> {
                val leftNode = exprNode.getChildByFieldName("left")
                val rightNode = exprNode.getChildByFieldName("right")
                val leftStr = extractUrlExpression(bytes, leftNode, stringVars)
                val rightStr = extractUrlExpression(bytes, rightNode, stringVars)
                when {
                    leftStr != null && rightStr != null -> leftStr + rightStr
                    leftStr != null -> if (leftStr.endsWith("/")) "${leftStr}{param}" else leftStr
                    rightStr != null && !leftNode.isNull && sliceText(bytes, leftNode).contains("URL", ignoreCase = true) ->
                        if (rightStr.startsWith("/")) rightStr else "/api/${rightStr.trimStart('/')}"
                    else -> null
                }
            }
            else -> null
        }
    }

    private fun unwrapTsDtoTypeName(raw: String): String {
        var s = raw.trim()
        if (s.startsWith("Array<") && s.endsWith(">")) {
            s = s.removePrefix("Array<").removeSuffix(">").trim()
        }
        while (s.endsWith("[]")) {
            s = s.removeSuffix("[]").trim()
        }
        return s
    }

    private fun collectIntersectionMembers(
        bytes: ByteArray,
        node: TSNode,
        parents: MutableList<String>,
        ownFields: MutableMap<String, String>
    ) {
        for (i in 0 until node.namedChildCount) {
            val child = node.getNamedChild(i)
            when (child.type) {
                "intersection_type", "parenthesized_type" -> collectIntersectionMembers(bytes, child, parents, ownFields)
                "object_type" -> ownFields.putAll(extractObjectProperties(bytes, child))
                "type_identifier", "generic_type" -> {
                    val pName = sliceText(bytes, child).substringBefore('<').trim()
                    if (pName.isNotEmpty()) parents.add(pName)
                }
            }
        }
    }

    private fun extractObjectProperties(bytes: ByteArray, bodyNode: TSNode): Map<String, String> {
        val fields = linkedMapOf<String, String>()
        for (i in 0 until bodyNode.namedChildCount) {
            val child = bodyNode.getNamedChild(i)
            if (child.type == "property_signature") {
                val propNameNode = child.getChildByFieldName("name")
                val propTypeNode = child.getChildByFieldName("type")
                if (!propNameNode.isNull && !propTypeNode.isNull) {
                    val propName = unquoteTsString(sliceText(bytes, propNameNode)).trim()
                    val propType = sliceText(bytes, propTypeNode).removePrefix(":").trim()
                    if (propName.isNotEmpty() && propType.isNotEmpty()) {
                        fields[propName] = propType
                    }
                }
            }
        }
        return fields
    }

    private fun extractPropertyUrlFromOptionsObject(
        bytes: ByteArray,
        objNode: TSNode,
        propKey: String,
        stringVars: Map<String, String>
    ): String? {
        for (i in 0 until objNode.namedChildCount) {
            val pair = objNode.getNamedChild(i)
            if (pair.type == "pair") {
                val keyNode = pair.getChildByFieldName("key")
                val valNode = pair.getChildByFieldName("value")
                if (!keyNode.isNull && !valNode.isNull) {
                    val key = unquoteTsString(sliceText(bytes, keyNode)).trim()
                    if (key == propKey) {
                        return extractUrlExpression(bytes, valNode, stringVars)
                    }
                }
            }
        }
        return null
    }

    private fun extractMethodFromOptionsObject(bytes: ByteArray, objNode: TSNode): String? {
        for (i in 0 until objNode.namedChildCount) {
            val pair = objNode.getNamedChild(i)
            if (pair.type == "pair") {
                val keyNode = pair.getChildByFieldName("key")
                val valNode = pair.getChildByFieldName("value")
                if (!keyNode.isNull && !valNode.isNull) {
                    val key = unquoteTsString(sliceText(bytes, keyNode)).trim()
                    if (key == "method") {
                        return unquoteTsString(sliceText(bytes, valNode)).trim()
                    }
                }
            }
        }
        return null
    }

    private fun findEnclosingSdkFunctionOrMethod(bytes: ByteArray, startNode: TSNode): Pair<String?, String>? {
        val classAndMethod = findEnclosingClassAndMethod(bytes, startNode)
        if (classAndMethod != null) return classAndMethod
        var curr = startNode.parent
        while (!curr.isNull) {
            if (curr.type == "variable_declarator") {
                val nameNode = curr.getChildByFieldName("name")
                if (!nameNode.isNull && nameNode.type == "identifier") {
                    val fnName = sliceText(bytes, nameNode).trim()
                    if (fnName.isNotEmpty()) return null to fnName
                }
            }
            curr = curr.parent
        }
        return null
    }

    private fun findEnclosingClassAndMethod(bytes: ByteArray, startNode: TSNode): Pair<String?, String>? {
        var methodName: String? = null
        var className: String? = null
        var curr = startNode.parent
        while (!curr.isNull) {
            if (methodName == null && curr.type == "method_definition") {
                val nNode = curr.getChildByFieldName("name")
                if (!nNode.isNull) {
                    methodName = sliceText(bytes, nNode).trim()
                }
            } else if (className == null && curr.type == "class_declaration") {
                val nNode = curr.getChildByFieldName("name")
                if (!nNode.isNull) {
                    className = sliceText(bytes, nNode).trim()
                }
            }
            curr = curr.parent
        }
        return if (!methodName.isNullOrEmpty() && !className.isNullOrEmpty() &&
            (className.endsWith("Service") || className.endsWith("Api") || className.endsWith("Client"))
        ) {
            className to methodName
        } else {
            null
        }
    }

    private fun findEnclosingFunctionPromiseType(bytes: ByteArray, startNode: TSNode): String? {
        var curr = startNode.parent
        while (!curr.isNull) {
            if (curr.type in setOf("function_declaration", "method_definition", "arrow_function", "function_expression")) {
                val retNode = curr.getChildByFieldName("return_type")
                if (!retNode.isNull) {
                    val retText = sliceText(bytes, retNode).removePrefix(":").trim()
                    if (retText.startsWith("Promise<") && retText.endsWith(">")) {
                        return unwrapTsDtoTypeName(retText.removePrefix("Promise<").removeSuffix(">").trim())
                    }
                    if (retText.startsWith("Observable<") && retText.endsWith(">")) {
                        val inner = retText.removePrefix("Observable<").removeSuffix(">").trim()
                        val unwrappedResp = if (inner.startsWith("HttpResponse<") && inner.endsWith(">")) {
                            inner.removePrefix("HttpResponse<").removeSuffix(">").trim()
                        } else inner
                        return unwrapTsDtoTypeName(unwrappedResp).takeIf {
                            it !in setOf("any", "unknown", "void", "undefined", "number", "string", "boolean")
                        }
                    }
                }
            }
            curr = curr.parent
        }
        return null
    }

    private val IGNORED_TS_REQUEST_PARAM_TYPES = setOf(
        "string", "number", "boolean", "any", "unknown", "void", "never", "object",
        "IQueryParams", "AbortSignal", "RequestInit", "AxiosRequestConfig"
    )
    private val HTTP_CLIENT_OPTION_KEYS = setOf(
        "headers", "params", "responseType", "signal", "withCredentials", "observe", "reportProgress", "context",
        "method", "body", "credentials", "mode", "cache", "redirect", "referrer", "referrerPolicy", "integrity", "keepalive"
    )

    private fun extractObjectLiteralKeys(bytes: ByteArray, objNode: TSNode): Map<String, String> {
        val fields = linkedMapOf<String, String>()
        for (i in 0 until objNode.namedChildCount) {
            val child = objNode.getNamedChild(i)
            when (child.type) {
                "pair" -> {
                    val kNode = child.getChildByFieldName("key")
                    if (!kNode.isNull) {
                        val k = unquoteTsString(sliceText(bytes, kNode)).trim()
                        if (k.isNotEmpty()) fields[k] = "any"
                    }
                }
                "shorthand_property_identifier" -> {
                    val k = sliceText(bytes, child).trim()
                    if (k.isNotEmpty()) fields[k] = "any"
                }
            }
        }
        return fields
    }

    private fun findEnclosingFunctionRequestPayload(
        bytes: ByteArray,
        startNode: TSNode
    ): Pair<String?, Map<String, String>> {
        val callArgsNode = startNode.getChildByFieldName("arguments")
        val hasSecondCallArg = !callArgsNode.isNull && callArgsNode.namedChildCount >= 2
        val firstCallArg = if (hasSecondCallArg) callArgsNode.getNamedChild(0) else null
        val secondCallArg = if (hasSecondCallArg) callArgsNode.getNamedChild(1) else null
        val firstArgIdentifiers = if (firstCallArg != null && !firstCallArg.isNull) collectIdentifierNames(bytes, firstCallArg) else emptySet()
        val secondArgIdentifiers = if (secondCallArg != null && !secondCallArg.isNull) collectIdentifierNames(bytes, secondCallArg) else emptySet()

        if (secondCallArg != null && !secondCallArg.isNull && secondCallArg.type == "object") {
            val literalKeys = extractObjectLiteralKeys(bytes, secondCallArg)
            if (literalKeys.isNotEmpty() && literalKeys.keys.none { it in HTTP_CLIENT_OPTION_KEYS }) {
                return "InlineRequestPayload" to literalKeys
            }
        }

        var curr = startNode.parent
        while (!curr.isNull) {
            if (curr.type in setOf("function_declaration", "method_definition", "arrow_function", "function_expression")) {
                val paramsNode = curr.getChildByFieldName("parameters")
                if (!paramsNode.isNull) {
                    for (i in 0 until paramsNode.namedChildCount) {
                        val param = paramsNode.getNamedChild(i)
                        if (param.type in setOf("required_parameter", "optional_parameter")) {
                            val patternNode = param.getChildByFieldName("pattern")
                            val paramName = if (!patternNode.isNull) sliceText(bytes, patternNode).trim() else ""
                            if (hasSecondCallArg && paramName.isNotEmpty()) {
                                val usedOnlyInUrl = paramName in firstArgIdentifiers && paramName !in secondArgIdentifiers
                                if (usedOnlyInUrl) continue
                            }
                            val typeAnn = param.getChildByFieldName("type")
                            if (!typeAnn.isNull) {
                                for (k in 0 until typeAnn.namedChildCount) {
                                    val typeChild = typeAnn.getNamedChild(k)
                                    if (typeChild.type == "object_type") {
                                        val inlineProps = extractObjectProperties(bytes, typeChild)
                                        if (inlineProps.isNotEmpty()) {
                                            return "InlineRequestPayload" to inlineProps
                                        }
                                    }
                                }
                                val rawType = sliceText(bytes, typeAnn).removePrefix(":").trim()
                                if (rawType.startsWith("Options<") && rawType.endsWith(">")) {
                                    val dataArg = rawType.removePrefix("Options<")
                                        .removeSuffix(">")
                                        .substringBefore(',')
                                        .trim()
                                    if (dataArg.isNotEmpty() && dataArg !in IGNORED_TS_REQUEST_PARAM_TYPES) {
                                        return dataArg to emptyMap()
                                    }
                                }
                                val unwrapped = unwrapTsDtoTypeName(rawType).substringBefore('<').trim()
                                if (unwrapped.isNotEmpty() &&
                                    unwrapped !in IGNORED_TS_REQUEST_PARAM_TYPES &&
                                    !unwrapped.endsWith("Props", ignoreCase = true) &&
                                    !unwrapped.endsWith("State", ignoreCase = true) &&
                                    !unwrapped.endsWith("Params", ignoreCase = true) &&
                                    unwrapped[0].isUpperCase()
                                ) {
                                    return unwrapped to emptyMap()
                                }
                            }
                        }
                    }
                }
                break
            }
            curr = curr.parent
        }
        return null to emptyMap()
    }

    private fun collectIdentifierNames(bytes: ByteArray, rootNode: TSNode): Set<String> {
        val names = mutableSetOf<String>()
        walkTree(rootNode) { n ->
            if (n.type == "identifier" || n.type == "shorthand_property_identifier") {
                val name = sliceText(bytes, n).trim()
                if (name.isNotEmpty()) names.add(name)
            }
        }
        return names
    }

    internal fun walkTree(node: TSNode, visitor: (TSNode) -> Unit) {
        if (node.isNull) return
        val stack = ArrayDeque<TSNode>()
        stack.addLast(node)
        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            if (current.isNull) continue
            visitor(current)
            val count = current.namedChildCount
            for (i in count - 1 downTo 0) {
                val child = current.getNamedChild(i)
                if (!child.isNull) {
                    stack.addLast(child)
                }
            }
        }
    }

    internal fun sliceText(bytes: ByteArray, node: TSNode): String {
        val start = node.startByte.coerceIn(0, bytes.size)
        val end = node.endByte.coerceIn(start, bytes.size)
        return bytes.decodeToString(start, end)
    }

    internal fun unquoteTsString(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.length >= 2) {
            val first = trimmed.first()
            val last = trimmed.last()
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'') || (first == '`' && last == '`')) {
                return trimmed.substring(1, trimmed.length - 1)
            }
        }
        return trimmed
    }

    internal fun normalizeClientUrl(url: String): String {
        return if (url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)) {
            "/" + url.substringAfter("://").substringAfter("/", "")
        } else {
            url
        }
    }
}
