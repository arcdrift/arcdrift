package dev.arcdrift.intellij.ui

import com.intellij.ide.projectView.ProjectView
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.PsiDocumentManager
import dev.arcdrift.core.model.ArcNode
import dev.arcdrift.intellij.service.ArcDriftProjectService
import java.io.File

/**
 * Resolves source file paths & line numbers from canvas events and synchronizes
 * disk mutations with IntelliJ open documents, VFS, and the Project View.
 */
internal object IdeSourceNavigator {

    private val IGNORED_DIRS = setOf(".git", ".gradle", ".idea", "build", "dist", "node_modules")
    private val LINE_SUFFIX_REGEX = Regex("""(?:#L(\d+)(?:-L?\d+)?|:(\d+)(?::\d+)?)$""")

    fun parseZeroBasedLine(explicitLine: Int?, explicitPath: String?): Int {
        val parsedLineFromPath = explicitPath?.trim()?.let { raw ->
            val m = LINE_SUFFIX_REGEX.find(raw)
            (m?.groupValues?.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: m?.groupValues?.getOrNull(2))?.toIntOrNull()
        }
        return ((explicitLine ?: parsedLineFromPath ?: 1).coerceAtLeast(1)) - 1
    }

    private fun resolvePathInsideBaseDir(basePath: String, candidate: File): File? {
        val rootCanonical = runCatching { File(basePath).canonicalFile }.getOrDefault(File(basePath).absoluteFile)
        val rootPath = rootCanonical.toPath().normalize()
        val candidateCanonical = runCatching { candidate.canonicalFile }.getOrNull() ?: return null
        if (!candidateCanonical.toPath().normalize().startsWith(rootPath)) return null
        return candidateCanonical
    }

    private fun resolveInsideBaseDir(basePath: String, candidate: File): File? =
        resolvePathInsideBaseDir(basePath, candidate)?.takeIf { it.exists() && it.isFile }

    fun resolveTargetSourceFile(
        basePath: String?,
        explicitPath: String?,
        nodeId: String?,
        nodes: List<ArcNode>
    ): File? {
        if (basePath == null) return null
        val cleanedExplicit = explicitPath
            ?.trim()
            ?.removePrefix("file://")
            ?.substringBefore('#')
            ?.replace(Regex(""":\d+(?::\d+)?$"""), "")
            ?.replace('\\', '/')
            ?.trim()

        if (!cleanedExplicit.isNullOrBlank()) {
            val relTrimmed = cleanedExplicit.removePrefix("./").trimStart('/')
            if (relTrimmed == ".arc.mmd" || relTrimmed == "*.arc.mmd") {
                ArcDriftProjectService.findPrimarySpecFile(File(basePath))?.let { return it }
            }
            resolveInsideBaseDir(basePath, File(basePath, relTrimmed))?.let { return it }
            resolveInsideBaseDir(basePath, File(cleanedExplicit))?.let { return it }

            val matchedByNodePath = nodes.firstOrNull { n ->
                val sp = n.source?.filePath?.replace('\\', '/') ?: return@firstOrNull false
                sp == cleanedExplicit ||
                    sp == relTrimmed ||
                    sp.endsWith("/$relTrimmed") ||
                    n.label.equals(relTrimmed, ignoreCase = true) ||
                    n.id.equals(relTrimmed, ignoreCase = true)
            }?.source?.filePath
            if (!matchedByNodePath.isNullOrBlank()) {
                val c = if (File(matchedByNodePath).isAbsolute) {
                    File(matchedByNodePath)
                } else {
                    File(basePath, matchedByNodePath)
                }
                resolveInsideBaseDir(basePath, c)?.let { return it }
            }

            if (!relTrimmed.contains("..")) {
                val baseName = relTrimmed.substringAfterLast('/')
                var suffixMatch: File? = null
                var basenameMatch: File? = null
                File(basePath).walkTopDown()
                    .onEnter { it.name !in IGNORED_DIRS }
                    .forEach { f ->
                        if (!f.isFile) return@forEach
                        val norm = f.invariantSeparatorsPath
                        if (suffixMatch == null && norm.endsWith("/$relTrimmed")) {
                            suffixMatch = f
                        } else if (basenameMatch == null &&
                            (f.name.equals(baseName, ignoreCase = true) || f.nameWithoutExtension.equals(baseName, ignoreCase = true))
                        ) {
                            basenameMatch = f
                        }
                    }
                val foundInTree = suffixMatch ?: basenameMatch
                if (foundInTree != null) {
                    resolveInsideBaseDir(basePath, foundInTree)?.let { return it }
                }
            }
        }

        if (!nodeId.isNullOrBlank()) {
            val matchedNode = nodes.find { it.id == nodeId }
                ?: nodes.find { it.parentId == nodeId && it.source?.filePath != null }
            val nodePath = matchedNode?.source?.filePath
            if (!nodePath.isNullOrBlank()) {
                val candidate = if (File(nodePath).isAbsolute) {
                    File(nodePath)
                } else {
                    File(basePath, nodePath)
                }
                resolveInsideBaseDir(basePath, candidate)?.let { return it }
            }
            val simpleName = nodeId.substringAfterLast('.').removeSuffix(".ts").removeSuffix(".tsx").removeSuffix(".py")
            val found = File(basePath).walkTopDown()
                .onEnter { it.name !in IGNORED_DIRS }
                .firstOrNull { f ->
                    f.isFile && (f.nameWithoutExtension.equals(simpleName, ignoreCase = true) || f.name.equals(simpleName, ignoreCase = true))
                }
            if (found != null) {
                resolveInsideBaseDir(basePath, found)?.let { return it }
            }
        }
        return ArcDriftProjectService.findPrimarySpecFile(File(basePath))
    }

