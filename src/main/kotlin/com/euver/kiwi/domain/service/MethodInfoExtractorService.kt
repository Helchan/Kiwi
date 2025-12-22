package com.euver.kiwi.domain.service

import com.euver.kiwi.domain.model.MethodInfo
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiMethod
import com.intellij.psi.javadoc.PsiDocComment
import com.intellij.psi.javadoc.PsiDocTag

/**
 * 方法信息提取服务
 * 负责从 PsiMethod 中提取 HTTP 请求方法类型、请求路径、全限定名、类功能注释和方法功能注释
 */
class MethodInfoExtractorService {

    private val logger = thisLogger()

    companion object {
        // JAX-RS 路径注解
        private const val PATH_ANNOTATION = "javax.ws.rs.Path"
        private const val PATH_ANNOTATION_JAKARTA = "jakarta.ws.rs.Path"
        private const val PATH_ANNOTATION_SHORT = "Path"

        // JAX-RS HTTP 方法注解
        private val JAXRS_HTTP_METHODS = mapOf(
            "javax.ws.rs.GET" to "GET",
            "javax.ws.rs.POST" to "POST",
            "javax.ws.rs.PUT" to "PUT",
            "javax.ws.rs.DELETE" to "DELETE",
            "javax.ws.rs.PATCH" to "PATCH",
            "javax.ws.rs.HEAD" to "HEAD",
            "javax.ws.rs.OPTIONS" to "OPTIONS",
            "jakarta.ws.rs.GET" to "GET",
            "jakarta.ws.rs.POST" to "POST",
            "jakarta.ws.rs.PUT" to "PUT",
            "jakarta.ws.rs.DELETE" to "DELETE",
            "jakarta.ws.rs.PATCH" to "PATCH",
            "jakarta.ws.rs.HEAD" to "HEAD",
            "jakarta.ws.rs.OPTIONS" to "OPTIONS"
        )

        // Spring MVC 路径注解（带 HTTP 方法信息）
        private val SPRING_MAPPING_ANNOTATIONS = mapOf(
            "org.springframework.web.bind.annotation.GetMapping" to "GET",
            "org.springframework.web.bind.annotation.PostMapping" to "POST",
            "org.springframework.web.bind.annotation.PutMapping" to "PUT",
            "org.springframework.web.bind.annotation.DeleteMapping" to "DELETE",
            "org.springframework.web.bind.annotation.PatchMapping" to "PATCH",
            "org.springframework.web.bind.annotation.RequestMapping" to ""
        )

        // Spring MVC 注解简称映射
        private val SPRING_ANNOTATION_SHORT_NAMES = mapOf(
            "GetMapping" to "GET",
            "PostMapping" to "POST",
            "PutMapping" to "PUT",
            "DeleteMapping" to "DELETE",
            "PatchMapping" to "PATCH",
            "RequestMapping" to ""
        )
    }

    /**
     * 提取方法的基础信息
     */
    fun extractMethodInfo(method: PsiMethod): MethodInfo {
        val qualifiedName = extractQualifiedName(method)
        val functionComment = extractFunctionComment(method)
        val classComment = extractClassComment(method)
        val (httpMethod, requestPath) = extractHttpMethodAndPath(method)

        logger.info("提取方法信息: qualifiedName=$qualifiedName, httpMethod=$httpMethod")

        return MethodInfo(
            httpMethod = httpMethod,
            requestPath = requestPath,
            qualifiedName = qualifiedName,
            classComment = classComment,
            functionComment = functionComment
        )
    }

    /**
     * 提取方法的全限定名
     * 格式：包名.类名.方法名
     */
    private fun extractQualifiedName(method: PsiMethod): String {
        val containingClass = method.containingClass
        val className = containingClass?.qualifiedName ?: return method.name
        return "$className.${method.name}"
    }

    /**
     * 提取方法的功能注释
     * 排除 @param、@return、@throws 等技术性注释
     * 如果是重写方法且无功能注释，则向上追溯
     */
    private fun extractFunctionComment(method: PsiMethod): String {
        // 尝试从当前方法获取功能注释
        val comment = getFunctionCommentFromDoc(method.docComment)
        if (comment.isNotEmpty()) {
            return comment
        }

        // 如果是重写方法，向上追溯
        if (isOverrideMethod(method)) {
            return findCommentFromSuperMethods(method)
        }

        return ""
    }

