package com.euver.kiwi.domain.model

/**
 * 顶层调用者与关联 Statement 的数据模型
 * 用于在 SQL 片段触发时记录顶层调用者与其对应的 Statement ID 关系
 * 
 * @param methodInfo 顶层调用者的方法信息
 * @param statementId 关联的 Statement ID（含 namespace，如 com.example.UserMapper.selectById）
 * @param statementComment Statement 对应的 Mapper 方法的功能注释
 */
data class TopCallerWithStatement(
    val methodInfo: MethodInfo,
    val statementId: String,
    val statementComment: String = ""
) {
    /**
     * 获取不含 namespace 的简短 Statement ID
     */
    fun getSimpleStatementId(): String {
        val lastDot = statementId.lastIndexOf('.')
        return if (lastDot > 0) statementId.substring(lastDot + 1) else statementId
    }
}
