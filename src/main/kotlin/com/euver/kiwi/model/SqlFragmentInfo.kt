package com.euver.kiwi.model

import com.intellij.psi.xml.XmlFile

/**
 * SQL 片段信息模型
 *
 * @property fragmentId sql 片段的 id 属性值
 * @property namespace 所属 Mapper 的 namespace
 * @property content sql 片段的完整内部内容(原始格式)
 * @property sourceFile 来源的 XML 文件
 */
data class SqlFragmentInfo(
    val fragmentId: String,
    val namespace: String,
    val content: String,
    val sourceFile: XmlFile
)
