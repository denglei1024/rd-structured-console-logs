package com.github.denglei1024.structconsolelog.toolwindow

import com.github.denglei1024.structconsolelog.model.LogLevel
import com.github.denglei1024.structconsolelog.model.LogStream
import com.github.denglei1024.structconsolelog.model.SessionStatus
import com.github.denglei1024.structconsolelog.model.StructuredLogEntry
import com.github.denglei1024.structconsolelog.model.StructuredLogSessionSnapshot
import com.github.denglei1024.structconsolelog.parser.SqlLogDetails
import com.github.denglei1024.structconsolelog.parser.StructuredLogParser
import com.github.denglei1024.structconsolelog.services.StructuredLogProjectService
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.service
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.JBColor
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import java.awt.datatransfer.StringSelection
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.DefaultListModel
import javax.swing.JComboBox
import javax.swing.JCheckBoxMenuItem
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.UIManager
import javax.swing.event.DocumentEvent
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableColumn

class StructConsoleLogPanel(project: Project) : JPanel(BorderLayout()), Disposable {
    private val parser = StructuredLogParser()
    private val service = project.service<StructuredLogProjectService>()
    private val sessionsModel = DefaultListModel<StructuredLogSessionSnapshot>()
    private val sessionsList = JBList(sessionsModel)
    private val tableModel = StructuredLogTableModel()
    private val table = JBTable(tableModel)
    private val searchField = SearchTextField()
    private val streamFilter = JComboBox(
        arrayOf(
            StreamFilterOption("All streams", null),
            StreamFilterOption("Stdout", LogStream.STDOUT),
            StreamFilterOption("Stderr", LogStream.STDERR),
            StreamFilterOption("System", LogStream.SYSTEM)
        )
    )
    private val traceCheck = createFilterCheckBox("TRACE")
    private val debugCheck = createFilterCheckBox("DEBUG")
    private val infoCheck = createFilterCheckBox("INFO")
    private val warnCheck = createFilterCheckBox("WARN")
    private val errorCheck = createFilterCheckBox("ERROR/FATAL")
    private val unknownCheck = createFilterCheckBox("UNKNOWN")
    private val summaryLabel = JBLabel("No Run/Debug output captured yet.")
    private val sessionsLabel = JBLabel("Sessions")
    private val entriesLabel = JBLabel("Entries")
    private val detailsTitleLabel = JBLabel("Details")
    private val detailsMetaLabel = JBLabel("Select a log entry to inspect it.")
    private val copyRawLink = ActionLink("Copy raw") { copyCurrentRaw() }
    private val copyExecutableSqlLink = ActionLink("Copy executable SQL") { copyCurrentExecutableSql() }
    private val columnsLink = ActionLink("Columns") { showColumnsPopup() }
    private val detailsArea = JBTextArea()
    private val tableColumnsByModelIndex = linkedMapOf<Int, TableColumn>()
    private val visibleTableColumnIndexes = ENTRY_COLUMNS.indices.toMutableSet()
    private var selectedSessionId: String? = null
    private var currentVisibleEntries: List<StructuredLogEntry> = emptyList()
    private var currentSelectedEntry: StructuredLogEntry? = null
    private var currentSqlLogDetails: SqlLogDetails? = null

    init {
        border = JBUI.Borders.empty()

        add(buildHeader(), BorderLayout.NORTH)
        add(buildContent(), BorderLayout.CENTER)

        configureSessionsList()
        configureTable()
        configureDetailsArea()
        installListeners()

        reloadSessions()
    }

    private fun buildHeader(): JPanel {
        val header = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            border = JBUI.Borders.compound(
                JBUI.Borders.empty(8, 12),
                JBUI.Borders.customLineBottom(UIUtil.getBoundsColor())
            )
        }

        val topRow = JPanel(BorderLayout()).apply {
            isOpaque = false
        }

