package com.purringlabs.gitworktree.gitworktreemanager.ui.dialogs

import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.datatransfer.StringSelection
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextArea

/**
 * Generic error dialog that shows a friendly summary plus an optional technical details section.
 *
 * Intended to help users understand the *exact* cause without drowning them in raw stderr by default.
 */
class ErrorDetailsDialog(
    project: Project,
    private val titleText: String,
    private val summary: String,
    private val actions: List<String> = emptyList(),
    private val detailsText: String? = null,
    private val copyText: String? = null
) : DialogWrapper(project) {

    init {
        title = titleText
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(0, JBUI.scale(10)))

        val body = buildString {
            appendLine(summary.trim())
            if (actions.isNotEmpty()) {
                appendLine()
                appendLine("What you can do:")
                actions.forEach { appendLine("• ${it.trim()}") }
            }
        }

        val bodyLabel = JBLabel("<html>${escapeHtml(body).replace("\n", "<br/>")}</html>")
        panel.add(bodyLabel, BorderLayout.NORTH)

        if (!detailsText.isNullOrBlank()) {
            val textArea = JTextArea(detailsText.trim()).apply {
                isEditable = false
                lineWrap = true
                wrapStyleWord = true
            }

            val scrollPane = JBScrollPane(textArea)
            scrollPane.preferredSize = Dimension(600, 260)
            panel.add(scrollPane, BorderLayout.CENTER)
        }

        return panel
    }

    override fun createActions(): Array<Action> {
        val copyAction = object : DialogWrapperAction("Copy details") {
            override fun doAction(e: java.awt.event.ActionEvent?) {
                val text = (copyText ?: detailsText).orEmpty().trim()
                if (text.isNotBlank()) {
                    CopyPasteManager.getInstance().setContents(StringSelection(text))
                }
            }
        }
        copyAction.isEnabled = !((copyText ?: detailsText).isNullOrBlank())

        return arrayOf(copyAction, okAction)
    }

    private fun escapeHtml(input: String): String {
        return input
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
    }
}
