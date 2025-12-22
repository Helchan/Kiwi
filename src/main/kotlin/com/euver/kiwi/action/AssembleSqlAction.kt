package com.euver.kiwi.action

import com.euver.kiwi.application.ExpandStatementUseCase
import com.euver.kiwi.model.AssemblyResult
import com.euver.kiwi.service.ConsoleOutputService
import com.euver.kiwi.service.NotificationService
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag

/**
 * MyBatis SQL 组装 Action
 * 支持在 XML Statement 或 Java Mapper 接口方法上右键触发,组装完整的 SQL 内容
 * 
 * 表示层职责：负责 UI 交互，委托应用层处理业务逻辑
 */
class AssembleSqlAction : AnAction() {

    private val logger = thisLogger()

    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.BGT
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        val psiFile = e.getData(CommonDataKeys.PSI_FILE)
        val editor = e.getData(CommonDataKeys.EDITOR)

        if (project == null || psiFile == null || editor == null) {
            e.presentation.isVisible = false
            e.presentation.isEnabled = false
            return
        }

        // 支持在 XML 文件或 Java 文件中显示菜单项
        when (psiFile) {
            is XmlFile -> {
                e.presentation.isVisible = true
                // 检查是否在 Statement 标签内
                val element = psiFile.findElementAt(editor.caretModel.offset)
                val statementTag = findStatementTag(element)
                e.presentation.isEnabled = statementTag != null
            }
            is PsiJavaFile -> {
                e.presentation.isVisible = true
                val element = psiFile.findElementAt(editor.caretModel.offset)
                
                // 场景1：检查光标是否在 Mapper 接口的方法定义上
                val method = PsiTreeUtil.getParentOfType(element, PsiMethod::class.java)
                if (method != null && isMapperMethod(method)) {
                    e.presentation.isEnabled = true
                    return
                }
                
                // 场景2：检查光标是否在方法调用表达式上（如 myDao.getInfoById()）
                val resolvedMethod = findMapperMethodFromCallExpression(element)
                e.presentation.isEnabled = resolvedMethod != null
            }
            else -> {
                e.presentation.isVisible = false
                e.presentation.isEnabled = false
            }
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val psiFile = e.getData(CommonDataKeys.PSI_FILE) ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return

        logger.info("触发 MyBatis SQL 组装功能")

        try {
            // 根据文件类型选择不同的处理逻辑
            when (psiFile) {
                is XmlFile -> handleXmlFileAction(project, psiFile, editor)
                is PsiJavaFile -> handleJavaFileAction(project, psiFile, editor)
                else -> {
                    val notificationService = NotificationService(project)
                    notificationService.showErrorNotification("不支持的文件类型")
                }
            }
        } catch (e: Exception) {
            logger.error("SQL 组装过程发生异常", e)
            val notificationService = NotificationService(project)
            notificationService.showErrorNotification("SQL 组装失败: ${e.message}")
        }
    }

    /**
     * 处理在 XML 文件中的操作
     */
    private fun handleXmlFileAction(project: Project, psiFile: XmlFile, editor: com.intellij.openapi.editor.Editor) {
        // 查找光标所在的 Statement 标签
        val element = psiFile.findElementAt(editor.caretModel.offset)
        val statementTag = findStatementTag(element)

        if (statementTag == null) {
            NotificationService(project).showErrorNotification("未找到对应的 MyBatis Statement 定义")
            return
        }

        // 使用应用层服务展开 Statement
        val useCase = ExpandStatementUseCase(project)
        val result = useCase.executeFromTag(statementTag, psiFile)
        
        if (result == null) {
            NotificationService(project).showErrorNotification("无法解析 Statement 信息")
            return
        }

        // 输出结果
        outputResult(project, result)
    }

