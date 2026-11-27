package dev.arcdrift.mcp.agent

import dev.arcdrift.mcp.protocol.asJsonArrayOrNull
import dev.arcdrift.mcp.protocol.asJsonObjectOrNull
import dev.arcdrift.mcp.protocol.asJsonPrimitiveOrNull
import dev.arcdrift.mcp.server.ArcDriftMcpServer
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

@Serializable
data class AgentStepEvent(
    val kind: String, // "status" | "turn" | "assistant" | "tool_call" | "tool_result" | "complete" | "error"
    val title: String,
    val detail: String = "",
    val turn: Int = 0,
    val maxTurns: Int = 30,
    val done: Boolean = false,
    val success: Boolean = true
)

@Serializable
data class AgentRunReport(
    val success: Boolean,
    val model: String,
    val stepsExecuted: Int,
    val finalReply: String,
    val events: List<AgentStepEvent>
)

/**
 * Standard OpenAI-compatible Chat Completions & Tool-Calling Agent Client (`/v1/models`, `/v1/chat/completions`).
 * Works with any OpenAI-compatible endpoint (OpenAI, llama-swap, llama.cpp server, vLLM, Ollama, LiteLLM, OpenRouter).
 */
class OpenAiCompatibleAgentClient(
    private val mcpServer: ArcDriftMcpServer,
    private val projectRoot: File?,
    private val fileReader: ((File) -> String)? = null
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    private val compactJson = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    private val transport = AgentHttpTransport(compactJson = compactJson)
    private val toolExecutor = AgentToolExecutor(
        mcpServer = mcpServer,
        projectRoot = projectRoot,
        fileReader = fileReader,
        compactJson = compactJson
    )

    fun normalizeV1Base(rawUrl: String): String = transport.normalizeV1Base(rawUrl)

    fun fetchModels(rawUrl: String, apiKey: String? = null): Result<List<String>> =
        transport.fetchModels(rawUrl, apiKey)

    fun runAgentLoop(
        rawUrl: String,
        model: String,
        taskPrompt: String,
        apiKey: String? = null,
        maxTurns: Int = 30,
        cancelFlag: AtomicBoolean = AtomicBoolean(false),
        onStepEvent: (AgentStepEvent) -> Unit = {},
        onFileMutated: (File) -> Unit = {}
    ): AgentRunReport {
        val effectiveMaxTurns = maxTurns.coerceIn(1, 100)
        val events = mutableListOf<AgentStepEvent>()

        fun emit(
            kind: String,
            title: String,
            detail: String = "",
            turn: Int = 0,
            done: Boolean = false,
            success: Boolean = true
        ) {
            val ev = AgentStepEvent(
                kind = kind,
                title = title,
                detail = detail,
                turn = turn,
                maxTurns = effectiveMaxTurns,
                done = done,
                success = success
            )
            events.add(ev)
            onStepEvent(ev)
        }

        val v1 = try {
            normalizeV1Base(rawUrl)
        } catch (e: Exception) {
            val err = "Invalid agent endpoint URL: ${e.message}"
            emit(kind = "error", title = "Invalid endpoint URL", detail = err, turn = 0, done = true, success = false)
            return AgentRunReport(false, model.trim().ifEmpty { "default" }, 0, err, events)
        }
        val availableModels = fetchModels(rawUrl, apiKey).getOrNull().orEmpty()
        val requestedModel = model.trim()
        val rankedModels = rankModelsBySimilarity(requestedModel, availableModels)
        val failedModels = mutableSetOf<String>()
        var chosenModel = when {
            requestedModel.isEmpty() -> rankedModels.firstOrNull() ?: "default"
            availableModels.isEmpty() || requestedModel in availableModels -> requestedModel
            else -> {
                val matched = rankedModels.first()
                emit(
                    kind = "status",
                    title = "Model '$requestedModel' not found on endpoint — auto-selected '$matched'",
                    detail = "Available /v1/models: ${availableModels.joinToString(", ")}"
                )
                matched
            }
        }

        emit(
            kind = "status",
            title = "Connected to $v1 · Model: $chosenModel",
            detail = "Max turns: $effectiveMaxTurns"
        )

        val complianceInitial = callMcpTool("verify_architectural_compliance", buildJsonObject {})
        val systemPrompt = buildString {
            appendLine("You are the ArcDrift Architectural Self-Healing Agent connected via MCP.")
            appendLine("Your goal is to resolve all architectural boundary/contract violations and implement any requested Ghost Spec components in the repository.")
            appendLine("Available tools:")
            appendLine("- verify_architectural_compliance: Check current rule & contract violations.")
            appendLine("- get_architecture_slice: Inspect nodes, edges, and source file paths.")
            appendLine("- execute_deterministic_refactoring: Run RENAME_SYMBOL, MOVE_CLASS_OR_PACKAGE, EXTRACT_INTERFACE, CREATE_NODE, or ADD_DEPENDENCY.")
            appendLine("- read_project_file: Read a source file by relative path.")
            appendLine("- write_project_file: Write updated code to a source file by relative path.")
            appendLine()
            appendLine("Always verify your fixes by calling verify_architectural_compliance before finishing.")
            appendLine("Format your explanations and final summary in clear GitHub-style Markdown, referencing any inspected or modified files in backticks (e.g. `src/main/kotlin/com/acme/controller/OrderController.kt` or `ordersClient.ts`).")
        }

        val messages = mutableListOf<JsonObject>(
            buildJsonObject {
                put("role", "system")
                put("content", systemPrompt)
            },
            buildJsonObject {
                put("role", "user")
                put(
                    "content",
                    buildString {
                        appendLine("Task:")
                        appendLine(taskPrompt)
                        appendLine()
                        appendLine("Current Architectural Compliance Report:")
                        appendLine(complianceInitial)
                    }
                )
            }
        )

        val toolsArray = toolExecutor.buildOpenAiToolsSchema()
        var steps = 0

        while (steps < effectiveMaxTurns) {
            if (cancelFlag.get()) {
                val msg = "Agent run cancelled by user at turn $steps."
                emit(kind = "error", title = msg, turn = steps, done = true, success = false)
                return AgentRunReport(false, chosenModel, steps, msg, events)
            }

            steps++
            emit(
                kind = "turn",
                title = "Turn $steps of $effectiveMaxTurns — Awaiting response from $chosenModel...",
                turn = steps
            )

            val reqBody = buildJsonObject {
                put("model", chosenModel)
                put("messages", JsonArray(messages))
                put("tools", toolsArray)
                put("tool_choice", "auto")
                put("temperature", 0.1)
            }

            val resp = try {
                transport.postChatCompletion(v1, compactJson.encodeToString(reqBody), apiKey, cancelFlag)
            } catch (_: CancellationException) {
                val msg = "Agent run cancelled by user at turn $steps."
                emit(kind = "error", title = msg, turn = steps, done = true, success = false)
                return AgentRunReport(false, chosenModel, steps, msg, events)
            } catch (e: Exception) {
                val err = "Connection to $v1 failed: ${e.message}"
                emit(kind = "error", title = "Connection failed", detail = err, turn = steps, done = true, success = false)
                return AgentRunReport(false, chosenModel, steps, err, events)
            }

            if (resp.statusCode() !in 200..299) {
                val bodyPreview = resp.body().take(800)
                val isModelSelectionError = resp.statusCode() in setOf(400, 404, 422)
                if (steps == 1 && isModelSelectionError && failedModels.size < 3) {
                    failedModels.add(chosenModel)
                    val fallbackCandidate = rankedModels.firstOrNull { it !in failedModels }
                    if (fallbackCandidate != null) {
                        val prevModel = chosenModel
                        chosenModel = fallbackCandidate
                        emit(
                            kind = "status",
                            title = "Model '$prevModel' returned HTTP ${resp.statusCode()} — auto-selected '$chosenModel'",
                            detail = bodyPreview
                        )
                        steps--
                        continue
                    }
                }
                val err = "HTTP ${resp.statusCode()} from $v1/chat/completions:\n$bodyPreview"
                emit(kind = "error", title = "Endpoint returned HTTP ${resp.statusCode()}", detail = err, turn = steps, done = true, success = false)
                return AgentRunReport(false, chosenModel, steps, err, events)
            }

            val respObj = runCatching { compactJson.parseToJsonElement(resp.body()).asJsonObjectOrNull() }.getOrNull()
                ?: run {
                    val err = "Invalid JSON response from endpoint"
                    emit(kind = "error", title = err, detail = resp.body().take(500), turn = steps, done = true, success = false)
                    return AgentRunReport(false, chosenModel, steps, err, events)
                }

            val choiceMsg = respObj["choices"].asJsonArrayOrNull()
                ?.firstOrNull().asJsonObjectOrNull()
                ?.get("message").asJsonObjectOrNull()
                ?: run {
                    val err = "Missing choices[0].message in response"
                    emit(kind = "error", title = err, detail = resp.body().take(500), turn = steps, done = true, success = false)
                    return AgentRunReport(false, chosenModel, steps, err, events)
                }

            val contentText = choiceMsg["content"].asJsonPrimitiveOrNull()?.contentOrNull?.trim().orEmpty()
            val reasoningText = choiceMsg["reasoning_content"].asJsonPrimitiveOrNull()?.contentOrNull?.trim().orEmpty()
            val rawToolCalls = choiceMsg["tool_calls"].asJsonArrayOrNull()

            data class NormalizedToolCall(
                val id: String,
                val name: String,
                val rawArgs: String,
                val jsonObject: JsonObject
            )

            val normalizedToolCalls = rawToolCalls.orEmpty().mapIndexedNotNull { idx, tc ->
                val tcObj = tc.asJsonObjectOrNull() ?: return@mapIndexedNotNull null
                val fnObj = tcObj["function"].asJsonObjectOrNull() ?: return@mapIndexedNotNull null
                val fnName = fnObj["name"].asJsonPrimitiveOrNull()?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
                    ?: return@mapIndexedNotNull null
                val callId = tcObj["id"].asJsonPrimitiveOrNull()?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
                    ?: "call_${steps}_$idx"
                val rawArgs = when (val argElem = fnObj["arguments"]) {
                    null, is JsonNull -> "{}"
                    is JsonObject -> compactJson.encodeToString(argElem)
                    else -> argElem.asJsonPrimitiveOrNull()?.contentOrNull?.takeIf { it.isNotBlank() } ?: "{}"
                }
                val rebuilt = buildJsonObject {
                    put("id", callId)
                    put("type", "function")
                    putJsonObject("function") {
                        put("name", fnName)
                        put("arguments", rawArgs)
                    }
                }
                NormalizedToolCall(callId, fnName, rawArgs, rebuilt)
            }

            // Append a clean OpenAI-standard assistant message (stripping `reasoning_content` so
            // llama-server / Qwen3 / DeepSeek Jinja chat templates don't reject multi-turn history)
            messages.add(
                buildJsonObject {
                    put("role", "assistant")
                    put("content", contentText)
                    if (normalizedToolCalls.isNotEmpty()) {
                        put("tool_calls", JsonArray(normalizedToolCalls.map { it.jsonObject }))
                    }
                }
            )

            val inlineToolMatches = Regex("""<tool_call>\s*(\{[\s\S]*?})\s*</tool_call>""").findAll(contentText).toList()
            val strippedContentForThought = contentText.replace(Regex("""<tool_call>\s*\{[\s\S]*?}\s*</tool_call>"""), "").trim()

            if (reasoningText.isNotEmpty() ||
                (contentText.isNotEmpty() && normalizedToolCalls.isNotEmpty()) ||
                (strippedContentForThought.isNotEmpty() && inlineToolMatches.isNotEmpty())
            ) {
                val displayContent = if (inlineToolMatches.isNotEmpty()) strippedContentForThought else contentText
                val combinedDetail = when {
                    reasoningText.isNotEmpty() && displayContent.isNotEmpty() ->
                        "<think>\n$reasoningText\n</think>\n\n$displayContent"
                    reasoningText.isNotEmpty() ->
                        "<think>\n$reasoningText\n</think>"
                    else -> displayContent
                }
                emit(
                    kind = "assistant",
                    title = "Agent Thought (Turn $steps)",
                    detail = combinedDetail,
                    turn = steps
                )
            }

            if (normalizedToolCalls.isNotEmpty()) {
                for (tc in normalizedToolCalls) {
                    if (cancelFlag.get()) {
                        val msg = "Agent run cancelled by user at turn $steps."
                        emit(kind = "error", title = msg, turn = steps, done = true, success = false)
                        return AgentRunReport(false, chosenModel, steps, msg, events)
                    }
                    val parsedArgsResult = runCatching {
                        compactJson.parseToJsonElement(tc.rawArgs).asJsonObjectOrNull()
                            ?: error("Tool arguments must be a JSON object")
                    }
                    val parsedArgs = parsedArgsResult.getOrNull()

                    val summaryTarget = parsedArgs?.get("filePath").asJsonPrimitiveOrNull()?.contentOrNull
                        ?: parsedArgs?.get("scope").asJsonPrimitiveOrNull()?.contentOrNull
                        ?: parsedArgs?.get("command").asJsonObjectOrNull()
                            ?.get("type").asJsonPrimitiveOrNull()?.contentOrNull
                        ?: ""

                    emit(
                        kind = "tool_call",
                        title = if (summaryTarget.isNotEmpty()) "${tc.name} ($summaryTarget)" else tc.name,
                        detail = tc.rawArgs,
                        turn = steps
                    )

                    val toolOutput = if (parsedArgs != null) {
                        executeAgentTool(tc.name, parsedArgs, onFileMutated)
                    } else {
                        "Error: Malformed JSON arguments for tool '${tc.name}': ${parsedArgsResult.exceptionOrNull()?.message}"
                    }

                    emit(
                        kind = "tool_result",
                        title = "Result from ${tc.name}",
                        detail = toolOutput,
                        turn = steps
                    )

                    messages.add(
                        buildJsonObject {
                            put("role", "tool")
                            put("tool_call_id", tc.id)
                            put("name", tc.name)
                            put("content", toolOutput)
                        }
                    )
                }
                continue
            }

            if (inlineToolMatches.isNotEmpty()) {
                val inlineResultBlocks = mutableListOf<String>()
                for (inlineToolMatch in inlineToolMatches) {
                    if (cancelFlag.get()) {
                        val msg = "Agent run cancelled by user at turn $steps."
                        emit(kind = "error", title = msg, turn = steps, done = true, success = false)
                        return AgentRunReport(false, chosenModel, steps, msg, events)
                    }
                    val inlineObj = runCatching {
                        compactJson.parseToJsonElement(inlineToolMatch.groupValues[1]).asJsonObjectOrNull()
                    }.getOrNull()
                    val fnName = inlineObj?.get("name").asJsonPrimitiveOrNull()?.contentOrNull?.takeIf { it.isNotBlank() }
                    val rawArgsElem = inlineObj?.get("arguments")
                    if (fnName != null) {
                        val fnArgs = when (rawArgsElem) {
                            null, is JsonNull -> buildJsonObject {}
                            is JsonObject -> rawArgsElem
                            else -> {
                                val prim = rawArgsElem.asJsonPrimitiveOrNull()
                                if (prim != null && prim.isString) {
                                    val trimmed = prim.content.trim()
                                    if (trimmed.isEmpty()) {
                                        buildJsonObject {}
                                    } else {
                                        runCatching { compactJson.parseToJsonElement(trimmed).asJsonObjectOrNull() }.getOrNull()
                                    }
                                } else {
                                    null
                                }
                            }
                        }
                        emit(
                            kind = "tool_call",
                            title = "$fnName (inline)",
                            detail = fnArgs?.let { compactJson.encodeToString(it) } ?: rawArgsElem.toString(),
                            turn = steps
                        )
                        val toolOutput = if (fnArgs != null) {
                            executeAgentTool(fnName, fnArgs, onFileMutated)
                        } else {
                            "Error: Malformed 'arguments' in inline <tool_call> for '$fnName': expected JSON object."
                        }
                        emit(
                            kind = "tool_result",
                            title = "Result from $fnName",
                            detail = toolOutput,
                            turn = steps
                        )
                        inlineResultBlocks.add("Tool '$fnName' result:\n$toolOutput")
                    }
                }
                if (inlineResultBlocks.isNotEmpty()) {
                    messages.add(
                        buildJsonObject {
                            put("role", "user")
                            put("content", inlineResultBlocks.joinToString("\n\n"))
                        }
                    )
                    continue
                }
            }

            val turnLabel = if (steps == 1) "1 turn" else "$steps turns"
            val finalMsg = when {
                reasoningText.isNotEmpty() && contentText.isNotEmpty() ->
                    "<think>\n$reasoningText\n</think>\n\n$contentText"
                contentText.isNotBlank() -> contentText
                reasoningText.isNotBlank() -> "<think>\n$reasoningText\n</think>"
                else -> "Completed in $turnLabel."
            }
            emit(
                kind = "complete",
                title = "Agent finished in $turnLabel",
                detail = finalMsg,
                turn = steps,
                done = true,
                success = true
            )
            return AgentRunReport(
                success = true,
                model = chosenModel,
                stepsExecuted = steps,
                finalReply = finalMsg,
                events = events
            )
        }

        val maxMsg = "Reached maximum configured turns ($effectiveMaxTurns) before completing verification. Increase Max Turns if more work remains."
        emit(
            kind = "error",
            title = maxMsg,
            detail = "Executed $steps turns.",
            turn = steps,
            done = true,
            success = false
        )
        return AgentRunReport(
            success = false,
            model = chosenModel,
            stepsExecuted = steps,
            finalReply = maxMsg,
            events = events
        )
    }

    internal fun resolveSafeRepoFile(root: File, rawPath: String): File? =
        toolExecutor.resolveSafeRepoFile(root, rawPath)

    private fun executeAgentTool(
        name: String,
        args: JsonObject,
        onFileMutated: (File) -> Unit
    ): String = toolExecutor.executeAgentTool(name, args, onFileMutated)

    private fun callMcpTool(name: String, args: JsonObject): String =
        toolExecutor.callMcpTool(name, args)

    private fun rankModelsBySimilarity(
        requestedModel: String,
        availableModels: List<String>
    ): List<String> = transport.rankModelsBySimilarity(requestedModel, availableModels)
}
