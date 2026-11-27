package dev.arcdrift.core.contracts

import dev.arcdrift.core.ast.TypeScriptAstParser
import dev.arcdrift.core.model.*
import dev.arcdrift.core.refactoring.InMemoryGraphRefactoringAdapter
import dev.arcdrift.core.refactoring.RefactoringCommand
import dev.arcdrift.core.rules.ArchUnitTestGenerator
import dev.arcdrift.core.scanner.PolyglotRepoScanner
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ContractBridgeAndArchUnitTest {

    @Test
    fun `TypeScriptAstParser resolves local url variable and PolyglotContractBridge flags orderz route mismatch on both consumer and controller`() {
        val tsSource = """
            export interface OrderPayload {
                id: string;
                totalCents: number;
            }
            export async function fetchOrder(id: string): Promise<OrderPayload> {
                const url = `/api/v1/orders/{id}`.replace('{id}', encodeURIComponent(id));
                const res = await fetch(url);
                return res.json();
            }
        """.trimIndent()
        val parsedTs = TypeScriptAstParser.parseTypeScriptFile(tsSource)
        assertEquals(1, parsedTs.contracts.size)
        assertEquals("/api/v1/orders/{id}", parsedTs.contracts.first().pathOrTopic)

        val graph = ArcGraph(
            projectName = "arcdrift-demo",
            nodes = listOf(
                ArcNode(
                    id = "com.acme.controller.OrderController",
                    label = "OrderController",
                    kind = NodeKind.REST_CONTROLLER,
                    contracts = listOf(
                        ContractEndpoint(
                            protocol = "REST",
                            methodOrAction = "GET",
                            pathOrTopic = "/api/v1/orderz/{id}",
                            role = "PRODUCER",
                            responseDto = "OrderPayload",
                            dtoFields = mapOf("id" to "String", "totalCents" to "Long")
                        )
                    )
                ),
                ArcNode(
                    id = "ts.web.ordersClient",
                    label = "ordersClient.ts",
                    kind = NodeKind.TS_CLIENT,
                    contracts = parsedTs.contracts
                )
            )
        )

        val result = PolyglotContractBridge.linkAndVerify(graph)
        assertEquals(1, result.violations.size)
        assertEquals(1, result.linkedEdges.size)
        assertEquals(DiffStatus.VIOLATION, result.linkedEdges.first().diffStatus)
        val enrichedCtrl = requireNotNull(result.enrichedGraph.nodeMap()["com.acme.controller.OrderController"])
        val enrichedClient = requireNotNull(result.enrichedGraph.nodeMap()["ts.web.ordersClient"])
        assertEquals(DiffStatus.VIOLATION, enrichedCtrl.diffStatus)
        assertEquals(DiffStatus.VIOLATION, enrichedClient.diffStatus)
        assertTrue(enrichedCtrl.violations.any { it.contains("/api/v1/orderz/{id}") })
    }

    @Test
    fun `PolyglotContractBridge links REST and Kafka contracts across Kotlin, TypeScript and Python and flags DTO drift`() {
        val graph = ArcGraph(
            projectName = "polyglot-commerce",
            nodes = listOf(
                ArcNode(
                    id = "com.acme.orders.OrderController",
                    label = "OrderController",
                    kind = NodeKind.REST_CONTROLLER,
                    contracts = listOf(
                        ContractEndpoint(
                            protocol = "REST",
                            methodOrAction = "GET",
                            pathOrTopic = "/v1/orders/{orderId}",
                            role = "PRODUCER",
                            responseDto = "OrderResponseDto",
                            dtoFields = mapOf(
                                "orderId" to "String",
                                "totalCents" to "Long",
                                "status" to "String"
                            )
                        )
                    )
                ),
                ArcNode(
                    id = "web.checkout.orderApiClient",
                    label = "orderApiClient.ts",
                    kind = NodeKind.TS_CLIENT,
                    contracts = listOf(
                        // Matches /v1/orders/{orderId} using template literal ${id}, but expects `totalAmount` (renamed to `totalCents` on backend!)
                        ContractEndpoint(
                            protocol = "REST",
                            methodOrAction = "GET",
                            pathOrTopic = "/v1/orders/\${id}",
                            role = "CONSUMER",
                            responseDto = "OrderDto",
                            dtoFields = mapOf(
                                "orderId" to "string",
                                "totalAmount" to "number"
                            )
                        )
                    )
                ),
                ArcNode(
                    id = "analytics.worker.order_sync",
                    label = "order_sync.py",
                    kind = NodeKind.PYTHON_CONSUMER,
                    contracts = listOf(
                        // Calls an endpoint that does not exist on backend
                        ContractEndpoint(
                            protocol = "REST",
                            methodOrAction = "POST",
                            pathOrTopic = "/v1/orders/legacy-export",
                            role = "CONSUMER"
                        )
                    )
                )
            )
        )

        val result = PolyglotContractBridge.linkAndVerify(graph)

        // 1 HTTP_CONTRACT edge linked between orderApiClient.ts and OrderController
        assertEquals(1, result.linkedEdges.size)
        val contractEdge = result.linkedEdges.first()
        assertEquals("web.checkout.orderApiClient", contractEdge.sourceId)
        assertEquals("com.acme.orders.OrderController", contractEdge.targetId)
        assertEquals(EdgeKind.HTTP_CONTRACT, contractEdge.kind)
        assertEquals(DiffStatus.VIOLATION, contractEdge.diffStatus)

        // 2 violations: 1 DTO field mismatch (`totalAmount` missing) + 1 unresolved endpoint (`/v1/orders/legacy-export`)
        assertEquals(2, result.violations.size)
        val dtoViolation = result.violations.find { it.consumerNodeId == "web.checkout.orderApiClient" }
        assertNotNull(dtoViolation)
        assertTrue(dtoViolation.missingOrMismatchedFields.any { it.contains("totalAmount") })

        val missingEndpointViolation = result.violations.find { it.consumerNodeId == "analytics.worker.order_sync" }
        assertNotNull(missingEndpointViolation)
        assertEquals("/v1/orders/legacy-export", missingEndpointViolation.pathOrTopic)

        // Verify collection-vs-scalar wire drift (List<String> vs string) is caught
        val arrayMismatchGraph = ArcGraph(
            projectName = "array-drift-test",
            nodes = listOf(
                ArcNode(
                    id = "com.acme.ItemController",
                    label = "ItemController",
                    kind = NodeKind.REST_CONTROLLER,
                    contracts = listOf(
                        ContractEndpoint(
                            protocol = "REST",
                            methodOrAction = "GET",
                            pathOrTopic = "/v1/items",
                            role = "PRODUCER",
                            responseDto = "ItemDto",
                            dtoFields = mapOf("tags" to "String[]")
                        )
                    )
                ),
                ArcNode(
                    id = "ts.itemsClient",
                    label = "itemsClient.ts",
                    kind = NodeKind.TS_CLIENT,
                    contracts = listOf(
                        ContractEndpoint(
                            protocol = "REST",
                            methodOrAction = "GET",
                            pathOrTopic = "/v1/items",
                            role = "CONSUMER",
                            responseDto = "ItemDto",
                            dtoFields = mapOf("tags" to "string")
                        )
                    )
                )
            )
        )
        val arrayDriftRes = PolyglotContractBridge.linkAndVerify(arrayMismatchGraph)
        assertEquals(1, arrayDriftRes.violations.size)
        assertTrue(arrayDriftRes.violations.first().missingOrMismatchedFields.any { it.startsWith("tags ") })

        // Verify Express `:id`, Flask `<int:id>`, and Spring `{id:.+}` path parameter normalization
        assertEquals("/api/v1/users/{param}", PolyglotContractBridge.normalizePath("/api/v1/users/:id"))
        assertEquals("/api/v1/users/{param}", PolyglotContractBridge.normalizePath("/api/v1/users/<int:id>"))
        assertEquals("/api/v1/users/{param}", PolyglotContractBridge.normalizePath("/api/v1/users/{id:.+}"))

        // Verify exportProducerManifest, mergeManifests, Kafka PUBLISH/CONSUME linking, and union/generic wire type compatibility
        val kafkaAndUnionGraph = ArcGraph(
            projectName = "kafka-union-service",
            nodes = listOf(
                ArcNode(
                    id = "com.acme.EventProducer",
                    label = "EventProducer",
                    kind = NodeKind.SERVICE_BEAN,
                    contracts = listOf(
                        ContractEndpoint(
                            protocol = "KAFKA",
                            methodOrAction = "PUBLISH",
                            pathOrTopic = "orders.created",
                            role = "PRODUCER",
                            responseDto = "OrderCreatedEvent",
                            dtoFields = mapOf("orderId" to "UUID", "createdAt" to "Instant", "status" to "String")
                        )
                    )
                ),
                ArcNode(
                    id = "py.worker.consumer",
                    label = "consumer.py",
                    kind = NodeKind.PYTHON_CONSUMER,
                    contracts = listOf(
                        ContractEndpoint(
                            protocol = "KAFKA",
                            methodOrAction = "CONSUME",
                            pathOrTopic = "orders.created",
                            role = "CONSUMER",
                            responseDto = "OrderCreatedPayload",
                            dtoFields = mapOf(
                                "orderId" to "string | null",
                                "createdAt" to "datetime",
                                "status" to "(typeof OrderStatus)[number]"
                            )
                        )
                    )
                )
            )
        )
        val manifest = PolyglotContractBridge.exportProducerManifest(kafkaAndUnionGraph)
        val mergedManifest = PolyglotContractBridge.mergeManifests(listOf(manifest, manifest))
        assertEquals(1, mergedManifest.producers.size)
        val kafkaRes = PolyglotContractBridge.linkAndVerify(kafkaAndUnionGraph)
        assertTrue(kafkaRes.violations.isEmpty(), "Compatible UUID/Instant/typeof union types should not trigger violations")
        assertEquals(1, kafkaRes.linkedEdges.size)

        // Verify upstreamManifest linking when consumer-only graph links against exported manifest, plus DTO affix matching & request body type mismatch
        val consumerOnlyWithDrift = ArcGraph(
            projectName = "consumer-only",
            nodes = listOf(
                ArcNode(
                    id = "ts.client",
                    label = "client.ts",
                    kind = NodeKind.TS_CLIENT,
                    contracts = listOf(
                        ContractEndpoint(
                            protocol = "KAFKA",
                            methodOrAction = "CONSUME",
                            pathOrTopic = "orders.created",
                            role = "CONSUMER",
                            responseDto = "IOrderCreatedView",
                            dtoFields = mapOf("orderId" to "OrderCreatedResponseDto"),
                            requestDto = "OrderCreatedRequest",
                            requestDtoFields = mapOf("count" to "boolean", "missingReq" to "string")
                        )
                    )
                )
            )
        )
        val upstreamWithReq = manifest.copy(
            producers = manifest.producers.map { p ->
                p.copy(
                    filePath = "src/EventProducer.kt",
                    contract = p.contract.copy(
                        dtoFields = mapOf("orderId" to "OrderCreatedEntity"),
                        requestDto = "OrderCreatedRequestDto",
                        requestDtoFields = mapOf("count" to "Int")
                    )
                )
            }
        )
        val federatedRes = PolyglotContractBridge.linkAndVerify(consumerOnlyWithDrift, upstreamManifest = upstreamWithReq)
        assertEquals(1, federatedRes.violations.size)
        assertTrue(federatedRes.violations.first().missingOrMismatchedFields.any { it.contains("count") })
        assertTrue(federatedRes.violations.first().missingOrMismatchedFields.any { it.contains("missingReq") })
        assertTrue(federatedRes.enrichedGraph.nodes.any { it.id.startsWith("upstream:") })
    }

    @Test
    fun `ArchUnitTestGenerator generates valid Kotlin JUnit5 ArchUnit test source for all rule policies`() {
        val rules = listOf(
            BoundaryRule(
                id = "no-controller-to-repo",
                name = "No Controller to Repository",
                fromPattern = "*.controller.*",
                toPattern = "*.repository.*",
                policy = RulePolicy.FORBIDDEN,
                rationale = "Controllers must use Services"
            ),
            BoundaryRule(
                id = "domain-isolation",
                name = "Domain Isolation",
                fromPattern = "*.domain.*",
                toPattern = "*.shared.*",
                policy = RulePolicy.ALLOWED_ONLY,
                rationale = "Domain must remain pure"
            ),
            BoundaryRule(
                id = "service-via-port",
                name = "Service Via Port Interface",
                fromPattern = "*.controller.*",
                toPattern = "*.service.*",
                policy = RulePolicy.MUST_DEPEND_VIA_INTERFACE,
                rationale = "Decouple controllers from concrete service implementations"
            )
        )

        val code = ArchUnitTestGenerator.generateKotlinJUnit5(
            projectName = "orders-service",
            basePackage = "com.acme.orders",
            rules = rules
        )

        assertTrue(code.contains("package com.acme.orders"))
        assertTrue(code.contains("@AnalyzeClasses(packages = [\"com.acme.orders\"]"))
        assertTrue(code.contains("val freeOfPackageCycles: ArchRule = slices()"))
        assertTrue(code.contains("val noControllerToRepo: ArchRule = noClasses()"))
        assertTrue(code.contains("val domainIsolation: ArchRule = classes()"))
        assertTrue(code.contains("val serviceViaPort: ArchRule = noClasses()"))
        assertTrue(code.contains(".because(\"Controllers must use Services\")"))
    }

    @Test
    fun `InMemoryGraphRefactoringAdapter executes Rename Move ExtractInterface CreateNode and AddDependency`() {
        val initial = ArcGraph(
            projectName = "refactor-test",
            nodes = listOf(
                ArcNode(
                    id = "com.acme.OrderController",
                    label = "OrderController",
                    kind = NodeKind.REST_CONTROLLER
                ),
                ArcNode(
                    id = "com.acme.OrderService",
                    label = "OrderService",
                    kind = NodeKind.SERVICE_BEAN,
                    members = listOf(MemberSignature("placeOrder", "METHOD", "(OrderReq): OrderResp"))
                )
            ),
            edges = listOf(
                ArcEdge(id = "e1", sourceId = "com.acme.OrderController", targetId = "com.acme.OrderService")
            )
        )

        val adapter = InMemoryGraphRefactoringAdapter(initial)

        // 1. Preview only does not mutate graph
        val previewRes = adapter.execute(
            RefactoringCommand.RenameSymbol("com.acme.OrderService", "CheckoutService"),
            previewOnly = true
        )
        assertTrue(previewRes.success)
        assertTrue(previewRes.summary.startsWith("[Preview]"))
        assertNotNull(adapter.currentGraph.nodeMap()["com.acme.OrderService"])

        // 2. Extract interface rewires OrderController -> OrderPort and adds OrderService -> OrderPort IMPLEMENTS edge
        val extractRes = adapter.execute(
            RefactoringCommand.ExtractInterface(
                sourceClassId = "com.acme.OrderService",
                interfaceSimpleName = "OrderPort",
                targetPackage = "com.acme.api",
                memberNames = listOf("placeOrder")
            )
        )
        assertTrue(extractRes.success)
        val portNode = adapter.currentGraph.nodeMap()["com.acme.api.OrderPort"]
        assertNotNull(portNode)
        assertEquals(NodeKind.INTERFACE, portNode.kind)
        assertTrue(adapter.currentGraph.edges.any {
            it.sourceId == "com.acme.OrderService" &&
                it.targetId == "com.acme.api.OrderPort" &&
                it.kind == EdgeKind.IMPLEMENTS
        })

        // 3. Create node + Add dependency + Move class
        assertTrue(adapter.execute(RefactoringCommand.CreateNode("com.acme.AuditLogger", "CLASS")).success)
        assertTrue(
            adapter.execute(
                RefactoringCommand.AddDependency("com.acme.OrderService", "com.acme.AuditLogger", "CONSTRUCTOR_INJECT")
            ).success
        )
        assertTrue(
            adapter.execute(
                RefactoringCommand.MoveClassOrPackage("com.acme.AuditLogger", "com.acme.infra")
            ).success
        )
        assertNotNull(adapter.currentGraph.nodeMap()["com.acme.infra.AuditLogger"])
    }

    @Test
    fun `PolyglotRepoScanner resolves JsonProperty and SerialName wire aliases and cross-file TypeScript DTO imports`() {
        val tempDir = Files.createTempDirectory("arcdrift-wire-alias-test").toFile()
        try {
            val ktFile = tempDir.resolve("src/main/kotlin/com/acme/orders/OrderController.kt")
            ktFile.parentFile.mkdirs()
            ktFile.writeText(
                """
                package com.acme.orders
                import com.fasterxml.jackson.annotation.JsonProperty
                import kotlinx.serialization.SerialName
                import org.springframework.web.bind.annotation.GetMapping
                import org.springframework.web.bind.annotation.RestController

                data class OrderWireDto(
                    @SerialName("order_id") val orderId: String,
                    @JsonProperty("total_cents") val totalCents: Long
                )

                interface OrderPort {
                    fun findOrder(id: String): OrderWireDto
                }

                class OrderServiceImpl : OrderPort {
                    override fun findOrder(id: String): OrderWireDto = OrderWireDto(id, 100L)
                }

                @RestController
                class OrderController(private val orderPort: OrderPort) {
                    @GetMapping("/api/v1/orders/{id}")
                    fun getOrder(id: String): OrderWireDto = orderPort.findOrder(id)
                }
                """.trimIndent()
            )

            val tsTypesFile = tempDir.resolve("web/src/types/orderTypes.ts")
            tsTypesFile.parentFile.mkdirs()
            tsTypesFile.writeText(
                """
                export interface OrderWirePayload {
                    order_id: string;
                    total_cents: number;
                }
                """.trimIndent()
            )

            val tsClientFile = tempDir.resolve("web/src/api/ordersClient.ts")
            tsClientFile.parentFile.mkdirs()
            tsClientFile.writeText(
                """
                import { OrderWirePayload } from '../types/orderTypes';

                export async function fetchOrderWire(id: string): Promise<OrderWirePayload> {
                    const res = await fetch(`/api/v1/orders/${'$'}{id}`);
                    return res.json();
                }
                """.trimIndent()
            )

            val graph = PolyglotRepoScanner.scanRepository(tempDir)
            val nodeMap = graph.nodeMap()
            val ctrl = nodeMap["com.acme.orders.OrderController"]
            assertNotNull(ctrl)
            assertEquals(DiffStatus.UNCHANGED, ctrl.diffStatus, "Serialized wire aliases should match cross-file TS DTO without drift violations")
            assertTrue(graph.edges.any {
                it.sourceId == "com.acme.orders.OrderController" &&
                    it.targetId == "com.acme.orders.OrderPort" &&
                    it.kind == EdgeKind.DEPENDS_ON
            })
            assertTrue(graph.edges.any {
                it.sourceId == "com.acme.orders.OrderServiceImpl" &&
                    it.targetId == "com.acme.orders.OrderPort" &&
                    it.kind == EdgeKind.IMPLEMENTS
            })
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `PolyglotContractBridge merges multiple contracts between same consumer and producer and does not copy reqFields into void response dtoFields`() {
        val tempDir = kotlin.io.path.createTempDirectory("arcdrift-multi-contract-test").toFile()
        try {
            File(tempDir, "src/main/kotlin/com/acme/orders/OrderController.kt").apply {
                parentFile.mkdirs()
                writeText(
                    """
                    package com.acme.orders
                    import org.springframework.http.ResponseEntity
                    import org.springframework.web.bind.annotation.*

                    data class OrderItemDto(val id: String, val qty: Int)
                    data class CreateOrderReq(val sku: String, val qty: Int)

                    @RestController
                    @RequestMapping("/api/v1/orders")
                    class OrderController {
                        @GetMapping
                        fun listOrders(): List<OrderItemDto> = emptyList()

                        @PostMapping
                        fun createOrder(@RequestBody req: CreateOrderReq): ResponseEntity<Void> = ResponseEntity.ok().build()
                    }
                    """.trimIndent()
                )
            }
            val graph = PolyglotRepoScanner.scanRepository(tempDir)
            val ctrl = requireNotNull(graph.nodeMap()["com.acme.orders.OrderController"])
            val postContract = requireNotNull(ctrl.contracts.find { it.methodOrAction == "POST" })
            // Void response must keep dtoFields empty rather than polluting response dtoFields with requestDtoFields
            assertTrue(postContract.dtoFields.isEmpty(), "Void response POST endpoint must have empty response dtoFields")
            assertEquals(mapOf("sku" to "String", "qty" to "Int"), postContract.requestDtoFields)
        } finally {
            tempDir.deleteRecursively()
        }

        // Verify multiple contracts between same consumer and producer (1 valid GET + 1 drifted POST) mark the HTTP_CONTRACT edge as VIOLATION
        val multiCallGraph = ArcGraph(
            projectName = "multi-call",
            nodes = listOf(
                ArcNode(
                    id = "com.acme.OrderController",
                    label = "OrderController",
                    kind = NodeKind.REST_CONTROLLER,
                    contracts = listOf(
                        ContractEndpoint("REST", "GET", "/api/orders", "PRODUCER", responseDto = "OrderDto", dtoFields = mapOf("id" to "String")),
                        ContractEndpoint("REST", "POST", "/api/orders", "PRODUCER", requestDto = "CreateReq", requestDtoFields = mapOf("sku" to "String"))
                    )
                ),
                ArcNode(
                    id = "ts.ordersApi",
                    label = "ordersApi.ts",
                    kind = NodeKind.TS_CLIENT,
                    contracts = listOf(
                        // Valid GET first
                        ContractEndpoint("REST", "GET", "/api/orders", "CONSUMER", responseDto = "OrderDto", dtoFields = mapOf("id" to "string")),
                        // Drifted POST second
                        ContractEndpoint("REST", "POST", "/api/orders", "CONSUMER", requestDto = "CreateReq", requestDtoFields = mapOf("wrongField" to "string"))
                    )
                )
            )
        )
        val res = PolyglotContractBridge.linkAndVerify(multiCallGraph)
        assertEquals(1, res.violations.size)
        val edge = requireNotNull(res.enrichedGraph.edges.find { it.kind == EdgeKind.HTTP_CONTRACT })
        assertEquals(DiffStatus.VIOLATION, edge.diffStatus, "Second drifted contract between same consumer and producer must upgrade edge to VIOLATION")
        assertTrue(edge.violations.any { it.contains("wrongField") })
        assertTrue(edge.label.orEmpty().contains("GET /api/orders"))
        assertTrue(edge.label.orEmpty().contains("POST /api/orders"))
    }
}
