package dev.arcdrift.core.analysis

import dev.arcdrift.core.model.*
import dev.arcdrift.core.rules.ArcRuleEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AnalysisAndRulesTest {

    @Test
    fun `TarjanCycleDetector detects multi-node SCCs and self loops and suggests lowest weight break edge`() {
        val graph = ArcGraph(
            projectName = "cycle-test",
            nodes = listOf(
                ArcNode(id = "com.acme.A", label = "A"),
                ArcNode(id = "com.acme.B", label = "B"),
                ArcNode(id = "com.acme.C", label = "C"),
                ArcNode(id = "com.acme.Self", label = "Self"),
                ArcNode(id = "com.acme.Clean", label = "Clean")
            ),
            edges = listOf(
                ArcEdge(id = "e1", sourceId = "com.acme.A", targetId = "com.acme.B", weight = 5),
                ArcEdge(id = "e2", sourceId = "com.acme.B", targetId = "com.acme.C", weight = 4),
                ArcEdge(id = "e3", sourceId = "com.acme.C", targetId = "com.acme.A", weight = 1),
                ArcEdge(id = "e4", sourceId = "com.acme.Self", targetId = "com.acme.Self", weight = 2),
                ArcEdge(id = "e5", sourceId = "com.acme.Clean", targetId = "com.acme.A", weight = 1)
            )
        )

        val cycles = TarjanCycleDetector.findCycles(graph)
        assertEquals(2, cycles.size)

        val abcCycle = cycles.find { "com.acme.A" in it.nodeIds }
        assertNotNull(abcCycle)
        assertEquals(listOf("com.acme.A", "com.acme.B", "com.acme.C"), abcCycle.nodeIds)
        assertEquals("e3", abcCycle.recommendedBreakEdge?.id)
        assertTrue(abcCycle.recommendationRationale.contains("lowest coupling weight: 1"))

        val selfCycle = cycles.find { "com.acme.Self" in it.nodeIds }
        assertNotNull(selfCycle)
        assertEquals(listOf("com.acme.Self"), selfCycle.nodeIds)

        val annotated = TarjanCycleDetector.annotateGraphWithCycles(graph)
        assertTrue(annotated.edges.first { it.id == "e1" }.inCycle)
        assertTrue(!annotated.edges.first { it.id == "e5" }.inCycle)

        // Verify deterministic canonical cycle rotation regardless of node insertion order
        val reversedNodeOrderGraph = graph.copy(nodes = graph.nodes.reversed(), edges = graph.edges.reversed())
        val reversedCycles = TarjanCycleDetector.findCycles(reversedNodeOrderGraph)
        assertEquals(cycles.map { it.nodeIds }, reversedCycles.map { it.nodeIds })
    }

    @Test
    fun `BlastRadiusDiffer computes ADDED MODIFIED REMOVED and transitive upstream BLAST_IMPACTED callers`() {
        // Call chain: FrontendUI -> ApiGateway -> OrderController -> OrderService -> OrderRepository
        val baseline = ArcGraph(
            projectName = "blast-test",
            nodes = listOf(
                ArcNode(id = "FrontendUI", label = "FrontendUI"),
                ArcNode(id = "ApiGateway", label = "ApiGateway"),
                ArcNode(id = "OrderController", label = "OrderController"),
                ArcNode(id = "OrderService", label = "OrderService"),
                ArcNode(id = "LegacyHelper", label = "LegacyHelper")
            ),
            edges = listOf(
                ArcEdge(id = "e1", sourceId = "FrontendUI", targetId = "ApiGateway"),
                ArcEdge(id = "e2", sourceId = "ApiGateway", targetId = "OrderController"),
                ArcEdge(id = "e3", sourceId = "OrderController", targetId = "OrderService")
            )
        )

        // In current: OrderService is modified (stereotypes changed), StripeAdapter is added, LegacyHelper is removed
        val current = ArcGraph(
            projectName = "blast-test",
            nodes = listOf(
                ArcNode(id = "FrontendUI", label = "FrontendUI"),
                ArcNode(id = "ApiGateway", label = "ApiGateway"),
                ArcNode(id = "OrderController", label = "OrderController"),
                ArcNode(id = "OrderService", label = "OrderService", stereotypes = listOf("@Transactional")),
                ArcNode(id = "StripeAdapter", label = "StripeAdapter")
            ),
            edges = listOf(
                ArcEdge(id = "e1", sourceId = "FrontendUI", targetId = "ApiGateway"),
                ArcEdge(id = "e2", sourceId = "ApiGateway", targetId = "OrderController"),
                ArcEdge(id = "e3", sourceId = "OrderController", targetId = "OrderService"),
                ArcEdge(id = "e4", sourceId = "OrderService", targetId = "StripeAdapter")
            )
        )

        val report = BlastRadiusDiffer.diff(baseline, current, maxDepth = 2)
        assertEquals(listOf("StripeAdapter"), report.addedNodeIds)
        assertEquals(listOf("OrderService"), report.modifiedNodeIds)
        assertEquals(listOf("LegacyHelper"), report.removedNodeIds)
        // Upstream within 2 hops of OrderService: OrderController (hop 1), ApiGateway (hop 2)
        assertEquals(listOf("ApiGateway", "OrderController"), report.blastImpactedNodeIds)

        val diffNodeMap = report.diffGraph.nodeMap()
        assertEquals(DiffStatus.ADDED, diffNodeMap.getValue("StripeAdapter").diffStatus)
        assertEquals(DiffStatus.MODIFIED, diffNodeMap.getValue("OrderService").diffStatus)
        assertEquals(DiffStatus.BLAST_IMPACTED, diffNodeMap.getValue("OrderController").diffStatus)
        assertEquals(DiffStatus.BLAST_IMPACTED, diffNodeMap.getValue("ApiGateway").diffStatus)
        assertEquals(DiffStatus.UNCHANGED, diffNodeMap.getValue("FrontendUI").diffStatus)
        assertEquals(DiffStatus.REMOVED, diffNodeMap.getValue("LegacyHelper").diffStatus)

        // Verify contract-only or member-only mutations also mark the component as MODIFIED and propagate BLAST_IMPACTED
        val contractModifiedCurrent = baseline.copy(
            nodes = baseline.nodes.map { n ->
                if (n.id == "OrderService") {
                    n.copy(members = listOf(MemberSignature("charge", "METHOD", "(Long): Boolean")))
                } else {
                    n
                }
            }
        )
        val contractReport = BlastRadiusDiffer.diff(baseline, contractModifiedCurrent, maxDepth = 1)
        assertEquals(listOf("OrderService"), contractReport.modifiedNodeIds)
        assertEquals(listOf("OrderController"), contractReport.blastImpactedNodeIds)
    }

    @Test
    fun `ArchitectureSlicer filters by C4 level and hop depth including parent containers`() {
        val graph = ArcGraph(
            projectName = "slice-test",
            nodes = listOf(
                ArcNode(id = "mod.orders", label = "orders", c4Level = C4Level.L2_CONTAINER, kind = NodeKind.MODULE),
                ArcNode(id = "mod.orders.OrderCtrl", label = "OrderCtrl", c4Level = C4Level.L3_COMPONENT, parentId = "mod.orders"),
                ArcNode(id = "mod.orders.OrderSvc", label = "OrderSvc", c4Level = C4Level.L3_COMPONENT, parentId = "mod.orders"),
                ArcNode(id = "mod.orders.OrderHelper", label = "OrderHelper", c4Level = C4Level.L4_CODE, parentId = "mod.orders"),
                ArcNode(id = "mod.billing.BillingSvc", label = "BillingSvc", c4Level = C4Level.L3_COMPONENT),
                ArcNode(id = "mod.shipping.ShippingSvc", label = "ShippingSvc", c4Level = C4Level.L3_COMPONENT)
            ),
            edges = listOf(
                ArcEdge(id = "e1", sourceId = "mod.orders.OrderCtrl", targetId = "mod.orders.OrderSvc"),
                ArcEdge(id = "e2", sourceId = "mod.orders.OrderSvc", targetId = "mod.orders.OrderHelper"),
                ArcEdge(id = "e3", sourceId = "mod.orders.OrderSvc", targetId = "mod.billing.BillingSvc")
            )
        )

        val slice = ArchitectureSlicer.slice(
            graph = graph,
            scopeQuery = "OrderCtrl",
            maxC4Level = C4Level.L3_COMPONENT,
            depth = 1
        )
        val ids = slice.nodes.map { it.id }.toSet()
        // Should include OrderCtrl (seed), OrderSvc (1 hop), and mod.orders (parent container of included nodes)
        // Should exclude OrderHelper (L4_CODE > L3_COMPONENT), BillingSvc (2 hops away), ShippingSvc (disconnected)
        assertEquals(setOf("mod.orders", "mod.orders.OrderCtrl", "mod.orders.OrderSvc"), ids)

        // Blank scopeQuery filters only by maxC4Level; unmatched scopeQuery returns empty graph
        val levelOnly = ArchitectureSlicer.slice(graph, scopeQuery = null, maxC4Level = C4Level.L3_COMPONENT, depth = 1)
        assertTrue(levelOnly.nodes.none { it.c4Level == C4Level.L4_CODE })
        val unmatched = ArchitectureSlicer.slice(graph, scopeQuery = "nonexistent-xyz", maxC4Level = null, depth = 2)
        assertTrue(unmatched.nodes.isEmpty())

        // High-degree hub (>20 neighbors) at hop >= 1 preserves HTTP_CONTRACT edges without exploding
        val hubTargets = (1..25).map { idx ->
            ArcNode(id = "leaf.$idx", label = "Leaf$idx")
        }
        val hubGraph = ArcGraph(
            projectName = "hub-test",
            nodes = listOf(
                ArcNode(id = "seed.Start", label = "Start"),
                ArcNode(id = "hub.SharedUtil", label = "SharedUtil"),
                ArcNode(id = "contract.RemoteApi", label = "RemoteApi")
            ) + hubTargets,
            edges = listOf(
                ArcEdge(id = "e-start-hub", sourceId = "seed.Start", targetId = "hub.SharedUtil"),
                ArcEdge(id = "e-hub-contract", sourceId = "hub.SharedUtil", targetId = "contract.RemoteApi", kind = EdgeKind.HTTP_CONTRACT)
            ) + hubTargets.mapIndexed { i, t ->
                ArcEdge(id = "e-hub-$i", sourceId = "hub.SharedUtil", targetId = t.id)
            }
        )
        val hubSlice = ArchitectureSlicer.slice(hubGraph, scopeQuery = "Start", depth = 2)
        assertTrue(hubSlice.nodes.any { it.id == "contract.RemoteApi" })
        assertTrue(hubSlice.nodes.none { it.id == "leaf.1" })
    }

    @Test
    fun `ArcRuleEngine detects FORBIDDEN, MUST_DEPEND_VIA_INTERFACE and ALLOWED_ONLY violations`() {
        val rules = listOf(
            BoundaryRule(
                id = "r-no-ctrl-repo",
                name = "Controllers cannot access Repositories directly",
                fromPattern = "*.controller.*",
                toPattern = "*.repository.*",
                policy = RulePolicy.FORBIDDEN
            ),
            BoundaryRule(
                id = "r-interface-only",
                name = "Controllers must depend on service interfaces",
                fromPattern = "*.controller.*",
                toPattern = "*.service.*",
                policy = RulePolicy.MUST_DEPEND_VIA_INTERFACE
            ),
            BoundaryRule(
                id = "r-domain-allowed",
                name = "Domain can only depend on shared kernel",
                fromPattern = "*.domain.*",
                toPattern = "*.kernel.*",
                policy = RulePolicy.ALLOWED_ONLY
            )
        )

        val graph = ArcGraph(
            projectName = "rule-test",
            rules = rules,
            nodes = listOf(
                ArcNode(id = "com.acme.controller.OrderController", label = "OrderController", kind = NodeKind.REST_CONTROLLER),
                ArcNode(id = "com.acme.repository.OrderRepository", label = "OrderRepository", kind = NodeKind.REPOSITORY),
                ArcNode(id = "com.acme.service.OrderServiceImpl", label = "OrderServiceImpl", kind = NodeKind.SERVICE_BEAN),
                ArcNode(id = "com.acme.domain.OrderAggregate", label = "OrderAggregate", kind = NodeKind.CLASS),
                ArcNode(id = "com.acme.infra.KafkaProducer", label = "KafkaProducer", kind = NodeKind.CLASS)
            ),
            edges = listOf(
                ArcEdge(id = "e-bad-repo", sourceId = "com.acme.controller.OrderController", targetId = "com.acme.repository.OrderRepository"),
                ArcEdge(id = "e-bad-concrete", sourceId = "com.acme.controller.OrderController", targetId = "com.acme.service.OrderServiceImpl"),
                ArcEdge(id = "e-bad-domain", sourceId = "com.acme.domain.OrderAggregate", targetId = "com.acme.infra.KafkaProducer")
            )
        )

        val violations = ArcRuleEngine.evaluate(graph)
        assertEquals(3, violations.size)
        assertEquals(setOf("r-no-ctrl-repo", "r-interface-only", "r-domain-allowed"), violations.map { it.ruleId }.toSet())

        val annotated = ArcRuleEngine.annotateViolations(graph, violations)
        assertTrue(annotated.edges.all { it.diffStatus == DiffStatus.VIOLATION })

        // Verify ArchUnit-style `com..controller..` matches both direct `com.controller.X` and nested `com.acme.controller.X`
        val archUnitDoubleDotRule = BoundaryRule(
            id = "r-double-dot",
            name = "Double dot package wildcard",
            fromPattern = "com..controller..",
            toPattern = "com..repository..",
            policy = RulePolicy.FORBIDDEN
        )
        val directPkgGraph = ArcGraph(
            projectName = "double-dot-test",
            rules = listOf(archUnitDoubleDotRule),
            nodes = listOf(
                ArcNode(id = "com.controller.DirectCtrl", label = "DirectCtrl"),
                ArcNode(id = "com.repository.DirectRepo", label = "DirectRepo")
            ),
            edges = listOf(
                ArcEdge(id = "e-direct", sourceId = "com.controller.DirectCtrl", targetId = "com.repository.DirectRepo")
            )
        )
        assertEquals(1, ArcRuleEngine.evaluate(directPkgGraph).size)
    }

    @Test
    fun `ComplianceReportFormatter serializes SARIF 2_1_0 and SonarQube Generic Issue JSON with related locations`() {
        val graph = ArcGraph(
            projectName = "report-test",
            nodes = listOf(
                ArcNode(
                    id = "com.acme.OrderController",
                    label = "OrderController",
                    source = SourceLocation("src/OrderController.kt", 10, 40, "kotlin")
                ),
                ArcNode(
                    id = "com.acme.OrderRepository",
                    label = "OrderRepository",
                    source = SourceLocation("src/OrderRepository.kt", 5, 25, "kotlin")
                ),
                ArcNode(
                    id = "ts.ordersClient",
                    label = "ordersClient.ts",
                    source = SourceLocation("web/ordersClient.ts", 1, 30, "typescript")
                )
            ),
            edges = listOf(
                ArcEdge("e1", "com.acme.OrderController", "com.acme.OrderRepository"),
                ArcEdge("e2", "com.acme.OrderRepository", "com.acme.OrderController")
            ),
            rules = listOf(
                BoundaryRule("no-ctrl-repo", "No Ctrl Repo", "*.OrderController", "*.OrderRepository", RulePolicy.FORBIDDEN)
            )
        )
        val cycles = TarjanCycleDetector.findCycles(graph)
        val ruleViolations = ArcRuleEngine.evaluate(graph)
        val contractViolations = listOf(
            dev.arcdrift.core.contracts.ContractViolation(
                id = "cv-1",
                protocol = "REST",
                methodOrAction = "GET",
                pathOrTopic = "/v1/orders",
                consumerNodeId = "ts.ordersClient",
                consumerFilePath = "web/ordersClient.ts",
                producerNodeId = "com.acme.OrderController",
                producerFilePath = "src/OrderController.kt",
                missingOrMismatchedFields = listOf("totalCents"),
                message = "DTO field mismatch",
                remediationHint = "Update OrderDto"
            )
        )

        val sarifJson = dev.arcdrift.core.reporting.ComplianceReportFormatter.toSarifJson(
            graph = graph,
            cycles = cycles,
            ruleViolations = ruleViolations,
            contractViolations = contractViolations
        )
        assertTrue(sarifJson.contains("ARCDRIFT_CONTRACT_DRIFT"))
        assertTrue(sarifJson.contains("ARCDRIFT_BOUNDARY_RULE"))
        assertTrue(sarifJson.contains("ARCDRIFT_DEPENDENCY_CYCLE"))

        val sonarJson = dev.arcdrift.core.reporting.ComplianceReportFormatter.toSonarGenericIssuesJson(
            graph = graph,
            cycles = cycles,
            ruleViolations = ruleViolations,
            contractViolations = contractViolations
        )
        assertTrue(sonarJson.contains("\"engineId\": \"arcdrift\""))
        assertTrue(sonarJson.contains("\"softwareQuality\": \"RELIABILITY\""))
        assertTrue(sonarJson.contains("\"softwareQuality\": \"MAINTAINABILITY\""))
    }

    @Test
    fun `ArcRuleEngine annotateViolations preserves non-rule bracketed violations and clears auto-generated rule edge labels on resolution, and BlastRadiusDiffer preserves multi-edges`() {
        val rule = BoundaryRule("no-a-to-b", "No A to B", "*.NodeA", "*.NodeB", RulePolicy.FORBIDDEN)
        val initialGraph = ArcGraph(
            projectName = "rule-cleanup-test",
            nodes = listOf(
                ArcNode(
                    id = "com.acme.NodeA",
                    label = "NodeA",
                    diffStatus = DiffStatus.VIOLATION,
                    violations = listOf("[CUSTOM_CHECK] Custom bracketed diagnostics")
                ),
                ArcNode(id = "com.acme.NodeB", label = "NodeB")
            ),
            edges = listOf(
                ArcEdge(id = "e-ab", sourceId = "com.acme.NodeA", targetId = "com.acme.NodeB", label = null)
            ),
            rules = listOf(rule)
        )
        val violatedGraph = ArcRuleEngine.annotateViolations(initialGraph, ArcRuleEngine.evaluate(initialGraph))
        val violatedEdge = violatedGraph.edges.first()
        assertEquals(DiffStatus.VIOLATION, violatedEdge.diffStatus)
        assertEquals("[no-a-to-b] No A to B", violatedEdge.label)
        val nodeAViolations = violatedGraph.nodeMap()["com.acme.NodeA"]!!.violations
        assertTrue(nodeAViolations.contains("[CUSTOM_CHECK] Custom bracketed diagnostics"))
        assertTrue(nodeAViolations.any { it.startsWith("[no-a-to-b]") })

        // Now resolve the rule violation (empty rule violation list) and re-annotate
        val resolvedGraph = ArcRuleEngine.annotateViolations(violatedGraph, emptyList())
        val resolvedEdge = resolvedGraph.edges.first()
        assertEquals(DiffStatus.UNCHANGED, resolvedEdge.diffStatus)
        assertEquals(null, resolvedEdge.label, "Auto-generated rule violation label must be cleared when rule violation is resolved")
        // NodeA still has its non-rule bracketed diagnostic
        assertEquals(
            listOf("[CUSTOM_CHECK] Custom bracketed diagnostics"),
            resolvedGraph.nodeMap()["com.acme.NodeA"]!!.violations
        )

        // BlastRadiusDiffer multi-edge preservation between same (sourceId, targetId, kind)
        val baseMulti = ArcGraph(
            projectName = "multi-edge-diff",
            nodes = listOf(ArcNode("a", "A"), ArcNode("b", "B")),
            edges = listOf(
                ArcEdge("e1", "a", "b", EdgeKind.HTTP_CONTRACT, label = "GET /orders"),
                ArcEdge("e2", "a", "b", EdgeKind.HTTP_CONTRACT, label = "POST /orders")
            )
        )
        val currMulti = baseMulti.copy(
            edges = listOf(
                ArcEdge("e1", "a", "b", EdgeKind.HTTP_CONTRACT, label = "GET /orders")
            )
        )
        val diff = BlastRadiusDiffer.diff(baseMulti, currMulti)
        val contractEdges = diff.diffGraph.edges.filter { it.kind == EdgeKind.HTTP_CONTRACT }
        assertEquals(2, contractEdges.size)
        assertTrue(contractEdges.any { it.label == "GET /orders" && it.diffStatus == DiffStatus.UNCHANGED })
        assertTrue(contractEdges.any { it.label == "POST /orders" && it.diffStatus == DiffStatus.REMOVED })
    }
}
