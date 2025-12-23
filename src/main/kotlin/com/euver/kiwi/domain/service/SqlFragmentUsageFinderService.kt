package com.euver.kiwi.domain.service

import com.euver.kiwi.parser.MyBatisXmlParser
import com.euver.kiwi.service.MapperIndexService
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiMethod
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag

/**
 * SQL 片段使用查找服务
 * 负责查找哪些 Statement 引用了指定的 SQL 片段，并找到对应的 Mapper 接口方法
 */
class SqlFragmentUsageFinderService(private val project: Project) {

    private val logger = thisLogger()
    private val parser = MyBatisXmlParser()

    companion object {
        private const val MAX_DEPTH = 50
    }

    /**
     * 查找引用指定 SQL 片段的所有 Statement 对应的 Mapper 方法
     * 支持递归查找嵌套引用的情况（SQL 片段被另一个 SQL 片段引用）
     * 
     * @param fragmentFullId SQL 片段的完整 ID（namespace.fragmentId）
     * @param currentFile 当前 XML 文件
     * @return 引用该片段的所有 Mapper 方法集合
     */
    fun findMapperMethodsUsingFragment(fragmentFullId: String, currentFile: XmlFile): Set<PsiMethod> {
        logger.info("开始查找引用 SQL 片段的 Statement: $fragmentFullId")
        
        val mapperMethods = mutableSetOf<PsiMethod>()
        val visitedFragments = mutableSetOf<String>()
        
        findMapperMethodsRecursively(fragmentFullId, currentFile, mapperMethods, visitedFragments, 0)
        
        logger.info("找到 ${mapperMethods.size} 个引用该 SQL 片段的 Mapper 方法")
        return mapperMethods
    }

    /**
     * 查找引用指定 SQL 片段的所有 Statement 对应的 Mapper 方法，并返回 Statement ID 与方法的映射关系
     * 用于需要同时展示 Statement ID 信息的场景
     * 
     * @param fragmentFullId SQL 片段的完整 ID（namespace.fragmentId）
     * @param currentFile 当前 XML 文件
     * @return Statement ID 到 Mapper 方法的映射（一个 Statement 对应一个 Mapper 方法）
     */
    fun findMapperMethodsWithStatementId(fragmentFullId: String, currentFile: XmlFile): Map<String, PsiMethod> {
        logger.info("开始查找引用 SQL 片段的 Statement 及其 ID: $fragmentFullId")
        
        val statementToMethod = mutableMapOf<String, PsiMethod>()
        val visitedFragments = mutableSetOf<String>()
        
        findMapperMethodsWithStatementIdRecursively(
            fragmentFullId, 
            currentFile, 
            statementToMethod, 
            visitedFragments, 
            0
        )
        
        logger.info("找到 ${statementToMethod.size} 个引用该 SQL 片段的 Statement")
        return statementToMethod
    }

