package dev.arcdrift.core.scanner

import dev.arcdrift.core.codec.ArcMermaidSerializer
import dev.arcdrift.core.contracts.PolyglotContractBridge
import dev.arcdrift.core.model.ContractEndpoint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class ArcDriftIgnoreAndCacheTest {

    @Test
    fun `arcdriftignore parses path globs, trailing-slash directories, bare filenames, comments, and backslashes`() {
        val ignore = ArcDriftIgnore.parse(
            """
            # Generated and vendored directories
            generated/**
            vendor/
            **/fixtures/**
            legacyClient.ts
            src/v?/compat.kt
            """.trimIndent()
        )

        assertFalse(ignore.isEmpty)
        assertTrue(ignore.isPathIgnored("generated/api/client.ts"))
        assertTrue(ignore.isPathIgnored("vendor/lib/sdk.py"))
        assertTrue(ignore.isPathIgnored("services/billing/vendor/helper.ts"))
        assertTrue(ignore.isPathIgnored("web/fixtures/mockApi.ts"))
        assertTrue(ignore.isPathIgnored("packages/ui/fixtures/deep/sample.tsx"))
        assertTrue(ignore.isPathIgnored("legacyClient.ts"))
        assertTrue(ignore.isPathIgnored("web/src/legacyClient.ts"))
        assertTrue(ignore.isPathIgnored("src\\v2\\compat.kt"))

        assertFalse(ignore.isPathIgnored("src/main/kotlin/OrderController.kt"))
        assertFalse(ignore.isPathIgnored("src/v10/compat.kt"))
        assertFalse(ignore.isPathIgnored("web/src/orderClient.ts"))
    }

    @Test
    fun `arcdriftignore evaluates exact and wildcard host rules case-insensitively and strips ports`() {
        val ignore = ArcDriftIgnore.parse(
            """
            host:api.opsgenie.com
            host:*.zoom.us
            """.trimIndent()
        )

        assertTrue(ignore.isHostIgnored("https://api.opsgenie.com/v2/alerts"))
        assertTrue(ignore.isHostIgnored("https://API.OPSGENIE.COM:8443/v2/alerts?limit=10"))
        assertTrue(ignore.isHostIgnored("http://api.zoom.us/v2/meetings"))

        assertFalse(ignore.isHostIgnored("https://evilzoom.us/v2/meetings"))
        assertFalse(ignore.isHostIgnored("https://a.b.zoom.us/v2/meetings"))
        assertFalse(ignore.isHostIgnored("/api/v1/orders"))
    }

    @Test
    fun `arcdriftignore evaluates method-agnostic and method-specific route rules`() {
        val ignore = ArcDriftIgnore.parse(
            """
            route:/v2/billing/**
            route:POST /webhooks/**
            """.trimIndent()
        )

        val getBillingRoot = ContractEndpoint("REST", "GET", "/v2/billing", "CONSUMER")
        val getBilling = ContractEndpoint("REST", "GET", "/v2/billing/invoices/42?expand=true", "CONSUMER")
        val postBilling = ContractEndpoint("REST", "POST", "https://gateway.internal/v2/billing/charge", "CONSUMER")
        val postWebhook = ContractEndpoint("REST", "POST", "/webhooks/stripe/events", "CONSUMER")
        val getWebhook = ContractEndpoint("REST", "GET", "/webhooks/stripe/events", "CONSUMER")
        val internalOrder = ContractEndpoint("REST", "GET", "/api/v1/orders", "CONSUMER")

        assertTrue(ignore.isContractIgnored(getBillingRoot))
        assertTrue(ignore.isContractIgnored(getBilling))
        assertTrue(ignore.isContractIgnored(postBilling))
        assertTrue(ignore.isContractIgnored(postWebhook))
        assertFalse(ignore.isContractIgnored(getWebhook))
        assertFalse(ignore.isContractIgnored(internalOrder))
    }

    @Test
    fun `scanner and contract bridge suppress ignored paths, external hosts, and allowlisted routes while catching real drift`(@TempDir tempDir: File) {
        File(tempDir, ".arcdriftignore").writeText(
            """
            generated/**
            host:api.opsgenie.com
            route:POST /webhooks/**
            """.trimIndent()
        )

        // Ignored generated file (should not appear in graph at all)
        File(tempDir, "generated/client.ts").apply {
            parentFile.mkdirs()
            writeText("""export async function gen() { return fetch('/api/v1/should-not-be-scanned'); }""")
        }

        // Kotlin producer for /api/v1/orders
        File(tempDir, "src/main/kotlin/com/acme/OrderController.kt").apply {
            parentFile.mkdirs()
            writeText(
                """
                package com.acme

                @RestController
                @RequestMapping("/api/v1/orders")
                class OrderController {
                    @GetMapping("/{id}")
                    fun getOrder(id: String): String = id
                }
                """.trimIndent()
            )
        }

        // Python consumer calling external OpsGenie host + allowlisted webhook + real valid endpoint
        File(tempDir, "worker/notifier.py").apply {
            parentFile.mkdirs()
            writeText(
                """
                import requests

                def notify_all(order_id: str):
                    requests.post("https://api.opsgenie.com/v2/alerts", json={"id": order_id})
                    requests.post("/webhooks/slack", json={"id": order_id})
                    return requests.get(f"/api/v1/orders/{order_id}")
                """.trimIndent()
            )
        }

        val cache = RepoAstCache()
        val graph = PolyglotRepoScanner.scanRepository(tempDir, astCache = cache)

        assertFalse(
            graph.nodes.any { it.source?.filePath?.startsWith("generated/") == true },
            "Files matching .arcdriftignore path globs must be excluded from scan"
        )

        val ignore = ArcDriftIgnore.load(tempDir)
        val verification = PolyglotContractBridge.linkAndVerify(graph, ignoreConfig = ignore)
        assertTrue(
            verification.violations.isEmpty(),
            "Allowlisted host/route calls must not cause contract drift violations; got: ${verification.violations}"
        )

        // Now add an un-allowlisted broken internal route — MUST still be caught!
        File(tempDir, "web/brokenClient.ts").apply {
            parentFile.mkdirs()
            writeText("""export async function loadMissing() { return fetch('/api/v1/nonexistent'); }""")
        }
        val graphWithDrift = PolyglotRepoScanner.scanRepository(tempDir, astCache = cache)
        val driftResult = PolyglotContractBridge.linkAndVerify(graphWithDrift, ignoreConfig = ignore)
        assertEquals(1, driftResult.violations.size)
        assertTrue(driftResult.violations.single().message.contains("/api/v1/nonexistent"))

        // Also verify TS SDK-synthesized consumer contracts matching .arcdriftignore are stripped from node contracts
        File(tempDir, "web/sdk.ts").apply {
            parentFile.mkdirs()
            writeText(
                """
                export class OpsClient {
                    static sendAlert() {
                        return axios.post('https://api.opsgenie.com/v2/alerts', {});
                    }
                }
                """.trimIndent()
            )
        }
        File(tempDir, "web/dashboard.ts").apply {
            parentFile.mkdirs()
            writeText(
                """
                import { OpsClient } from './sdk';
                export function notify() {
                    return OpsClient.sendAlert();
                }
                """.trimIndent()
            )
        }
        val graphWithSdk = PolyglotRepoScanner.scanRepository(tempDir, astCache = cache)
        val dashboardNode = graphWithSdk.nodes.firstOrNull { it.source?.filePath == "web/dashboard.ts" }
        assertTrue(
            dashboardNode == null || dashboardNode.contracts.none { it.pathOrTopic == "/v2/alerts" },
            "Ignored SDK-synthesized consumer contracts must be filtered from scanned nodes too"
        )
    }

    @Test
    fun `ast cache tracks SHA-256 hits, invalidates on edit, bounds memory, and persists cleanly to disk`(@TempDir tempDir: File) {
        val ktFile = File(tempDir, "src/main/kotlin/com/acme/GreetingService.kt").apply {
            parentFile.mkdirs()
            writeText(
                """
                package com.acme
                class GreetingService {
                    fun hello(): String = "hi"
                }
                """.trimIndent()
            )
        }
        val tsFile = File(tempDir, "web/client.ts").apply {
            parentFile.mkdirs()
            writeText("""export function ping() { return fetch('/api/ping'); }""")
        }
        val pyFile = File(tempDir, "py/worker.py").apply {
            parentFile.mkdirs()
            writeText(
                """
                import requests
                def run():
                    return requests.get("/api/ping")
                """.trimIndent()
            )
        }
        val cacheDir = File(tempDir, ".cache-store")

        // Cold scan writes cache to disk
        val coldCache = RepoAstCache()
        PolyglotRepoScanner.scanRepository(tempDir, astCache = coldCache, diskCacheDir = cacheDir)
        val coldStats = coldCache.stats()
        assertEquals(0L, coldStats.hits)
        assertEquals(3L, coldStats.misses)
        assertEquals(3, coldStats.entries)
        assertTrue(File(cacheDir, RepoAstCache.CACHE_FILE_NAME).isFile)

        // Warm scan with a brand-new RepoAstCache instance loads from disk with 100% cache hits and skips redundant disk rewrite
        val cacheFile = File(cacheDir, RepoAstCache.CACHE_FILE_NAME)
        val initialWriteTime = cacheFile.lastModified() - 5_000L
        assertTrue(cacheFile.setLastModified(initialWriteTime))
        val warmCache = RepoAstCache()
        PolyglotRepoScanner.scanRepository(tempDir, astCache = warmCache, diskCacheDir = cacheDir)
        val warmStats = warmCache.stats()
        assertEquals(3L, warmStats.hits)
        assertEquals(0L, warmStats.misses)
        assertEquals(initialWriteTime, cacheFile.lastModified(), "Warm scan with 0 misses must not redundantly rewrite disk cache")

        // Second warm scan on same cache instance does not re-read disk cache or lose hits
        PolyglotRepoScanner.scanRepository(tempDir, astCache = warmCache, diskCacheDir = cacheDir)
        assertEquals(6L, warmCache.stats().hits)
        assertEquals(0L, warmCache.stats().misses)

        // Invalid maxEntries rejected
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
            RepoAstCache(maxEntries = 0)
        }

        // Edit one file -> exactly 1 cache miss and 2 cache hits on next scan
        ktFile.writeText(
            """
            package com.acme
            class GreetingService {
                fun hello(): String = "updated"
                fun bye(): String = "bye"
            }
            """.trimIndent()
        )
        warmCache.clear()
        PolyglotRepoScanner.scanRepository(tempDir, astCache = warmCache, diskCacheDir = cacheDir)
        val afterEditStats = warmCache.stats()
        assertEquals(2L, afterEditStats.hits)
        assertEquals(1L, afterEditStats.misses)

        // Bounded eviction does not crash and resets entry count
        val tinyCache = RepoAstCache(maxEntries = 2)
        val parseCount = AtomicInteger(0)
        repeat(5) { idx ->
            tinyCache.getOrParseTs(isTsx = false, source = "export const x$idx = $idx;") {
                parseCount.incrementAndGet()
                dev.arcdrift.core.ast.TsParsedFileAst(
                    importPaths = emptyList(),
                    types = emptyMap(),
                    contracts = emptyList(),
                    sdkMethods = emptyMap(),
                    sdkCalls = emptyList()
                )
            }
        }
        assertEquals(5, parseCount.get())
        assertTrue(tinyCache.stats().entries <= 2)

        // Corrupt or mismatched schema disk cache is silently ignored
        File(cacheDir, RepoAstCache.CACHE_FILE_NAME).writeText("""{"schemaVersion":"legacy-v0","jvmEntries":{}}""")
        val mismatchedCache = RepoAstCache()
        mismatchedCache.loadFromDisk(cacheDir)
        assertEquals(0, mismatchedCache.stats().entries)

        File(cacheDir, RepoAstCache.CACHE_FILE_NAME).writeText("<<<not json>>>")
        mismatchedCache.loadFromDisk(cacheDir)
        assertEquals(0, mismatchedCache.stats().entries)

        // Keep references used
        assertTrue(tsFile.exists() && pyFile.exists())
    }

    @Test
    fun `parallel multi-core scanning across 20 polyglot files is byte-for-byte deterministic across cold, warm, and concurrent runs`(@TempDir tempDir: File) {
        // Generate 20 polyglot files (> PARALLEL_SCAN_THRESHOLD = 8) across Kotlin, Java, TS, TSX, Python
        for (i in 1..6) {
            File(tempDir, "src/main/kotlin/com/acme/svc$i/Service$i.kt").apply {
                parentFile.mkdirs()
                writeText(
                    """
                    package com.acme.svc$i

                    @RestController
                    @RequestMapping("/api/v1/items$i")
                    class ItemController$i {
                        @GetMapping("/{id}")
                        fun getItem$i(id: String): String = id
                    }
                    """.trimIndent()
                )
            }
        }
        for (i in 1..4) {
            File(tempDir, "src/main/java/com/acme/java$i/Helper$i.java").apply {
                parentFile.mkdirs()
                writeText(
                    """
                    package com.acme.java$i;
                    public class Helper$i {
                        public String format(String v) { return v; }
                    }
                    """.trimIndent()
                )
            }
        }
        for (i in 1..4) {
            File(tempDir, "web/src/client$i.ts").apply {
                parentFile.mkdirs()
                writeText("""export async function fetchItem$i(id: string) { return fetch(`/api/v1/items$i/${'$'}{id}`); }""")
            }
        }
        for (i in 1..3) {
            File(tempDir, "web/src/Widget$i.tsx").apply {
                parentFile.mkdirs()
                writeText(
                    """
                    export function Widget$i() {
                        fetch('/api/v1/items$i/42');
                        return <div>Item $i</div>;
                    }
                    """.trimIndent()
                )
            }
        }
        for (i in 1..3) {
            File(tempDir, "py/consumer$i.py").apply {
                parentFile.mkdirs()
                writeText(
                    """
                    import requests
                    def fetch_$i():
                        return requests.get("/api/v1/items$i/99")
                    """.trimIndent()
                )
            }
        }

        val coldCache = RepoAstCache()
        val coldMermaid = ArcMermaidSerializer.serialize(
            PolyglotRepoScanner.scanRepository(tempDir, astCache = coldCache)
        )
        assertEquals(20L, coldCache.stats().misses)

        val warmMermaid = ArcMermaidSerializer.serialize(
            PolyglotRepoScanner.scanRepository(tempDir, astCache = coldCache)
        )
        assertEquals(coldMermaid, warmMermaid, "Warm cached scan must match cold scan byte-for-byte")
        assertEquals(20L, coldCache.stats().hits)

        // Concurrent 4-thread parallel scan stress test
        val pool = Executors.newFixedThreadPool(4)
        try {
            val futures = (1..4).map {
                pool.submit(
                    Callable {
                        val isolatedCache = RepoAstCache()
                        ArcMermaidSerializer.serialize(
                            PolyglotRepoScanner.scanRepository(tempDir, astCache = isolatedCache)
                        )
                    }
                )
            }
            for (f in futures) {
                assertEquals(
                    coldMermaid,
                    f.get(),
                    "Concurrent parallel scans must produce byte-for-byte identical .arc.mmd output"
                )
            }
        } finally {
            pool.shutdownNow()
        }
    }
}
