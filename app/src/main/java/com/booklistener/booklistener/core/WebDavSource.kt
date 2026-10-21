package com.booklistener.core

import com.booklistener.source.WebDavCredential
import com.booklistener.source.resolveWebDavHref
import okhttp3.Credentials
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

/**
 * 网盘来源（WebDAV）实现。
 *
 * 只负责递归列目录（PROPFIND Depth:1 逐层），返回 SourceFile 列表（含相对路径）；
 * 不做标签解析、CUE 收集、索引写入。
 *
 * @param credentialProvider 按目录 URL 返回匹配的账号凭证（支持同账号多子目录）
 */
class WebDavSource(
    private val credentialProvider: (url: String) -> WebDavCredential?,
    private val maxDepth: Int = 10
) : Source {

    override val type: SourceType = SourceType.WEBDAV

    override suspend fun listFiles(rootUri: String): List<SourceFile> {
        val out = mutableListOf<SourceFile>()
        walk(rootUri, "", 0, out)
        return out
    }

    private suspend fun walk(
        url: String,
        dirRelativePath: String,
        depth: Int,
        out: MutableList<SourceFile>
    ) {
        if (depth > maxDepth) return
        val cred = credentialProvider(url) ?: return
        val body = """<?xml version="1.0" encoding="utf-8"?>
<d:propfind xmlns:d="DAV:">
  <d:prop>
    <d:displayname/>
    <d:getcontentlength/>
    <d:resourcetype/>
    <d:getlastmodified/>
  </d:prop>
</d:propfind>""".trimIndent()
        val request = Request.Builder()
            .url(url)
            .method("PROPFIND", body.toRequestBody("application/xml".toMediaTypeOrNull()))
            .header("Depth", "1")
            .header("Authorization", Credentials.basic(cred.username, cred.password))
            .build()

        val entries = runCatching {
            com.booklistener.net.OkHttpHolder.client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@use emptyList()
                val xml = resp.body?.string().orEmpty()
                parseAll(xml, url)
            }
        }.getOrElse { emptyList() }

        for (entry in entries) {
            val (name, href, isDir, size) = entry
            val fileRelativePath = if (dirRelativePath.isEmpty()) "" else dirRelativePath
            out.add(
                SourceFile(
                    name = name,
                    path = href,
                    relativePath = fileRelativePath,
                    isDirectory = isDir,
                    sizeBytes = size
                )
            )
            if (isDir) {
                val childPath = if (dirRelativePath.isEmpty()) name else "$dirRelativePath/$name"
                walk(href, childPath, depth + 1, out)
            }
        }
    }

    /** 解析 multistatus 为全部条目（文件+目录，含非音频）：返回 (name, href, isDir, size) */
    private fun parseAll(
        xml: String,
        base: String
    ): List<Quad> {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = false
        val parser = factory.newPullParser()
        parser.setInput(StringReader(xml))
        val result = mutableListOf<Quad>()
        var href = ""
        var displayName = ""
        var length = 0L
        var isCollection = false
        var inResponse = false

        fun localTag(name: String?): String {
            if (name.isNullOrEmpty()) return ""
            val idx = name.indexOf(':')
            return if (idx >= 0) name.substring(idx + 1) else name
        }

        fun isSelf(href: String, base: String): Boolean {
            fun pathOf(u: String): String {
                val s = u.trim().trimEnd('/')
                val afterScheme = if (s.contains("://")) s.substring(s.indexOf("://") + 3) else s
                val slash = afterScheme.indexOf('/')
                return if (slash >= 0) afterScheme.substring(slash) else "/"
            }
            return pathOf(href) == pathOf(base)
        }

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    when (localTag(parser.name)) {
                        "response" -> {
                            inResponse = true; href = ""; displayName = ""; length = 0L; isCollection = false
                        }
                        "href" -> href = parser.nextText()
                        "displayname" -> displayName = parser.nextText()
                        "getcontentlength" -> length = parser.nextText().toLongOrNull() ?: 0L
                        "collection" -> isCollection = true
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (localTag(parser.name) == "response" && inResponse) {
                        val rawHref = href
                        val resolved = resolveWebDavHref(base, rawHref)
                        if (resolved.isNotEmpty() && !isSelf(resolved, base)) {
                            val dir = isCollection || resolved.endsWith("/")
                            val name = displayName.ifEmpty {
                                android.net.Uri.decode(resolved.trimEnd('/').substringAfterLast('/'))
                            }
                            if (name.isNotEmpty()) {
                                result.add(Quad(name, resolved, dir, length))
                            }
                        }
                        inResponse = false
                    }
                }
            }
            event = parser.next()
        }
        return result
    }

    private data class Quad(
        val name: String,
        val href: String,
        val isDir: Boolean,
        val size: Long
    )
}
