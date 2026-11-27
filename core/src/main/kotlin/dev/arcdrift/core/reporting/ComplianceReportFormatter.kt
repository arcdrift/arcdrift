package dev.arcdrift.core.reporting

import dev.arcdrift.core.analysis.DependencyCycle
import dev.arcdrift.core.contracts.ContractViolation
import dev.arcdrift.core.model.ArcGraph
import dev.arcdrift.core.rules.RuleViolation
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class SarifTextMessage(
    val text: String
)

@Serializable
data class SarifArtifactLocation(
    val uri: String
)

@Serializable
data class SarifRegion(
    val startLine: Int = 1,
    val endLine: Int = 1
)

@Serializable
data class SarifPhysicalLocation(
    val artifactLocation: SarifArtifactLocation,
    val region: SarifRegion = SarifRegion()
)

@Serializable
data class SarifLocation(
    val id: Int? = null,
    val physicalLocation: SarifPhysicalLocation,
    val message: SarifTextMessage? = null
)

@Serializable
data class SarifReportingDescriptor(
    val id: String,
    val name: String,
    val shortDescription: SarifTextMessage,
    val fullDescription: SarifTextMessage,
    val help: SarifTextMessage
)

@Serializable
data class SarifDriver(
    val name: String = "ArcDrift",
    val version: String = "0.1.0",
    val informationUri: String = "https://arcdrift.dev",
    val rules: List<SarifReportingDescriptor>
)

@Serializable
data class SarifTool(
    val driver: SarifDriver
)

@Serializable
data class SarifResult(
    val ruleId: String,
    val level: String,
    val message: SarifTextMessage,
    val locations: List<SarifLocation>,
    val relatedLocations: List<SarifLocation> = emptyList()
)

@Serializable
data class SarifRun(
    val tool: SarifTool,
    val results: List<SarifResult>
)

@Serializable
data class SarifLog(
    @SerialName("\$schema")
    val schema: String = "https://raw.githubusercontent.com/oasis-tcs/sarif-spec/main/sarif-2.1/schema/sarif-schema-2.1.0.json",
    val version: String = "2.1.0",
    val runs: List<SarifRun>
)

@Serializable
data class SonarImpact(
    val softwareQuality: String,
    val severity: String
)

@Serializable
data class SonarExternalRule(
    val id: String,
    val name: String,
    val description: String,
    val engineId: String = "arcdrift",
    val cleanCodeAttribute: String = "CONVENTIONAL",
    val type: String = "BUG",
    val severity: String = "CRITICAL",
    val impacts: List<SonarImpact> = emptyList()
)

@Serializable
data class SonarTextRange(
    val startLine: Int = 1,
    val endLine: Int = 1
)

@Serializable
data class SonarLocation(
    val message: String,
    val filePath: String,
    val textRange: SonarTextRange = SonarTextRange()
)

@Serializable
data class SonarExternalIssue(
    val engineId: String = "arcdrift",
    val ruleId: String,
    val severity: String = "CRITICAL",
    val type: String = "BUG",
    val primaryLocation: SonarLocation,
    val secondaryLocations: List<SonarLocation> = emptyList()
)

@Serializable
data class SonarGenericIssueReport(
    val rules: List<SonarExternalRule>,
    val issues: List<SonarExternalIssue>
)

/**
 * Formats ArcDrift architectural compliance findings into standard SARIF v2.1.0
 * (GitHub Code Scanning, GitLab SAST, Qodana) and SonarQube Generic Issue Import JSON
 * (`sonar.externalIssuesReportPaths`).
 */
object ComplianceReportFormatter {

