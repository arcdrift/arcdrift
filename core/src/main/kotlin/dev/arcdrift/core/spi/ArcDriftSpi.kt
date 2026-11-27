package dev.arcdrift.core.spi

import dev.arcdrift.core.contracts.ContractFederationManifest
import dev.arcdrift.core.model.ArcGraph
import dev.arcdrift.core.model.BoundaryRule
import java.io.File
import java.util.ServiceConfigurationError
import java.util.ServiceLoader

/**
 * Summary emitted to [ComplianceTelemetrySink] implementations after a compliance check completes.
 */
data class ComplianceCheckSummary(
    val projectName: String,
    val passed: Boolean,
    val cycleCount: Int,
    val ruleViolationCount: Int,
    val contractViolationCount: Int
)

/**
 * Extension point for federated contract registries (e.g. remote/team `.arc-contracts.json` stores).
 * Implementations are discovered via [java.util.ServiceLoader].
 */
interface ContractRegistryProvider {
    val priority: Int get() = 100

    fun loadUpstreamManifests(projectName: String, repoRoot: File): List<ContractFederationManifest> = emptyList()

    fun publishProducerManifest(graph: ArcGraph, manifest: ContractFederationManifest) {}

    fun publishProducerManifest(graph: ArcGraph, manifest: ContractFederationManifest, repoRoot: File?) {
        publishProducerManifest(graph, manifest)
    }
}

/**
 * Extension point for organization-wide `.arc.mmd` boundary policy packs.
 * Implementations are discovered via [java.util.ServiceLoader].
 */
interface PolicyPackProvider {
    val priority: Int get() = 100

    fun loadOrgRules(projectName: String, repoRoot: File): List<BoundaryRule> = emptyList()
}

/**
 * Extension point for compliance check telemetry / audit sinks.
 * Implementations are discovered via [java.util.ServiceLoader].
 */
interface ComplianceTelemetrySink {
    fun recordComplianceCheck(summary: ComplianceCheckSummary) {}
}

/**
 * Discovers and invokes optional [ServiceLoader]-registered extensions while keeping
 * Apache-2.0 core execution zero-dependency and local-first when no extensions are present.
 */
object ArcDriftSpiLoader {

    private fun defaultClassLoader(): ClassLoader? =
        ArcDriftSpiLoader::class.java.classLoader ?: Thread.currentThread().contextClassLoader

    private fun <S : Any> safeLoadServices(serviceClass: Class<S>, classLoader: ClassLoader?): List<S> {
        val loader = if (classLoader != null) {
            ServiceLoader.load(serviceClass, classLoader)
        } else {
            ServiceLoader.load(serviceClass)
        }
        val instances = mutableListOf<S>()
        val iterator = loader.iterator()
        var consecutiveFailures = 0
        while (true) {
            val hasMore = try {
                iterator.hasNext()
            } catch (e: ServiceConfigurationError) {
                break
            } catch (e: LinkageError) {
                break
            } catch (e: Exception) {
                break
            }
            if (!hasMore) break
            val next = try {
                iterator.next()
            } catch (_: ServiceConfigurationError) {
                if (++consecutiveFailures >= 32) break
                continue
            } catch (_: LinkageError) {
                if (++consecutiveFailures >= 32) break
                continue
            } catch (_: Exception) {
                if (++consecutiveFailures >= 32) break
                continue
            }
            consecutiveFailures = 0
            instances.add(next)
        }
        return instances
    }

    fun loadContractRegistryProviders(
        classLoader: ClassLoader? = defaultClassLoader()
    ): List<ContractRegistryProvider> =
        safeLoadServices(ContractRegistryProvider::class.java, classLoader)
            .sortedBy { it.priority }

    fun loadPolicyPackProviders(
        classLoader: ClassLoader? = defaultClassLoader()
    ): List<PolicyPackProvider> =
        safeLoadServices(PolicyPackProvider::class.java, classLoader)
            .sortedBy { it.priority }

    fun loadComplianceTelemetrySinks(
        classLoader: ClassLoader? = defaultClassLoader()
    ): List<ComplianceTelemetrySink> =
        safeLoadServices(ComplianceTelemetrySink::class.java, classLoader)

    fun mergeOrgRules(
        graph: ArcGraph,
        repoRoot: File,
        classLoader: ClassLoader? = defaultClassLoader()
    ): ArcGraph {
        val extraRules = loadPolicyPackProviders(classLoader)
            .flatMap { provider ->
                try {
                    provider.loadOrgRules(graph.projectName, repoRoot)
                } catch (ise: IllegalStateException) {
                    throw ise
                } catch (_: Throwable) {
                    emptyList()
                }
            }
            .distinctBy { it.id }
        if (extraRules.isEmpty()) return graph
        // Organization policy pack rules take precedence on ID collision while preserving local rule ordering
        val orgById = extraRules.associateBy { it.id }
        val resolvedLocal = graph.rules.map { local -> orgById[local.id] ?: local }
        val merged = (resolvedLocal + extraRules).distinctBy { it.id }
        return graph.copy(rules = merged)
    }

    fun loadAdditionalUpstreamManifests(
        projectName: String,
        repoRoot: File,
        classLoader: ClassLoader? = defaultClassLoader()
    ): List<ContractFederationManifest> =
        loadContractRegistryProviders(classLoader)
            .flatMap { provider ->
                try {
                    provider.loadUpstreamManifests(projectName, repoRoot)
                } catch (ise: IllegalStateException) {
                    throw ise
                } catch (_: Throwable) {
                    emptyList()
                }
            }

    fun publishProducerManifest(
        graph: ArcGraph,
        manifest: ContractFederationManifest,
        classLoader: ClassLoader? = defaultClassLoader(),
        repoRoot: File? = null
    ) {
        for (provider in loadContractRegistryProviders(classLoader)) {
            provider.publishProducerManifest(graph, manifest, repoRoot)
        }
    }

    fun recordComplianceCheck(
        summary: ComplianceCheckSummary,
        classLoader: ClassLoader? = defaultClassLoader()
    ) {
        for (sink in loadComplianceTelemetrySinks(classLoader)) {
            runCatching { sink.recordComplianceCheck(summary) }
        }
    }
}
