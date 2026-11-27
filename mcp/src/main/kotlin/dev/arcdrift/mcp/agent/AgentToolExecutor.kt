package dev.arcdrift.mcp.agent

import dev.arcdrift.mcp.protocol.JsonRpcRequest
import dev.arcdrift.mcp.protocol.asJsonArrayOrNull
import dev.arcdrift.mcp.protocol.asJsonObjectOrNull
import dev.arcdrift.mcp.protocol.asJsonPrimitiveOrNull
import dev.arcdrift.mcp.server.ArcDriftMcpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File

/**
 * Executes sandboxed file and MCP tools on behalf of [OpenAiCompatibleAgentClient]
 * and builds the OpenAI function-calling tool schema.
 */
internal class AgentToolExecutor(
    private val mcpServer: ArcDriftMcpServer,
    private val projectRoot: File?,
    private val fileReader: ((File) -> String)? = null,
    private val compactJson: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }
) {

    companion object {
        private const val MAX_READ_FILE_BYTES = 512 * 1024L
        private const val MAX_WRITE_FILE_BYTES = 512 * 1024L
        private val PROTECTED_PATH_SEGMENTS = setOf(
            ".git",
            ".github",
            ".gitlab",
            ".gradle",
            ".gradle-user-home",
            ".idea"
        )
        private val PROTECTED_WRITE_FILE_NAMES = setOf(
            "gradlew",
            "gradlew.bat",
            "build.gradle",
            "build.gradle.kts",
            "settings.gradle",
            "settings.gradle.kts",
            "gradle.properties",
            ".arcdrift-policies.arc.mmd"
        )
        private val SENSITIVE_READ_FILE_NAMES = setOf(
            ".env",
            ".npmrc",
            ".pypirc",
            ".netrc",
            "local.properties"
        )
    }

    fun isProtectedReadTarget(root: File, target: File): Boolean {
        val rootCanonical = runCatching { root.canonicalFile }.getOrDefault(root.absoluteFile)
        val rel = target.relativeToOrSelf(rootCanonical).invariantSeparatorsPath
        val segments = rel.split('/').filter { it.isNotEmpty() }
        if (segments.any { it.lowercase() == ".git" }) return true
        val fileNameLower = target.name.lowercase()
        if (fileNameLower in SENSITIVE_READ_FILE_NAMES || fileNameLower.startsWith(".env.")) return true
        return false
    }

    fun isProtectedWriteTarget(root: File, target: File): Boolean {
        val rootCanonical = runCatching { root.canonicalFile }.getOrDefault(root.absoluteFile)
        val rel = target.relativeToOrSelf(rootCanonical).invariantSeparatorsPath
        val segments = rel.split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) return true
        if (segments.any { seg ->
                val lower = seg.lowercase()
                lower in PROTECTED_PATH_SEGMENTS || lower.startsWith(".gradle")
            }
        ) {
            return true
        }
        val fileNameLower = segments.last().lowercase()
        if (fileNameLower in PROTECTED_WRITE_FILE_NAMES) return true
        if (fileNameLower in SENSITIVE_READ_FILE_NAMES || fileNameLower.startsWith(".env.")) return true
        return false
    }

    fun resolveSafeRepoFile(root: File, rawPath: String): File? {
        val trimmed = rawPath.trim()
        if (trimmed.isEmpty()) return null
        val rootCanonical = runCatching { root.canonicalFile }.getOrDefault(root.absoluteFile)
        val rootPath = rootCanonical.toPath().normalize()
        val directCandidate = File(trimmed)
        val resolved = if (directCandidate.isAbsolute) {
            val absCanonical = runCatching { directCandidate.canonicalFile }.getOrNull() ?: return null
            if (!absCanonical.toPath().normalize().startsWith(rootPath)) return null
            absCanonical
        } else {
            File(rootCanonical, trimmed.removePrefix("./"))
        }
        val resolvedCanonical = runCatching { resolved.canonicalFile }.getOrNull() ?: return null
        val normalizedTarget = resolvedCanonical.toPath().normalize()
        return if (normalizedTarget.startsWith(rootPath) && normalizedTarget != rootPath) {
            resolvedCanonical
        } else {
            null
        }
    }

    fun executeAgentTool(
        name: String,
        args: JsonObject,
        onFileMutated: (File) -> Unit
    ): String {
        return when (name) {
            "read_project_file" -> {
                val relPath = args["filePath"].asJsonPrimitiveOrNull()?.contentOrNull
                    ?: return "Error: Missing 'filePath' argument."
                val root = projectRoot ?: return "Error: No project root configured."
                val target = resolveSafeRepoFile(root, relPath)
                    ?: return "Error: Invalid filePath outside repository root: $relPath"
                if (isProtectedReadTarget(root, target)) {
                    return "Error: Access to sensitive credential or VCS metadata file is blocked: $relPath"
                }
                if (!target.exists() || !target.isFile) {
                    "Error: File not found: $relPath"
                } else if (target.length() > MAX_READ_FILE_BYTES) {
                    "Error: File exceeds maximum readable size (${MAX_READ_FILE_BYTES} bytes): $relPath"
                } else {
                    runCatching {
                        fileReader?.invoke(target) ?: target.readText(Charsets.UTF_8)
                    }.getOrElse { err ->
                        "Error: Failed to read '$relPath': ${err.message}"
                    }
                }
            }
            "write_project_file" -> {
                val relPath = args["filePath"].asJsonPrimitiveOrNull()?.contentOrNull
                    ?: return "Error: Missing 'filePath' argument."
                val content = args["content"].asJsonPrimitiveOrNull()?.contentOrNull
                    ?: return "Error: Missing 'content' argument."
                val root = projectRoot ?: return "Error: No project root configured."
                val target = resolveSafeRepoFile(root, relPath)
                    ?: return "Error: Invalid filePath outside repository root: $relPath"
                if (isProtectedWriteTarget(root, target)) {
                    return "Error: Refusing to modify protected repository metadata or build configuration: $relPath"
                }
                if (target.exists() && target.isDirectory) {
                    return "Error: Target path is an existing directory: $relPath"
                }
                val contentBytes = content.toByteArray(Charsets.UTF_8).size.toLong()
                if (contentBytes > MAX_WRITE_FILE_BYTES) {
                    return "Error: Content exceeds maximum writable size (${MAX_WRITE_FILE_BYTES} bytes): $relPath"
                }
                runCatching {
                    target.parentFile?.mkdirs()
                    target.writeText(content, Charsets.UTF_8)
                    onFileMutated(target)
                    "Successfully wrote ${content.length} chars to $relPath."
                }.getOrElse { err ->
                    "Error: Failed to write '$relPath': ${err.message}"
                }
            }
            else -> {
                val out = callMcpTool(name, args)
                val root = projectRoot
                val previewOnly = args["previewOnly"].asJsonPrimitiveOrNull()?.booleanOrNull ?: false
                if (name == "execute_deterministic_refactoring" && root != null && !previewOnly) {
                    runCatching {
                        val parsed = compactJson.parseToJsonElement(out).asJsonObjectOrNull()
                        val succeeded = parsed?.get("success").asJsonPrimitiveOrNull()?.booleanOrNull ?: false
                        if (succeeded) {
                            val touched = parsed?.get("touchedFiles").asJsonArrayOrNull().orEmpty()
                            for (elem in touched) {
                                val rel = elem.asJsonPrimitiveOrNull()?.contentOrNull ?: continue
                                val f = resolveSafeRepoFile(root, rel) ?: continue
                                onFileMutated(f)
                            }
                        }
                    }
                }
                out
            }
        }
    }

    fun callMcpTool(name: String, args: JsonObject): String {
        val rpcReq = JsonRpcRequest(
            id = JsonPrimitive(1),
            method = "tools/call",
            params = buildJsonObject {
                put("name", name)
                put("arguments", args)
            }
        )
        val resp = mcpServer.handleRequest(rpcReq)
        val resultObj = resp?.result.asJsonObjectOrNull()
        val firstText = resultObj
            ?.get("content").asJsonArrayOrNull()
            ?.firstOrNull().asJsonObjectOrNull()
            ?.get("text").asJsonPrimitiveOrNull()?.contentOrNull
        return firstText ?: resp?.error?.message ?: "{}"
    }

    fun buildOpenAiToolsSchema(): JsonArray = buildJsonArray {
        add(
            buildToolDef(
                name = "verify_architectural_compliance",
                description = "Verifies the repository against circular dependencies, .arc.mmd boundary rules, and cross-language REST/Kafka DTO contracts.",
                properties = buildJsonObject {
                    putJsonObject("includeContracts") {
                        put("type", "boolean")
                        put("description", "Verify cross-language contracts (default true).")
                    }
                }
            )
        )
        add(
            buildToolDef(
                name = "get_architecture_slice",
                description = "Returns the architectural graph slice (nodes with source file paths, edges, and boundary rules).",
                properties = buildJsonObject {
                    putJsonObject("scope") {
                        put("type", "string")
                        put("description", "Optional component or package name substring.")
                    }
                }
            )
        )
        add(
            buildToolDef(
                name = "read_project_file",
                description = "Reads the full text content of a source file in the repository.",
                required = listOf("filePath"),
                properties = buildJsonObject {
                    putJsonObject("filePath") {
                        put("type", "string")
                        put("description", "Relative file path from project root (e.g. 'src/main/kotlin/com/acme/controller/OrderController.kt').")
                    }
                }
            )
        )
        add(
            buildToolDef(
                name = "write_project_file",
                description = "Writes updated source code to a file in the repository and refreshes the architecture graph.",
                required = listOf("filePath", "content"),
                properties = buildJsonObject {
                    putJsonObject("filePath") {
                        put("type", "string")
                        put("description", "Relative file path from project root.")
                    }
                    putJsonObject("content") {
                        put("type", "string")
                        put("description", "Complete updated file content.")
                    }
                }
            )
        )
        add(
            buildToolDef(
                name = "execute_deterministic_refactoring",
                description = "Executes a deterministic refactoring command (EXTRACT_INTERFACE, RENAME_SYMBOL, MOVE_CLASS_OR_PACKAGE, CREATE_NODE, ADD_DEPENDENCY).",
                required = listOf("command"),
                properties = buildJsonObject {
                    putJsonObject("command") {
                        put("type", "object")
                        put("description", "RefactoringCommand object with 'type' discriminator.")
                        putJsonArray("required") { add(JsonPrimitive("type")) }
                        putJsonObject("properties") {
                            putJsonObject("type") {
                                put("type", "string")
                                put("description", "One of: EXTRACT_INTERFACE, RENAME_SYMBOL, MOVE_CLASS_OR_PACKAGE, CREATE_NODE, ADD_DEPENDENCY")
                            }
                            putJsonObject("sourceClassId") {
                                put("type", "string")
                                put("description", "Required for EXTRACT_INTERFACE and ADD_DEPENDENCY: fully qualified source class ID (e.g. 'com.acme.repo.OrderRepository').")
                            }
                            putJsonObject("interfaceSimpleName") {
                                put("type", "string")
                                put("description", "Required for EXTRACT_INTERFACE: simple name of the interface to extract (e.g. 'OrderRepositoryPort').")
                            }
                            putJsonObject("targetPackage") {
                                put("type", "string")
                                put("description", "Required for EXTRACT_INTERFACE: package for the extracted interface (e.g. 'com.acme.service').")
                            }
                            putJsonObject("memberNames") {
                                put("type", "array")
                                putJsonObject("items") { put("type", "string") }
                                put("description", "Optional for EXTRACT_INTERFACE: method names to extract (empty list extracts all public methods).")
                            }
                            putJsonObject("targetNodeId") {
                                put("type", "string")
                                put("description", "Required for RENAME_SYMBOL and MOVE_CLASS_OR_PACKAGE: fully qualified node ID.")
                            }
                            putJsonObject("newSimpleName") {
                                put("type", "string")
                                put("description", "Required for RENAME_SYMBOL: new simple class name.")
                            }
                            putJsonObject("destinationPackage") {
                                put("type", "string")
                                put("description", "Required for MOVE_CLASS_OR_PACKAGE: target package name.")
                            }
                            putJsonObject("fqName") {
                                put("type", "string")
                                put("description", "Required for CREATE_NODE: fully qualified name of the new class/interface.")
                            }
                            putJsonObject("kind") {
                                put("type", "string")
                                put("description", "Optional for CREATE_NODE: CLASS, INTERFACE, DATA_CLASS, SERVICE_BEAN, REST_CONTROLLER, REPOSITORY, or GHOST_SPEC.")
                            }
                            putJsonObject("stereotypes") {
                                put("type", "array")
                                putJsonObject("items") { put("type", "string") }
                                put("description", "Optional for CREATE_NODE: annotations or stereotypes (e.g. ['@Service']).")
                            }
                            putJsonObject("targetTypeFqName") {
                                put("type", "string")
                                put("description", "Required for ADD_DEPENDENCY: fully qualified target type.")
                            }
                            putJsonObject("relationKind") {
                                put("type", "string")
                                put("description", "Optional for ADD_DEPENDENCY: DEPENDS_ON, IMPLEMENTS, or EXTENDS.")
                            }
                        }
                    }
                    putJsonObject("previewOnly") {
                        put("type", "boolean")
                        put("description", "If true, simulates the refactoring without mutating files (default false).")
                    }
                }
            )
        )
    }

    private fun buildToolDef(
        name: String,
        description: String,
        required: List<String> = emptyList(),
        properties: JsonObject
    ): JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", name)
            put("description", description)
            putJsonObject("parameters") {
                put("type", "object")
                put("properties", properties)
                if (required.isNotEmpty()) {
                    putJsonArray("required") {
                        required.forEach { add(JsonPrimitive(it)) }
                    }
                }
            }
        }
    }
}
