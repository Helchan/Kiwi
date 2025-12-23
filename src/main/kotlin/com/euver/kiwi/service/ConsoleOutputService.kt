package com.euver.kiwi.service

import com.euver.kiwi.domain.model.MethodInfo
import com.euver.kiwi.domain.model.TopCallerWithStatement
import com.euver.kiwi.model.AssemblyResult
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.ContentFactory
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.text.SimpleDateFormat
import java.util.*

/**
 * 控制台输出服务
 * 负责将组装结果输出到 IDE 可见的控制台窗口
 */
class ConsoleOutputService(private val project: Project) {

    private val logger = thisLogger()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss")

    companion object {
        private const val TOOL_WINDOW_ID = "Kiwi Console"
        
        // 缓存各标签页的 ConsoleView，用于追加内容
        private val consoleViewCache = mutableMapOf<String, ConsoleView>()
    }

    /**
     * 输出组装结果到控制台工具窗口
     */
    fun outputToConsole(result: AssemblyResult) {
        val output = buildConsoleOutput(result)
        val contentType = if (result.isFullySuccessful()) {
            ConsoleViewContentType.NORMAL_OUTPUT
        } else {
            ConsoleViewContentType.ERROR_OUTPUT
        }
        showInToolWindow(output, contentType)
    }

    /**
     * 直接输出错误信息到控制台工具窗口
     */
    fun outputErrorMessage(message: String) {
        val output = buildErrorOutput(message)
        showInToolWindow(output, ConsoleViewContentType.ERROR_OUTPUT)
    }

    /**
     * 输出方法信息到控制台工具窗口
     */
    fun outputMethodInfo(methodInfo: MethodInfo) {
        val output = buildMethodInfoOutput(methodInfo)
        showInToolWindow(output, ConsoleViewContentType.NORMAL_OUTPUT, "Method Info")
    }

    /**
     * 输出顶层调用者信息到控制台工具窗口
     */
    fun outputTopCallersInfo(sourceMethodName: String, topCallers: List<MethodInfo>) {
        val output = buildTopCallersOutput(sourceMethodName, topCallers)
        showInToolWindow(output, ConsoleViewContentType.NORMAL_OUTPUT, "Top Callers")
    }

    /**
     * 输出带 Statement ID 的顶层调用者信息到控制台工具窗口（SQL 片段模式）
     * 与 TopCallersTreeTableDialog 显示的列保持一致
     */
    fun outputTopCallersInfoWithStatements(
        sourceMethodName: String,
        topCallersWithStatements: List<TopCallerWithStatement>
    ) {
        val output = buildTopCallersOutputWithStatements(sourceMethodName, topCallersWithStatements)
        showInToolWindow(output, ConsoleViewContentType.NORMAL_OUTPUT, "Top Callers")
    }
    
    /**
     * 输出通用信息到控制台工具窗口（追加模式）
     */
    fun output(message: String, tabTitle: String = "Top Callers") {
        appendToToolWindow(message + "\n", ConsoleViewContentType.NORMAL_OUTPUT, tabTitle)
    }

