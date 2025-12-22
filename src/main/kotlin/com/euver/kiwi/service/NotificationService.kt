package com.euver.kiwi.service

import com.euver.kiwi.model.AssemblyResult
import com.intellij.notification.Notification
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project

/**
 * 通知服务
 * 负责显示不同级别的用户通知
 */
class NotificationService(private val project: Project) {

    private val notificationGroupId = "MyBatis.SQL.Assembly"

    /**
     * 显示组装结果通知
     */
    fun showAssemblyResultNotification(result: AssemblyResult) {
        when {
            result.hasCircularReference() -> showCircularReferenceNotification(result)
            result.isPartiallySuccessful() -> showPartialSuccessNotification(result)
            result.isFullySuccessful() -> showSuccessNotification()
        }
    }

    /**
     * 显示成功通知
     */
    private fun showSuccessNotification() {
        val notification = createNotification(
            title = "Kiwi Tips",
            content = "内容组装成功，已拷贝到剪切板!",
            type = NotificationType.INFORMATION
        )
        notification.notify(project)
    }

    /**
     * 显示部分成功通知(有未找到的片段,但没有循环引用)
     */
    private fun showPartialSuccessNotification(result: AssemblyResult) {
        val content = buildMissingFragmentsContent(result)
        
        val notification = createNotification(
            title = "Kiwi Tips",
            content = content,
            type = NotificationType.WARNING
        )
        notification.notify(project)
    }

    /**
     * 构建未找到片段的通知内容(使用 HTML 格式支持换行)
     * 格式: 内容组装完毕,以下 SQL 片段未找到:<br>
     *       1. xxx.fragment1<br>
     *       2. xxx.fragment2
     */
    private fun buildMissingFragmentsContent(result: AssemblyResult): String {
        val builder = StringBuilder()
        builder.append("内容组装完毕,以下 SQL 片段未找到:")
        
        result.missingFragments.take(10).forEachIndexed { index, fragment ->
            val fullRefId = if (fragment.expectedNamespace != null) {
                "${fragment.expectedNamespace}.${fragment.refid}"
            } else {
                fragment.refid
            }
            builder.append("<br>${index + 1}. $fullRefId")
        }
        
        if (result.missingFragments.size > 10) {
            builder.append("<br>... 还有 ${result.missingFragments.size - 10} 个未找到的片段")
        }
        
        return builder.toString()
    }

    /**
     * 显示循环引用失败通知
     */
    private fun showCircularReferenceNotification(result: AssemblyResult) {
        val content = buildCircularReferenceContent(result)
        
        val notification = createNotification(
            title = "Kiwi Tips",
            content = content,
            type = NotificationType.ERROR
        )
        notification.notify(project)
    }

    /**
     * 构建循环引用通知内容(使用 HTML 格式支持换行)
     * 格式: 内容组装失败，存在循环引用：A → B → A<br>
     *       A: xxx.Base_Column_List<br>
     *       B: xxx.testRecur
     */
    private fun buildCircularReferenceContent(result: AssemblyResult): String {
        val builder = StringBuilder()
        
        result.circularReferences.forEachIndexed { index, circularRef ->
            if (index > 0) {
                builder.append("<br><br>")
            }
            
            val (simplifiedCycle, labelMappings) = circularRef.getFormattedDescription()
            builder.append("内容组装失败，存在循环引用：$simplifiedCycle")
            
            labelMappings.forEach { mapping ->
                builder.append("<br>$mapping")
            }
        }
        
        return builder.toString()
    }

    /**
     * 显示信息通知
     */
    fun showInfoNotification(message: String) {
        val notification = createNotification(
            title = "Kiwi Tips",
            content = message,
            type = NotificationType.INFORMATION
        )
        notification.notify(project)
    }

    /**
     * 显示错误通知
     */
    fun showErrorNotification(message: String) {
        val notification = createNotification(
            title = "Kiwi Tips",
            content = message,
            type = NotificationType.ERROR
        )
        notification.notify(project)
    }

    /**
     * 创建通知对象
     */
    private fun createNotification(
        title: String,
        content: String,
        type: NotificationType
    ): Notification {
        return NotificationGroupManager.getInstance()
            .getNotificationGroup(notificationGroupId)
            .createNotification(title, content, type)
    }
}
