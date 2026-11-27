package dev.arcdrift.cli.reporter

import dev.arcdrift.core.analysis.BlastRadiusReport
import dev.arcdrift.core.analysis.DependencyCycle
import dev.arcdrift.core.codec.ArcMermaidSerializer
import dev.arcdrift.core.contracts.ContractViolation
import dev.arcdrift.core.reporting.ComplianceReportFormatter
import dev.arcdrift.core.rules.RuleViolation

/**
 * Renders a GitHub / GitLab Pull Request Markdown comment summarizing architectural diff,
 * blast-radius impact, boundary rule violations, cross-language contract breaks, and embedded Mermaid preview.
 */
object MarkdownPrReporter {

    fun generatePrReport(
        projectName: String,
        diffReport: BlastRadiusReport,
        cycles: List<DependencyCycle>,
        ruleViolations: List<RuleViolation>,
        contractViolations: List<ContractViolation>
    ): String {
        val passed = cycles.isEmpty() && ruleViolations.isEmpty() && contractViolations.isEmpty()
        val statusBadge = if (passed) "✅ **PASSED**" else "❌ **ACTION REQUIRED (Violations Detected)**"
        val couplingSign = if (diffReport.couplingDeltaPercent > 0) "+" else ""

        val sb = StringBuilder()
        sb.appendLine("## ⚡ ArcDrift Architecture Impact & Blast-Radius Report")
        sb.appendLine()
        sb.appendLine("**Project:** ${codeSpan(projectName)} | **Compliance Status:** $statusBadge")
        sb.appendLine()
        sb.appendLine("| Metric | Value | Details |")
        sb.appendLine("| :--- | :--- | :--- |")
        sb.appendLine("| **Added Components** | `${diffReport.addedNodeIds.size}` | ${formatIdList(diffReport.addedNodeIds)} |")
        sb.appendLine("| **Modified Components** | `${diffReport.modifiedNodeIds.size}` | ${formatIdList(diffReport.modifiedNodeIds)} |")
        sb.appendLine("| **Removed Components** | `${diffReport.removedNodeIds.size}` | ${formatIdList(diffReport.removedNodeIds)} |")
        sb.appendLine("| **Upstream Blast Radius** | `${diffReport.blastImpactedNodeIds.size}` | ${formatIdList(diffReport.blastImpactedNodeIds)} |")
        sb.appendLine("| **Coupling Delta** | `$couplingSign${diffReport.couplingDeltaPercent}%` | Baseline cycles: `${diffReport.baselineCycleCount}` -> Current: `${diffReport.currentCycleCount}` |")
        sb.appendLine("| **Boundary Rule Violations** | `${ruleViolations.size}` | ${if (ruleViolations.isEmpty()) "None" else "See below"} |")
        sb.appendLine("| **Cross-Language Contract Drift** | `${contractViolations.size}` | ${if (contractViolations.isEmpty()) "None" else "See below"} |")

        if (ruleViolations.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("### 🚨 Architectural Boundary Violations")
            for (v in ruleViolations) {
                sb.appendLine("- **[${escapeMarkdownText(v.ruleId)}] ${escapeMarkdownText(v.ruleName)}** (`${v.policy.name}`): ${codeSpan(v.sourceNodeId)} -> ${codeSpan(v.targetNodeId)}")
                sb.appendLine("  - *Message:* ${escapeMarkdownText(v.message)}")
                sb.appendLine("  - *Remediation:* ${escapeMarkdownText(v.remediationHint)}")
            }
        }

        if (contractViolations.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("### 🔗 Cross-Language API / DTO Contract Violations")
            for (cv in contractViolations) {
                val feedbackUrl = ComplianceReportFormatter.buildFeedbackIssueUrl(cv)
                sb.appendLine("- **[${escapeMarkdownText(cv.protocol)} ${escapeMarkdownText(cv.methodOrAction)} ${codeSpan(cv.pathOrTopic)}]** Consumer ${codeSpan(cv.consumerNodeId)} <-> Producer ${codeSpan(cv.producerNodeId ?: "MISSING")} ([Report False Positive]($feedbackUrl))")
                sb.appendLine("  - *Issue:* ${escapeMarkdownText(cv.message)}")
                sb.appendLine("  - *Remediation:* ${escapeMarkdownText(cv.remediationHint)}")
            }
        }

        if (cycles.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("### 🔄 Circular Dependencies Detected (Tarjan SCC)")
            for (c in cycles) {
                sb.appendLine("- **${escapeMarkdownText(c.id)}:** ${codeSpan(c.nodeIds.joinToString(" <-> "))}")
                sb.appendLine("  - *Cycle Breaker Hint:* ${escapeMarkdownText(c.recommendationRationale)}")
            }
        }

        sb.appendLine()
        sb.appendLine("<details>")
        sb.appendLine("<summary>📊 <b>View Interactive Architecture Diff (Mermaid / .arc.mmd)</b></summary>")
        sb.appendLine()
        val mermaidText = ArcMermaidSerializer.serialize(diffReport.diffGraph)
        val maxBacktickRun = Regex("`+").findAll(mermaidText).maxOfOrNull { it.value.length } ?: 0
        val fence = "`".repeat(maxOf(3, maxBacktickRun + 1))
        sb.appendLine("${fence}mermaid")
        sb.append(mermaidText)
        if (!mermaidText.endsWith("\n")) sb.appendLine()
        sb.appendLine(fence)
        sb.appendLine("</details>")
        sb.appendLine()
        val encodedProject = java.net.URLEncoder.encode(projectName, Charsets.UTF_8)
        sb.appendLine("_⚡ Generated by **ArcDrift** (`arc diff`) — [Open interactively in IntelliJ / Cursor](jetbrains://idea/arcdrift?project=$encodedProject) · [Give Feedback / Report Issue](https://github.com/arcdrift/arcdrift/issues/new?labels=feedback)_")

        return sb.toString()
    }

