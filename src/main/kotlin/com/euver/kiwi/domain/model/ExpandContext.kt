package com.euver.kiwi.domain.model

import com.euver.kiwi.model.CircularReferenceInfo
import com.euver.kiwi.model.MissingFragmentInfo

/**
 * Statement 展开上下文
 * 用于在展开过程中跟踪状态和收集结果
 */
class ExpandContext {
    
    /**
     * 当前正在处理的引用栈，用于检测循环引用
     */
    val processingStack: MutableList<String> = mutableListOf()
    
    /**
     * 未找到的 SQL 片段列表
     */
    val missingFragments: MutableList<MissingFragmentInfo> = mutableListOf()
    
    /**
     * 检测到的循环引用列表
     */
    val circularReferences: MutableList<CircularReferenceInfo> = mutableListOf()
    
    /**
     * 已成功替换的 include 引用集合
     */
    val replacedIncludes: MutableSet<String> = mutableSetOf()
    
    /**
     * 最大递归深度
     */
    val maxRecursionDepth: Int = 10
    
    /**
     * 检查是否存在循环引用
     */
    fun checkCircularReference(refKey: String): Boolean {
        return refKey in processingStack
    }
    
    /**
     * 记录循环引用
     */
    fun recordCircularReference(refKey: String) {
        val cycleStartIndex = processingStack.indexOf(refKey)
        val cyclePath = processingStack.subList(cycleStartIndex, processingStack.size).toMutableList()
        cyclePath.add(refKey) // 添加回到起点，形成完整的环
        circularReferences.add(CircularReferenceInfo(cyclePath.toList()))
    }
    
    /**
     * 进入引用处理
     */
    fun enterReference(refKey: String) {
        processingStack.add(refKey)
    }
    
    /**
     * 离开引用处理
     */
    fun leaveReference(refKey: String) {
        processingStack.remove(refKey)
    }
    
    /**
     * 记录成功替换的 include
     */
    fun recordReplacedInclude(refid: String) {
        replacedIncludes.add(refid)
    }
    
    /**
     * 记录未找到的片段
     */
    fun recordMissingFragment(info: MissingFragmentInfo) {
        missingFragments.add(info)
    }
    
    /**
     * 获取去重后的未找到片段列表
     */
    fun getDistinctMissingFragments(): List<MissingFragmentInfo> {
        return missingFragments.distinctBy { it.refid }
    }
    
    /**
     * 获取去重后的循环引用列表
     */
    fun getDistinctCircularReferences(): List<CircularReferenceInfo> {
        return circularReferences.distinct()
    }
}
