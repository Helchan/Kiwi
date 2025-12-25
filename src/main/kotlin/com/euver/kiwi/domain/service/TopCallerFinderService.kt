package com.euver.kiwi.domain.service

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiExpression
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiLambdaExpression
import com.intellij.psi.PsiMethodReferenceExpression
import com.intellij.psi.PsiQualifiedReference
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.GlobalSearchScopesCore
import com.intellij.psi.search.searches.MethodReferencesSearch
import com.intellij.psi.search.searches.FunctionalExpressionSearch
import com.intellij.psi.util.InheritanceUtil
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.PsiUtil
import java.util.concurrent.ConcurrentHashMap

/**
 * 顶层调用者查找服务
 * 负责从指定方法开始，向上追溯调用链，找到所有顶层调用者（入口方法）
 * 
 * 支持的调用链场景：
 * - 直接方法调用
 * - 接口/父类方法调用
 * - Lambda表达式调用（追踪到Lambda声明位置）
 * - 方法引用调用（Class::method，追踪到引用声明位置）
 * 
 * 实现机制（与 IDEA 原生 Hierarchy CallerMethodsTreeStructure 完全一致）：
 * - 使用 GlobalSearchScopesCore.projectProductionScope，O(1) 时间复杂度过滤
 * - 使用 runReadActionInSmartMode 确保 Smart Mode 下执行，避免 IndexNotReadyException
 * - 使用 BFS 广度优先搜索，避免栈溢出
 * - 搜索结果缓存：避免重复搜索相同方法
 * - 无截断限制：保证结果完整性
 * - Javadoc 引用过滤：跳过 Javadoc 中的引用（与原生 Hierarchy 一致）
 * - 类型关联性检查：过滤不相关类的引用（与原生 Hierarchy areClassesRelated 一致）
 */
class TopCallerFinderService(private val project: Project) {

    private val logger = thisLogger()
    
    // 方法键缓存，避免重复计算
    private val methodKeyCache = ConcurrentHashMap<PsiMethod, String>()
    
    // 搜索结果缓存：方法键 -> 调用者列表
    private val callerSearchCache = ConcurrentHashMap<String, List<PsiMethod>>()
    
    // 生产代码搜索范围缓存
    @Volatile
    private var cachedProductionScope: GlobalSearchScope? = null

    companion object {
        private const val MAX_DEPTH = 50
        
        /** 搜索结果缓存最大条目数 */
        private const val MAX_CACHE_SIZE = 1000
        
        /**
         * 需要过滤的JDK函数式接口方法
         * 这些方法是函数式接口的抽象方法，不应被视为有意义的顶层调用者
         */
        private val FUNCTIONAL_INTERFACE_METHODS = setOf(
            // java.util.function
            "java.util.function.Consumer.accept",
            "java.util.function.BiConsumer.accept",
            "java.util.function.Function.apply",
            "java.util.function.BiFunction.apply",
            "java.util.function.Supplier.get",
            "java.util.function.Predicate.test",
            "java.util.function.BiPredicate.test",
            "java.util.function.UnaryOperator.apply",
            "java.util.function.BinaryOperator.apply",
            // java.lang
            "java.lang.Runnable.run",
            "java.util.concurrent.Callable.call",
            // Java Streams
            "java.util.stream.Stream.forEach",
            "java.util.stream.Stream.map",
            "java.util.stream.Stream.filter",
            "java.util.stream.Stream.flatMap",
            // 常见框架回调接口
            "org.springframework.transaction.support.TransactionCallback.doInTransaction",
            "org.springframework.jdbc.core.RowMapper.mapRow",
            "org.springframework.jdbc.core.ResultSetExtractor.extractData"
        )
    }

