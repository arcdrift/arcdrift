package dev.arcdrift.core.scanner

import dev.arcdrift.core.model.ContractEndpoint
import java.io.File

/**
 * Parses and evaluates `.arcdriftignore` rules for repository scanning and cross-language
 * contract verification.
 *
 * Supported rule forms (one per line; `#` comments and blank lines are ignored):
 * - Path globs (supports single-star, double-star recursive segments, `?`, trailing-slash dirs like `vendor/`, and exact paths like `legacy/client.ts`)
 * - External host allowlists: `host:api.opsgenie.com`, `host:*.zoom.us`
 * - Contract route allowlists: `route:/v2/billing/...`, `route:POST /webhooks/...` (supports glob wildcards)
 */
class ArcDriftIgnore(
    private val pathMatchers: List<Regex> = emptyList(),
    private val hostMatchers: List<Regex> = emptyList(),
    private val routeMatchers: List<RouteIgnoreRule> = emptyList()
) {
    data class RouteIgnoreRule(
        val method: String?, // null matches any HTTP method
        val pathRegex: Regex
    )

    val isEmpty: Boolean
        get() = pathMatchers.isEmpty() && hostMatchers.isEmpty() && routeMatchers.isEmpty()

    fun isPathIgnored(relativePosixPath: String): Boolean {
        if (pathMatchers.isEmpty()) return false
        val normalized = relativePosixPath.replace('\\', '/').trimStart('/')
        return pathMatchers.any { it.matches(normalized) }
    }

    fun isHostIgnored(pathOrUrl: String): Boolean {
        if (hostMatchers.isEmpty()) return false
        val host = extractHost(pathOrUrl) ?: return false
        return hostMatchers.any { it.matches(host) }
    }

    fun isContractIgnored(contract: ContractEndpoint): Boolean {
        if (hostMatchers.isEmpty() && routeMatchers.isEmpty()) return false
        val explicitHost = contract.targetHost?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        if (explicitHost != null && hostMatchers.any { it.matches(explicitHost) }) return true
        if (isHostIgnored(contract.pathOrTopic)) return true
        if (routeMatchers.isEmpty()) return false

        val normalizedPath = normalizeContractPathForMatching(contract.pathOrTopic)
        val methodUpper = contract.methodOrAction.trim().uppercase()
        return routeMatchers.any { rule ->
            (rule.method == null || rule.method == methodUpper) &&
                (rule.pathRegex.matches(normalizedPath) || rule.pathRegex.matches(contract.pathOrTopic.trim()))
        }
    }

    companion object {
        const val IGNORE_FILE_NAME = ".arcdriftignore"

        val EMPTY = ArcDriftIgnore()

        private val HOST_EXTRACT_REGEX = Regex("""^https?://([^/:?#]+)""", RegexOption.IGNORE_CASE)
        private val SCHEME_HOST_PREFIX = Regex("""^https?://[^/]+""", RegexOption.IGNORE_CASE)

        fun load(rootDir: File, fileContentOverrides: Map<String, String> = emptyMap()): ArcDriftIgnore {
            val ignoreFile = File(rootDir, IGNORE_FILE_NAME)
            val content = fileContentOverrides[IGNORE_FILE_NAME]
                ?: fileContentOverrides[ignoreFile.invariantSeparatorsPath]
                ?: ignoreFile.takeIf { it.isFile }?.runCatching { readText(Charsets.UTF_8) }?.getOrNull()
                ?: return EMPTY
            return parse(content)
        }

        fun parse(content: String): ArcDriftIgnore {
            val pathMatchers = mutableListOf<Regex>()
            val hostMatchers = mutableListOf<Regex>()
            val routeMatchers = mutableListOf<RouteIgnoreRule>()

            for (rawLine in content.removePrefix("\uFEFF").lineSequence()) {
                val line = rawLine.trim()
                if (line.isEmpty() || line.startsWith("#")) continue

                when {
                    line.startsWith("host:", ignoreCase = true) -> {
                        val pattern = line.substringAfter(':').trim().lowercase()
                        if (pattern.isNotEmpty()) {
                            hostMatchers.add(globToRegex(pattern, crossSlash = false, ignoreCase = true))
                        }
                    }
                    line.startsWith("route:", ignoreCase = true) -> {
                        val rawRule = line.substringAfter(':').trim()
                        if (rawRule.isNotEmpty()) {
                            val parts = rawRule.split(Regex("""\s+"""), limit = 2)
                            val (method, pathPattern) = if (parts.size == 2 && parts[0].uppercase() in HTTP_METHODS) {
                                parts[0].uppercase() to parts[1].trim()
                            } else {
                                null to rawRule
                            }
                            if (pathPattern.isNotEmpty()) {
                                val normalizedPat = if (pathPattern.startsWith("/")) pathPattern else "/$pathPattern"
                                routeMatchers.add(
                                    RouteIgnoreRule(
                                        method = method,
                                        pathRegex = globToRegex(normalizedPat, crossSlash = true, ignoreCase = true)
                                    )
                                )
                            }
                        }
                    }
                    else -> {
                        val clean = line.replace('\\', '/').trimStart('/')
                        if (clean.isNotEmpty()) {
                            pathMatchers.addAll(buildPathRegexes(clean))
                        }
                    }
                }
            }
            return ArcDriftIgnore(pathMatchers, hostMatchers, routeMatchers)
        }

        internal fun extractHost(pathOrUrl: String): String? {
            val m = HOST_EXTRACT_REGEX.find(pathOrUrl.trim()) ?: return null
            return m.groupValues[1].trim().lowercase().takeIf { it.isNotEmpty() }
        }

        private fun normalizeContractPathForMatching(rawPath: String): String {
            var p = rawPath.trim()
            p = SCHEME_HOST_PREFIX.replace(p, "")
            p = p.substringBefore('?').substringBefore('#')
            if (!p.startsWith("/")) p = "/$p"
            return p.replace(Regex("/+"), "/")
        }

        private fun buildPathRegexes(pattern: String): List<Regex> {
            return if (pattern.endsWith("/")) {
                val dir = pattern.trimEnd('/')
                listOf(
                    globToRegex("$dir/**", crossSlash = true, ignoreCase = false),
                    globToRegex("**/$dir/**", crossSlash = true, ignoreCase = false)
                )
            } else if (!pattern.contains('/')) {
                // Bare filename or segment matches root or any nested path
                listOf(
                    globToRegex(pattern, crossSlash = true, ignoreCase = false),
                    globToRegex("**/$pattern", crossSlash = true, ignoreCase = false),
                    globToRegex("**/$pattern/**", crossSlash = true, ignoreCase = false)
                )
            } else {
                listOf(globToRegex(pattern, crossSlash = true, ignoreCase = false))
            }
        }

        private fun globToRegex(glob: String, crossSlash: Boolean, ignoreCase: Boolean): Regex {
            val sb = StringBuilder("^")
            var i = 0
            while (i < glob.length) {
                val ch = glob[i]
                when {
                    ch == '/' && i + 3 == glob.length && glob[i + 1] == '*' && glob[i + 2] == '*' -> {
                        sb.append("(?:/.*)?")
                        i += 3
                    }
                    ch == '*' && i + 1 < glob.length && glob[i + 1] == '*' -> {
                        if (i + 2 < glob.length && glob[i + 2] == '/') {
                            sb.append("(?:.*/)?")
                            i += 3
                        } else {
                            sb.append(".*")
                            i += 2
                        }
                    }
                    ch == '*' -> {
                        sb.append(if (crossSlash) "[^/]*" else "[^.]*")
                        i++
                    }
                    ch == '?' -> {
                        sb.append(if (crossSlash) "[^/]" else "[^.]")
                        i++
                    }
                    ch in "\\.+^\${}()|[]" -> {
                        sb.append('\\').append(ch)
                        i++
                    }
                    else -> {
                        sb.append(ch)
                        i++
                    }
                }
            }
            sb.append('$')
            return if (ignoreCase) Regex(sb.toString(), RegexOption.IGNORE_CASE) else Regex(sb.toString())
        }

        private val HTTP_METHODS = setOf("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS")
    }
}
