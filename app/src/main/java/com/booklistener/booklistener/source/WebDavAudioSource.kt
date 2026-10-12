package com.booklistener.source

import android.util.Xml
import com.booklistener.core.NaturalOrder
import com.booklistener.core.AudioMetaDefaults
import com.booklistener.core.AudioItemFactory
import com.booklistener.core.AudioExt
import com.booklistener.core.AudioItem
import com.booklistener.core.AudioMeta
import com.booklistener.core.AudioSource
import com.booklistener.core.AudioSourceException
import com.booklistener.core.SourceType
import com.booklistener.net.OkHttpHolder
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader

/**
 * 标准 RFC4918 WebDAV 数据源（兼容 Nextcloud / InfiniCLOUD / 中国科技云 / 自建 NAS）。
 * 只实现标准 PROPFIND / GET，不做私有扩展兼容。
 * 凭证（地址、用户名、密码）由 CredentialStore 提供。
 */
data class WebDavCredential(
    val baseUrl: String,
    val username: String,
    val password: String
) {
    /** 归一化地址（去空白/尾斜杠后统一补 "/"），多账号匹配与条目 id 共用 */
    val normalizedUrl: String get() = normalizeWebDavUrl(baseUrl)

    /** 显示名：URL 主机名（取不到时回退完整地址） */
    val host: String get() = runCatching { java.net.URI(baseUrl).host }.getOrNull() ?: baseUrl
}

/** WebDAV 地址归一化：去空白与多余尾斜杠，统一以 "/" 结尾（v1.6.25 账号匹配用） */
fun normalizeWebDavUrl(url: String): String {
    val trimmed = url.trim().trimEnd('/')
    return "$trimmed/"
}

class WebDavAudioSource(private val credentialProvider: () -> WebDavCredential?) : AudioSource {

    private val baseUrl: String get() = credentialProvider()?.baseUrl.orEmpty()

    override suspend fun listDirectory(path: String): Result<List<AudioItem>> = runCatching {
        val cred = credentialProvider()
            ?: throw AudioSourceException.AuthFailed("WebDAV not configured")
        val url = if (path.isEmpty()) normalize(cred.baseUrl) else path
        val body = propfindBody()
        val request = Request.Builder()
            .url(url)
            .method("PROPFIND", body.toRequestBody("application/xml".toMediaTypeOrNull()))
            .header("Depth", "1")
            .header("Authorization", Credentials.basic(cred.username, cred.password))
            .build()
        OkHttpHolder.client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw mapHttpError(resp)
            val xml = resp.body?.string().orEmpty()
            parseMultiStatus(xml, url)
        }
    }

    override suspend fun getPlayUri(item: AudioItem): Result<String> = runCatching {
        if (item.sourceType != SourceType.WEBDAV) {
            throw AudioSourceException.NotFound("item not webdav")
        }
        item.path
    }

    override suspend fun refreshCredential(): Result<Unit> = Result.success(Unit)

    override suspend fun getMeta(item: AudioItem): Result<AudioMeta> = runCatching {
        AudioMeta(item.displayName, AudioMetaDefaults.UNKNOWN_ARTIST, 0L)
    }

    private fun normalize(url: String): String = normalizeWebDavUrl(url)

    private fun propfindBody(): String {
        return """<?xml version="1.0" encoding="utf-8"?>
<d:propfind xmlns:d="DAV:">
  <d:prop>
    <d:displayname/>
    <d:getcontentlength/>
    <d:resourcetype/>
  </d:prop>
</d:propfind>""".trimIndent()
    }

    /**
     * 解析 PROPFIND 的 multistatus 响应，提取当前目录下的音频文件。
     * href 为完整 URL，可直接用于 GET（支持 Range）。
     */
    private fun parseMultiStatus(xml: String, base: String): List<AudioItem> =
        parseWebDavMultiStatus(xml, base)

    private fun mapHttpError(resp: Response): AudioSourceException {
        return when (resp.code) {
            401, 403 -> AudioSourceException.AuthFailed("WebDAV auth failed (${resp.code})")
            429 -> AudioSourceException.NetworkLimit("WebDAV rate limited (429)")
            else -> AudioSourceException.NetworkError("WebDAV error ${resp.code}", null)
        }
    }
}

/**
 * WebDAV multistatus 响应解析（v1.6.26 修复：XmlPullParser 未开启 namespace 处理时
 * parser.name 返回带前缀的 "d:response" 等，导致所有分支失配、目录永远解析为空。
 * 现统一取局部名匹配；并用 resourcetype/collection 排除目录条目）。
 */
internal fun parseWebDavMultiStatus(xml: String, base: String): List<AudioItem> {
    val parser = Xml.newPullParser()
    parser.setInput(StringReader(xml))
    val items = mutableListOf<AudioItem>()
    var currentHref = ""
    var currentDisplayName = ""
    var currentLength = 0L
    var isCollection = false
    var inResponse = false

    /**
     * 取元素局部名（去命名空间前缀）："d:response" → "response"、"response" → "response"。
     * 注意不能用 substringAfter(':', "")：它在无冒号时返回 missingDelimiterValue 而非原串，
     * 会导致无前缀标签全部失配（v1.6.26 回归教训，已有 UT 覆盖）。
     */
    fun localTag(name: String?): String {
        if (name.isNullOrEmpty()) return ""
        val idx = name.indexOf(':')
        return if (idx >= 0) name.substring(idx + 1) else name
    }

    fun resetFields() {
        currentHref = ""
        currentDisplayName = ""
        currentLength = 0L
        isCollection = false
    }

    var event = parser.eventType
    while (event != XmlPullParser.END_DOCUMENT) {
        when (event) {
            XmlPullParser.START_TAG -> {
                when (localTag(parser.name)) {
                    "response" -> { inResponse = true; resetFields() }
                    "href" -> currentHref = parser.nextText()
                    "displayname" -> currentDisplayName = parser.nextText()
                    "getcontentlength" -> currentLength = parser.nextText().toLongOrNull() ?: 0L
                    "collection" -> isCollection = true
                }
            }
            XmlPullParser.END_TAG -> {
                if (localTag(parser.name) == "response" && inResponse) {
                    val href = currentHref
                    // 目录条目（href 以 "/" 结尾 或 resourcetype 含 collection）不入音频列表
                    if (href.isNotEmpty() && href != base && !href.endsWith("/") && !isCollection) {
                        // displayname 缺省时回退 href 文件名并做 URL 解码（%E7%AC%AC…→第一章）
                        val name = currentDisplayName.ifEmpty {
                            android.net.Uri.decode(href.substringAfterLast('/'))
                        }
                        if (AudioExt.isAudio(name)) {
                            val display = name.substringBeforeLast('.').takeUnless { it.isEmpty() } ?: name
                            items.add(
                                AudioItemFactory.fromWebDav(
                                    href = href,
                                    displayName = display,
                                    size = currentLength,
                                    rawName = name
                                )
                            )
                        }
                    }
                    inResponse = false
                }
            }
        }
        event = parser.next()
    }
    return items.sortedWith(compareBy(NaturalOrder.bySortKey) { it.displayName })
}
