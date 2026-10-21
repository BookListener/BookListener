package com.booklistener.core

/**
 * 来源统一抽象（v2.2.0 设置侧链路重构）。
 *
 * Source 实现只负责"列目录"，返回原始文件列表；
 * 不做标签解析、CUE 收集、索引写入、作品聚合——这些由 ScanCoordinator 统一处理。
 * 新增来源（如阿里云盘）只需实现本接口。
 */
interface Source {

    /** 该来源类型标识（LOCAL / WEBDAV） */
    val type: SourceType

    /**
     * 递归列出顶层文件夹下所有文件（含子文件夹）。
     * @param rootUri 顶层文件夹标识（SAF tree uri 或网盘根 URL）
     * @return 原始文件列表（含目录条目，由调用方决定如何处理）
     */
    suspend fun listFiles(rootUri: String): List<SourceFile>
}

/**
 * 统一的文件描述（来源无关）。
 *
 * @param name 文件名（不含路径，含扩展名）
 * @param path 完整可访问路径（SAF document uri 或网盘完整 URL）
 * @param relativePath 相对顶层文件夹的目录路径（根目录文件为 ""；子文件夹为 "CD1"、"艺人/专辑"）
 * @param isDirectory 是否为目录
 * @param sizeBytes 文件大小（目录为 0；未知为 null）
 * @param lastModified 修改时间（未知为 null）
 */
data class SourceFile(
    val name: String,
    val path: String,
    val relativePath: String,
    val isDirectory: Boolean,
    val sizeBytes: Long? = null,
    val lastModified: Long? = null
) {
    /** 扩展名（小写，不含点；无扩展名返回空串） */
    val extension: String
        get() = name.substringAfterLast('.', "").lowercase()
}
