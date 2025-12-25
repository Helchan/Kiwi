package com.euver.kiwi.service

import com.euver.kiwi.application.ExpandStatementUseCase
import com.euver.kiwi.domain.model.MethodInfo
import com.euver.kiwi.domain.model.TopCallerWithStatement
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
import com.intellij.ui.treeStructure.treetable.ListTreeTableModelOnColumns
import com.intellij.ui.treeStructure.treetable.TreeColumnInfo
import com.intellij.ui.treeStructure.treetable.TreeTableModel
import com.intellij.util.ui.ColumnInfo
import org.apache.poi.ss.usermodel.FillPatternType
import org.apache.poi.ss.usermodel.IndexedColors
import org.apache.poi.ss.util.CellRangeAddress
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.awt.BorderLayout
import java.awt.Desktop
import java.awt.datatransfer.StringSelection
import java.awt.event.ActionEvent
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicReference
import javax.swing.*
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.JTableHeader
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreePath
import javax.swing.ListSelectionModel
import com.intellij.ui.treeStructure.treetable.TreeTable
import java.awt.Component
import java.awt.Cursor

/**
 * 顶层调用者 TreeTable 展示对话框
 * 使用 TreeTable 实现真正的层级展示效果：
 * - 顶层调用者作为父节点
 * - Statement ID 作为子节点（仅在 SQL 片段模式下）
 */
