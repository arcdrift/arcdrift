package dev.arcdrift.cli

import com.github.ajalt.clikt.core.UsageError
import dev.arcdrift.cli.reporter.MarkdownPrReporter
import dev.arcdrift.core.analysis.BlastRadiusReport
import dev.arcdrift.core.model.ArcGraph
import dev.arcdrift.core.model.ArcNode
import dev.arcdrift.core.model.C4Level
import dev.arcdrift.core.model.NodeKind
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class CliHardeningRegressionTest {

    @Test
    fun `invalid --format fails upfront before writing exported contracts or ArchUnit files`(@TempDir tempDir: File) {
        val srcDir = File(tempDir, "src/main/kotlin/com/acme").apply { mkdirs() }
        File(srcDir, "Demo.kt").writeText("package com.acme\nclass Demo\n")
        val baselineSpec = File(tempDir, "baseline.arc.mmd").apply {
            writeText("flowchart TB\n  com_acme_Demo[\"Demo\"]\n")
        }

        val contractsOut = File(tempDir, "should-not-exist.arc-contracts.json")
        assertThrows(UsageError::class.java) {
            ScanCommand().parse(listOf(tempDir.absolutePath, "--format", "bogus", "--export-contracts", contractsOut.absolutePath))
        }
        assertFalse(contractsOut.exists(), "scan --format bogus must not export contracts before validating --format")

        val archUnitOut = File(tempDir, "ShouldNotExistTest.kt")
        assertThrows(UsageError::class.java) {
            CheckCommand().parse(listOf(tempDir.absolutePath, "--format", "bogus", "--generate-archunit", archUnitOut.absolutePath))
        }
        assertFalse(archUnitOut.exists(), "check --format bogus must not write ArchUnit file before validating --format")

        assertThrows(UsageError::class.java) {
            DiffCommand().parse(listOf(baselineSpec.absolutePath, tempDir.absolutePath, "--format", "bogus"))
        }
    }

    @Test
    fun `MarkdownPrReporter dynamically expands Mermaid fence beyond longest embedded backtick run`() {
        val trickyGraph = ArcGraph(
            projectName = "BacktickDemo",
            nodes = listOf(
                ArcNode(
                    id = "com.acme.TickNode",
                    label = "Node with ```` four backticks",
                    c4Level = C4Level.L3_COMPONENT,
                    kind = NodeKind.CLASS
                )
            )
        )
        val report = MarkdownPrReporter.generatePrReport(
            projectName = "BacktickDemo",
            diffReport = BlastRadiusReport(
                diffGraph = trickyGraph,
                addedNodeIds = listOf("com.acme.TickNode"),
                modifiedNodeIds = emptyList(),
                removedNodeIds = emptyList(),
                blastImpactedNodeIds = emptyList(),
                baselineCycleCount = 0,
                currentCycleCount = 0,
                couplingDeltaPercent = 0.0
            ),
            cycles = emptyList(),
            ruleViolations = emptyList(),
            contractViolations = emptyList()
        )
        assertTrue(report.contains("`````mermaid\n"), "Expected 5-backtick opening fence:\n$report")
        assertTrue(report.contains("\n`````\n</details>"), "Expected 5-backtick closing fence:\n$report")
    }
}