    /**
     * 提取类的功能注释
     * 排除 @param、@author、@version 等技术性注释
     * 如果是重写方法且类无功能注释，则向上追溯到定义该方法的父类或接口
     */
    private fun extractClassComment(method: PsiMethod): String {
        val containingClass = method.containingClass ?: return ""

        // 尝试从当前类获取功能注释
        val classComment = getClassFunctionComment(containingClass)
        if (classComment.isNotEmpty()) {
            return classComment
        }

        // 如果类无注释且方法是重写方法，向上追溯
        if (isOverrideMethod(method)) {
            return findClassCommentFromSuperMethods(method)
        }

        return ""
    }

    /**
     * 从类上获取功能性注释
     */
    private fun getClassFunctionComment(psiClass: PsiClass): String {
        val docComment = psiClass.docComment ?: return ""
        return getFunctionCommentFromDoc(docComment)
    }

    /**
     * 从父类或接口中查找类的功能注释
     */
    private fun findClassCommentFromSuperMethods(method: PsiMethod): String {
        val superMethods = method.findSuperMethods()

        for (superMethod in superMethods) {
            val superClass = superMethod.containingClass ?: continue
            val classComment = getClassFunctionComment(superClass)
            if (classComment.isNotEmpty()) {
                logger.info("从父类/接口 ${superClass.qualifiedName} 获取到类功能注释")
                return classComment
            }

            // 递归向上查找
            if (isOverrideMethod(superMethod)) {
                val parentClassComment = findClassCommentFromSuperMethods(superMethod)
                if (parentClassComment.isNotEmpty()) {
                    return parentClassComment
                }
            }
        }

        return ""
    }

    /**
     * 判断是否为重写方法
     */
    private fun isOverrideMethod(method: PsiMethod): Boolean {
        // 检查是否有 @Override 注解
        val hasOverrideAnnotation = method.modifierList.annotations.any { 
            it.qualifiedName == "java.lang.Override" || it.text?.contains("@Override") == true
        }
        
        if (hasOverrideAnnotation) {
            return true
        }

        // 检查是否覆盖了父类或接口的方法
        return method.findSuperMethods().isNotEmpty()
    }

    /**
     * 从父类或接口中查找方法的功能注释
     */
    private fun findCommentFromSuperMethods(method: PsiMethod): String {
        val superMethods = method.findSuperMethods()
        
        for (superMethod in superMethods) {
            val comment = getFunctionCommentFromDoc(superMethod.docComment)
            if (comment.isNotEmpty()) {
                logger.info("从父类/接口方法 ${superMethod.containingClass?.qualifiedName}.${superMethod.name} 获取到功能注释")
                return comment
            }
            
            // 递归向上查找
            if (isOverrideMethod(superMethod)) {
                val parentComment = findCommentFromSuperMethods(superMethod)
                if (parentComment.isNotEmpty()) {
                    return parentComment
                }
            }
        }

        return ""
    }

    /**
     * 从 PsiDocComment 中提取功能性描述
     * 排除 @param、@return、@throws 等技术性标签
     */
    private fun getFunctionCommentFromDoc(docComment: PsiDocComment?): String {
        if (docComment == null) {
            return ""
        }

        val technicalTags = setOf("param", "return", "throws", "exception", "see", "since", "version", "author", "deprecated")
        
        val descriptionBuilder = StringBuilder()
        
        for (element in docComment.descriptionElements) {
            val text = element.text.trim()
            if (text.isNotEmpty() && text != "*") {
                descriptionBuilder.append(text).append(" ")
            }
        }

        // 检查是否有非技术性的自定义标签（如功能说明标签）
        for (tag in docComment.tags) {
            if (tag.name.lowercase() !in technicalTags) {
                val tagText = getTagText(tag)
                if (tagText.isNotEmpty()) {
                    descriptionBuilder.append(tagText).append(" ")
                }
            }
        }

        return descriptionBuilder.toString().trim()
    }

    /**
     * 获取标签的文本内容
     */
    private fun getTagText(tag: PsiDocTag): String {
        val textElements = tag.dataElements
        return textElements.joinToString(" ") { it.text.trim() }.trim()
    }

