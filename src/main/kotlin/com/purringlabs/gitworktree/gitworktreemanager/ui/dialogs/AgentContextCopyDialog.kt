package com.purringlabs.gitworktree.gitworktreemanager.ui.dialogs

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.purringlabs.gitworktree.gitworktreemanager.models.AgentContextCopyOption
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.table.AbstractTableModel

class AgentContextCopyDialog(
    project: Project,
    options: List<AgentContextCopyOption>,
    private val sourceRepoPath: java.nio.file.Path? = null
) : DialogWrapper(project) {
    private val tableModel = AgentContextCopyTableModel(options.toMutableList(), sourceRepoPath)
    private val table = JBTable(tableModel)

    init {
        title = "Copy Coding Agent Context"
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(0, 10))
        panel.add(
            JBLabel("Select the Claude Code context to copy into the new worktree."),
            BorderLayout.NORTH
        )

        table.setShowGrid(true)
        table.rowSelectionAllowed = false
        table.columnSelectionAllowed = false
        table.columnModel.getColumn(0).apply {
            maxWidth = 50
            preferredWidth = 50
        }
        table.columnModel.getColumn(1).preferredWidth = 240
        table.columnModel.getColumn(2).preferredWidth = 420

        val scrollPane = JBScrollPane(table)
        scrollPane.preferredSize = Dimension(820, 260)
        panel.add(scrollPane, BorderLayout.CENTER)

        val sessionOptions = tableModel.options().filter { it.type == AgentContextCopyOption.Type.CLAUDE_SESSION_HISTORY }
        if (sessionOptions.isNotEmpty()) {
            val controls = JPanel()
            controls.add(JButton("Select all sessions").apply {
                addActionListener { tableModel.setSessionsSelected(true) }
            })
            controls.add(JButton("Deselect all sessions").apply {
                addActionListener { tableModel.setSessionsSelected(false) }
            })
            panel.add(controls, BorderLayout.SOUTH)
        }
        return panel
    }

    fun selectedOptions(): List<AgentContextCopyOption> = tableModel.options()

    private class AgentContextCopyTableModel(
        private val options: MutableList<AgentContextCopyOption>,
        private val sourceRepoPath: java.nio.file.Path?
    ) : AbstractTableModel() {
        private val columnNames = arrayOf("Copy", "Session / Context", "Details")

        override fun getRowCount(): Int = options.size
        override fun getColumnCount(): Int = columnNames.size
        override fun getColumnName(column: Int): String = columnNames[column]

        override fun getColumnClass(columnIndex: Int): Class<*> {
            return if (columnIndex == 0) java.lang.Boolean::class.java else String::class.java
        }

        override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = columnIndex == 0

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val option = options[rowIndex]
            return when (columnIndex) {
                0 -> option.selected
                1 -> option.displayName
                2 -> if (option.type == AgentContextCopyOption.Type.CLAUDE_SESSION_HISTORY) {
                    buildString {
                        option.sourceProjectPath?.let { path ->
                            val source = sourceRepoPath?.toAbsolutePath()?.normalize()
                            val current = path.toAbsolutePath().normalize()
                            append(current.fileName?.toString() ?: current.toString())
                            if (source != null && current == source) {
                                append(" (this worktree)")
                            }
                            append("  •  ")
                        }
                        append(option.lastModified?.toString() ?: "Unknown time")
                    }
                } else option.description
                else -> ""
            }
        }

        override fun setValueAt(value: Any?, rowIndex: Int, columnIndex: Int) {
            if (columnIndex == 0 && value is Boolean) {
                options[rowIndex] = options[rowIndex].copy(selected = value)
                fireTableCellUpdated(rowIndex, columnIndex)
            }
        }

        fun options(): List<AgentContextCopyOption> = options.toList()

        fun setSessionsSelected(selected: Boolean) {
            options.indices.filter { options[it].type == AgentContextCopyOption.Type.CLAUDE_SESSION_HISTORY }
                .forEach { index -> options[index] = options[index].copy(selected = selected) }
            fireTableDataChanged()
        }
    }
}
