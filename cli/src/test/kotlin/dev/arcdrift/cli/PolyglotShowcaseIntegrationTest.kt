package dev.arcdrift.cli

import dev.arcdrift.cli.reporter.MarkdownPrReporter
import dev.arcdrift.core.scanner.PolyglotRepoScanner
import dev.arcdrift.core.analysis.BlastRadiusDiffer
import dev.arcdrift.core.analysis.TarjanCycleDetector
import dev.arcdrift.core.contracts.PolyglotContractBridge
import dev.arcdrift.core.refactoring.InMemoryGraphRefactoringAdapter
import dev.arcdrift.core.rules.ArcRuleEngine
import dev.arcdrift.mcp.protocol.*
import dev.arcdrift.mcp.server.ArcDriftMcpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PolyglotShowcaseIntegrationTest {

    @TempDir
    lateinit var tempDir: Path

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `full Spec-and-Verify pipeline across Kotlin Spring Boot, TypeScript client, Python analytics worker and MCP server`() {
        val repoRoot = tempDir.resolve("polyglot-showcase").toFile().apply { mkdirs() }

        // 1. Human Architect creates declarative .arc.mmd spec with boundary guardrails
        File(repoRoot, "architecture.arc.mmd").writeText(
            """
            flowchart TB
                %% @arc:project name="polyglot-fintech" version="2.1"
                %% @arc:rule id="no-controller-to-repo" name="Controllers Must Use Services" from="*.controller.*" to="*.repository.*" policy="FORBIDDEN" rationale="Never bypass transactional service layer"
                %% @arc:rule id="domain-isolation" name="Domain Must Not Depend on Infra" from="*.domain.*" to="*.infra.*" policy="FORBIDDEN" rationale="Clean Architecture boundary"
            """.trimIndent()
        )

        // 2. Seed Kotlin Spring Boot backend + DTOs
        val dtoFile = File(repoRoot, "services/ledger/src/main/kotlin/com/fintech/ledger/dto/TransferResponseDto.kt")
        dtoFile.parentFile.mkdirs()
        dtoFile.writeText(
            """
            package com.fintech.ledger.dto
            data class TransferResponseDto(val transferId: String, val settledAmountCents: Long, val status: String)
            """.trimIndent()
        )

        val repoFile = File(repoRoot, "services/ledger/src/main/kotlin/com/fintech/ledger/repository/LedgerRepository.kt")
        repoFile.parentFile.mkdirs()
        repoFile.writeText(
            """
            package com.fintech.ledger.repository
            import org.springframework.stereotype.Repository
            @Repository
            class LedgerRepository
            """.trimIndent()
        )

        val svcFile = File(repoRoot, "services/ledger/src/main/kotlin/com/fintech/ledger/service/LedgerService.kt")
        svcFile.parentFile.mkdirs()
        svcFile.writeText(
            """
            package com.fintech.ledger.service
            import com.fintech.ledger.repository.LedgerRepository
            import org.springframework.stereotype.Service
            @Service
            class LedgerService(val repository: LedgerRepository)
            """.trimIndent()
        )

        val ctrlFile = File(repoRoot, "services/ledger/src/main/kotlin/com/fintech/ledger/controller/LedgerController.kt")
        ctrlFile.parentFile.mkdirs()
        ctrlFile.writeText(
            """
            package com.fintech.ledger.controller
            import com.fintech.ledger.service.LedgerService
            import com.fintech.ledger.dto.TransferResponseDto
            import org.springframework.web.bind.annotation.RestController
            import org.springframework.web.bind.annotation.RequestMapping
            import org.springframework.web.bind.annotation.PostMapping

            @RestController
            @RequestMapping("/v1/transfers")
            class LedgerController(val ledgerService: LedgerService) {
                @PostMapping("/{accountId}")
                fun createTransfer(): TransferResponseDto = TransferResponseDto("tx-1", 10000L, "SETTLED")
            }
            """.trimIndent()
        )

        // 3. Seed TypeScript frontend client & Python settlement audit consumer
        val tsClient = File(repoRoot, "web/src/api/transfersApi.ts")
        tsClient.parentFile.mkdirs()
        tsClient.writeText(
            """
            export interface TransferReceipt {
                transferId: string;
                settledAmountCents: number;
                status: string;
            }
            export async function initiateTransfer(accountId: string): Promise<TransferReceipt> {
                const res = await axios.post(`/v1/transfers/${'$'}{accountId}`);
                return res.data;
            }
            """.trimIndent()
        )

        val pyWorker = File(repoRoot, "workers/audit_worker.py")
        pyWorker.parentFile.mkdirs()
        pyWorker.writeText(
            """
            from pydantic import BaseModel

            class TransferAuditModel(BaseModel):
                transferId: str
                settledAmountCents: int

            def sync_transfer(account_id: str):
                return httpx.post(f"/v1/transfers/{account_id}")
            """.trimIndent()
        )

        // 4. Scan baseline graph & connect MCP server
        val baselineGraph = PolyglotRepoScanner.scanRepository(repoRoot)
        val refactoringAdapter = InMemoryGraphRefactoringAdapter(baselineGraph)
        val mcpServer = ArcDriftMcpServer(
            graphProvider = { refactoringAdapter.currentGraph },
            refactoringPort = refactoringAdapter
        )

        // Verify baseline compliance via MCP tool call
        val verifyCleanRaw = requireNotNull(mcpServer.handleJsonRpcLine(
            """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"verify_architectural_compliance","arguments":{"includeContracts":true}}}"""
        ))
        val verifyCleanRes = json.decodeFromJsonElement<McpCallToolResult>(
            requireNotNull(json.decodeFromString<JsonRpcResponse>(verifyCleanRaw).result)
        )
        assertFalse(verifyCleanRes.isError)
        val cleanCompliance = json.decodeFromString<ArchitecturalComplianceReport>(verifyCleanRes.content.first().text)
        assertTrue(cleanCompliance.passed, "Expected clean compliance, got: ${cleanCompliance.summary}")

        // 5. AI Coding Agent uses MCP `execute_deterministic_refactoring` to extract `LedgerPort` interface
        val extractRaw = requireNotNull(mcpServer.handleJsonRpcLine(
            """
            {
              "jsonrpc": "2.0",
              "id": 2,
              "method": "tools/call",
              "params": {
                "name": "execute_deterministic_refactoring",
                "arguments": {
                  "previewOnly": false,
                  "command": {
                    "type": "EXTRACT_INTERFACE",
                    "sourceClassId": "com.fintech.ledger.service.LedgerService",
                    "interfaceSimpleName": "LedgerPort",
                    "targetPackage": "com.fintech.ledger.port",
                    "memberNames": []
                  }
                }
              }
            }
            """.trimIndent().lines().joinToString(" ")
        ))
        val extractRes = json.decodeFromJsonElement<McpCallToolResult>(
            requireNotNull(json.decodeFromString<JsonRpcResponse>(extractRaw).result)
        )
        assertFalse(extractRes.isError)

        // 6. Compute visual diff & PR Blast-Radius report between baseline and refactored graph
        val currentGraph = refactoringAdapter.currentGraph
        val diffReport = BlastRadiusDiffer.diff(baselineGraph, currentGraph, maxDepth = 3)
        assertEquals(listOf("com.fintech.ledger.port.LedgerPort"), diffReport.addedNodeIds)
        assertTrue("com.fintech.ledger.controller.LedgerController" in diffReport.blastImpactedNodeIds)

        val prMarkdown = MarkdownPrReporter.generatePrReport(
            projectName = currentGraph.projectName,
            diffReport = diffReport,
            cycles = TarjanCycleDetector.findCycles(currentGraph),
            ruleViolations = ArcRuleEngine.evaluate(currentGraph),
            contractViolations = PolyglotContractBridge.linkAndVerify(currentGraph).violations
        )
        assertTrue(prMarkdown.contains("✅ **PASSED**"))
        assertTrue(prMarkdown.contains("`LedgerPort`"))
        assertNotNull(currentGraph.nodeMap()["com.fintech.ledger.port.LedgerPort"])
    }

    @Test
    fun `catches sneaky agentic disk mutations (wildcard imports, inline FQN, same-package calls, wrapped generic records, deletions) and mutates real files on disk via MCP`() {
        val repoRoot = tempDir.resolve("agent-harness-repo").toFile().apply { mkdirs() }

        File(repoRoot, "architecture.arc.mmd").writeText(
            """
            flowchart TB
                %% @arc:project name="agent-harness-test" version="1.0"
                %% @arc:rule id="no-controller-to-repo" name="Controllers Must Use Services" from="*.controller.*" to="*.repository.*" policy="FORBIDDEN" rationale="Never bypass service layer"
            """.trimIndent()
        )

        val dtoFile = File(repoRoot, "src/main/kotlin/com/fintech/ledger/dto/TransferResponseDto.kt").apply {
            parentFile.mkdirs()
            writeText(
                """
                package com.fintech.ledger.dto
                data class TransferResponseDto(val transferId: String, val settledAmountCents: Long, val status: String)
                """.trimIndent()
            )
        }

        val repoFile = File(repoRoot, "src/main/kotlin/com/fintech/ledger/repository/LedgerRepository.kt").apply {
            parentFile.mkdirs()
            writeText(
                """
                package com.fintech.ledger.repository
                import org.springframework.stereotype.Repository
                @Repository
                class LedgerRepository {
                    fun saveTransfer(id: String): Boolean = true
                }
                """.trimIndent()
            )
        }

        val svcFile = File(repoRoot, "src/main/kotlin/com/fintech/ledger/service/LedgerService.kt").apply {
            parentFile.mkdirs()
            writeText(
                """
                package com.fintech.ledger.service
                import com.fintech.ledger.repository.LedgerRepository
                import org.springframework.stereotype.Service
                @Service
                class LedgerService(val repository: LedgerRepository) {
                    fun executeTransfer(accountId: String): String = "tx-1"
                }
                """.trimIndent()
            )
        }

        val ctrlFile = File(repoRoot, "src/main/kotlin/com/fintech/ledger/controller/LedgerController.kt").apply {
            parentFile.mkdirs()
            writeText(
                """
                package com.fintech.ledger.controller
                import com.fintech.ledger.service.LedgerService
                import com.fintech.ledger.dto.TransferResponseDto
                import org.springframework.web.bind.annotation.RestController
                import org.springframework.web.bind.annotation.RequestMapping
                import org.springframework.web.bind.annotation.PostMapping

                @RestController
                @RequestMapping("/v1/transfers")
                class LedgerController(val ledgerService: LedgerService) {
                    @PostMapping("/{accountId}")
                    fun createTransfer(): TransferResponseDto = TransferResponseDto("tx-1", 10000L, "SETTLED")
                }
                """.trimIndent()
            )
        }

        val tsClient = File(repoRoot, "web/src/api/transfersApi.ts").apply {
            parentFile.mkdirs()
            writeText(
                """
                export type TransferReceipt = {
                    transferId: string;
                    settledAmountCents: number;
                };
                export async function initiateTransfer(accountId: string): Promise<TransferReceipt> {
                    const res = await axios.post(`/v1/transfers/${'$'}{accountId}`);
                    return res.data;
                }
                """.trimIndent()
            )
        }

        val pyWorker = File(repoRoot, "workers/audit_worker.py").apply {
            parentFile.mkdirs()
            writeText(
                """
                from dataclasses import dataclass

                @dataclass
                class TransferAuditModel:
                    transferId: str
                    settledAmountCents: int

                def sync_transfer(account_id: str):
                    return httpx.post(f"/v1/transfers/{account_id}")
                """.trimIndent()
            )
        }

        // Start disk-backed MCP server (same configuration as `arc mcp`)
        val baselineGraph = PolyglotRepoScanner.scanRepository(repoRoot)
        val adapter = InMemoryGraphRefactoringAdapter(baselineGraph, repoRoot)
        val mcpServer = ArcDriftMcpServer(
            graphProvider = { adapter.rescanFromDiskIfAvailable() },
            refactoringPort = adapter
        )

        fun callVerifyCompliance(): ArchitecturalComplianceReport {
            val raw = requireNotNull(mcpServer.handleJsonRpcLine(
                """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"verify_architectural_compliance","arguments":{"includeContracts":true}}}"""
            ))
            val toolRes = json.decodeFromJsonElement<McpCallToolResult>(
                requireNotNull(json.decodeFromString<JsonRpcResponse>(raw).result)
            )
            val report = json.decodeFromString<ArchitecturalComplianceReport>(toolRes.content.first().text)
            assertEquals(!report.passed, toolRes.isError)
            return report
        }

        assertTrue(callVerifyCompliance().passed, "Baseline should pass compliance")

        // SNEAKY AGENT MUTATION 1a: Bare wildcard import `import com.fintech.ledger.repository.*` even before calling any symbol
        ctrlFile.writeText(
            """
            package com.fintech.ledger.controller
            import com.fintech.ledger.repository.*
            import com.fintech.ledger.service.LedgerService
            import com.fintech.ledger.dto.TransferResponseDto
            import org.springframework.web.bind.annotation.RestController
            import org.springframework.web.bind.annotation.RequestMapping
            import org.springframework.web.bind.annotation.PostMapping

            @RestController
            @RequestMapping("/v1/transfers")
            class LedgerController(val ledgerService: LedgerService) {
                @PostMapping("/{accountId}")
                fun createTransfer(): TransferResponseDto = TransferResponseDto("tx-1", 10000L, "SETTLED")
            }
            """.trimIndent()
        )
        val reportBareWildcard = callVerifyCompliance()
        assertFalse(reportBareWildcard.passed, "Should catch bare wildcard import `import com.fintech.ledger.repository.*`")
        assertEquals(1, reportBareWildcard.ruleViolationCount)
        assertEquals("no-controller-to-repo", reportBareWildcard.ruleViolations.first().ruleId)

        // SNEAKY AGENT MUTATION 1b: Wildcard import + method-body instantiation (no field/constructor param)
        ctrlFile.writeText(
            """
            package com.fintech.ledger.controller
            import com.fintech.ledger.service.LedgerService
            import com.fintech.ledger.dto.TransferResponseDto
            import com.fintech.ledger.repository.*
            import org.springframework.web.bind.annotation.RestController
            import org.springframework.web.bind.annotation.RequestMapping
            import org.springframework.web.bind.annotation.PostMapping

            @RestController
            @RequestMapping("/v1/transfers")
            class LedgerController(val ledgerService: LedgerService) {
                @PostMapping("/{accountId}")
                fun createTransfer(): TransferResponseDto {
                    val sneakyRepo = LedgerRepository()
                    sneakyRepo.saveTransfer("tx-1")
                    return TransferResponseDto("tx-1", 10000L, "SETTLED")
                }
            }
            """.trimIndent()
        )
        val reportWildcard = callVerifyCompliance()
        assertFalse(reportWildcard.passed, "Should catch wildcard import + method body call")
        assertEquals(1, reportWildcard.ruleViolationCount)
        assertEquals("no-controller-to-repo", reportWildcard.ruleViolations.first().ruleId)

        // SNEAKY AGENT MUTATION 2: Zero import statement, inline fully-qualified class usage in method body
        ctrlFile.writeText(
            """
            package com.fintech.ledger.controller
            import com.fintech.ledger.service.LedgerService
            import com.fintech.ledger.dto.TransferResponseDto
            import org.springframework.web.bind.annotation.RestController
            import org.springframework.web.bind.annotation.RequestMapping
            import org.springframework.web.bind.annotation.PostMapping

            @RestController
            @RequestMapping("/v1/transfers")
            class LedgerController(val ledgerService: LedgerService) {
                @PostMapping("/{accountId}")
                fun createTransfer(): TransferResponseDto {
                    // Commented out: com.fintech.ledger.repository.LedgerRepository
                    val inlineRepo = com.fintech.ledger.repository.LedgerRepository()
                    inlineRepo.saveTransfer("tx-1")
                    return TransferResponseDto("tx-1", 10000L, "SETTLED")
                }
            }
            """.trimIndent()
        )
        val reportInlineFqn = callVerifyCompliance()
        assertFalse(reportInlineFqn.passed, "Should catch inline FQN usage without import")
        assertEquals(1, reportInlineFqn.ruleViolationCount)

        // SNEAKY AGENT MUTATION 3: Same-package cycle without any import statements
        val samePkgA = File(repoRoot, "src/main/kotlin/com/fintech/ledger/service/OrderValidator.kt").apply {
            writeText(
                """
                package com.fintech.ledger.service
                class OrderValidator {
                    fun validate() {
                        val enricher = OrderEnricher()
                    }
                }
                """.trimIndent()
            )
        }
        val samePkgB = File(repoRoot, "src/main/kotlin/com/fintech/ledger/service/OrderEnricher.kt").apply {
            writeText(
                """
                package com.fintech.ledger.service
                class OrderEnricher {
                    fun enrich() {
                        val validator = OrderValidator()
                    }
                }
                """.trimIndent()
            )
        }
        val reportSamePkgCycle = callVerifyCompliance()
        assertTrue(reportSamePkgCycle.cycleCount >= 1, "Should catch same-package cycle without imports")
        samePkgA.delete()
        samePkgB.delete()

        // SNEAKY AGENT MUTATION 4: Java record DTO + ResponseEntity<List<TransferResponseDto>> generic wrapper + renamed field
        dtoFile.delete()
        val javaRecordDto = File(repoRoot, "src/main/java/com/fintech/ledger/dto/TransferResponseDto.java").apply {
            parentFile.mkdirs()
            writeText(
                """
                package com.fintech.ledger.dto;
                public record TransferResponseDto(String transferId, long totalSettledCents, String status) {}
                """.trimIndent()
            )
        }
        ctrlFile.writeText(
            """
            package com.fintech.ledger.controller
            import com.fintech.ledger.service.LedgerService
            import com.fintech.ledger.dto.TransferResponseDto
            import org.springframework.http.ResponseEntity
            import org.springframework.web.bind.annotation.RestController
            import org.springframework.web.bind.annotation.RequestMapping
            import org.springframework.web.bind.annotation.PostMapping

            @RestController
            @RequestMapping("/v1/transfers")
            class LedgerController(val ledgerService: LedgerService) {
                @PostMapping("/{accountId}")
                fun createTransfer(): ResponseEntity<List<TransferResponseDto>> = TODO()
            }
            """.trimIndent()
        )
        val reportContractDrift = callVerifyCompliance()
        assertFalse(reportContractDrift.passed, "Should unwrap ResponseEntity<List<TransferResponseDto>>, parse Java record, and detect contract drift")
        assertEquals(2, reportContractDrift.contractViolationCount, "Both TS type and Python @dataclass consumers should fail on missing settledAmountCents")

        // Restore valid DTO and controller
        javaRecordDto.delete()
        dtoFile.writeText(
            """
            package com.fintech.ledger.dto
            data class TransferResponseDto(val transferId: String, val settledAmountCents: Long, val status: String)
            """.trimIndent()
        )
        ctrlFile.writeText(
            """
            package com.fintech.ledger.controller
            import com.fintech.ledger.service.LedgerService
            import com.fintech.ledger.dto.TransferResponseDto
            import org.springframework.http.ResponseEntity
            import org.springframework.web.bind.annotation.RestController
            import org.springframework.web.bind.annotation.RequestMapping
            import org.springframework.web.bind.annotation.PostMapping

            @RestController
            @RequestMapping("/v1/transfers")
            class LedgerController(val ledgerService: LedgerService) {
                @PostMapping("/{accountId}")
                fun createTransfer(): ResponseEntity<TransferResponseDto> = TODO()
            }
            """.trimIndent()
        )
        // SNEAKY AGENT MUTATION 4b: Multi-attribute @PostMapping + Kotlin inferred expression body + TS base URL variable & query param + @Qualifier in constructor
        tsClient.writeText(
            """
            export type TransferReceipt = {
                transferId: string;
                settledAmountCents: number;
            };
            export async function initiateTransfer(accountId: string): Promise<TransferReceipt> {
                const res = await axios.post(`${'$'}{API_BASE_URL}/v1/transfers/${'$'}{accountId}?audit=true`);
                return res.data;
            }
            """.trimIndent()
        )
        ctrlFile.writeText(
            """
            package com.fintech.ledger.controller
            import com.fintech.ledger.service.LedgerService
            import com.fintech.ledger.dto.TransferResponseDto
            import org.springframework.beans.factory.annotation.Qualifier
            import org.springframework.web.bind.annotation.RestController
            import org.springframework.web.bind.annotation.RequestMapping
            import org.springframework.web.bind.annotation.PostMapping

            @RestController
            @RequestMapping(path = ["/v1/transfers"], produces = ["application/json"])
            class LedgerController(@Qualifier("primaryLedger") val ledgerService: LedgerService) {
                @PostMapping(value = "/{accountId}", consumes = ["application/json"])
                fun createTransfer() = TransferResponseDto("tx-1", 10000L, "SETTLED")
            }
            """.trimIndent()
        )
        // Also verify `src/test/` files are ignored by production scan
        File(repoRoot, "src/test/kotlin/com/fintech/ledger/controller/LedgerControllerTest.kt").apply {
            parentFile.mkdirs()
            writeText(
                """
                package com.fintech.ledger.controller
                import com.fintech.ledger.repository.LedgerRepository
                class LedgerControllerTest(val repo: LedgerRepository)
                """.trimIndent()
            )
        }
        assertTrue(callVerifyCompliance().passed, "Multi-attribute annotations, @Qualifier ctor, inferred return DTO, TS base URL var, and src/test exclusion should pass")

        // SNEAKY AGENT MUTATION 4c: Agent changes controller return type to primitive String while TS/Python still expect DTO fields
        ctrlFile.writeText(
            """
            package com.fintech.ledger.controller
            import com.fintech.ledger.service.LedgerService
            import org.springframework.web.bind.annotation.RestController
            import org.springframework.web.bind.annotation.RequestMapping
            import org.springframework.web.bind.annotation.PostMapping

            @RestController
            @RequestMapping("/v1/transfers")
            class LedgerController(val ledgerService: LedgerService) {
                @PostMapping("/{accountId}")
                fun createTransfer(): String = "ok"
            }
            """.trimIndent()
        )
        val reportPrimitiveBreak = callVerifyCompliance()
        assertFalse(reportPrimitiveBreak.passed, "Changing controller return type from DTO to String should fail consumer contract checks")
        assertEquals(2, reportPrimitiveBreak.contractViolationCount)

        // Restore valid controller before refactoring test
        ctrlFile.writeText(
            """
            package com.fintech.ledger.controller
            import com.fintech.ledger.service.LedgerService
            import com.fintech.ledger.dto.TransferResponseDto
            import org.springframework.http.ResponseEntity
            import org.springframework.web.bind.annotation.RestController
            import org.springframework.web.bind.annotation.RequestMapping
            import org.springframework.web.bind.annotation.PostMapping

            @RestController
            @RequestMapping("/v1/transfers")
            class LedgerController(val ledgerService: LedgerService) {
                @PostMapping("/{accountId}")
                fun createTransfer(): ResponseEntity<TransferResponseDto> = TODO()
            }
            """.trimIndent()
        )
        assertTrue(callVerifyCompliance().passed, "Restored DTO and controller should pass")

        // SNEAKY AGENT MUTATION 5: Real Disk-Backed MCP Refactoring (EXTRACT_INTERFACE on disk)
        val extractRaw = requireNotNull(mcpServer.handleJsonRpcLine(
            """
            {
              "jsonrpc": "2.0",
              "id": 10,
              "method": "tools/call",
              "params": {
                "name": "execute_deterministic_refactoring",
                "arguments": {
                  "previewOnly": false,
                  "command": {
                    "type": "EXTRACT_INTERFACE",
                    "sourceClassId": "com.fintech.ledger.service.LedgerService",
                    "interfaceSimpleName": "LedgerPort",
                    "targetPackage": "com.fintech.ledger.port",
                    "memberNames": ["executeTransfer"]
                  }
                }
              }
            }
            """.trimIndent().lines().joinToString(" ")
        ))
        val extractRes = json.decodeFromJsonElement<McpCallToolResult>(
            requireNotNull(json.decodeFromString<JsonRpcResponse>(extractRaw).result)
        )
        assertFalse(extractRes.isError)

        // Verify the physical interface file was created on disk and source + caller files were updated on disk!
        val generatedInterfaceFile = File(repoRoot, "src/main/kotlin/com/fintech/ledger/port/LedgerPort.kt")
        assertTrue(generatedInterfaceFile.exists(), "Expected LedgerPort.kt to be written to disk")
        assertTrue(generatedInterfaceFile.readText().contains("interface LedgerPort"))
        assertTrue(svcFile.readText().contains("LedgerPort"), "Expected LedgerService.kt on disk to implement LedgerPort")
        assertTrue(ctrlFile.readText().contains("import com.fintech.ledger.port.LedgerPort"), "Expected LedgerController.kt on disk to import LedgerPort")

        // SNEAKY AGENT MUTATION 6: Agent deletes LedgerRepository.kt on disk; verify BlastRadiusDiffer catches REMOVED node
        repoFile.delete()
        val afterDeleteGraph = adapter.rescanFromDiskIfAvailable()
        val deleteDiff = BlastRadiusDiffer.diff(baselineGraph, afterDeleteGraph, maxDepth = 3)
        assertTrue("com.fintech.ledger.repository.LedgerRepository" in deleteDiff.removedNodeIds)
    }
}
