package com.euver.kiwi.model

/**
 * SQL 组装结果模型
 *
 * @property assembledSql 组装后的完整 SQL 内容
 * @property statementInfo Statement 信息
 * @property replacedIncludeCount 替换的 include 标签数量
 * @property missingFragments 未找到的 SQL 片段列表
 * @property circularReferences 循环引用的片段列表
 */
data class AssemblyResult(
    val assembledSql: String,
    val statementInfo: StatementInfo,
    val replacedIncludeCount: Int,
    val missingFragments: List<MissingFragmentInfo>,
    val circularReferences: List<CircularReferenceInfo> = emptyList()
) {
    /**
     * 判断是否完全成功(没有未找到的片段和循环引用)
     */
    fun isFullySuccessful(): Boolean = missingFragments.isEmpty() && circularReferences.isEmpty()

    /**
     * 判断是否存在循环引用(组装失败)
     */
    fun hasCircularReference(): Boolean = circularReferences.isNotEmpty()

    /**
     * 判断是否部分成功(有未找到的片段,但没有循环引用)
     */
    fun isPartiallySuccessful(): Boolean = missingFragments.isNotEmpty() && circularReferences.isEmpty()
}

/**
 * 循环引用信息
 *
 * @property cyclePath 循环路径中的完整引用ID列表(包含回到起点)
 */
data class CircularReferenceInfo(
    val cyclePath: List<String>
) {
    /**
     * 获取格式化的循环引用描述
     * 返回: Pair<简化环描述, 代号映射列表>
     * 例如: Pair("A → B → A", listOf("A: xxx.Base_Column_List", "B: xxx.testRecur"))
     */
    fun getFormattedDescription(): Pair<String, List<String>> {
        // 去掉最后一个(因为它和第一个相同,形成环)
        val uniqueNodes = cyclePath.dropLast(1)
        
        // 为每个唯一节点分配代号 A, B, C...
        val nodeToLabel = mutableMapOf<String, String>()
        uniqueNodes.forEachIndexed { index, node ->
            if (node !in nodeToLabel) {
                nodeToLabel[node] = ('A' + index).toString()
            }
        }
        
        // 构建简化的环描述
        val simplifiedCycle = cyclePath.map { nodeToLabel[it] ?: it }.joinToString(" → ")
        
        // 构建代号映射列表
        val labelMappings = nodeToLabel.entries
            .sortedBy { it.value }
            .map { "${it.value}: ${it.key}" }
        
        return Pair(simplifiedCycle, labelMappings)
    }
}

/**
 * 未找到的片段信息
 *
 * @property refid 片段的 refid 值
 * @property statementId 引用位置的 Statement ID
 * @property expectedNamespace 预期查找的 namespace
 * @property reason 未找到的原因
 */
data class MissingFragmentInfo(
    val refid: String,
    val statementId: String,
    val expectedNamespace: String?,
    val reason: String
)