    const val RULE_CONTRACT_DRIFT = "ARCDRIFT_CONTRACT_DRIFT"
    const val RULE_BOUNDARY_VIOLATION = "ARCDRIFT_BOUNDARY_RULE"
    const val RULE_DEPENDENCY_CYCLE = "ARCDRIFT_DEPENDENCY_CYCLE"

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    fun toSarifLog(
        graph: ArcGraph,
        cycles: List<DependencyCycle>,
        ruleViolations: List<RuleViolation>,
        contractViolations: List<ContractViolation>
    ): SarifLog {
        val nodeMap = graph.nodeMap()
        val rules = listOf(
            SarifReportingDescriptor(
                id = RULE_CONTRACT_DRIFT,
                name = "CrossLanguageContractDrift",
                shortDescription = SarifTextMessage("Cross-language REST/Kafka/OpenAPI contract or DTO field drift"),
                fullDescription = SarifTextMessage("Detects breaking route, response DTO, or request-body DTO drift between Kotlin/Java/Python producers and TypeScript/Python consumers."),
                help = SarifTextMessage("Synchronize the endpoint route and request/response DTO fields between backend producer and frontend/SDK consumer.")
            ),
            SarifReportingDescriptor(
                id = RULE_BOUNDARY_VIOLATION,
                name = "ArchitecturalBoundaryViolation",
                shortDescription = SarifTextMessage("Declarative .arc.mmd architectural boundary rule violation"),
                fullDescription = SarifTextMessage("Detects forbidden or unauthorized structural dependencies across architectural layers defined in .arc.mmd specs."),
                help = SarifTextMessage("Route the dependency through an authorized service or extract an interface port.")
            ),
            SarifReportingDescriptor(
                id = RULE_DEPENDENCY_CYCLE,
                name = "CircularDependencyCycle",
                shortDescription = SarifTextMessage("Circular dependency cycle (Tarjan Strongly Connected Component)"),
                fullDescription = SarifTextMessage("Detects multi-node or self-loop circular dependencies in the architecture graph."),
                help = SarifTextMessage("Break the cycle along the minimum-weight dependency edge by extracting an interface or inverting control.")
            )
        )

        val results = mutableListOf<SarifResult>()

        for (cv in contractViolations) {
            val consNode = nodeMap[cv.consumerNodeId]
            val prodNode = cv.producerNodeId?.let { nodeMap[it] }
            val consPath = cv.consumerFilePath ?: consNode?.source?.filePath
            val prodPath = cv.producerFilePath ?: prodNode?.source?.filePath
            val (primaryPath, primarySource) = when {
                consPath != null -> consPath to consNode?.source
                prodPath != null -> prodPath to prodNode?.source
                else -> "architecture.arc.mmd" to null
            }
            val startLine = primarySource?.startLine ?: 1
            val endLine = maxOf(startLine, primarySource?.endLine ?: startLine)

            val related = mutableListOf<SarifLocation>()
            if (prodPath != null && prodPath != primaryPath) {
                val pStart = prodNode?.source?.startLine ?: 1
                val pEnd = maxOf(pStart, prodNode?.source?.endLine ?: pStart)
                related.add(
                    SarifLocation(
                        id = 1,
                        physicalLocation = SarifPhysicalLocation(
                            artifactLocation = SarifArtifactLocation(uri = prodPath),
                            region = SarifRegion(startLine = pStart, endLine = pEnd)
                        ),
                        message = SarifTextMessage("Producer endpoint in ${prodNode?.label ?: prodPath}")
                    )
                )
            }

            results.add(
                SarifResult(
                    ruleId = RULE_CONTRACT_DRIFT,
                    level = "error",
                    message = SarifTextMessage("${cv.message} Remediation: ${cv.remediationHint}"),
                    locations = listOf(
                        SarifLocation(
                            physicalLocation = SarifPhysicalLocation(
                                artifactLocation = SarifArtifactLocation(uri = primaryPath),
                                region = SarifRegion(startLine = startLine, endLine = endLine)
                            )
                        )
                    ),
                    relatedLocations = related
                )
            )
        }

        for (rv in ruleViolations) {
            val srcNode = nodeMap[rv.sourceNodeId]
            val tgtNode = nodeMap[rv.targetNodeId]
            val explicitFilePath = rv.sourceFilePath ?: srcNode?.source?.filePath
            val filePath = explicitFilePath ?: "architecture.arc.mmd"
            val startLine = if (explicitFilePath != null) (rv.sourceLine ?: srcNode?.source?.startLine ?: 1) else 1
            val endLine = if (explicitFilePath != null) maxOf(startLine, srcNode?.source?.endLine ?: startLine) else 1

            val related = mutableListOf<SarifLocation>()
            val tgtPath = tgtNode?.source?.filePath
            if (tgtPath != null && tgtPath != filePath) {
                val tStart = tgtNode.source?.startLine ?: 1
                val tEnd = maxOf(tStart, tgtNode.source?.endLine ?: tStart)
                related.add(
                    SarifLocation(
                        id = 1,
                        physicalLocation = SarifPhysicalLocation(
                            artifactLocation = SarifArtifactLocation(uri = tgtPath),
                            region = SarifRegion(startLine = tStart, endLine = tEnd)
                        ),
                        message = SarifTextMessage("Target component '${tgtNode.label}'")
                    )
                )
            }

            results.add(
                SarifResult(
                    ruleId = RULE_BOUNDARY_VIOLATION,
                    level = "error",
                    message = SarifTextMessage("[${rv.ruleId}] ${rv.message} Remediation: ${rv.remediationHint}"),
                    locations = listOf(
                        SarifLocation(
                            physicalLocation = SarifPhysicalLocation(
                                artifactLocation = SarifArtifactLocation(uri = filePath),
                                region = SarifRegion(startLine = startLine, endLine = endLine)
                            )
                        )
                    ),
                    relatedLocations = related
                )
            )
        }

        for (c in cycles) {
            val primaryNode = c.recommendedBreakEdge?.let { nodeMap[it.sourceId] }
                ?: c.nodeIds.firstNotNullOfOrNull { nodeMap[it] }
            val filePath = primaryNode?.source?.filePath ?: "architecture.arc.mmd"
            val startLine = primaryNode?.source?.startLine ?: 1
            val endLine = maxOf(startLine, primaryNode?.source?.endLine ?: startLine)

            results.add(
                SarifResult(
                    ruleId = RULE_DEPENDENCY_CYCLE,
                    level = "warning",
                    message = SarifTextMessage("Circular dependency ${c.id}: ${c.nodeIds.joinToString(" -> ")}. ${c.recommendationRationale}"),
                    locations = listOf(
                        SarifLocation(
                            physicalLocation = SarifPhysicalLocation(
                                artifactLocation = SarifArtifactLocation(uri = filePath),
                                region = SarifRegion(startLine = startLine, endLine = endLine)
                            )
                        )
                    )
                )
            )
        }

        return SarifLog(
            runs = listOf(
                SarifRun(
                    tool = SarifTool(driver = SarifDriver(rules = rules)),
                    results = results
                )
            )
        )
    }

