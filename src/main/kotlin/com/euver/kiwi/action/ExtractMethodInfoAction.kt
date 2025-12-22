package com.euver.kiwi.action

import com.euver.kiwi.domain.model.MethodInfo
import com.euver.kiwi.domain.service.MethodInfoExtractorService
import com.euver.kiwi.service.ConsoleOutputService
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.util.PsiTreeUtil

/**
 * 提取方法信息 Action
 * 在方法上右键触发，将方法的基础信息输出到控制台
 * 
 * 表示层职责：负责 UI 交互，委托领域层处理业务逻辑
 */
class ExtractMethodInfoAction : AnAction() {

    private val logger = thisLogger()
    private val methodInfoExtractorService = MethodInfoExtractorService()

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

        // 只在 Java 文件中显示
        if (psiFile !is PsiJavaFile) {
            e.presentation.isVisible = false
            e.presentation.isEnabled = false
            return
        }

        e.presentation.isVisible = true
        
        // 检查光标是否在方法调用或方法定义上
        val element = psiFile.findElementAt(editor.caretModel.offset)
        val targetMethod = findTargetMethod(element)
        e.presentation.isEnabled = targetMethod != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val psiFile = e.getData(CommonDataKeys.PSI_FILE) as? PsiJavaFile ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return

        logger.info("触发提取方法信息功能")

        // 查找光标所指向的目标方法（优先方法调用，其次方法定义）
        val element = psiFile.findElementAt(editor.caretModel.offset)
        val method = findTargetMethod(element)

        if (method == null) {
            logger.warn("未找到光标所指向的方法")
            return
        }

        try {
            // 提取方法信息
            val methodInfo = methodInfoExtractorService.extractMethodInfo(method)
            
            // 输出到控制台
            val consoleOutputService = ConsoleOutputService(project)
            consoleOutputService.outputMethodInfo(methodInfo)
            
            logger.info("方法信息已输出到控制台")
        } catch (ex: Exception) {
            logger.error("提取方法信息过程发生异常", ex)
        }
    }

    /**
     * 查找光标所指向的目标方法
     * 优先级：
     * 1. 如果光标在方法调用表达式上，返回被调用的方法
     * 2. 否则返回光标所在的方法定义
     */
    private fun findTargetMethod(element: com.intellij.psi.PsiElement?): PsiMethod? {
        if (element == null) return null

        // 优先检查是否在方法调用上
        // 向上查找 PsiMethodCallExpression（方法调用表达式）
        val methodCall = PsiTreeUtil.getParentOfType(element, PsiMethodCallExpression::class.java, false)
        if (methodCall != null) {
            // 检查光标是否在方法名部分（而不是参数部分）
            val methodExpression = methodCall.methodExpression
            val referenceNameElement = methodExpression.referenceNameElement
            if (referenceNameElement != null && isElementWithinRange(element, referenceNameElement)) {
                val resolvedMethod = methodCall.resolveMethod()
                if (resolvedMethod != null) {
                    logger.info("识别到方法调用: ${resolvedMethod.name}")
                    return resolvedMethod
                }
            }
        }

        // 检查是否在方法引用上（如 this::methodName 或 ClassName::methodName）
        val methodRef = PsiTreeUtil.getParentOfType(element, com.intellij.psi.PsiMethodReferenceExpression::class.java, false)
        if (methodRef != null) {
            val resolvedMethod = methodRef.resolve() as? PsiMethod
            if (resolvedMethod != null) {
                logger.info("识别到方法引用: ${resolvedMethod.name}")
                return resolvedMethod
            }
        }

        // 退回到获取外层方法定义
        return PsiTreeUtil.getParentOfType(element, PsiMethod::class.java)
    }

    /**
     * 检查 element 是否在 container 的范围内
     */
    private fun isElementWithinRange(element: com.intellij.psi.PsiElement, container: com.intellij.psi.PsiElement): Boolean {
        val elementOffset = element.textRange.startOffset
        val containerRange = container.textRange
        return elementOffset >= containerRange.startOffset && elementOffset <= containerRange.endOffset
    }
}
