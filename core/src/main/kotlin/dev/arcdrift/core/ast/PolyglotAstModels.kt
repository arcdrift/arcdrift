package dev.arcdrift.core.ast

import dev.arcdrift.core.model.ContractEndpoint
import dev.arcdrift.core.model.MemberSignature
import dev.arcdrift.core.model.NodeKind
import kotlinx.serialization.Serializable

@Serializable
data class AstAnnotation(
    val name: String,
    val namedArgs: Map<String, List<String>> = emptyMap(),
    val positionalStrings: List<String> = emptyList(),
    val rawTokensText: String = ""
) {
    fun firstPathOrValue(): String {
        for (key in listOf("value", "path", "topics", "queues")) {
            val v = namedArgs[key]?.firstOrNull()
            if (!v.isNullOrEmpty()) return v
        }
        return positionalStrings.firstOrNull().orEmpty()
    }

    fun allPathsOrValues(): List<String> {
        for (key in listOf("value", "path", "topics", "queues")) {
            val vals = namedArgs[key]?.filter { it.isNotBlank() || namedArgs[key]?.size == 1 }
            if (!vals.isNullOrEmpty()) return vals
        }
        return positionalStrings.ifEmpty { listOf("") }
    }

    companion object {
        private val SERIALIZED_NAME_ANNOTATIONS = setOf("JsonProperty", "SerialName", "SerializedName", "Json")

        fun resolveSerializedFieldName(annotations: List<AstAnnotation>, fallbackName: String): String {
            for (ann in annotations) {
                if (ann.name in SERIALIZED_NAME_ANNOTATIONS) {
                    val custom = ann.namedArgs["value"]?.firstOrNull()
                        ?: ann.namedArgs["name"]?.firstOrNull()
                        ?: ann.positionalStrings.firstOrNull()
                    if (!custom.isNullOrBlank()) return custom
                }
            }
            return fallbackName
        }
    }
}

@Serializable
data class AstTypeRef(
    val rawName: String,
    val typeArguments: List<AstTypeRef> = emptyList(),
    val isNullable: Boolean = false,
    val isVararg: Boolean = false
) {
    fun render(): String {
        val args = if (typeArguments.isEmpty()) "" else "<${typeArguments.joinToString(", ") { it.render() }}>"
        val nullSuffix = if (isNullable) "?" else ""
        val varargSuffix = if (isVararg) "..." else ""
        return "$rawName$args$nullSuffix$varargSuffix"
    }

    fun collectAllSimpleTypeNames(out: MutableSet<String>) {
        val clean = rawName.removeSuffix("[]").removeSuffix("...")
        for (seg in clean.split('.')) {
            if (seg.isNotEmpty() && seg[0].isUpperCase()) {
                out.add(seg)
            }
        }
        for (arg in typeArguments) {
            arg.collectAllSimpleTypeNames(out)
        }
    }
}

@Serializable
data class AstParam(
    val name: String,
    val type: AstTypeRef,
    val annotations: List<AstAnnotation> = emptyList(),
    val isProperty: Boolean = false
)

@Serializable
data class AstMethod(
    val name: String,
    val parameters: List<AstParam>,
    val returnType: AstTypeRef?,
    val inferredReturnTypeName: String? = null,
    val annotations: List<AstAnnotation> = emptyList(),
    val visibility: String = "public",
    val isSuspend: Boolean = false
)

@Serializable
data class JvmParsedClassAst(
    val fqId: String,
    val simpleName: String,
    val pkg: String,
    val kind: NodeKind,
    val stereotypes: List<String>,
    val annotations: List<AstAnnotation>,
    val fields: Map<String, String>, // fieldName -> unwrappedType
    val members: List<MemberSignature>,
    val methods: List<AstMethod>,
    val inlineRoutes: List<ContractEndpoint>,
    val referencedSimpleNames: Set<String>,
    val referencedQualifiedNames: Set<String>,
    val superTypeSimpleNames: Set<String> = emptySet(),
    val startLine: Int,
    val endLine: Int
)

