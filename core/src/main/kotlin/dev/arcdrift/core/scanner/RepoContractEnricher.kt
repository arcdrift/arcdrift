package dev.arcdrift.core.scanner

import dev.arcdrift.core.ast.AstTypeUnwrapper
import dev.arcdrift.core.model.ArcNode
import dev.arcdrift.core.model.ContractEndpoint
import dev.arcdrift.core.model.NodeKind

/**
 * Enriches TypeScript and Python node contracts using cross-file DTO/model schemas,
 * `@hey-api/openapi-ts` SDK call sites, OpenAPI operations, and FastAPI global prefixes.
 */
internal object RepoContractEnricher {

    private val OPENAPI_DATA_WRAPPER_KEYS = setOf("body", "path", "query", "url", "headers")

    fun resolveTsDtoAndFields(
        rawDtoName: String?,
        tsTypeDefinitions: Map<String, Map<String, String>>,
        visited: Set<String> = emptySet()
    ): Pair<String?, Map<String, String>> {
        if (rawDtoName.isNullOrEmpty()) return null to emptyMap()
        val cleanDto = AstTypeUnwrapper.unwrapPrimaryDtoString(rawDtoName).ifEmpty { rawDtoName }
        if (cleanDto in visited) return null to emptyMap()
        val nextVisited = visited + cleanDto
        val directFields = tsTypeDefinitions[cleanDto] ?: tsTypeDefinitions[rawDtoName]
        if (directFields != null) {
            val statusTarget = (directFields["200"] ?: directFields["201"])
                ?.let { AstTypeUnwrapper.unwrapPrimaryDtoString(it) }
            if (!statusTarget.isNullOrEmpty() && statusTarget !in nextVisited) {
                return resolveTsDtoAndFields(statusTarget, tsTypeDefinitions, nextVisited)
            }
            if (directFields.keys.all { it.toIntOrNull() != null }) {
                return statusTarget to emptyMap()
            }
            if ("content" in directFields && "totalElements" in directFields && "totalPages" in directFields) {
                val pageTarget = directFields["content"]?.let { AstTypeUnwrapper.unwrapPrimaryDtoString(it) }
                if (!pageTarget.isNullOrEmpty() && pageTarget !in nextVisited) {
                    return resolveTsDtoAndFields(pageTarget, tsTypeDefinitions, nextVisited)
                }
                return pageTarget to emptyMap()
            }
            return cleanDto to directFields
        }
        if (cleanDto.startsWith("Rest") && cleanDto.length > 4) {
            val iCandidate = "I" + cleanDto.removePrefix("Rest")
            if (iCandidate in tsTypeDefinitions) {
                return iCandidate to tsTypeDefinitions[iCandidate].orEmpty()
            }
        }
        if (cleanDto.startsWith("New") && cleanDto.length > 3) {
            val iCandidate = "I" + cleanDto.removePrefix("New").removePrefix("Rest")
            if (iCandidate in tsTypeDefinitions) {
                return iCandidate to tsTypeDefinitions[iCandidate].orEmpty()
            }
        }
        return cleanDto to emptyMap()
    }

    fun resolveTsRequestDtoAndFields(
        rawReqDto: String?,
        existingReqFields: Map<String, String>,
        tsTypeDefinitions: Map<String, Map<String, String>>
    ): Pair<String?, Map<String, String>> {
        if (rawReqDto == "InlineRequestPayload" && existingReqFields.isNotEmpty()) {
            return rawReqDto to existingReqFields
        }
        if (rawReqDto.isNullOrEmpty()) return null to existingReqFields
        val cleanReqDto = AstTypeUnwrapper.unwrapPrimaryDtoString(rawReqDto).ifEmpty { rawReqDto }
        val directFields = tsTypeDefinitions[cleanReqDto] ?: tsTypeDefinitions[rawReqDto]
        if (directFields != null) {
            if (cleanReqDto.endsWith("Data") && directFields.keys.all { it in OPENAPI_DATA_WRAPPER_KEYS }) {
                val bodyTarget = directFields["body"]?.let { AstTypeUnwrapper.unwrapPrimaryDtoString(it) }
                if (bodyTarget.isNullOrEmpty() || bodyTarget == "never" || bodyTarget == "unknown" || bodyTarget == "any") {
                    return null to emptyMap()
                }
                if (bodyTarget in tsTypeDefinitions) {
                    return bodyTarget to tsTypeDefinitions[bodyTarget].orEmpty()
                }
                return bodyTarget to emptyMap()
            }
            return cleanReqDto to directFields
        }
        return cleanReqDto to existingReqFields
    }

