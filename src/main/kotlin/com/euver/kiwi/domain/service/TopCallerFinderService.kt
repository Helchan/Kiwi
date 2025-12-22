package com.euver.kiwi.domain.service

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiMethod
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.roots.TestSourcesFilter
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.MethodReferencesSearch
import com.intellij.psi.search.searches.OverridingMethodsSearch
import com.intellij.psi.util.PsiTreeUtil

/**
 * 顶层调用者查找服务
 * 负责从指定方法开始，向上追溯调用链，找到所有顶层调用者（入口方法）
 */
class TopCallerFinderService(private val project: Project) {

    private val logger = thisLogger()

    companion object {
        private const val MAX_DEPTH = 50
    }

    /**
     * 查找指定方法的所有顶层调用者
     * @param method 起始方法
     * @return 顶层调用者方法集合（已去重，不包含起始方法本身）
     */
    fun findTopCallers(method: PsiMethod): Set<PsiMethod> {
        val methodFullName = ApplicationManager.getApplication().runReadAction<String> {
            "${method.containingClass?.qualifiedName}.${method.name}"
        }
        logger.info("开始查找方法的顶层调用者: $methodFullName")
        
        val topCallers = mutableSetOf<PsiMethod>()
        val visited = mutableSetOf<String>()
        val sourceMethodKey = getMethodKey(method)
        
        findTopCallersRecursively(method, topCallers, visited, 0)
        
        // 排除起始方法本身：如果起始方法没有调用者，不应该将它返回为顶层调用者
        val filteredCallers = topCallers.filter { getMethodKey(it) != sourceMethodKey }.toSet()
        
        logger.info("找到 ${filteredCallers.size} 个顶层调用者")
        return filteredCallers
    }

    /**
     * 递归查找顶层调用者
     */
    private fun findTopCallersRecursively(
        method: PsiMethod,
        topCallers: MutableSet<PsiMethod>,
        visited: MutableSet<String>,
        depth: Int
    ) {
        if (depth > MAX_DEPTH) {
            val methodKey = getMethodKey(method)
            logger.warn("达到最大递归深度限制: $MAX_DEPTH，当前方法: $methodKey，可能存在过深的调用链或循环引用")
            return
        }

        val methodKey = getMethodKey(method)
        if (methodKey in visited) {
            return
        }
        visited.add(methodKey)

        // 查找该方法及其所实现/重写的接口或父类方法的所有调用者
        val callers = findAllCallers(method)

        if (callers.isEmpty()) {
            topCallers.add(method)
            logger.debug("找到顶层调用者: $methodKey")
        } else {
            for (caller in callers) {
                findTopCallersRecursively(caller, topCallers, visited, depth + 1)
            }
        }
    }

    /**
     * 查找方法的所有调用者（包括通过接口或父类调用的情况）
     * 关键点：当实现类方法重写了接口方法时，调用者可能调用的是接口方法
     * 因此需要同时搜索该方法及其所实现的父接口/父类方法的调用者
     */
    private fun findAllCallers(method: PsiMethod): List<PsiMethod> {
        val allCallers = mutableSetOf<String>()
        val callerMethods = mutableListOf<PsiMethod>()

        // 1. 查找该方法本身的直接调用者
        val directCallers = findDirectCallers(method)
        for (caller in directCallers) {
            val key = getMethodKey(caller)
            if (key !in allCallers) {
                allCallers.add(key)
                callerMethods.add(caller)
            }
        }

        // 2. 查找该方法所实现/重写的父类或接口方法的调用者
        val superMethods = findSuperMethods(method)
        for (superMethod in superMethods) {
            val superCallers = findDirectCallers(superMethod)
            for (caller in superCallers) {
                val key = getMethodKey(caller)
                if (key !in allCallers) {
                    allCallers.add(key)
                    callerMethods.add(caller)
                }
            }
        }

        return callerMethods
    }

    /**
     * 查找方法所实现或重写的所有父类/接口方法
     */
    private fun findSuperMethods(method: PsiMethod): List<PsiMethod> {
        return ApplicationManager.getApplication().runReadAction<List<PsiMethod>> {
            method.findSuperMethods().toList()
        }
    }

    /**
     * 查找直接调用指定方法的所有方法
     * 注意：只搜索生产代码，排除测试代码
     */
    private fun findDirectCallers(method: PsiMethod): List<PsiMethod> {
        val scope = createProductionScope()
        val references = ApplicationManager.getApplication().runReadAction<Collection<com.intellij.psi.PsiReference>> {
            MethodReferencesSearch.search(method, scope, true).findAll()
        }

        return references.mapNotNull { reference ->
            ApplicationManager.getApplication().runReadAction<PsiMethod?> {
                val callerMethod = PsiTreeUtil.getParentOfType(reference.element, PsiMethod::class.java)
                // 再次过滤，确保调用者不在测试源码中
                if (callerMethod != null && !isInTestSource(callerMethod)) {
                    callerMethod
                } else {
                    null
                }
            }
        }.distinctBy { getMethodKey(it) }
    }

    /**
     * 创建生产代码搜索范围（排除测试目录）
     */
    private fun createProductionScope(): GlobalSearchScope {
        return ApplicationManager.getApplication().runReadAction<GlobalSearchScope> {
            val projectScope = GlobalSearchScope.projectScope(project)
            val projectFileIndex = ProjectRootManager.getInstance(project).fileIndex
            
            // 过滤掉测试源码目录
            projectScope.intersectWith(object : GlobalSearchScope(project) {
                override fun contains(file: com.intellij.openapi.vfs.VirtualFile): Boolean {
                    return !projectFileIndex.isInTestSourceContent(file)
                }

                override fun isSearchInModuleContent(aModule: com.intellij.openapi.module.Module): Boolean = true
                override fun isSearchInLibraries(): Boolean = false
            })
        }
    }

    /**
     * 判断方法是否在测试源码中
     */
    private fun isInTestSource(method: PsiMethod): Boolean {
        return ApplicationManager.getApplication().runReadAction<Boolean> {
            val containingFile = method.containingFile?.virtualFile ?: return@runReadAction false
            val projectFileIndex = ProjectRootManager.getInstance(project).fileIndex
            projectFileIndex.isInTestSourceContent(containingFile)
        }
    }

    /**
     * 生成方法的唯一标识键
     */
    private fun getMethodKey(method: PsiMethod): String {
        return ApplicationManager.getApplication().runReadAction<String> {
            val className = method.containingClass?.qualifiedName ?: "UnknownClass"
            val methodName = method.name
            val params = method.parameterList.parameters.joinToString(",") { it.type.canonicalText }
            "$className.$methodName($params)"
        }
    }
}