        val titlePanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            add(JBLabel("Log Explorer").apply {
                font = font.deriveFont(font.size2D + 1f)
            })
            add(JBLabel("  ").apply { isOpaque = false })
            add(summaryLabel.apply {
                foreground = UIUtil.getContextHelpForeground()
            })
        }

        val actionsPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0)).apply {
            isOpaque = false
            add(ActionLink("Reset filters") { resetFilters() })
            add(ActionLink("Clear session") { selectedSessionId?.let(service::clearSession) })
        }

        val filtersRow = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
            isOpaque = false
            border = JBUI.Borders.emptyTop(8)
            add(searchField)
            add(streamFilter)
            add(traceCheck)
            add(debugCheck)
            add(infoCheck)
            add(warnCheck)
            add(errorCheck)
            add(unknownCheck)
        }

        searchField.preferredSize = JBUI.size(260, 28)
        streamFilter.preferredSize = JBUI.size(130, 28)

        topRow.add(titlePanel, BorderLayout.WEST)
        topRow.add(actionsPanel, BorderLayout.EAST)

        header.add(topRow, BorderLayout.NORTH)
        header.add(filtersRow, BorderLayout.CENTER)
        return header
    }

    private fun buildContent(): JPanel {
        val sessionsPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(8, 12, 8, 8)
            add(buildSectionHeader(sessionsLabel), BorderLayout.NORTH)
            add(JBScrollPane(sessionsList).apply {
                border = JBUI.Borders.customLine(UIUtil.getBoundsColor(), 1)
            }, BorderLayout.CENTER)
        }

        val entriesPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(8, 8, 8, 12)
            add(buildSectionHeader(entriesLabel, columnsLink), BorderLayout.NORTH)
            add(JBScrollPane(table).apply {
                border = JBUI.Borders.customLine(UIUtil.getBoundsColor(), 1)
            }, BorderLayout.CENTER)
        }

        val detailsPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(8, 8, 8, 12)
            add(buildDetailsHeader(), BorderLayout.NORTH)
            add(JBScrollPane(detailsArea).apply {
                border = JBUI.Borders.customLine(UIUtil.getBoundsColor(), 1)
            }, BorderLayout.CENTER)
        }

        val rightSplitter = OnePixelSplitter(false, 0.66f).apply {
            firstComponent = entriesPanel
            secondComponent = detailsPanel
        }

        val contentSplitter = OnePixelSplitter(true, 0.24f).apply {
            firstComponent = sessionsPanel
            secondComponent = rightSplitter
        }

        return JPanel(BorderLayout()).apply {
            add(contentSplitter, BorderLayout.CENTER)
        }
    }

    private fun buildSectionHeader(label: JBLabel, action: Component? = null): JPanel {
        return JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.emptyBottom(6)
            label.foreground = UIUtil.getLabelInfoForeground()
            add(label, BorderLayout.WEST)
            action?.let { add(it, BorderLayout.EAST) }
        }
    }

    private fun buildDetailsHeader(): JPanel {
        val topRow = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(detailsTitleLabel, BorderLayout.WEST)
            add(JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0)).apply {
                isOpaque = false
                add(copyRawLink)
                add(copyExecutableSqlLink)
            }, BorderLayout.EAST)
        }

        return JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(0, 0, 6, 0)
            add(topRow, BorderLayout.NORTH)
            add(detailsMetaLabel.apply {
                foreground = UIUtil.getContextHelpForeground()
            }, BorderLayout.CENTER)
        }
    }

    private fun configureSessionsList() {
        sessionsList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        sessionsList.emptyText.text = "No sessions yet"
        sessionsList.cellRenderer = SessionRenderer()
    }

    private fun configureTable() {
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        table.autoCreateRowSorter = true
        table.fillsViewportHeight = true
        table.rowHeight = JBUI.scale(24)
        table.setShowGrid(false)
        table.intercellSpacing = JBUI.emptySize()
        ENTRY_COLUMNS.forEach { column ->
            table.columnModel.getColumn(column.modelIndex).preferredWidth = JBUI.scale(column.preferredWidth)
        }
        table.columnModel.getColumn(0).cellRenderer = SecondaryTextRenderer()
        table.columnModel.getColumn(1).cellRenderer = LevelRenderer()
        table.columnModel.getColumn(2).cellRenderer = SecondaryTextRenderer()
        table.columnModel.getColumn(3).cellRenderer = SecondaryTextRenderer()
        ENTRY_COLUMNS.indices.forEach { modelIndex ->
            tableColumnsByModelIndex[modelIndex] = table.columnModel.getColumn(modelIndex)
        }
        table.emptyText.text = "No log entries for the current session"
    }

    private fun configureDetailsArea() {
        detailsArea.isEditable = false
        detailsArea.lineWrap = true
        detailsArea.wrapStyleWord = true
        detailsArea.margin = JBUI.insets(10)
        detailsArea.font = UIManager.getFont("TextArea.font") ?: detailsArea.font
        detailsArea.emptyText.text = "Select a log entry to inspect it."
    }

    private fun installListeners() {
        sessionsList.addListSelectionListener {
            if (!it.valueIsAdjusting) {
                selectedSessionId = sessionsList.selectedValue?.id
                applyFilters()
            }
        }

        table.selectionModel.addListSelectionListener {
            if (!it.valueIsAdjusting) {
                updateDetails()
            }
        }

        searchField.textEditor.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                applyFilters()
            }
        })

        listOf(traceCheck, debugCheck, infoCheck, warnCheck, errorCheck, unknownCheck).forEach { checkbox ->
            checkbox.addActionListener { applyFilters() }
        }
        streamFilter.addActionListener { applyFilters() }

        service.addListener(object : StructuredLogProjectService.Listener {
            override fun logsUpdated() {
                reloadSessions()
            }
        }, this)
    }

    private fun reloadSessions() {
        val snapshot = service.getSnapshot()

        sessionsModel.removeAllElements()
        snapshot.forEach(sessionsModel::addElement)
        sessionsLabel.text = "Sessions (${snapshot.size})"

        if (snapshot.isEmpty()) {
            selectedSessionId = null
            tableModel.setEntries(emptyList())
            summaryLabel.text = "No Run/Debug output captured yet."
            entriesLabel.text = "Entries"
            detailsTitleLabel.text = "Details"
            detailsMetaLabel.text = "Start a run configuration to see structured logs here."
            detailsArea.text = ""
            updateDetailActions(null, null)
            return
        }

        val targetSession = snapshot.firstOrNull { it.id == selectedSessionId } ?: snapshot.last()
        selectedSessionId = targetSession.id
        sessionsList.setSelectedValue(targetSession, true)
        applyFilters()
    }

    private fun applyFilters() {
        val session = currentSession()
        if (session == null) {
            tableModel.setEntries(emptyList())
            summaryLabel.text = "No session selected."
            entriesLabel.text = "Entries"
            detailsMetaLabel.text = "Select a session to inspect its logs."
            detailsArea.text = ""
            updateDetailActions(null, null)
            return
        }

        val query = searchField.text.trim()
        val stream = (streamFilter.selectedItem as? StreamFilterOption)?.stream
        val allowedLevels = buildSet {
            if (traceCheck.isSelected) add(LogLevel.TRACE)
            if (debugCheck.isSelected) add(LogLevel.DEBUG)
            if (infoCheck.isSelected) add(LogLevel.INFO)
            if (warnCheck.isSelected) add(LogLevel.WARN)
            if (errorCheck.isSelected) {
                add(LogLevel.ERROR)
                add(LogLevel.FATAL)
            }
            if (unknownCheck.isSelected) add(LogLevel.UNKNOWN)
        }

        currentVisibleEntries = session.entries.filter { entry ->
            (stream == null || entry.stream == stream) &&
                entry.level in allowedLevels &&
                (query.isBlank() || matchesQuery(entry, query))
        }

        tableModel.setEntries(currentVisibleEntries)
        summaryLabel.text = buildSummary(session, currentVisibleEntries)
        entriesLabel.text = "Entries (${currentVisibleEntries.size})"

        if (currentVisibleEntries.isEmpty()) {
            detailsTitleLabel.text = "Details"
            detailsMetaLabel.text = "No log entries match the current filters."
            detailsArea.text = ""
            table.emptyText.text = "No log entries match the current filters"
            updateDetailActions(null, null)
        } else {
            table.emptyText.text = "No log entries for the current session"
            if (table.selectedRow < 0 || table.selectedRow >= currentVisibleEntries.size) {
                table.setRowSelectionInterval(0, 0)
            }
            updateDetails()
        }
    }

    private fun updateDetails() {
        val selectedRow = table.selectedRow
        if (selectedRow < 0 || selectedRow >= currentVisibleEntries.size) {
            detailsTitleLabel.text = "Details"
            detailsMetaLabel.text = "Select a log entry to inspect it."
            detailsArea.text = ""
            updateDetailActions(null, null)
            return
        }

        val modelRow = table.convertRowIndexToModel(selectedRow)
        val entry = tableModel.entryAt(modelRow) ?: return
        val sqlLogDetails = parser.extractSqlLog(entry.rawText)
        detailsTitleLabel.text = "${entry.level} · ${entry.stream.label}"
        detailsMetaLabel.text = buildDetailsMeta(entry, sqlLogDetails)
        detailsArea.text = buildDetailsBody(entry, sqlLogDetails)
        updateDetailActions(entry, sqlLogDetails)
        detailsArea.caretPosition = 0
    }

    private fun currentSession(): StructuredLogSessionSnapshot? {
        return (0 until sessionsModel.size)
            .asSequence()
            .map(sessionsModel::getElementAt)
            .firstOrNull { it.id == selectedSessionId }
    }

    private fun buildSummary(session: StructuredLogSessionSnapshot, visibleEntries: List<StructuredLogEntry>): String {
        val counts = visibleEntries.groupingBy { it.level }.eachCount()
        val errorCount = (counts[LogLevel.ERROR] ?: 0) + (counts[LogLevel.FATAL] ?: 0)
        return buildString {
            append(session.displayName)
            append(" · ")
            append(visibleEntries.size)
            append(" visible of ")
            append(session.totalEntries)
            append(" · ")
            append(counts[LogLevel.INFO] ?: 0)
            append(" info · ")
            append(counts[LogLevel.WARN] ?: 0)
            append(" warnings · ")
            append(errorCount)
            append(" errors")
        }
    }

    private fun buildDetailsMeta(entry: StructuredLogEntry, sqlLogDetails: SqlLogDetails?): String {
        return buildString {
            append(entry.parsedTimestamp ?: TIME_FORMATTER.format(entry.capturedAt.atZone(ZoneId.systemDefault())))
            append(" · ")
            append(entry.logger ?: "No logger")
            if (entry.fields.isNotEmpty()) {
                append(" · ")
                append(entry.fields.size)
                append(" fields")
            }
            if (sqlLogDetails != null) {
                append(" · SQL")
            }
        }
    }

    private fun buildDetailsBody(entry: StructuredLogEntry, sqlLogDetails: SqlLogDetails?): String {
        return buildString {
            appendLine("Message")
            appendLine(entry.message)
            appendLine()
            appendLine("Captured")
            appendLine(TIME_FORMATTER.format(entry.capturedAt.atZone(ZoneId.systemDefault())))
            appendLine()
            appendLine("Timestamp")
            appendLine(entry.parsedTimestamp ?: "-")
            appendLine()
            appendLine("Logger")
            appendLine(entry.logger ?: "-")
            appendLine()
            if (entry.fields.isNotEmpty()) {
                appendLine("Fields")
                entry.fields.forEach { (key, value) ->
                    appendLine("$key = $value")
                }
                appendLine()
            }
            if (sqlLogDetails != null) {
                appendLine("Executable SQL")
                appendLine(sqlLogDetails.executableSql)
                appendLine()
            }
            appendLine("Raw")
            append(entry.rawText)
        }
    }

    private fun updateDetailActions(entry: StructuredLogEntry?, sqlLogDetails: SqlLogDetails?) {
        currentSelectedEntry = entry
        currentSqlLogDetails = sqlLogDetails
        copyRawLink.isVisible = entry != null
        copyExecutableSqlLink.isVisible = sqlLogDetails != null
    }

    private fun copyCurrentRaw() {
        val rawText = currentSelectedEntry?.rawText ?: return
        CopyPasteManager.getInstance().setContents(StringSelection(rawText))
    }

    private fun copyCurrentExecutableSql() {
        val executableSql = currentSqlLogDetails?.executableSql ?: return
        CopyPasteManager.getInstance().setContents(StringSelection(executableSql))
    }

    private fun matchesQuery(entry: StructuredLogEntry, query: String): Boolean {
        val lowerQuery = query.lowercase()
        return entry.message.lowercase().contains(lowerQuery) ||
            entry.rawText.lowercase().contains(lowerQuery) ||
            (entry.logger?.lowercase()?.contains(lowerQuery) == true) ||
            entry.fields.any { (key, value) ->
                key.lowercase().contains(lowerQuery) || value.lowercase().contains(lowerQuery)
            }
    }

    private fun resetFilters() {
        searchField.text = ""
        streamFilter.selectedIndex = 0
        traceCheck.isSelected = true
        debugCheck.isSelected = true
        infoCheck.isSelected = true
        warnCheck.isSelected = true
        errorCheck.isSelected = true
        unknownCheck.isSelected = true
        applyFilters()
    }

    private fun showColumnsPopup() {
        val popup = JPopupMenu()
        ENTRY_COLUMNS.forEach { column ->
            val item = JCheckBoxMenuItem(column.title, column.modelIndex in visibleTableColumnIndexes)
            item.addActionListener {
                if (item.isSelected) {
                    visibleTableColumnIndexes += column.modelIndex
                } else if (visibleTableColumnIndexes.size > 1) {
                    visibleTableColumnIndexes -= column.modelIndex
                } else {
                    item.isSelected = true
                    return@addActionListener
                }
                updateVisibleTableColumns()
            }
            popup.add(item)
        }
        popup.show(columnsLink, 0, columnsLink.height)
    }

    private fun updateVisibleTableColumns() {
        val columnModel = table.columnModel
        tableColumnsByModelIndex.values
            .filter { column -> column.isAttachedTo(columnModel) }
            .forEach(columnModel::removeColumn)

        ENTRY_COLUMNS
            .filter { column -> column.modelIndex in visibleTableColumnIndexes }
            .forEach { column -> columnModel.addColumn(tableColumnsByModelIndex.getValue(column.modelIndex)) }
    }

    private fun TableColumn.isAttachedTo(columnModel: javax.swing.table.TableColumnModel): Boolean {
        return (0 until columnModel.columnCount).any { index -> columnModel.getColumn(index) === this }
    }

    private fun createFilterCheckBox(text: String): JBCheckBox {
        return JBCheckBox(text, true).apply {
            isOpaque = false
            border = JBUI.Borders.empty(2, 0)
        }
    }

    override fun dispose() = Unit

    private data class StreamFilterOption(val label: String, val stream: LogStream?) {
        override fun toString(): String = label
    }

    private data class EntryColumn(
        val modelIndex: Int,
        val title: String,
        val preferredWidth: Int
    )

    private class StructuredLogTableModel : AbstractTableModel() {
        private var entries: List<StructuredLogEntry> = emptyList()

        override fun getRowCount(): Int = entries.size

        override fun getColumnCount(): Int = ENTRY_COLUMNS.size

        override fun getColumnName(column: Int): String = ENTRY_COLUMNS[column].title

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val entry = entries[rowIndex]
            return when (columnIndex) {
                0 -> entry.parsedTimestamp ?: TIME_FORMATTER.format(entry.capturedAt.atZone(ZoneId.systemDefault()))
                1 -> entry.level
                2 -> entry.logger ?: ""
                3 -> entry.stream.label
                4 -> entry.message.lineSequence().firstOrNull().orEmpty()
                else -> ""
            }
        }

        fun setEntries(entries: List<StructuredLogEntry>) {
            this.entries = entries
            fireTableDataChanged()
        }

        fun entryAt(rowIndex: Int): StructuredLogEntry? = entries.getOrNull(rowIndex)
    }

    private class SessionRenderer : ColoredListCellRenderer<StructuredLogSessionSnapshot>() {
        override fun customizeCellRenderer(
            list: JList<out StructuredLogSessionSnapshot>,
            value: StructuredLogSessionSnapshot?,
            index: Int,
            selected: Boolean,
            hasFocus: Boolean
        ) {
            if (value == null) {
                return
            }

            border = JBUI.Borders.empty(6, 8)
            append(value.displayName, SimpleTextAttributes.REGULAR_ATTRIBUTES)
            append("  ", SimpleTextAttributes.REGULAR_ATTRIBUTES)
            append(
                "${value.totalEntries} entries",
                SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES
            )
            append("  ", SimpleTextAttributes.REGULAR_ATTRIBUTES)
            append(
                if (value.status == SessionStatus.RUNNING) "Running" else "Finished",
                SimpleTextAttributes(
                    SimpleTextAttributes.STYLE_PLAIN,
                    if (value.status == SessionStatus.RUNNING) INFO_COLOR else UIUtil.getContextHelpForeground()
                )
            )
        }
    }

    private open class SecondaryTextRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable,
            value: Any?,
            isSelected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int
        ): Component {
            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
            border = JBUI.Borders.empty(0, 8)
            if (!isSelected) {
                foreground = UIUtil.getContextHelpForeground()
            }
            return this
        }
    }

    private class LevelRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable,
            value: Any?,
            isSelected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int
        ): Component {
            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
            val level = value as? LogLevel ?: LogLevel.UNKNOWN
            text = level.name
            border = JBUI.Borders.empty(0, 8)
            horizontalAlignment = LEFT
            font = font.deriveFont(font.style or java.awt.Font.BOLD)
            if (!isSelected) {
                foreground = when (level) {
                    LogLevel.TRACE -> UIUtil.getContextHelpForeground()
                    LogLevel.DEBUG -> DEBUG_COLOR
                    LogLevel.INFO -> INFO_COLOR
                    LogLevel.WARN -> WARN_COLOR
                    LogLevel.ERROR, LogLevel.FATAL -> ERROR_COLOR
                    LogLevel.UNKNOWN -> UIUtil.getContextHelpForeground()
                }
            }
            return this
        }
    }

    companion object {
        private val TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
        private val DEBUG_COLOR = JBColor(0x3574F0, 0x548AF7)
        private val INFO_COLOR = JBColor(0x2E7D32, 0x6FB96F)
        private val WARN_COLOR = JBColor(0xB76E00, 0xD9A343)
        private val ERROR_COLOR = UIUtil.getErrorForeground()
        private val ENTRY_COLUMNS = listOf(
            EntryColumn(0, "Time", 95),
            EntryColumn(1, "Level", 80),
            EntryColumn(2, "Logger", 180),
            EntryColumn(3, "Stream", 80),
            EntryColumn(4, "Message", 900)
        )
    }
}
