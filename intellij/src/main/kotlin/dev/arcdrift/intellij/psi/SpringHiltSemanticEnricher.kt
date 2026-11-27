package dev.arcdrift.intellij.psi

import dev.arcdrift.core.model.ContractEndpoint
import dev.arcdrift.core.model.NodeKind
import org.jetbrains.uast.UAnnotation
import org.jetbrains.uast.UClass
import org.jetbrains.uast.UMethod

/**
 * Enriches extracted UAST classes and methods with semantic framework classifications
 * for Spring Boot, Ktor, Jakarta EE, and Android Hilt/Dagger.
 */
object SpringHiltSemanticEnricher {

    private val CONTROLLER_ANNOTATIONS = setOf(
        "RestController", "Controller", "GrpcService"
    )
    private val SERVICE_ANNOTATIONS = setOf(
        "Service", "Component", "Singleton", "ApplicationScoped", "Module", "InstallIn"
    )
    private val REPOSITORY_ANNOTATIONS = setOf(
        "Repository", "Dao"
    )
    private val ENTITY_ANNOTATIONS = setOf(
        "Entity", "Table", "Document"
    )
    private val LISTENER_ANNOTATIONS = setOf(
        "KafkaListener", "RabbitListener", "SqsListener", "EventListener"
    )
    private val ROUTE_ANNOTATIONS = mapOf(
        "GetMapping" to "GET",
        "PostMapping" to "POST",
        "PutMapping" to "PUT",
        "DeleteMapping" to "DELETE",
        "PatchMapping" to "PATCH",
        "RequestMapping" to "GET"
    )

