package dev.arcdrift.intellij.ui

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import dev.arcdrift.intellij.service.ArcDriftProjectService

/**
 * Registers the "ArcDrift" Spec-and-Verify Architecture Canvas tool window on the right IDE dock.
 */
class ArcDriftToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        ArcDriftProjectService.getInstance(project).scheduleRefreshProjectGraph(0)
        val panel = JcefCanvasPanel(project, toolWindow.disposable)
        val content = ContentFactory.getInstance().createContent(panel, "Architecture Canvas", false)
        toolWindow.contentManager.addContent(content)
    }
}
