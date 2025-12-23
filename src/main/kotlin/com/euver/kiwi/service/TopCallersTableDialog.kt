package com.euver.kiwi.service

import com.euver.kiwi.application.ExpandStatementUseCase
import com.euver.kiwi.domain.model.MethodInfo
import com.euver.kiwi.domain.model.TopCallerWithStatement
import com.euver.kiwi.parser.MyBatisXmlParser
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.pom.Navigatable
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiMethod
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import org.apache.poi.ss.usermodel.FillPatternType
import org.apache.poi.ss.usermodel.IndexedColors
import org.apache.poi.ss.util.CellRangeAddress
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Desktop
import java.awt.datatransfer.StringSelection
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicReference
import javax.swing.AbstractAction
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JTable
import javax.swing.KeyStroke
import javax.swing.ListSelectionModel
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.DefaultTableModel

/**
 * 顶层调用者表格展示对话框
 * 支持两种模式：
 * 1. 普通模式：只展示顶层调用者信息
 * 2. SQL 片段模式：展示顶层调用者信息 + StatementID 列，支持单元格合并
 */
class TopCallersTableDialog private constructor(
    private val project: Project,
    private val sourceMethodName: String,
    private val topCallers: List<MethodInfo>,
    private val topCallersWithStatements: List<TopCallerWithStatement>?,
    private val isSqlFragmentMode: Boolean
) : DialogWrapper(project) {

    private val logger = thisLogger()
    private lateinit var tableModel: DefaultTableModel
    private val baseColumnNames = arrayOf(
        "序号",
        "类型",
        "请求路径",
        "方法",
        "类功能注释",
        "方法功能注释",
        "包路径"
    )
    // StatementID 列索引（在 SQL 片段模式下为最后一列，索引为7）
    private val statementIdColumnIndex = 7
    // 记录单元格合并信息：行索引 -> (startRow, rowSpan)
    private val mergeInfo = mutableMapOf<Int, Pair<Int, Int>>()
    // 记录每行对应的数据（用于导航和右键菜单）
    private data class RowData(
        val methodInfo: MethodInfo,
        val statementId: String? = null
    )
    private val rowDataList = mutableListOf<RowData>()

    companion object {
        /**
         * 创建普通模式的对话框
         */
        fun create(
            project: Project,
            sourceMethodName: String,
            topCallers: List<MethodInfo>
        ): TopCallersTableDialog {
            return TopCallersTableDialog(
                project = project,
                sourceMethodName = sourceMethodName,
                topCallers = topCallers,
                topCallersWithStatements = null,
                isSqlFragmentMode = false
            )
        }

        /**
         * 创建 SQL 片段模式的对话框
         */
        fun createWithStatements(
            project: Project,
            sourceMethodName: String,
            topCallersWithStatements: List<TopCallerWithStatement>
        ): TopCallersTableDialog {
            return TopCallersTableDialog(
                project = project,
                sourceMethodName = sourceMethodName,
                topCallers = emptyList(),
                topCallersWithStatements = topCallersWithStatements,
                isSqlFragmentMode = true
            )
        }
    }

    init {
        title = "顶层调用者列表"
        isModal = false
        init()
    }
    
    override fun createActions(): Array<Action> {
        val exportAction = object : DialogWrapperAction("EXPORT") {
            override fun doAction(e: ActionEvent?) {
                exportToExcel()
            }
        }
        return arrayOf(okAction, exportAction)
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout())

        val columnNames = if (isSqlFragmentMode) {
            baseColumnNames + arrayOf("StatementID")
        } else {
            baseColumnNames
        }

        tableModel = object : DefaultTableModel(columnNames, 0) {
            override fun isCellEditable(row: Int, column: Int): Boolean = false
        }

        if (isSqlFragmentMode && topCallersWithStatements != null) {
            populateTableWithStatements(topCallersWithStatements)
        } else {
            populateTableNormal(topCallers)
        }

        val table = JBTable(tableModel)
        table.autoCreateRowSorter = !isSqlFragmentMode  // SQL 片段模式下禁用排序（因为有单元格合并）
        
        // 禁用自动调整，使用手动计算的列宽
        table.autoResizeMode = javax.swing.JTable.AUTO_RESIZE_OFF
        
        // 根据内容自适应列宽，最大宽度限制为80个字符
        adjustColumnWidths(table, maxCharWidth = 80)
        
        // 启用单元格选择模式
        table.cellSelectionEnabled = true
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION)

        // 如果是 SQL 片段模式，设置单元格合并渲染器
        if (isSqlFragmentMode) {
            setupMergeCellRenderer(table)
        }

        // 设置右键菜单
        setupContextMenu(table)

        panel.add(JBScrollPane(table), BorderLayout.CENTER)
        return panel
    }

    /**
     * 普通模式填充表格
     */
    private fun populateTableNormal(topCallers: List<MethodInfo>) {
        topCallers.forEachIndexed { index, methodInfo ->
            val type = if (methodInfo.isExternalInterface()) "API" else "Normal"
            val methodDisplay = "${methodInfo.simpleClassName}.${methodInfo.methodSignature}"

            tableModel.addRow(
                arrayOf<Any>(
                    index + 1,
                    type,
                    methodInfo.requestPath,
                    methodDisplay,
                    methodInfo.classComment,
                    methodInfo.functionComment,
                    methodInfo.packageName
                )
            )
            rowDataList.add(RowData(methodInfo))
        }
    }

    /**
     * SQL 片段模式填充表格（带单元格合并）
     * 处理多对多关系：同一个顶层调用者可能对应多个 Statement
     */
    private fun populateTableWithStatements(data: List<TopCallerWithStatement>) {
        // 按顶层调用者分组，保留 Statement 关联
        val groupedByMethod = data.groupBy { it.methodInfo.qualifiedName }
        
        var rowIndex = 0
        var seqNumber = 0
        
        for ((_, group) in groupedByMethod) {
            seqNumber++
            val methodInfo = group.first().methodInfo
            val statementIds = group.map { it.statementId }.distinct()
            val rowSpan = statementIds.size
            val startRow = rowIndex
            
            for ((i, statementId) in statementIds.withIndex()) {
                val type = if (methodInfo.isExternalInterface()) "API" else "Normal"
                val methodDisplay = "${methodInfo.simpleClassName}.${methodInfo.methodSignature}"

                tableModel.addRow(
                    arrayOf<Any>(
                        if (i == 0) seqNumber else "",
                        if (i == 0) type else "",
                        if (i == 0) methodInfo.requestPath else "",
                        if (i == 0) methodDisplay else "",
                        if (i == 0) methodInfo.classComment else "",
                        if (i == 0) methodInfo.functionComment else "",
                        if (i == 0) methodInfo.packageName else "",
                        statementId
                    )
                )
                rowDataList.add(RowData(methodInfo, statementId))
                mergeInfo[rowIndex] = Pair(startRow, rowSpan)
                rowIndex++
            }
        }
    }

    /**
     * 设置单元格合并渲染器
     * 注意：Swing JTable 不原生支持单元格合并，此处通过渲染器实现"伪合并"效果
     * - 非首行单元格显示为空，但单元格边框仍存在
     */
    private fun setupMergeCellRenderer(table: JBTable) {
        // 记录需要合并的行数统计（用于调试）
        val mergeableRows = mergeInfo.entries.filter { it.value.second > 1 }
        if (mergeableRows.isNotEmpty()) {
            logger.info("需要合并的行: ${mergeableRows.size} 组, 详情: ${mergeableRows.take(5)}")
        }
        
        val mergeCellRenderer = object : DefaultTableCellRenderer() {
            override fun getTableCellRendererComponent(
                table: JTable?,
                value: Any?,
                isSelected: Boolean,
                hasFocus: Boolean,
                row: Int,
                column: Int
            ): Component {
                // 将视图行索引转换为模型行索引（处理排序情况）
                val modelRow = table?.convertRowIndexToModel(row) ?: row
                
                val component = super.getTableCellRendererComponent(
                    table, value, isSelected, hasFocus, row, column
                )
                
                // StatementID 列（最后一列）不合并，正常显示
                if (column == statementIdColumnIndex) {
                    return component
                }
                
                // 其他列处理合并显示
                val mergeData = mergeInfo[modelRow]
                if (mergeData != null) {
                    val (startRow, rowSpan) = mergeData
                    if (modelRow != startRow && rowSpan > 1) {
                        // 非首行，返回透明的空标签（保持背景色一致）
                        val emptyLabel = JLabel()
                        emptyLabel.isOpaque = true
                        emptyLabel.background = if (isSelected) {
                            table?.selectionBackground
                        } else {
                            table?.background
                        }
                        return emptyLabel
                    }
                }
                
                return component
            }
        }
        
        // 应用到除 StatementID 列（最后一列）外的所有列
        for (i in 0 until statementIdColumnIndex) {
            table.columnModel.getColumn(i).cellRenderer = mergeCellRenderer
        }
    }

    /**
     * 设置右键菜单
     */
    private fun setupContextMenu(table: JBTable) {
        val popupMenu = JPopupMenu()
        val navigateItem = JMenuItem("跳转到源码")
        val copyItem = JMenuItem("复制")
        val copyExpandedItem = JMenuItem("Copy Expanded Statement")
        
        popupMenu.add(navigateItem)
        popupMenu.add(copyItem)
        if (isSqlFragmentMode) {
            popupMenu.add(copyExpandedItem)
        }

        navigateItem.addActionListener {
            val selectedRow = table.selectedRow
            val selectedCol = table.selectedColumn
            if (selectedRow >= 0) {
                if (isSqlFragmentMode && selectedCol == statementIdColumnIndex) {
                    // StatementID 列：跳转到 XML
                    val rowData = rowDataList.getOrNull(selectedRow)
                    if (rowData?.statementId != null) {
                        navigateToStatement(rowData.statementId)
                    }
                } else {
                    // 其他列：跳转到 Java 源码
                    val rowData = rowDataList.getOrNull(selectedRow)
                    if (rowData != null) {
                        navigateToMethod(rowData.methodInfo)
                    }
                }
            }
        }
        
        copyItem.addActionListener {
            copySelectedCells(table)
        }
        
        copyExpandedItem.addActionListener {
            val selectedRow = table.selectedRow
            if (selectedRow >= 0) {
                val rowData = rowDataList.getOrNull(selectedRow)
                if (rowData?.statementId != null) {
                    copyExpandedStatement(rowData.statementId)
                }
            }
        }
        
        // 注册 Ctrl+C / Command+C 快捷键
        val copyKeyStroke = KeyStroke.getKeyStroke(KeyEvent.VK_C, 
            java.awt.Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx)
        table.getInputMap(JComponent.WHEN_FOCUSED).put(copyKeyStroke, "copy")
        table.actionMap.put("copy", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) {
                copySelectedCells(table)
            }
        })

        table.addMouseListener(object : MouseAdapter() {
            private fun handlePopup(e: MouseEvent) {
                if (e.isPopupTrigger) {
                    val row = table.rowAtPoint(e.point)
                    val col = table.columnAtPoint(e.point)
                    if (row >= 0 && col >= 0) {
                        // 如果点击的单元格不在当前选择范围内，则选中该单元格
                        if (!table.isCellSelected(row, col)) {
                            table.setRowSelectionInterval(row, row)
                            table.setColumnSelectionInterval(col, col)
                        }
                        
                        // 根据列位置动态调整菜单项
                        if (isSqlFragmentMode) {
                            if (col == statementIdColumnIndex) {
                                navigateItem.text = "跳转到 XML"
                                copyExpandedItem.isVisible = true
                            } else {
                                navigateItem.text = "跳转到源码"
                                copyExpandedItem.isVisible = false
                            }
                        }
                        
                        popupMenu.show(e.component, e.x, e.y)
                    }
                }
            }

            override fun mousePressed(e: MouseEvent) = handlePopup(e)
            override fun mouseReleased(e: MouseEvent) = handlePopup(e)
        })
    }

    override fun createNorthPanel(): JComponent? {
        val panel = JPanel(BorderLayout())
        panel.add(JLabel("源位置：$sourceMethodName"), BorderLayout.WEST)
        return panel
    }

    override fun getDimensionServiceKey(): String? {
        return "Kiwi.TopCallers.TableDialog"
    }

    private fun navigateToMethod(methodInfo: MethodInfo) {
        val app = ApplicationManager.getApplication()
        val psiMethodRef = AtomicReference<PsiMethod?>()

        app.runReadAction {
            psiMethodRef.set(findPsiMethod(methodInfo))
        }

        val psiMethod = psiMethodRef.get() ?: return

        app.invokeLater {
            (psiMethod as? Navigatable)?.navigate(true)
        }
    }

    private fun findPsiMethod(methodInfo: MethodInfo): PsiMethod? {
        val qualifiedName = methodInfo.qualifiedName
        val lastDot = qualifiedName.lastIndexOf('.')
        if (lastDot <= 0 || lastDot == qualifiedName.length - 1) {
            return null
        }

        val classFqn = qualifiedName.substring(0, lastDot)
        val methodName = extractMethodName(methodInfo.methodSignature)
        val paramTypes = extractParamTypes(methodInfo.methodSignature)

        val psiFacade = JavaPsiFacade.getInstance(project)
        val scope = GlobalSearchScope.projectScope(project)
        val psiClass = psiFacade.findClass(classFqn, scope) ?: return null

        val candidates = psiClass.findMethodsByName(methodName, false)
        if (candidates.isEmpty()) {
            return null
        }

        if (paramTypes.isEmpty()) {
            return candidates.firstOrNull()
        }

        for (candidate in candidates) {
            val psiParams = candidate.parameterList.parameters
            if (psiParams.size != paramTypes.size) {
                continue
            }
            var allMatch = true
            for (i in paramTypes.indices) {
                if (psiParams[i].type.presentableText != paramTypes[i]) {
                    allMatch = false
                    break
                }
            }
            if (allMatch) {
                return candidate
            }
        }

        return candidates.firstOrNull()
    }

    private fun extractMethodName(signature: String): String {
        return signature.substringBefore("(").trim()
    }

    private fun extractParamTypes(signature: String): List<String> {
        val paramsPart = signature.substringAfter("(", "").substringBeforeLast(")")
        if (paramsPart.isBlank()) {
            return emptyList()
        }
        return paramsPart.split(",").map { it.trim() }
    }

    /**
     * 跳转到 Statement 对应的 XML 位置
     */
    private fun navigateToStatement(statementFullId: String) {
        val app = ApplicationManager.getApplication()
        
        // 解析 statementFullId，格式为 namespace.statementId
        val lastDot = statementFullId.lastIndexOf('.')
        if (lastDot <= 0) {
            NotificationService(project).showErrorNotification("无效的 Statement ID: $statementFullId")
            return
        }
        
        val namespace = statementFullId.substring(0, lastDot)
        val statementId = statementFullId.substring(lastDot + 1)
        
        app.runReadAction {
            val indexService = MapperIndexService.getInstance(project)
            val xmlFile = indexService.findMapperFileByNamespace(namespace)
            
            if (xmlFile == null) {
                app.invokeLater {
                    NotificationService(project).showErrorNotification("未找到 Mapper XML 文件: $namespace")
                }
                return@runReadAction
            }
            
            // 查找对应的 Statement 标签
            val rootTag = xmlFile.rootTag ?: return@runReadAction
            for (tag in rootTag.subTags) {
                if (tag.name in setOf("select", "insert", "update", "delete") && 
                    tag.getAttributeValue("id") == statementId) {
                    app.invokeLater {
                        (tag as? Navigatable)?.navigate(true)
                    }
                    return@runReadAction
                }
            }
            
            app.invokeLater {
                NotificationService(project).showErrorNotification("未找到 Statement: $statementId")
            }
        }
    }

    /**
     * 复制展开后的 Statement 内容
     */
    private fun copyExpandedStatement(statementFullId: String) {
        val lastDot = statementFullId.lastIndexOf('.')
        if (lastDot <= 0) {
            NotificationService(project).showErrorNotification("无效的 Statement ID: $statementFullId")
            return
        }
        
        val namespace = statementFullId.substring(0, lastDot)
        val statementId = statementFullId.substring(lastDot + 1)
        
        val useCase = ExpandStatementUseCase(project)
        val result = useCase.executeFromMapperMethod(namespace, statementId)
        
        if (result == null) {
            NotificationService(project).showErrorNotification("未找到 Statement: $statementId")
            ConsoleOutputService(project).outputErrorMessage("未找到 Statement: $statementId (Mapper: $namespace)")
            return
        }
        
        // 输出到控制台
        ConsoleOutputService(project).outputToConsole(result)
        
        // 如果存在循环引用，不复制到剪贴板
        if (result.hasCircularReference()) {
            logger.warn("存在循环引用，跳过剪贴板复制")
            NotificationService(project).showAssemblyResultNotification(result)
            return
        }
        
        // 复制到剪贴板
        val copySuccess = ConsoleOutputService(project).copyToClipboard(result.assembledSql)
        if (copySuccess) {
            NotificationService(project).showAssemblyResultNotification(result)
        } else {
            logger.warn("复制到剪贴板失败")
        }
    }
    
    /**
     * 复制选中的单元格内容到系统剪贴板
     * 保持表格的结构化格式，使用 Tab 分隔列，换行符分隔行
     */
    private fun copySelectedCells(table: JBTable) {
        val selectedRows = table.selectedRows
        val selectedColumns = table.selectedColumns
        
        if (selectedRows.isEmpty() || selectedColumns.isEmpty()) {
            return
        }
        
        val sb = StringBuilder()
        
        for (row in selectedRows) {
            val rowData = mutableListOf<String>()
            for (col in selectedColumns) {
                val value = table.getValueAt(row, col)?.toString() ?: ""
                rowData.add(value)
            }
            sb.append(rowData.joinToString("\t"))
            sb.append("\n")
        }
        
        val content = sb.toString().trimEnd('\n')
        CopyPasteManager.getInstance().setContents(StringSelection(content))
    }
    
    /**
     * 导出表格内容到 Excel 文件
     */
    private fun exportToExcel() {
        try {
            val outDir = createOutputDirectory()
            if (outDir == null) {
                NotificationService(project).showErrorNotification("创建导出目录失败")
                ConsoleOutputService(project).output("导出失败：无法创建 .Kiwi/out 目录")
                return
            }
            
            val fileName = generateFileName()
            val file = File(outDir, fileName)
            
            XSSFWorkbook().use { workbook ->
                val sheet = workbook.createSheet("顶层调用者")
                
                // 创建表头样式（浅蓝色背景）
                val headerStyle = workbook.createCellStyle().apply {
                    fillForegroundColor = IndexedColors.PALE_BLUE.index
                    fillPattern = FillPatternType.SOLID_FOREGROUND
                    val font = workbook.createFont().apply {
                        bold = true
                    }
                    setFont(font)
                }
                
                // 创建表头行
                val columnNames = if (isSqlFragmentMode) {
                    baseColumnNames + arrayOf("StatementID")
                } else {
                    baseColumnNames
                }
                val headerRow = sheet.createRow(0)
                columnNames.forEachIndexed { index, name ->
                    val cell = headerRow.createCell(index)
                    cell.setCellValue(name)
                    cell.cellStyle = headerStyle
                }
                
                // 填充数据行
                for (rowIndex in 0 until tableModel.rowCount) {
                    val row = sheet.createRow(rowIndex + 1)
                    for (colIndex in 0 until tableModel.columnCount) {
                        val cell = row.createCell(colIndex)
                        val value = tableModel.getValueAt(rowIndex, colIndex)?.toString() ?: ""
                        cell.setCellValue(value)
                    }
                }
                
                // 设置列宽度（自适应，最大 50 个字符宽度）
                val maxCharWidth = 50
                val maxColumnWidth = maxCharWidth * 256  // POI 使用 1/256 字符单位
                for (colIndex in 0 until tableModel.columnCount) {
                    sheet.autoSizeColumn(colIndex)
                    if (sheet.getColumnWidth(colIndex) > maxColumnWidth) {
                        sheet.setColumnWidth(colIndex, maxColumnWidth)
                    }
                }
                
                // SQL 片段模式下儈单元格合并
                if (isSqlFragmentMode) {
                    applyExcelMergeRegions(sheet)
                }
                
                // 写入文件
                FileOutputStream(file).use { fos ->
                    workbook.write(fos)
                }
            }
            
            logger.info("导出成功：${file.absolutePath}")
            ConsoleOutputService(project).output("导出成功：${file.absolutePath}")
            NotificationService(project).showInfoNotification("导出成功")
            
            // 打开文件所在目录
            openDirectory(outDir)
            
        } catch (e: Exception) {
            logger.error("导出 Excel 失败", e)
            ConsoleOutputService(project).output("导出失败：${e.message}")
            NotificationService(project).showErrorNotification("导出失败：${e.message}")
        }
    }
    
    /**
     * 创建输出目录 .Kiwi/out
     */
    private fun createOutputDirectory(): File? {
        val projectBasePath = project.basePath ?: return null
        val outDir = File(projectBasePath, ".Kiwi/out")
        return if (outDir.exists() || outDir.mkdirs()) outDir else null
    }
    
    /**
     * 生成文件名
     * 格式：{StatementID或类名.方法名}_{YYYYMMDD_HHMMSS}.xlsx
     */
    private fun generateFileName(): String {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        val baseName = sanitizeFileName(sourceMethodName)
        return "${baseName}_${timestamp}.xlsx"
    }
    
    /**
     * 清理文件名中的非法字符
     */
    private fun sanitizeFileName(name: String): String {
        // 移除 "SQL Fragment: " 前缀
        val cleanName = name.removePrefix("SQL Fragment: ")
        // 替换文件名中的非法字符
        return cleanName.replace(Regex("[\\\\/:*?\"<>|]"), "_")
    }
    
    /**
     * 打开文件所在目录
     */
    private fun openDirectory(directory: File) {
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(directory)
            }
        } catch (e: Exception) {
            logger.warn("无法打开目录：${e.message}")
        }
    }

    /**
     * 在 Excel 中应用单元格合并区域
     */
    private fun applyExcelMergeRegions(sheet: org.apache.poi.ss.usermodel.Sheet) {
        // 找出所有需要合并的区域
        val processedStartRows = mutableSetOf<Int>()
        
        for ((row, mergeData) in mergeInfo) {
            val (startRow, rowSpan) = mergeData
            
            // 跳过已处理的区域或不需要合并的单行
            if (startRow in processedStartRows || rowSpan <= 1) {
                continue
            }
            processedStartRows.add(startRow)
            
            // 对除 StatementID 列（最后一列）外的所有列进行合并
            // Excel 行索引需要 +1（表头占用第0行）
            for (colIndex in 0 until statementIdColumnIndex) {
                val mergeRegion = CellRangeAddress(
                    startRow + 1,  // 起始行（+1 因为表头）
                    startRow + rowSpan,  // 结束行
                    colIndex,  // 起始列
                    colIndex   // 结束列
                )
                sheet.addMergedRegion(mergeRegion)
            }
        }
    }
    
    /**
     * 根据内容自适应调整列宽
     * @param table 表格对象
     * @param maxCharWidth 最大字符宽度限制
     */
    private fun adjustColumnWidths(table: JBTable, maxCharWidth: Int) {
        val columnModel = table.columnModel
        val fontMetrics = table.getFontMetrics(table.font)
        val charWidth = fontMetrics.charWidth('M') // 使用M字符作为平均字符宽度参考
        val defaultMaxPixelWidth = maxCharWidth * charWidth
        val statementIdMaxPixelWidth = 200 // StatementID 列最大宽度限制为 200 像素
        val padding = 16 // 左右内边距
        
        for (colIndex in 0 until columnModel.columnCount) {
            var maxWidth = 0
            
            // 计算表头宽度
            val headerValue = table.columnModel.getColumn(colIndex).headerValue?.toString() ?: ""
            val headerWidth = fontMetrics.stringWidth(headerValue) + padding
            maxWidth = maxOf(maxWidth, headerWidth)
            
            // 计算每行内容宽度
            for (rowIndex in 0 until table.rowCount) {
                val value = table.getValueAt(rowIndex, colIndex)?.toString() ?: ""
                val cellWidth = fontMetrics.stringWidth(value) + padding
                maxWidth = maxOf(maxWidth, cellWidth)
            }
            
            // 应用最大宽度限制：StatementID 列使用 200 像素限制，其他列使用默认限制
            val maxPixelWidth = if (isSqlFragmentMode && colIndex == statementIdColumnIndex) {
                statementIdMaxPixelWidth
            } else {
                defaultMaxPixelWidth
            }
            val finalWidth = minOf(maxWidth, maxPixelWidth)
            columnModel.getColumn(colIndex).preferredWidth = finalWidth
        }
    }
}
