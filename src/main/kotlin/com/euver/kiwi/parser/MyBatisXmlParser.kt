package com.euver.kiwi.parser

import com.euver.kiwi.model.SqlFragmentInfo
import com.euver.kiwi.model.StatementInfo
import com.euver.kiwi.model.StatementType
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag

/**
 * MyBatis XML 解析器
 * 负责解析 MyBatis Mapper XML 文件,提取 Statement 和 SQL 片段信息
 */
class MyBatisXmlParser {

    private val logger = thisLogger()

    /**
     * 从 XML 文件中提取 namespace
     */
    fun extractNamespace(xmlFile: XmlFile): String? {
        val rootTag = xmlFile.rootTag ?: return null
        if (rootTag.name != "mapper") return null
        return rootTag.getAttributeValue("namespace")
    }

    /**
     * 从 XML 文件中查找指定 ID 的 Statement
     */
    fun findStatement(xmlFile: XmlFile, statementId: String): StatementInfo? {
        val namespace = extractNamespace(xmlFile) ?: return null
        val rootTag = xmlFile.rootTag ?: return null

        // 查找匹配的 statement 标签
        for (tag in rootTag.subTags) {
            val tagName = tag.name
            val id = tag.getAttributeValue("id")

            if (id == statementId && isStatementTag(tagName)) {
                val statementType = parseStatementType(tagName) ?: continue
                val content = extractTagInnerContent(tag)

                return StatementInfo(
                    statementId = statementId,
                    statementType = statementType,
                    namespace = namespace,
                    content = content,
                    sourceFile = xmlFile
                )
            }
        }

        return null
    }

    /**
     * 从 XML 标签中获取指定 ID 的 Statement
     * 用于从光标所在的标签直接提取
     */
    fun extractStatementFromTag(tag: XmlTag, xmlFile: XmlFile): StatementInfo? {
        val tagName = tag.name
        if (!isStatementTag(tagName)) return null

        val statementId = tag.getAttributeValue("id") ?: return null
        val namespace = extractNamespace(xmlFile) ?: return null
        val statementType = parseStatementType(tagName) ?: return null
        val content = extractTagInnerContent(tag)

        return StatementInfo(
            statementId = statementId,
            statementType = statementType,
            namespace = namespace,
            content = content,
            sourceFile = xmlFile
        )
    }

    /**
     * 从 XML 文件中查找所有 SQL 片段
     */
    fun findAllSqlFragments(xmlFile: XmlFile): List<SqlFragmentInfo> {
        val namespace = extractNamespace(xmlFile) ?: return emptyList()
        val rootTag = xmlFile.rootTag ?: return emptyList()
        val fragments = mutableListOf<SqlFragmentInfo>()

        for (tag in rootTag.subTags) {
            if (tag.name == "sql") {
                val fragmentId = tag.getAttributeValue("id") ?: continue
                val content = extractTagInnerContent(tag)

                fragments.add(
                    SqlFragmentInfo(
                        fragmentId = fragmentId,
                        namespace = namespace,
                        content = content,
                        sourceFile = xmlFile
                    )
                )
            }
        }

        logger.info("Found ${fragments.size} SQL fragments in ${xmlFile.name}")
        return fragments
    }

    /**
     * 从 XML 文件中查找指定 ID 的 SQL 片段
     */
    fun findSqlFragment(xmlFile: XmlFile, fragmentId: String): SqlFragmentInfo? {
        val namespace = extractNamespace(xmlFile) ?: return null
        val rootTag = xmlFile.rootTag ?: return null

        for (tag in rootTag.subTags) {
            if (tag.name == "sql" && tag.getAttributeValue("id") == fragmentId) {
                val content = extractTagInnerContent(tag)
                return SqlFragmentInfo(
                    fragmentId = fragmentId,
                    namespace = namespace,
                    content = content,
                    sourceFile = xmlFile
                )
            }
        }

        return null
    }

    /**
     * 提取 XML 标签的内部内容(保留原始格式)
     * 包括所有子标签和文本节点,保持原始的缩进和换行
     */
    private fun extractTagInnerContent(tag: XmlTag): String {
        val text = tag.value.text
        // 移除首尾可能的空白,但保留内部格式
        return text.trim()
    }

    /**
     * 判断是否为 Statement 标签
     */
    private fun isStatementTag(tagName: String): Boolean {
        return tagName in setOf("select", "insert", "update", "delete")
    }

    /**
     * 解析 Statement 类型
     */
    private fun parseStatementType(tagName: String): StatementType? {
        return when (tagName) {
            "select" -> StatementType.SELECT
            "insert" -> StatementType.INSERT
            "update" -> StatementType.UPDATE
            "delete" -> StatementType.DELETE
            else -> null
        }
    }
}