    private val GENERIC_WRAPPERS = setOf(
        "ResponseEntity", "HttpEntity", "Optional", "List", "MutableList", "Set", "MutableSet",
        "Collection", "MutableCollection", "Iterable", "Sequence", "Array",
        "Flux", "Mono", "CompletableFuture", "Deferred", "Call", "Result"
    )
    private val STRING_LITERAL_REGEX = Regex(""""([^"]*)"""")

    private val SERIALIZED_NAME_ANNOTATIONS = setOf(
        "JsonProperty", "SerialName", "SerializedName", "Json"
    )

    fun resolveUastSerializedFieldName(annotations: List<UAnnotation>, fallbackName: String): String {
        for (ann in annotations) {
            val shortName = annotationShortName(ann) ?: continue
            if (shortName in SERIALIZED_NAME_ANNOTATIONS) {
                val custom = extractAnnotationSingleString(ann, listOf("value", "name")).trim()
                if (custom.isNotEmpty()) return custom
            }
        }
        return fallbackName
    }

    fun extractSimpleStereotypes(uClass: UClass): List<String> {
        return uClass.uAnnotations.mapNotNull { ann ->
            val shortName = annotationShortName(ann) ?: return@mapNotNull null
            "@$shortName"
        }.distinct()
    }

    fun classifyNodeKind(uClass: UClass, stereotypes: List<String>): NodeKind {
        val bareNames = stereotypes.map { it.removePrefix("@") }.toSet()
        return when {
            bareNames.any { it in CONTROLLER_ANNOTATIONS } -> NodeKind.REST_CONTROLLER
            bareNames.any { it in REPOSITORY_ANNOTATIONS } || (uClass.name?.endsWith("Repository") == true) -> NodeKind.REPOSITORY
            uClass.isInterface -> NodeKind.INTERFACE
            bareNames.any { it in SERVICE_ANNOTATIONS } -> NodeKind.SERVICE_BEAN
            bareNames.any { it in ENTITY_ANNOTATIONS } -> NodeKind.ENTITY
            bareNames.any { it in LISTENER_ANNOTATIONS } -> NodeKind.MESSAGE_LISTENER
            uClass.isEnum -> NodeKind.ENUM
            uClass.javaPsi.isRecord -> NodeKind.DATA_CLASS
            else -> NodeKind.CLASS
        }
    }

    fun extractRestContracts(
        uClass: UClass,
        dtoFieldsBySimpleName: Map<String, Map<String, String>> = emptyMap()
    ): List<ContractEndpoint> {
        val baseMapping = uClass.uAnnotations
            .firstOrNull { annotationShortName(it) == "RequestMapping" }
            ?.let { extractAnnotationPath(it) }
            .orEmpty()

        val contracts = mutableListOf<ContractEndpoint>()
        for (method in uClass.methods) {
            for (ann in method.uAnnotations) {
                val shortName = annotationShortName(ann) ?: continue
                if (shortName == "KafkaListener" || shortName == "RabbitListener") {
                    for (topic in extractListenerTopics(ann)) {
                        contracts.add(
                            ContractEndpoint(
                                protocol = "KAFKA",
                                methodOrAction = "CONSUME",
                                pathOrTopic = topic,
                                role = "CONSUMER"
                            )
                        )
                    }
                    continue
                }
                val httpVerb = if (shortName == "RequestMapping") {
                    val rawText = (ann.findAttributeValue("method")?.asRenderString() ?: "").uppercase()
                    when {
                        Regex("""\bPOST\b""").containsMatchIn(rawText) -> "POST"
                        Regex("""\bPUT\b""").containsMatchIn(rawText) -> "PUT"
                        Regex("""\bDELETE\b""").containsMatchIn(rawText) -> "DELETE"
                        Regex("""\bPATCH\b""").containsMatchIn(rawText) -> "PATCH"
                        else -> "GET"
                    }
                } else {
                    ROUTE_ANNOTATIONS[shortName] ?: continue
                }
                val subPath = extractAnnotationPath(ann)
                val fullPath = ("/$baseMapping/$subPath")
                    .replace(Regex("/+"), "/")
                    .let { if (it.length > 1) it.trimEnd('/') else it }
                val ktPsiReturnType = (method.sourcePsi as? org.jetbrains.kotlin.psi.KtNamedFunction)?.typeReference?.text
                val rawReturnType = method.returnType?.presentableText?.takeIf {
                    !it.startsWith("NonExistent") && it != "error.NonExistentClass" && it != "Unit"
                } ?: ktPsiReturnType ?: ""
                val unwrappedReturnDto = unwrapDtoName(rawReturnType)

                val requestDto = method.uastParameters.firstNotNullOfOrNull { param ->
                    val hasRequestBody = param.uAnnotations.any {
                        annotationShortName(it) == "RequestBody"
                    }
                    if (hasRequestBody) unwrapDtoName(param.type.presentableText) else null
                } ?: if (httpVerb in setOf("POST", "PUT", "PATCH")) {
                    method.uastParameters
                        .mapNotNull { unwrapDtoName(it.type.presentableText) }
                        .firstOrNull { it in dtoFieldsBySimpleName }
                } else null

                val reqFields = linkedMapOf<String, String>()
                if (requestDto != null) {
                    dtoFieldsBySimpleName[requestDto]?.let { reqFields.putAll(it) }
                }
                val respFields = linkedMapOf<String, String>()
                if (unwrappedReturnDto != null) {
                    dtoFieldsBySimpleName[unwrappedReturnDto]?.let { respFields.putAll(it) }
                }

                contracts.add(
                    ContractEndpoint(
                        protocol = "REST",
                        methodOrAction = httpVerb,
                        pathOrTopic = fullPath,
                        role = "PRODUCER",
                        requestDto = requestDto,
                        responseDto = unwrappedReturnDto,
                        dtoFields = respFields,
                        requestDtoFields = reqFields
                    )
                )
            }
        }
        for (ann in uClass.uAnnotations) {
            val shortName = annotationShortName(ann) ?: continue
            if (shortName == "KafkaListener" || shortName == "RabbitListener") {
                for (topic in extractListenerTopics(ann)) {
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
        return contracts
    }

    fun unwrapDtoName(rawType: String): String? {
        var current = rawType.trim().removeSuffix("?")
        if (current.isEmpty() || current == "Unit" || current == "void" || current == "Void") return null
        while (current.contains('<') && current.endsWith('>')) {
            val outer = current.substringBefore('<').trim().substringAfterLast('.')
            val inner = current.substringAfter('<').removeSuffix(">").trim()
            if (outer in GENERIC_WRAPPERS) {
                current = inner.removeSuffix("?")
            } else {
                break
            }
        }
        return current.substringBefore('<').substringAfterLast('.').removeSuffix("?").trim().ifEmpty { null }
    }

    private fun annotationShortName(ann: UAnnotation): String? {
        val qName = ann.qualifiedName
        if (!qName.isNullOrBlank()) {
            return qName.substringAfterLast('.')
        }
        val rawPsi = ann.sourcePsi?.text?.trim() ?: return null
        return rawPsi
            .removePrefix("@")
            .substringBefore('(')
            .substringAfterLast('.')
            .trim()
            .takeIf { it.isNotEmpty() }
    }

    private fun extractAnnotationSingleString(ann: UAnnotation, preferredKeys: List<String>): String {
        val attrExpr = preferredKeys.firstNotNullOfOrNull { ann.findAttributeValue(it) }
            ?: ann.attributeValues.firstOrNull()?.expression
        val evaluated = attrExpr?.evaluate()
        val fromUast = when (evaluated) {
            is String -> evaluated
            is Array<*> -> evaluated.firstOrNull()?.toString().orEmpty()
            else -> attrExpr?.asRenderString()?.let { STRING_LITERAL_REGEX.find(it)?.groupValues?.get(1) }
                ?: attrExpr?.asRenderString()?.trim('"', '\'', ' ', '[', ']').orEmpty()
        }
        if (fromUast.isNotBlank()) return fromUast
        val rawPsiText = ann.sourcePsi?.text.orEmpty()
        return STRING_LITERAL_REGEX.find(rawPsiText)?.groupValues?.get(1).orEmpty()
    }

    private fun extractListenerTopics(ann: UAnnotation): List<String> {
        val attrExpr = ann.findAttributeValue("topics")
            ?: ann.findAttributeValue("queues")
            ?: ann.findAttributeValue("value")
            ?: ann.attributeValues.firstOrNull()?.expression
        val evaluated = attrExpr?.evaluate()
        val extracted = when (evaluated) {
            is String -> listOf(evaluated)
            is Array<*> -> evaluated.mapNotNull { it?.toString()?.trim()?.takeIf { s -> s.isNotEmpty() } }
            else -> {
                val rendered = attrExpr?.asRenderString() ?: ann.sourcePsi?.text.orEmpty()
                STRING_LITERAL_REGEX.findAll(rendered)
                    .map { it.groupValues[1].trim() }
                    .filter { it.isNotEmpty() }
                    .toList()
            }
        }
        return extracted.distinct()
    }

    private fun extractAnnotationPath(ann: UAnnotation): String =
        extractAnnotationSingleString(ann, listOf("value", "path"))
}
