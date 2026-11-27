package dev.arcdrift.core.ast

import dev.arcdrift.core.model.ContractEndpoint
import dev.arcdrift.core.scanner.ArcDriftIgnore
import kotlinx.serialization.Serializable
import org.treesitter.TSNode
import org.treesitter.TSParser
import org.treesitter.TreeSitterPython

@Serializable
data class PyParsedFileAst(
    val importModules: List<String> = emptyList(),
    val models: Map<String, Map<String, String>>,
    val contracts: List<ContractEndpoint>,
    val apiPrefixConstants: Map<String, String> = emptyMap(),
    val modelParents: Map<String, List<String>> = emptyMap()
)

/**
 * Lock-free Tree-sitter Python AST extractor powered by `io.github.bonede:tree-sitter-python`
 * ([TSParser] + [TreeSitterPython]).
 */
object PythonAstParser {

    private val HTTP_CLIENTS = setOf("requests", "httpx", "client", "session")
    private val HTTP_VERBS = setOf("get", "post", "put", "delete", "patch")
    private val FASTAPI_ROUTERS = setOf("app", "router")

    private val PY_LANGUAGE = TreeSitterPython()
    private val PY_PARSER: ThreadLocal<TSParser> = ThreadLocal.withInitial {
        TSParser().apply { setLanguage(PY_LANGUAGE) }
    }