    /**
     * 查找指定方法的所有顶层调用者
     * @param method 起始方法
     * @return 顶层调用者方法集合（已去重，不包含起始方法本身）
     */
    fun findTopCallers(method: PsiMethod): Set<PsiMethod> {
        // 清理方法键缓存，但保留搜索结果缓存（可跨次查找复用）
        methodKeyCache.clear()
        cachedProductionScope = null
        
        // 缓存满时清理
        if (callerSearchCache.size > MAX_CACHE_SIZE) {
            callerSearchCache.clear()
            logger.debug("搜索结果缓存已满，执行清理")
        }
        
        val dumbService = DumbService.getInstance(project)
        
        // 将整个 BFS 搜索过程包装在单个 runReadActionInSmartMode 中
        // 与 IDEA 原生 Hierarchy 实现一致，避免多次调用的开销
        return dumbService.runReadActionInSmartMode<Set<PsiMethod>> {
            val methodFullName = "${method.containingClass?.qualifiedName}.${method.name}"
            logger.info("开始查找方法的顶层调用者: $methodFullName")
            
            val topCallers = mutableSetOf<PsiMethod>()
            val visited = mutableSetOf<String>()
            val sourceMethodKey = getMethodKeyInternal(method)
            
            // 使用广度优先搜索（BFS）代替深度优先递归，减少栈深度和提高效率
            findTopCallersBFSInternal(method, topCallers, visited)
            
            // 排除起始方法本身：如果起始方法没有调用者，不应该将它返回为顶层调用者
            val filteredCallers = topCallers.filter { getMethodKeyInternal(it) != sourceMethodKey }.toSet()
            
            logger.info("找到 ${filteredCallers.size} 个顶层调用者，共访问了 ${visited.size} 个不同方法")
            filteredCallers
        }
    }
    
    /**
     * 清理所有缓存（包括搜索结果缓存）
     */
    fun clearAllCaches() {
        methodKeyCache.clear()
        callerSearchCache.clear()
        cachedProductionScope = null
        logger.debug("已清理所有缓存")
    }
    
    /**
     * 使用广度优先搜索查找顶层调用者（内部方法，在 ReadAction 上下文中调用）
     * 优点：避免深度递归导致的栈溢出，更容易控制和取消
     */
    private fun findTopCallersBFSInternal(
        startMethod: PsiMethod,
        topCallers: MutableSet<PsiMethod>,
        visited: MutableSet<String>
    ) {
        val queue = ArrayDeque<Pair<PsiMethod, Int>>()
        queue.add(startMethod to 0)
        
        val indicator = ProgressManager.getInstance().progressIndicator
        var processedCount = 0
        
        while (queue.isNotEmpty()) {
            ProgressManager.checkCanceled()
            
            val (method, depth) = queue.removeFirst()
            processedCount++
            
            // 更新进度指示器
            indicator?.let {
                it.text2 = "已处理 $processedCount 个方法，找到 ${topCallers.size} 个顶层调用者，队列: ${queue.size}"
            }
            
            if (depth > MAX_DEPTH) {
                val methodKey = getMethodKeyInternal(method)
                logger.warn("达到最大递归深度限制: $MAX_DEPTH，当前方法: $methodKey")
                continue
            }
            
            val methodKey = getMethodKeyInternal(method)
            if (methodKey in visited) {
                continue
            }
            visited.add(methodKey)
            
            // 检查是否是函数式接口方法
            if (isFunctionalInterfaceMethodInternal(methodKey)) {
                logger.debug("检测到函数式接口方法: $methodKey，尝试通过Lambda/方法引用追溯实际调用者")
                val lambdaCallers = findLambdaDeclarationCallersInternal(method)
                if (lambdaCallers.isNotEmpty()) {
                    logger.debug("找到 ${lambdaCallers.size} 个通过Lambda/方法引用的调用者")
                    for (caller in lambdaCallers) {
                        queue.add(caller to depth + 1)
                    }
                    continue
                }
            }
            
            // 查找所有调用者（带缓存，无截断限制）
            val callers = findAllCallersInternal(method, methodKey)
            
            if (callers.isEmpty()) {
                // 没有调用者，是顶层调用者
                if (!isFunctionalInterfaceMethodInternal(methodKey)) {
                    topCallers.add(method)
                    logger.debug("找到顶层调用者: $methodKey")
                }
            } else {
                // 将调用者加入队列继续搜索
                for (caller in callers) {
                    queue.add(caller to depth + 1)
                }
            }
        }
    }

