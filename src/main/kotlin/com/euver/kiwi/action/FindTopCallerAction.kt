package com.euver.kiwi.action

import com.euver.kiwi.domain.model.MethodInfo
import com.euver.kiwi.domain.service.MethodInfoExtractorService
import com.euver.kiwi.domain.service.TopCallerFinderService
import com.euver.kiwi.service.ConsoleOutputService
import com.euver.kiwi.service.NotificationService
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag

/**
 * 查找顶层调用者 Action
 * 支持在 Java 方法或 MyBatis XML Statement 上右键触发
 * 查找所有顶层调用者并输出其方法信息
 *
 * 表示层职责：负责 UI 交互，委托领域层处理业务逻辑
 */
class FindTopCallerAction : AnAction() {

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

        when (psiFile) {
            is XmlFile -> {
                e.presentation.isVisible = true
                val element = psiFile.findElementAt(editor.caretModel.offset)
                val statementTag = findStatementTag(element)
                e.presentation.isEnabled = statementTag != null
            }
            is PsiJavaFile -> {
                e.presentation.isVisible = true
                val element = psiFile.findElementAt(editor.caretModel.offset)
                val method = PsiTreeUtil.getParentOfType(element, PsiMethod::class.java)
                e.presentation.isEnabled = method != null
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

        logger.info("触发 Find Top Caller 功能")

        when (psiFile) {
            is XmlFile -> handleXmlFileAction(project, psiFile, editor)
            is PsiJavaFile -> handleJavaFileAction(project, psiFile, editor)
            else -> {
                NotificationService(project).showErrorNotification("不支持的文件类型")
            }
        }
    }

    /**
     * 处理 Java 文件中的操作
     * 优先检测光标是否在方法调用表达式上，如果是则查找被调用方法的顶层调用者
     * 否则查找包含方法的顶层调用者
     */
    private fun handleJavaFileAction(
        project: Project,
        psiFile: PsiJavaFile,
        editor: com.intellij.openapi.editor.Editor
    ) {
        val element = psiFile.findElementAt(editor.caretModel.offset)
        
        // 优先尝试获取被调用的方法（当光标在方法调用表达式上时）
        val calledMethod = findCalledMethodAtCaret(element)
        if (calledMethod != null) {
            logger.info("检测到方法调用表达式，查找被调用方法的顶层调用者")
            findAndOutputTopCallers(project, calledMethod)
            return
        }
        
        // 否则获取光标所在的包含方法
        val containingMethod = PsiTreeUtil.getParentOfType(element, PsiMethod::class.java)
        if (containingMethod == null) {
            NotificationService(project).showErrorNotification("未找到光标所在的方法")
            return
        }

        findAndOutputTopCallers(project, containingMethod)
    }

    /**
     * 查找光标位置的方法调用表达式所调用的方法
     * 当用户选中一个方法调用（如 getProductById(id)）时，返回被调用的方法
     */
    private fun findCalledMethodAtCaret(element: PsiElement?): PsiMethod? {
        if (element == null) return null

        // 查找方法调用表达式
        val methodCall = PsiTreeUtil.getParentOfType(element, PsiMethodCallExpression::class.java, false)
        if (methodCall != null) {
            // 获取方法引用表达式
            val methodExpression = methodCall.methodExpression
            // 解析引用，获取实际被调用的方法
            val resolved = methodExpression.resolve()
            if (resolved is PsiMethod) {
                return resolved
            }
        }

        // 检查是否直接在方法引用上（可能是方法引用表达式的一部分）
        val refExpression = PsiTreeUtil.getParentOfType(element, PsiReferenceExpression::class.java, false)
        if (refExpression != null) {
            val parent = refExpression.parent
            if (parent is PsiMethodCallExpression) {
                val resolved = refExpression.resolve()
                if (resolved is PsiMethod) {
                    return resolved
                }
            }
        }

        return null
    }

    /**
     * 处理 XML 文件中的操作
     */
    private fun handleXmlFileAction(
        project: Project,
        psiFile: XmlFile,
        editor: com.intellij.openapi.editor.Editor
    ) {
        val element = psiFile.findElementAt(editor.caretModel.offset)
        val statementTag = findStatementTag(element)

        if (statementTag == null) {
            NotificationService(project).showErrorNotification("未找到对应的 MyBatis Statement 定义")
            return
        }

        val namespace = findMapperNamespace(statementTag)
        val statementId = statementTag.getAttributeValue("id")

        if (namespace == null || statementId == null) {
            NotificationService(project).showErrorNotification("无法获取 Statement 的 namespace 或 id")
            return
        }

        val mapperMethod = findMapperMethod(project, namespace, statementId)
        if (mapperMethod == null) {
            NotificationService(project).showErrorNotification("未找到对应的 Mapper 接口方法: $namespace.$statementId")
            return
        }

        findAndOutputTopCallers(project, mapperMethod)
    }

    /**
     * 查找并输出顶层调用者
     */
    private fun findAndOutputTopCallers(project: Project, method: PsiMethod) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "查找顶层调用者...", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                indicator.text = "正在分析调用链..."

                val topCallerService = TopCallerFinderService(project)
                val topCallers = topCallerService.findTopCallers(method)

                ApplicationManager.getApplication().invokeLater {
                    outputTopCallers(project, method, topCallers)
                }
            }
        })
    }

    /**
     * 输出顶层调用者信息
     */
    private fun outputTopCallers(project: Project, sourceMethod: PsiMethod, topCallers: Set<PsiMethod>) {
        val consoleOutputService = ConsoleOutputService(project)
        val methodInfoExtractorService = MethodInfoExtractorService()

        val sourceMethodName = ApplicationManager.getApplication().runReadAction<String> {
            "${sourceMethod.containingClass?.qualifiedName}.${sourceMethod.name}"
        }

        if (topCallers.isEmpty()) {
            consoleOutputService.outputTopCallersInfo(sourceMethodName, emptyList())
            NotificationService(project).showInfoNotification("未找到顶层调用者，当前方法没有被其他方法调用")
            return
        }

        val methodInfoList = topCallers.mapNotNull { caller ->
            try {
                ApplicationManager.getApplication().runReadAction<MethodInfo> {
                    methodInfoExtractorService.extractMethodInfo(caller)
                }
            } catch (e: Exception) {
                logger.warn("提取方法信息失败: ${caller.name}", e)
                null
            }
        }

        consoleOutputService.outputTopCallersInfo(sourceMethodName, methodInfoList)
        NotificationService(project).showInfoNotification("找到 ${topCallers.size} 个顶层调用者")
    }

    /**
     * 查找 Mapper 接口中对应的方法
     */
    private fun findMapperMethod(project: Project, namespace: String, methodName: String): PsiMethod? {
        return ApplicationManager.getApplication().runReadAction<PsiMethod?> {
            val psiFacade = JavaPsiFacade.getInstance(project)
            val scope = GlobalSearchScope.projectScope(project)
            val mapperClass = psiFacade.findClass(namespace, scope) ?: return@runReadAction null
            mapperClass.findMethodsByName(methodName, false).firstOrNull()
        }
    }

    /**
     * 查找包含当前元素的 Statement 标签（select/insert/update/delete）
     */
    private fun findStatementTag(element: PsiElement?): XmlTag? {
        if (element == null) return null

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

    /**
     * 获取 Mapper XML 文件的 namespace
     */
    private fun findMapperNamespace(statementTag: XmlTag): String? {
        var parent = statementTag.parent
        while (parent is XmlTag) {
            if (parent.name == "mapper") {
                return parent.getAttributeValue("namespace")
            }
            parent = parent.parent
        }
        return null
    }
}
