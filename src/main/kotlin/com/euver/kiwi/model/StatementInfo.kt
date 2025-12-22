package com.euver.kiwi.model

import com.intellij.psi.xml.XmlFile

/**
 * MyBatis Statement 信息模型
 *
 * @property statementId Statement 的 id 属性值
 * @property statementType Statement 类型
 * @property namespace 所属 Mapper 的 namespace
 * @property content Statement 的完整内部内容(原始格式)
 * @property sourceFile 来源的 XML 文件
 */
data class StatementInfo(
    val statementId: String,
    val statementType: StatementType,
    val namespace: String,
    val content: String,
    val sourceFile: XmlFile
)

/**
 * Statement 类型枚举
 */
enum class StatementType {
    SELECT,
    INSERT,
    UPDATE,
    DELETE
}
