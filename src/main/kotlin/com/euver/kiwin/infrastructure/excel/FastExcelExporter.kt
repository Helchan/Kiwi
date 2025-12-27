package com.euver.kiwin.infrastructure.excel

import com.euver.kiwin.domain.service.ExcelExportData
import com.euver.kiwin.domain.service.ExcelExporter
import org.dhatim.fastexcel.Workbook
import org.dhatim.fastexcel.Worksheet
import java.io.File
import java.io.FileOutputStream

/**
 * FastExcel 实现的 Excel 导出器
 * 基础设施层实现，使用轻量级 FastExcel 库
 */
class FastExcelExporter : ExcelExporter {
    
    override fun export(file: File, data: ExcelExportData) {
        FileOutputStream(file).use { os ->
            Workbook(os, "Kiwin", "1.0").use { workbook ->
                val worksheet = workbook.newWorksheet(data.sheetName)
                
                writeHeader(worksheet, data)
                writeData(worksheet, data)
                applyMergeRegions(worksheet, data)
                adjustColumnWidths(worksheet, data)
            }
        }
    }
    
    private fun writeHeader(worksheet: Worksheet, data: ExcelExportData) {
        data.headers.forEachIndexed { colIndex, header ->
            worksheet.value(0, colIndex, header)
            worksheet.style(0, colIndex)
                .bold()
                .fillColor(data.headerStyle.fillColor)
                .set()
        }
    }
    
    private fun writeData(worksheet: Worksheet, data: ExcelExportData) {
        data.rows.forEachIndexed { rowIndex, row ->
            row.forEachIndexed { colIndex, value ->
                when (value) {
                    is Number -> worksheet.value(rowIndex + 1, colIndex, value)
                    is Boolean -> worksheet.value(rowIndex + 1, colIndex, value)
                    null -> worksheet.value(rowIndex + 1, colIndex, "")
                    else -> worksheet.value(rowIndex + 1, colIndex, value.toString())
                }
            }
        }
    }
    
    private fun applyMergeRegions(worksheet: Worksheet, data: ExcelExportData) {
        data.mergeRegions.forEach { region ->
            worksheet.range(
                region.firstRow + 1,
                region.firstCol,
                region.lastRow + 1,
                region.lastCol
            ).merge()
        }
    }
    
    private fun adjustColumnWidths(worksheet: Worksheet, data: ExcelExportData) {
        val columnCount = data.headers.size
        for (colIndex in 0 until columnCount) {
            var maxLength = data.headers[colIndex].length
            
            data.rows.forEach { row ->
                if (colIndex < row.size) {
                    val valueLength = row[colIndex]?.toString()?.length ?: 0
                    maxLength = maxOf(maxLength, valueLength)
                }
            }
            
            val width = minOf(maxLength + 4, 50)
            worksheet.width(colIndex, width.toDouble())
        }
    }
}
