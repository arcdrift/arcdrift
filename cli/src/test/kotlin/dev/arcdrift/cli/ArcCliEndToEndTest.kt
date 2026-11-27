package dev.arcdrift.cli

import com.github.ajalt.clikt.testing.test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ArcCliEndToEndTest {

    @TempDir
    lateinit var tempDir: Path

    private fun seedCleanPolyglotRepo(root: File) {
        val specFile = File(root, "architecture.arc.mmd")
        specFile.writeText(
            """
            flowchart TB
                %% @arc:project name="checkout-platform" version="1.0"
                %% @arc:rule id="no-controller-to-repo" name="No Controller to Repo" from="*.controller.*" to="*.repository.*" policy="FORBIDDEN" rationale="Use Service layer"
            """.trimIndent()
        )

        val dtoFile = File(root, "backend/src/main/kotlin/com/acme/checkout/dto/OrderResponseDto.kt")
        dtoFile.parentFile.mkdirs()
        dtoFile.writeText(
            """
            package com.acme.checkout.dto
            data class OrderResponseDto(val orderId: String, val amountCents: Int)
            """.trimIndent()
        )

        val repoFile = File(root, "backend/src/main/kotlin/com/acme/checkout/repository/OrderRepository.kt")
        repoFile.parentFile.mkdirs()
        repoFile.writeText(
            """
            package com.acme.checkout.repository
            import org.springframework.stereotype.Repository
            @Repository
            class OrderRepository
            """.trimIndent()
        )

        val svcFile = File(root, "backend/src/main/kotlin/com/acme/checkout/service/OrderService.kt")
        svcFile.parentFile.mkdirs()
        svcFile.writeText(
            """
            package com.acme.checkout.service
            import com.acme.checkout.repository.OrderRepository
            import org.springframework.stereotype.Service
            @Service
            class OrderService(val repo: OrderRepository)
            """.trimIndent()
        )

        val ctrlFile = File(root, "backend/src/main/kotlin/com/acme/checkout/controller/OrderController.kt")
        ctrlFile.parentFile.mkdirs()
        ctrlFile.writeText(
            """
            package com.acme.checkout.controller
            import com.acme.checkout.service.OrderService
            import com.acme.checkout.dto.OrderResponseDto
            import org.springframework.web.bind.annotation.RestController
            import org.springframework.web.bind.annotation.RequestMapping
            import org.springframework.web.bind.annotation.GetMapping

            @RestController
            @RequestMapping("/v1/orders")
            class OrderController(val service: OrderService) {
                @GetMapping("/{id}")
                fun getOrder(): OrderResponseDto = OrderResponseDto("1", 500)
            }
            """.trimIndent()
        )

        val tsFile = File(root, "frontend/src/api/ordersClient.ts")
        tsFile.parentFile.mkdirs()
        tsFile.writeText(
            """
            export interface OrderView {
                orderId: string;
                amountCents: number;
            }
            export async function fetchOrder(id: string): Promise<OrderView> {
                const res = await fetch(`/v1/orders/${'$'}{id}`);
                return res.json();
            }
            """.trimIndent()
        )
    }

    @Test
    fun `arc scan, check, slice and diff work end-to-end on clean and violated repos`() {
        val repoDir = tempDir.resolve("repo").toFile().apply { mkdirs() }
        seedCleanPolyglotRepo(repoDir)

        val baselineOut = tempDir.resolve("baseline.arc.mmd").toFile()

        // 1. arc scan writes baseline .arc.mmd
        val scanResult = buildArcCli().test("scan ${repoDir.path} -o ${baselineOut.path}")
        assertEquals(0, scanResult.statusCode, "scan output: ${scanResult.output}")
        assertTrue(baselineOut.exists())
        val mmdContent = baselineOut.readText()
        assertTrue(mmdContent.contains("OrderController"))
        assertTrue(mmdContent.contains("ordersClient.ts"))

        // 2. arc check passes on clean repo and generates ArchUnit test
        val archUnitOut = tempDir.resolve("ArcDriftArchitectureTest.kt").toFile()
        val checkClean = buildArcCli().test(
            "check ${repoDir.path} --generate-archunit ${archUnitOut.path} --base-package com.acme.checkout"
        )
        assertEquals(0, checkClean.statusCode, "checkClean output: ${checkClean.output}")
        assertTrue(checkClean.stdout.contains("PASSED"))
        assertTrue(archUnitOut.exists())
        assertTrue(archUnitOut.readText().contains("val noControllerToRepo: ArchRule"))

        // 3. arc slice returns compact JSON around OrderController
        val sliceRes = buildArcCli().test("slice ${repoDir.path} --scope OrderController --depth 1")
        assertEquals(0, sliceRes.statusCode)
        assertTrue(sliceRes.stdout.contains("OrderController"))
        assertTrue(sliceRes.stdout.contains("OrderService"))

        // 4. Introduce an AI agent regression: OrderController directly imports OrderRepository AND TypeScript client expects `legacyTotal`
        val ctrlFile = File(repoDir, "backend/src/main/kotlin/com/acme/checkout/controller/OrderController.kt")
        ctrlFile.writeText(
            ctrlFile.readText().replace(
                "import com.acme.checkout.service.OrderService",
                "import com.acme.checkout.service.OrderService\nimport com.acme.checkout.repository.OrderRepository"
            )
        )
        val tsFile = File(repoDir, "frontend/src/api/ordersClient.ts")
        tsFile.writeText(
            tsFile.readText().replace("amountCents: number;", "legacyTotal: number;")
        )

        // 5. arc check now exits with code 1 and reports both rule and contract violations
        val checkViolated = buildArcCli().test("check ${repoDir.path}")
        assertEquals(1, checkViolated.statusCode)
        assertTrue(checkViolated.stdout.contains("FAILED"))
        assertTrue(checkViolated.stdout.contains("[RULE VIOLATION] no-controller-to-repo"))
        assertTrue(checkViolated.stdout.contains("[CONTRACT DRIFT]"))

        // 6. arc diff produces a rich Markdown PR Blast-Radius report
        val diffRes = buildArcCli().test("diff ${baselineOut.path} ${repoDir.path} --format markdown")
        assertEquals(0, diffRes.statusCode)
        assertTrue(diffRes.stdout.contains("## ⚡ ArcDrift Architecture Impact & Blast-Radius Report"))
        assertTrue(diffRes.stdout.contains("no-controller-to-repo"))
        assertTrue(diffRes.stdout.contains("legacyTotal"))

        // 7. arc check --format sarif and --format sonar emit standard CI/SonarQube payloads
        val sarifRes = buildArcCli().test("check ${repoDir.path} --format sarif")
        assertEquals(1, sarifRes.statusCode)
        assertTrue(sarifRes.stdout.contains("\"version\": \"2.1.0\""))
        assertTrue(sarifRes.stdout.contains("\"ruleId\": \"ARCDRIFT_CONTRACT_DRIFT\""))
        assertTrue(sarifRes.stdout.contains("\"ruleId\": \"ARCDRIFT_BOUNDARY_RULE\""))

        val sonarRes = buildArcCli().test("check ${repoDir.path} --format sonar")
        assertEquals(1, sonarRes.statusCode)
        assertTrue(sonarRes.stdout.contains("\"engineId\": \"arcdrift\""))
        assertTrue(sonarRes.stdout.contains("\"ruleId\": \"ARCDRIFT_CONTRACT_DRIFT\""))
        assertTrue(sonarRes.stdout.contains("\"softwareQuality\": \"RELIABILITY\""))
    }

    @Test
    fun `request-body DTO drift and multi-repo contract federation work end-to-end`() {
        val producerRepo = tempDir.resolve("billing-service").toFile().apply { mkdirs() }
        val consumerRepo = tempDir.resolve("web-portal").toFile().apply { mkdirs() }

        val prodCtrl = File(producerRepo, "src/main/kotlin/com/acme/billing/BillingController.kt")
        prodCtrl.parentFile.mkdirs()
        prodCtrl.writeText(
            """
            package com.acme.billing
            import org.springframework.web.bind.annotation.RestController
            import org.springframework.web.bind.annotation.RequestMapping
            import org.springframework.web.bind.annotation.PostMapping
            import org.springframework.web.bind.annotation.RequestBody

            data class UpgradePlanRequest(val planId: String, val seats: Int)
            data class SubscriptionStatusDto(val activePlan: String)

            @RestController
            @RequestMapping("/v2/billing")
            class BillingController {
                @PostMapping("/upgrade")
                fun upgradePlan(@RequestBody req: UpgradePlanRequest): SubscriptionStatusDto =
                    SubscriptionStatusDto(req.planId)
            }
            """.trimIndent()
        )

        val consTs = File(consumerRepo, "src/ee/billing/upgradeClient.ts")
        consTs.parentFile.mkdirs()
        consTs.writeText(
            """
            import axios from 'axios';

            export interface UpgradePayload {
                planId: string;
                seats: number;
            }

            export interface SubscriptionView {
                activePlan: string;
            }

            export async function submitUpgrade(payload: UpgradePayload) {
                return axios.post<SubscriptionView>('/v2/billing/upgrade', payload);
            }
            """.trimIndent()
        )

        // 1. Export producer contracts from billing-service
        val manifestFile = tempDir.resolve("billing.arc-contracts.json").toFile()
        val exportRes = buildArcCli().test("scan ${producerRepo.path} --export-contracts ${manifestFile.path}")
        assertEquals(0, exportRes.statusCode)
        assertTrue(manifestFile.exists())
        assertTrue(manifestFile.readText().contains("UpgradePlanRequest"))

        // 2. Without --upstream-contracts, consumerRepo fails with unresolved contract
        val checkWithoutUpstream = buildArcCli().test("check ${consumerRepo.path}")
        assertEquals(1, checkWithoutUpstream.statusCode)
        assertTrue(checkWithoutUpstream.stdout.contains("Unresolved REST contract"))

        // 3. With --upstream-contracts (including repeated flags), consumerRepo links and passes both request and response DTO checks
        val checkWithUpstream = buildArcCli().test(
            "check ${consumerRepo.path} --upstream-contracts ${manifestFile.path} --upstream-contracts ${manifestFile.path}"
        )
        assertEquals(0, checkWithUpstream.statusCode, "Expected pass: ${checkWithUpstream.stdout}")
        assertTrue(checkWithUpstream.stdout.contains("PASSED"))

        // 4. Mutate @RequestBody field in consumer (seats -> seatCount) while keeping response DTO intact
        consTs.writeText(consTs.readText().replace("seats: number;", "seatCount: number;"))
        val checkReqDrift = buildArcCli().test(
            "check ${consumerRepo.path} --upstream-contracts ${manifestFile.path}"
        )
        assertEquals(1, checkReqDrift.statusCode)
        assertTrue(checkReqDrift.stdout.contains("seatCount (missing in producer request body 'UpgradePlanRequest')"))
    }

    @Test
    fun `arc --version reports CLI version`() {
        val res = buildArcCli().test("--version")
        assertEquals(0, res.statusCode, "version output: ${res.output}")
        assertTrue(res.stdout.contains("arc version"), "Expected 'arc version' in stdout, got: ${res.stdout}")
    }

    @Test
    fun `arc check fails on dependency cycles and emits ARCDRIFT_DEPENDENCY_CYCLE in SARIF and SonarQube`() {
        val cycleRepo = tempDir.resolve("cycle-repo").toFile().apply { mkdirs() }
        val srcDir = File(cycleRepo, "src/main/kotlin/com/acme/cycle").apply { mkdirs() }
        File(srcDir, "ServiceA.kt").writeText(
            """
            package com.acme.cycle
            class ServiceA(val b: ServiceB)
            """.trimIndent()
        )
        File(srcDir, "ServiceB.kt").writeText(
            """
            package com.acme.cycle
            class ServiceB(val a: ServiceA)
            """.trimIndent()
        )

        val failByDefault = buildArcCli().test("check ${cycleRepo.path}")
        assertEquals(1, failByDefault.statusCode)
        assertTrue(failByDefault.stdout.contains("[CYCLE]"))

        val sarifCycle = buildArcCli().test("check ${cycleRepo.path} --format sarif")
        assertEquals(1, sarifCycle.statusCode)
        assertTrue(sarifCycle.stdout.contains("\"ruleId\": \"ARCDRIFT_DEPENDENCY_CYCLE\""))

        val sonarCycle = buildArcCli().test("check ${cycleRepo.path} --format sonar")
        assertEquals(1, sonarCycle.statusCode)
        assertTrue(sonarCycle.stdout.contains("\"ruleId\": \"ARCDRIFT_DEPENDENCY_CYCLE\""))

        // Verify scan --format json, check --format json -o, diff --format mmd / json -o, slice --level, and invalid format UsageError
        val scanJson = buildArcCli().test("scan ${cycleRepo.path} --format json")
        assertEquals(0, scanJson.statusCode)
        assertTrue(scanJson.stdout.contains("\"projectName\""))

        val checkJsonFile = tempDir.resolve("check-report.json").toFile()
        val checkJson = buildArcCli().test("check ${cycleRepo.path} --format json -o ${checkJsonFile.path}")
        assertEquals(1, checkJson.statusCode)
        assertTrue(checkJsonFile.exists())

        val baselineSpec = tempDir.resolve("cycle-base.arc.mmd").toFile()
        buildArcCli().test("scan ${cycleRepo.path} -o ${baselineSpec.path}")
        val diffMmd = buildArcCli().test("diff ${baselineSpec.path} ${baselineSpec.path} --format mmd")
        assertEquals(0, diffMmd.statusCode)
        assertTrue(diffMmd.stdout.contains("flowchart TB"))

        val diffJsonFile = tempDir.resolve("diff-report.json").toFile()
        val diffJson = buildArcCli().test("diff ${baselineSpec.path} ${cycleRepo.path} --format json -o ${diffJsonFile.path}")
        assertEquals(0, diffJson.statusCode)
        assertTrue(diffJsonFile.exists())

        val sliceLevel = buildArcCli().test("slice ${cycleRepo.path} --level L3_COMPONENT --depth 1")
        assertEquals(0, sliceLevel.statusCode)
        assertTrue(sliceLevel.stdout.contains("ServiceA"))

        val prevIn = System.`in`
        try {
            System.setIn(java.io.ByteArrayInputStream("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}""".toByteArray()))
            val mcpRes = buildArcCli().test("mcp ${cycleRepo.path}")
            assertEquals(0, mcpRes.statusCode)
        } finally {
            System.setIn(prevIn)
        }
    }

    @Test
    fun `CLI validates nonexistent paths, invalid C4 levels, negative depths, and MarkdownPrReporter escapes special chars`() {
        val missingDir = tempDir.resolve("does-not-exist").toFile()
        assertTrue(buildArcCli().test("scan ${missingDir.path}").statusCode != 0)
        assertTrue(buildArcCli().test("check ${missingDir.path}").statusCode != 0)

        val validDir = tempDir.resolve("valid-cli-dir").toFile().apply { mkdirs() }
        assertTrue(buildArcCli().test("check ${validDir.path} --spec ${missingDir.path}/bad.arc.mmd").statusCode != 0)
        assertTrue(buildArcCli().test("slice ${validDir.path} --level INVALID_LEVEL").statusCode != 0)
        assertTrue(buildArcCli().test("slice ${validDir.path} --depth -1").statusCode != 0)
        assertTrue(buildArcCli().test("diff ${missingDir.path}/base.arc.mmd ${validDir.path}").statusCode != 0)

        val baseSpec = tempDir.resolve("base-clean.arc.mmd").toFile().apply {
            writeText("flowchart TB\n    %% @arc:project name=\"pipe|proj`name\"\n")
        }
        assertTrue(buildArcCli().test("diff ${baseSpec.path} ${validDir.path} --depth -2").statusCode != 0)
        assertTrue(buildArcCli().test("check ${validDir.path} --upstream-contracts ${missingDir.path}/missing.json").statusCode != 0)

        // MarkdownPrReporter escaping & TypeScript/Python shortDisplayId formatting
        assertEquals("orders.service", dev.arcdrift.cli.reporter.MarkdownPrReporter.shortDisplayId("ts.frontend.src.api.orders.service"))
        assertEquals("OrderCard.tsx", dev.arcdrift.cli.reporter.MarkdownPrReporter.shortDisplayId("ts.frontend.src.OrderCard.tsx"))
        assertEquals("BillingController", dev.arcdrift.cli.reporter.MarkdownPrReporter.shortDisplayId("upstream:billing:com.acme.BillingController"))

        val diffReport = dev.arcdrift.core.analysis.BlastRadiusDiffer.diff(
            dev.arcdrift.core.model.ArcGraph(projectName = "proj`1"),
            dev.arcdrift.core.model.ArcGraph(
                projectName = "proj`1",
                nodes = listOf(dev.arcdrift.core.model.ArcNode("ts.web.orders.service", "orders.service.ts"))
            )
        )
        val md = dev.arcdrift.cli.reporter.MarkdownPrReporter.generatePrReport(
            projectName = "proj`1",
            diffReport = diffReport,
            cycles = emptyList(),
            ruleViolations = listOf(
                dev.arcdrift.core.rules.RuleViolation(
                    ruleId = "r1",
                    ruleName = "No <RawHtml> | Pipes",
                    policy = dev.arcdrift.core.model.RulePolicy.FORBIDDEN,
                    sourceNodeId = "a`b",
                    targetNodeId = "c|d",
                    offendingEdgeId = "e1",
                    message = "Illegal List<OrderDto>\ninjection",
                    remediationHint = "Extract <Port>"
                )
            ),
            contractViolations = emptyList()
        )
        assertTrue(md.contains("``proj`1``"))
        assertTrue(md.contains("`orders.service`"))
        assertTrue(md.contains("&lt;OrderDto&gt;"))
        assertTrue(!md.contains("<RawHtml>"))
    }
}