    /**
     * 提取 HTTP 方法类型和请求路径
     * 支持 JAX-RS 和 Spring MVC 注解
     */
    private fun extractHttpMethodAndPath(method: PsiMethod): Pair<String, String> {
        val containingClass = method.containingClass ?: return Pair("", "")

        // 优先从当前方法和类获取
        val result = getHttpMethodAndPathFromAnnotations(method, containingClass)
        if (result.first.isNotEmpty() || result.second.isNotEmpty()) {
            return result
        }

        // 如果当前类是接口实现类，尝试从接口获取
        if (!containingClass.isInterface) {
            return findHttpMethodAndPathFromInterfaces(method, containingClass)
        }

        return Pair("", "")
    }

    /**
     * 从注解中获取 HTTP 方法和路径
     */
    private fun getHttpMethodAndPathFromAnnotations(method: PsiMethod, containingClass: PsiClass): Pair<String, String> {
        // 获取类级别路径
        val classPath = getPathFromAnnotations(containingClass.annotations)

        // 获取方法级别的 HTTP 方法和路径
        val (methodHttpMethod, methodPath) = getHttpMethodAndPathFromMethodAnnotations(method.annotations)

        // 如果方法没有 HTTP 方法注解，尝试从 JAX-RS 注解获取
        val httpMethod = if (methodHttpMethod.isEmpty()) {
            getJaxrsHttpMethod(method.annotations)
        } else {
            methodHttpMethod
        }

        if (classPath.isNotEmpty() || methodPath.isNotEmpty()) {
            return Pair(httpMethod, combinePaths(classPath, methodPath))
        }

        return Pair(httpMethod, "")
    }

    /**
     * 从方法注解中获取 HTTP 方法和路径
     */
    private fun getHttpMethodAndPathFromMethodAnnotations(annotations: Array<PsiAnnotation>): Pair<String, String> {
        for (annotation in annotations) {
            val qualifiedName = annotation.qualifiedName ?: continue

            // 检查 Spring MVC 注解
            SPRING_MAPPING_ANNOTATIONS[qualifiedName]?.let { httpMethod ->
                val path = getValueFromAnnotation(annotation)
                val resolvedHttpMethod = if (httpMethod.isEmpty()) {
                    // 对于 @RequestMapping，需要从 method 属性获取
                    getRequestMappingMethod(annotation)
                } else {
                    httpMethod
                }
                return Pair(resolvedHttpMethod, path)
            }

            // 检查简写名称
            val shortName = qualifiedName.substringAfterLast(".")
            SPRING_ANNOTATION_SHORT_NAMES[shortName]?.let { httpMethod ->
                val path = getValueFromAnnotation(annotation)
                val resolvedHttpMethod = if (httpMethod.isEmpty()) {
                    getRequestMappingMethod(annotation)
                } else {
                    httpMethod
                }
                return Pair(resolvedHttpMethod, path)
            }
        }

        // 检查 JAX-RS @Path 注解
        val jaxrsPath = getJaxrsPath(annotations)
        if (jaxrsPath.isNotEmpty()) {
            return Pair("", jaxrsPath)
        }

        return Pair("", "")
    }

    /**
     * 从 @RequestMapping 注解的 method 属性获取 HTTP 方法
     */
    private fun getRequestMappingMethod(annotation: PsiAnnotation): String {
        val methodAttr = annotation.findAttributeValue("method")
        val methodText = methodAttr?.text ?: return ""

        // 解析如 RequestMethod.POST 或 {RequestMethod.GET, RequestMethod.POST} 格式
        return when {
            methodText.contains("GET") -> "GET"
            methodText.contains("POST") -> "POST"
            methodText.contains("PUT") -> "PUT"
            methodText.contains("DELETE") -> "DELETE"
            methodText.contains("PATCH") -> "PATCH"
            methodText.contains("HEAD") -> "HEAD"
            methodText.contains("OPTIONS") -> "OPTIONS"
            else -> ""
        }
    }

    /**
     * 从 JAX-RS 注解获取 HTTP 方法
     */
    private fun getJaxrsHttpMethod(annotations: Array<PsiAnnotation>): String {
        for (annotation in annotations) {
            val qualifiedName = annotation.qualifiedName ?: continue
            JAXRS_HTTP_METHODS[qualifiedName]?.let { return it }

            // 检查简写名称
            val shortName = qualifiedName.substringAfterLast(".")
            if (shortName in listOf("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS")) {
                return shortName
            }
        }
        return ""
    }