    internal fun shortDisplayId(id: String): String {
        val core = if (id.startsWith("upstream:")) {
            id.removePrefix("upstream:").substringAfter(':')
        } else {
            id
        }
        if (core.startsWith("ts.")) {
            val rest = core.removePrefix("ts.")
            if (rest.endsWith(".tsx")) {
                val base = rest.removeSuffix(".tsx")
                return "${base.substringAfterLast('.')}.tsx"
            }
            val parts = rest.split('.').filter { it.isNotEmpty() }
            val commonFileRoles = setOf("service", "client", "controller", "api", "hooks", "store", "model", "types", "dto")
            if (parts.size >= 2 && parts.last().lowercase() in commonFileRoles) {
                return "${parts[parts.size - 2]}.${parts.last()}"
            }
            return parts.lastOrNull() ?: core
        }
        if (core.startsWith("py.")) {
            val parts = core.removePrefix("py.").split('.').filter { it.isNotEmpty() }
            return parts.lastOrNull() ?: core
        }
        return core.substringAfterLast('.')
    }

    internal fun escapeMarkdownText(raw: String, escapePipe: Boolean = false): String {
        var cleaned = raw.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ')
            .replace("<", "&lt;")
            .replace(">", "&gt;")
        if (escapePipe) {
            cleaned = cleaned.replace("|", "\\|")
        }
        return cleaned
    }

    internal fun codeSpan(raw: String, escapePipe: Boolean = false): String {
        var cleaned = raw.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ')
        if (escapePipe) {
            cleaned = cleaned.replace("|", "\\|")
        }
        return if ('`' in cleaned) {
            val maxRun = Regex("`+").findAll(cleaned).maxOfOrNull { it.value.length } ?: 1
            val fence = "`".repeat(maxRun + 1)
            val padded = if (cleaned.startsWith('`') || cleaned.endsWith('`')) " $cleaned " else cleaned
            "$fence$padded$fence"
        } else {
            "`$cleaned`"
        }
    }

    private fun formatIdList(ids: List<String>): String {
        if (ids.isEmpty()) return "_None_"
        val shortNames = ids.take(5).map { codeSpan(shortDisplayId(it), escapePipe = true) }
        val suffix = if (ids.size > 5) " (+${ids.size - 5} more)" else ""
        return shortNames.joinToString(", ") + suffix
    }
}