    /**
     * 处理在 Java 文件中的操作
     */
    private fun handleJavaFileAction(project: Project, psiFile: PsiJavaFile, editor: com.intellij.openapi.editor.Editor) {
        val element = psiFile.findElementAt(editor.caretModel.offset)
        
        // 尝试两种场景获取 Mapper 方法
        val (mapperMethod, source) = findMapperMethodInfo(element)
        
        if (mapperMethod == null) {
            NotificationService(project).showErrorNotification("未找到 Mapper 接口方法")
            return
        }

        // 获取方法所在的接口
        val containingClass = mapperMethod.containingClass
        if (containingClass == null || !containingClass.isInterface) {
            NotificationService(project).showErrorNotification("当前方法不在接口中")
            return
        }

        // 获取接口的全限定名（即 namespace）
        val namespace = containingClass.qualifiedName
        if (namespace == null) {
            NotificationService(project).showErrorNotification("无法获取接口的完整路径")
            return
        }

        // 获取方法名（即 statementId）
        val methodName = mapperMethod.name
        
        logger.info("找到 Mapper 方法: $namespace.$methodName (来源: $source)")

        // 使用应用层服务展开 Statement
        val useCase = ExpandStatementUseCase(project)
        val result = useCase.executeFromMapperMethod(namespace, methodName)
        
        if (result == null) {
            val message = "未找到对应的 Statement: $methodName"
            NotificationService(project).showErrorNotification(message)
            ConsoleOutputService(project).outputErrorMessage("$message (Mapper: $namespace)")
            return
        }

        // 输出结果
        outputResult(project, result)
    }
    
    /**
     * 查找 Mapper 方法信息
     * @return Pair<方法, 来源描述>，如果未找到则返回 Pair(null, "")
     */
    private fun findMapperMethodInfo(element: PsiElement?): Pair<PsiMethod?, String> {
        if (element == null) return Pair(null, "")
        
        // 场景1：直接在 Mapper 接口方法定义上
        val directMethod = PsiTreeUtil.getParentOfType(element, PsiMethod::class.java)
        if (directMethod != null && isMapperMethod(directMethod)) {
            return Pair(directMethod, "接口方法定义")
        }
        
        // 场景2：在方法调用表达式上（如 myDao.getInfoById()）
        val resolvedMethod = findMapperMethodFromCallExpression(element)
        if (resolvedMethod != null) {
            return Pair(resolvedMethod, "方法调用")
        }
        
        return Pair(null, "")
    }
    
    /**
     * 从方法调用表达式中查找 Mapper 方法
     * 支持场景：myDao.getInfoById(参数) 中的 getInfoById 调用
     */
    private fun findMapperMethodFromCallExpression(element: PsiElement?): PsiMethod? {
        if (element == null) return null
        
        // 向上查找方法调用表达式
        val methodCallExpr = PsiTreeUtil.getParentOfType(element, PsiMethodCallExpression::class.java)
            ?: return null
        
        // 解析方法调用，获取被调用的方法
        val resolvedMethod = methodCallExpr.resolveMethod() ?: return null
        
        // 检查是否为 Mapper 接口方法
        return if (isMapperMethod(resolvedMethod)) resolvedMethod else null
    }

    /**
     * 输出组装结果
     */
    private fun outputResult(project: Project, result: AssemblyResult) {
        // 显示通知
        NotificationService(project).showAssemblyResultNotification(result)

        // 输出到控制台（无论成功还是失败都显示）
        logger.info("准备输出到控制台窗口...")
        val outputService = ConsoleOutputService(project)
        outputService.outputToConsole(result)
        logger.info("控制台输出完成")

        // 如果存在循环引用，不复制到剪贴板
        if (result.hasCircularReference()) {
            logger.warn("存在循环引用，跳过剪贴板复制")
            return
        }

        // 复制到剪贴板
        val copySuccess = outputService.copyToClipboard(result.assembledSql)
        if (!copySuccess) {
            logger.warn("复制到剪贴板失败,但控制台输出成功")
        }
    }

    /**
     * 判断是否为 Mapper 接口方法
     * 简单判断：在接口中定义的方法即认为是 Mapper 方法
     */
    private fun isMapperMethod(method: PsiMethod): Boolean {
        val containingClass = method.containingClass ?: return false
        return containingClass.isInterface
    }

    /**
     * 查找包含当前元素的 Statement 标签
     */
    private fun findStatementTag(element: PsiElement?): XmlTag? {
        if (element == null) return null

        // 向上查找父级标签
        var currentTag = PsiTreeUtil.getParentOfType(element, XmlTag::class.java)

        while (currentTag != null) {
            val tagName = currentTag.name
            if (tagName in setOf("select", "insert", "update", "delete")) {
                return currentTag
            }
            currentTag = PsiTreeUtil.getParentOfType(currentTag, XmlTag::class.java)
        }

        return null
    }
}
