package dev.arcdrift.core.scanner

import java.io.File
import java.nio.file.Files

data class OpenApiExtractionResult(
    val operations: Map<String, Pair<String, String>>,
    val schemas: Map<String, Map<String, String>>
)

/**
 * Extracts REST operationIds and component/model schemas from OpenAPI YAML (`*.yaml`, `*.yml`)
 * and TypeSpec (`*.tsp`) files, including `allOf` / `extends` / `...Spread` field propagation.
 */
object OpenApiAndTypeSpecExtractor {

    private val HTTP_VERBS = setOf("get", "post", "put", "delete", "patch")
    private val TSP_ROUTE_REGEX = Regex("""@route\(\s*"([^"]+)"\s*\)""")
    private val TSP_OP_ID_REGEX = Regex("""@operationId\(\s*"([^"]+)"\s*\)""")
    private val TSP_VERB_REGEX = Regex("""(?:^|\s)@(get|post|put|delete|patch)\b""")
    private val TSP_OP_DECL_REGEX = Regex("""^(?:op\s+)?([a-zA-Z_][a-zA-Z0-9_]*)\s*\(""")
    private val TSP_FIELD_REGEX = Regex("""^([a-zA-Z_][a-zA-Z0-9_]*)\??\s*:\s*([^;|={}]+);?$""")
    private val BLOCK_COMMENT_REGEX = Regex("""/\*[\s\S]*?\*/""")

    fun extract(
        rootDir: File,
        ignoredDirs: Set<String>,
        fileContentOverrides: Map<String, String> = emptyMap(),
        ignoreConfig: ArcDriftIgnore = ArcDriftIgnore.EMPTY
    ): OpenApiExtractionResult {
        val operations = linkedMapOf<String, Pair<String, String>>()
        val schemas = linkedMapOf<String, MutableMap<String, String>>()
        val schemaParents = linkedMapOf<String, MutableList<String>>()

        extractOpenApiYaml(rootDir, ignoredDirs, fileContentOverrides, operations, schemas, schemaParents, ignoreConfig)
        extractTypeSpec(rootDir, ignoredDirs, fileContentOverrides, operations, schemas, schemaParents, ignoreConfig)
        propagateParentsAndAliases(schemas, schemaParents)

        return OpenApiExtractionResult(operations = operations, schemas = schemas)
    }

    private fun readOverriddenLines(rootDir: File, file: File, overrides: Map<String, String>): List<String> {
        val relPath = runCatching { file.relativeTo(rootDir).invariantSeparatorsPath }.getOrNull()
        val overridden = (if (relPath != null) overrides[relPath] else null)
            ?: overrides[file.invariantSeparatorsPath]
            ?: overrides[file.name]
        if (overridden != null) return overridden.lines()
        return runCatching { file.readLines(Charsets.UTF_8) }.getOrDefault(emptyList())
    }

    private fun isIgnoredByConfig(file: File, rootDir: File, ignoreConfig: ArcDriftIgnore): Boolean {
        if (ignoreConfig === ArcDriftIgnore.EMPTY) return false
        val rel = runCatching { file.relativeTo(rootDir).invariantSeparatorsPath }.getOrNull() ?: return false
        if (rel.isEmpty()) return false
        val relPath = if (file.isDirectory) "$rel/" else rel
        return ignoreConfig.isPathIgnored(relPath) || ignoreConfig.isPathIgnored(rel)
    }

    private fun isSafeWalkDirectory(
        dir: File,
        rootDir: File,
        ignoredDirs: Set<String>,
        visited: MutableSet<String>,
        ignoreConfig: ArcDriftIgnore = ArcDriftIgnore.EMPTY
    ): Boolean {
        if (dir.name in ignoredDirs) return false
        if (dir != rootDir && isIgnoredByConfig(dir, rootDir, ignoreConfig)) return false
        if (dir != rootDir && runCatching { Files.isSymbolicLink(dir.toPath()) }.getOrDefault(false)) return false
        val canonical = runCatching { dir.canonicalPath }.getOrNull() ?: return false
        return visited.add(canonical)
    }

    private fun unquoteYamlKey(raw: String): String =
        raw.trim().removeSurrounding("\"").removeSurrounding("'").trim()

