package com.github.mikrop.intellijkanbn.ui

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.jcef.JBCefApp
import javax.swing.SwingConstants

class KanbnToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val factory = ContentFactory.getInstance()
        if (!JBCefApp.isSupported()) {
            val label = JBLabel("Kanbn board potřebuje JCEF, které v tomto IDE není k dispozici.", SwingConstants.CENTER)
            toolWindow.contentManager.addContent(factory.createContent(label, null, false))
            return
        }
        val panel = KanbnBoardPanel(project)
        val content = factory.createContent(panel, null, false)
        Disposer.register(content, panel)
        toolWindow.contentManager.addContent(content)
    }
}
