package dev.arcdrift.intellij

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.arcdrift.core.model.ArcGraph
import dev.arcdrift.core.model.ArcNode
import dev.arcdrift.core.model.BoundaryRule
import dev.arcdrift.core.model.C4Level
import dev.arcdrift.core.model.NodeKind
import dev.arcdrift.core.model.RulePolicy
import dev.arcdrift.core.model.SourceLocation
import dev.arcdrift.core.refactoring.RefactoringCommand
import dev.arcdrift.intellij.service.ArcDriftProjectService
import dev.arcdrift.intellij.ui.IdeSourceNavigator
import dev.arcdrift.intellij.ui.JcefCanvasPanel
import java.io.File
import kotlin.io.path.createTempDirectory

/**
 * Multi-case regression suite covering IDE VFS refresh filtering when ancestor paths contain `build/`,
 * relative path suffix priority over basename match, clearing all rules in `.arc.mmd`, null-safe
 * malformed webview payloads, and `ExtractInterface` upfront validation in `IntellijPsiRefactoringAdapter`.
 */
class IdeHardeningRegressionTest : BasePlatformTestCase() {

    fun testShouldTriggerGraphRefreshIgnoresAncestorBuildSegmentAndBlocksProjectBuildDir() {
        val base = "/home/ci/build/my-app"
        assertTrue(
            "Project under /home/ci/build/my-app must still trigger refresh for src/App.kt",
            IdeSourceNavigator.shouldTriggerGraphRefresh("$base/src/main/kotlin/App.kt", base)
        )
        assertFalse(
            "Generated files inside project's own /build/ directory must still be ignored",
            IdeSourceNavigator.shouldTriggerGraphRefresh("$base/build/generated/App.kt", base)
        )
        assertFalse(
            "Files outside projectBasePath must be ignored",
            IdeSourceNavigator.shouldTriggerGraphRefresh("/home/ci/other-app/src/App.kt", base)
        )
    }

