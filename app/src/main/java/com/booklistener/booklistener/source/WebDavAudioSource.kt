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

/** WebDAV 子目录条目（目录导航用，v1.6.27）：name 为解码后目录名，href 为完整 URL 供下一层 PROPFIND */
data class WebDavDirEntry(
    val name: String,
    val href: String
)

/**
 * 把 WebDAV href 解析为完整 URL（v1.6.28 播放/导航必需）：
 * 相对服务器根（"/dav/..."）与相对当前目录（"x/…"）都基于 PROPFIND 的 base 补全。
 */
internal fun resolveWebDavHref(base: String, href: String): String {
    if (href.startsWith("http://") || href.startsWith("https://")) return href
    return runCatching { java.net.URI(base).resolve(href).toString() }.getOrElse { href }
}

class WebDavAudioSource(private val credentialProvider: () -> WebDavCredential?) : AudioSource {

    private val baseUrl: String get() = credentialProvider()?.baseUrl.orEmpty()

    override suspend fun listDirectory(path: String): Result<List<AudioItem>> =
        probeDirectory(path).map { it.first }

    /** 列举目录并同时统计子目录数量（不含根自身），供"测试连接"区分"根目录无音频但有子目录" */
    suspend fun probeDirectory(url: String): Result<Pair<List<AudioItem>, Int>> =
        listDirEntries(url).map { (dirs, items) -> items to dirs.size }

    /** 列举目录：返回 (子目录列表, 音频列表)；url 为完整目录 URL（账号根或子目录，v1.6.28） */
    suspend fun listDirEntries(url: String): Result<Pair<List<WebDavDirEntry>, List<AudioItem>>> = runCatching {
        val cred = credentialProvider()
            ?: throw AudioSourceException.AuthFailed("WebDAV not configured")
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
            parseWebDavDirEntries(xml, url)
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
 * v1.6.27：泛化返回 (音频列表, 子目录计数)（不含根自身），供"测试连接"区分
 * "根目录无音频但有子目录"的误报场景。
 */
internal fun parseWebDavMultiStatus(xml: String, base: String): List<AudioItem> =
    parseWebDavDirEntries(xml, base).second

/** 解析 multistatus 并同时统计子目录数量（根自身不计入），目录计数供测试连接提示用 */
internal fun parseWebDavMultiStatusDetailed(xml: String, base: String): Pair<List<AudioItem>, Int> {
    val (dirs, items) = parseWebDavDirEntries(xml, base)
    return items to dirs.size
}

/** 解析 multistatus 为 (子目录列表, 音频列表)（v1.6.27 目录导航）：目录名解码、href 保留供下一层 PROPFIND */
internal fun parseWebDavDirEntries(xml: String, base: String): Pair<List<WebDavDirEntry>, List<AudioItem>> {
    val parser = Xml.newPullParser()
    parser.setInput(StringReader(xml))
    val items = mutableListOf<AudioItem>()
    val dirs = mutableListOf<WebDavDirEntry>()
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

    /** href 是否指向目录根自身：兼容完整 URL 与相对路径（提取 path 比较，忽略 scheme/host） */
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
                    "response" -> { inResponse = true; resetFields() }
                    "href" -> currentHref = parser.nextText()
                    "displayname" -> currentDisplayName = parser.nextText()
                    "getcontentlength" -> currentLength = parser.nextText().toLongOrNull() ?: 0L
                    "collection" -> isCollection = true
                }
            }
            XmlPullParser.END_TAG -> {
                if (localTag(parser.name) == "response" && inResponse) {
                    val rawHref = currentHref
                    // 相对 href（"/dav/x/" 或 "x/"）→ 完整 URL；根自身判定在 resolve 后进行
                    val href = resolveWebDavHref(base, rawHref)
                    if (href.isNotEmpty() && !isSelf(href, base)) {
                        if (isCollection || href.endsWith("/")) {
                            // 目录条目：子目录（非根自身）收集为 WebDavDirEntry；目录名解码
                            val dirName = currentDisplayName.ifEmpty {
                                android.net.Uri.decode(href.trimEnd('/').substringAfterLast('/'))
                            }
                            dirs.add(WebDavDirEntry(name = dirName, href = href))
                        } else {
                            // displayname 缺省时回退 href 文件名并做 URL 解码（%E7%AC%AC…→第一章）
                            val name = currentDisplayName.ifEmpty {
                                android.net.Uri.decode(rawHref.substringAfterLast('/'))
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
                    }
                    inResponse = false
                }
            }
        }
        event = parser.next()
    }
    val sortedItems = items.sortedWith(compareBy(NaturalOrder.bySortKey) { it.displayName })
    return dirs to sortedItems
}
