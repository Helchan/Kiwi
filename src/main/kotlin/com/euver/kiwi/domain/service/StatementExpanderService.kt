package com.euver.kiwi.domain.service

import com.euver.kiwi.domain.model.ExpandContext
import com.euver.kiwi.model.AssemblyResult
import com.euver.kiwi.model.MissingFragmentInfo
import com.euver.kiwi.model.SqlFragmentInfo
import com.euver.kiwi.model.StatementInfo
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.psi.xml.XmlFile

/**
 * Statement 展开服务
 * 领域层核心服务，负责递归展开 Statement 中的 include 标签
 * 
 * 这是一个纯领域服务，通过依赖注入获取外部依赖（SqlFragmentResolver）
 * 可被其他功能模块复用
 */
class StatementExpanderService(
    private val fragmentResolver: SqlFragmentResolver
) {
    
    private val logger = thisLogger()
    
    /**
     * 展开 Statement 内容，替换所有 include 标签
     * 
     * @param statementInfo Statement 信息
     * @return 组装结果
     */
    fun expand(statementInfo: StatementInfo): AssemblyResult {
        val startTime = System.currentTimeMillis()
        logger.info("开始展开 Statement: ${statementInfo.namespace}.${statementInfo.statementId}")
        
        val context = ExpandContext()
        
        val assembledSql = processIncludes(
            content = statementInfo.content,
            currentFile = statementInfo.sourceFile,
            currentNamespace = statementInfo.namespace,
            statementId = statementInfo.statementId,
            depth = 0,
            context = context
        )
        
        val elapsedTime = System.currentTimeMillis() - startTime
        logger.info("Statement 展开完成，耗时 ${elapsedTime}ms，替换了 ${context.replacedIncludes.size} 个 include 标签")
        
        return AssemblyResult(
            assembledSql = assembledSql,
            statementInfo = statementInfo,
            replacedIncludeCount = context.replacedIncludes.size,
            missingFragments = context.getDistinctMissingFragments(),
            circularReferences = context.getDistinctCircularReferences()
        )
    }
    
    /**
     * 仅展开内容，返回展开后的字符串
     * 简化版本，适用于只需要展开结果的场景
     * 
     * @param content 原始内容
     * @param currentFile 当前 XML 文件
     * @param currentNamespace 当前 namespace
     * @return 展开后的内容
     */
    fun expandContent(
        content: String,
        currentFile: XmlFile,
        currentNamespace: String
    ): String {
        val context = ExpandContext()
        return processIncludes(
            content = content,
            currentFile = currentFile,
            currentNamespace = currentNamespace,
            statementId = "",
            depth = 0,
            context = context
        )
    }
    
    /**
     * 递归处理 include 标签
     */
    private fun processIncludes(
        content: String,
        currentFile: XmlFile,
        currentNamespace: String,
        statementId: String,
        depth: Int,
        context: ExpandContext
    ): String {
        // 检查递归深度
        if (depth >= context.maxRecursionDepth) {
            logger.warn("达到最大递归深度 ${context.maxRecursionDepth}，停止处理")
            return content
        }
        
        // 查找所有 include 标签
        val includePattern = Regex("""<include\s+refid\s*=\s*["']([^"']+)["']\s*/?>""")
        val matches = includePattern.findAll(content).toList()
        
        if (matches.isEmpty()) {
            return content
        }
        
        var result = content
        // 从后向前替换，避免偏移问题
        for (match in matches.reversed()) {
            val refid = match.groupValues[1]
            
            // 解析 refid
            val (targetNamespace, fragmentId) = fragmentResolver.parseRefId(refid, currentNamespace)
            
            // 检测循环引用
            val refKey = "$targetNamespace.$fragmentId"
            if (context.checkCircularReference(refKey)) {
                logger.warn("检测到循环引用: $refKey")
                context.recordCircularReference(refKey)
                continue
            }
            
            // 查找 SQL 片段
            val fragment = fragmentResolver.findSqlFragment(targetNamespace, fragmentId, currentFile)
            
            if (fragment != null) {
                // 处理找到的片段
                result = processFoundFragment(
                    result = result,
                    match = match,
                    refid = refid,
                    refKey = refKey,
                    fragment = fragment,
                    statementId = statementId,
                    depth = depth,
                    context = context
                )
            } else {
                // 记录未找到的片段
                recordMissingFragment(
                    refid = refid,
                    statementId = statementId,
                    targetNamespace = targetNamespace,
                    currentNamespace = currentNamespace,
                    context = context
                )
            }
        }
        
        return result
    }
    
    /**
     * 处理找到的片段
     */
    private fun processFoundFragment(
        result: String,
        match: MatchResult,
        refid: String,
        refKey: String,
        fragment: SqlFragmentInfo,
        statementId: String,
        depth: Int,
        context: ExpandContext
    ): String {
        // 将当前引用加入栈
        context.enterReference(refKey)
        
        // 递归处理片段中的 include
        val processedContent = processIncludes(
            content = fragment.content,
            currentFile = fragment.sourceFile,
            currentNamespace = fragment.namespace,
            statementId = statementId,
            depth = depth + 1,
            context = context
        )
        
        // 替换 include 标签
        val newResult = result.replaceRange(match.range, processedContent)
        context.recordReplacedInclude(refid)
        
        // 从栈中移除
        context.leaveReference(refKey)
        
        return newResult
    }
    
    /**
     * 记录未找到的片段
     */
    private fun recordMissingFragment(
        refid: String,
        statementId: String,
        targetNamespace: String,
        currentNamespace: String,
        context: ExpandContext
    ) {
        val reason = if (targetNamespace != currentNamespace) {
            "目标文件不存在或片段未定义"
        } else {
            "片段未定义"
        }
        
        context.recordMissingFragment(
            MissingFragmentInfo(
                refid = refid,
                statementId = statementId,
                expectedNamespace = targetNamespace,
                reason = reason
            )
        )
        
        logger.warn("未找到 SQL 片段: $refid (namespace: $targetNamespace)")
    }
}