    fun toSarifJson(
        graph: ArcGraph,
        cycles: List<DependencyCycle>,
        ruleViolations: List<RuleViolation>,
        contractViolations: List<ContractViolation>
    ): String = json.encodeToString(toSarifLog(graph, cycles, ruleViolations, contractViolations))

    fun toSonarGenericReport(
        graph: ArcGraph,
        cycles: List<DependencyCycle>,
        ruleViolations: List<RuleViolation>,
        contractViolations: List<ContractViolation>
    ): SonarGenericIssueReport {
        val nodeMap = graph.nodeMap()
        val rules = listOf(
            SonarExternalRule(
                id = RULE_CONTRACT_DRIFT,
                name = "Cross-Language API / DTO Contract Drift",
                description = "Detects breaking route, response DTO, or request-body DTO drift across Kotlin/Java/Python producers and TypeScript/Python consumers.",
                type = "BUG",
                severity = "CRITICAL",
                impacts = listOf(SonarImpact(softwareQuality = "RELIABILITY", severity = "HIGH"))
            ),
            SonarExternalRule(
                id = RULE_BOUNDARY_VIOLATION,
                name = "Architectural Boundary Rule Violation",
                description = "Detects forbidden or unauthorized structural dependencies across architectural layers defined in .arc.mmd specs.",
                type = "CODE_SMELL",
                severity = "MAJOR",
                impacts = listOf(SonarImpact(softwareQuality = "MAINTAINABILITY", severity = "HIGH"))
            ),
            SonarExternalRule(
                id = RULE_DEPENDENCY_CYCLE,
                name = "Circular Dependency Cycle",
                description = "Detects multi-node or self-loop circular dependencies in the architecture graph.",
                type = "CODE_SMELL",
                severity = "MAJOR",
                impacts = listOf(SonarImpact(softwareQuality = "MAINTAINABILITY", severity = "MEDIUM"))
            )
        )

        val issues = mutableListOf<SonarExternalIssue>()

        for (cv in contractViolations) {
            val consNode = nodeMap[cv.consumerNodeId]
            val prodNode = cv.producerNodeId?.let { nodeMap[it] }
            val consPath = cv.consumerFilePath ?: consNode?.source?.filePath
            val prodPath = cv.producerFilePath ?: prodNode?.source?.filePath
            val (primaryPath, primarySource) = when {
                consPath != null -> consPath to consNode?.source
                prodPath != null -> prodPath to prodNode?.source
                else -> "architecture.arc.mmd" to null
            }
            val startLine = primarySource?.startLine ?: 1
            val endLine = maxOf(startLine, primarySource?.endLine ?: startLine)

            val secondaries = mutableListOf<SonarLocation>()
            if (prodPath != null && prodPath != primaryPath) {
                val pStart = prodNode?.source?.startLine ?: 1
                val pEnd = maxOf(pStart, prodNode?.source?.endLine ?: pStart)
                secondaries.add(
                    SonarLocation(
                        message = "Producer endpoint in ${prodNode?.label ?: prodPath}",
                        filePath = prodPath,
                        textRange = SonarTextRange(startLine = pStart, endLine = pEnd)
                    )
                )
            }

            issues.add(
                SonarExternalIssue(
                    ruleId = RULE_CONTRACT_DRIFT,
                    severity = "CRITICAL",
                    type = "BUG",
                    primaryLocation = SonarLocation(
                        message = "${cv.message} ${cv.remediationHint}",
                        filePath = primaryPath,
                        textRange = SonarTextRange(startLine = startLine, endLine = endLine)
                    ),
                    secondaryLocations = secondaries
                )
            )
        }

        for (rv in ruleViolations) {
            val srcNode = nodeMap[rv.sourceNodeId]
            val explicitFilePath = rv.sourceFilePath ?: srcNode?.source?.filePath
            val filePath = explicitFilePath ?: "architecture.arc.mmd"
            val startLine = if (explicitFilePath != null) (rv.sourceLine ?: srcNode?.source?.startLine ?: 1) else 1
            val endLine = if (explicitFilePath != null) maxOf(startLine, srcNode?.source?.endLine ?: startLine) else 1

            issues.add(
                SonarExternalIssue(
                    ruleId = RULE_BOUNDARY_VIOLATION,
                    severity = "MAJOR",
                    type = "CODE_SMELL",
                    primaryLocation = SonarLocation(
                        message = "[${rv.ruleId}] ${rv.message} ${rv.remediationHint}",
                        filePath = filePath,
                        textRange = SonarTextRange(startLine = startLine, endLine = endLine)
                    )
                )
            )
        }

        for (c in cycles) {
            val primaryNode = c.recommendedBreakEdge?.let { nodeMap[it.sourceId] }
                ?: c.nodeIds.firstNotNullOfOrNull { nodeMap[it] }
            val filePath = primaryNode?.source?.filePath ?: "architecture.arc.mmd"
            val startLine = primaryNode?.source?.startLine ?: 1
            val endLine = maxOf(startLine, primaryNode?.source?.endLine ?: startLine)

            issues.add(
                SonarExternalIssue(
                    ruleId = RULE_DEPENDENCY_CYCLE,
                    severity = "MAJOR",
                    type = "CODE_SMELL",
                    primaryLocation = SonarLocation(
                        message = "Circular dependency ${c.id} (${c.nodeIds.joinToString(" -> ")}). ${c.recommendationRationale}",
                        filePath = filePath,
                        textRange = SonarTextRange(startLine = startLine, endLine = endLine)
                    )
                )
            )
        }

        return SonarGenericIssueReport(rules = rules, issues = issues)
    }

