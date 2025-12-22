package com.euver.kiwi.service

import com.euver.kiwi.domain.model.MethodInfo
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
     * 在工具窗口中显示输出内容
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
            toolWindow.contentManager.addContent(content)

            consoleView.print(output, contentType)

            toolWindow.show {
                logger.info("控制台窗口已显示")
            }
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
     * 构建顶层调用者输出内容
     */
    private fun buildTopCallersOutput(sourceMethodName: String, topCallers: List<MethodInfo>): String {
        val builder = StringBuilder()

        builder.appendLine("========== 顶层调用者分析结果 ==========")
        builder.appendLine("时间: ${dateFormat.format(Date())}")
        builder.appendLine("源方法: $sourceMethodName")
        builder.appendLine("------------------------------------------")

        if (topCallers.isEmpty()) {
            builder.appendLine("未找到顶层调用者，当前方法没有被其他方法调用。")
        } else {
            builder.appendLine("共找到 ${topCallers.size} 个顶层调用者:")
            builder.appendLine()

            topCallers.forEachIndexed { index, methodInfo ->
                builder.appendLine("【${index + 1}】${methodInfo.qualifiedName}")
                builder.appendLine("    请求类型: ${methodInfo.httpMethod.ifEmpty { "(无)" }}")
                builder.appendLine("    请求路径: ${methodInfo.requestPath.ifEmpty { "(无)" }}")
                builder.appendLine("    类功能注释: ${methodInfo.classComment.ifEmpty { "(无)" }}")
                builder.appendLine("    方法功能注释: ${methodInfo.functionComment.ifEmpty { "(无)" }}")
                builder.appendLine()
            }
        }

        builder.appendLine("==========================================")

        return builder.toString()
    }
}