    fun parsePythonFile(source: String): PyParsedFileAst {
        val parser = PY_PARSER.get()
        val tree = parser.parseString(null, source)
        val root = tree.rootNode
        val bytes = source.toByteArray(Charsets.UTF_8)

        val importModules = mutableListOf<String>()
        val models = linkedMapOf<String, Map<String, String>>()
        val modelParents = linkedMapOf<String, MutableList<String>>()
        val stringVars = linkedMapOf<String, String>()
        val routerPrefixes = linkedMapOf<String, String>()
        val apiPrefixConstants = linkedMapOf<String, String>()
        val contracts = mutableListOf<ContractEndpoint>()

        // Pass 1: Walk Tree-sitter Python AST for imports, `class_definition`, and string/router assignments
        TypeScriptAstParser.walkTree(root) { node ->
            when (node.type) {
                "import_statement" -> {
                    for (k in 0 until node.namedChildCount) {
                        val child = node.getNamedChild(k)
                        val modNode = if (child.type == "aliased_import") {
                            child.getChildByFieldName("name").takeIf { !it.isNull }
                                ?: (if (child.namedChildCount > 0) child.getNamedChild(0) else child)
                        } else {
                            child
                        }
                        val mod = TypeScriptAstParser.sliceText(bytes, modNode).split(Regex("""\s+as\s+""")).first().trim()
                        if (mod.isNotEmpty()) importModules.add(mod)
                    }
                }
                "import_from_statement" -> {
                    val modNode = node.getChildByFieldName("module_name")
                    if (!modNode.isNull) {
                        val mod = TypeScriptAstParser.sliceText(bytes, modNode).trim()
                        if (mod.isNotEmpty() && mod.all { it == '.' }) {
                            // `from . import schemas` or `from .. import models as m`
                            for (k in 0 until node.namedChildCount) {
                                val child = node.getNamedChild(k)
                                if (child == modNode || child.type == "relative_import" || child.type == "import_prefix") continue
                                val targetNode = if (child.type == "aliased_import") {
                                    child.getChildByFieldName("name").takeIf { !it.isNull }
                                        ?: (if (child.namedChildCount > 0) child.getNamedChild(0) else child)
                                } else {
                                    child
                                }
                                val importedName = TypeScriptAstParser.sliceText(bytes, targetNode)
                                    .split(Regex("""\s+as\s+""")).first().trim()
                                if (importedName.isNotEmpty() && importedName != "*") {
                                    importModules.add("$mod$importedName")
                                }
                            }
                        } else if (mod.isNotEmpty()) {
                            importModules.add(mod)
                        }
                    }
                }
                "class_definition" -> {
                    val nameNode = node.getChildByFieldName("name")
                    val bodyNode = node.getChildByFieldName("body")
                    val superNode = node.getChildByFieldName("superclasses")
                    if (!nameNode.isNull && !bodyNode.isNull) {
                        val className = TypeScriptAstParser.sliceText(bytes, nameNode).trim()
                        val parents = mutableListOf<String>()
                        if (!superNode.isNull) {
                            for (k in 0 until superNode.namedChildCount) {
                                val supArg = superNode.getNamedChild(k)
                                if (supArg.type == "identifier") {
                                    val supName = TypeScriptAstParser.sliceText(bytes, supArg).trim()
                                    if (supName.isNotEmpty() && supName != "BaseModel") {
                                        parents.add(supName)
                                    }
                                }
                            }
                        }
                        if (className.isNotEmpty() && parents.isNotEmpty()) {
                            modelParents.getOrPut(className) { mutableListOf() }.addAll(parents)
                        }
                        val ownFields = extractPythonClassFields(bytes, bodyNode)
                        if (className.isNotEmpty() && (ownFields.isNotEmpty() || parents.isNotEmpty())) {
                            models[className] = ownFields
                        }
                    }
                }
                "assignment" -> {
                    val leftNode = node.getChildByFieldName("left")
                    val rightNode = node.getChildByFieldName("right")
                    if (!leftNode.isNull && !rightNode.isNull && leftNode.type == "identifier") {
                        val varName = TypeScriptAstParser.sliceText(bytes, leftNode).trim()
                        val urlVal = extractPyUrlExpression(bytes, rightNode, stringVars)
                        if (varName.isNotEmpty() && !urlVal.isNullOrEmpty()) {
                            stringVars[varName] = urlVal
                            if ((varName.contains("API") || varName.contains("PREFIX")) && urlVal.startsWith("/")) {
                                apiPrefixConstants[varName] = urlVal
                            }
                        } else if (varName.isNotEmpty() && rightNode.type == "call") {
                            val fnNode = rightNode.getChildByFieldName("function")
                            val argsNode = rightNode.getChildByFieldName("arguments")
                            if (!fnNode.isNull && !argsNode.isNull) {
                                val fnText = TypeScriptAstParser.sliceText(bytes, fnNode).trim()
                                if (fnText.endsWith("APIRouter") || fnText.endsWith("FastAPI")) {
                                    for (j in 0 until argsNode.namedChildCount) {
                                        val arg = argsNode.getNamedChild(j)
                                        if (arg.type == "keyword_argument") {
                                            val kNode = arg.getChildByFieldName("name")
                                            val vNode = arg.getChildByFieldName("value")
                                            if (!kNode.isNull && !vNode.isNull &&
                                                TypeScriptAstParser.sliceText(bytes, kNode).trim() == "prefix"
                                            ) {
                                                val prefixVal = extractPyUrlExpression(bytes, vNode, stringVars)
                                                if (!prefixVal.isNullOrEmpty()) {
                                                    routerPrefixes[varName] = prefixVal
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Propagate intra-file Python superclass field inheritance regardless of declaration order
        repeat(4) {
            var changed = false
            for ((childName, parents) in modelParents) {
                val inherited = linkedMapOf<String, String>()
                for (p in parents) {
                    models[p]?.let { inherited.putAll(it) }
                }
                if (inherited.isNotEmpty()) {
                    val existing = models[childName].orEmpty()
                    val merged = linkedMapOf<String, String>().apply {
                        putAll(inherited)
                        putAll(existing)
                    }
                    if (merged != existing) {
                        models[childName] = merged
                        changed = true
                    }
                }
            }
            if (!changed) return@repeat
        }

        val nonEmptyModels = models.filterValues { it.isNotEmpty() }
        val defaultModelName = nonEmptyModels.keys.singleOrNull()
        val defaultModelFields = defaultModelName?.let { nonEmptyModels[it] }.orEmpty()

        // Pass 2: Walk Tree-sitter Python AST for `decorator` (FastAPI routes) and `call` (requests/httpx client calls)
        TypeScriptAstParser.walkTree(root) { node ->
            when (node.type) {
                "decorator" -> {
                    for (i in 0 until node.namedChildCount) {
                        val child = node.getNamedChild(i)
                        if (child.type == "call") {
                            val fnNode = child.getChildByFieldName("function")
                            val argsNode = child.getChildByFieldName("arguments")
                            if (!fnNode.isNull && !argsNode.isNull) {
                                val (routerPrefix, verbPart) = extractPyAttributeReceiverAndName(bytes, fnNode)
                                val isKnownRouter = routerPrefix in FASTAPI_ROUTERS ||
                                    routerPrefix in routerPrefixes ||
                                    routerPrefix.endsWith("router", ignoreCase = true) ||
                                    routerPrefix.endsWith("_app", ignoreCase = true)
                                if (isKnownRouter && verbPart in HTTP_VERBS) {
                                    var path: String? = null
                                    var responseModel: String? = null
                                    for (j in 0 until argsNode.namedChildCount) {
                                        val arg = argsNode.getNamedChild(j)
                                        when (arg.type) {
                                            "string" -> if (path == null) path = extractPyUrlExpression(bytes, arg, stringVars)
                                            "keyword_argument" -> {
                                                val kNode = arg.getChildByFieldName("name")
                                                val vNode = arg.getChildByFieldName("value")
                                                if (!kNode.isNull && !vNode.isNull) {
                                                    val k = TypeScriptAstParser.sliceText(bytes, kNode).trim()
                                                    if (k == "response_model") {
                                                        responseModel = unwrapPythonModelName(TypeScriptAstParser.sliceText(bytes, vNode).trim())
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    if (path != null) {
                                        val routerBase = routerPrefixes[routerPrefix].orEmpty()
                                        val fullPath = if (routerBase.isNotEmpty()) {
                                            ("/$routerBase/$path").replace(Regex("/+"), "/")
                                                .let { if (it.length > 1) it.removeSuffix("/") else it }
                                        } else {
                                            path
                                        }
                                        val fnReturnModel = extractDecoratedFunctionReturnModel(bytes, node)
                                        val fnRequestModel = if (verbPart in setOf("post", "put", "patch")) {
                                            extractDecoratedFunctionRequestModel(bytes, node, models, fullPath)
                                        } else null
                                        val modelToUse = responseModel ?: fnReturnModel ?: defaultModelName
                                        contracts.add(
                                            ContractEndpoint(
                                                protocol = "REST",
                                                methodOrAction = verbPart.uppercase(),
                                                pathOrTopic = fullPath,
                                                role = "PRODUCER",
                                                requestDto = fnRequestModel,
                                                responseDto = modelToUse,
                                                dtoFields = modelToUse?.let { models[it] }.orEmpty(),
                                                requestDtoFields = fnRequestModel?.let { models[it] }.orEmpty()
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                "call" -> {
                    val fnNode = node.getChildByFieldName("function")
                    val argsNode = node.getChildByFieldName("arguments")
                    if (!fnNode.isNull && !argsNode.isNull) {
                        val (clientPrefix, verbPart) = extractPyAttributeReceiverAndName(bytes, fnNode)
                        if (clientPrefix in HTTP_CLIENTS && verbPart in HTTP_VERBS) {
                            var url: String? = null
                            for (j in 0 until argsNode.namedChildCount) {
                                val arg = argsNode.getNamedChild(j)
                                val extracted = extractPyUrlExpression(bytes, arg, stringVars)
                                if (!extracted.isNullOrEmpty()) {
                                    url = extracted
                                    break
                                }
                            }
                            if (url != null) {
                                contracts.add(
                                    ContractEndpoint(
                                        protocol = "REST",
                                        methodOrAction = verbPart.uppercase(),
                                        pathOrTopic = TypeScriptAstParser.normalizeClientUrl(url),
                                        role = "CONSUMER",
                                        responseDto = defaultModelName,
                                        dtoFields = defaultModelFields,
                                        targetHost = ArcDriftIgnore.extractHost(url)
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }

        return PyParsedFileAst(
            importModules = importModules,
            models = models,
            contracts = contracts,
            apiPrefixConstants = apiPrefixConstants,
            modelParents = modelParents
        )
    }

    private fun extractPyAttributeReceiverAndName(bytes: ByteArray, fnNode: TSNode): Pair<String, String> {
        if (fnNode.type == "attribute") {
            val objNode = fnNode.getChildByFieldName("object")
            val attrNode = fnNode.getChildByFieldName("attribute")
            val objText = if (!objNode.isNull) TypeScriptAstParser.sliceText(bytes, objNode).trim() else ""
            val attrText = if (!attrNode.isNull) TypeScriptAstParser.sliceText(bytes, attrNode).trim().lowercase() else ""
            if (objText.isNotEmpty() || attrText.isNotEmpty()) {
                return objText to attrText
            }
        }
        val fnText = TypeScriptAstParser.sliceText(bytes, fnNode).trim()
        return fnText.substringBefore('.', "").trim() to fnText.substringAfter('.', "").trim().lowercase()
    }

    private val PY_FSTRING_VAR_REGEX = Regex("""\{\s*([a-zA-Z_][a-zA-Z0-9_]*)\s*\}""")

    private fun extractPyUrlExpression(bytes: ByteArray, exprNode: TSNode, stringVars: Map<String, String>): String? {
        if (exprNode.isNull) return null
        return when (exprNode.type) {
            "string" -> {
                val rawSlice = TypeScriptAstParser.sliceText(bytes, exprNode)
                val unquoted = unquotePyString(rawSlice)
                if (rawSlice.trimStart().firstOrNull()?.lowercaseChar() == 'f') {
                    PY_FSTRING_VAR_REGEX.replace(unquoted) { match ->
                        val varName = match.groupValues[1]
                        stringVars[varName] ?: match.value
                    }
                } else {
                    unquoted
                }
            }
            "identifier" -> stringVars[TypeScriptAstParser.sliceText(bytes, exprNode).trim()]
            "parenthesized_expression" -> if (exprNode.namedChildCount > 0) {
                extractPyUrlExpression(bytes, exprNode.getNamedChild(0), stringVars)
            } else null
            "binary_operator" -> {
                val leftNode = exprNode.getChildByFieldName("left")
                val rightNode = exprNode.getChildByFieldName("right")
                val leftStr = extractPyUrlExpression(bytes, leftNode, stringVars)
                val rightStr = extractPyUrlExpression(bytes, rightNode, stringVars)
                when {
                    leftStr != null && rightStr != null -> leftStr + rightStr
                    leftStr != null -> if (leftStr.endsWith("/")) "${leftStr}{param}" else leftStr
                    rightStr != null && rightStr.startsWith("/") -> rightStr
                    else -> null
                }
            }
            "call" -> {
                val fnNode = exprNode.getChildByFieldName("function")
                if (!fnNode.isNull && fnNode.type == "attribute") {
                    val objNode = fnNode.getChildByFieldName("object")
                    extractPyUrlExpression(bytes, objNode, stringVars)
                } else null
            }
            else -> null
        }
    }

    private val IGNORED_PY_REQUEST_PARAM_TYPES = setOf(
        "SessionDep", "CurrentUser", "Session", "AsyncSession", "Request", "Response",
        "BackgroundTasks", "HTTPAuthorizationCredentials", "OAuth2PasswordRequestForm",
        "int", "str", "bool", "float", "bytes", "Any", "UUID", "uuid.UUID", "dict", "list"
    )

    private fun extractDecoratedFunctionRequestModel(
        bytes: ByteArray,
        decoratorNode: TSNode,
        models: Map<String, Map<String, String>>,
        routePath: String
    ): String? {
        val parent = decoratorNode.parent
        if (!parent.isNull && parent.type == "decorated_definition") {
            val defNode = parent.getChildByFieldName("definition")
            if (!defNode.isNull && defNode.type == "function_definition") {
                val paramsNode = defNode.getChildByFieldName("parameters")
                if (!paramsNode.isNull) {
                    for (i in 0 until paramsNode.namedChildCount) {
                        val param = paramsNode.getNamedChild(i)
                        if (param.type == "typed_parameter" || param.type == "typed_default_parameter") {
                            val typeNode = param.getChildByFieldName("type")
                            val nameNode = if (param.namedChildCount > 0) param.getNamedChild(0) else null
                            val paramName = if (nameNode != null && !nameNode.isNull) {
                                TypeScriptAstParser.sliceText(bytes, nameNode).trim()
                            } else ""
                            if (paramName.isNotEmpty() &&
                                Regex("""\{\s*${Regex.escape(paramName)}(?:\s*:[^}]*)?\}""").containsMatchIn(routePath)
                            ) continue
                            if (!typeNode.isNull) {
                                var rawType = TypeScriptAstParser.sliceText(bytes, typeNode).trim()
                                if (rawType.startsWith("Annotated[") && rawType.endsWith("]")) {
                                    rawType = rawType.removePrefix("Annotated[").substringBefore(',').trim()
                                }
                                rawType = rawType.substringBefore('|').trim()
                                if (rawType in models ||
                                    (rawType.isNotEmpty() && rawType[0].isUpperCase() && rawType !in IGNORED_PY_REQUEST_PARAM_TYPES && !rawType.endsWith("Dep"))
                                ) {
                                    return rawType
                                }
                            }
                        }
                    }
                }
            }
        }
        return null
    }

    private fun unwrapPythonModelName(raw: String): String? {
        var current = raw.trim()
        if (current.isEmpty()) return null
        var changed = true
        while (changed) {
            changed = false
            if (current.contains('|')) {
                current = current.split('|')
                    .map { it.trim() }
                    .firstOrNull { it != "None" }
                    ?: ""
                changed = true
            }
            if (current.contains('[') && current.endsWith(']')) {
                val outer = current.substringBefore('[').trim().substringAfterLast('.')
                val inner = current.substringAfter('[').removeSuffix("]").trim()
                if (outer.equals("Annotated", true)) {
                    current = inner.substringBefore(',').trim()
                    changed = true
                } else if (outer in setOf("list", "List", "set", "Set", "Sequence", "Iterable", "Optional", "Union", "tuple", "Tuple")) {
                    current = inner.split(',').map { it.trim() }.firstOrNull { it != "None" }.orEmpty()
                    changed = true
                }
            }
        }
        val clean = current.substringAfterLast('.').trim()
        return clean.takeIf { it.isNotEmpty() && it !in setOf("None", "dict", "list", "Any", "str", "int", "bool", "float") }
    }

    private fun extractDecoratedFunctionReturnModel(bytes: ByteArray, decoratorNode: TSNode): String? {
        val parent = decoratorNode.parent
        if (!parent.isNull && parent.type == "decorated_definition") {
            val defNode = parent.getChildByFieldName("definition")
            if (!defNode.isNull && defNode.type == "function_definition") {
                val retTypeNode = defNode.getChildByFieldName("return_type")
                if (!retTypeNode.isNull) {
                    val raw = TypeScriptAstParser.sliceText(bytes, retTypeNode).trim()
                    return unwrapPythonModelName(raw)
                }
            }
        }
        return null
    }

    private fun extractPythonClassFields(bytes: ByteArray, bodyNode: TSNode): Map<String, String> {
        val fields = linkedMapOf<String, String>()
        for (i in 0 until bodyNode.namedChildCount) {
            val stmt = bodyNode.getNamedChild(i)
            val assignmentNode = when {
                stmt.type == "assignment" -> stmt
                stmt.type == "expression_statement" && stmt.namedChildCount > 0 && stmt.getNamedChild(0).type == "assignment" ->
                    stmt.getNamedChild(0)
                else -> null
            }
            if (assignmentNode != null) {
                val leftNode = assignmentNode.getChildByFieldName("left")
                val typeNode = assignmentNode.getChildByFieldName("type")
                if (!leftNode.isNull && !typeNode.isNull) {
                    val fieldName = TypeScriptAstParser.sliceText(bytes, leftNode).trim()
                    val fieldType = TypeScriptAstParser.sliceText(bytes, typeNode).trim()
                    if (fieldName.isNotEmpty() && !fieldName.startsWith("_") && fieldType.isNotEmpty()) {
                        fields[fieldName] = fieldType
                    }
                }
            }
        }
        return fields
    }

    private fun unquotePyString(raw: String): String {
        var s = raw.trim()
        // Strip leading f/r/b/u string prefix (`f"/v1/..."`, `u"..."`)
        while (s.isNotEmpty() && s[0].lowercaseChar() in setOf('f', 'r', 'b', 'u')) {
            if (s.length >= 2 && (s[1] == '"' || s[1] == '\'' || s[1].lowercaseChar() in setOf('f', 'r', 'b', 'u'))) {
                s = s.substring(1)
            } else {
                break
            }
        }
        if (s.startsWith("\"\"\"") && s.endsWith("\"\"\"") && s.length >= 6) {
            return s.substring(3, s.length - 3)
        }
        if (s.startsWith("'''") && s.endsWith("'''") && s.length >= 6) {
            return s.substring(3, s.length - 3)
        }
        return TypeScriptAstParser.unquoteTsString(s)
    }
}
