package dev.arcdrift.cli

import dev.arcdrift.core.analysis.ArchitectureSlicer
import dev.arcdrift.core.contracts.PolyglotContractBridge
import dev.arcdrift.core.model.C4Level
import dev.arcdrift.core.model.NodeKind
import dev.arcdrift.core.refactoring.InMemoryGraphRefactoringAdapter
import dev.arcdrift.core.refactoring.RefactoringCommand
import dev.arcdrift.core.scanner.PolyglotRepoScanner
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import kotlin.system.measureTimeMillis

class GateG4RealWorldBenchmarkTest {

    private val json = Json { prettyPrint = false; encodeDefaults = false }

    @Test
    fun `run full Gate G4 benchmark across real-world open-source repositories`() {
        val baseDir = File("/tmp/arcdrift-g4-benchmarks")
        if (!baseDir.isDirectory) return

        val repos = listOf(
            "spring-petclinic-reactjs" to "OwnerRestController",
            "spring-petclinic-kotlin" to "OwnerController",
            "spring-petclinic-angular" to "visit.service",
            "jhipster-sample-app-react" to "OperationResource",
            "jhipster-sample-app" to "OperationResource",
            "full-stack-fastapi-template" to "items",
            "dispatch" to "workflow",
            "kafka-ui" to "ClustersController",
            "komga" to "UserController",
            "tolgee-platform" to "ApiKeyController"
        )

        println("=== ARCDRIFT GATE G4 REAL-WORLD OSS BENCHMARK ===")
        for ((repoName, seedScope) in repos) {
            val repoDir = File(baseDir, repoName)
            if (!repoDir.isDirectory) continue

            // Count raw source files & characters
            val sourceFiles = repoDir.walkTopDown()
                .onEnter { it.name !in setOf(".git", ".gradle", "node_modules", "build", "dist", "target", "tests", "test", "e2e", "cypress") }
                .filter { it.isFile && it.extension.lowercase() in setOf("kt", "java", "ts", "tsx", "py") }
                .toList()
            val rawSourceChars = sourceFiles.sumOf { runCatching { it.length() }.getOrDefault(0L) }
            val rawSourceTokensApprox = rawSourceChars / 4

            var graph = dev.arcdrift.core.model.ArcGraph(projectName = repoName)
            val elapsedMs = measureTimeMillis {
                graph = PolyglotRepoScanner.scanRepository(repoDir)
            }

            val fullGraphJson = json.encodeToString(graph)
            val fullGraphTokensApprox = fullGraphJson.length / 4

            val slice = ArchitectureSlicer.slice(
                graph = graph,
                scopeQuery = seedScope,
                maxC4Level = C4Level.L3_COMPONENT,
                depth = 2
            )
            val sliceJson = json.encodeToString(slice)
            val sliceTokensApprox = sliceJson.length / 4
            val tokenReductionVsSourcePct = if (rawSourceTokensApprox > 0) {
                100.0 * (1.0 - sliceTokensApprox.toDouble() / rawSourceTokensApprox.toDouble())
            } else 0.0

            val producers = graph.nodes.flatMap { n -> n.contracts.filter { it.role == "PRODUCER" }.map { n.id to it } }
            val consumers = graph.nodes.flatMap { n -> n.contracts.filter { it.role == "CONSUMER" }.map { n.id to it } }
            val bridgeResult = PolyglotContractBridge.linkAndVerify(graph)

            println("\n--- REPO: $repoName ---")
            println("Files: ${sourceFiles.size} | Scan Time: ${elapsedMs}ms")
            println("Nodes: ${graph.nodes.size} | Edges: ${graph.edges.size}")
            println("Producer Endpoints: ${producers.size} | Consumer Endpoints: ${consumers.size}")
            println("Linked Contract Edges: ${bridgeResult.linkedEdges.size} | Clean-HEAD Violations: ${bridgeResult.violations.size}")
            println("Tokens (approx chars/4): Raw Source=${rawSourceTokensApprox} | Full Graph=${fullGraphTokensApprox} | 2-Hop L3 Slice('$seedScope')=${sliceTokensApprox} (${String.format("%.1f", tokenReductionVsSourcePct)}% reduction vs raw source)")
            producers.take(6).forEach { (id, c) ->
                println("  [PROD] $id -> ${c.protocol} ${c.methodOrAction} ${c.pathOrTopic} (dto=${c.responseDto}, fields=${c.dtoFields.keys})")
            }
            consumers.take(6).forEach { (id, c) ->
                println("  [CONS] $id -> ${c.protocol} ${c.methodOrAction} ${c.pathOrTopic} (dto=${c.responseDto}, fields=${c.dtoFields.keys})")
            }
            bridgeResult.violations.forEach { v ->
                println("  [VIOLATION] consumer=${v.consumerNodeId} producer=${v.producerNodeId} path=${v.pathOrTopic} missing=${v.missingOrMismatchedFields} msg=${v.message}")
            }
        }

        // Part 2: Controlled Contract-Break Mutation Injection Benchmark on JHipster React
        val jhipsterDir = File(baseDir, "jhipster-sample-app-react")
        if (jhipsterDir.isDirectory) {
            println("\n=== GATE G4 MUTATION INJECTION BENCHMARK (jhipster-sample-app-react) ===")
            val opResourcePath = "src/main/java/io/github/jhipster/sample/web/rest/OperationResource.java"
            val opDtoPath = "src/main/java/io/github/jhipster/sample/service/dto/OperationDTO.java"
            val bankResourcePath = "src/main/java/io/github/jhipster/sample/web/rest/BankAccountResource.java"
            val labelResourcePath = "src/main/java/io/github/jhipster/sample/web/rest/LabelResource.java"
            val accountResourcePath = "src/main/java/io/github/jhipster/sample/web/rest/AccountResource.java"

            val origOpRes = File(jhipsterDir, opResourcePath).readText()
            val origOpDto = File(jhipsterDir, opDtoPath).readText()
            val origBankRes = File(jhipsterDir, bankResourcePath).readText()
            val origLabelRes = File(jhipsterDir, labelResourcePath).readText()
            val origAccRes = File(jhipsterDir, accountResourcePath).readText()
            val pwDtoPath = "src/main/java/io/github/jhipster/sample/service/dto/PasswordChangeDTO.java"
            val origPwDto = File(jhipsterDir, pwDtoPath).readText()

            val mutations = listOf(
                "M1: Rename /api/operations -> /api/operationz" to mapOf(
                    opResourcePath to origOpRes.replace("\"/api/operations\"", "\"/api/operationz\"")
                ),
                "M2: Rename OperationDTO.description -> OperationDTO.details" to mapOf(
                    opDtoPath to origOpDto.replace("private String description;", "private String details;")
                ),
                "M3: Rename OperationDTO.amount -> OperationDTO.totalAmount" to mapOf(
                    opDtoPath to origOpDto.replace("private BigDecimal amount;", "private BigDecimal totalAmount;")
                ),
                "M4: Rename /api/bank-accounts -> /api/bank-accountz" to mapOf(
                    bankResourcePath to origBankRes.replace("\"/api/bank-accounts\"", "\"/api/bank-accountz\"")
                ),
                "M5: Rename /api/labels -> /api/tags" to mapOf(
                    labelResourcePath to origLabelRes.replace("\"/api/labels\"", "\"/api/tags\"")
                ),
                "M6: Remove GET /api/account endpoint" to mapOf(
                    accountResourcePath to origAccRes.replace("@GetMapping(\"/account\")", "@GetMapping(\"/account-deprecated\")")
                ),
                "M7: Rename @RequestBody PasswordChangeDTO.newPassword -> updatedPassword" to mapOf(
                    pwDtoPath to origPwDto.replace("private String newPassword;", "private String updatedPassword;")
                )
            )

            var caughtCount = 0
            for ((name, overrides) in mutations) {
                val mutatedGraph = PolyglotRepoScanner.scanRepository(
                    rootDir = jhipsterDir,
                    fileContentOverrides = overrides
                )
                val res = PolyglotContractBridge.linkAndVerify(mutatedGraph)
                val caught = res.violations.isNotEmpty()
                if (caught) caughtCount++
                println("  [$name] -> Caught=$caught (violations=${res.violations.size}: ${res.violations.firstOrNull()?.message})")
            }
            println("Mutation Recall (JHipster): $caughtCount / ${mutations.size}")
        }

        val fastApiDir = File(baseDir, "full-stack-fastapi-template")
        if (fastApiDir.isDirectory) {
            val modelsPath = "backend/app/models.py"
            val origModels = File(fastApiDir, modelsPath).readText()
            val mutatedFastApiGraph = PolyglotRepoScanner.scanRepository(
                rootDir = fastApiDir,
                fileContentOverrides = mapOf(
                    modelsPath to origModels.replace(
                        "class ItemCreate(ItemBase):\n    pass",
                        "class ItemCreate(SQLModel):\n    heading: str\n    description: str | None = None"
                    )
                )
            )
            val m8Res = PolyglotContractBridge.linkAndVerify(mutatedFastApiGraph)
            println("  [M8: FastAPI Request-Body ItemCreate.title -> heading] -> Caught=${m8Res.violations.isNotEmpty()} (violations=${m8Res.violations.size}: ${m8Res.violations.firstOrNull()?.message})")
        }

        val tolgeeDir = File(baseDir, "tolgee-platform")
        if (tolgeeDir.isDirectory) {
            val tolgeeGraph = PolyglotRepoScanner.scanRepository(tolgeeDir)
            val cloudBillingEndpoints = listOf(
                "GET" to "/v2/organizations/{organizationId}/billing/customer-portal",
                "GET" to "/v2/organizations/{organizationId}/billing/subscription",
                "GET" to "/v2/organizations/{organizationId}/billing/plans",
                "PUT" to "/v2/organizations/{organizationId}/billing/auto-upgrade",
                "POST" to "/v2/projects/{projectId}/billing/order-translation",
                "GET" to "/v2/projects/{projectId}/billing/order-translation/preferred-agency",
                "GET" to "/v2/billing/translation-agency"
            )
            val upstreamManifest = dev.arcdrift.core.contracts.ContractFederationManifest(
                exportedFromProjects = listOf("tolgee-cloud"),
                producers = cloudBillingEndpoints.map { (verb, path) ->
                    dev.arcdrift.core.contracts.ExportedContractProducer(
                        projectName = "tolgee-cloud",
                        nodeId = "io.tolgee.cloud.billing.BillingController",
                        nodeLabel = "BillingController",
                        filePath = "src/main/kotlin/io/tolgee/cloud/billing/BillingController.kt",
                        contract = dev.arcdrift.core.model.ContractEndpoint(
                            protocol = "REST",
                            methodOrAction = verb,
                            pathOrTopic = path,
                            role = "PRODUCER"
                        )
                    )
                }
            )
            val federatedRes = PolyglotContractBridge.linkAndVerify(tolgeeGraph, upstreamManifest)
            println("  [Multi-Repo Federation: tolgee-platform + tolgee-cloud manifest] -> LinkedEdges=${federatedRes.linkedEdges.size}, RemainingViolations=${federatedRes.violations.size}")
        }

        val petclinicBackendDir = File(baseDir, "spring-petclinic-reactjs")
        val petclinicAngularDir = File(baseDir, "spring-petclinic-angular")
        if (petclinicBackendDir.isDirectory && petclinicAngularDir.isDirectory) {
            val backendGraph = PolyglotRepoScanner.scanRepository(petclinicBackendDir)
            val backendManifest = PolyglotContractBridge.exportProducerManifest(backendGraph)
            val angularGraph = PolyglotRepoScanner.scanRepository(petclinicAngularDir)
            val angularFederated = PolyglotContractBridge.linkAndVerify(angularGraph, backendManifest)
            println("  [Multi-Repo Federation: spring-petclinic-angular + spring-petclinic-rest manifest] -> LinkedEdges=${angularFederated.linkedEdges.size}, Violations=${angularFederated.violations.size}")
        }

        // Part 3: Deterministic Refactoring Benchmark on spring-petclinic-kotlin & jhipster-sample-app-react
        val petclinicKtDir = File(baseDir, "spring-petclinic-kotlin")
        if (petclinicKtDir.isDirectory) {
            println("\n=== GATE G4 DETERMINISTIC REFACTORING BENCHMARK (spring-petclinic-kotlin) ===")
            val tempCopy = Files.createTempDirectory("g4-refactor-petclinic").toFile()
            try {
                petclinicKtDir.copyRecursively(tempCopy, overwrite = true)
                val initialGraph = PolyglotRepoScanner.scanRepository(tempCopy)
                val adapter = InMemoryGraphRefactoringAdapter(initialGraph, tempCopy)

                val cmds = listOf(
                    "R1: EXTRACT_INTERFACE from ClinicService" to RefactoringCommand.ExtractInterface(
                        sourceClassId = "org.springframework.samples.petclinic.owner.OwnerController",
                        interfaceSimpleName = "OwnerControllerPort",
                        targetPackage = "org.springframework.samples.petclinic.owner",
                        memberNames = listOf("initCreationForm")
                    ),
                    "R2: CREATE_NODE AuditPort" to RefactoringCommand.CreateNode(
                        fqName = "org.springframework.samples.petclinic.system.AuditPort",
                        kind = "INTERFACE"
                    ),
                    "R3: ADD_DEPENDENCY WelcomeController -> AuditPort" to RefactoringCommand.AddDependency(
                        sourceClassId = "org.springframework.samples.petclinic.system.WelcomeController",
                        targetTypeFqName = "org.springframework.samples.petclinic.system.AuditPort",
                        relationKind = "DEPENDS_ON"
                    ),
                    "R4: MOVE_CLASS_OR_PACKAGE Vet -> model" to RefactoringCommand.MoveClassOrPackage(
                        targetNodeId = "org.springframework.samples.petclinic.vet.Vet",
                        destinationPackage = "org.springframework.samples.petclinic.model"
                    ),
                    "R5: RENAME_SYMBOL WelcomeController -> HomeController" to RefactoringCommand.RenameSymbol(
                        targetNodeId = "org.springframework.samples.petclinic.system.WelcomeController",
                        newSimpleName = "HomeController"
                    )
                )

                var successCount = 0
                for ((label, cmd) in cmds) {
                    val res = adapter.execute(cmd, previewOnly = false)
                    val rescanned = PolyglotRepoScanner.scanRepository(tempCopy)
                    val syntaxErrorsInTouchedKt = res.touchedFiles
                        .filter { it.endsWith(".kt") }
                        .map { File(tempCopy, it) }
                        .filter { it.exists() }
                        .count { f -> dev.arcdrift.core.ast.JvmAstRewriters.forFileName(f.name).hasSyntaxErrors(f.readText(), f.name) }
                    val ok = res.success && rescanned.nodes.isNotEmpty() && syntaxErrorsInTouchedKt == 0
                    if (ok) successCount++
                    println("  [$label] -> success=${res.success}, syntaxErrors=$syntaxErrorsInTouchedKt, touched=${res.touchedFiles}, summary=${res.summary}")
                }
                println("Refactoring Accuracy: $successCount / ${cmds.size}")
            } finally {
                tempCopy.deleteRecursively()
            }
        }
    }
}