@Serializable
data class JvmParsedFileAst(
    val pkg: String,
    val explicitImports: List<String>,
    val wildcardImports: List<String>,
    val importAliases: Map<String, String>, // alias -> fqName
    val classes: List<JvmParsedClassAst>
)

object AstTypeUnwrapper {
    private val GENERIC_WRAPPERS = setOf(
        "ResponseEntity", "HttpEntity", "Optional", "List", "MutableList", "Set", "MutableSet",
        "Collection", "MutableCollection", "Iterable", "MutableIterable", "Array", "ReadonlyArray",
        "CompletableFuture", "Future", "Mono", "Flux", "Flow", "Deferred", "Result", "Page", "Slice",
        "PagedModel", "CollectionModel", "EntityModel", "Resources"
    )
    private val COLLECTION_WRAPPERS = setOf(
        "List", "MutableList", "Set", "MutableSet", "Collection", "MutableCollection",
        "Iterable", "MutableIterable", "Flux", "Flow", "Array", "ReadonlyArray"
    )

    fun unwrapPrimaryDto(typeRef: AstTypeRef?): String? {
        if (typeRef == null) return null
        var curr: AstTypeRef = typeRef
        while (curr.rawName.substringAfterLast('.') in GENERIC_WRAPPERS && curr.typeArguments.isNotEmpty()) {
            curr = curr.typeArguments.first()
        }
        val simple = curr.rawName.removeSuffix("[]").substringAfterLast('.').removeSuffix("?").trim()
        return simple.takeIf { it.isNotEmpty() && it !in setOf("Unit", "void", "Void", "Any", "Object") }
    }

    fun unwrapFieldWireTypeString(rawType: String): String {
        var current = rawType.trim().removeSuffix("...").removeSuffix("?")
        var isCollection = false
        while (current.endsWith("[]")) {
            isCollection = true
            current = current.removeSuffix("[]").trim().removeSuffix("?")
        }
        while (current.contains('<') && current.endsWith('>')) {
            val outer = current.substringBefore('<').trim().substringAfterLast('.')
            val inner = current.substringAfter('<').substringBeforeLast('>').trim()
            if (outer in GENERIC_WRAPPERS && inner.isNotEmpty()) {
                if (outer in COLLECTION_WRAPPERS) isCollection = true
                current = inner.removeSuffix("?")
            } else {
                break
            }
        }
        while (current.endsWith("[]")) {
            isCollection = true
            current = current.removeSuffix("[]").trim().removeSuffix("?")
        }
        val base = current.substringBefore('<').substringAfterLast('.').removeSuffix("?").trim()
        return if (isCollection && base.isNotEmpty()) "$base[]" else base
    }

    fun unwrapPrimaryDtoString(rawType: String): String {
        var current = rawType.trim()
        if (current.contains('|')) {
            current = current.split('|')
                .map { it.trim() }
                .firstOrNull { it !in setOf("null", "undefined", "void", "None") }
                ?: current
        }
        current = current.removePrefix("readonly ").removeSuffix("?").trim()
        while (current.endsWith("[]")) {
            current = current.removeSuffix("[]").trim().removeSuffix("?")
        }
        while ((current.startsWith("list[", true) || current.startsWith("set[", true) ||
                current.startsWith("sequence[", true) || current.startsWith("optional[", true)) &&
            current.endsWith("]")
        ) {
            current = current.substringAfter('[').removeSuffix("]").trim()
            if (current.contains('|')) {
                current = current.split('|').map { it.trim() }
                    .firstOrNull { it !in setOf("null", "undefined", "void", "None") } ?: current
            }
        }
        while (current.contains('<') && current.endsWith('>')) {
            val outer = current.substringBefore('<').trim().substringAfterLast('.')
            val inner = current.substringAfter('<').substringBeforeLast('>').trim()
            if (outer in GENERIC_WRAPPERS && inner.isNotEmpty()) {
                current = inner.removeSuffix("?")
                while (current.endsWith("[]")) {
                    current = current.removeSuffix("[]").trim().removeSuffix("?")
                }
            } else {
                break
            }
        }
        return current.substringBefore('<').removeSuffix("[]").substringAfterLast('.').removeSuffix("?").trim()
    }

