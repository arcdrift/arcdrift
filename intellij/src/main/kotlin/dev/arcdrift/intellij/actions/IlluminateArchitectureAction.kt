package dev.arcdrift.intellij.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.wm.ToolWindowManager
import dev.arcdrift.intellij.service.ArcDriftProjectService

/**
 * Editor & Project View action that refreshes the ArcDrift architecture graph
 * and opens the "ArcDrift" Spec-and-Verify tool window.
 */
class IlluminateArchitectureAction : AnAction("Illuminate Architecture in ArcDrift"), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        ArcDriftProjectService.getInstance(project).scheduleRefreshProjectGraph(0)
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("ArcDrift")
        toolWindow?.activate(null)
    }
}
