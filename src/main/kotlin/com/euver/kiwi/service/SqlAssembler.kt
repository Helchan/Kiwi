package com.euver.kiwi.service

import com.euver.kiwi.model.AssemblyResult
import com.euver.kiwi.model.CircularReferenceInfo
import com.euver.kiwi.model.MissingFragmentInfo
import com.euver.kiwi.model.StatementInfo
import com.euver.kiwi.parser.MyBatisXmlParser
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.psi.xml.XmlFile

/**
 * SQL 组装服务
 * 负责递归解析并替换 SQL 中的 include 标签
 */
class SqlAssembler(private val project: Project) {

    private val logger = thisLogger()
    private val parser = MyBatisXmlParser()
    private val indexService = MapperIndexService.getInstance(project)

    // 最大递归深度,防止无限递归
    private val maxRecursionDepth = 10

    /**
     * 组装完整的 SQL
     */
    fun assembleSql(statementInfo: StatementInfo): AssemblyResult {
        val startTime = System.currentTimeMillis()
        logger.info("开始组装 SQL: ${statementInfo.namespace}.${statementInfo.statementId}")

        var assembledSql = statementInfo.content
        val missingFragments = mutableListOf<MissingFragmentInfo>()
        val circularReferences = mutableListOf<CircularReferenceInfo>()
        val replacedIncludes = mutableSetOf<String>()
        val processingStack = mutableListOf<String>()

        // 递归处理 include 标签
        assembledSql = processIncludes(
            content = assembledSql,
            currentFile = statementInfo.sourceFile,
            currentNamespace = statementInfo.namespace,
            statementId = statementInfo.statementId,
            depth = 0,
            processingStack = processingStack,
            missingFragments = missingFragments,
            circularReferences = circularReferences,
            replacedIncludes = replacedIncludes
        )

        val elapsedTime = System.currentTimeMillis() - startTime
        logger.info("SQL 组装完成,耗时 ${elapsedTime}ms, 替换了 ${replacedIncludes.size} 个 include 标签")

        return AssemblyResult(
            assembledSql = assembledSql,
            statementInfo = statementInfo,
            replacedIncludeCount = replacedIncludes.size,
            missingFragments = missingFragments.distinctBy { it.refid },
            circularReferences = circularReferences.distinct()
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
        processingStack: MutableList<String>,
        missingFragments: MutableList<MissingFragmentInfo>,
        circularReferences: MutableList<CircularReferenceInfo>,
        replacedIncludes: MutableSet<String>
    ): String {
        // 检查递归深度
        if (depth >= maxRecursionDepth) {
            logger.warn("达到最大递归深度 $maxRecursionDepth,停止处理")
            return content
        }

        // 查找所有 include 标签
        val includePattern = Regex("""<include\s+refid\s*=\s*["']([^"']+)["']\s*/?>""")
        val matches = includePattern.findAll(content).toList()

        if (matches.isEmpty()) {
            return content
        }

        var result = content
        // 从后向前替换,避免偏移问题
        for (match in matches.reversed()) {
            val refid = match.groupValues[1]
            val includeTag = match.value

            // 解析 refid,判断是否跨文件引用
            val (targetNamespace, fragmentId) = parseRefId(refid, currentNamespace)

            // 检测循环引用
            val refKey = "$targetNamespace.$fragmentId"
            if (refKey in processingStack) {
                // 构建完整的循环引用链路
                val cycleStartIndex = processingStack.indexOf(refKey)
                val cyclePath = processingStack.subList(cycleStartIndex, processingStack.size).toMutableList()
                cyclePath.add(refKey) // 添加回到起点，形成完整的环
                
                val cycleDescription = cyclePath.joinToString(" → ")
                logger.warn("检测到循环引用: $cycleDescription")
                circularReferences.add(CircularReferenceInfo(cyclePath.toList()))
                continue
            }

            // 查找 SQL 片段
            val fragment = findSqlFragment(targetNamespace, fragmentId, currentFile)

            if (fragment != null) {
                // 将当前引用加入栈
                processingStack.add(refKey)

                // 递归处理片段中的 include
                val processedFragmentContent = processIncludes(
                    content = fragment.content,
                    currentFile = fragment.sourceFile,
                    currentNamespace = fragment.namespace,
                    statementId = statementId,
                    depth = depth + 1,
                    processingStack = processingStack,
                    missingFragments = missingFragments,
                    circularReferences = circularReferences,
                    replacedIncludes = replacedIncludes
                )

                // 替换 include 标签
                result = result.replaceRange(match.range, processedFragmentContent)
                replacedIncludes.add(refid)

                // 从栈中移除
                processingStack.remove(refKey)
            } else {
                // 记录未找到的片段
                val reason = if (targetNamespace != currentNamespace) {
                    "目标文件不存在或片段未定义"
                } else {
                    "片段未定义"
                }

                missingFragments.add(
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

        return result
    }

    /**
     * 解析 refid,返回 (namespace, fragmentId)
     */
    private fun parseRefId(refid: String, currentNamespace: String): Pair<String, String> {
        val dotIndex = refid.lastIndexOf('.')
        return if (dotIndex > 0) {
            // 跨文件引用: namespace.fragmentId
            val namespace = refid.substring(0, dotIndex)
            val fragmentId = refid.substring(dotIndex + 1)
            Pair(namespace, fragmentId)
        } else {
            // 当前文件引用
            Pair(currentNamespace, refid)
        }
    }

    /**
     * 查找 SQL 片段
     */
    private fun findSqlFragment(namespace: String, fragmentId: String, currentFile: XmlFile): com.euver.kiwi.model.SqlFragmentInfo? {
        // 先在当前文件中查找
        val currentNamespace = parser.extractNamespace(currentFile)
        if (namespace == currentNamespace) {
            val fragment = parser.findSqlFragment(currentFile, fragmentId)
            if (fragment != null) {
                return fragment
            }
        }

        // 跨文件查找
        if (namespace != currentNamespace) {
            val targetFile = indexService.findMapperFileByNamespace(namespace)
            if (targetFile != null) {
                return parser.findSqlFragment(targetFile, fragmentId)
            }
        }

        return null
    }
}