    fun buildContractsForClass(
        annotations: List<AstAnnotation>,
        methods: List<AstMethod>,
        inlineRoutes: List<ContractEndpoint>,
        dtoDefinitions: Map<String, Map<String, String>>,
        openApiOperations: Map<String, Pair<String, String>> = emptyMap(),
        isControllerOrApiImpl: Boolean = false
    ): List<ContractEndpoint> {
        val contracts = mutableListOf<ContractEndpoint>()
        val baseMappings = annotations
            .filter { it.name == "RequestMapping" }
            .flatMap { it.allPathsOrValues() }
            .ifEmpty { listOf("") }

        val routeNames = setOf("GetMapping", "PostMapping", "PutMapping", "DeleteMapping", "PatchMapping", "RequestMapping")
        for (method in methods) {
            var matchedExplicitRoute = false
            for (ann in method.annotations) {
                if (ann.name in routeNames) {
                    matchedExplicitRoute = true
                    val subPaths = ann.allPathsOrValues()
                    val httpVerb = when (ann.name) {
                        "GetMapping" -> "GET"
                        "PostMapping" -> "POST"
                        "PutMapping" -> "PUT"
                        "DeleteMapping" -> "DELETE"
                        "PatchMapping" -> "PATCH"
                        "RequestMapping" -> {
                            val methodArg = (ann.namedArgs["method"]?.firstOrNull() ?: ann.rawTokensText).uppercase()
                            when {
                                Regex("""\bPOST\b""").containsMatchIn(methodArg) -> "POST"
                                Regex("""\bPUT\b""").containsMatchIn(methodArg) -> "PUT"
                                Regex("""\bDELETE\b""").containsMatchIn(methodArg) -> "DELETE"
                                Regex("""\bPATCH\b""").containsMatchIn(methodArg) -> "PATCH"
                                else -> "GET"
                            }
                        }
                        else -> "GET"
                    }
                    val requestDto = method.parameters
                        .firstOrNull { p -> p.annotations.any { it.name == "RequestBody" } }
                        ?.let { unwrapPrimaryDto(it.type) }
                        ?: if (httpVerb in setOf("POST", "PUT", "PATCH")) {
                            method.parameters.mapNotNull { unwrapPrimaryDto(it.type) }.firstOrNull { it in dtoDefinitions }
                        } else null
                    val responseDto = unwrapPrimaryDto(method.returnType) ?: method.inferredReturnTypeName

                    val reqFields = linkedMapOf<String, String>()
                    if (requestDto != null) reqFields.putAll(dtoDefinitions[requestDto].orEmpty())
                    val respFields = linkedMapOf<String, String>()
                    if (responseDto != null) respFields.putAll(dtoDefinitions[responseDto].orEmpty())

                    for (baseMapping in baseMappings) {
                        for (subPath in subPaths) {
                            val fullPath = ("/$baseMapping/$subPath")
                                .replace(Regex("/+"), "/")
                                .let { if (it.length > 1 && subPath.isEmpty()) it.removeSuffix("/") else it }
                            contracts.add(
                                ContractEndpoint(
                                    protocol = "REST",
                                    methodOrAction = httpVerb,
                                    pathOrTopic = fullPath,
                                    role = "PRODUCER",
                                    requestDto = requestDto,
                                    responseDto = responseDto,
                                    dtoFields = respFields,
                                    requestDtoFields = reqFields
                                )
                            )
                        }
                    }
                } else if (ann.name == "KafkaListener" || ann.name == "RabbitListener") {
                    for (topic in ann.allPathsOrValues()) {
                        if (topic.isNotEmpty()) {
                            contracts.add(
                                ContractEndpoint(
                                    protocol = "KAFKA",
                                    methodOrAction = "CONSUME",
                                    pathOrTopic = topic,
                                    role = "CONSUMER"
                                )
                            )
                        }
                    }
                }
            }

            if (!matchedExplicitRoute && isControllerOrApiImpl && method.name in openApiOperations) {
                val (httpVerb, oaPath) = openApiOperations.getValue(method.name)
                val primaryBase = baseMappings.firstOrNull().orEmpty().trim('/')
                val normOaPath = "/" + oaPath.trimStart('/')
                val alreadyHasBase = primaryBase.isEmpty() ||
                    normOaPath == "/$primaryBase" ||
                    normOaPath.startsWith("/$primaryBase/")
                val fullPath = if (!alreadyHasBase) {
                    ("/$primaryBase/$oaPath").replace(Regex("/+"), "/")
                } else {
                    normOaPath.replace(Regex("/+"), "/")
                }
                val requestDto = if (httpVerb in setOf("POST", "PUT", "PATCH")) {
                    method.parameters
                        .mapNotNull { unwrapPrimaryDto(it.type) }
                        .firstOrNull { candidate ->
                            candidate in dtoDefinitions ||
                                candidate.removeSuffix("Dto").removeSuffix("DTO") in dtoDefinitions
                        }
                } else null
                val responseDto = unwrapPrimaryDto(method.returnType) ?: method.inferredReturnTypeName
                val reqFields = linkedMapOf<String, String>()
                if (requestDto != null) {
                    reqFields.putAll(dtoDefinitions[requestDto].orEmpty())
                    if (reqFields.isEmpty()) {
                        val strippedReq = requestDto.removeSuffix("Dto").removeSuffix("DTO")
                        reqFields.putAll(dtoDefinitions[strippedReq].orEmpty())
                    }
                }
                val respFields = linkedMapOf<String, String>()
                if (responseDto != null) {
                    respFields.putAll(dtoDefinitions[responseDto].orEmpty())
                    if (respFields.isEmpty()) {
                        val strippedDto = responseDto.removeSuffix("Dto").removeSuffix("DTO")
                        respFields.putAll(dtoDefinitions[strippedDto].orEmpty())
                    }
                }
                contracts.add(
                    ContractEndpoint(
                        protocol = "REST",
                        methodOrAction = httpVerb,
                        pathOrTopic = fullPath,
                        role = "PRODUCER",
                        requestDto = requestDto,
                        responseDto = responseDto,
                        dtoFields = respFields,
                        requestDtoFields = reqFields
                    )
                )
            }
        }

        for (ann in annotations) {
            if (ann.name == "KafkaListener" || ann.name == "RabbitListener") {
                for (topic in ann.allPathsOrValues()) {
                    if (topic.isNotEmpty()) {
                        contracts.add(
                            ContractEndpoint(
                                protocol = "KAFKA",
                                methodOrAction = "CONSUME",
                                pathOrTopic = topic,
                                role = "CONSUMER"
                            )
                        )
                    }
                }
            }
        }

        contracts.addAll(inlineRoutes)
        return contracts
    }

    fun classifyNodeKind(
        declKeyword: String,
        stereotypes: List<String>,
        simpleName: String,
        isDataOrRecord: Boolean
    ): NodeKind {
        return when {
            stereotypes.any { it in setOf("@RestController", "@Controller", "@GrpcService") } -> NodeKind.REST_CONTROLLER
            stereotypes.any { it in setOf("@Repository", "@Dao") } || simpleName.endsWith("Repository") -> NodeKind.REPOSITORY
            declKeyword == "interface" -> NodeKind.INTERFACE
            stereotypes.any { it in setOf("@Service", "@Component", "@Singleton", "@ApplicationScoped") } -> NodeKind.SERVICE_BEAN
            stereotypes.any { it in setOf("@Entity", "@Table", "@Document") } -> NodeKind.ENTITY
            stereotypes.any { it in setOf("@KafkaListener", "@RabbitListener", "@SqsListener") } -> NodeKind.MESSAGE_LISTENER
            declKeyword.startsWith("enum") -> NodeKind.ENUM
            isDataOrRecord -> NodeKind.DATA_CLASS
            else -> NodeKind.CLASS
        }
    }
}