    fun toSonarGenericIssuesJson(
        graph: ArcGraph,
        cycles: List<DependencyCycle>,
        ruleViolations: List<RuleViolation>,
        contractViolations: List<ContractViolation>
    ): String = json.encodeToString(toSonarGenericReport(graph, cycles, ruleViolations, contractViolations))

    /**
     * Generates a pre-filled, anonymized GitHub Issue URL so users can report false positives or feedback
     * in one click from PR comments or the IDE without manual outreach.
     */
    fun buildFeedbackIssueUrl(cv: ContractViolation): String {
        val redactedRouteShape = cv.pathOrTopic
            .split('/')
            .joinToString("/") { seg ->
                when {
                    seg.isEmpty() -> ""
                    seg.startsWith("{") || seg.startsWith(":") || seg.startsWith("\${") -> "{param}"
                    seg.matches(Regex("^v\\d+$", RegexOption.IGNORE_CASE)) -> seg
                    else -> "<segment>"
                }
            }
            .ifEmpty { "<topic>" }
        val violationCategory = when {
            cv.producerNodeId == null -> "unresolved-endpoint"
            cv.missingOrMismatchedFields.isNotEmpty() -> "dto-field-drift (${cv.missingOrMismatchedFields.size} fields)"
            else -> "route-mismatch"
        }
        val redactedTypesOnly = cv.missingOrMismatchedFields.map { entry ->
            val parenPart = entry.substringAfter('(', "").removeSuffix(")")
            when {
                parenPart.contains(" vs ") -> parenPart.replace(Regex("'[A-Za-z0-9_.$]+'"), "'<DTO>'")
                parenPart.startsWith("missing in ") -> "missing-field"
                else -> "field-mismatch"
            }
        }
        val title = java.net.URLEncoder.encode(
            "[Feedback] Contract check ($violationCategory) on ${cv.protocol} ${cv.methodOrAction}",
            Charsets.UTF_8
        )
        val bodyText = buildString {
            appendLine("### Anonymized Contract Diagnostic")
            appendLine("- **Protocol / Verb:** `${cv.protocol} ${cv.methodOrAction}`")
            appendLine("- **Route Shape (Redacted):** `$redactedRouteShape`")
            appendLine("- **Diagnostic Category:** `$violationCategory`")
            if (redactedTypesOnly.isNotEmpty()) {
                appendLine("- **Type Comparison Summary (Redacted):** `${redactedTypesOnly.joinToString("; ")}`")
            }
            appendLine()
            appendLine("### Expected Behavior / Context")
            appendLine("_Briefly describe why this endpoint or DTO mapping is valid in your stack:_")
        }
        val body = java.net.URLEncoder.encode(bodyText, Charsets.UTF_8)
        return "https://github.com/arcdrift/arcdrift/issues/new?labels=feedback,contract-bridge&title=$title&body=$body"
    }
}
