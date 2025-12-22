package com.euver.kiwi.infrastructure.resolver

import com.euver.kiwi.domain.service.SqlFragmentResolver
import com.euver.kiwi.model.SqlFragmentInfo
import com.euver.kiwi.parser.MyBatisXmlParser
import com.euver.kiwi.service.MapperIndexService
import com.intellij.openapi.project.Project
import com.intellij.psi.xml.XmlFile

/**
 * SQL 片段解析器实现
 * 基础设施层实现，负责查找和解析 SQL 片段
 */
class SqlFragmentResolverImpl(
    private val project: Project,
    private val parser: MyBatisXmlParser = MyBatisXmlParser()
) : SqlFragmentResolver {
    
    private val indexService by lazy { MapperIndexService.getInstance(project) }
    
    override fun parseRefId(refid: String, currentNamespace: String): Pair<String, String> {
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
    
    override fun findSqlFragment(namespace: String, fragmentId: String, currentFile: XmlFile): SqlFragmentInfo? {
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
