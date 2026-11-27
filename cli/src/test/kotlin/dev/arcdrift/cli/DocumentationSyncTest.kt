package dev.arcdrift.cli

import dev.arcdrift.core.model.C4Level
import dev.arcdrift.core.model.RulePolicy
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Ensures the Astro documentation site (`docs/src/content/docs`) remains strictly
 * synchronized with the actual ArcDrift core models, MCP server tools, CLI subcommands,
 * and JCEF IDE bridge protocol at all times.
 */
class DocumentationSyncTest {

    private fun resolveDocsRoot(): File {
        val candidates = listOf(
            File("../docs/src/content/docs"),
            File("docs/src/content/docs"),
            File("arcdrift/docs/src/content/docs")
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error("Could not locate docs/src/content/docs from ${File(".").absolutePath}")
    }

    private fun resolveRepoRoot(): File {
        return resolveDocsRoot().parentFile.parentFile.parentFile.parentFile
    }

    @Test
    fun `all sidebar slugs in astro config correspond to existing MDX pages`() {
        val docsRoot = resolveDocsRoot()
        val astroConfig = File(docsRoot.parentFile.parentFile.parentFile, "astro.config.mjs").readText()
        val slugRegex = Regex("""slug:\s*'([^']+)'""")
        val slugs = slugRegex.findAll(astroConfig).map { it.groupValues[1] }.toList()

        assertTrue(slugs.size >= 14, "Expected at least 14 documented pages in astro.config.mjs sidebar")

        for (slug in slugs) {
            val mdxFile = File(docsRoot, "$slug.mdx")
            val mdFile = File(docsRoot, "$slug.md")
            assertTrue(
                mdxFile.isFile || mdFile.isFile,
                "Missing documentation page for sidebar slug '$slug' (expected ${mdxFile.path})"
            )
        }
    }

    @Test
    fun `all RefactoringCommand types are documented in MCP reference and refactoring guide`() {
        val docsRoot = resolveDocsRoot()
        val mcpDoc = File(docsRoot, "reference/mcp-server-tools.mdx").readText()
        val refactoringGuide = File(docsRoot, "guides/deterministic-refactoring.mdx").readText()

        val refactoringTypes = listOf(
            "EXTRACT_INTERFACE",
            "RENAME_SYMBOL",
            "MOVE_CLASS_OR_PACKAGE",
            "CREATE_NODE",
            "ADD_DEPENDENCY"
        )

        for (typeName in refactoringTypes) {
            assertTrue(
                mcpDoc.contains(typeName),
                "RefactoringCommand '$typeName' is missing from reference/mcp-server-tools.mdx"
            )
            assertTrue(
                refactoringGuide.contains(typeName),
                "RefactoringCommand '$typeName' is missing from guides/deterministic-refactoring.mdx"
            )
        }
    }

    @Test
    fun `all C4Level enum values and RulePolicy values are documented`() {
        val docsRoot = resolveDocsRoot()
        val c4Doc = File(docsRoot, "concepts/c4-semantic-zoom.mdx").readText()
        val mcpDoc = File(docsRoot, "reference/mcp-server-tools.mdx").readText()

        for (level in C4Level.entries) {
            assertTrue(
                c4Doc.contains(level.name),
                "C4Level.${level.name} is missing from concepts/c4-semantic-zoom.mdx"
            )
        }

        for (policy in RulePolicy.entries) {
            assertTrue(
                mcpDoc.contains(policy.name),
                "RulePolicy.${policy.name} is missing from reference/mcp-server-tools.mdx"
            )
        }
    }

    @Test
    fun `all MCP server and agent tools are documented in mcp-server-tools reference`() {
        val docsRoot = resolveDocsRoot()
        val mcpDoc = File(docsRoot, "reference/mcp-server-tools.mdx").readText()
        val agentDoc = File(docsRoot, "guides/self-healing-agent.mdx").readText()

        val expectedMcpTools = listOf(
            "get_architecture_slice",
            "propose_structural_diff",
            "execute_deterministic_refactoring",
            "verify_architectural_compliance"
        )

        for (toolName in expectedMcpTools) {
            assertTrue(
                mcpDoc.contains(toolName),
                "MCP tool '$toolName' is missing from reference/mcp-server-tools.mdx"
            )
        }

        assertTrue(
            !mcpDoc.contains("write_project_file"),
            "External MCP reference (mcp-server-tools.mdx) must not expose raw write_project_file tool"
        )
        assertTrue(
            agentDoc.contains("read_project_file") && agentDoc.contains("write_project_file"),
            "In-IDE workspace tools 'read_project_file' and 'write_project_file' should be documented in guides/self-healing-agent.mdx"
        )
    }

    @Test
    fun `all CLI subcommands, options, and default values are documented in cli-reference`() {
        val docsRoot = resolveDocsRoot()
        val cliDoc = File(docsRoot, "reference/cli-reference.mdx").readText()

        val expectedSubcommands = listOf("arc scan", "arc check", "arc diff", "arc slice", "arc mcp", "arc ui")
        for (cmd in expectedSubcommands) {
            assertTrue(
                cliDoc.contains(cmd),
                "CLI subcommand '$cmd' is missing from reference/cli-reference.mdx"
            )
        }

        val expectedOptionsAndDefaults = listOf(
            "--output",
            "--format",
            "--spec",
            "--export-contracts",
            "--upstream-contracts",
            "--generate-archunit",
            "--base-package",
            "--depth",
            "--scope",
            "--level",
            "--port",
            "--no-open",
            "sarif",
            "sonar",
            "`\".\"`",
            "`\"mmd\"`",
            "`\"text\"`",
            "`\"com.acme\"`",
            "`\"markdown\"`",
            "`3`",
            "`2`",
            "`4747`",
            "`false`"
        )
        for (item in expectedOptionsAndDefaults) {
            assertTrue(
                cliDoc.contains(item),
                "CLI option or default value '$item' is missing from reference/cli-reference.mdx"
            )
        }

        // Verify that every subcommand section has an "### Options & Arguments" table with a "Default" column
        val optionsTableCount = Regex("""### Options & Arguments""").findAll(cliDoc).count()
        assertTrue(
            optionsTableCount == expectedSubcommands.size,
            "Expected ${expectedSubcommands.size} '### Options & Arguments' tables in cli-reference.mdx, found $optionsTableCount"
        )
    }

    @Test
    fun `all IDE bridge message types in bridge_ts are wired between JcefCanvasPanel and ArcCanvas`() {
        val repoRoot = resolveRepoRoot()
        val bridgeTs = File(repoRoot, "webview/src/types/bridge.ts").readText()
        val jcefPanel = File(repoRoot, "intellij/src/main/kotlin/dev/arcdrift/intellij/ui/JcefCanvasPanel.kt").readText()
        val refactoringContracts = File(repoRoot, "core/src/main/kotlin/dev/arcdrift/core/refactoring/RefactoringContracts.kt").readText()
        val arcCanvas = File(repoRoot, "webview/src/components/ArcCanvas.tsx").readText()
        val architectureDoc = File(repoRoot, "ARCHITECTURE.md").readText()

        val messageTypeRegex = Regex("""type:\s*'([A-Z_]+)'""")
        val messageTypes = messageTypeRegex.findAll(bridgeTs).map { it.groupValues[1] }.toSet()

        assertTrue(messageTypes.isNotEmpty(), "Expected to extract message types from bridge.ts")
        val combinedBridgeHandlers = "$jcefPanel\n$refactoringContracts\n$arcCanvas\n$architectureDoc"
        for (msgType in messageTypes) {
            assertTrue(
                combinedBridgeHandlers.contains(msgType),
                "Message/command type '$msgType' from bridge.ts is not handled in JcefCanvasPanel.kt / ArcCanvas.tsx"
            )
        }
    }

    @Test
    fun `documentation is written from the ArcDrift user perspective without internal developer class names or local file URIs`() {
        val docsRoot = resolveDocsRoot()
        val forbiddenInternalTerms = listOf(
            "file:///home/",
            "UastGraphExtractor",
            "PolyglotRepoScanner",
            "JcefCanvasPanel",
            "PropertiesComponent",
            "WriteCommandAction",
            "InMemoryGraphRefactoringAdapter",
            "TypeScriptAstParser",
            "PythonAstParser",
            "GateG4RealWorldBenchmarkTest",
            "kotlin-compiler-embeddable",
            "filterGraphByZoomAndDiff",
            "JCEF Tearing",
            "2024.2",
            "Community or Ultimate",
            "Android Studio"
        )

        docsRoot.walkTopDown()
            .filter { it.isFile && (it.extension == "mdx" || it.extension == "md") }
            .forEach { docFile ->
                val text = docFile.readText()
                for (term in forbiddenInternalTerms) {
                    assertTrue(
                        !text.contains(term),
                        "Documentation file '${docFile.relativeTo(docsRoot)}' contains internal developer term '$term' instead of user-facing perspective"
                    )
                }
            }
    }

    @Test
    fun `documentation and UI strings use spaced emdashes instead of endashes or unspaced emdashes`() {
        val docsRoot = resolveDocsRoot()
        val unspacedEmdashRegex = Regex("""\S\u2014|\u2014\S""")

        docsRoot.walkTopDown()
            .filter { it.isFile && (it.extension == "mdx" || it.extension == "md") }
            .forEach { docFile ->
                val text = docFile.readText()
                val relPath = docFile.relativeTo(docsRoot)
                assertTrue(
                    !text.contains('\u2013'),
                    "Documentation file '$relPath' contains an endash (\u2013); use a spaced emdash (' \u2014 ') instead"
                )
                val unspacedMatch = unspacedEmdashRegex.find(text)
                assertTrue(
                    unspacedMatch == null,
                    "Documentation file '$relPath' contains an unspaced emdash ('${unspacedMatch?.value}'); keep whitespace around emdashes (' \u2014 ')"
                )
            }
    }

    @Test
    fun `developer documentation in ARCHITECTURE_md and README stays synchronized with settings_gradle_kts modules and plugin metadata`() {
        val repoRoot = resolveRepoRoot()
        val settingsGradle = File(repoRoot, "settings.gradle.kts").readText()
        val moduleRegex = Regex(""":(arcdrift-[a-z]+)""")
        val modules = moduleRegex.findAll(settingsGradle).map { it.groupValues[1] }.toSet()

        val readme = File(repoRoot, "README.md").readText()
        val architectureDoc = File(repoRoot, "ARCHITECTURE.md").readText()

        for (mod in modules) {
            assertTrue(readme.contains(mod), "Module '$mod' from settings.gradle.kts is missing in root README.md")
            assertTrue(architectureDoc.contains(mod), "Module '$mod' from settings.gradle.kts is missing in ARCHITECTURE.md")
        }

        // Verify JetBrains Marketplace plugin.xml compliance & EPL-2.0 third-party notices
        val pluginXml = File(repoRoot, "intellij/src/main/resources/META-INF/plugin.xml").readText()
        val idMatch = Regex("""<id>([^<]+)</id>""").find(pluginXml)?.groupValues?.get(1).orEmpty()
        val nameMatch = Regex("""<name>([^<]+)</name>""").find(pluginXml)?.groupValues?.get(1).orEmpty()
        assertTrue(
            idMatch.isNotBlank() && !idMatch.lowercase().contains("intellij"),
            "JetBrains Marketplace plugin <id> ('$idMatch') must not contain 'intellij'"
        )
        assertTrue(
            nameMatch == "ArcDrift",
            "JetBrains Marketplace plugin <name> ('$nameMatch') should be 'ArcDrift' (without 'Plugin', 'IntelliJ', or 'JetBrains')"
        )

        val noticesFile = File(repoRoot, "THIRD-PARTY-NOTICES.md")
        assertTrue(noticesFile.isFile, "Expected THIRD-PARTY-NOTICES.md at repository root")
        val noticesText = noticesFile.readText()
        assertTrue(
            noticesText.contains("@dagrejs/dagre") && noticesText.contains("MIT License") && !noticesText.contains("EPL-2.0"),
            "THIRD-PARTY-NOTICES.md must document @dagrejs/dagre under MIT License with zero EPL-2.0 dependencies"
        )
        val webviewPkg = File(repoRoot, "webview/package.json").readText()
        assertTrue(
            webviewPkg.contains("@dagrejs/dagre") && !webviewPkg.contains("elkjs"),
            "webview/package.json must use @dagrejs/dagre (MIT) instead of elkjs (EPL-2.0)"
        )
    }
}