    /**
     * 复制到系统剪贴板
     */
    fun copyToClipboard(content: String): Boolean {
        return try {
            val clipboard = Toolkit.getDefaultToolkit().systemClipboard
            val selection = StringSelection(content)
            clipboard.setContents(selection, selection)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 在工具窗口中显示输出内容（替换模式）
     */
    private fun showInToolWindow(output: String, contentType: ConsoleViewContentType, tabTitle: String = "Statement Result") {
        logger.info("准备显示控制台输出窗口...")
        
        ApplicationManager.getApplication().invokeLater {
            val toolWindowManager = ToolWindowManager.getInstance(project)
            val toolWindow = toolWindowManager.getToolWindow(TOOL_WINDOW_ID)
            
            if (toolWindow == null) {
                logger.warn("未找到工具窗口: $TOOL_WINDOW_ID，请检查 plugin.xml 配置")
                return@invokeLater
            }
            
            logger.info("找到工具窗口，准备输出内容...")

            val consoleView = createConsoleView()
            val contentFactory = ContentFactory.getInstance()
            val content = contentFactory.createContent(consoleView.component, tabTitle, false)

            toolWindow.contentManager.removeAllContents(true)
            // 清空缓存
            consoleViewCache.clear()
            toolWindow.contentManager.addContent(content)
            // 缓存新的 ConsoleView
            consoleViewCache[tabTitle] = consoleView

            consoleView.print(output, contentType)

            toolWindow.show {
                logger.info("控制台窗口已显示")
            }
        }
    }
    
    /**
     * 追加内容到工具窗口（不清空现有内容）
     */
    private fun appendToToolWindow(output: String, contentType: ConsoleViewContentType, tabTitle: String = "Top Callers") {
        logger.info("准备追加控制台输出内容...")
        
        ApplicationManager.getApplication().invokeLater {
            val toolWindowManager = ToolWindowManager.getInstance(project)
            val toolWindow = toolWindowManager.getToolWindow(TOOL_WINDOW_ID)
            
            if (toolWindow == null) {
                logger.warn("未找到工具窗口: $TOOL_WINDOW_ID，请检查 plugin.xml 配置")
                return@invokeLater
            }
            
            // 尝试从缓存获取现有的 ConsoleView
            val cachedConsoleView = consoleViewCache[tabTitle]
            if (cachedConsoleView != null) {
                // 直接追加到现有的 ConsoleView
                cachedConsoleView.print(output, contentType)
                toolWindow.show()
                return@invokeLater
            }
            
            // 如果没有缓存，创建新的 ConsoleView
            val consoleView = createConsoleView()
            val contentFactory = ContentFactory.getInstance()
            val content = contentFactory.createContent(consoleView.component, tabTitle, false)
            
            toolWindow.contentManager.removeAllContents(true)
            consoleViewCache.clear()
            toolWindow.contentManager.addContent(content)
            consoleViewCache[tabTitle] = consoleView
            
            consoleView.print(output, contentType)
            toolWindow.show()
        }
    }

    /**
     * 创建控制台视图
     */
    private fun createConsoleView(): ConsoleView {
        return TextConsoleBuilderFactory.getInstance()
            .createBuilder(project)
            .console
    }

    /**
     * 构建控制台输出内容
     */
    private fun buildConsoleOutput(result: AssemblyResult): String {
        val builder = StringBuilder()
        val statementInfo = result.statementInfo

        builder.appendLine("========== MyBatis SQL 组装结果 ==========")
        builder.appendLine("时间: ${dateFormat.format(Date())}")
        builder.appendLine("Statement ID: ${statementInfo.statementId}")
        builder.appendLine("Mapper Namespace: ${statementInfo.namespace}")
        builder.appendLine("Source File: ${statementInfo.sourceFile.virtualFile.path}")
        builder.appendLine("------------------------------------------")
        builder.appendLine(result.assembledSql)
        builder.appendLine("------------------------------------------")
        builder.appendLine("组装统计:")
        builder.appendLine("- 替换的 include 标签数: ${result.replacedIncludeCount}")
        builder.appendLine("- 未找到的 SQL 片段数: ${result.missingFragments.size}")

        // 如果有未找到的片段,列出详情
        if (result.missingFragments.isNotEmpty()) {
            builder.appendLine()
            builder.appendLine("未找到的 SQL 片段详情:")
            result.missingFragments.forEachIndexed { index, info ->
                builder.appendLine("${index + 1}. refid=\"${info.refid}\" (引用位置: ${info.statementId})")
                if (info.expectedNamespace != null) {
                    builder.appendLine("   预期 namespace: ${info.expectedNamespace}")
                }
                builder.appendLine("   原因: ${info.reason}")
            }
        }

        // 如果有循环引用,列出详情
        if (result.circularReferences.isNotEmpty()) {
            builder.appendLine()
            builder.appendLine("检测到的循环引用:")
            result.circularReferences.forEachIndexed { index, circularRef ->
                val (simplifiedCycle, labelMappings) = circularRef.getFormattedDescription()
                builder.appendLine("${index + 1}. $simplifiedCycle")
                labelMappings.forEach { mapping ->
                    builder.appendLine("   $mapping")
                }
            }
        }

        builder.appendLine("==========================================")
        return builder.toString()
    }

    /**
     * 构建通用错误输出内容
     */
    private fun buildErrorOutput(message: String): String {
        val builder = StringBuilder()

        builder.appendLine("========== MyBatis SQL 组装结果 ==========")
        builder.appendLine("时间: ${dateFormat.format(Date())}")
        builder.appendLine("------------------------------------------")
        builder.appendLine(message)
        builder.appendLine("==========================================")

        return builder.toString()
    }

    /**
     * 构建方法信息输出内容
     */
    private fun buildMethodInfoOutput(methodInfo: MethodInfo): String {
        val builder = StringBuilder()

        builder.appendLine("========== 方法基础信息 ==========")
        builder.appendLine("时间: ${dateFormat.format(Date())}")
        builder.appendLine("------------------------------------------")
        builder.append(methodInfo.toFormattedString())
        builder.appendLine("==========================================")

        return builder.toString()
    }

    /**
     * 构建顶层调用者输出内容（表格格式）
     * 列顺序与 TreeTable 保持一致：序号、类型、请求路径、方法、类功能注释、方法功能注释、包路径
     */
    private fun buildTopCallersOutput(sourceMethodName: String, topCallers: List<MethodInfo>): String {
        val builder = StringBuilder()

        builder.appendLine("========== 顶层调用者分析结果 ==========")
        builder.appendLine("时间: ${dateFormat.format(Date())}")
        builder.appendLine("源位置: $sourceMethodName")
        builder.appendLine("------------------------------------------")

        if (topCallers.isEmpty()) {
            builder.appendLine("未找到顶层调用者，当前方法没有被其他方法调用。")
        } else {
            builder.appendLine("共找到 ${topCallers.size} 个顶层调用者:")
            builder.appendLine()
            
            // 构建表格数据
            val headers = listOf("Seq", "Type", "Request Path", "Method", "Class Comment", "Method Comment", "Package")
            val rows = topCallers.mapIndexed { index, methodInfo ->
                listOf(
                    (index + 1).toString(),
                    if (methodInfo.isExternalInterface()) "API" else "Normal",
                    methodInfo.requestPath.ifEmpty { "-" },
                    "${methodInfo.simpleClassName}.${methodInfo.methodSignature}",
                    methodInfo.classComment.ifEmpty { "-" },
                    methodInfo.functionComment.ifEmpty { "-" },
                    methodInfo.packageName.ifEmpty { "-" }
                )
            }
            
            // 计算每列的最大宽度
            val columnWidths = headers.indices.map { colIndex ->
                val headerWidth = getDisplayWidth(headers[colIndex])
                val maxDataWidth = rows.maxOfOrNull { getDisplayWidth(it[colIndex]) } ?: 0
                maxOf(headerWidth, maxDataWidth)
            }
            
            // 构建表格
            val tableOutput = buildAsciiTable(headers, rows, columnWidths)
            builder.append(tableOutput)
        }

        builder.appendLine("==========================================")

        return builder.toString()
    }

    /**
     * 构建带 Statement ID 的顶层调用者输出内容（SQL 片段模式）
     * 列顺序与 TreeTable 保持一致：Seq、Type、Request Path、Method FQN、Class Comment、Method Comment、StatementID
     * 注意：包路径已包含在 Method FQN 中，因此使用全限定方法名代替
     */
    private fun buildTopCallersOutputWithStatements(
        sourceMethodName: String,
        topCallersWithStatements: List<TopCallerWithStatement>
    ): String {
        val builder = StringBuilder()

        builder.appendLine("========== 顶层调用者分析结果 ==========")
        builder.appendLine("时间: ${dateFormat.format(Date())}")
        builder.appendLine("源位置: $sourceMethodName")
        builder.appendLine("------------------------------------------")

        if (topCallersWithStatements.isEmpty()) {
            builder.appendLine("未找到顶层调用者，当前 SQL 片段没有被任何 Statement 引用。")
        } else {
            // 按顶层调用者分组，统计唯一的顶层调用者数量
            val groupedByMethod = topCallersWithStatements.groupBy { it.methodInfo.qualifiedName }
            builder.appendLine("共找到 ${groupedByMethod.size} 个顶层调用者:")
            builder.appendLine()
            
            // 构建表格数据（与 TreeTable 列保持一致）
            val headers = listOf("Seq", "Type", "Request Path", "Method FQN", "Class Comment", "Method Comment", "StatementID")
            val rows = mutableListOf<List<String>>()
            var seqNumber = 0
            
            for ((_, group) in groupedByMethod) {
                seqNumber++
                val methodInfo = group.first().methodInfo
                val statementIds = group.map { it.statementId }.distinct()
                
                // 每个 StatementID 输出一行
                for (statementId in statementIds) {
                    rows.add(listOf(
                        seqNumber.toString(),
                        if (methodInfo.isExternalInterface()) "API" else "Normal",
                        methodInfo.requestPath.ifEmpty { "-" },
                        "${methodInfo.simpleClassName}.${methodInfo.methodSignature}",
                        methodInfo.classComment.ifEmpty { "-" },
                        methodInfo.functionComment.ifEmpty { "-" },
                        statementId
                    ))
                }
            }
            
            // 计算每列的最大宽度
            val columnWidths = headers.indices.map { colIndex ->
                val headerWidth = getDisplayWidth(headers[colIndex])
                val maxDataWidth = rows.maxOfOrNull { getDisplayWidth(it[colIndex]) } ?: 0
                maxOf(headerWidth, maxDataWidth)
            }
            
            // 构建表格
            val tableOutput = buildAsciiTable(headers, rows, columnWidths)
            builder.append(tableOutput)
        }

        builder.appendLine("==========================================")

        return builder.toString()
    }
    
    /**
     * 构建 ASCII 表格
     */
    private fun buildAsciiTable(
        headers: List<String>,
        rows: List<List<String>>,
        columnWidths: List<Int>
    ): String {
        val builder = StringBuilder()
        
        // 构建分隔线
        val separator = buildSeparatorLine(columnWidths)
        
        // 表头
        builder.appendLine(separator)
        builder.appendLine(buildDataRow(headers, columnWidths))
        builder.appendLine(separator)
        
        // 数据行
        rows.forEach { row ->
            builder.appendLine(buildDataRow(row, columnWidths))
        }
        
        // 底部分隔线
        builder.appendLine(separator)
        
        return builder.toString()
    }
    
    /**
     * 构建分隔线
     */
    private fun buildSeparatorLine(columnWidths: List<Int>): String {
        val builder = StringBuilder("+")
        columnWidths.forEach { width ->
            builder.append("-".repeat(width + 2)) // +2 为左右内边距
            builder.append("+")
        }
        return builder.toString()
    }
    
    /**
     * 构建数据行
     */
    private fun buildDataRow(data: List<String>, columnWidths: List<Int>): String {
        val builder = StringBuilder("|")
        data.forEachIndexed { index, value ->
            val width = columnWidths[index]
            val paddedValue = padString(value, width)
            builder.append(" $paddedValue ")
            builder.append("|")
        }
        return builder.toString()
    }
    
    /**
     * 字符串填充到指定宽度（左对齐）
     * 支持中文字符（中文字符占用 2 个显示宽度）
     */
    private fun padString(str: String, targetWidth: Int): String {
        val currentWidth = getDisplayWidth(str)
        val padding = targetWidth - currentWidth
        return if (padding > 0) {
            str + " ".repeat(padding)
        } else {
            str
        }
    }
    
    /**
     * 获取字符串的显示宽度
     * 中文字符占 2 个宽度，英文字符占 1 个宽度
     */
    private fun getDisplayWidth(str: String): Int {
        return str.sumOf { char ->
            if (char.code > 127) 2.toInt() else 1.toInt()
        }
    }
}
