package com.purringlabs.gitworktree.gitworktreemanager.ui.dialogs

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.purringlabs.gitworktree.gitworktreemanager.models.IgnoredFileInfo
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.*
import javax.swing.table.AbstractTableModel

/**
 * Dialog for selecting which ignored files to copy to a new worktree
 *
 * Displays a table of ignored files with checkboxes, file paths, types, and sizes.
 * Users can select/deselect files and use Select All/Deselect All buttons.
 */
class IgnoredFilesSelectionDialog(
    project: Project,
    private val ignoredFiles: List<IgnoredFileInfo>
) : DialogWrapper(project) {

    private val tableModel = IgnoredFilesTableModel(ignoredFiles.toMutableList())
    private val table = JBTable(tableModel)

    init {
        title = "Select Ignored Files to Copy"
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout())

        // Table setup
        table.setShowGrid(true)
        table.rowSelectionAllowed = false
        table.columnSelectionAllowed = false

        // Set column widths
        table.columnModel.getColumn(0).apply {
            maxWidth = 50
            preferredWidth = 50
        }
        table.columnModel.getColumn(1).preferredWidth = 300
        table.columnModel.getColumn(2).apply {
            maxWidth = 100
            preferredWidth = 100
        }
        table.columnModel.getColumn(3).apply {
            maxWidth = 100
            preferredWidth = 100
        }

        val scrollPane = JBScrollPane(table)
        scrollPane.preferredSize = Dimension(600, 400)
        panel.add(scrollPane, BorderLayout.CENTER)

        // Buttons panel
        val buttonsPanel = JPanel()
        val selectAllButton = JButton("Select All").apply {
            addActionListener {
                tableModel.selectAll(true)
            }
        }
        val deselectAllButton = JButton("Deselect All").apply {
            addActionListener {
                tableModel.selectAll(false)
            }
        }
        buttonsPanel.add(selectAllButton)
        buttonsPanel.add(deselectAllButton)
        panel.add(buttonsPanel, BorderLayout.SOUTH)

        return panel
    }

    /**
     * Returns the list of ignored files with updated selection states
     */
    fun getSelectedFiles(): List<IgnoredFileInfo> {
        return tableModel.getFiles()
    }

    /**
     * Table model for displaying ignored files with selection checkboxes
     */
    private class IgnoredFilesTableModel(
        private val files: MutableList<IgnoredFileInfo>
    ) : AbstractTableModel() {

        private val columnNames = arrayOf("Select", "File Path", "Type", "Size")

        override fun getRowCount(): Int = files.size

        override fun getColumnCount(): Int = columnNames.size

        override fun getColumnName(column: Int): String = columnNames[column]

        override fun getColumnClass(columnIndex: Int): Class<*> {
            return when (columnIndex) {
                0 -> java.lang.Boolean::class.java
                else -> String::class.java
            }
        }

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val file = files[rowIndex]
            return when (columnIndex) {
                0 -> file.selected
                1 -> file.relativePath
                2 -> if (file.type == IgnoredFileInfo.FileType.DIRECTORY) "Directory" else "File"
                3 -> file.displaySize()
                else -> ""
            }
        }

        override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean {
            return columnIndex == 0
        }

        override fun setValueAt(aValue: Any?, rowIndex: Int, columnIndex: Int) {
            if (columnIndex == 0 && aValue is Boolean) {
                files[rowIndex] = files[rowIndex].copy(selected = aValue)
                fireTableCellUpdated(rowIndex, columnIndex)
            }
        }

        fun selectAll(selected: Boolean) {
            files.forEachIndexed { index, file ->
                files[index] = file.copy(selected = selected)
            }
            fireTableDataChanged()
        }

        fun getFiles(): List<IgnoredFileInfo> = files.toList()
    }
}