    private fun extractOpenApiYaml(
        rootDir: File,
        ignoredDirs: Set<String>,
        fileContentOverrides: Map<String, String>,
        operations: MutableMap<String, Pair<String, String>>,
        schemas: MutableMap<String, MutableMap<String, String>>,
        schemaParents: MutableMap<String, MutableList<String>>,
        ignoreConfig: ArcDriftIgnore = ArcDriftIgnore.EMPTY
    ) {
        val visitedDirs = HashSet<String>()
        val yamlFiles = rootDir.walkTopDown()
            .onEnter { isSafeWalkDirectory(it, rootDir, ignoredDirs, visitedDirs, ignoreConfig) }
            .filter { f ->
                f.isFile &&
                    !isIgnoredByConfig(f, rootDir, ignoreConfig) &&
                    !runCatching { Files.isSymbolicLink(f.toPath()) }.getOrDefault(false) &&
                    (f.extension.equals("yml", true) || f.extension.equals("yaml", true)) &&
                    (f.name.contains("openapi", true) || f.name.contains("api", true) || f.name.contains("swagger", true))
            }
            .toList()

        for (yf in yamlFiles) {
            val lines = readOverriddenLines(rootDir, yf, fileContentOverrides)
            if (lines.none { it.trimStart().startsWith("openapi:") || it.trimStart().startsWith("paths:") }) continue

            var currentPath: String? = null
            var currentVerb: String? = null
            var inPaths = false
            var pathKeyIndent = -1
            var verbKeyIndent = -1
            var inComponents = false
            var inSchemas = false
            var schemasIndent = -1
            var schemaKeyIndent = -1
            var currentSchema: String? = null
            var propertiesIndent = -1
            var propertyKeyIndent = -1
            var currentProperty: String? = null
            var currentPropertyIsArray = false
            var nestedSubPropertiesIndent = -1

            for (line in lines) {
                val rawTrimmed = line.trim()
                if (rawTrimmed.isEmpty() || rawTrimmed.startsWith("#")) continue
                val trimmed = stripYamlInlineComment(rawTrimmed)
                if (trimmed.isEmpty()) continue
                val indent = line.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) 999 else it }
                if (indent == 0 && trimmed.startsWith("paths:")) {
                    inPaths = true
                    pathKeyIndent = -1
                    verbKeyIndent = -1
                    inComponents = false
                    inSchemas = false
                    continue
                } else if (indent == 0 && trimmed.startsWith("components:")) {
                    inPaths = false
                    inComponents = true
                    inSchemas = false
                    schemasIndent = -1
                    schemaKeyIndent = -1
                    continue
                } else if (indent == 0 && trimmed.endsWith(":")) {
                    inPaths = false
                    inComponents = false
                    inSchemas = false
                }
                if (inPaths) {
                    if (trimmed.endsWith(":")) {
                        val candidateKey = unquoteYamlKey(trimmed.removeSuffix(":"))
                        if (candidateKey.startsWith("/") && (pathKeyIndent < 0 || indent == pathKeyIndent)) {
                            pathKeyIndent = indent
                            verbKeyIndent = -1
                            currentPath = candidateKey
                            currentVerb = null
                            continue
                        }
                    }
                    if (pathKeyIndent >= 0 && indent > pathKeyIndent &&
                        unquoteYamlKey(trimmed.removeSuffix(":")).lowercase() in HTTP_VERBS &&
                        trimmed.endsWith(":") &&
                        (verbKeyIndent < 0 || indent == verbKeyIndent)
                    ) {
                        verbKeyIndent = indent
                        currentVerb = unquoteYamlKey(trimmed.removeSuffix(":")).uppercase()
                    } else if (trimmed.startsWith("operationId:")) {
                        val opId = unquoteYamlKey(trimmed.substringAfter("operationId:"))
                        if (opId.isNotEmpty() && currentPath != null && currentVerb != null) {
                            operations[opId] = currentVerb to currentPath
                        }
                    }
                } else if (inComponents) {
                    if (indent > 0 && trimmed.startsWith("schemas:") && (schemasIndent < 0 || indent == schemasIndent)) {
                        inSchemas = true
                        schemasIndent = indent
                        schemaKeyIndent = -1
                        currentSchema = null
                        propertiesIndent = -1
                        currentProperty = null
                        currentPropertyIsArray = false
                        nestedSubPropertiesIndent = -1
                        continue
                    } else if (schemasIndent > 0 && indent == schemasIndent && trimmed.endsWith(":")) {
                        inSchemas = false
                    }
                    if (inSchemas) {
                        if (indent > schemasIndent && trimmed.endsWith(":") && !trimmed.startsWith("-") &&
                            (schemaKeyIndent < 0 || indent == schemaKeyIndent)
                        ) {
                            schemaKeyIndent = indent
                            currentSchema = unquoteYamlKey(trimmed.removeSuffix(":"))
                            propertiesIndent = -1
                            currentProperty = null
                            currentPropertyIsArray = false
                            nestedSubPropertiesIndent = -1
                        } else if (currentSchema != null && schemaKeyIndent > 0 && indent > schemaKeyIndent) {
                            // First: reset `propertiesIndent` whenever indentation returns to or above `propertiesIndent`
                            // (including `- $ref:` list items inside `allOf` following `- properties:`)
                            if (propertiesIndent > 0 && indent <= propertiesIndent) {
                                propertiesIndent = -1
                                propertyKeyIndent = -1
                                currentProperty = null
                                currentPropertyIsArray = false
                                nestedSubPropertiesIndent = -1
                            }

                            val normalizedKey = trimmed.removePrefix("-").trim()
                            if (propertiesIndent < 0 && normalizedKey == "properties:") {
                                propertiesIndent = indent
                                propertyKeyIndent = -1
                                currentProperty = null
                                currentPropertyIsArray = false
                                nestedSubPropertiesIndent = -1
                            } else if (propertiesIndent < 0 && trimmed.contains("\$ref:") && trimmed.contains("#/components/schemas/")) {
                                val refPart = trimmed.substringAfter("#/components/schemas/")
                                    .substringBefore('"')
                                    .substringBefore('\'')
                                    .substringBefore('}')
                                    .substringBefore(']')
                                    .trim()
                                if (refPart.isNotEmpty()) {
                                    schemaParents.getOrPut(currentSchema) { mutableListOf() }.add(refPart)
                                }
                            } else if (propertiesIndent > 0) {
                                if (nestedSubPropertiesIndent > 0 && indent <= nestedSubPropertiesIndent) {
                                    nestedSubPropertiesIndent = -1
                                }
                                if (nestedSubPropertiesIndent > 0) {
                                    // Inside nested `object` sub-properties (`address.properties.street`) — do not overwrite outer property type
                                    continue
                                }
                                if (indent > propertiesIndent && trimmed.endsWith(":") && !trimmed.startsWith("-") &&
                                    (propertyKeyIndent < 0 || indent == propertyKeyIndent)
                                ) {
                                    propertyKeyIndent = indent
                                    currentProperty = unquoteYamlKey(trimmed.removeSuffix(":"))
                                    currentPropertyIsArray = false
                                    nestedSubPropertiesIndent = -1
                                } else if (propertyKeyIndent > 0 && indent > propertyKeyIndent) {
                                    val propName = currentProperty ?: continue
                                    if (trimmed == "properties:") {
                                        nestedSubPropertiesIndent = indent
                                    } else if (trimmed.startsWith("type:")) {
                                        val propType = unquoteYamlKey(trimmed.substringAfter("type:"))
                                        if (propType == "array") {
                                            currentPropertyIsArray = true
                                            val schemaFields = schemas.getOrPut(currentSchema) { linkedMapOf() }
                                            val existing = schemaFields[propName]
                                            if (existing == null) {
                                                schemaFields[propName] = "any[]"
                                            } else if (!existing.endsWith("[]")) {
                                                schemaFields[propName] = "$existing[]"
                                            }
                                        } else if (propType.isNotEmpty()) {
                                            val storedType = if (currentPropertyIsArray) "$propType[]" else propType
                                            schemas.getOrPut(currentSchema) { linkedMapOf() }[propName] = storedType
                                        }
                                    } else if (trimmed.contains("\$ref:") && trimmed.contains("#/components/schemas/")) {
                                        val refTarget = trimmed.substringAfterLast("#/components/schemas/")
                                            .trim(' ', '"', '\'', '}', ']')
                                        if (refTarget.isNotEmpty()) {
                                            val storedType = if (currentPropertyIsArray) "$refTarget[]" else refTarget
                                            schemas.getOrPut(currentSchema) { linkedMapOf() }[propName] = storedType
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

    private fun extractTypeSpec(
        rootDir: File,
        ignoredDirs: Set<String>,
        fileContentOverrides: Map<String, String>,
        operations: MutableMap<String, Pair<String, String>>,
        schemas: MutableMap<String, MutableMap<String, String>>,
        schemaParents: MutableMap<String, MutableList<String>>,
        ignoreConfig: ArcDriftIgnore = ArcDriftIgnore.EMPTY
    ) {
        val visitedDirs = HashSet<String>()
        val tspFiles = rootDir.walkTopDown()
            .onEnter { isSafeWalkDirectory(it, rootDir, ignoredDirs, visitedDirs, ignoreConfig) }
            .filter {
                it.isFile &&
                    !isIgnoredByConfig(it, rootDir, ignoreConfig) &&
                    !runCatching { Files.isSymbolicLink(it.toPath()) }.getOrDefault(false) &&
                    it.extension.equals("tsp", true)
            }
            .toList()

        for (tf in tspFiles) {
            val rawText = readOverriddenLines(rootDir, tf, fileContentOverrides).joinToString("\n")
            val uncommentedLines = BLOCK_COMMENT_REGEX.replace(rawText, "").lines()
            var inInterface = false
            var interfaceBraceDepth = 0
            var inModel = false
            var modelBraceDepth = 0
            var currentModelName: String? = null
            var interfaceRoute = ""
            var pendingRoute: String? = null
            var pendingVerb: String? = null
            var pendingOpId: String? = null

            for (rawLine in uncommentedLines) {
                val trimmed = stripTypeSpecLineComment(rawLine).trim()
                if (trimmed.isEmpty()) continue

                val modelMatch = Regex("""^model\s+([a-zA-Z_][a-zA-Z0-9_]*)(?:<[^>]*>)?(?:\s+extends\s+([a-zA-Z_][a-zA-Z0-9_.]*)(?:<[^>]*>)?)?\s*\{(.*)$""").find(trimmed)
                if (!inInterface && !inModel && modelMatch != null) {
                    val mName = modelMatch.groupValues[1].trim()
                    val parent = modelMatch.groupValues[2].trim().substringAfterLast('.')
                    val remainder = modelMatch.groupValues[3].trim()
                    if (mName.isNotEmpty()) {
                        if (parent.isNotEmpty()) {
                            schemaParents.getOrPut(mName) { mutableListOf() }.add(parent)
                        }
                        val netBraces = 1 + remainder.count { it == '{' } - remainder.count { it == '}' }
                        if (remainder.isNotEmpty()) {
                            val inlineBody = remainder.substringBeforeLast('}').trim()
                            parseTypeSpecModelEntries(inlineBody, mName, schemas, schemaParents)
                        }
                        if (netBraces > 0) {
                            inModel = true
                            modelBraceDepth = netBraces
                            currentModelName = mName
                        }
                    }
                    continue
                }
                if (inModel) {
                    val opens = trimmed.count { it == '{' }
                    val closes = trimmed.count { it == '}' }
                    val prevDepth = modelBraceDepth
                    modelBraceDepth += (opens - closes)
                    if (prevDepth == 1 && modelBraceDepth <= 1) {
                        val contentBeforeClose = if (trimmed.endsWith("}")) trimmed.removeSuffix("}").trim() else trimmed
                        val modelName = currentModelName
                        if (modelName != null && contentBeforeClose.isNotEmpty() && !contentBeforeClose.contains('{')) {
                            parseTypeSpecModelEntries(contentBeforeClose, modelName, schemas, schemaParents)
                        }
                    }
                    if (modelBraceDepth <= 0) {
                        inModel = false
                        modelBraceDepth = 0
                        currentModelName = null
                    }
                    continue
                }

                TSP_ROUTE_REGEX.find(trimmed)?.let { pendingRoute = it.groupValues[1] }
                TSP_OP_ID_REGEX.find(trimmed)?.let { pendingOpId = it.groupValues[1] }
                TSP_VERB_REGEX.find(trimmed.replace(Regex(""""(?:\\.|[^"\\])*""""), "\"\""))?.let {
                    pendingVerb = it.groupValues[1].uppercase()
                }

                if (!inInterface && Regex("""^interface\s+[a-zA-Z_][a-zA-Z0-9_]*\s*\{""").containsMatchIn(trimmed)) {
                    val net = trimmed.count { it == '{' } - trimmed.count { it == '}' }
                    if (net > 0) {
                        inInterface = true
                        interfaceBraceDepth = net
                        interfaceRoute = pendingRoute.orEmpty()
                    }
                    pendingRoute = null
                    pendingVerb = null
                    pendingOpId = null
                    continue
                }
                if (inInterface) {
                    val net = trimmed.count { it == '{' } - trimmed.count { it == '}' }
                    if (trimmed == "}" || (interfaceBraceDepth + net) <= 0) {
                        inInterface = false
                        interfaceBraceDepth = 0
                        interfaceRoute = ""
                        pendingRoute = null
                        pendingVerb = null
                        pendingOpId = null
                        continue
                    }
                    interfaceBraceDepth += net
                }

                val withoutLeadingDecorators = trimmed.replace(Regex("""^(?:@[a-zA-Z_][a-zA-Z0-9_.]*(?:\([^)]*\))?\s+)+"""), "")
                val declMatch = TSP_OP_DECL_REGEX.find(withoutLeadingDecorators)
                if (declMatch != null && (inInterface || Regex("""^op\s+""").containsMatchIn(withoutLeadingDecorators))) {
                    val methodName = declMatch.groupValues[1]
                    val opId = pendingOpId ?: methodName
                    val verb = pendingVerb ?: "GET"
                    val sub = pendingRoute.orEmpty()
                    val fullPath = if (inInterface) {
                        ("/$interfaceRoute/$sub")
                            .replace(Regex("/+"), "/")
                            .let { if (it.length > 1 && sub.isEmpty()) it.removeSuffix("/") else it }
                    } else {
                        pendingRoute ?: "/$opId"
                    }
                    operations[opId] = verb to fullPath
                    pendingRoute = null
                    pendingVerb = null
                    pendingOpId = null
                }
            }
        }
    }

    private fun parseTypeSpecModelEntries(
        segmentText: String,
        modelName: String,
        schemas: MutableMap<String, MutableMap<String, String>>,
        schemaParents: MutableMap<String, MutableList<String>>
    ) {
        for (rawPart in segmentText.split(';')) {
            val part = rawPart.trim()
            if (part.isEmpty()) continue
            if (part.startsWith("...")) {
                val spreadTarget = part.removePrefix("...").trim().substringAfterLast('.')
                if (spreadTarget.isNotEmpty()) {
                    schemaParents.getOrPut(modelName) { mutableListOf() }.add(spreadTarget)
                }
            } else {
                val fieldMatch = TSP_FIELD_REGEX.matchEntire(part)
                if (fieldMatch != null) {
                    val fName = fieldMatch.groupValues[1].trim()
                    val fType = fieldMatch.groupValues[2].trim()
                    if (fName.isNotEmpty() && fType.isNotEmpty()) {
                        schemas.getOrPut(modelName) { linkedMapOf() }[fName] = fType
                    }
                }
            }
        }
    }

    private fun propagateParentsAndAliases(
        schemas: MutableMap<String, MutableMap<String, String>>,
        schemaParents: Map<String, List<String>>
    ) {
        for (pass in 0 until 4) {
            var changed = false
            for ((schemaName, parents) in schemaParents) {
                val inherited = linkedMapOf<String, String>()
                for (p in parents) {
                    schemas[p]?.let { inherited.putAll(it) }
                }
                if (inherited.isNotEmpty()) {
                    val existing = schemas[schemaName].orEmpty()
                    val merged = linkedMapOf<String, String>().apply {
                        putAll(inherited)
                        putAll(existing)
                    }
                    if (merged != existing) {
                        schemas[schemaName] = merged
                        changed = true
                    }
                }
            }
            if (!changed) break
        }
        for ((schemaName, fieldMap) in schemas.entries.toList()) {
            if (fieldMap.isNotEmpty() && !schemaName.endsWith("Dto", ignoreCase = true)) {
                schemas.putIfAbsent("${schemaName}Dto", fieldMap.toMutableMap())
                schemas.putIfAbsent("${schemaName}DTO", fieldMap.toMutableMap())
            }
        }
    }

    private fun stripTypeSpecLineComment(line: String): String {
        var activeQuote: Char? = null
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            if (activeQuote != null) {
                if (ch == '\\' && i + 1 < line.length) {
                    i += 2
                    continue
                }
                if (ch == activeQuote) {
                    activeQuote = null
                }
                i++
                continue
            }
            if (ch == '"' || ch == '\'') {
                activeQuote = ch
                i++
                continue
            }
            if (ch == '/' && i + 1 < line.length && line[i + 1] == '/') {
                return line.substring(0, i)
            }
            i++
        }
        return line
    }

    private fun stripYamlInlineComment(line: String): String {
        var inSingle = false
        var inDouble = false
        for (i in line.indices) {
            when (line[i]) {
                '\'' -> if (!inDouble) inSingle = !inSingle
                '"' -> if (!inSingle) inDouble = !inDouble
                '#' -> if (!inSingle && !inDouble && (i == 0 || line[i - 1].isWhitespace())) {
                    return line.substring(0, i).trim()
                }
            }
        }
        return line.trim()
    }
}