    /**
     * 函数式接口方法检查（内部方法，在 ReadAction 上下文中调用）
     */
    private fun isFunctionalInterfaceMethodInternal(methodKey: String): Boolean {
        val className = methodKey.substringAfter(" ").substringBefore(".")
        val methodName = methodKey.substringAfter(".").substringBefore("(")
        val fullName = "$className.$methodName"
        return fullName in FUNCTIONAL_INTERFACE_METHODS
    }
    
    /**
     * 调用者查找（内部方法，在 ReadAction 上下文中调用）
     * 使用 IDEA 原生的 projectProductionScope，性能与 Hierarchy 一致
     */
    private fun findAllCallersInternal(method: PsiMethod, methodKey: String): List<PsiMethod> {
        // 检查缓存
        callerSearchCache[methodKey]?.let { 
            logger.debug("缓存命中: $methodKey")
            return it 
        }
        
        ProgressManager.checkCanceled()
        
        val allCallers = mutableSetOf<String>()
        val callerMethods = mutableListOf<PsiMethod>()
        
        try {
            val scope = getProductionScope()
            
            // 1. 查找该方法本身的直接调用者
            findDirectCallers(method, scope, allCallers, callerMethods)
            
            // 2. 查找该方法所实现/重写的最深层父类或接口方法的调用者
            // 使用 findDeepestSuperMethods() 与 IDEA 原生 Hierarchy 保持一致
            val deepestSuperMethods = method.findDeepestSuperMethods().toList()
            for (superMethod in deepestSuperMethods) {
                ProgressManager.checkCanceled()
                findDirectCallers(superMethod, scope, allCallers, callerMethods)
            }
        } catch (e: ProcessCanceledException) {
            throw e // 重新抛出取消异常
        } catch (e: com.intellij.openapi.project.IndexNotReadyException) {
            logger.warn("索引未就绪，跳过调用者查找: ${e.message}")
        } catch (e: Exception) {
            logger.warn("查找调用者时出错: ${e.message}")
        }
        
        // 存入缓存
        callerSearchCache[methodKey] = callerMethods
        return callerMethods
    }
    
    /**
     * 查找直接调用者（与 IDEA 原生 CallerMethodsTreeStructure 完全一致）
     * - 过滤 Javadoc 中的引用
     * - 检查类型关联性，排除不相关类的引用
     */
    private fun findDirectCallers(
        method: PsiMethod,
        scope: GlobalSearchScope,
        allCallers: MutableSet<String>,
        callerMethods: MutableList<PsiMethod>
    ) {
        ProgressManager.checkCanceled()
        
        val expectedQualifierClass = method.containingClass
        val searchQuery = MethodReferencesSearch.search(method, scope, true)
        
        for (reference in searchQuery) {
            ProgressManager.checkCanceled()
            
            val element = reference.element
            
            // 与 IDEA 原生 Hierarchy 一致：跳过 Javadoc 中的引用
            if (PsiUtil.isInsideJavadocComment(element)) {
                continue
            }
            
            // 与 IDEA 原生 Hierarchy 一致：检查类型关联性
            if (expectedQualifierClass != null) {
                var receiverClass: PsiClass? = null
                if (reference is PsiQualifiedReference) {
                    val qualifier = reference.qualifier
                    if (qualifier is PsiExpression) {
                        receiverClass = PsiUtil.resolveClassInClassTypeOnly(qualifier.type)
                    }
                }
                if (receiverClass == null) {
                    val resolved = reference.resolve()
                    if (resolved is PsiMethod) {
                        receiverClass = resolved.containingClass
                    }
                }
                
                // 过滤不相关的类引用
                if (receiverClass != null && !areClassesRelated(expectedQualifierClass, receiverClass)) {
                    continue
                }
            }
            
            val callerMethod = PsiTreeUtil.getParentOfType(element, PsiMethod::class.java)
            if (callerMethod != null) {
                val key = getMethodKeyInternal(callerMethod)
                if (key !in allCallers) {
                    allCallers.add(key)
                    callerMethods.add(callerMethod)
                }
            }
        }
    }
    
