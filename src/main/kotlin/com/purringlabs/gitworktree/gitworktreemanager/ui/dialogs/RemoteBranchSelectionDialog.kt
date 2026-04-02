package com.purringlabs.gitworktree.gitworktreemanager.ui.dialogs

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.event.DocumentEvent

class RemoteBranchSelectionDialog(
    project: Project,
    remoteBranches: List<String>
) : DialogWrapper(project) {

    private val allBranches = remoteBranches
        .filterNot { it.endsWith("/HEAD") }
        .sorted()

    private val listModel = DefaultListModel<String>()
    private val branchList = JBList(listModel)
    private val searchField = JTextField()

    init {
        title = "Select Remote Branch"
        setOKButtonText("Select")
        init()
        refreshList("")
        branchList.selectedIndex = if (listModel.size() > 0) 0 else -1
    }

    val selectedBranch: String?
        get() = branchList.selectedValue

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(8, 8))
        panel.preferredSize = Dimension(480, 360)

        searchField.toolTipText = "Filter remote branches"
        searchField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                refreshList(searchField.text)
            }
        })

        panel.add(searchField, BorderLayout.NORTH)
        panel.add(JBScrollPane(branchList), BorderLayout.CENTER)
        return panel
    }

    override fun doOKAction() {
        if (branchList.selectedValue != null) {
            super.doOKAction()
        }
    }

    private fun refreshList(query: String) {
        val normalized = query.trim().lowercase()
        listModel.clear()
        allBranches
            .filter { normalized.isBlank() || it.lowercase().contains(normalized) }
            .forEach { listModel.addElement(it) }
        if (listModel.size() > 0 && branchList.selectedIndex == -1) {
            branchList.selectedIndex = 0
        }
    }
}