    /**
     * 从 JAX-RS @Path 注解获取路径
     */
    private fun getJaxrsPath(annotations: Array<PsiAnnotation>): String {
        for (annotation in annotations) {
            val qualifiedName = annotation.qualifiedName
            if (qualifiedName == PATH_ANNOTATION ||
                qualifiedName == PATH_ANNOTATION_JAKARTA ||
                annotation.text?.startsWith("@$PATH_ANNOTATION_SHORT") == true) {
                return getValueFromAnnotation(annotation)
            }
        }
        return ""
    }

    /**
     * 从注解数组中获取路径（支持 JAX-RS 和 Spring MVC）
     */
    private fun getPathFromAnnotations(annotations: Array<PsiAnnotation>): String {
        for (annotation in annotations) {
            val qualifiedName = annotation.qualifiedName ?: continue

            // JAX-RS @Path
            if (qualifiedName == PATH_ANNOTATION ||
                qualifiedName == PATH_ANNOTATION_JAKARTA ||
                annotation.text?.startsWith("@$PATH_ANNOTATION_SHORT") == true) {
                return getValueFromAnnotation(annotation)
            }

            // Spring @RequestMapping
            if (qualifiedName == "org.springframework.web.bind.annotation.RequestMapping" ||
                qualifiedName.endsWith(".RequestMapping")) {
                return getValueFromAnnotation(annotation)
            }
        }
        return ""
    }

    /**
     * 从注解中获取 value 属性
     */
    private fun getValueFromAnnotation(annotation: PsiAnnotation): String {
        val value = annotation.findAttributeValue("value")
            ?: annotation.findAttributeValue("path")

        val text = value?.text ?: return ""

        // 处理数组格式，如 {"/path1", "/path2"}，取第一个
        return if (text.startsWith("{") && text.endsWith("}")) {
            text.removeSurrounding("{", "}")
                .split(",")
                .firstOrNull()
                ?.trim()
                ?.removeSurrounding("\"") ?: ""
        } else {
            text.removeSurrounding("\"")
        }
    }

    /**
     * 从接口中查找 HTTP 方法和路径
     */
    private fun findHttpMethodAndPathFromInterfaces(method: PsiMethod, containingClass: PsiClass): Pair<String, String> {
        for (interfaceClass in containingClass.interfaces) {
            val interfaceMethod = interfaceClass.findMethodsByName(method.name, false)
                .firstOrNull { matchesSignature(it, method) }

            if (interfaceMethod != null) {
                val result = getHttpMethodAndPathFromAnnotations(interfaceMethod, interfaceClass)
                if (result.first.isNotEmpty() || result.second.isNotEmpty()) {
                    logger.info("从接口 ${interfaceClass.qualifiedName} 获取到请求信息")
                    return result
                }
            }
        }

        // 递归检查父类
        val superClass = containingClass.superClass
        if (superClass != null && superClass.qualifiedName != "java.lang.Object") {
            return findHttpMethodAndPathFromInterfaces(method, superClass)
        }

        return Pair("", "")
    }

    /**
     * 检查两个方法的签名是否匹配
     */
    private fun matchesSignature(method1: PsiMethod, method2: PsiMethod): Boolean {
        if (method1.name != method2.name) {
            return false
        }

        val params1 = method1.parameterList.parameters
        val params2 = method2.parameterList.parameters

        if (params1.size != params2.size) {
            return false
        }

        for (i in params1.indices) {
            val type1 = params1[i].type.canonicalText
            val type2 = params2[i].type.canonicalText
            if (type1 != type2) {
                return false
            }
        }

        return true
    }


    /**
     * 组合类级别路径和方法级别路径
     */
    private fun combinePaths(classPath: String, methodPath: String): String {
        if (classPath.isEmpty() && methodPath.isEmpty()) {
            return ""
        }

        val normalizedClassPath = classPath.trimEnd('/')
        val normalizedMethodPath = if (methodPath.startsWith("/")) methodPath else "/$methodPath"

        return if (classPath.isEmpty()) {
            normalizedMethodPath.trimStart('/')
        } else if (methodPath.isEmpty()) {
            normalizedClassPath
        } else {
            "$normalizedClassPath$normalizedMethodPath"
        }
    }
}
