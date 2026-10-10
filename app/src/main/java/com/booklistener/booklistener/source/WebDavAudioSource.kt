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
)

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

    private fun normalize(url: String): String {
        val trimmed = url.trimEnd('/')
        return if (trimmed.endsWith("dav", ignoreCase = true) ||
            trimmed.endsWith("remote.php", ignoreCase = true) ||
            trimmed.endsWith("webdav", ignoreCase = true)
        ) "$trimmed/" else "$trimmed/"
    }

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
    private fun parseMultiStatus(xml: String, base: String): List<AudioItem> {
        val parser = Xml.newPullParser()
        parser.setInput(StringReader(xml))
        val items = mutableListOf<AudioItem>()
        var currentHref = ""
        var currentDisplayName = ""
        var currentLength = 0L
        var isCollection = false
        var inResponse = false

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
                    when (parser.name) {
                        "response" -> { inResponse = true; resetFields() }
                        "href" -> currentHref = parser.nextText()
                        "displayname" -> currentDisplayName = parser.nextText()
                        "getcontentlength" -> currentLength = parser.nextText().toLongOrNull() ?: 0L
                        "collection" -> isCollection = true
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == "response" && inResponse) {
                        val href = currentHref
                        if (href.isNotEmpty() && href != base && !href.endsWith("/")) {
                            val name = currentDisplayName.ifEmpty { href.substringAfterLast('/') }
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

    private fun mapHttpError(resp: Response): AudioSourceException {
        return when (resp.code) {
            401, 403 -> AudioSourceException.AuthFailed("WebDAV auth failed (${resp.code})")
            429 -> AudioSourceException.NetworkLimit("WebDAV rate limited (429)")
            else -> AudioSourceException.NetworkError("WebDAV error ${resp.code}", null)
        }
    }
}