    fun openSourceInEditor(
        project: Project,
        explicitPath: String?,
        nodeId: String?,
        explicitLine: Int?,
        nodes: List<ArcNode>
    ) {
        val line = parseZeroBasedLine(explicitLine, explicitPath)
        ApplicationManager.getApplication().executeOnPooledThread {
            if (project.isDisposed) return@executeOnPooledThread
            val targetFile = resolveTargetSourceFile(project.basePath, explicitPath, nodeId, nodes) ?: return@executeOnPooledThread
            val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(targetFile) ?: return@executeOnPooledThread
            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed) return@invokeLater
                FileEditorManager.getInstance(project).openTextEditor(
                    OpenFileDescriptor(project, vf, line, 0),
                    true
                )
                runCatching {
                    ProjectView.getInstance(project)?.select(null, vf, false)
                }
            }
        }
    }

    private fun runOnEdtAndWait(action: Runnable) {
        val app = ApplicationManager.getApplication()
        if (app.isDispatchThread) {
            action.run()
        } else {
            app.invokeAndWait(action)
        }
    }

    fun flushUnsavedEditorDocuments(project: Project) {
        if (project.isDisposed) return
        runCatching {
            runOnEdtAndWait {
                if (!project.isDisposed) {
                    FileDocumentManager.getInstance().saveAllDocuments()
                }
            }
        }
    }

    fun syncDiskFilesIntoOpenDocuments(project: Project, touchedPaths: List<String>) {
        val base = project.basePath ?: return
        val baseDir = File(base)
        runCatching {
            runOnEdtAndWait {
                if (project.isDisposed) return@runOnEdtAndWait
                val lfs = LocalFileSystem.getInstance()
                val fdm = FileDocumentManager.getInstance()
                val pdm = PsiDocumentManager.getInstance(project)
                for (rel in touchedPaths) {
                    runCatching {
                        val rawCandidate = File(rel).let { if (it.isAbsolute) it else File(baseDir, rel) }
                        val ioFile = resolvePathInsideBaseDir(base, rawCandidate) ?: return@runCatching
                        if (!ioFile.exists()) {
                            ioFile.parentFile?.takeIf { it.exists() }?.let { parentDir ->
                                lfs.refreshIoFiles(listOf(parentDir), true, false, null)
                            }
                            return@runCatching
                        }
                        if (!ioFile.isFile) return@runCatching
                        val diskText = ioFile.readText(Charsets.UTF_8).replace("\r\n", "\n").replace('\r', '\n')
                        val vf = lfs.refreshAndFindFileByIoFile(ioFile) ?: return@runCatching
                        VfsUtil.markDirtyAndRefresh(false, false, false, vf)
                        val doc = fdm.getCachedDocument(vf)
                        if (doc != null && doc.text != diskText) {
                            WriteCommandAction.runWriteCommandAction(project) {
                                doc.setText(diskText)
                                pdm.commitDocument(doc)
                            }
                            fdm.saveDocument(doc)
                        } else {
                            fdm.reloadFiles(vf)
                        }
                    }
                }
            }
        }
    }

    fun refreshProjectVfsAndExplorer(project: Project, touchedPaths: List<String>, openPrimaryFile: Boolean) {
        val base = project.basePath ?: return
        val baseDir = File(base)
        ApplicationManager.getApplication().executeOnPooledThread {
            if (project.isDisposed) return@executeOnPooledThread
            val lfs = LocalFileSystem.getInstance()
            val resolvedPaths = touchedPaths.mapNotNull { rel ->
                val f = File(rel)
                resolvePathInsideBaseDir(base, if (f.isAbsolute) f else File(baseDir, rel))
            }
            val ioFiles = resolvedPaths.filter { it.exists() }
            val parentDirs = resolvedPaths.mapNotNull { it.parentFile?.takeIf { p -> p.exists() } }
            val targetsToRefresh = (ioFiles + parentDirs).distinct().ifEmpty { listOf(baseDir) }
            val refreshedVfs = ioFiles.mapNotNull { lfs.refreshAndFindFileByIoFile(it) }
            val primaryVf = refreshedVfs.firstOrNull()
            lfs.refreshIoFiles(targetsToRefresh, true, false, null)

            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed) return@invokeLater
                if (refreshedVfs.isNotEmpty()) {
                    VfsUtil.markDirtyAndRefresh(true, false, false, *refreshedVfs.toTypedArray())
                    FileDocumentManager.getInstance().reloadFiles(*refreshedVfs.toTypedArray())
                }
                runCatching {
                    val projectView = ProjectView.getInstance(project)
                    projectView?.refresh()
                    if (primaryVf != null && openPrimaryFile) {
                        projectView?.select(null, primaryVf, false)
                    }
                }
                if (primaryVf != null && openPrimaryFile) {
                    FileEditorManager.getInstance(project).openTextEditor(
                        OpenFileDescriptor(project, primaryVf, 0, 0),
                        true
                    )
                }
            }
        }
    }

    private val WATCHED_REFRESH_SUFFIXES = setOf(
        ".kt", ".kts", ".java", ".ts", ".tsx", ".py", ".arc.mmd", ".yaml", ".yml", ".tsp"
    )
    private val IGNORED_REFRESH_SEGMENTS = setOf(
        "/.git/", "/.gradle/", "/build/", "/target/", "/out/", "/node_modules/", "/dist/"
    )

    fun shouldTriggerGraphRefresh(filePath: String, projectBasePath: String?): Boolean {
        val base = projectBasePath?.trimEnd('/', '\\')?.replace('\\', '/') ?: return false
        val normalized = filePath.replace('\\', '/')
        if (!normalized.startsWith("$base/")) return false
        val relPath = "/" + normalized.removePrefix("$base/")
        val lowerRel = relPath.lowercase()
        if (IGNORED_REFRESH_SEGMENTS.any { it in lowerRel }) return false
        return WATCHED_REFRESH_SUFFIXES.any { lowerRel.endsWith(it) }
    }
}