    /**
     * 检查两个类是否存在继承关系（与 IDEA 原生 CallerMethodsTreeStructure.areClassesRelated 一致）
     * 用于过滤不相关类的方法调用引用
     */
    private fun areClassesRelated(expectedQualifierClass: PsiClass, receiverClass: PsiClass): Boolean {
        // 直接继承关系检查
        if (areClassesDirectlyRelated(expectedQualifierClass, receiverClass)) {
            return true
        }
        // 泛型类型参数的继承关系检查
        if (receiverClass is com.intellij.psi.PsiTypeParameter) {
            val superClasses = com.intellij.psi.impl.PsiClassImplUtil.getAllSuperClassesRecursively(receiverClass)
            for (receiverExtends in superClasses) {
                if (areClassesDirectlyRelated(expectedQualifierClass, receiverExtends)) {
                    return true
                }
            }
        }
        return false
    }
    
    /**
     * 检查两个类是否存在直接继承关系
     */
    private fun areClassesDirectlyRelated(expectedQualifierClass: PsiClass, receiverClass: PsiClass): Boolean {
        return InheritanceUtil.isInheritorOrSelf(expectedQualifierClass, receiverClass, true)
                || InheritanceUtil.isInheritorOrSelf(receiverClass, expectedQualifierClass, true)
    }
    
    /**
     * 内部方法：生成方法键（在 ReadAction 上下文中调用）
     */
    private fun getMethodKeyInternal(method: PsiMethod): String {
        // 先检查缓存
        methodKeyCache[method]?.let { return it }
        
        val className = method.containingClass?.qualifiedName ?: "UnknownClass"
        val methodName = method.name
        val returnType = method.returnType?.canonicalText ?: "void"
        val params = method.parameterList.parameters.joinToString(",") { 
            it.type.presentableText 
        }
        val key = "$returnType $className.$methodName($params)"
        
        // 缓存结果
        methodKeyCache[method] = key
        return key
    }

    /**
     * 获取 IDEA 原生的生产代码搜索范围
     * 使用 GlobalSearchScopesCore.projectProductionScope，性能远高于自定义 scope
     */
    private fun getProductionScope(): GlobalSearchScope {
        cachedProductionScope?.let { return it }
        
        val scope = GlobalSearchScopesCore.projectProductionScope(project)
        cachedProductionScope = scope
        return scope
    }

    /**
     * 查找Lambda表达式和方法引用的声明位置所在的方法（内部方法，在 ReadAction 上下文中调用）
     * 用于追溯函数式接口调用的实际调用者
     */
    private fun findLambdaDeclarationCallersInternal(method: PsiMethod): List<PsiMethod> {
        ProgressManager.checkCanceled()
        
        val callers = mutableListOf<PsiMethod>()
        val callerKeys = mutableSetOf<String>()
        val scope = getProductionScope()
        
        // 获取方法所在的接口/类
        val containingClass = method.containingClass ?: return emptyList()
        
        // 查找所有实现该函数式接口的Lambda表达式和方法引用
        try {
            val searchQuery = FunctionalExpressionSearch.search(containingClass, scope)
            
            for (expression in searchQuery) {
                ProgressManager.checkCanceled()
                
                // 与原生 Hierarchy 一致：跳过 Javadoc 中的表达式
                if (PsiUtil.isInsideJavadocComment(expression)) {
                    continue
                }
                
                val enclosingMethod = when (expression) {
                    is PsiLambdaExpression -> PsiTreeUtil.getParentOfType(expression, PsiMethod::class.java)
                    is PsiMethodReferenceExpression -> PsiTreeUtil.getParentOfType(expression, PsiMethod::class.java)
                    else -> null
                }
                
                if (enclosingMethod != null) {
                    val key = getMethodKeyInternal(enclosingMethod)
                    if (key !in callerKeys) {
                        callerKeys.add(key)
                        callers.add(enclosingMethod)
                    }
                }
            }
        } catch (e: ProcessCanceledException) {
            throw e // 重新抛出取消异常，确保用户可以取消操作
        } catch (e: com.intellij.openapi.project.IndexNotReadyException) {
            // 索引未就绪时记录警告并返回空结果，避免阻塞
            logger.warn("索引未就绪，跳过Lambda查找: ${e.message}")
        } catch (e: Exception) {
            logger.warn("查找Lambda/方法引用时出错: ${e.message}")
        }
        
        return callers
    }
}
