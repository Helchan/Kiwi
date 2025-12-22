package com.euver.kiwi.service

import com.euver.kiwi.parser.MyBatisXmlParser
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.xml.XmlFile
import com.intellij.ide.highlighter.XmlFileType

/**
 * Mapper 索引服务
 * 负责维护项目中 MyBatis Mapper 文件的索引,建立 namespace 到文件的映射
 */
@Service(Service.Level.PROJECT)
class MapperIndexService(private val project: Project) {

    private val logger = thisLogger()
    private val parser = MyBatisXmlParser()

    // namespace 到 XmlFile 的映射缓存
    private val namespaceToFileCache = mutableMapOf<String, XmlFile>()

    /**
     * 根据 namespace 查找对应的 Mapper XML 文件
     */
    fun findMapperFileByNamespace(namespace: String): XmlFile? {
        // 先从缓存中查找
        namespaceToFileCache[namespace]?.let { return it }

        // 缓存未命中,扫描项目中的所有 XML 文件
        buildIndexIfNeeded()
        return namespaceToFileCache[namespace]
    }

    /**
     * 查找项目中所有的 Mapper XML 文件
     */
    fun findAllMapperFiles(): List<XmlFile> {
        val mapperFiles = mutableListOf<XmlFile>()
        val psiManager = PsiManager.getInstance(project)

        // 使用 FileTypeIndex 查找所有 XML 文件
        val xmlFiles = FileTypeIndex.getFiles(
            XmlFileType.INSTANCE,
            GlobalSearchScope.projectScope(project)
        )

        for (virtualFile in xmlFiles) {
            val psiFile = psiManager.findFile(virtualFile) as? XmlFile ?: continue
            if (isMapperFile(psiFile)) {
                mapperFiles.add(psiFile)
            }
        }

        logger.info("Found ${mapperFiles.size} MyBatis Mapper XML files in project")
        return mapperFiles
    }

    /**
     * 构建索引(如果需要)
     */
    private fun buildIndexIfNeeded() {
        if (namespaceToFileCache.isNotEmpty()) {
            return
        }

        logger.info("Building MyBatis Mapper index...")
        val mapperFiles = findAllMapperFiles()

        for (mapperFile in mapperFiles) {
            val namespace = parser.extractNamespace(mapperFile)
            if (namespace != null) {
                namespaceToFileCache[namespace] = mapperFile
            }
        }

        logger.info("Index built: ${namespaceToFileCache.size} namespaces indexed")
    }

    /**
     * 清空缓存
     */
    fun clearCache() {
        namespaceToFileCache.clear()
        logger.info("Mapper index cache cleared")
    }

    /**
     * 判断是否为 MyBatis Mapper 文件
     */
    private fun isMapperFile(xmlFile: XmlFile): Boolean {
        val rootTag = xmlFile.rootTag ?: return false
        return rootTag.name == "mapper" && rootTag.getAttributeValue("namespace") != null
    }

    companion object {
        /**
         * 获取服务实例
         */
        fun getInstance(project: Project): MapperIndexService {
            return project.getService(MapperIndexService::class.java)
        }
    }
}