    fun testResolveTargetSourceFilePrefersExactRelativeSuffixOverEarlierBasenameMatch() {
        val tempRoot = createTempDirectory("arcdrift-nav-priority").toFile()
        try {
            val aaaIndex = File(tempRoot, "aaa/index.ts").apply {
                parentFile.mkdirs()
                writeText("export const a = 1;")
            }
            val backendIndex = File(tempRoot, "backend/src/index.ts").apply {
                parentFile.mkdirs()
                writeText("export const b = 2;")
            }
            val resolved = IdeSourceNavigator.resolveTargetSourceFile(
                basePath = tempRoot.absolutePath,
                explicitPath = "backend/src/index.ts",
                nodeId = null,
                nodes = emptyList()
            )
            assertEquals(backendIndex.canonicalPath, resolved?.canonicalPath)
            assertFalse(aaaIndex.canonicalPath == resolved?.canonicalPath)

            // Fallback to basename match when explicit path has no matching suffix
            val fallbackByBase = IdeSourceNavigator.resolveTargetSourceFile(
                basePath = tempRoot.absolutePath,
                explicitPath = "nonexistent/index.ts",
                nodeId = null,
                nodes = emptyList()
            )
            assertNotNull(fallbackByBase)

            // Match via node metadata (relative & absolute paths)
            val nodeWithRel = ArcNode(
                id = "ts.backend.index",
                label = "BackendIndex",
                c4Level = C4Level.L3_COMPONENT,
                kind = NodeKind.TS_CLIENT,
                source = SourceLocation("backend/src/index.ts", 1, 2, "typescript")
            )
            val nodeWithAbs = ArcNode(
                id = "ts.aaa.index",
                label = "AaaIndex",
                c4Level = C4Level.L3_COMPONENT,
                kind = NodeKind.TS_CLIENT,
                source = SourceLocation(aaaIndex.absolutePath, 1, 2, "typescript")
            )
            assertEquals(
                backendIndex.canonicalPath,
                IdeSourceNavigator.resolveTargetSourceFile(tempRoot.absolutePath, "BackendIndex", null, listOf(nodeWithRel))?.canonicalPath
            )
            assertEquals(
                aaaIndex.canonicalPath,
                IdeSourceNavigator.resolveTargetSourceFile(tempRoot.absolutePath, "AaaIndex", null, listOf(nodeWithAbs))?.canonicalPath
            )
            assertEquals(
                aaaIndex.canonicalPath,
                IdeSourceNavigator.resolveTargetSourceFile(tempRoot.absolutePath, null, "ts.aaa.index", listOf(nodeWithAbs))?.canonicalPath
            )
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    fun testSyncDiskFilesIntoOpenDocumentsNormalizesBareCrAndCrlfAndIsolatesMissingFiles() {
        val basePath = project.basePath ?: return
        val baseDir = File(basePath).apply { mkdirs() }
        val targetFile = File(baseDir, "src/CrDocTest.kt").apply {
            parentFile.mkdirs()
            writeText("package demo\nclass Initial\n")
        }
        val missingFile = File(baseDir, "src/DeletedFile.kt")
        try {
            val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(targetFile)
            assertNotNull(vf)
            val doc = FileDocumentManager.getInstance().getDocument(vf!!)
            assertNotNull(doc)
            assertEquals("package demo\nclass Initial\n", doc!!.text)

            // Mutate file on disk with bare CR and CRLF line endings
            targetFile.writeText("package demo\rclass UpdatedCr\r\n")
            IdeSourceNavigator.syncDiskFilesIntoOpenDocuments(
                project,
                listOf(missingFile.absolutePath, targetFile.absolutePath)
            )
            assertEquals("package demo\nclass UpdatedCr\n", doc.text)
        } finally {
            targetFile.delete()
        }
    }

    fun testAddBoundaryRuleAndSaveCreatesSpecFileAtomicallyWhenMissing() {
        val basePath = project.basePath ?: return
        val baseDir = File(basePath).apply { mkdirs() }
        val specFile = File(baseDir, "architecture.arc.mmd")
        if (specFile.exists()) specFile.delete()
        try {
            val service = ArcDriftProjectService.getInstance(project)
            val rule = BoundaryRule("atomic-r1", "Atomic Rule", "*.web.*", "*.db.*", RulePolicy.FORBIDDEN, "layered")
            service.addBoundaryRuleAndSave(rule)
            assertTrue("Expected architecture.arc.mmd to be created", specFile.exists())
            assertTrue(specFile.readText(Charsets.UTF_8).contains("atomic-r1"))
        } finally {
            specFile.delete()
        }
    }

    fun testLoadRulesFromSpecFileReturnsEmptyListWhenValidSpecDeletesAllRules() {
        val service = ArcDriftProjectService.getInstance(project)
        val staleRule = BoundaryRule("stale-r1", "Stale", "*.a.*", "*.b.*", RulePolicy.FORBIDDEN, "old")
        service.updateGraph(ArcGraph(projectName = project.name, rules = listOf(staleRule)))

        val tempRoot = createTempDirectory("arcdrift-empty-rules").toFile()
        try {
            // Valid spec file with ZERO rules (user deleted all rules)
            File(tempRoot, "architecture.arc.mmd").writeText("flowchart TB\n  A[\"A\"]\n")
            val loaded = service.loadRulesFromSpecFile(tempRoot.absolutePath)
            assertTrue("Deleting all rules in valid spec file must not resurrect stale in-memory rules", loaded.isEmpty())
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    fun testJcefCanvasPanelHandlesMalformedJsonFieldTypesWithoutThrowing() {
        val panel = JcefCanvasPanel(project, testRootDisposable, enableJcefBrowser = false)
        // None of these malformed payloads may throw IllegalArgumentException / ClassCastException
        panel.handleIncomingWebviewMessage("""{"type":{}}""")
        panel.handleIncomingWebviewMessage("""{"type":[]}""")
        panel.handleIncomingWebviewMessage("""{"type":"SAVE_AGENT_CONFIG","baseUrl":{},"maxTurns":[]}""")
        panel.handleIncomingWebviewMessage("""{"type":"NAVIGATE_TO_SOURCE","filePath":[],"line":"not-a-number"}""")
        panel.handleIncomingWebviewMessage("""{"type":"FETCH_AGENT_MODELS","baseUrl":{},"apiKey":[]}""")
        panel.handleIncomingWebviewMessage("""{"type":"HANDOFF_TO_AGENT","agentBaseUrl":{},"maxTurns":{},"specPrompt":[]}""")
        panel.handleIncomingWebviewMessage("""{"type":"APPLY_REFACTORING","previewOnly":{},"command":{}}""")
    }

    fun testIntellijPsiRefactoringAdapterRejectsInvalidExtractInterfaceNamesUpfront() {
        val service = ArcDriftProjectService.getInstance(project)
        val badName = service.psiRefactoringAdapter.execute(
            RefactoringCommand.ExtractInterface("com.acme.OrderService", "123Invalid", "com.acme.ports"),
            previewOnly = false
        )
        assertFalse(badName.success)
        assertEquals("INVALID_IDENTIFIER", badName.errorMessage)

        val badPkg = service.psiRefactoringAdapter.execute(
            RefactoringCommand.ExtractInterface("com.acme.OrderService", "OrderPort", ""),
            previewOnly = false
        )
        assertFalse(badPkg.success)
        assertEquals("INVALID_IDENTIFIER", badPkg.errorMessage)
    }
}
