package dev.arcdrift.core.analysis

import dev.arcdrift.core.codec.ArcMermaidParser
import dev.arcdrift.core.codec.ArcMermaidSerializer
import dev.arcdrift.core.contracts.ContractFederationManifest
import dev.arcdrift.core.contracts.ExportedContractProducer
import dev.arcdrift.core.contracts.PolyglotContractBridge
import dev.arcdrift.core.model.*
import dev.arcdrift.core.reporting.ComplianceReportFormatter
import dev.arcdrift.core.rules.ArcRuleEngine
import dev.arcdrift.core.rules.ArchUnitTestGenerator
import dev.arcdrift.core.spi.ArcDriftSpiLoader
import dev.arcdrift.core.spi.ComplianceCheckSummary
import dev.arcdrift.core.spi.ComplianceTelemetrySink
import dev.arcdrift.core.spi.ContractRegistryProvider
import dev.arcdrift.core.spi.PolicyPackProvider
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HolisticAnalysisCodecRulesRegressionTest {

    @Test
    fun `ArchitectureSlicer handles cyclic parentIds, L2 cross-container edge lifting, container scope seeding, and REMOVED edges`() {
        val graph = ArcGraph(
            projectName = "slicer-test",
            nodes = listOf(
                ArcNode("pkg.alpha", "alpha", C4Level.L2_CONTAINER, NodeKind.PACKAGE, parentId = "pkg.beta"),
                ArcNode("pkg.beta", "beta", C4Level.L2_CONTAINER, NodeKind.PACKAGE, parentId = "pkg.alpha"),
                ArcNode("pkg.orders", "orders", C4Level.L2_CONTAINER, NodeKind.PACKAGE),
                ArcNode("pkg.billing", "billing", C4Level.L2_CONTAINER, NodeKind.PACKAGE),
                ArcNode("pkg.orders.OrderService", "OrderService", C4Level.L3_COMPONENT, NodeKind.SERVICE_BEAN, parentId = "pkg.orders"),
                ArcNode("pkg.billing.BillingClient", "BillingClient", C4Level.L3_COMPONENT, NodeKind.SERVICE_BEAN, parentId = "pkg.billing"),
                ArcNode("pkg.dead.RemovedHub", "RemovedHub", C4Level.L3_COMPONENT, NodeKind.CLASS, diffStatus = DiffStatus.REMOVED)
            ),
            edges = listOf(
                ArcEdge("e-cross", "pkg.orders.OrderService", "pkg.billing.BillingClient", EdgeKind.CALLS),
                ArcEdge("e-rem-1", "pkg.dead.RemovedHub", "pkg.orders.OrderService", EdgeKind.DEPENDS_ON, diffStatus = DiffStatus.REMOVED),
                ArcEdge("e-rem-2", "pkg.dead.RemovedHub", "pkg.billing.BillingClient", EdgeKind.DEPENDS_ON, diffStatus = DiffStatus.REMOVED),
                ArcEdge("e-rem-3", "pkg.dead.RemovedHub", "pkg.alpha", EdgeKind.DEPENDS_ON, diffStatus = DiffStatus.REMOVED)
            )
        )

        // 1. Cyclic parentId must terminate without infinite loop, and L2 zoom lifts component edge to L2 containers
        val l2Slice = ArchitectureSlicer.slice(graph, scopeQuery = null, maxC4Level = C4Level.L2_CONTAINER, depth = 2)
        assertTrue(l2Slice.edges.any { it.sourceId == "pkg.orders" && it.targetId == "pkg.billing" })

        // 2. Querying a container name at L3 seeds its descendants and traverses across component edges
        val containerScoped = ArchitectureSlicer.slice(graph, scopeQuery = "pkg.orders", maxC4Level = C4Level.L3_COMPONENT, depth = 1)
        val scopedIds = containerScoped.nodes.map { it.id }.toSet()
        assertTrue("pkg.orders.OrderService" in scopedIds)
        assertTrue("pkg.billing.BillingClient" in scopedIds)

        // 3. Traversal around OrderService does not traverse REMOVED edges to RemovedHub
        val scopedFromOrder = ArchitectureSlicer.slice(graph, scopeQuery = "OrderService", maxC4Level = C4Level.L3_COMPONENT, depth = 1)
        assertTrue(scopedFromOrder.nodes.any { it.id == "pkg.orders.OrderService" })
        assertTrue(scopedFromOrder.nodes.any { it.id == "pkg.billing.BillingClient" })
        assertFalse(scopedFromOrder.nodes.any { it.id == "pkg.dead.RemovedHub" })
    }

    @Test
    fun `TarjanCycleDetector ignores REMOVED elements and orders multi-node SCC along directed cycle path`() {
        // Directed cycle: A -> C -> B -> A (alphabetical order would be A, B, C, which has no edge A -> B!)
        val graph = ArcGraph(
            projectName = "cycle-order-test",
            nodes = listOf(
                ArcNode("A", "A"),
                ArcNode("B", "B"),
                ArcNode("C", "C"),
                ArcNode("RemovedNode", "RemovedNode", diffStatus = DiffStatus.REMOVED)
            ),
            edges = listOf(
                ArcEdge("e-ac", "A", "C", EdgeKind.DEPENDS_ON),
                ArcEdge("e-cb", "C", "B", EdgeKind.DEPENDS_ON),
                ArcEdge("e-ba", "B", "A", EdgeKind.DEPENDS_ON),
                // Removed cycle edge between A and RemovedNode must be ignored
                ArcEdge("e-ar", "A", "RemovedNode", EdgeKind.DEPENDS_ON),
                ArcEdge("e-ra", "RemovedNode", "A", EdgeKind.DEPENDS_ON, diffStatus = DiffStatus.REMOVED)
            )
        )

        val cycles = TarjanCycleDetector.findCycles(graph)
        assertEquals(1, cycles.size)
        val ordered = cycles.first().nodeIds
        assertEquals(3, ordered.size)
        for (i in ordered.indices) {
            val from = ordered[i]
            val to = ordered[(i + 1) % ordered.size]
            assertTrue(
                graph.edges.any { it.sourceId == from && it.targetId == to && it.diffStatus != DiffStatus.REMOVED },
                "Expected directed edge $from -> $to in cycle ordering $ordered"
            )
        }

        // Annotating a graph where a previously cyclic edge is REMOVED clears stale cycle flags
        val brokenCycleGraph = graph.copy(
            edges = graph.edges.map { if (it.id == "e-ba") it.copy(diffStatus = DiffStatus.REMOVED) else it }
        )
        assertTrue(TarjanCycleDetector.findCycles(brokenCycleGraph).isEmpty())
        val annotated = TarjanCycleDetector.annotateGraphWithCycles(brokenCycleGraph)
        assertTrue(annotated.edges.none { it.inCycle })
    }

    @Test
    fun `BlastRadiusDiffer detects multi-edge diffs, duplicate-label edges, narrow blast edges, and unique removed IDs`() {
        val baseline = ArcGraph(
            projectName = "diff-test",
            nodes = listOf(
                ArcNode("Caller", "Caller"),
                ArcNode("Target", "Target"),
                ArcNode("UnrelatedClean", "UnrelatedClean"),
                ArcNode("OldDep", "OldDep")
            ),
            edges = listOf(
                ArcEdge("e1", "Caller", "Target", EdgeKind.DEPENDS_ON, label = "dep"),
                ArcEdge("e-clean", "Caller", "UnrelatedClean", EdgeKind.DEPENDS_ON, label = "clean"),
                ArcEdge("dup-id", "Caller", "OldDep", EdgeKind.CALLS, label = "op1"),
                ArcEdge("dup-id-2", "Target", "OldDep", EdgeKind.CALLS, label = "op2")
            )
        )
        // Current adds a second edge (CALLS) between Caller -> Target AND modifies Target
        val current = ArcGraph(
            projectName = "diff-test",
            nodes = listOf(
                ArcNode("Caller", "Caller"),
                ArcNode("Target", "Target", stereotypes = listOf("@Transactional")),
                ArcNode("UnrelatedClean", "UnrelatedClean")
            ),
            edges = listOf(
                ArcEdge("dup-id", "Caller", "Target", EdgeKind.DEPENDS_ON, label = "dep"),
                ArcEdge("e2", "Caller", "Target", EdgeKind.CALLS, label = "invoke"),
                ArcEdge("e-clean", "Caller", "UnrelatedClean", EdgeKind.DEPENDS_ON, label = "clean")
            )
        )

        val report = BlastRadiusDiffer.diff(baseline, current, maxDepth = 3)
        assertTrue("Target" in report.modifiedNodeIds)
        assertTrue("Caller" in report.blastImpactedNodeIds || "Caller" in report.modifiedNodeIds)

        // Edge from Caller -> UnrelatedClean must NOT be marked BLAST_IMPACTED because UnrelatedClean is untouched
        val cleanEdge = report.diffGraph.edges.first { it.sourceId == "Caller" && it.targetId == "UnrelatedClean" }
        assertEquals(DiffStatus.UNCHANGED, cleanEdge.diffStatus)

        // All edges in diffGraph (including synthesized REMOVED edges) must have unique IDs
        val allEdgeIds = report.diffGraph.edges.map { it.id }
        assertEquals(allEdgeIds.size, allEdgeIds.toSet().size, "Expected unique edge IDs in diffGraph: $allEdgeIds")
    }

    @Test
    fun `ArcMermaidSerializer and ArcMermaidParser round-trip special characters, null labels, colliding IDs, and orphan parentIds`() {
        val original = ArcGraph(
            projectName = "codec-edge-cases",
            metadata = emptyMap(),
            nodes = listOf(
                ArcNode(
                    id = "com.a_b.OrderService",
                    label = "OrderService",
                    parentId = "nonexistent.orphan.pkg",
                    stereotypes = listOf("@Tag(a;b|c,d=1:2%3)"),
                    members = listOf(
                        MemberSignature(
                            name = "handle",
                            kind = "METHOD",
                            typeOrSignature = "(cb: (x: { a: string; b: number }) => void): Map<String, Int>",
                            visibility = "package-private"
                        )
                    ),
                    contracts = listOf(
                        ContractEndpoint(
                            protocol = "REST",
                            methodOrAction = "POST",
                            pathOrTopic = "/v1/orders;matrix=1|2",
                            role = "PRODUCER",
                            requestDto = "ReqDto",
                            responseDto = "RespDto",
                            dtoFields = mapOf("meta:key" to "String;UTF-8", "pct%val" to "Int"),
                            requestDtoFields = mapOf("in,field" to "Boolean=true")
                        )
                    ),
                    violations = listOf("Rule A|B;C violated 100%")
                ),
                ArcNode(
                    id = "com.a.b.OrderService",
                    label = "OrderService2"
                )
            ),
            edges = listOf(
                ArcEdge(
                    id = "e-null-label",
                    sourceId = "com.a_b.OrderService",
                    targetId = "com.a.b.OrderService",
                    kind = EdgeKind.DEPENDS_ON,
                    label = null,
                    violations = listOf("Edge violation | pipe ; semi % pct")
                )
            )
        )

        val mmd = ArcMermaidSerializer.serialize(original)
        val parsed = ArcMermaidParser.parse(mmd)

        // Both colliding Mermaid IDs remain distinct nodes, and no phantom MODULE is synthesized for orphan parentId
        assertEquals(2, parsed.nodes.size)
        assertTrue(parsed.nodes.none { it.kind == NodeKind.MODULE })
        assertEquals(emptyMap(), parsed.metadata)

        val restoredNode = parsed.nodes.first { it.id == "com.a_b.OrderService" }
        assertEquals(original.nodes.first().stereotypes, restoredNode.stereotypes)
        assertEquals(original.nodes.first().members, restoredNode.members)
        assertEquals(original.nodes.first().contracts, restoredNode.contracts)
        assertEquals(original.nodes.first().violations, restoredNode.violations)

        val restoredEdge = parsed.edges.single()
        assertNull(restoredEdge.label)
        assertEquals(original.edges.first().violations, restoredEdge.violations)

        // Dangling @arc:edge followed by a non-edge node declaration must NOT leak onto a subsequent edge
        val danglingSpec = """
            flowchart TB
                %% @arc:edge id="stale-directive" kind="FORBIDDEN_RULE" status="VIOLATION"
                A["Node A"]
                B["Node B"]
                A --> B
        """.trimIndent()
        val parsedDangling = ArcMermaidParser.parse(danglingSpec)
        assertEquals(EdgeKind.DEPENDS_ON, parsedDangling.edges.single().kind)
        assertEquals(DiffStatus.UNCHANGED, parsedDangling.edges.single().diffStatus)
    }

    @Test
    fun `ArcRuleEngine distinguishes single-segment vs multi-segment wildcards, preserves HTTP labels, and skips REMOVED`() {
        val ruleSingleSeg = BoundaryRule(
            id = "single-seg",
            name = "Single segment wildcard",
            fromPattern = "com.*.controller.*",
            toPattern = "com.*.repository.*",
            policy = RulePolicy.FORBIDDEN
        )
        val ruleMultiSeg = BoundaryRule(
            id = "multi-seg",
            name = "Multi segment wildcard",
            fromPattern = "com.**.controller.*",
            toPattern = "com.**.repository.*",
            policy = RulePolicy.FORBIDDEN
        )
        val graph = ArcGraph(
            projectName = "rules-test",
            rules = listOf(ruleSingleSeg, ruleMultiSeg),
            nodes = listOf(
                ArcNode("com.orders.controller.OrderCtrl", "OrderCtrl", parentId = "cycle.p1"),
                ArcNode("com.orders.sub.controller.DeepCtrl", "DeepCtrl", parentId = "cycle.p2"),
                ArcNode("com.orders.repository.OrderRepo", "OrderRepo"),
                ArcNode("cycle.p1", "p1", C4Level.L2_CONTAINER, NodeKind.PACKAGE, parentId = "cycle.p2"),
                ArcNode("cycle.p2", "p2", C4Level.L2_CONTAINER, NodeKind.PACKAGE, parentId = "cycle.p1")
            ),
            edges = listOf(
                ArcEdge("e-shallow", "com.orders.controller.OrderCtrl", "com.orders.repository.OrderRepo", EdgeKind.DEPENDS_ON, label = "[HTTP] GET /orders"),
                ArcEdge("e-deep", "com.orders.sub.controller.DeepCtrl", "com.orders.repository.OrderRepo", EdgeKind.DEPENDS_ON),
                ArcEdge("e-removed", "com.orders.controller.OrderCtrl", "com.orders.repository.OrderRepo", EdgeKind.CALLS, diffStatus = DiffStatus.REMOVED)
            )
        )

        val violations = ArcRuleEngine.evaluate(graph)
        // Shallow edge matches BOTH single-seg and multi-seg; deep edge matches ONLY multi-seg; REMOVED edge is ignored
        assertTrue(violations.any { it.offendingEdgeId == "e-shallow" && it.ruleId == "single-seg" })
        assertTrue(violations.any { it.offendingEdgeId == "e-shallow" && it.ruleId == "multi-seg" })
        assertFalse(violations.any { it.offendingEdgeId == "e-deep" && it.ruleId == "single-seg" })
        assertTrue(violations.any { it.offendingEdgeId == "e-deep" && it.ruleId == "multi-seg" })
        assertFalse(violations.any { it.offendingEdgeId == "e-removed" })

        // Clearing violations must NEVER erase non-rule bracketed labels like "[HTTP] GET /orders"
        val annotated = ArcRuleEngine.annotateViolations(graph, violations)
        val cleared = ArcRuleEngine.annotateViolations(annotated, emptyList())
        assertEquals("[HTTP] GET /orders", cleared.edges.first { it.id == "e-shallow" }.label)
    }

    @Test
    fun `ArchUnitTestGenerator handles stereotypes, exact vs suffix class patterns, KDoc comment injection, and Kotlin keywords`() {
        val rules = listOf(
            BoundaryRule(
                id = "when",
                name = "Sanitize */ comment /* markers",
                fromPattern = "@RestController",
                toPattern = "@Repository",
                policy = RulePolicy.FORBIDDEN,
                rationale = "Never allow */ closing KDoc early"
            ),
            BoundaryRule(
                id = "exact-vs-suffix",
                name = "Exact class vs suffix",
                fromPattern = "*.OrderController",
                toPattern = "*.service.*Service",
                policy = RulePolicy.ALLOWED_ONLY
            )
        )
        val code = ArchUnitTestGenerator.generateKotlinJUnit5(
            projectName = "billing */ inject",
            basePackage = "com.in.object",
            rules = rules,
            className = "fun"
        )
        assertTrue(code.contains("package com.`in`.`object`"))
        assertTrue(code.contains("class FunTest"))
        assertTrue(code.contains("val whenRule: ArchRule"))
        assertFalse(code.contains("billing */ inject"))
        assertTrue(code.contains("billing * / inject"))
        assertTrue(code.contains("DescribedPredicate"))
        assertTrue(code.contains("haveSimpleName(\"OrderController\")"))
        assertTrue(code.contains("simpleNameEndingWith(\"Service\")"))
    }

    @Test
    fun `PolyglotContractBridge is idempotent with upstream manifests and handles union arrays, regex routes, Kafka topics, and dual req-resp drift`() {
        val consumerNode = ArcNode(
            id = "ts.web.ordersClient",
            label = "ordersClient.ts",
            kind = NodeKind.TS_CLIENT,
            contracts = listOf(
                ContractEndpoint(
                    protocol = "REST",
                    methodOrAction = "POST",
                    pathOrTopic = "https://api.acme.com\${API_PREFIX}/v1/orders/{id}//items",
                    role = "CONSUMER",
                    requestDto = "UpdateOrderReq",
                    responseDto = "UpdateOrderResp",
                    requestDtoFields = mapOf(
                        "status" to "number", // incompatible with String!
                        "tags" to "Array<string | null>",
                        "codes" to "readonly string[]",
                        "accounts" to "Array<IOrderItem>"
                    ),
                    dtoFields = mapOf(
                        "status" to "boolean", // ALSO incompatible with String in response!
                        "tags" to "(string | null)[]",
                        "matrix" to "any[]" // incompatible with scalar String!
                    )
                ),
                ContractEndpoint(
                    protocol = "KAFKA",
                    methodOrAction = "CONSUME",
                    pathOrTopic = "orders.events.v1",
                    role = "CONSUMER"
                )
            )
        )
        val manifest = ContractFederationManifest(
            exportedFromProjects = listOf("orders-backend"),
            producers = listOf(
                ExportedContractProducer(
                    projectName = "orders-backend",
                    nodeId = "com.acme.OrderController",
                    nodeLabel = "OrderController",
                    contract = ContractEndpoint(
                        protocol = "REST",
                        methodOrAction = "POST",
                        pathOrTopic = "/v1/orders/{orderId:[0-9a-f-]+}/items",
                        role = "PRODUCER",
                        requestDto = "UpdateOrderRequestDto",
                        responseDto = "UpdateOrderResponseDto",
                        requestDtoFields = mapOf(
                            "status" to "String",
                            "tags" to "List<String>",
                            "codes" to "Collection<String>",
                            "accounts" to "Iterable<OrderItemDto>"
                        ),
                        dtoFields = mapOf(
                            "status" to "String",
                            "tags" to "Set<String>",
                            "matrix" to "String"
                        )
                    )
                ),
                ExportedContractProducer(
                    projectName = "orders-backend",
                    nodeId = "com.acme.OrderPublisher",
                    nodeLabel = "OrderPublisher",
                    contract = ContractEndpoint(
                        protocol = "KAFKA",
                        methodOrAction = "PUBLISH",
                        pathOrTopic = "orders.events.v1",
                        role = "PRODUCER"
                    )
                )
            )
        )

        val initialGraph = ArcGraph(projectName = "web-app", nodes = listOf(consumerNode))
        // First pass without upstream marks consumer VIOLATION; second & third passes with upstream must be idempotent!
        val unlinked = PolyglotContractBridge.linkAndVerify(initialGraph).enrichedGraph
        val pass1 = PolyglotContractBridge.linkAndVerify(unlinked, manifest)
        val pass2 = PolyglotContractBridge.linkAndVerify(pass1.enrichedGraph, manifest)

        assertEquals(pass1.enrichedGraph.nodes.map { it.id }.toSet(), pass2.enrichedGraph.nodes.map { it.id }.toSet())
        assertTrue(pass2.enrichedGraph.nodes.none { it.id.startsWith("upstream:orders-backend:upstream:") })

        // Verify:
        // - regex path `/v1/orders/{orderId:[0-9a-f-]+}/items` matched `https://api.acme.com${API_PREFIX}/v1/orders/{id}//items`
        // - Kafka topic `orders.events.v1` matched withoutunresolved violation
        // - `Array<string | null>`, `(string | null)[]`, `readonly string[]`, `Collection<String>`, `Iterable<OrderItemDto>` <-> `Array<IOrderItem>` ALL matched cleanly!
        // - BOTH request-body `status` AND response `status` + `matrix` mismatches are reported!
        assertEquals(1, pass2.violations.size)
        val v = pass2.violations.single()
        assertTrue(v.missingOrMismatchedFields.any { it.contains("request body") && it.startsWith("status (") }, "Expected request body status in ${v.missingOrMismatchedFields}")
        assertTrue(v.missingOrMismatchedFields.any { it.startsWith("status (producer:") }, "Expected response status in ${v.missingOrMismatchedFields}")
        assertTrue(v.missingOrMismatchedFields.any { it.startsWith("matrix (") }, "Expected matrix any[] vs String mismatch in ${v.missingOrMismatchedFields}")
        assertFalse(v.missingOrMismatchedFields.any { it.contains("tags") || it.contains("codes") || it.contains("accounts") }, "Unexpected false positive in ${v.missingOrMismatchedFields}")

        // Verify ComplianceReportFormatter redacts proprietary routes, DTO names, and field names in feedback URL
        val feedbackUrl = ComplianceReportFormatter.buildFeedbackIssueUrl(v)
        assertFalse(feedbackUrl.contains("UpdateOrderReq"))
        assertFalse(feedbackUrl.contains("orders.events"))
        assertTrue(feedbackUrl.contains("labels=feedback,contract-bridge"))
        assertTrue(feedbackUrl.contains("dto-field-drift"))
    }

    @Test
    fun `ArcDriftSpiLoader isolates RuntimeExceptions from faulty providers while preserving fail-closed IllegalStateExceptions`() {
        val tempCl = object : ClassLoader(ArcDriftSpiLoader::class.java.classLoader) {
            override fun getResources(name: String) = when (name) {
                "META-INF/services/${PolicyPackProvider::class.java.name}" ->
                    java.util.Collections.enumeration(listOf(createServiceUrl(name, ThrowingRuntimePolicyProvider::class.java.name)))
                "META-INF/services/${ComplianceTelemetrySink::class.java.name}" ->
                    java.util.Collections.enumeration(listOf(createServiceUrl(name, ThrowingTelemetrySink::class.java.name)))
                else -> super.getResources(name)
            }
        }
        val baseGraph = ArcGraph(projectName = "spi-test")
        // RuntimeException in non-critical SPI provider is isolated
        val merged = ArcDriftSpiLoader.mergeOrgRules(baseGraph, File("."), tempCl)
        assertEquals("spi-test", merged.projectName)
        ArcDriftSpiLoader.recordComplianceCheck(
            ComplianceCheckSummary("spi-test", true, 0, 0, 0),
            tempCl
        )

        // Fail-closed IllegalStateException MUST propagate so misconfigured enterprise policy packs are never silently bypassed!
        val failClosedCl = object : ClassLoader(ArcDriftSpiLoader::class.java.classLoader) {
            override fun getResources(name: String) = when (name) {
                "META-INF/services/${PolicyPackProvider::class.java.name}" ->
                    java.util.Collections.enumeration(listOf(createServiceUrl(name, FailClosedPolicyProvider::class.java.name)))
                "META-INF/services/${ContractRegistryProvider::class.java.name}" ->
                    java.util.Collections.enumeration(listOf(createServiceUrl(name, FailClosedRegistryProvider::class.java.name)))
                else -> super.getResources(name)
            }
        }
        assertFailsWith<IllegalStateException> {
            ArcDriftSpiLoader.mergeOrgRules(baseGraph, File("."), failClosedCl)
        }
        assertFailsWith<IllegalStateException> {
            ArcDriftSpiLoader.loadAdditionalUpstreamManifests("spi-test", File("."), failClosedCl)
        }
    }

    private fun createServiceUrl(serviceName: String, implName: String): java.net.URL {
        val f = java.nio.file.Files.createTempFile("spi-${serviceName.substringAfterLast('.')}", ".txt").toFile()
        f.deleteOnExit()
        f.writeText(implName, Charsets.UTF_8)
        return f.toURI().toURL()
    }

    class ThrowingRuntimePolicyProvider : PolicyPackProvider {
        override fun loadOrgRules(projectName: String, repoRoot: File): List<BoundaryRule> {
            throw IllegalArgumentException("Simulated non-fatal provider bug")
        }
    }

    class FailClosedPolicyProvider : PolicyPackProvider {
        override fun loadOrgRules(projectName: String, repoRoot: File): List<BoundaryRule> {
            throw IllegalStateException("Explicitly configured policy pack missing")
        }
    }

    class FailClosedRegistryProvider : ContractRegistryProvider {
        override fun loadUpstreamManifests(projectName: String, repoRoot: File): List<ContractFederationManifest> {
            throw IllegalStateException("Explicitly configured registry dir missing")
        }
    }

    class ThrowingTelemetrySink : ComplianceTelemetrySink {
        override fun recordComplianceCheck(summary: ComplianceCheckSummary) {
            throw RuntimeException("Telemetry disk full")
        }
    }
}
