package com.booklistener.core

import android.content.Context
import com.booklistener.auth.CredentialStore
import com.booklistener.net.OkHttpHolder
import com.booklistener.source.WebDavCredential
import com.booklistener.source.normalizeWebDavUrl
import okhttp3.Credentials
import okhttp3.Request

/**
 * 按来源类型构造 [ScanCoordinator]（v2.2.0）。
 * 本地/网盘的差异只在 Source 与读取器实现，扫描流程一致。
 */
class ScanCoordinatorFactory(
    private val context: Context,
    private val credentialStore: CredentialStore,
    private val indexStore: EntryIndexStore,
    private val coverManager: CoverManager? = null
) {

    /** 本地：SAF 列目录 + MediaMetadataRetriever 标签 + SAF 读 cue + 内嵌封面提取 */
    fun forLocal(): ScanCoordinator {
        val tagResolver = TagResolver(context)
        return ScanCoordinator(
            source = LocalSource(context),
            tagReader = { file -> tagResolver.resolve(android.net.Uri.parse(file.path)) },
            cueTextReader = { file -> readSafText(file.path) },
            indexStore = indexStore,
            coverExtractor = coverManager?.let { mgr -> { uri -> mgr.extractCover(uri) } }
        )
    }

    /** 网盘：PROPFIND 列目录，无内嵌标签（tagReader 恒 null），GET 读 cue，无内嵌封面 */
    fun forWebDav(): ScanCoordinator {
        return ScanCoordinator(
            source = WebDavSource(credentialProvider = { url -> credentialFor(url) }),
            tagReader = { null },
            cueTextReader = { file -> readWebDavText(file.path) },
            indexStore = indexStore,
            coverExtractor = null
        )
    }

    private fun credentialFor(url: String): WebDavCredential? {
        val norm = normalizeWebDavUrl(url)
        return credentialStore.webDavAccounts().firstOrNull { norm.startsWith(it.normalizedUrl) }
    }

    private fun readSafText(uri: String): String? = runCatching {
        context.contentResolver.openInputStream(android.net.Uri.parse(uri))
            ?.use { CueTextDecoder.decode(it.readBytes()) }
    }.getOrNull()

    private fun readWebDavText(url: String): String? = runCatching {
        val cred = credentialFor(url) ?: return null
        val request = Request.Builder()
            .url(url)
            .header("Authorization", Credentials.basic(cred.username, cred.password))
            .build()
        OkHttpHolder.client.newCall(request).execute().use { resp ->
            if (resp.isSuccessful) CueTextDecoder.decode(resp.body?.bytes() ?: ByteArray(0)) else null
        }
    }.getOrNull()
}
