package dev.arcdrift.core.refactoring

import dev.arcdrift.core.model.EdgeKind
import dev.arcdrift.core.scanner.PolyglotRepoScanner
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DiskBackedRefactoringTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `executes CreateNode, AddDependency, RenameSymbol, MoveClassOrPackage, and ExtractInterface on real files on disk`() {
        val repoRoot = tempDir.resolve("disk-refactor-repo").toFile().apply { mkdirs() }
        val srcRoot = File(repoRoot, "src/main/kotlin").apply { mkdirs() }

        val billingSvcFile = File(srcRoot, "com/acme/billing/BillingService.kt").apply {
            parentFile.mkdirs()
            writeText(
                """
                package com.acme.billing

                class BillingService {
                    fun charge(amountCents: Long): Boolean = true
                }
                """.trimIndent()
            )
        }

        val orderCtrlFile = File(srcRoot, "com/acme/order/OrderController.kt").apply {
            parentFile.mkdirs()
            writeText(
                """
                package com.acme.order

                import com.acme.billing.BillingService

                class OrderController(val billing: BillingService) {
                    fun checkout(): Boolean = billing.charge(500L)
                }
                """.trimIndent()
            )
        }

        val initialGraph = PolyglotRepoScanner.scanRepository(repoRoot)
        val adapter = InMemoryGraphRefactoringAdapter(initialGraph, repoRoot)

        // 1. CREATE_NODE on disk
        val createRes = adapter.execute(
            RefactoringCommand.CreateNode(
                fqName = "com.acme.audit.AuditLogger",
                kind = "SERVICE_BEAN",
                stereotypes = listOf("@Service")
            ),
            previewOnly = false
        )
        assertTrue(createRes.success)
        val createdFile = File(srcRoot, "com/acme/audit/AuditLogger.kt")
        assertTrue(createdFile.exists(), "Expected AuditLogger.kt to be created on disk")
        assertTrue(createdFile.readText().contains("@Service"))
        assertTrue(createdFile.readText().contains("class AuditLogger"))

        // 2. ADD_DEPENDENCY on disk
        val addDepRes = adapter.execute(
            RefactoringCommand.AddDependency(
                sourceClassId = "com.acme.order.OrderController",
                targetTypeFqName = "com.acme.audit.AuditLogger",
                relationKind = EdgeKind.DEPENDS_ON
            ),
            previewOnly = false
        )
        assertTrue(addDepRes.success)
        assertTrue(
            orderCtrlFile.readText().contains("import com.acme.audit.AuditLogger"),
            "Expected OrderController.kt on disk to import AuditLogger"
        )

        // 3. RENAME_SYMBOL on disk
        val renameRes = adapter.execute(
            RefactoringCommand.RenameSymbol(
                targetNodeId = "com.acme.billing.BillingService",
                newSimpleName = "PaymentProcessor"
            ),
            previewOnly = false
        )
        assertTrue(renameRes.success)
        assertFalse(billingSvcFile.exists(), "Old BillingService.kt should be renamed")
        val renamedFile = File(srcRoot, "com/acme/billing/PaymentProcessor.kt")
        assertTrue(renamedFile.exists(), "Expected PaymentProcessor.kt to exist on disk")
        assertTrue(renamedFile.readText().contains("class PaymentProcessor"))
        assertTrue(
            orderCtrlFile.readText().contains("import com.acme.billing.PaymentProcessor"),
            "OrderController.kt on disk should have updated import to PaymentProcessor"
        )
        assertTrue(
            orderCtrlFile.readText().contains("val billing: PaymentProcessor"),
            "OrderController.kt on disk should reference PaymentProcessor"
        )

        // 4. MOVE_CLASS_OR_PACKAGE on disk
        val moveRes = adapter.execute(
            RefactoringCommand.MoveClassOrPackage(
                targetNodeId = "com.acme.billing.PaymentProcessor",
                destinationPackage = "com.acme.payment.core"
            ),
            previewOnly = false
        )
        assertTrue(moveRes.success)
        val movedFile = File(srcRoot, "com/acme/payment/core/PaymentProcessor.kt")
        assertTrue(movedFile.exists(), "Expected PaymentProcessor.kt to be moved to com/acme/payment/core/")
        assertTrue(movedFile.readText().contains("package com.acme.payment.core"))
        assertTrue(
            orderCtrlFile.readText().contains("import com.acme.payment.core.PaymentProcessor"),
            "OrderController.kt on disk should have updated package import"
        )

        // 5. EXTRACT_INTERFACE on disk
        val extractRes = adapter.execute(
            RefactoringCommand.ExtractInterface(
                sourceClassId = "com.acme.payment.core.PaymentProcessor",
                interfaceSimpleName = "PaymentGatewayPort",
                targetPackage = "com.acme.payment.port",
                memberNames = listOf("charge")
            ),
            previewOnly = false
        )
        assertTrue(extractRes.success)
        val interfaceFile = File(srcRoot, "com/acme/payment/port/PaymentGatewayPort.kt")
        assertTrue(interfaceFile.exists(), "Expected PaymentGatewayPort.kt to be created on disk")
        assertTrue(interfaceFile.readText().contains("interface PaymentGatewayPort"))
        assertTrue(interfaceFile.readText().contains("fun charge(amountCents: Long): Boolean"))
        assertTrue(movedFile.readText().contains("PaymentGatewayPort"))
        assertTrue(orderCtrlFile.readText().contains("import com.acme.payment.port.PaymentGatewayPort"))

        // Verify fresh cold scan of the repository from disk matches the refactored state
        val freshScan = PolyglotRepoScanner.scanRepository(repoRoot)
        assertNotNull(freshScan.nodeMap()["com.acme.payment.port.PaymentGatewayPort"])
        assertNotNull(freshScan.nodeMap()["com.acme.payment.core.PaymentProcessor"])
        assertNotNull(freshScan.nodeMap()["com.acme.audit.AuditLogger"])
        assertEquals(
            1,
            freshScan.edges.count {
                it.sourceId == "com.acme.order.OrderController" &&
                    it.targetId == "com.acme.payment.port.PaymentGatewayPort"
            }
        )
    }

    @Test
    fun `ExtractInterface preserves TypeScript HTTP_CONTRACT consumers, copies DTO imports, and infers expression return types`() {
        val repoRoot = tempDir.resolve("polyglot-extract-repo").toFile().apply { mkdirs() }
        val srcRoot = File(repoRoot, "src/main/kotlin").apply { mkdirs() }

        File(srcRoot, "com/acme/dto/OrderDto.kt").apply {
            parentFile.mkdirs()
            writeText(
                """
                package com.acme.dto

                data class OrderDto(val id: String, val totalCents: Long)
                """.trimIndent()
            )
        }
        File(srcRoot, "com/acme/repo/OrderRepository.kt").apply {
            parentFile.mkdirs()
            writeText(
                """
                package com.acme.repo

                class OrderRepository {
                    fun findOrder(id: String) = id
                }
                """.trimIndent()
            )
        }
        File(srcRoot, "com/acme/controller/OrderController.kt").apply {
            parentFile.mkdirs()
            writeText(
                """
                package com.acme.controller

                import com.acme.dto.OrderDto
                import com.acme.repo.OrderRepository
                import org.springframework.web.bind.annotation.RestController
                import org.springframework.web.bind.annotation.GetMapping
                import org.springframework.web.bind.annotation.PathVariable

                @RestController
                class OrderController(val repo: OrderRepository) {
                    @GetMapping("/api/v1/orders/{id}")
                    fun getOrder(@PathVariable id: String): OrderDto = OrderDto(id, 2500L)
                }
                """.trimIndent()
            )
        }
        val tsFile = File(repoRoot, "web/ordersClient.ts").apply {
            parentFile.mkdirs()
            writeText(
                """
                export async function fetchOrder(id: string) {
                  return fetch(`/api/v1/orders/${'$'}{id}`);
                }
                """.trimIndent()
            )
        }
        val originalTsContent = tsFile.readText()

        val initialGraph = PolyglotRepoScanner.scanRepository(repoRoot)
        val adapter = InMemoryGraphRefactoringAdapter(initialGraph, repoRoot)

        // 1. Extract interface from OrderRepository (which has expression-body `fun findOrder(id: String) = id`)
        val repoExtract = adapter.execute(
            RefactoringCommand.ExtractInterface(
                sourceClassId = "com.acme.repo.OrderRepository",
                interfaceSimpleName = "OrderRepositoryPort",
                targetPackage = "com.acme.repo"
            ),
            previewOnly = false
        )
        assertTrue(repoExtract.success)
        val repoPortFile = File(srcRoot, "com/acme/repo/OrderRepositoryPort.kt")
        assertTrue(repoPortFile.exists())
        assertTrue(
            repoPortFile.readText().contains("fun findOrder(id: String): String"),
            "Expected expression-body return type String to be inferred instead of Unit, got:\n${repoPortFile.readText()}"
        )

        // 2. Extract interface from OrderController (which has incoming HTTP_CONTRACT edge from ordersClient.ts and returns OrderDto)
        val ctrlExtract = adapter.execute(
            RefactoringCommand.ExtractInterface(
                sourceClassId = "com.acme.controller.OrderController",
                interfaceSimpleName = "OrderControllerPort",
                targetPackage = "com.acme.controller"
            ),
            previewOnly = false
        )
        assertTrue(ctrlExtract.success)
        val ctrlPortFile = File(srcRoot, "com/acme/controller/OrderControllerPort.kt")
        assertTrue(ctrlPortFile.exists())
        assertTrue(
            ctrlPortFile.readText().contains("import com.acme.dto.OrderDto"),
            "Expected OrderControllerPort.kt to include needed import com.acme.dto.OrderDto, got:\n${ctrlPortFile.readText()}"
        )
        assertEquals(
            originalTsContent,
            tsFile.readText(),
            "TypeScript consumer file ordersClient.ts must never be modified by JVM ExtractInterface"
        )
    }

    @Test
    fun `CreateNode GHOST_SPEC persists to architecture_arc_mmd and materializing removes Ghost placeholder`() {
        val repoRoot = tempDir.resolve("ghost-spec-repo").toFile().apply { mkdirs() }
        val srcRoot = File(repoRoot, "src/main/kotlin").apply { mkdirs() }

        File(srcRoot, "com/acme/service/OrderService.kt").apply {
            parentFile.mkdirs()
            writeText(
                """
                package com.acme.service

                class OrderService {
                    fun process(): Boolean = true
                }
                """.trimIndent()
            )
        }

        val initialGraph = PolyglotRepoScanner.scanRepository(repoRoot)
        val adapter = InMemoryGraphRefactoringAdapter(initialGraph, repoRoot)

        // 1. Add a GHOST_SPEC component
        val ghostRes = adapter.execute(
            RefactoringCommand.CreateNode(
                fqName = "com.acme.spec.PlannedComponent1",
                kind = "GHOST_SPEC",
                stereotypes = listOf("«Ghost Spec»")
            ),
            previewOnly = false
        )
        assertTrue(ghostRes.success)
        val specFile = File(repoRoot, "architecture.arc.mmd")
        assertTrue(specFile.exists(), "Expected architecture.arc.mmd to be created with GHOST_SPEC")
        val rescannedWithGhost = PolyglotRepoScanner.scanRepository(repoRoot)
        val ghostNode = rescannedWithGhost.nodeMap()["com.acme.spec.PlannedComponent1"]
        assertNotNull(ghostNode, "Expected GHOST_SPEC node to survive repository rescan")
        assertEquals(dev.arcdrift.core.model.NodeKind.GHOST_SPEC, ghostNode.kind)

        // 2. Materialize the GHOST_SPEC into a real INTERFACE on disk
        val materializeRes = adapter.execute(
            RefactoringCommand.CreateNode(
                fqName = "com.acme.spec.PlannedComponent1",
                kind = "INTERFACE",
                stereotypes = listOf("@Component")
            ),
            previewOnly = false
        )
        assertTrue(materializeRes.success)
        val createdKt = File(srcRoot, "com/acme/spec/PlannedComponent1.kt")
        assertTrue(createdKt.exists(), "Expected real PlannedComponent1.kt to exist on disk")
        val afterMaterialize = PolyglotRepoScanner.scanRepository(repoRoot)
        val realNode = afterMaterialize.nodeMap()["com.acme.spec.PlannedComponent1"]
        assertNotNull(realNode)
        assertEquals(dev.arcdrift.core.model.NodeKind.INTERFACE, realNode.kind)
    }

    @Test
    fun `RenameSymbol rewrites Java constructors and MoveClassOrPackage only rewrites references to the moved package`() {
        val repoRoot = tempDir.resolve("java-ctor-and-move-repo").toFile().apply { mkdirs() }
        val javaRoot = File(repoRoot, "src/main/java").apply { mkdirs() }

        val billingFile = File(javaRoot, "com/acme/billing/BillingService.java").apply {
            parentFile.mkdirs()
            writeText(
                """
                package com.acme.billing;
                public class BillingService {
                    private final String BillingServiceHelper = "helper";
                    public BillingService() {}
                }
                """.trimIndent()
            )
        }
        val otherHandlerFile = File(javaRoot, "com/acme/other/PaymentProcessor.java").apply {
            parentFile.mkdirs()
            writeText(
                """
                package com.acme.other;
                public class PaymentProcessor {}
                """.trimIndent()
            )
        }
        val otherConsumerFile = File(javaRoot, "com/acme/other/OtherConsumer.java").apply {
            parentFile.mkdirs()
            writeText(
                """
                package com.acme.other;
                public class OtherConsumer {
                    private final PaymentProcessor local = new PaymentProcessor();
                }
                """.trimIndent()
            )
        }

        val initialGraph = PolyglotRepoScanner.scanRepository(repoRoot)
        val adapter = InMemoryGraphRefactoringAdapter(initialGraph, repoRoot)

        // 1. Rename Java class with constructor; verify constructor is renamed and BillingServiceHelper identifier is untouched
        val renameRes = adapter.execute(
            RefactoringCommand.RenameSymbol("com.acme.billing.BillingService", "PaymentGateway"),
            previewOnly = false
        )
        assertTrue(renameRes.success)
        assertFalse(billingFile.exists())
        val renamedJavaFile = File(javaRoot, "com/acme/billing/PaymentGateway.java")
        assertTrue(renamedJavaFile.exists())
        assertTrue(renamedJavaFile.readText().contains("public PaymentGateway() {}"))
        assertTrue(renamedJavaFile.readText().contains("BillingServiceHelper"))

        // 2. Move com.acme.billing.PaymentGateway to com.acme.core while com.acme.other.PaymentProcessor remains untouched
        val moveRes = adapter.execute(
            RefactoringCommand.MoveClassOrPackage("com.acme.billing.PaymentGateway", "com.acme.core"),
            previewOnly = false
        )
        assertTrue(moveRes.success)
        assertTrue(otherHandlerFile.exists())
        assertFalse(otherConsumerFile.readText().contains("import com.acme.core"), "Unrelated package consumer must not get bogus import")
    }

    @Test
    fun `ExtractInterface with explicit memberNames filters out FIELD and private members, and CreateNode GHOST_SPEC respects non-default arcdrift_arc_mmd spec file`() {
        val repoRoot = tempDir.resolve("extract-and-spec-discovery-repo").toFile().apply { mkdirs() }
        val customSpecFile = File(repoRoot, "arcdrift.arc.mmd").apply {
            writeText("flowchart TB\n  %% @arc:project name=\"custom-spec-proj\"\n")
        }
        val srcRoot = File(repoRoot, "src/main/kotlin").apply { mkdirs() }
        File(srcRoot, "com/acme/service/InvoiceService.kt").apply {
            parentFile.mkdirs()
            writeText(
                """
                package com.acme.service

                class InvoiceService(private val secretKey: String) {
                    val publicField: Int = 42
                    fun generateInvoice(id: String): String = id
                    private fun internalHelper(): String = secretKey
                }
                """.trimIndent()
            )
        }

        val initialGraph = PolyglotRepoScanner.scanRepository(repoRoot)
        val adapter = InMemoryGraphRefactoringAdapter(initialGraph, repoRoot)

        // Pass ALL member names (including fields and private methods, as UI previously did)
        val extractRes = adapter.execute(
            RefactoringCommand.ExtractInterface(
                sourceClassId = "com.acme.service.InvoiceService",
                interfaceSimpleName = "InvoiceServicePort",
                targetPackage = "com.acme.service",
                memberNames = listOf("secretKey", "publicField", "generateInvoice", "internalHelper")
            ),
            previewOnly = false
        )
        assertTrue(extractRes.success)
        val portFile = File(srcRoot, "com/acme/service/InvoiceServicePort.kt")
        assertTrue(portFile.exists())
        val portSource = portFile.readText()
        assertTrue(portSource.contains("fun generateInvoice(id: String): String"))
        assertFalse(portSource.contains("secretKey"), "FIELD secretKey must never be rendered as a method in extracted interface")
        assertFalse(portSource.contains("publicField"), "FIELD publicField must never be rendered as a method in extracted interface")
        assertFalse(portSource.contains("internalHelper"), "private method must never be extracted into interface")

        // Create GHOST_SPEC in repo that uses arcdrift.arc.mmd (not architecture.arc.mmd)
        val ghostRes = adapter.execute(
            RefactoringCommand.CreateNode("com.acme.service.GhostBilling", "GHOST_SPEC"),
            previewOnly = false
        )
        assertTrue(ghostRes.success)
        assertEquals(listOf("arcdrift.arc.mmd"), ghostRes.touchedFiles)
        assertFalse(File(repoRoot, "architecture.arc.mmd").exists(), "Must not create split-brain architecture.arc.mmd when arcdrift.arc.mmd exists")
        assertTrue(customSpecFile.readText().contains("com.acme.service.GhostBilling"))

        // Materializing the ghost node removes it from arcdrift.arc.mmd
        val matRes = adapter.execute(
            RefactoringCommand.CreateNode("com.acme.service.GhostBilling", "CLASS"),
            previewOnly = false
        )
        assertTrue(matRes.success)
        assertFalse(customSpecFile.readText().contains("GHOST_SPEC"))
    }

    @Test
    fun `RenameSymbol in multi-class file preserves file and sibling classes, CreateNode appends to existing multi-class file, and ExtractInterface preserves subclass inheritance`() {
        val repoRoot = tempDir.resolve("multi-class-and-subclass-repo").toFile().apply { mkdirs() }
        val srcRoot = File(repoRoot, "src/main/kotlin").apply { mkdirs() }

        val modelsFile = File(srcRoot, "com/acme/models/Models.kt").apply {
            parentFile.mkdirs()
            writeText(
                """
                package com.acme.models

                data class OrderDto(val id: String)
                data class CustomerDto(val name: String)
                """.trimIndent()
            )
        }
        val serviceFile = File(srcRoot, "com/acme/service/BaseOrderService.kt").apply {
            parentFile.mkdirs()
            writeText(
                """
                package com.acme.service
                import com.acme.models.OrderDto

                open class BaseOrderService {
                    open fun findOrder(id: String): OrderDto = OrderDto(id)
                }
                """.trimIndent()
            )
        }
        val subclassAndCallerFile = File(srcRoot, "com/acme/caller/SpecialOrderHandler.kt").apply {
            parentFile.mkdirs()
            writeText(
                """
                package com.acme.caller
                import com.acme.service.BaseOrderService

                class SpecialOrderHandler(
                    private val delegate: BaseOrderService
                ) : BaseOrderService()
                """.trimIndent()
            )
        }

        val initialGraph = PolyglotRepoScanner.scanRepository(repoRoot)
        val adapter = InMemoryGraphRefactoringAdapter(initialGraph, repoRoot)

        // 1. Rename OrderDto inside Models.kt -> Models.kt must stay in place and CustomerDto must survive
        val renameDtoRes = adapter.execute(
            RefactoringCommand.RenameSymbol("com.acme.models.OrderDto", "PurchaseOrderDto"),
            previewOnly = false
        )
        assertTrue(renameDtoRes.success)
        assertTrue(modelsFile.exists(), "Multi-class file Models.kt must not be deleted when renaming one class inside it")
        val modelsText = modelsFile.readText()
        assertTrue(modelsText.contains("data class PurchaseOrderDto(val id: String)"))
        assertTrue(modelsText.contains("data class CustomerDto(val name: String)"))
        assertTrue(serviceFile.readText().contains("import com.acme.models.PurchaseOrderDto"))

        // 2. CreateNode com.acme.models.Models -> Models.kt already exists on disk, so it must append `class Models` without destroying CustomerDto
        val createModelsRes = adapter.execute(
            RefactoringCommand.CreateNode("com.acme.models.Models", "CLASS"),
            previewOnly = false
        )
        assertTrue(createModelsRes.success)
        val afterCreateModels = modelsFile.readText()
        assertTrue(afterCreateModels.contains("data class CustomerDto(val name: String)"), "CreateNode must not clobber existing declarations in Models.kt")
        assertTrue(afterCreateModels.contains("class Models"))

        // 3. ExtractInterface on BaseOrderService -> SpecialOrderHandler's constructor parameter `delegate: BaseOrderService`
        // should rewire to `BaseOrderPort`, while its superclass constructor call `: BaseOrderService()` must remain `: BaseOrderService()`!
        val extractBaseRes = adapter.execute(
            RefactoringCommand.ExtractInterface(
                sourceClassId = "com.acme.service.BaseOrderService",
                interfaceSimpleName = "BaseOrderPort",
                targetPackage = "com.acme.port"
            ),
            previewOnly = false
        )
        assertTrue(extractBaseRes.success)
        val callerAfter = subclassAndCallerFile.readText()
        assertTrue(callerAfter.contains("private val delegate: BaseOrderPort"), "Constructor parameter type must rewire to BaseOrderPort, got:\n$callerAfter")
        assertTrue(callerAfter.contains(") : BaseOrderService()"), "Subclass constructor inheritance `: BaseOrderService()` must NOT be corrupted into `: BaseOrderPort()`, got:\n$callerAfter")
    }
}
