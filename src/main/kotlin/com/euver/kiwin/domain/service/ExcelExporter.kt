package com.euver.kiwin.domain.service

import java.io.File

/**
 * Excel 导出器接口
 * 领域层定义的抽象接口，由基础设施层实现
 * 
 * 遵循 DDD 依赖倒置原则：领域层定义接口，基础设施层提供实现
 */
interface ExcelExporter {
    
    /**
     * 导出数据到 Excel 文件
     * 
     * @param file 目标文件
     * @param data 导出数据
     */
    fun export(file: File, data: ExcelExportData)
}

/**
 * Excel 导出数据模型
 */
data class ExcelExportData(
    val sheetName: String,
    val headers: List<String>,
    val rows: List<List<Any?>>,
    val mergeRegions: List<MergeRegion> = emptyList(),
    val headerStyle: HeaderStyle = HeaderStyle()
)

/**
 * 合并区域定义
 * @param firstRow 起始行（0-based，不含表头）
 * @param lastRow 结束行（0-based，不含表头）
 * @param firstCol 起始列
 * @param lastCol 结束列
 */
data class MergeRegion(
    val firstRow: Int,
    val lastRow: Int,
    val firstCol: Int,
    val lastCol: Int
)

/**
 * 表头样式
 */
data class HeaderStyle(
    val bold: Boolean = true,
    val fillColor: String = "DDEBF7"
)
