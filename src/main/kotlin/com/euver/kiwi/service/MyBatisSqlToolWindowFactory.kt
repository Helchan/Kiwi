package com.euver.kiwi.service

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory

/**
 * MyBatis SQL 工具窗口工厂
 * 用于在 IDE 底部创建可见的控制台输出窗口
 */
class MyBatisSqlToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        // 初始内容由 ConsoleOutputService 动态添加
    }

    override fun shouldBeAvailable(project: Project): Boolean = true
}