    fun enrichPolyglotContracts(
        nodesById: MutableMap<String, ArcNode>,
        tsTypeDefinitions: Map<String, Map<String, String>>,
        tsSdkMethods: Map<String, ContractEndpoint>,
        tsSdkCallsByNodeId: Map<String, List<Pair<String, String>>>,
        pyModelDefinitions: Map<String, Map<String, String>>,
        pyApiPrefixes: Map<String, String>,
        openApiOperations: Map<String, Pair<String, String>>,
        implementedJvmMethodNames: Set<String>
    ) {
        val pyGlobalApiPrefix = pyApiPrefixes["API_V1_STR"] ?: pyApiPrefixes.values.firstOrNull()

        for ((id, node) in nodesById.entries.toList()) {
            if (node.kind == NodeKind.TS_CLIENT) {
                val sdkCallContracts = tsSdkCallsByNodeId[id].orEmpty().mapNotNull { (receiver, methodName) ->
                    val sdkEndpoint = tsSdkMethods["$receiver.$methodName"] ?: tsSdkMethods[methodName]
                    if (sdkEndpoint != null) {
                        sdkEndpoint
                    } else if (methodName in openApiOperations && methodName in implementedJvmMethodNames) {
                        val (verb, oaPath) = openApiOperations.getValue(methodName)
                        ContractEndpoint(
                            protocol = "REST",
                            methodOrAction = verb,
                            pathOrTopic = oaPath,
                            role = "CONSUMER"
                        )
                    } else {
                        null
                    }
                }
                val combinedContracts = (node.contracts + sdkCallContracts).map { c ->
                    val (resolvedDto, resolvedFields) = resolveTsDtoAndFields(c.responseDto, tsTypeDefinitions)
                    val (resolvedReqDto, resolvedReqFields) = resolveTsRequestDtoAndFields(
                        c.requestDto,
                        c.requestDtoFields,
                        tsTypeDefinitions
                    )
                    c.copy(
                        requestDto = resolvedReqDto,
                        responseDto = resolvedDto,
                        dtoFields = if (resolvedFields.isNotEmpty() || resolvedDto != c.responseDto) resolvedFields else c.dtoFields,
                        requestDtoFields = if (resolvedReqFields.isNotEmpty() || resolvedReqDto != c.requestDto) resolvedReqFields else c.requestDtoFields
                    )
                }
                nodesById[id] = node.copy(contracts = combinedContracts)
            } else if ((node.kind == NodeKind.PYTHON_CONSUMER || node.source?.language == "python") && node.contracts.isNotEmpty()) {
                val updatedContracts = node.contracts.map { c ->
                    val dtoName = c.responseDto?.let { AstTypeUnwrapper.unwrapPrimaryDtoString(it).ifEmpty { it } }
                    val enrichedFields = if (dtoName != null && dtoName in pyModelDefinitions) {
                        pyModelDefinitions[dtoName].orEmpty().ifEmpty { c.dtoFields }
                    } else {
                        c.dtoFields
                    }
                    val reqDtoName = c.requestDto?.let { AstTypeUnwrapper.unwrapPrimaryDtoString(it).ifEmpty { it } }
                    val enrichedReqFields = if (reqDtoName != null && reqDtoName in pyModelDefinitions) {
                        pyModelDefinitions[reqDtoName].orEmpty().ifEmpty { c.requestDtoFields }
                    } else {
                        c.requestDtoFields
                    }
                    val normPrefix = "/" + pyGlobalApiPrefix.orEmpty().trim('/')
                    val normPath = "/" + c.pathOrTopic.trimStart('/')
                    val enrichedPath = if (c.protocol.equals("REST", ignoreCase = true) &&
                        c.role == "PRODUCER" &&
                        !pyGlobalApiPrefix.isNullOrEmpty() &&
                        normPath != normPrefix &&
                        !normPath.startsWith("$normPrefix/")
                    ) {
                        ("/$pyGlobalApiPrefix/${c.pathOrTopic}").replace(Regex("/+"), "/")
                    } else {
                        c.pathOrTopic
                    }
                    c.copy(
                        responseDto = dtoName,
                        requestDto = reqDtoName,
                        pathOrTopic = enrichedPath,
                        dtoFields = enrichedFields,
                        requestDtoFields = enrichedReqFields
                    )
                }
                nodesById[id] = node.copy(contracts = updatedContracts)
            }
        }
    }
}