class TopCallersTreeTableDialog private constructor(
    private val project: Project,
    private val sourceMethodName: String,
    private val topCallers: List<MethodInfo>,
    private val topCallersWithStatements: List<TopCallerWithStatement>?,
    private val isSqlFragmentMode: Boolean
) : DialogWrapper(project) {

    private val logger = thisLogger()
    private lateinit var treeTable: TreeTable
    private lateinit var treeModel: ListTreeTableModelOnColumns
    private lateinit var scrollPane: JBScrollPane
    
    // 用于 Excel 导出的扁平化数据
    private val exportDataList = mutableListOf<ExportRowData>()
    // 记录单元格合并信息（用于 Excel 导出）：行索引 -> (startRow, rowSpan)
    private val mergeInfo = mutableMapOf<Int, Pair<Int, Int>>()
    
    // 排序状态管理
    private var currentSortColumn: SortColumn = SortColumn.NONE
    private var sortAscending: Boolean = true
    
    // 原始数据存储（用于重新排序）
    private val originalTopCallers: List<MethodInfo> = topCallers.toList()
    private val originalTopCallersWithStatements: List<TopCallerWithStatement>? = topCallersWithStatements?.toList()
    
    /**
     * 可排序的列枚举
     * 表格列顺序：Seq(0), Type(1), Request Path(2), Method(3), Class Comment(4), Method Comment(5), StatementID(6), Statement Comment(7)
     */
    enum class SortColumn(val columnIndex: Int, val displayName: String) {
        NONE(-1, ""),
        TYPE(1, "Type"),
        REQUEST_PATH(2, "Request Path"),
        METHOD(3, "Method")
    }

    /**
     * 树节点数据类
     */
    sealed class TreeNodeData {
        /**
         * 顶层调用者节点
         */
        data class TopCallerData(
            val seqNumber: Int,
            val methodInfo: MethodInfo,
            val statementIds: List<String> = emptyList(),
            val statementComments: Map<String, String> = emptyMap()
        ) : TreeNodeData() {
            val type: String get() = if (methodInfo.isExternalInterface()) "API" else "Normal"
            val methodDisplay: String get() = "${methodInfo.simpleClassName}.${methodInfo.methodSignature}"
            
            // TreeColumnInfo 使用 toString() 显示内容
            override fun toString(): String = seqNumber.toString()
        }
        
        /**
         * Statement 子节点（仅在 SQL 片段模式下使用）
         */
        data class StatementData(
            val statementId: String,
            val parentMethodInfo: MethodInfo,
            val statementComment: String = ""
        ) : TreeNodeData() {
            /**
             * 获取不含 namespace 的简短 Statement ID
             */
            fun getSimpleStatementId(): String {
                val lastDot = statementId.lastIndexOf('.')
                return if (lastDot > 0) statementId.substring(lastDot + 1) else statementId
            }
            
            // 子节点显示空字符串（序号列不显示内容）
            override fun toString(): String = ""
        }
    }
    
    /**
     * 导出行数据（完整格式，用于控制台和 Excel 导出）
     */
    private data class ExportRowData(
        val seqNumber: Int,
        val type: String,
        val requestPath: String,
        val methodFqn: String,          // 方法全限定名：包名.类名.方法名(参数列表)
        val classComment: String,
        val functionComment: String,
        val statementId: String? = null, // 完整 StatementID（含 namespace）
        val statementComment: String? = null, // Statement 对应方法注释
        val isFirstOfGroup: Boolean = true
    )

    companion object {
        /**
         * MethodInfo 默认排序比较器
         * 排序规则：
         * 1. 类型（API 在前，Normal 在后）
         * 2. 请求路径（升序）
         * 3. 方法全限定名（升序）
         */
        private val methodInfoComparator = Comparator<MethodInfo> { a, b ->
            // 第一优先级：类型（API 在前）
            val typeA = if (a.isExternalInterface()) 0 else 1
            val typeB = if (b.isExternalInterface()) 0 else 1
            if (typeA != typeB) return@Comparator typeA.compareTo(typeB)
            
            // 第二优先级：请求路径升序
            val pathCompare = a.requestPath.compareTo(b.requestPath)
            if (pathCompare != 0) return@Comparator pathCompare
            
            // 第三优先级：方法全限定名升序
            a.qualifiedName.compareTo(b.qualifiedName)
        }
        /**
         * 创建普通模式的对话框
         */
        fun create(
            project: Project,
            sourceMethodName: String,
            topCallers: List<MethodInfo>
        ): TopCallersTreeTableDialog {
            return TopCallersTreeTableDialog(
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
        ): TopCallersTreeTableDialog {
            return TopCallersTreeTableDialog(
                project = project,
                sourceMethodName = sourceMethodName,
                topCallers = emptyList(),
                topCallersWithStatements = topCallersWithStatements,
                isSqlFragmentMode = true
            )
        }
    }

    init {
        title = "Top Callers List"
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
        
        // 构建树模型
        val root = DefaultMutableTreeNode("root")
        val columns = createColumns()
        
        if (isSqlFragmentMode && topCallersWithStatements != null) {
            buildTreeWithStatements(root, topCallersWithStatements)
        } else {
            buildTreeNormal(root, topCallers)
        }
        
        treeModel = ListTreeTableModelOnColumns(root, columns)
        treeTable = TreeTable(treeModel)
        
        // 配置 TreeTable
        treeTable.setRootVisible(false)
        treeTable.tree.isRootVisible = false
        treeTable.tree.showsRootHandles = true
        
        // 启用单元格选择模式，支持类似 Excel 的多选功能
        treeTable.setRowSelectionAllowed(true)
        treeTable.setColumnSelectionAllowed(true)
        treeTable.selectionModel.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
        treeTable.columnModel.selectionModel.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
        
        // 默认折叠所有节点（不调用 expandAllNodes）
        
        // 使用 AUTO_RESIZE_OFF 配合手动按比例分配列宽
        treeTable.autoResizeMode = JTable.AUTO_RESIZE_OFF
        
        // 设置表头排序点击监听和渲染器
        setupHeaderSorting()
        
        // 计算每列的内容宽度比例
        val columnContentWidths = calculateColumnContentWidths()
        
        // 添加滚动面板的尺寸变化监听器，实现响应式布局
        scrollPane = JBScrollPane(treeTable)
        scrollPane.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent?) {
                adjustColumnWidthsByProportion(columnContentWidths, scrollPane.viewport.width)
            }
        })
        
        // 设置右键菜单
        setupContextMenu()

        panel.add(scrollPane, BorderLayout.CENTER)
        return panel
    }
    
    /**
     * 设置表头排序点击监听和渲染器
     */
    private fun setupHeaderSorting() {
        val header = treeTable.tableHeader
        
        // 设置自定义表头渲染器（显示排序箭头）
        header.defaultRenderer = SortableHeaderRenderer(header.defaultRenderer)
        
        // 添加表头点击监听
        header.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                // 如果在列边缘区域，不触发排序（留给列宽拖拽）
                if (isOnColumnBorder(e.point, header)) return
                
                val columnIndex = header.columnAtPoint(e.point)
                if (columnIndex < 0) return
                
                // 检查是否是可排序的列
                val sortColumn = getSortColumnByIndex(columnIndex)
                if (sortColumn == SortColumn.NONE) return
                
                // 切换排序方向
                if (currentSortColumn == sortColumn) {
                    sortAscending = !sortAscending
                } else {
                    currentSortColumn = sortColumn
                    sortAscending = true
                }
                
                // 重新排序并重建树
                rebuildTreeWithCurrentSort()
                
                // 刷新表头显示
                header.repaint()
            }
            
            override fun mouseEntered(e: MouseEvent) {
                updateHeaderCursor(e.point, header)
            }
        })
        
        // 添加鼠标移动监听以更新光标
        header.addMouseMotionListener(object : MouseAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                updateHeaderCursor(e.point, header)
            }
            
            override fun mouseDragged(e: MouseEvent) {
                // 拖拽时保持列宽调整光标
                if (isOnColumnBorder(e.point, header)) {
                    header.cursor = Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR)
                }
            }
        })
    }
    
    /**
     * 检测鼠标是否在列边缘区域（用于列宽拖拽）
     * @param point 鼠标位置
     * @param header 表头组件
     * @return 是否在列边缘区域
     */
    private fun isOnColumnBorder(point: java.awt.Point, header: JTableHeader): Boolean {
        val columnModel = header.columnModel
        val borderTolerance = 4 // 边缘检测范围（像素）
        
        var columnX = 0
        for (i in 0 until columnModel.columnCount) {
            columnX += columnModel.getColumn(i).width
            // 检查是否在列右边缘附近
            if (point.x >= columnX - borderTolerance && point.x <= columnX + borderTolerance) {
                return true
            }
        }
        return false
    }
    
    /**
     * 更新表头光标样式
     * - 列边缘区域：显示列宽调整光标
     * - 可排序列内部：显示手型光标
     * - 其他区域：默认光标
     */
    private fun updateHeaderCursor(point: java.awt.Point, header: JTableHeader) {
        header.cursor = when {
            isOnColumnBorder(point, header) -> Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR)
            getSortColumnByIndex(header.columnAtPoint(point)) != SortColumn.NONE -> Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            else -> Cursor.getDefaultCursor()
        }
    }
    
    /**
     * 根据列索引获取对应的排序列枚举
     * 表格列顺序：Seq(0), Type(1), Request Path(2), Method(3), Class Comment(4), Method Comment(5), [StatementID(6), Statement Comment(7)]
     * 只有 Type(1), Request Path(2), Method(3) 支持排序
     */
    private fun getSortColumnByIndex(columnIndex: Int): SortColumn {
        // StatementID 列（索引 6）和 Statement Comment 列（索引 7）不支持排序
        if (isSqlFragmentMode && columnIndex >= 6) {
            return SortColumn.NONE
        }
        return SortColumn.values().find { it.columnIndex == columnIndex } ?: SortColumn.NONE
    }
    
    /**
     * 根据当前排序状态重建树
     * 保持当前的展开/折叠状态
     */
    private fun rebuildTreeWithCurrentSort() {
        // 保存当前展开的节点（通过 qualifiedName 标识）
        val expandedMethods = mutableSetOf<String>()
        val tree = treeTable.tree
        val root = treeModel.root as DefaultMutableTreeNode
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i) as DefaultMutableTreeNode
            val nodeData = child.userObject as? TreeNodeData.TopCallerData
            if (nodeData != null && child.childCount > 0) {
                val path = javax.swing.tree.TreePath(arrayOf(root, child))
                if (tree.isExpanded(path)) {
                    expandedMethods.add(nodeData.methodInfo.qualifiedName)
                }
            }
        }
        
        // 清空导出数据和合并信息
        exportDataList.clear()
        mergeInfo.clear()
        
        // 获取当前排序比较器
        val comparator = getCurrentSortComparator()
        
        // 重建树模型
        root.removeAllChildren()
        
        if (isSqlFragmentMode && originalTopCallersWithStatements != null) {
            buildTreeWithStatementsAndComparator(root, originalTopCallersWithStatements, comparator)
        } else {
            buildTreeNormalWithComparator(root, originalTopCallers, comparator)
        }
        
        // 通知模型更新
        treeModel.reload()
        
        // 恢复之前展开的节点
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i) as DefaultMutableTreeNode
            val nodeData = child.userObject as? TreeNodeData.TopCallerData
            if (nodeData != null && child.childCount > 0 && 
                expandedMethods.contains(nodeData.methodInfo.qualifiedName)) {
                val path = javax.swing.tree.TreePath(arrayOf(root, child))
                tree.expandPath(path)
            }
        }
    }
    
    /**
     * 获取当前排序比较器
     */
    private fun getCurrentSortComparator(): Comparator<MethodInfo> {
        if (currentSortColumn == SortColumn.NONE) {
            return methodInfoComparator
        }
        
        val primaryComparator = when (currentSortColumn) {
            SortColumn.TYPE -> Comparator<MethodInfo> { a, b ->
                val typeA = if (a.isExternalInterface()) "API" else "Normal"
                val typeB = if (b.isExternalInterface()) "API" else "Normal"
                typeA.compareTo(typeB)
            }
            SortColumn.REQUEST_PATH -> Comparator<MethodInfo> { a, b ->
                a.requestPath.compareTo(b.requestPath)
            }
            SortColumn.METHOD -> Comparator<MethodInfo> { a, b ->
                val methodA = "${a.simpleClassName}.${a.methodSignature}"
                val methodB = "${b.simpleClassName}.${b.methodSignature}"
                methodA.compareTo(methodB)
            }
            SortColumn.NONE -> methodInfoComparator
        }
        
        // 应用升序/降序
        val orderedPrimary = if (sortAscending) primaryComparator else primaryComparator.reversed()
        
        // 组合次要排序条件（使用默认排序的其他字段作为次要条件）
        return orderedPrimary.thenComparing(methodInfoComparator)
    }
    
    /**
     * 普通模式构建树（使用指定比较器）
     */
    private fun buildTreeNormalWithComparator(
        root: DefaultMutableTreeNode, 
        topCallers: List<MethodInfo>,
        comparator: Comparator<MethodInfo>
    ) {
        val sortedCallers = topCallers.sortedWith(comparator)
        
        sortedCallers.forEachIndexed { index, methodInfo ->
            val nodeData = TreeNodeData.TopCallerData(
                seqNumber = index + 1,
                methodInfo = methodInfo
            )
            val node = DefaultMutableTreeNode(nodeData)
            root.add(node)
            
            exportDataList.add(ExportRowData(
                seqNumber = index + 1,
                type = nodeData.type,
                requestPath = methodInfo.requestPath,
                methodFqn = "${methodInfo.packageName}.${methodInfo.simpleClassName}.${methodInfo.methodSignature}",
                classComment = methodInfo.classComment,
                functionComment = methodInfo.functionComment
            ))
        }
    }
    
    /**
     * SQL 片段模式构建树（使用指定比较器）
     */
    private fun buildTreeWithStatementsAndComparator(
        root: DefaultMutableTreeNode,
        data: List<TopCallerWithStatement>,
        comparator: Comparator<MethodInfo>
    ) {
        val groupedByMethod = data.groupBy { it.methodInfo.qualifiedName }
        
        val sortedGroups = groupedByMethod.entries.sortedWith(
            Comparator { a, b -> comparator.compare(a.value.first().methodInfo, b.value.first().methodInfo) }
        )
        
        var seqNumber = 0
        var exportRowIndex = 0
        
        for ((_, group) in sortedGroups) {
            seqNumber++
            val methodInfo = group.first().methodInfo
            val statementIds = group.map { it.statementId }.distinct()
            val statementComments = group.associate { it.statementId to it.statementComment }
            
            val nodeData = TreeNodeData.TopCallerData(
                seqNumber = seqNumber,
                methodInfo = methodInfo,
                statementIds = statementIds,
                statementComments = statementComments
            )
            val parentNode = DefaultMutableTreeNode(nodeData)
            root.add(parentNode)
            
            val startRow = exportRowIndex
            val rowSpan = statementIds.size
            
            if (statementIds.size == 1) {
                val statementId = statementIds.first()
                exportDataList.add(ExportRowData(
                    seqNumber = seqNumber,
                    type = nodeData.type,
                    requestPath = methodInfo.requestPath,
                    methodFqn = "${methodInfo.packageName}.${methodInfo.simpleClassName}.${methodInfo.methodSignature}",
                    classComment = methodInfo.classComment,
                    functionComment = methodInfo.functionComment,
                    statementId = statementId,
                    statementComment = statementComments[statementId] ?: "",
                    isFirstOfGroup = true
                ))
                mergeInfo[exportRowIndex] = Pair(startRow, 1)
                exportRowIndex++
            } else {
                for ((i, statementId) in statementIds.withIndex()) {
                    val childData = TreeNodeData.StatementData(
                        statementId = statementId,
                        parentMethodInfo = methodInfo,
                        statementComment = statementComments[statementId] ?: ""
                    )
                    val childNode = DefaultMutableTreeNode(childData)
                    parentNode.add(childNode)
                    
                    exportDataList.add(ExportRowData(
                        seqNumber = seqNumber,
                        type = nodeData.type,
                        requestPath = methodInfo.requestPath,
                        methodFqn = "${methodInfo.packageName}.${methodInfo.simpleClassName}.${methodInfo.methodSignature}",
                        classComment = methodInfo.classComment,
                        functionComment = methodInfo.functionComment,
                        statementId = statementId,
                        statementComment = statementComments[statementId] ?: "",
                        isFirstOfGroup = (i == 0)
                    ))
                    mergeInfo[exportRowIndex] = Pair(startRow, rowSpan)
                    exportRowIndex++
                }
            }
        }
        
        logger.info("构建树完成：${seqNumber} 个顶层调用者，${exportDataList.size} 行导出数据")
    }
    
    /**
     * 自定义表头渲染器，显示排序箭头
     */
    private inner class SortableHeaderRenderer(
        private val defaultRenderer: javax.swing.table.TableCellRenderer
    ) : DefaultTableCellRenderer() {
        
        override fun getTableCellRendererComponent(
            table: JTable,
            value: Any?,
            isSelected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int
        ): Component {
            val component = defaultRenderer.getTableCellRendererComponent(
                table, value, isSelected, hasFocus, row, column
            )
            
            if (component is JLabel) {
                val sortColumn = getSortColumnByIndex(column)
                if (sortColumn != SortColumn.NONE && currentSortColumn == sortColumn) {
                    // 添加排序箭头
                    val arrow = if (sortAscending) " ▲" else " ▼"
                    component.text = value.toString() + arrow
                }
            }
            
            return component
        }
    }
    
    /**
     * 创建列定义
     * 注意：第一列必须使用 TreeColumnInfo 类型才能显示展开/折叠控件
     * 表格列顺序：Seq, Type, Request Path, Method, Class Comment, Method Comment, [StatementID, Statement Comment]
     * 注：Package 列已移除，保持界面简洁
     */
    private fun createColumns(): Array<ColumnInfo<*, *>> {
        val columns = mutableListOf<ColumnInfo<*, *>>()
        
        // 第一列使用 TreeColumnInfo，显示序号并支持展开/折叠
        // TreeColumnInfo 会使用节点的 toString() 方法显示内容
        columns.add(TreeColumnInfo("Seq"))
        
        columns.add(object : ColumnInfo<Any?, String>("Type") {
            override fun valueOf(item: Any?): String {
                return when (val data = (item as? DefaultMutableTreeNode)?.userObject) {
                    is TreeNodeData.TopCallerData -> data.type
                    else -> ""
                }
            }
        })
        
        columns.add(object : ColumnInfo<Any?, String>("Request Path") {
            override fun valueOf(item: Any?): String {
                return when (val data = (item as? DefaultMutableTreeNode)?.userObject) {
                    is TreeNodeData.TopCallerData -> data.methodInfo.requestPath
                    else -> ""
                }
            }
        })
        
        columns.add(object : ColumnInfo<Any?, String>("Method") {
            override fun valueOf(item: Any?): String {
                return when (val data = (item as? DefaultMutableTreeNode)?.userObject) {
                    is TreeNodeData.TopCallerData -> data.methodDisplay
                    else -> ""
                }
            }
        })
        
        columns.add(object : ColumnInfo<Any?, String>("Class Comment") {
            override fun valueOf(item: Any?): String {
                return when (val data = (item as? DefaultMutableTreeNode)?.userObject) {
                    is TreeNodeData.TopCallerData -> data.methodInfo.classComment
                    else -> ""
                }
            }
        })
        
        columns.add(object : ColumnInfo<Any?, String>("Method Comment") {
            override fun valueOf(item: Any?): String {
                return when (val data = (item as? DefaultMutableTreeNode)?.userObject) {
                    is TreeNodeData.TopCallerData -> data.methodInfo.functionComment
                    else -> ""
                }
            }
        })
        
        // SQL 片段模式下增加 StatementID 和 Statement Comment 列
        if (isSqlFragmentMode) {
            // StatementID 列：仅显示 ID 部分（去除 namespace）
            columns.add(object : ColumnInfo<Any?, String>("StatementID") {
                override fun valueOf(item: Any?): String {
                    return when (val data = (item as? DefaultMutableTreeNode)?.userObject) {
                        is TreeNodeData.StatementData -> data.getSimpleStatementId()
                        is TreeNodeData.TopCallerData -> {
                            if (data.statementIds.size == 1) {
                                val fullId = data.statementIds.first()
                                val lastDot = fullId.lastIndexOf('.')
                                if (lastDot > 0) fullId.substring(lastDot + 1) else fullId
                            } else ""
                        }
                        else -> ""
                    }
                }
            })
            
            // Statement Comment 列：显示 Statement 对应的 Mapper 方法注释
            columns.add(object : ColumnInfo<Any?, String>("Statement Comment") {
                override fun valueOf(item: Any?): String {
                    return when (val data = (item as? DefaultMutableTreeNode)?.userObject) {
                        is TreeNodeData.StatementData -> data.statementComment
                        is TreeNodeData.TopCallerData -> {
                            if (data.statementIds.size == 1) {
                                data.statementComments[data.statementIds.first()] ?: ""
                            } else ""
                        }
                        else -> ""
                    }
                }
            })
        }
        
        return columns.toTypedArray()
    }
    
    /**
     * 普通模式构建树
     */
    private fun buildTreeNormal(root: DefaultMutableTreeNode, topCallers: List<MethodInfo>) {
        // 按默认排序规则排序
        val sortedCallers = topCallers.sortedWith(methodInfoComparator)
        
        sortedCallers.forEachIndexed { index, methodInfo ->
            val nodeData = TreeNodeData.TopCallerData(
                seqNumber = index + 1,
                methodInfo = methodInfo
            )
            val node = DefaultMutableTreeNode(nodeData)
            root.add(node)
            
            // 添加导出数据（方法全限定名格式）
            exportDataList.add(ExportRowData(
                seqNumber = index + 1,
                type = nodeData.type,
                requestPath = methodInfo.requestPath,
                methodFqn = "${methodInfo.packageName}.${methodInfo.simpleClassName}.${methodInfo.methodSignature}",
                classComment = methodInfo.classComment,
                functionComment = methodInfo.functionComment
            ))
        }
    }
    
    /**
     * SQL 片段模式构建树（带 Statement 子节点）
     */
    private fun buildTreeWithStatements(root: DefaultMutableTreeNode, data: List<TopCallerWithStatement>) {
        // 按顶层调用者分组
        val groupedByMethod = data.groupBy { it.methodInfo.qualifiedName }
        
        // 按默认排序规则对分组进行排序（取每组第一个元素的 methodInfo 进行排序）
        val sortedGroups = groupedByMethod.entries.sortedWith(
            Comparator { a, b -> methodInfoComparator.compare(a.value.first().methodInfo, b.value.first().methodInfo) }
        )
        
        var seqNumber = 0
        var exportRowIndex = 0
        
        for ((_, group) in sortedGroups) {
            seqNumber++
            val methodInfo = group.first().methodInfo
            val statementIds = group.map { it.statementId }.distinct()
            // 构建 statementId -> statementComment 的映射
            val statementComments = group.associate { it.statementId to it.statementComment }
            
            val nodeData = TreeNodeData.TopCallerData(
                seqNumber = seqNumber,
                methodInfo = methodInfo,
                statementIds = statementIds,
                statementComments = statementComments
            )
            val parentNode = DefaultMutableTreeNode(nodeData)
            root.add(parentNode)
            
            // 记录合并信息起始行
            val startRow = exportRowIndex
            val rowSpan = statementIds.size
            
            // 如果只有一个 Statement，不需要子节点
            if (statementIds.size == 1) {
                val statementId = statementIds.first()
                exportDataList.add(ExportRowData(
                    seqNumber = seqNumber,
                    type = nodeData.type,
                    requestPath = methodInfo.requestPath,
                    methodFqn = "${methodInfo.packageName}.${methodInfo.simpleClassName}.${methodInfo.methodSignature}",
                    classComment = methodInfo.classComment,
                    functionComment = methodInfo.functionComment,
                    statementId = statementId,
                    statementComment = statementComments[statementId] ?: "",
                    isFirstOfGroup = true
                ))
                mergeInfo[exportRowIndex] = Pair(startRow, 1)
                exportRowIndex++
            } else {
                // 多个 Statement，添加子节点
                for ((i, statementId) in statementIds.withIndex()) {
                    val childData = TreeNodeData.StatementData(
                        statementId = statementId,
                        parentMethodInfo = methodInfo,
                        statementComment = statementComments[statementId] ?: ""
                    )
                    val childNode = DefaultMutableTreeNode(childData)
                    parentNode.add(childNode)
                    
                    // 添加导出数据
                    exportDataList.add(ExportRowData(
                        seqNumber = seqNumber,
                        type = nodeData.type,
                        requestPath = methodInfo.requestPath,
                        methodFqn = "${methodInfo.packageName}.${methodInfo.simpleClassName}.${methodInfo.methodSignature}",
                        classComment = methodInfo.classComment,
                        functionComment = methodInfo.functionComment,
                        statementId = statementId,
                        statementComment = statementComments[statementId] ?: "",
                        isFirstOfGroup = (i == 0)
                    ))
                    mergeInfo[exportRowIndex] = Pair(startRow, rowSpan)
                    exportRowIndex++
                }
            }
        }
        
        logger.info("构建树完成：${seqNumber} 个顶层调用者，${exportDataList.size} 行导出数据")
    }
    
    /**
     * 展开所有节点
     */
    private fun expandAllNodes() {
        val tree = treeTable.tree
        var i = 0
        while (i < tree.rowCount) {
            tree.expandRow(i)
            i++
        }
    }
    
    /**
     * 折叠所有节点
     */
    private fun collapseAllNodes() {
        val tree = treeTable.tree
        // 从后往前折叠，避免索引变化问题
        for (i in tree.rowCount - 1 downTo 0) {
            tree.collapseRow(i)
        }
    }
    
    /**
     * 检查是否存在可展开的节点（有子节点且当前折叠的节点）
     */
    private fun hasCollapsedNodes(): Boolean {
        val tree = treeTable.tree
        val root = treeModel.root as DefaultMutableTreeNode
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i) as DefaultMutableTreeNode
            if (child.childCount > 0) {
                val path = javax.swing.tree.TreePath(arrayOf(root, child))
                if (tree.isCollapsed(path)) {
                    return true
                }
            }
        }
        return false
    }
    
    /**
     * 检查是否存在可折叠的节点（有子节点且当前展开的节点）
     */
    private fun hasExpandedNodes(): Boolean {
        val tree = treeTable.tree
        val root = treeModel.root as DefaultMutableTreeNode
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i) as DefaultMutableTreeNode
            if (child.childCount > 0) {
                val path = javax.swing.tree.TreePath(arrayOf(root, child))
                if (tree.isExpanded(path)) {
                    return true
                }
            }
        }
        return false
    }
    
    /**
     * 检查是否存在有子节点的父节点
     */
    private fun hasExpandableNodes(): Boolean {
        val root = treeModel.root as DefaultMutableTreeNode
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i) as DefaultMutableTreeNode
            if (child.childCount > 0) {
                return true
            }
        }
        return false
    }
    
    /**
     * 计算每列内容的最大宽度（用于按比例分配列宽）
     * 表格列顺序：Seq(0), Type(1), Request Path(2), Method(3), Class Comment(4), Method Comment(5), [StatementID(6), Statement Comment(7)]
     * @return 每列的最大内容宽度（像素）列表
     */
    private fun calculateColumnContentWidths(): List<Int> {
        val columnModel = treeTable.columnModel
        val fontMetrics = treeTable.getFontMetrics(treeTable.font)
        val padding = 16 // 左右内边距
        val minColumnWidth = 50 // 最小列宽
        // 第一列（树列）需要额外空间用于展开/折叠图标
        val treeColumnExtraPadding = 40
        
        return (0 until columnModel.columnCount).map { colIndex ->
            var maxWidth = 0
            
            // 计算表头宽度
            val headerValue = columnModel.getColumn(colIndex).headerValue?.toString() ?: ""
            val headerWidth = fontMetrics.stringWidth(headerValue) + padding
            maxWidth = maxOf(maxWidth, headerWidth)
            
            // 为第一列添加额外空间
            if (colIndex == 0) {
                maxWidth += treeColumnExtraPadding
            }
            
            // 遍历导出数据计算内容宽度
            // 表格列顺序：Seq(0), Type(1), Request Path(2), Method(3), Class Comment(4), Method Comment(5), [StatementID(6), Statement Comment(7)]
            for (rowData in exportDataList) {
                val value = when (colIndex) {
                    0 -> rowData.seqNumber.toString()
                    1 -> rowData.type
                    2 -> rowData.requestPath
                    3 -> "${rowData.methodFqn.substringAfterLast('.', "").substringBefore('(')}" // 表格显示简短方法名
                    4 -> rowData.classComment
                    5 -> rowData.functionComment
                    6 -> {
                        // StatementID 列（表格显示简短 ID）
                        val fullId = rowData.statementId ?: ""
                        val lastDot = fullId.lastIndexOf('.')
                        if (lastDot > 0) fullId.substring(lastDot + 1) else fullId
                    }
                    7 -> rowData.statementComment ?: ""
                    else -> ""
                }
                val cellWidth = fontMetrics.stringWidth(value) + padding
                maxWidth = maxOf(maxWidth, cellWidth)
            }
            
            maxOf(maxWidth, minColumnWidth)
        }
    }
    
    /**
     * 根据内容宽度比例分配列宽
     * 算法：根据各列最大内容宽度的比例分配总可用宽度
     * 例如：若A列最大内容宽度为200px，B列为300px，C列为400px，
     * 则A列宽度应为 (200/(200+300+400)) * 总可用宽度
     * 
     * @param contentWidths 每列的内容宽度列表
     * @param availableWidth 可用总宽度
     */
    private fun adjustColumnWidthsByProportion(contentWidths: List<Int>, availableWidth: Int) {
        if (contentWidths.isEmpty() || availableWidth <= 0) return
        
        val columnModel = treeTable.columnModel
        val totalContentWidth = contentWidths.sum()
        
        if (totalContentWidth <= 0) return
        
        // 按比例分配宽度
        var allocatedWidth = 0
        for (colIndex in 0 until columnModel.columnCount) {
            val proportion = contentWidths[colIndex].toDouble() / totalContentWidth
            val columnWidth = if (colIndex == columnModel.columnCount - 1) {
                // 最后一列使用剩余宽度，避免累计误差
                availableWidth - allocatedWidth
            } else {
                (availableWidth * proportion).toInt()
            }
            
            columnModel.getColumn(colIndex).preferredWidth = columnWidth
            allocatedWidth += columnWidth
        }
        
        // 强制刷新表格以应用新的列宽
        treeTable.doLayout()
    }
    
    /**
     * 设置右键菜单
     * 根据用户需求：
     * 1. 多选模式（选中多个单元格）：只显示"复制"
     * 2. 单选模式 - StatementID 列有内容：显示 "跳转到XML"、"Copy Expanded Statement"、"复制"
     * 3. 单选模式 - 其他列有内容：显示 "跳转到源码"、"复制"
     * 4. 无内容的单元格或空白区域：显示 Expand All/Collapse All（如果存在可展开节点）
     */
    private fun setupContextMenu() {
        // 注册 Ctrl+C / Command+C 快捷键
        val copyKeyStroke = KeyStroke.getKeyStroke(KeyEvent.VK_C, 
            java.awt.Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx)
        treeTable.getInputMap(JComponent.WHEN_FOCUSED).put(copyKeyStroke, "copy")
        treeTable.actionMap.put("copy", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) {
                // 复制选中的单元格（支持多选）
                copySelectedCells()
            }
        })

        treeTable.addMouseListener(object : MouseAdapter() {
            private fun handlePopup(e: MouseEvent) {
                if (e.isPopupTrigger) {
                    val col = treeTable.columnAtPoint(e.point)
                    val row = treeTable.rowAtPoint(e.point)
                    
                    // 空白区域点击：显示 Expand All/Collapse All 菜单
                    if (row < 0 || col < 0) {
                        showExpandCollapseOnlyMenu(e)
                        return
                    }
                    
                    // 检查是否选中了多个单元格
                    val isMultiSelect = hasMultipleCellsSelected()
                    
                    // 多选模式：只显示"复制"菜单
                    if (isMultiSelect) {
                        val popupMenu = JPopupMenu()
                        val copyItem = JMenuItem("复制")
                        copyItem.addActionListener { copySelectedCells() }
                        popupMenu.add(copyItem)
                        popupMenu.show(e.component, e.x, e.y)
                        return
                    }
                    
                    // 单选模式逻辑
                    // 获取单元格内容
                    val cellValue = treeTable.getValueAt(row, col)?.toString() ?: ""
                    if (cellValue.isBlank()) {
                        // 无内容的单元格：显示 Expand All/Collapse All 菜单
                        showExpandCollapseOnlyMenu(e)
                        return
                    }
                    
                    treeTable.setRowSelectionInterval(row, row)
                    treeTable.setColumnSelectionInterval(col, col)
                    val treePath = treeTable.tree.getPathForRow(row)
                    if (treePath != null) {
                        treeTable.tree.selectionPath = treePath
                    }
                    
                    val node = treePath?.lastPathComponent as? DefaultMutableTreeNode
                    val nodeData = node?.userObject
                    
                    // 判断是否是 StatementID 列（SQL 片段模式下的倒数第二列，索引为6）
                    val isStatementIdColumn = isSqlFragmentMode && col == 6
                    // 判断是否是 Method 列（索引 3）
                    val isMethodColumn = col == 3
                    
                    // 构建菜单
                    val popupMenu = JPopupMenu()
                    
                    if (isStatementIdColumn) {
                        // StatementID 列：跳转到XML、Copy Expanded Statement
                        val navigateToXmlItem = JMenuItem("跳转到XML")
                        navigateToXmlItem.addActionListener {
                            when (nodeData) {
                                is TreeNodeData.StatementData -> navigateToStatement(nodeData.statementId)
                                is TreeNodeData.TopCallerData -> {
                                    if (nodeData.statementIds.size == 1) {
                                        navigateToStatement(nodeData.statementIds.first())
                                    }
                                }
                            }
                        }
                        popupMenu.add(navigateToXmlItem)
                        
                        val copyExpandedItem = JMenuItem("Copy Expanded Statement")
                        copyExpandedItem.addActionListener {
                            when (nodeData) {
                                is TreeNodeData.StatementData -> copyExpandedStatement(nodeData.statementId)
                                is TreeNodeData.TopCallerData -> {
                                    if (nodeData.statementIds.size == 1) {
                                        copyExpandedStatement(nodeData.statementIds.first())
                                    }
                                }
                            }
                        }
                        popupMenu.add(copyExpandedItem)
                    } else if (isMethodColumn) {
                        // Method 列：跳转到源码
                        val navigateToSourceItem = JMenuItem("跳转到源码")
                        navigateToSourceItem.addActionListener {
                            when (nodeData) {
                                is TreeNodeData.TopCallerData -> navigateToMethod(nodeData.methodInfo)
                                is TreeNodeData.StatementData -> navigateToMethod(nodeData.parentMethodInfo)
                            }
                        }
                        popupMenu.add(navigateToSourceItem)
                    }
                    // 其他列（Seq、Type、Request Path、Class Comment、Method Comment、Package）不显示跳转功能
                    
                    // 添加展开全部/折叠全部菜单项（仅在存在可展开/折叠节点时显示）
                    addExpandCollapseMenuItems(popupMenu)
                    
                    // 复制菜单项放在最后
                    popupMenu.addSeparator()
                    val copyItem = JMenuItem("复制")
                    copyItem.addActionListener { copyCellContent(row, col) }
                    popupMenu.add(copyItem)
                    
                    popupMenu.show(e.component, e.x, e.y)
                }
            }

            override fun mousePressed(e: MouseEvent) = handlePopup(e)
            override fun mouseReleased(e: MouseEvent) = handlePopup(e)
        })
        
        // 同时在 tree 组件上添加监听器（保证点击树节点时也能触发）
        treeTable.tree.addMouseListener(object : MouseAdapter() {
            private fun handlePopup(e: MouseEvent) {
                if (e.isPopupTrigger) {
                    val path = treeTable.tree.getPathForLocation(e.x, e.y)
                    if (path == null) {
                        // 空白区域点击：显示 Expand All/Collapse All 菜单
                        showExpandCollapseOnlyMenu(e)
                        return
                    }
                    
                    // 检查是否选中了多个单元格
                    val isMultiSelect = hasMultipleCellsSelected()
                    
                    // 多选模式：只显示"复制"菜单
                    if (isMultiSelect) {
                        val popupMenu = JPopupMenu()
                        val copyItem = JMenuItem("复制")
                        copyItem.addActionListener { copySelectedCells() }
                        popupMenu.add(copyItem)
                        popupMenu.show(e.component, e.x, e.y)
                        return
                    }
                    
                    // 单选模式逻辑
                    // 获取对应的行号
                    val row = treeTable.tree.getRowForPath(path)
                    
                    treeTable.tree.selectionPath = path
                    val node = path.lastPathComponent as? DefaultMutableTreeNode
                    val nodeData = node?.userObject ?: return
                    
                    // 树列点击（第一列 Seq），不显示跳转功能
                    val popupMenu = JPopupMenu()
                    
                    // 添加展开全部/折叠全部菜单项（仅在存在可展开/折叠节点时显示）
                    addExpandCollapseMenuItems(popupMenu)
                    
                    // 复制菜单项放在最后（树列是第0列）
                    popupMenu.addSeparator()
                    val copyItem = JMenuItem("复制")
                    copyItem.addActionListener { copyCellContent(row, 0) }
                    popupMenu.add(copyItem)
                    
                    popupMenu.show(e.component, e.x, e.y)
                }
            }

            override fun mousePressed(e: MouseEvent) = handlePopup(e)
            override fun mouseReleased(e: MouseEvent) = handlePopup(e)
        })
        
        // 在滚动面板上添加监听器，保证在滚动面板空白区域右键时也能触发
        scrollPane.addMouseListener(object : MouseAdapter() {
            private fun handlePopup(e: MouseEvent) {
                if (e.isPopupTrigger) {
                    showExpandCollapseOnlyMenu(e)
                }
            }
            
            override fun mousePressed(e: MouseEvent) = handlePopup(e)
            override fun mouseReleased(e: MouseEvent) = handlePopup(e)
        })
        
        // 在 viewport 上添加监听器
        scrollPane.viewport.addMouseListener(object : MouseAdapter() {
            private fun handlePopup(e: MouseEvent) {
                if (e.isPopupTrigger) {
                    showExpandCollapseOnlyMenu(e)
                }
            }
            
            override fun mousePressed(e: MouseEvent) = handlePopup(e)
            override fun mouseReleased(e: MouseEvent) = handlePopup(e)
        })
    }
    
    /**
     * 显示仅包含 Expand All/Collapse All 的右键菜单
     * 用于空白区域或无内容单元格的右键点击
     */
    private fun showExpandCollapseOnlyMenu(e: MouseEvent) {
        // 检查是否存在可展开/折叠的节点
        if (!hasExpandableNodes()) {
            return  // 无子节点，不显示菜单
        }
        
        val hasCollapsed = hasCollapsedNodes()
        val hasExpanded = hasExpandedNodes()
        
        // 如果没有可展开或可折叠的节点，不显示菜单
        if (!hasCollapsed && !hasExpanded) {
            return
        }
        
        val popupMenu = JPopupMenu()
        
        if (hasCollapsed) {
            val expandAllItem = JMenuItem("Expand All")
            expandAllItem.addActionListener {
                expandAllNodes()
            }
            popupMenu.add(expandAllItem)
        }
        
        if (hasExpanded) {
            val collapseAllItem = JMenuItem("Collapse All")
            collapseAllItem.addActionListener {
                collapseAllNodes()
            }
            popupMenu.add(collapseAllItem)
        }
        
        popupMenu.show(e.component, e.x, e.y)
    }
    
    /**
     * 复制指定单元格内容
     * @param row 行索引
     * @param col 列索引
     */
    private fun copyCellContent(row: Int, col: Int) {
        if (row < 0 || col < 0) return
        
        val cellValue = treeTable.getValueAt(row, col)?.toString() ?: ""
        if (cellValue.isNotBlank()) {
            CopyPasteManager.getInstance().setContents(StringSelection(cellValue))
        }
    }
    
    /**
     * 检查是否选中了多个单元格
     * @return 是否有多个单元格被选中
     */
    private fun hasMultipleCellsSelected(): Boolean {
        val selectedRows = treeTable.selectedRows
        val selectedColumns = treeTable.selectedColumns
        return selectedRows.size * selectedColumns.size > 1
    }
    
    /**
     * 复制选中的所有单元格内容
     * 如果选中了多个单元格，使用制表符分隔列，换行符分隔行（与 Excel 格式兼容）
     */
    private fun copySelectedCells() {
        val selectedRows = treeTable.selectedRows
        val selectedColumns = treeTable.selectedColumns
        
        if (selectedRows.isEmpty() || selectedColumns.isEmpty()) return
        
        // 如果只选中了一个单元格，调用单元格复制方法
        if (selectedRows.size == 1 && selectedColumns.size == 1) {
            copyCellContent(selectedRows[0], selectedColumns[0])
            return
        }
        
        // 构建制表符分隔的文本
        val builder = StringBuilder()
        
        // 对行进行排序以确保顺序
        val sortedRows = selectedRows.sorted()
        val sortedColumns = selectedColumns.sorted()
        
        for ((rowIndex, row) in sortedRows.withIndex()) {
            for ((colIndex, col) in sortedColumns.withIndex()) {
                val cellValue = treeTable.getValueAt(row, col)?.toString() ?: ""
                builder.append(cellValue)
                
                // 不是最后一列，添加制表符
                if (colIndex < sortedColumns.size - 1) {
                    builder.append("\t")
                }
            }
            
            // 不是最后一行，添加换行符
            if (rowIndex < sortedRows.size - 1) {
                builder.append("\n")
            }
        }
        
        val content = builder.toString()
        if (content.isNotBlank()) {
            CopyPasteManager.getInstance().setContents(StringSelection(content))
        }
    }
    
    /**
     * 添加展开全部/折叠全部菜单项
     * 根据当前状态智能显示：
     * - 无子节点时：不显示任何菜单项
     * - 所有节点都已展开：只显示"折叠全部"
     * - 所有节点都已折叠：只显示"展开全部"
     * - 混合状态：同时显示两个选项
     */
    private fun addExpandCollapseMenuItems(popupMenu: JPopupMenu) {
        // 检查是否存在可展开/折叠的节点
        if (!hasExpandableNodes()) {
            return  // 无子节点，不显示菜单项
        }
        
        val hasCollapsed = hasCollapsedNodes()
        val hasExpanded = hasExpandedNodes()
        
        // 添加分隔线
        popupMenu.addSeparator()
        
        // 根据状态显示相应菜单项
        if (hasCollapsed) {
            val expandAllItem = JMenuItem("Expand All")
            expandAllItem.addActionListener {
                expandAllNodes()
            }
            popupMenu.add(expandAllItem)
        }
        
        if (hasExpanded) {
            val collapseAllItem = JMenuItem("Collapse All")
            collapseAllItem.addActionListener {
                collapseAllNodes()
            }
            popupMenu.add(collapseAllItem)
        }
    }

    override fun createNorthPanel(): JComponent? {
        val panel = JPanel(BorderLayout())
        panel.add(JLabel("Source: $sourceMethodName"), BorderLayout.WEST)
        return panel
    }

    override fun getDimensionServiceKey(): String? {
        return "Kiwi.TopCallers.TreeTableDialog"
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
        
        ConsoleOutputService(project).outputToConsole(result)
        
        if (result.hasCircularReference()) {
            logger.warn("存在循环引用，跳过剪贴板复制")
            NotificationService(project).showAssemblyResultNotification(result)
            return
        }
        
        val copySuccess = ConsoleOutputService(project).copyToClipboard(result.assembledSql)
        if (copySuccess) {
            NotificationService(project).showAssemblyResultNotification(result)
        } else {
            logger.warn("复制到剪贴板失败")
        }
    }
    
    /**
     * 导出表格内容到 Excel 文件（保持合并单元格效果）
     * Excel 导出使用完整格式：方法全限定名，完整 StatementID
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
                val sheet = workbook.createSheet("Top Callers")
                
                // 创建表头样式
                val headerStyle = workbook.createCellStyle().apply {
                    fillForegroundColor = IndexedColors.PALE_BLUE.index
                    fillPattern = FillPatternType.SOLID_FOREGROUND
                    val font = workbook.createFont().apply {
                        bold = true
                    }
                    setFont(font)
                }
                
                // 创建表头行（完整格式：方法全限定名，完整 StatementID，新增 Statement Comment）
                val columnNames = if (isSqlFragmentMode) {
                    arrayOf("Seq", "Type", "Request Path", "Method FQN", "Class Comment", "Method Comment", "StatementID", "Statement Comment")
                } else {
                    arrayOf("Seq", "Type", "Request Path", "Method FQN", "Class Comment", "Method Comment")
                }
                val headerRow = sheet.createRow(0)
                columnNames.forEachIndexed { index, name ->
                    val cell = headerRow.createCell(index)
                    cell.setCellValue(name)
                    cell.cellStyle = headerStyle
                }
                
                // 填充数据行
                exportDataList.forEachIndexed { rowIndex, rowData ->
                    val row = sheet.createRow(rowIndex + 1)
                    
                    row.createCell(0).setCellValue(if (rowData.isFirstOfGroup) rowData.seqNumber.toString() else "")
                    row.createCell(1).setCellValue(if (rowData.isFirstOfGroup) rowData.type else "")
                    row.createCell(2).setCellValue(if (rowData.isFirstOfGroup) rowData.requestPath else "")
                    row.createCell(3).setCellValue(if (rowData.isFirstOfGroup) rowData.methodFqn else "") // 方法全限定名
                    row.createCell(4).setCellValue(if (rowData.isFirstOfGroup) rowData.classComment else "")
                    row.createCell(5).setCellValue(if (rowData.isFirstOfGroup) rowData.functionComment else "")
                    
                    if (isSqlFragmentMode) {
                        row.createCell(6).setCellValue(rowData.statementId ?: "") // 完整 StatementID
                        row.createCell(7).setCellValue(rowData.statementComment ?: "") // Statement Comment
                    }
                }
                
                // 设置列宽度（自适应内容+30像素，最大300像素）
                // POI 列宽单位：1/256 字符宽度，1字符约7像素
                val extraPadding = 30 * 256 / 7  // 30像素转换为POI单位
                val maxPixelWidth = 300
                val maxColumnWidth = maxPixelWidth * 256 / 7
                for (colIndex in columnNames.indices) {
                    sheet.autoSizeColumn(colIndex)
                    val newWidth = minOf(sheet.getColumnWidth(colIndex) + extraPadding, maxColumnWidth)
                    sheet.setColumnWidth(colIndex, newWidth)
                }
                
                // SQL 片段模式下应用单元格合并
                if (isSqlFragmentMode) {
                    applyExcelMergeRegions(sheet)
                }
                
                FileOutputStream(file).use { fos ->
                    workbook.write(fos)
                }
            }
            
            logger.info("导出成功：${file.absolutePath}")
            ConsoleOutputService(project).output("导出成功：${file.absolutePath}")
            NotificationService(project).showInfoNotification("导出成功")
            
            openDirectory(outDir)
            
        } catch (e: Exception) {
            logger.error("导出 Excel 失败", e)
            ConsoleOutputService(project).output("导出失败：${e.message}")
            NotificationService(project).showErrorNotification("导出失败：${e.message}")
        }
    }
    
    private fun createOutputDirectory(): File? {
        val projectBasePath = project.basePath ?: return null
        val outDir = File(projectBasePath, ".Kiwi/out")
        return if (outDir.exists() || outDir.mkdirs()) outDir else null
    }
    
    private fun generateFileName(): String {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        // 从 sourceMethodName 提取触发的 ID 或方法名
        val baseName = extractTriggerName(sourceMethodName)
        return "${baseName}_${timestamp}.xlsx"
    }
    
    /**
     * 提取触发的 ID 或方法名
     * 支持格式：
     * - SQL Fragment: com.example.UserMapper.baseColumns -> baseColumns
     * - com.example.UserService.getUserById -> getUserById
     */
    private fun extractTriggerName(sourceName: String): String {
        val cleanName = sourceName.removePrefix("SQL Fragment: ")
        // 取最后一个点号后的部分作为文件名
        val triggerName = cleanName.substringAfterLast('.', cleanName)
        return sanitizeFileName(triggerName)
    }
    
    private fun sanitizeFileName(name: String): String {
        val cleanName = name.removePrefix("SQL Fragment: ")
        return cleanName.replace(Regex("[\\\\/:*?\"<>|]"), "_")
    }
    
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
     * 列结构：Seq(0), Type(1), Request Path(2), Method FQN(3), Class Comment(4), Method Comment(5), StatementID(6), Statement Comment(7)
     * 合并前 6 列（索引 0-5），StatementID 和 Statement Comment 列不合并
     */
    private fun applyExcelMergeRegions(sheet: org.apache.poi.ss.usermodel.Sheet) {
        val processedStartRows = mutableSetOf<Int>()
        
        for ((row, mergeData) in mergeInfo) {
            val (startRow, rowSpan) = mergeData
            
            if (startRow in processedStartRows || rowSpan <= 1) {
                continue
            }
            processedStartRows.add(startRow)
            
            // 对除 StatementID 列（索引 6）和 Statement Comment 列（索引 7）外的所有列进行合并
            for (colIndex in 0 until 6) {
                val mergeRegion = CellRangeAddress(
                    startRow + 1,
                    startRow + rowSpan,
                    colIndex,
                    colIndex
                )
                sheet.addMergedRegion(mergeRegion)
            }
        }
    }
}
