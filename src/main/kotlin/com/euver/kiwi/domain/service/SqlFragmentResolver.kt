package com.euver.kiwi.domain.service

import com.euver.kiwi.model.SqlFragmentInfo
import com.intellij.psi.xml.XmlFile

/**
 * SQL 片段解析器接口
 * 领域层定义的抽象接口，由基础设施层实现
 * 
 * 遵循 DDD 依赖倒置原则：领域层定义接口，基础设施层提供实现
 */
interface SqlFragmentResolver {
    
    /**
     * 解析 refid，返回目标 namespace 和 fragmentId
     * 
     * @param refid include 标签中的 refid 值
     * @param currentNamespace 当前所在的 namespace
     * @return Pair<namespace, fragmentId>
     */
    fun parseRefId(refid: String, currentNamespace: String): Pair<String, String>
    
    /**
     * 查找 SQL 片段
     * 
     * @param namespace 目标 namespace
     * @param fragmentId 片段 ID
     * @param currentFile 当前文件（用于本地查找优先）
     * @return SQL 片段信息，未找到时返回 null
     */
    fun findSqlFragment(namespace: String, fragmentId: String, currentFile: XmlFile): SqlFragmentInfo?
}
