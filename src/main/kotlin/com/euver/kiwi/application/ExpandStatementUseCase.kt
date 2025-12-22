package com.euver.kiwi.application

import com.euver.kiwi.domain.service.SqlFragmentResolver
import com.euver.kiwi.domain.service.StatementExpanderService
import com.euver.kiwi.infrastructure.resolver.SqlFragmentResolverImpl
import com.euver.kiwi.model.AssemblyResult
import com.euver.kiwi.model.StatementInfo
import com.euver.kiwi.parser.MyBatisXmlParser
import com.euver.kiwi.service.MapperIndexService
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag

/**
 * 展开 Statement 用例
 * 应用层服务，协调领域服务和基础设施层完成 Statement 展开功能
 * 
 * 提供统一的入口供各种触发方式（Action、API 等）调用
 */
class ExpandStatementUseCase(private val project: Project) {
    
    private val logger = thisLogger()
    private val parser = MyBatisXmlParser()
    private val fragmentResolver: SqlFragmentResolver = SqlFragmentResolverImpl(project, parser)
    private val expanderService = StatementExpanderService(fragmentResolver)
    private val indexService by lazy { MapperIndexService.getInstance(project) }
    
    /**
     * 展开指定的 Statement
     * 
     * @param statementInfo Statement 信息
     * @return 组装结果
     */
    fun execute(statementInfo: StatementInfo): AssemblyResult {
        logger.info("执行 Statement 展开用例: ${statementInfo.namespace}.${statementInfo.statementId}")
        return expanderService.expand(statementInfo)
    }
    
    /**
     * 从 XML 标签展开 Statement
     * 
     * @param tag XML 标签
     * @param xmlFile XML 文件
     * @return 组装结果，如果标签不是有效的 Statement 则返回 null
     */
    fun executeFromTag(tag: XmlTag, xmlFile: XmlFile): AssemblyResult? {
        val statementInfo = parser.extractStatementFromTag(tag, xmlFile)
        if (statementInfo == null) {
            logger.warn("无法从标签提取 Statement 信息")
            return null
        }
        return execute(statementInfo)
    }
    
    /**
     * 从 Mapper 方法名展开 Statement
     * 
     * @param namespace Mapper 接口的全限定名
     * @param methodName 方法名
     * @return 组装结果，如果找不到对应 Statement 则返回 null
     */
    fun executeFromMapperMethod(namespace: String, methodName: String): AssemblyResult? {
        // 查找对应的 XML 文件
        val xmlFile = indexService.findMapperFileByNamespace(namespace)
        if (xmlFile == null) {
            logger.warn("未找到对应的 Mapper XML 文件: $namespace")
            return null
        }
        
        // 查找对应的 Statement
        val statementInfo = parser.findStatement(xmlFile, methodName)
        if (statementInfo == null) {
            logger.warn("未找到对应的 Statement: $methodName (Mapper: $namespace)")
            return null
        }
        
        return execute(statementInfo)
    }
    
    /**
     * 展开指定内容
     * 简化版本，只展开内容不返回完整的组装结果
     * 
     * @param content 原始内容
     * @param currentFile 当前 XML 文件
     * @param currentNamespace 当前 namespace
     * @return 展开后的内容
     */
    fun expandContent(content: String, currentFile: XmlFile, currentNamespace: String): String {
        return expanderService.expandContent(content, currentFile, currentNamespace)
    }
    
    /**
     * 获取领域服务实例
     * 供需要直接使用领域服务的场景使用
     */
    fun getExpanderService(): StatementExpanderService {
        return expanderService
    }
}
