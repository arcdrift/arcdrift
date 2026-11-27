package dev.arcdrift.core.codec

import dev.arcdrift.core.model.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ArcMermaidCodecTest {

    @Test
    fun `roundtrip complex ArcGraph with subgraphs, stereotypes, coordinates, source refs and rules`() {
        val original = ArcGraph(
            projectName = "commerce-platform",
            metadata = mapOf("version" to "2.1", "owner" to "platform-arch"),
            rules = listOf(
                BoundaryRule(
                    id = "no-controller-to-repo",
                    name = "No Direct Repository Access from Controllers",
                    fromPattern = "*.controller.*",
                    toPattern = "*.repository.*",
                    policy = RulePolicy.FORBIDDEN,
                    rationale = "Controllers must invoke application services, never JPA repositories directly."
                ),
                BoundaryRule(
                    id = "domain-purity",
                    name = "Domain Must Not Depend on Infrastructure",
                    fromPattern = "*.domain.*",
                    toPattern = "*.infra.*",
                    policy = RulePolicy.FORBIDDEN,
                    rationale = "Hexagonal architecture boundary."
                )
            ),
            nodes = listOf(
                ArcNode(
                    id = "com.acme.orders",
                    label = "orders",
                    c4Level = C4Level.L2_CONTAINER,
                    kind = NodeKind.MODULE,
                    layoutX = 10.0,
                    layoutY = 20.0
                ),
                ArcNode(
                    id = "com.acme.orders.controller.OrderController",
                    label = "OrderController",
                    c4Level = C4Level.L3_COMPONENT,
                    kind = NodeKind.REST_CONTROLLER,
                    parentId = "com.acme.orders",
                    stereotypes = listOf("@RestController", "@RequestMapping"),
                    source = SourceLocation("src/main/kotlin/com/acme/orders/OrderController.kt", 12, 85, "kotlin"),
                    diffStatus = DiffStatus.MODIFIED,
                    layoutX = 120.5,
                    layoutY = 80.25
                ),
                ArcNode(
                    id = "com.acme.orders.service.OrderService",
                    label = "OrderService",
                    c4Level = C4Level.L3_COMPONENT,
                    kind = NodeKind.SERVICE_BEAN,
                    parentId = "com.acme.orders",
                    stereotypes = listOf("@Service", "@Transactional"),
                    source = SourceLocation("src/main/kotlin/com/acme/orders/OrderService.kt", 8, 140, "kotlin"),
                    diffStatus = DiffStatus.UNCHANGED,
                    layoutX = 120.5,
                    layoutY = 240.0
                ),
                ArcNode(
                    id = "com.acme.orders.repository.OrderRepository",
                    label = "OrderRepository",
                    c4Level = C4Level.L3_COMPONENT,
                    kind = NodeKind.REPOSITORY,
                    parentId = "com.acme.orders",
                    stereotypes = listOf("@Repository"),
                    diffStatus = DiffStatus.ADDED,
                    layoutX = 120.5,
                    layoutY = 400.0
                )
            ),
            edges = listOf(
                ArcEdge(
                    id = "e-ctrl-svc",
                    sourceId = "com.acme.orders.controller.OrderController",
                    targetId = "com.acme.orders.service.OrderService",
                    kind = EdgeKind.CALLS,
                    label = "createOrder()",
                    weight = 3,
                    inCycle = false,
                    diffStatus = DiffStatus.UNCHANGED
                ),
                ArcEdge(
                    id = "e-svc-repo",
                    sourceId = "com.acme.orders.service.OrderService",
                    targetId = "com.acme.orders.repository.OrderRepository",
                    kind = EdgeKind.DEPENDS_ON,
                    label = "save()",
                    weight = 1,
                    inCycle = false,
                    diffStatus = DiffStatus.ADDED
                )
            )
        )

        val mmdText = ArcMermaidSerializer.serialize(original)
        assertTrue(mmdText.startsWith("flowchart TB"))
        assertTrue(mmdText.contains("%% @arc:project name=\"commerce-platform\" version=\"2.1\""))
        assertTrue(mmdText.contains("%% @arc:rule id=\"no-controller-to-repo\""))
        assertTrue(mmdText.contains("subgraph com_acme_orders[\"orders (MODULE)\"]"))

        val parsed = ArcMermaidParser.parse(mmdText)
        assertEquals(original.projectName, parsed.projectName)
        assertEquals("2.1", parsed.metadata["version"])
        assertEquals("platform-arch", parsed.metadata["owner"])
        assertEquals(2, parsed.rules.size)
        assertEquals(original.rules.sortedBy { it.id }, parsed.rules.sortedBy { it.id })

        val parsedNodeMap = parsed.nodeMap()
        assertEquals(4, parsedNodeMap.size)

        val ctrl = parsedNodeMap["com.acme.orders.controller.OrderController"]
        assertNotNull(ctrl)
        assertEquals("OrderController", ctrl.label)
        assertEquals(C4Level.L3_COMPONENT, ctrl.c4Level)
        assertEquals(NodeKind.REST_CONTROLLER, ctrl.kind)
        assertEquals("com.acme.orders", ctrl.parentId)
        assertEquals(listOf("@RestController", "@RequestMapping"), ctrl.stereotypes)
        assertEquals(DiffStatus.MODIFIED, ctrl.diffStatus)
        assertEquals(120.5, ctrl.layoutX)
        assertEquals(80.25, ctrl.layoutY)
        assertEquals("src/main/kotlin/com/acme/orders/OrderController.kt", ctrl.source?.filePath)
        assertEquals(12, ctrl.source?.startLine)

        assertEquals(2, parsed.edges.size)
        val edge1 = parsed.edges.find { it.id == "e-ctrl-svc" }
        assertNotNull(edge1)
        assertEquals("com.acme.orders.controller.OrderController", edge1.sourceId)
        assertEquals("com.acme.orders.service.OrderService", edge1.targetId)
        assertEquals(EdgeKind.CALLS, edge1.kind)
        assertEquals("createOrder()", edge1.label)
        assertEquals(3, edge1.weight)
    }

    @Test
    fun `parse unannotated plain Mermaid flowchart written by human or LLM`() {
        val rawMermaid = """
            flowchart TB
                subgraph backend["Backend Service"]
                    OrderCtrl["OrderController<br/><i>«@RestController, @Validated»</i>"]
                    OrderSvc["OrderService"]
                end
                OrderCtrl -->|"calls"| OrderSvc
                OrderSvc -.->|"implements"| OrderPort
        """.trimIndent()

        val graph = ArcMermaidParser.parse(rawMermaid)
        val nodeMap = graph.nodeMap()
        assertTrue("OrderCtrl" in nodeMap)
        assertTrue("OrderSvc" in nodeMap)
        assertTrue("OrderPort" in nodeMap)

        val ctrl = nodeMap.getValue("OrderCtrl")
        assertEquals("OrderController", ctrl.label)
        assertEquals("backend", ctrl.parentId)
        assertEquals(listOf("@RestController", "@Validated"), ctrl.stereotypes)

        assertEquals(2, graph.edges.size)
        val implEdge = graph.edges.find { it.targetId == "OrderPort" }
        assertNotNull(implEdge)
        assertEquals(EdgeKind.IMPLEMENTS, implEdge.kind)
    }

    @Test
    fun `deterministic serialization produces identical output on repeated calls`() {
        val g1 = ArcGraph(
            projectName = "det-test",
            nodes = listOf(
                ArcNode(id = "b.NodeB", label = "NodeB"),
                ArcNode(id = "a.NodeA", label = "NodeA")
            ),
            edges = listOf(
                ArcEdge(id = "e2", sourceId = "b.NodeB", targetId = "a.NodeA"),
                ArcEdge(id = "e1", sourceId = "a.NodeA", targetId = "b.NodeB")
            )
        )
        val g2 = g1.copy(nodes = g1.nodes.reversed(), edges = g1.edges.reversed())
        assertEquals(ArcMermaidSerializer.serialize(g1), ArcMermaidSerializer.serialize(g2))
    }

    @Test
    fun `roundtrip preserves backslashes before n r and quotes in attribute values and generic contract field types`() {
        val graph = ArcGraph(
            projectName = "backslash-test",
            metadata = mapOf("winPath" to """C:\new\repo\read\test"quote"""),
            nodes = listOf(
                ArcNode(
                    id = "end",
                    label = "EndReservedNode",
                    contracts = listOf(
                        ContractEndpoint(
                            protocol = "REST",
                            methodOrAction = "GET",
                            pathOrTopic = "/v1/items",
                            role = "PRODUCER",
                            responseDto = "ItemDto",
                            dtoFields = mapOf("tags" to "Map<String, Int>", "names" to "String[]")
                        )
                    ),
                    violations = listOf("Rule violation 1", "Rule violation 2")
                )
            )
        )
        val serialized = ArcMermaidSerializer.serialize(graph)
        assertTrue(serialized.contains("node_end[\"EndReservedNode\"]"))
        val parsed = ArcMermaidParser.parse(serialized)
        assertEquals("""C:\new\repo\read\test"quote""", parsed.metadata["winPath"])
        val endNode = parsed.nodeMap()["end"]
        assertNotNull(endNode)
        assertEquals(listOf("Rule violation 1", "Rule violation 2"), endNode.violations)
        assertEquals(mapOf("tags" to "Map<String, Int>", "names" to "String[]"), endNode.contracts.first().dtoFields)
    }

    @Test
    fun `parser supports x y coordinate attributes, standalone @arc member directives, and Windows drive-letter source locations`() {
        val mmd = """
            flowchart TB
              %% @arc:project name="commerce-platform"
              %% @arc:node id="com.acme.orders.controller.OrderController" kind="REST_CONTROLLER" level="L3_COMPONENT" stereotypes="@RestController" x="120.0" y="80.0" file="C:/repo/src/OrderController.kt:15:45:kotlin"
              com_acme_orders_controller_OrderController["OrderController"]
              %% @arc:node id="com.acme.orders.port.PaymentGatewayPort" kind="GHOST_SPEC" level="L3_COMPONENT" stereotypes="«Ghost Spec»"
              %% @arc:member node="com.acme.orders.port.PaymentGatewayPort" name="authorizeCharge" kind="METHOD" sig="(amountCents: Long): ChargeToken" vis="public"
              com_acme_orders_port_PaymentGatewayPort["PaymentGatewayPort"]
        """.trimIndent()
        val parsed = ArcMermaidParser.parse(mmd)
        val ctrl = requireNotNull(parsed.nodeMap()["com.acme.orders.controller.OrderController"])
        assertEquals(120.0, ctrl.layoutX)
        assertEquals(80.0, ctrl.layoutY)
        assertEquals("C:/repo/src/OrderController.kt", ctrl.source?.filePath)
        assertEquals(15, ctrl.source?.startLine)
        assertEquals(45, ctrl.source?.endLine)
        assertEquals("kotlin", ctrl.source?.language)

        val ghost = requireNotNull(parsed.nodeMap()["com.acme.orders.port.PaymentGatewayPort"])
        assertEquals(1, ghost.members.size)
        assertEquals("authorizeCharge", ghost.members.first().name)
        assertEquals("(amountCents: Long): ChargeToken", ghost.members.first().typeOrSignature)
    }

    @Test
    fun `roundtrip preserves 3-level nested container hierarchy without duplicate directives and escapes union pipes and semicolons in contracts and members`() {
        val graph = ArcGraph(
            projectName = "nested-hierarchy-and-unions",
            nodes = listOf(
                ArcNode(
                    id = "com.acme",
                    label = "acme",
                    c4Level = C4Level.L1_SYSTEM,
                    kind = NodeKind.SYSTEM
                ),
                ArcNode(
                    id = "com.acme.orders",
                    label = "orders",
                    c4Level = C4Level.L2_CONTAINER,
                    kind = NodeKind.MODULE,
                    parentId = "com.acme"
                ),
                ArcNode(
                    id = "com.acme.orders.api",
                    label = "api",
                    c4Level = C4Level.L2_CONTAINER,
                    kind = NodeKind.PACKAGE,
                    parentId = "com.acme.orders"
                ),
                ArcNode(
                    id = "com.acme.orders.api.OrderClient",
                    label = "OrderClient",
                    c4Level = C4Level.L3_COMPONENT,
                    kind = NodeKind.TS_CLIENT,
                    parentId = "com.acme.orders.api",
                    members = listOf(
                        MemberSignature(
                            name = "fetchOrder",
                            kind = "METHOD",
                            typeOrSignature = "(opts: { id: string; retry: boolean }): Promise<OrderDto | null>",
                            visibility = "public"
                        )
                    ),
                    contracts = listOf(
                        ContractEndpoint(
                            protocol = "REST",
                            methodOrAction = "POST",
                            pathOrTopic = "/v1/orders",
                            role = "CONSUMER",
                            requestDto = "CreateOrderReq",
                            responseDto = "OrderDto",
                            dtoFields = linkedMapOf(
                                "id" to "string",
                                "email" to "string | null",
                                "meta" to "{ tag: string; count: number }"
                            ),
                            requestDtoFields = linkedMapOf(
                                "couponCode" to "string | undefined",
                                "amount" to "number"
                            )
                        )
                    )
                )
            )
        )

        val serialized = ArcMermaidSerializer.serialize(graph)
        // Verify each node directive is emitted exactly once (no duplicate emission for intermediate containers)
        assertEquals(1, Regex("""%% @arc:node id="com\.acme"""").findAll(serialized).count())
        assertEquals(1, Regex("""%% @arc:node id="com\.acme\.orders"""").findAll(serialized).count())
        assertEquals(1, Regex("""%% @arc:node id="com\.acme\.orders\.api"""").findAll(serialized).count())
        assertEquals(1, Regex("""%% @arc:node id="com\.acme\.orders\.api\.OrderClient"""").findAll(serialized).count())

        val parsed = ArcMermaidParser.parse(serialized)
        assertEquals(4, parsed.nodes.size)
        val nodeMap = parsed.nodeMap()
        assertEquals(null, nodeMap["com.acme"]?.parentId)
        assertEquals("com.acme", nodeMap["com.acme.orders"]?.parentId)
        assertEquals("com.acme.orders", nodeMap["com.acme.orders.api"]?.parentId)
        assertEquals("com.acme.orders.api", nodeMap["com.acme.orders.api.OrderClient"]?.parentId)

        val client = requireNotNull(nodeMap["com.acme.orders.api.OrderClient"])
        assertEquals(1, client.members.size)
        assertEquals(
            "(opts: { id: string; retry: boolean }): Promise<OrderDto | null>",
            client.members.first().typeOrSignature
        )
        assertEquals(1, client.contracts.size)
        val contract = client.contracts.first()
        assertEquals(
            mapOf(
                "id" to "string",
                "email" to "string | null",
                "meta" to "{ tag: string; count: number }"
            ),
            contract.dtoFields
        )
        assertEquals(
            mapOf(
                "couponCode" to "string | undefined",
                "amount" to "number"
            ),
            contract.requestDtoFields
        )
    }
}
