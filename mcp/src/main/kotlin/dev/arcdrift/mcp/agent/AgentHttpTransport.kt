package dev.arcdrift.mcp.agent

import dev.arcdrift.mcp.protocol.asJsonArrayOrNull
import dev.arcdrift.mcp.protocol.asJsonObjectOrNull
import dev.arcdrift.mcp.protocol.asJsonPrimitiveOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * HTTP transport and model discovery/ranking helpers for OpenAI-compatible endpoints.
 */
internal class AgentHttpTransport(
    private val compactJson: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    },
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .build()
) {

    fun normalizeV1Base(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        require(trimmed.isNotEmpty()) { "Agent base URL must not be blank." }
        val uri = try {
            URI.create(trimmed)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid agent base URL '$trimmed': ${e.message}", e)
        }
        val scheme = uri.scheme?.lowercase()
        require(scheme == "http" || scheme == "https") {
            "Unsupported URL scheme '${uri.scheme ?: ""}' in '$trimmed'. Only http and https are allowed."
        }
        require(!uri.host.isNullOrBlank()) {
            "Agent base URL '$trimmed' is missing a host."
        }
        val cleanPath = (uri.rawPath ?: "").trimEnd('/')
        val effectivePath = if (cleanPath.isBlank()) "/v1" else cleanPath
        val authority = uri.rawAuthority
        val querySuffix = uri.rawQuery?.takeIf { it.isNotEmpty() }?.let { "?$it" }.orEmpty()
        return "$scheme://$authority$effectivePath$querySuffix"
    }

    fun appendEndpointPath(v1Base: String, subPath: String): URI {
        val qIdx = v1Base.indexOf('?')
        val baseWithoutQuery = if (qIdx >= 0) v1Base.substring(0, qIdx).trimEnd('/') else v1Base.trimEnd('/')
        val querySuffix = if (qIdx >= 0) v1Base.substring(qIdx) else ""
        val cleanSub = subPath.trimStart('/')
        return URI.create("$baseWithoutQuery/$cleanSub$querySuffix")
    }

    fun fetchModels(rawUrl: String, apiKey: String? = null): Result<List<String>> = runCatching {
        val v1 = normalizeV1Base(rawUrl)
        val targetUri = appendEndpointPath(v1, "models")
        val reqBuilder = HttpRequest.newBuilder()
            .uri(targetUri)
            .timeout(Duration.ofSeconds(15))
            .header("Accept", "application/json")
            .GET()
        if (!apiKey.isNullOrBlank()) {
            reqBuilder.header("Authorization", "Bearer ${apiKey.trim()}")
        }

        val resp = try {
            httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString())
        } catch (ie: InterruptedException) {
            Thread.currentThread().interrupt()
            throw ie
        }
        if (resp.statusCode() !in 200..299) {
            error("HTTP ${resp.statusCode()} from $targetUri: ${resp.body().take(300)}")
        }
        val root = compactJson.parseToJsonElement(resp.body()).asJsonObjectOrNull()
            ?: error("Unexpected non-object JSON payload from $targetUri")
        val dataArr = root["data"].asJsonArrayOrNull() ?: JsonArray(emptyList())
        dataArr
            .mapNotNull { elem ->
                val obj = elem.asJsonObjectOrNull() ?: return@mapNotNull null
                val id = obj["id"].asJsonPrimitiveOrNull()?.contentOrNull?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                val isLoaded = obj["status"].asJsonObjectOrNull()
                    ?.get("value").asJsonPrimitiveOrNull()?.contentOrNull == "loaded"
                id to isLoaded
            }
            .sortedByDescending { it.second }
            .map { it.first }
    }

    fun postChatCompletion(
        v1Base: String,
        bodyJson: String,
        apiKey: String?,
        cancelFlag: AtomicBoolean? = null
    ): HttpResponse<String> {
        val targetUri = appendEndpointPath(v1Base, "chat/completions")
        val reqBuilder = HttpRequest.newBuilder()
            .uri(targetUri)
            .timeout(Duration.ofMinutes(4))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(bodyJson))
        if (!apiKey.isNullOrBlank()) {
            reqBuilder.header("Authorization", "Bearer ${apiKey.trim()}")
        }
        if (cancelFlag == null) {
            return try {
                httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString())
            } catch (ie: InterruptedException) {
                Thread.currentThread().interrupt()
                throw ie
            }
        }

        val future = httpClient.sendAsync(reqBuilder.build(), HttpResponse.BodyHandlers.ofString())
        val deadlineNanos = System.nanoTime() + Duration.ofMinutes(4).plusSeconds(5).toNanos()
        while (true) {
            if (cancelFlag.get()) {
                future.cancel(true)
                throw java.util.concurrent.CancellationException("Agent HTTP request cancelled by user.")
            }
            val remainingMs = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime())
            if (remainingMs <= 0L) {
                future.cancel(true)
                throw TimeoutException("Chat completion request timed out.")
            }
            try {
                return future.get(minOf(remainingMs, 150L), TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                // Poll cancelFlag every 150ms while waiting for LLM completion
            } catch (ie: InterruptedException) {
                future.cancel(true)
                Thread.currentThread().interrupt()
                throw ie
            } catch (ee: ExecutionException) {
                throw (ee.cause ?: ee)
            }
        }
    }

    fun rankModelsBySimilarity(
        requestedModel: String,
        availableModels: List<String>
    ): List<String> {
        if (availableModels.isEmpty()) return emptyList()
        val req = requestedModel.trim().lowercase()
        if (req.isEmpty()) return availableModels

        val reqTokens = Regex("[a-z]+|[0-9]+").findAll(req).map { it.value }.toSet()

        return availableModels.sortedByDescending { candidate ->
            val cand = candidate.lowercase()
            if (cand == req) return@sortedByDescending 10_000
            var score = 0
            if (cand.contains(req) || req.contains(cand)) score += 500
            val candTokens = Regex("[a-z]+|[0-9]+").findAll(cand).map { it.value }.toSet()
            for (tok in reqTokens) {
                if (tok in candTokens) {
                    score += 80 + tok.length * 10
                } else if (tok.length >= 3 && cand.contains(tok.take(3))) {
                    score += 40
                }
            }
            val commonPrefix = req.commonPrefixWith(cand).length
            score += commonPrefix * 15
            score
        }
    }
}
