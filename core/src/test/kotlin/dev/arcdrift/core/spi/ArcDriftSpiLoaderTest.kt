package dev.arcdrift.core.spi

import dev.arcdrift.core.contracts.ContractFederationManifest
import dev.arcdrift.core.model.ArcGraph
import dev.arcdrift.core.model.BoundaryRule
import dev.arcdrift.core.model.RulePolicy
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ArcDriftSpiLoaderTest {

    class LowPriorityPolicyProvider : PolicyPackProvider {
        override val priority: Int = 200
        override fun loadOrgRules(projectName: String, repoRoot: File): List<BoundaryRule> = listOf(
            BoundaryRule("org-1", "Org Override", "*.a.*", "*.b.*", RulePolicy.FORBIDDEN, "low"),
            BoundaryRule("org-2", "Org Second", "*.x.*", "*.y.*", RulePolicy.FORBIDDEN, "second")
        )
    }

    class HighPriorityPolicyProvider : PolicyPackProvider {
        override val priority: Int = 10
        override fun loadOrgRules(projectName: String, repoRoot: File): List<BoundaryRule> = listOf(
            BoundaryRule("org-1", "Org Primary", "*.ctrl.*", "*.repo.*", RulePolicy.FORBIDDEN, "high")
        )
    }

    class SampleRegistryProvider : ContractRegistryProvider {
        override fun loadUpstreamManifests(projectName: String, repoRoot: File): List<ContractFederationManifest> =
            listOf(ContractFederationManifest(exportedFromProjects = listOf("upstream-billing")))

        override fun publishProducerManifest(graph: ArcGraph, manifest: ContractFederationManifest) {
            publishedProjects.addAll(manifest.exportedFromProjects)
        }

        companion object {
            val publishedProjects = mutableListOf<String>()
        }
    }

    class SampleTelemetrySink : ComplianceTelemetrySink {
        override fun recordComplianceCheck(summary: ComplianceCheckSummary) {
            recorded.add(summary)
        }

        companion object {
            val recorded = mutableListOf<ComplianceCheckSummary>()
        }
    }

    @Test
    fun `ArcDriftSpiLoader discovers ServiceLoader extensions, orders by priority, and merges rules`() {
        val tempServicesDir = Files.createTempDirectory("arcdrift-spi-test").toFile()
        try {
            val servicesDir = tempServicesDir.resolve("META-INF/services").apply { mkdirs() }
            servicesDir.resolve(PolicyPackProvider::class.java.name).writeText(
                "${LowPriorityPolicyProvider::class.java.name}\n${HighPriorityPolicyProvider::class.java.name}\n"
            )
            servicesDir.resolve(ContractRegistryProvider::class.java.name).writeText(
                "${SampleRegistryProvider::class.java.name}\n"
            )
            servicesDir.resolve(ComplianceTelemetrySink::class.java.name).writeText(
                "${SampleTelemetrySink::class.java.name}\n"
            )

            val cl = URLClassLoader(arrayOf(tempServicesDir.toURI().toURL()), javaClass.classLoader)

            val baseGraph = ArcGraph(
                projectName = "orders-api",
                rules = listOf(
                    BoundaryRule("local-1", "Local", "*.web.*", "*.db.*", RulePolicy.FORBIDDEN, "local"),
                    BoundaryRule("org-1", "Local Attempt to Override Org Rule", "none", "none", RulePolicy.ALLOWED_ONLY, "shadow")
                )
            )
            val merged = ArcDriftSpiLoader.mergeOrgRules(baseGraph, tempServicesDir, cl)
            assertEquals(listOf("local-1", "org-1", "org-2"), merged.rules.map { it.id })
            assertEquals("Org Primary", merged.rules.first { it.id == "org-1" }.name)
            assertEquals(RulePolicy.FORBIDDEN, merged.rules.first { it.id == "org-1" }.policy)

            val manifests = ArcDriftSpiLoader.loadAdditionalUpstreamManifests("orders-api", tempServicesDir, cl)
            assertEquals(listOf("upstream-billing"), manifests.flatMap { it.exportedFromProjects })

            SampleRegistryProvider.publishedProjects.clear()
            ArcDriftSpiLoader.publishProducerManifest(
                merged,
                ContractFederationManifest(exportedFromProjects = listOf("orders-api")),
                cl
            )
            assertEquals(listOf("orders-api"), SampleRegistryProvider.publishedProjects)

            SampleTelemetrySink.recorded.clear()
            val summary = ComplianceCheckSummary("orders-api", passed = true, 0, 0, 0)
            ArcDriftSpiLoader.recordComplianceCheck(summary, cl)
            assertEquals(listOf(summary), SampleTelemetrySink.recorded)
            assertTrue(ArcDriftSpiLoader.loadPolicyPackProviders().isEmpty())
        } finally {
            tempServicesDir.deleteRecursively()
        }
    }
}