    /**
     * 递归查找引用 SQL 片段的 Mapper 方法，同时记录 Statement ID
     */
    private fun findMapperMethodsWithStatementIdRecursively(
        fragmentFullId: String,
        currentFile: XmlFile,
        statementToMethod: MutableMap<String, PsiMethod>,
        visitedFragments: MutableSet<String>,
        depth: Int
    ) {
        if (depth > MAX_DEPTH) {
            logger.warn("达到最大递归深度限制: $MAX_DEPTH，当前片段: $fragmentFullId，可能存在过深的引用链或循环引用")
            return
        }

        if (fragmentFullId in visitedFragments) {
            return
        }
        visitedFragments.add(fragmentFullId)

        val indexService = MapperIndexService.getInstance(project)
        val allMapperFiles = ApplicationManager.getApplication().runReadAction<List<XmlFile>> {
            indexService.findAllMapperFiles()
        }

        for (mapperFile in allMapperFiles) {
            ApplicationManager.getApplication().runReadAction {
                val namespace = parser.extractNamespace(mapperFile) ?: return@runReadAction
                val rootTag = mapperFile.rootTag ?: return@runReadAction

                for (tag in rootTag.subTags) {
                    val tagName = tag.name
                    val tagId = tag.getAttributeValue("id") ?: continue

                    if (isStatementTag(tagName)) {
                        if (doesTagReferenceFragment(tag, fragmentFullId, namespace)) {
                            val method = findMapperMethod(namespace, tagId)
                            if (method != null) {
                                val statementFullId = "$namespace.$tagId"
                                statementToMethod[statementFullId] = method
                            }
                        }
                    } else if (tagName == "sql") {
                        if (doesTagReferenceFragment(tag, fragmentFullId, namespace)) {
                            val parentFragmentFullId = "$namespace.$tagId"
                            findMapperMethodsWithStatementIdRecursively(
                                parentFragmentFullId,
                                mapperFile,
                                statementToMethod,
                                visitedFragments,
                                depth + 1
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * 递归查找引用 SQL 片段的 Mapper 方法
     * 处理 SQL 片段被其他 SQL 片段引用的嵌套情况
     */
    private fun findMapperMethodsRecursively(
        fragmentFullId: String,
        currentFile: XmlFile,
        mapperMethods: MutableSet<PsiMethod>,
        visitedFragments: MutableSet<String>,
        depth: Int
    ) {
        if (depth > MAX_DEPTH) {
            logger.warn("达到最大递归深度限制: $MAX_DEPTH，当前片段: $fragmentFullId，可能存在过深的引用链或循环引用")
            return
        }

        if (fragmentFullId in visitedFragments) {
            return
        }
        visitedFragments.add(fragmentFullId)

        val indexService = MapperIndexService.getInstance(project)
        val allMapperFiles = ApplicationManager.getApplication().runReadAction<List<XmlFile>> {
            indexService.findAllMapperFiles()
        }

        // 在所有 Mapper 文件中查找引用该片段的地方
        for (mapperFile in allMapperFiles) {
            ApplicationManager.getApplication().runReadAction {
                val namespace = parser.extractNamespace(mapperFile) ?: return@runReadAction
                val rootTag = mapperFile.rootTag ?: return@runReadAction

                for (tag in rootTag.subTags) {
                    val tagName = tag.name
                    val tagId = tag.getAttributeValue("id") ?: continue

                    if (isStatementTag(tagName)) {
                        // 检查 Statement 是否引用了该片段
                        if (doesTagReferenceFragment(tag, fragmentFullId, namespace)) {
                            val method = findMapperMethod(namespace, tagId)
                            if (method != null) {
                                mapperMethods.add(method)
                            }
                        }
                    } else if (tagName == "sql") {
                        // 检查 SQL 片段是否引用了该片段（嵌套引用）
                        if (doesTagReferenceFragment(tag, fragmentFullId, namespace)) {
                            val parentFragmentFullId = "$namespace.$tagId"
                            // 递归查找引用父片段的 Statement
                            findMapperMethodsRecursively(
                                parentFragmentFullId,
                                mapperFile,
                                mapperMethods,
                                visitedFragments,
                                depth + 1
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * 检查标签是否引用了指定的 SQL 片段
     */
    private fun doesTagReferenceFragment(tag: XmlTag, fragmentFullId: String, currentNamespace: String): Boolean {
        // 查找所有 include 标签
        val includesTags = findAllIncludeTags(tag)
        
        for (includeTag in includesTags) {
            val refid = includeTag.getAttributeValue("refid") ?: continue
            val resolvedFullId = resolveRefId(refid, currentNamespace)
            if (resolvedFullId == fragmentFullId) {
                return true
            }
        }
        return false
    }

    /**
     * 递归查找所有 include 标签
     */
    private fun findAllIncludeTags(tag: XmlTag): List<XmlTag> {
        val includeTags = mutableListOf<XmlTag>()
        collectIncludeTags(tag, includeTags)
        return includeTags
    }

    /**
     * 递归收集 include 标签
     */
    private fun collectIncludeTags(tag: XmlTag, result: MutableList<XmlTag>) {
        for (subTag in tag.subTags) {
            if (subTag.name == "include") {
                result.add(subTag)
            }
            // 递归检查子标签
            collectIncludeTags(subTag, result)
        }
    }

    /**
     * 解析 refid，返回完整的 namespace.fragmentId 格式
     */
    private fun resolveRefId(refid: String, currentNamespace: String): String {
        val dotIndex = refid.lastIndexOf('.')
        return if (dotIndex > 0) {
            // 已经是完整格式
            refid
        } else {
            // 当前文件内引用
            "$currentNamespace.$refid"
        }
    }

    /**
     * 判断是否为 Statement 标签
     */
    private fun isStatementTag(tagName: String): Boolean {
        return tagName in setOf("select", "insert", "update", "delete")
    }

    /**
     * 查找 Mapper 接口中对应的方法
     */
    private fun findMapperMethod(namespace: String, methodName: String): PsiMethod? {
        return ApplicationManager.getApplication().runReadAction<PsiMethod?> {
            val psiFacade = JavaPsiFacade.getInstance(project)
            val scope = GlobalSearchScope.projectScope(project)
            val mapperClass = psiFacade.findClass(namespace, scope) ?: return@runReadAction null
            mapperClass.findMethodsByName(methodName, false).firstOrNull()
        }
    }
}
